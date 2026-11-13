package io.github.samzhu.bot;

import io.github.samzhu.bot.usage.WorkspaceAdmission;
import io.github.samzhu.bot.usage.WorkspaceMonthlyBudgetOperations;
import io.github.samzhu.bot.conversation.ConversationOperations;
import io.github.samzhu.bot.identity.CurrentUserProvider;
import io.github.samzhu.bot.workspace.WorkspaceOperations;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.UUID;
import java.time.YearMonth;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class WorkspaceAdmissionPublicTests {
    @Autowired WorkspaceAdmission admission;
    @Autowired io.github.samzhu.bot.usage.UsageLedger ledger;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean CurrentUserProvider actor;
    @Autowired ConversationOperations conversations;
    @Autowired WorkspaceOperations workspaces;
    @Autowired WorkspaceMonthlyBudgetOperations budgets;
    @Autowired javax.sql.DataSource dataSource;

    @Test void authorizedRegistrationIsDurableAndBindsItsScope() {
        String owner = "admission-" + UUID.randomUUID();
        String workspace = workspaces.personal(owner).id();
        String bot = "admission-bot-" + UUID.randomUUID();
        jdbc.update("insert into bot(id,name,target) values (?,?,'LOCAL')", bot, bot);
        var conversation = conversations.createConversation(bot, "fixture", owner, workspace);
        var request = new WorkspaceAdmission.Register(workspace, "run-" + UUID.randomUUID(), owner,
                conversation.id(), WorkspaceAdmission.Partition.REAL);
        var result = admission.register(request).toCompletableFuture().join();
        var registration = assertInstanceOf(WorkspaceAdmission.RegisterResult.Registered.class, result).registration();
        assertEquals(workspace, registration.workspaceId());
        assertEquals(owner, registration.actorId());
        assertEquals(request.runId(), registration.runId());
        assertEquals("REGISTERED", jdbc.queryForObject(
                "select status from workspace_admission_registration where token=?", String.class, registration.token()));
        var forged = new WorkspaceAdmission.Registration(registration.token(), workspace, request.runId(),
                "outsider", conversation.id(), WorkspaceAdmission.Partition.REAL);
        assertEquals(WorkspaceAdmission.CancellationState.UNKNOWN,
                admission.cancellationState(forged).toCompletableFuture().join().state());
    }

    @Test void publishedRegistrationClaimsPreparedAttemptBeforeProviderStart() {
        String owner = "claim-owner-" + UUID.randomUUID();
        String workspace = workspaces.personal(owner).id();
        String bot = "claim-bot-" + UUID.randomUUID();
        jdbc.update("insert into bot(id,name,target) values (?,?,'LOCAL')", bot, bot);
        var conversation = conversations.createConversation(bot, "fixture", owner, workspace);
        String run = "claim-run-" + UUID.randomUUID();
        var request = new WorkspaceAdmission.Register(workspace, run, owner, conversation.id(), WorkspaceAdmission.Partition.REAL);
        var registration = assertInstanceOf(WorkspaceAdmission.RegisterResult.Registered.class,
                admission.register(request).toCompletableFuture().join()).registration();
        jdbc.update("insert into chat_run(id,conversation_id,client_request_id,input_text,status) values (?,?,?,'test','PENDING')",run,conversation.id(),UUID.randomUUID().toString());
        assertInstanceOf(WorkspaceAdmission.PublicationResult.Ready.class, admission.publish(registration).toCompletableFuture().join());
        String attempt = ledger.begin(new io.github.samzhu.bot.usage.UsageLedger.Dispatch(run,workspace,owner,"FAKE","test","model","FAKE","1",false,"UNKNOWN"));
        var allowed = assertInstanceOf(WorkspaceAdmission.Decision.Allowed.class,
                admission.dispatch(new WorkspaceAdmission.Dispatch(registration,attempt)).toCompletableFuture().join());
        assertEquals(attempt, allowed.claim().attemptId());
        var recovered = assertInstanceOf(WorkspaceAdmission.ClaimInspection.Found.class,
                admission.inspectClaim(registration, attempt).toCompletableFuture().join());
        assertEquals(allowed.claim(), recovered.claim());
        assertNotNull(jdbc.queryForObject("select started_at from provider_attempt where id=?", java.sql.Timestamp.class, attempt));
        assertInstanceOf(WorkspaceAdmission.ProviderStartResult.Ready.class,
                admission.authorizeProviderStart(allowed.claim()).toCompletableFuture().join());
        var cancelled = admission.cancelRun(registration).toCompletableFuture().join();
        assertEquals(WorkspaceAdmission.CancelStatus.AFTER_CLAIM,cancelled.status());
        assertEquals(java.util.List.of(allowed.claim()),cancelled.claimedTargets());
        assertInstanceOf(WorkspaceAdmission.ProviderStartResult.CancelPending.class,
                admission.authorizeProviderStart(allowed.claim()).toCompletableFuture().join());
        assertNotNull(jdbc.queryForObject("select started_at from provider_attempt where id=?", java.sql.Timestamp.class, attempt));
        assertEquals(WorkspaceAdmission.FinalizationResult.SETTLED, admission.finalizeAttempt(
                new WorkspaceAdmission.FinalizeAttempt(registration, attempt, allowed.claim(), WorkspaceAdmission.TerminalKind.UNKNOWN)).toCompletableFuture().join());
        assertEquals("UNKNOWN", jdbc.queryForObject("select execution_outcome from provider_attempt where id=?", String.class, attempt));
        Long version = jdbc.queryForObject("select projection_version from provider_attempt where id=?", Long.class, attempt);
        assertEquals(WorkspaceAdmission.FinalizationResult.SETTLED, admission.finalizeAttempt(
                new WorkspaceAdmission.FinalizeAttempt(registration, attempt, allowed.claim(), WorkspaceAdmission.TerminalKind.UNKNOWN)).toCompletableFuture().join());
        assertEquals(version,jdbc.queryForObject("select projection_version from provider_attempt where id=?", Long.class, attempt));
        assertThrows(CompletionException.class, () -> admission.complete(registration).toCompletableFuture().join());
        jdbc.update("update chat_run set status='CANCELLED' where id=?",run);
        admission.complete(registration).toCompletableFuture().join();
        assertEquals("TERMINAL",jdbc.queryForObject("select status from workspace_admission_registration where token=?",String.class,registration.token()));
        assertEquals(WorkspaceAdmission.FinalizationResult.RECOVERY_REQUIRED, admission.finalizeAttempt(
                new WorkspaceAdmission.FinalizeAttempt(registration, attempt, null, WorkspaceAdmission.TerminalKind.NOT_DISPATCHED)).toCompletableFuture().join());
    }

    @Test void oldPendingEffectKeepsItsOriginalFenceAndRetriesWhenOldPeriodClaimExists() {
        String owner = "effect-owner-" + UUID.randomUUID();
        String workspace = workspaces.personal(owner).id();
        String bot = "effect-bot-" + UUID.randomUUID();
        jdbc.update("insert into bot(id,name,target) values (?,?,'LOCAL')",bot,bot);
        var conversation = conversations.createConversation(bot,"fixture",owner,workspace);
        String run="effect-run-"+UUID.randomUUID();
        var registration=assertInstanceOf(WorkspaceAdmission.RegisterResult.Registered.class,
                admission.register(new WorkspaceAdmission.Register(workspace,run,owner,conversation.id(),WorkspaceAdmission.Partition.REAL)).toCompletableFuture().join()).registration();
        jdbc.update("insert into chat_run(id,conversation_id,client_request_id,input_text,status) values (?,?,?,'test','PENDING')",run,conversation.id(),UUID.randomUUID().toString());
        assertInstanceOf(WorkspaceAdmission.PublicationResult.Ready.class,admission.publish(registration).toCompletableFuture().join());
        var row=jdbc.queryForMap("select pending_id,pending_epoch,pending_period_start,pending_period_end from workspace_admission_registration where token=?",registration.token());
        String effectId=UUID.randomUUID().toString(), receipt=UUID.randomUUID().toString();
        jdbc.update("""
                insert into workspace_admission_effect_outbox(effect_id,registration_token,target_workspace_id,target_partition,target_run_id,
                  pending_id,pending_epoch,period_start,period_end,generation,delivery_status,intent_receipt)
                values (?,?,?,'REAL',?,?,?,?,?,0,'PENDING',?)
                """,effectId,registration.token(),workspace,run,row.get("pending_id"),row.get("pending_epoch"),row.get("pending_period_start"),row.get("pending_period_end"),receipt);
        var found=assertInstanceOf(WorkspaceAdmission.EffectInspection.Found.class,admission.inspectCancellationEffect(effectId).toCompletableFuture().join()).effect();
        String attempt=ledger.begin(new io.github.samzhu.bot.usage.UsageLedger.Dispatch(run,workspace,owner,"FAKE","test","model","FAKE","1",false,"UNKNOWN"));
        assertInstanceOf(WorkspaceAdmission.Decision.Allowed.class,admission.dispatch(new WorkspaceAdmission.Dispatch(registration,attempt)).toCompletableFuture().join());
        var after=assertInstanceOf(WorkspaceAdmission.EffectInspection.Found.class,admission.inspectCancellationEffect(effectId).toCompletableFuture().join()).effect();
        assertEquals(found.target(),after.target());
        var effect=new io.github.samzhu.bot.usage.BudgetCancellationSink.Effect(effectId,registration.token(),workspace,WorkspaceAdmission.Partition.REAL,run,found.target(),receipt);
        assertInstanceOf(WorkspaceAdmission.EffectAcceptance.Retry.class,admission.prepareCancellationEffect(effect).toCompletableFuture().join());
        assertInstanceOf(WorkspaceAdmission.ClaimInspection.Found.class,admission.inspectClaim(registration,attempt).toCompletableFuture().join());
    }

    @Test void enablingZeroCapPersistsPendingCancellationIntentBeforePublication() {
        String owner = "cap-owner-"+UUID.randomUUID(), workspace=workspaces.personal(owner).id();
        String bot="cap-bot-"+UUID.randomUUID(),run="cap-run-"+UUID.randomUUID();
        jdbc.update("insert into bot(id,name,target) values (?,?,'LOCAL')",bot,bot);
        var conversation=conversations.createConversation(bot,"fixture",owner,workspace);
        var registration=assertInstanceOf(WorkspaceAdmission.RegisterResult.Registered.class,admission.register(
                new WorkspaceAdmission.Register(workspace,run,owner,conversation.id(),WorkspaceAdmission.Partition.REAL)).toCompletableFuture().join()).registration();
        var scope=new WorkspaceMonthlyBudgetOperations.Scope(workspace,WorkspaceAdmission.Partition.REAL,YearMonth.now(java.time.ZoneId.of("Asia/Taipei")));
        budgets.configure(new WorkspaceMonthlyBudgetOperations.ConfigureCommand(scope,owner,true,java.math.BigDecimal.ZERO,0)).toCompletableFuture().join();
        String effectId=jdbc.queryForObject("select effect_id from workspace_admission_effect_outbox where registration_token=?",String.class,registration.token());
        var effect=assertInstanceOf(WorkspaceAdmission.EffectInspection.Found.class,admission.inspectCancellationEffect(effectId).toCompletableFuture().join()).effect();
        assertNotNull(effect.intentReceipt());
        assertNull(effect.ackReceipt());
        assertInstanceOf(io.github.samzhu.bot.usage.BudgetCancellationSink.Target.Pending.class,effect.target());
        jdbc.update("insert into chat_run(id,conversation_id,client_request_id,input_text,status) values (?,?,?,'test','PENDING')",run,conversation.id(),UUID.randomUUID().toString());
        assertInstanceOf(WorkspaceAdmission.PublicationResult.CancelPending.class,admission.publish(registration).toCompletableFuture().join());
        var later=assertInstanceOf(WorkspaceAdmission.RegisterResult.Denied.class,admission.register(
                new WorkspaceAdmission.Register(workspace,"later-"+UUID.randomUUID(),owner,conversation.id(),WorkspaceAdmission.Partition.REAL)).toCompletableFuture().join());
        assertEquals(WorkspaceAdmission.Reason.MONTH_CAP,later.reason());
    }

    @Test void ledgerTerminalUnknownBlocksLaterRegistrationInSamePeriod() {
        String owner="ledger-gate-"+UUID.randomUUID(),workspace=workspaces.personal(owner).id();
        String bot="ledger-bot-"+UUID.randomUUID(),run="ledger-run-"+UUID.randomUUID();
        jdbc.update("insert into bot(id,name,target) values (?,?,'LOCAL')",bot,bot);
        var conversation=conversations.createConversation(bot,"fixture",owner,workspace);
        var scope=new WorkspaceMonthlyBudgetOperations.Scope(workspace,WorkspaceAdmission.Partition.REAL,YearMonth.now(java.time.ZoneId.of("Asia/Taipei")));
        budgets.configure(new WorkspaceMonthlyBudgetOperations.ConfigureCommand(scope,owner,true,new java.math.BigDecimal("100"),0)).toCompletableFuture().join();
        var registration=assertInstanceOf(WorkspaceAdmission.RegisterResult.Registered.class,admission.register(new WorkspaceAdmission.Register(workspace,run,owner,conversation.id(),WorkspaceAdmission.Partition.REAL)).toCompletableFuture().join()).registration();
        jdbc.update("insert into chat_run(id,conversation_id,client_request_id,input_text,status) values (?,?,?,'test','PENDING')",run,conversation.id(),UUID.randomUUID().toString());
        assertInstanceOf(WorkspaceAdmission.PublicationResult.Ready.class,admission.publish(registration).toCompletableFuture().join());
        String attempt=ledger.begin(new io.github.samzhu.bot.usage.UsageLedger.Dispatch(run,workspace,owner,"FAKE","test","model","FAKE","1",false,"UNKNOWN"));
        assertInstanceOf(WorkspaceAdmission.Decision.Allowed.class,admission.dispatch(new WorkspaceAdmission.Dispatch(registration,attempt)).toCompletableFuture().join());
        ledger.finish(attempt,"UNKNOWN");
        var denied=assertInstanceOf(WorkspaceAdmission.RegisterResult.Denied.class,admission.register(
                new WorkspaceAdmission.Register(workspace,"later-"+UUID.randomUUID(),owner,conversation.id(),WorkspaceAdmission.Partition.REAL)).toCompletableFuture().join());
        assertEquals(WorkspaceAdmission.Reason.MONTH_UNKNOWN,denied.reason());
    }

    @Test void committedZeroCapConfigurationPrecedesLaterRegistration() throws Exception {
        String owner="config-window-"+UUID.randomUUID(),workspace=workspaces.personal(owner).id();
        String bot="config-bot-"+UUID.randomUUID();
        jdbc.update("insert into bot(id,name,target) values (?,?,'LOCAL')",bot,bot);
        var conversation=conversations.createConversation(bot,"fixture",owner,workspace);
        var scope=new WorkspaceMonthlyBudgetOperations.Scope(workspace,WorkspaceAdmission.Partition.REAL,YearMonth.now(java.time.ZoneId.of("Asia/Taipei")));
        budgets.read(new WorkspaceMonthlyBudgetOperations.ReadCommand(scope,owner)).toCompletableFuture().join();
        try(var connection=dataSource.getConnection()) {
            connection.setAutoCommit(false);
            int blocker;
            try(var statement=connection.createStatement(); var rs=statement.executeQuery("select pg_backend_pid()")) { rs.next(); blocker=rs.getInt(1); }
            try(var lock=connection.prepareStatement("select workspace_id from workspace_monthly_budget where workspace_id=? and partition='REAL' for update")) {
                lock.setString(1,workspace); lock.executeQuery().close();
            }
            var configure=budgets.configure(new WorkspaceMonthlyBudgetOperations.ConfigureCommand(scope,owner,true,java.math.BigDecimal.ZERO,0)).toCompletableFuture();
            long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            boolean blocked;
            do {
                blocked=Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from pg_stat_activity where ?=any(pg_blocking_pids(pid)))",Boolean.class,blocker));
                if (blocked) break;
                Thread.sleep(20);
            } while (System.nanoTime()<deadline);
            assertTrue(blocked,"configuration must reach the held budget row lock");
            var next=admission.register(new WorkspaceAdmission.Register(workspace,"later-"+UUID.randomUUID(),owner,
                    conversation.id(),WorkspaceAdmission.Partition.REAL)).toCompletableFuture();
            try { assertThrows(java.util.concurrent.TimeoutException.class,()->next.get(500,java.util.concurrent.TimeUnit.MILLISECONDS)); }
            finally { connection.commit(); }
            assertTrue(configure.get(5,java.util.concurrent.TimeUnit.SECONDS).enabled());
            var denied=assertInstanceOf(WorkspaceAdmission.RegisterResult.Denied.class,next.get(5,java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(WorkspaceAdmission.Reason.MONTH_CAP,denied.reason());
        }
    }

    @Test void claimedAttemptIsIncludedInLaterCapCancellationEffect() throws Exception {
        String owner="claim-cap-"+UUID.randomUUID(),workspace=workspaces.personal(owner).id();
        String bot="claim-bot-"+UUID.randomUUID(),run="claim-run-"+UUID.randomUUID();
        jdbc.update("insert into bot(id,name,target) values (?,?,'LOCAL')",bot,bot);
        var conversation=conversations.createConversation(bot,"fixture",owner,workspace);
        var scope=new WorkspaceMonthlyBudgetOperations.Scope(workspace,WorkspaceAdmission.Partition.REAL,YearMonth.now(java.time.ZoneId.of("Asia/Taipei")));
        budgets.configure(new WorkspaceMonthlyBudgetOperations.ConfigureCommand(scope,owner,true,new java.math.BigDecimal("100"),0)).toCompletableFuture().join();
        var registration=assertInstanceOf(WorkspaceAdmission.RegisterResult.Registered.class,admission.register(
                new WorkspaceAdmission.Register(workspace,run,owner,conversation.id(),WorkspaceAdmission.Partition.REAL)).toCompletableFuture().join()).registration();
        jdbc.update("insert into chat_run(id,conversation_id,client_request_id,input_text,status) values (?,?,?,'test','PENDING')",run,conversation.id(),UUID.randomUUID().toString());
        assertInstanceOf(WorkspaceAdmission.PublicationResult.Ready.class,admission.publish(registration).toCompletableFuture().join());
        String attempt=ledger.begin(new io.github.samzhu.bot.usage.UsageLedger.Dispatch(run,workspace,owner,"FAKE","test","model","FAKE","1",false,"UNKNOWN"));
        try(var connection=dataSource.getConnection()) {
            connection.setAutoCommit(false);
            int blocker;
            try(var statement=connection.createStatement(); var rs=statement.executeQuery("select pg_backend_pid()")) { rs.next(); blocker=rs.getInt(1); }
            try(var lock=connection.prepareStatement("select id from provider_attempt where id=? for update")) { lock.setString(1,attempt); lock.executeQuery().close(); }
            var claimStage=admission.dispatch(new WorkspaceAdmission.Dispatch(registration,attempt)).toCompletableFuture();
            long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5); boolean blocked;
            do { blocked=Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from pg_stat_activity where ?=any(pg_blocking_pids(pid)))",Boolean.class,blocker));
                if(blocked) break; Thread.sleep(20); } while(System.nanoTime()<deadline);
            assertTrue(blocked,"claim must reach the held attempt row before cap configuration");
            var configure=budgets.configure(new WorkspaceMonthlyBudgetOperations.ConfigureCommand(scope,owner,true,java.math.BigDecimal.ZERO,1)).toCompletableFuture();
            try { assertThrows(java.util.concurrent.TimeoutException.class,()->configure.get(500,java.util.concurrent.TimeUnit.MILLISECONDS)); }
            finally { connection.commit(); }
            var claim=assertInstanceOf(WorkspaceAdmission.Decision.Allowed.class,claimStage.get(5,java.util.concurrent.TimeUnit.SECONDS)).claim();
            assertTrue(configure.get(5,java.util.concurrent.TimeUnit.SECONDS).enabled());
            assertEquals(1,jdbc.queryForObject("select count(*) from workspace_admission_effect_outbox where registration_token=? and claim_id=? and target_attempt_id=? and delivery_status='PENDING'",Integer.class,registration.token(),claim.claimId(),attempt));
        }
    }

    @Test void lateSourceMakesPriorCompleteCostUnknownBeforeNextRegistration() {
        String owner="late-owner-"+UUID.randomUUID(), workspace=workspaces.personal(owner).id();
        String bot="late-bot-"+UUID.randomUUID(),run="late-run-"+UUID.randomUUID();
        jdbc.update("insert into bot(id,name,target) values (?,?,'LOCAL')",bot,bot);
        var conversation=conversations.createConversation(bot,"fixture",owner,workspace);
        var scope=new WorkspaceMonthlyBudgetOperations.Scope(workspace,WorkspaceAdmission.Partition.REAL,YearMonth.now(java.time.ZoneId.of("Asia/Taipei")));
        budgets.configure(new WorkspaceMonthlyBudgetOperations.ConfigureCommand(scope,owner,true,new java.math.BigDecimal("100"),0)).toCompletableFuture().join();
        var registration=assertInstanceOf(WorkspaceAdmission.RegisterResult.Registered.class,admission.register(new WorkspaceAdmission.Register(workspace,run,owner,conversation.id(),WorkspaceAdmission.Partition.REAL)).toCompletableFuture().join()).registration();
        jdbc.update("insert into chat_run(id,conversation_id,client_request_id,input_text,status) values (?,?,?,'test','PENDING')",run,conversation.id(),UUID.randomUUID().toString());
        assertInstanceOf(WorkspaceAdmission.PublicationResult.Ready.class,admission.publish(registration).toCompletableFuture().join());
        String attempt=ledger.begin(new io.github.samzhu.bot.usage.UsageLedger.Dispatch(run,workspace,owner,"FAKE","test","model","FAKE","1",false,"UNKNOWN"));
        assertInstanceOf(WorkspaceAdmission.Decision.Allowed.class,admission.dispatch(new WorkspaceAdmission.Dispatch(registration,attempt)).toCompletableFuture().join());
        ledger.finish(attempt,"SUCCEEDED");
        long version=jdbc.queryForObject("select projection_version from provider_attempt where id=?",Long.class,attempt);
        String price="price-"+UUID.randomUUID(),estimate="estimate-"+UUID.randomUUID();
        jdbc.update("insert into usage_price_snapshot(id,attempt_id,selection_state,provider,endpoint,model,channel,price_revision,rates) values (?,?,'MATCHED','FAKE','test','model','FAKE','test','{}'::jsonb)",price,attempt);
        jdbc.update("insert into usage_cost_estimate(id,attempt_id,source_version,price_snapshot_id,calculator_version,status,known_subtotal,complete_amount,details,payload_hash) values (?,?,?,?,'test','COMPLETE',5,5,'{}'::jsonb,'test')",estimate,attempt,version,price);
        jdbc.update("insert into usage_cost_current(attempt_id,estimate_id) values (?,?)",attempt,estimate);
        jdbc.update("update workspace_monthly_budget_period set known_subtotal_usd=5,terminal_effective_unknown=false where workspace_id=? and partition='REAL' and period_start=?",workspace,scope.period().atDay(1));
        ledger.append(attempt,new io.github.samzhu.bot.usage.UsageLedger.Snapshot("late",1,"FINAL","FAKE_RAW","1","1","1","COMPLETE","{\"input_tokens\":1}"));
        var denied=assertInstanceOf(WorkspaceAdmission.RegisterResult.Denied.class,admission.register(new WorkspaceAdmission.Register(workspace,"later-"+UUID.randomUUID(),owner,conversation.id(),WorkspaceAdmission.Partition.REAL)).toCompletableFuture().join());
        assertEquals(WorkspaceAdmission.Reason.MONTH_UNKNOWN,denied.reason());
    }

    @Test void activeMemberCanReadButCannotConfigureAndAuthorizationPrecedesInputValidation() throws Exception {
        String manager = "budget-manager-" + UUID.randomUUID();
        String member = "budget-member-" + UUID.randomUUID();
        String workspace = workspaces.personal(manager).id();
        jdbc.update("insert into workspace_member(workspace_id,user_id,role,active) values (?,?,'MEMBER',true)", workspace, member);
        var scope = new WorkspaceMonthlyBudgetOperations.Scope(workspace, WorkspaceAdmission.Partition.REAL,
                YearMonth.of(2026, 11));

        var defaults = budgets.read(new WorkspaceMonthlyBudgetOperations.ReadCommand(scope, manager)).toCompletableFuture().join();
        assertFalse(defaults.enabled());
        assertEquals(0L, defaults.configVersion());
        assertEquals(0L, defaults.gateGeneration());
        var memberRead = budgets.read(new WorkspaceMonthlyBudgetOperations.ReadCommand(scope, member)).toCompletableFuture().join();
        assertFalse(memberRead.enabled());
        var memberDenied = assertThrows(CompletionException.class, () -> budgets.configure(
                new WorkspaceMonthlyBudgetOperations.ConfigureCommand(scope, member, true,
                        null, -1)).toCompletableFuture().join());
        assertEquals("WORKSPACE_ACCESS_DENIED", memberDenied.getCause().getMessage());
    }

    @Test void concurrentManagerConfigurationUsesIndependentVersionCas() throws Exception {
        String manager = "budget-manager-" + UUID.randomUUID();
        String workspace = workspaces.personal(manager).id();
        var scope = new WorkspaceMonthlyBudgetOperations.Scope(workspace, WorkspaceAdmission.Partition.REAL,
                YearMonth.of(2026, 11));
        var defaults = budgets.read(new WorkspaceMonthlyBudgetOperations.ReadCommand(scope, manager)).toCompletableFuture().join();
        assertFalse(defaults.enabled());
        assertEquals(0L, defaults.configVersion());
        assertEquals(0L, defaults.gateGeneration());
        var disabled = budgets.configure(new WorkspaceMonthlyBudgetOperations.ConfigureCommand(
                scope, manager, false, null, 0)).toCompletableFuture().join();
        assertEquals(1L, disabled.configVersion());
        assertEquals(1L, disabled.gateGeneration());
        var pool = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        try {
            var first = pool.submit(() -> { start.await(); return configure(scope, manager, true, "25.125000000000", 1); });
            var second = pool.submit(() -> { start.await(); return configure(scope, manager, true, "26.125000000000", 1); });
            start.countDown();
            int succeeded = 0;
            for (var result : java.util.List.of(first.get(), second.get())) if (result) succeeded++;
            assertEquals(1, succeeded, "only one update may consume a config version");
        } finally { pool.shutdownNow(); }

        var current = budgets.read(new WorkspaceMonthlyBudgetOperations.ReadCommand(scope, manager)).toCompletableFuture().join();
        assertTrue(current.enabled());
        assertEquals(2L, current.configVersion());
        assertEquals(2L, current.gateGeneration());
    }

    @Test void managerConfigurationEnforcesCapPresenceNonnegativeAndExactUsd() {
        String manager = "budget-manager-" + UUID.randomUUID();
        String workspace = workspaces.personal(manager).id();
        var scope = new WorkspaceMonthlyBudgetOperations.Scope(workspace, WorkspaceAdmission.Partition.REAL,
                YearMonth.of(2026, 11));
        assertThrows(CompletionException.class, () -> budgets.configure(
                new WorkspaceMonthlyBudgetOperations.ConfigureCommand(scope, manager, true, null, 0)).toCompletableFuture().join());
        assertThrows(CompletionException.class, () -> budgets.configure(
                new WorkspaceMonthlyBudgetOperations.ConfigureCommand(scope, manager, false,
                        new java.math.BigDecimal("-0.000000000001"), 0)).toCompletableFuture().join());
        assertThrows(CompletionException.class, () -> budgets.configure(
                new WorkspaceMonthlyBudgetOperations.ConfigureCommand(scope, manager, false,
                        new java.math.BigDecimal("0.0000000000001"), 0)).toCompletableFuture().join());
        var exact = budgets.configure(new WorkspaceMonthlyBudgetOperations.ConfigureCommand(
                scope, manager, true, new java.math.BigDecimal("999999999999999999.999999999999"), 0)).toCompletableFuture().join();
        assertEquals(new java.math.BigDecimal("999999999999999999.999999999999"), exact.capUsd());
    }

    @Test void budgetConfigurationPersistsAcrossMonthsButPeriodTotalsAreSeparate() {
        String manager = "budget-month-" + UUID.randomUUID();
        String workspace = workspaces.personal(manager).id();
        var nov = new WorkspaceMonthlyBudgetOperations.Scope(workspace, WorkspaceAdmission.Partition.REAL, YearMonth.of(2026, 11));
        var dec = new WorkspaceMonthlyBudgetOperations.Scope(workspace, WorkspaceAdmission.Partition.REAL, YearMonth.of(2026, 12));
        budgets.configure(new WorkspaceMonthlyBudgetOperations.ConfigureCommand(nov, manager, true,
                new java.math.BigDecimal("12.500000000000"), 0)).toCompletableFuture().join();
        jdbc.update("update workspace_monthly_budget_period set known_subtotal_usd=3, terminal_effective_unknown=true where workspace_id=? and partition='REAL' and period_start=?", workspace, nov.period().atDay(1));
        var next = budgets.read(new WorkspaceMonthlyBudgetOperations.ReadCommand(dec, manager)).toCompletableFuture().join();
        assertTrue(next.enabled());
        assertEquals(new java.math.BigDecimal("12.500000000000"), next.capUsd());
        assertEquals(0, next.knownSubtotalUsd().signum());
        assertFalse(next.terminalEffectiveUnknown());
        assertEquals(1, next.configVersion());
    }

    private boolean configure(WorkspaceMonthlyBudgetOperations.Scope scope, String actor, boolean enabled,
                              String cap, long version) {
        try {
            budgets.configure(new WorkspaceMonthlyBudgetOperations.ConfigureCommand(scope, actor, enabled,
                    new java.math.BigDecimal(cap), version)).toCompletableFuture().join();
            return true;
        } catch (CompletionException expectedCasFailure) {
            return false;
        }
    }
    @Test void finalizationReasonSurvivesFailedWriteAndRecoveryWithoutBeingOverwritten() {
        String owner="reason-"+UUID.randomUUID(), workspace=workspaces.personal(owner).id(), bot="reason-"+UUID.randomUUID(),run="reason-"+UUID.randomUUID();
        jdbc.update("insert into bot(id,name,target) values (?,?,'LOCAL')",bot,bot);
        var conversation=conversations.createConversation(bot,"fixture",owner,workspace);
        var registration=assertInstanceOf(WorkspaceAdmission.RegisterResult.Registered.class,admission.register(new WorkspaceAdmission.Register(workspace,run,owner,conversation.id(),WorkspaceAdmission.Partition.REAL)).toCompletableFuture().join()).registration();
        jdbc.update("insert into chat_run(id,conversation_id,client_request_id,input_text,status) values (?,?,?,'test','PENDING')",run,conversation.id(),UUID.randomUUID().toString());
        assertInstanceOf(WorkspaceAdmission.PublicationResult.Ready.class,admission.publish(registration).toCompletableFuture().join());
        String attempt=ledger.begin(new io.github.samzhu.bot.usage.UsageLedger.Dispatch(run,workspace,owner,"FAKE","test","model","FAKE","1",false,"UNKNOWN"));
        var claim=assertInstanceOf(WorkspaceAdmission.Decision.Allowed.class,admission.dispatch(new WorkspaceAdmission.Dispatch(registration,attempt)).toCompletableFuture().join()).claim();
        jdbc.execute("create or replace function test_reason_failure() returns trigger language plpgsql as $$ begin raise exception 'injected'; end $$");
        jdbc.execute("create trigger test_reason_failure before update of execution_outcome on provider_attempt for each row execute function test_reason_failure()");
        try {
            var command=new WorkspaceAdmission.FinalizeAttempt(registration,attempt,claim,WorkspaceAdmission.TerminalKind.UNKNOWN,WorkspaceAdmission.TerminalReason.CAPTURE_FAILED);
            assertEquals(WorkspaceAdmission.FinalizationResult.RECOVERY_REQUIRED,admission.finalizeAttempt(command).toCompletableFuture().join());
            assertEquals("CAPTURE_FAILED",jdbc.queryForObject("select terminal_reason from workspace_admission_finalization where attempt_id=?",String.class,attempt));
            jdbc.execute("drop trigger test_reason_failure on provider_attempt");
            assertEquals(WorkspaceAdmission.FinalizationResult.SETTLED,admission.recoverFinalization(registration,attempt).toCompletableFuture().join());
            assertEquals("CAPTURE_FAILED",jdbc.queryForObject("select execution_reason from provider_attempt where id=?",String.class,attempt));
            assertEquals("CAPTURE_FAILED",jdbc.queryForObject("select projection_error from provider_attempt where id=?",String.class,attempt));
            var invalid=new WorkspaceAdmission.FinalizeAttempt(registration,attempt,claim,WorkspaceAdmission.TerminalKind.UNKNOWN,WorkspaceAdmission.TerminalReason.PROMPT_CONTEXT_UNAVAILABLE);
            assertEquals(WorkspaceAdmission.FinalizationResult.RECOVERY_REQUIRED,admission.finalizeAttempt(invalid).toCompletableFuture().join());
            var conflicting=new WorkspaceAdmission.FinalizeAttempt(registration,attempt,claim,WorkspaceAdmission.TerminalKind.UNKNOWN,WorkspaceAdmission.TerminalReason.ADMISSION_RECOVERY);
            assertEquals(WorkspaceAdmission.FinalizationResult.RECOVERY_REQUIRED,admission.finalizeAttempt(conflicting).toCompletableFuture().join());
            assertEquals("CAPTURE_FAILED",jdbc.queryForObject("select execution_reason from provider_attempt where id=?",String.class,attempt));
        } finally {
            jdbc.execute("drop trigger if exists test_reason_failure on provider_attempt");
            jdbc.execute("drop function if exists test_reason_failure()");
        }
    }

    @Test void trustedPromptFailureScanReturnsSettledFactsAndKeepsUnknownDistinct() throws Exception {
        String owner="scan-"+UUID.randomUUID(),workspace=workspaces.personal(owner).id(),bot="scan-"+UUID.randomUUID(),run="scan-"+UUID.randomUUID();
        jdbc.update("insert into bot(id,name,target) values (?,?,'LOCAL')",bot,bot);
        var conversation=conversations.createConversation(bot,"fixture",owner,workspace);
        var registration=assertInstanceOf(WorkspaceAdmission.RegisterResult.Registered.class,admission.register(new WorkspaceAdmission.Register(workspace,run,owner,conversation.id(),WorkspaceAdmission.Partition.REAL)).toCompletableFuture().join()).registration();
        jdbc.update("insert into chat_run(id,conversation_id,client_request_id,input_text,status) values (?,?,?,'test','PENDING')",run,conversation.id(),UUID.randomUUID().toString());
        assertInstanceOf(WorkspaceAdmission.PublicationResult.Ready.class,admission.publish(registration).toCompletableFuture().join());
        String attempt=ledger.begin(new io.github.samzhu.bot.usage.UsageLedger.Dispatch(run,workspace,owner,"FAKE","test","model","FAKE","1",false,"UNKNOWN"));
        assertEquals(WorkspaceAdmission.FinalizationResult.SETTLED,admission.finalizeAttempt(new WorkspaceAdmission.FinalizeAttempt(registration,attempt,null,WorkspaceAdmission.TerminalKind.NOT_DISPATCHED,WorkspaceAdmission.TerminalReason.PROMPT_CONTEXT_UNAVAILABLE)).toCompletableFuture().join());
        var first=assertInstanceOf(WorkspaceAdmission.PromptFailurePage.Page.class,admission.scanSettledPromptFailures(null,null,100).toCompletableFuture().join());
        assertTrue(first.facts().contains(new WorkspaceAdmission.PromptFailureFact(attempt,registration)));
        assertNotNull(first.upperAttemptId());
        var done=assertInstanceOf(WorkspaceAdmission.PromptFailurePage.Page.class,admission.scanSettledPromptFailures(first.upperAttemptId(),first.upperAttemptId(),1).toCompletableFuture().join());
        assertTrue(done.facts().isEmpty()); assertEquals(first.upperAttemptId(),done.upperAttemptId());
        assertThrows(IllegalArgumentException.class,()->admission.scanSettledPromptFailures(null,null,0));
        assertThrows(IllegalArgumentException.class,()->admission.scanSettledPromptFailures(null,null,101));
        try(var lock=dataSource.getConnection()) {
            lock.setAutoCommit(false);
            try(var statement=lock.createStatement()) { statement.execute("lock table workspace_admission_finalization in access exclusive mode"); }
            assertInstanceOf(WorkspaceAdmission.PromptFailurePage.Unknown.class,admission.scanSettledPromptFailures(null,null,100).toCompletableFuture().get(8,java.util.concurrent.TimeUnit.SECONDS));
            lock.rollback();
        }
        assertInstanceOf(WorkspaceAdmission.PromptFailurePage.Page.class,admission.scanSettledPromptFailures(null,null).toCompletableFuture().join());
    }

    @Test void promptFailureScanPinsUpperAndExcludesPendingAndOtherReasons() throws Exception {
        String owner="pages-"+UUID.randomUUID(), workspace=workspaces.personal(owner).id(),bot="pages-"+UUID.randomUUID();
        jdbc.update("insert into bot(id,name,target) values (?,?,'LOCAL')",bot,bot);
        var registrations=new java.util.ArrayList<WorkspaceAdmission.Registration>();
        var attempts=new java.util.ArrayList<String>();
        for(int i=0;i<4;i++) {
            var conversation=conversations.createConversation(bot,"fixture-"+i,owner,workspace);
            String run="pages-"+UUID.randomUUID();
            var reg=assertInstanceOf(WorkspaceAdmission.RegisterResult.Registered.class,admission.register(new WorkspaceAdmission.Register(workspace,run,owner,conversation.id(),WorkspaceAdmission.Partition.REAL)).toCompletableFuture().join()).registration();
            jdbc.update("insert into chat_run(id,conversation_id,client_request_id,input_text,status) values (?,?,?,'test','PENDING')",run,conversation.id(),UUID.randomUUID().toString());
            assertInstanceOf(WorkspaceAdmission.PublicationResult.Ready.class,admission.publish(reg).toCompletableFuture().join());
            attempts.add(ledger.begin(new io.github.samzhu.bot.usage.UsageLedger.Dispatch(run,workspace,owner,"FAKE","test","model","FAKE","1",false,"UNKNOWN")));
            registrations.add(reg);
        }
        var prompt=WorkspaceAdmission.TerminalReason.PROMPT_CONTEXT_UNAVAILABLE;
        for(int i=0;i<2;i++) assertEquals(WorkspaceAdmission.FinalizationResult.SETTLED,admission.finalizeAttempt(new WorkspaceAdmission.FinalizeAttempt(registrations.get(i),attempts.get(i),null,WorkspaceAdmission.TerminalKind.NOT_DISPATCHED,prompt)).toCompletableFuture().join());
        assertEquals(WorkspaceAdmission.FinalizationResult.SETTLED,admission.finalizeAttempt(new WorkspaceAdmission.FinalizeAttempt(registrations.get(2),attempts.get(2),null,WorkspaceAdmission.TerminalKind.NOT_DISPATCHED,WorkspaceAdmission.TerminalReason.RUN_TERMINATED_BEFORE_DISPATCH)).toCompletableFuture().join());
        String held=attempts.get(3);
        jdbc.execute("create or replace function test_pending_scan() returns trigger language plpgsql as $$ begin if new.id='"+held+"' and new.execution_outcome <> 'IN_PROGRESS' then raise exception 'injected'; end if; return new; end $$");
        jdbc.execute("create trigger test_pending_scan before update on provider_attempt for each row execute function test_pending_scan()");
        try {
            assertEquals(WorkspaceAdmission.FinalizationResult.RECOVERY_REQUIRED,admission.finalizeAttempt(new WorkspaceAdmission.FinalizeAttempt(registrations.get(3),held,null,WorkspaceAdmission.TerminalKind.NOT_DISPATCHED,prompt)).toCompletableFuture().join());
            assertEquals("PENDING",jdbc.queryForObject("select state from workspace_admission_finalization where attempt_id=?",String.class,held));
            var first=assertInstanceOf(WorkspaceAdmission.PromptFailurePage.Page.class,admission.scanSettledPromptFailures(null,null,1).toCompletableFuture().join());
            String upper=first.upperAttemptId(); assertNotNull(upper);
            // A new settled synthetic fact beyond the pinned upper belongs to the next cycle.
            String later=upper+"z";
            jdbc.update("""
                insert into provider_attempt(id,run_id,workspace_id,actor_id,provider,endpoint,model,channel,adapter_version,simulation,attempt_coverage,prepared_at,execution_outcome,usage_presence,capture_completeness,projection_state)
                select ?,run_id,workspace_id,actor_id,provider,endpoint,model,channel,adapter_version,simulation,attempt_coverage,prepared_at,execution_outcome,usage_presence,capture_completeness,projection_state from provider_attempt where id=?
                """,later,attempts.get(0));
            jdbc.update("insert into workspace_admission_finalization(attempt_id,registration_token,terminal_kind,terminal_reason,state,settled_at) values (?,?,'NOT_DISPATCHED','PROMPT_CONTEXT_UNAVAILABLE','SETTLED',current_timestamp)",later,registrations.get(0).token());
            var seen=new java.util.HashSet<String>(); var page=first;
            while(true) {
                assertEquals(upper,page.upperAttemptId());
                if(page.facts().isEmpty()) break;
                assertEquals(1,page.facts().size()); String id=page.facts().getFirst().attemptId(); assertTrue(seen.add(id));
                page=assertInstanceOf(WorkspaceAdmission.PromptFailurePage.Page.class,admission.scanSettledPromptFailures(id,upper,1).toCompletableFuture().join());
            }
            assertTrue(seen.contains(attempts.get(0))); assertTrue(seen.contains(attempts.get(1)));
            assertFalse(seen.contains(attempts.get(2))); assertFalse(seen.contains(held)); assertFalse(seen.contains(later));
            jdbc.execute("drop trigger test_pending_scan on provider_attempt");
            assertEquals(WorkspaceAdmission.FinalizationResult.SETTLED,admission.recoverFinalization(registrations.get(3),held).toCompletableFuture().join());
            var next=assertInstanceOf(WorkspaceAdmission.PromptFailurePage.Page.class,admission.scanSettledPromptFailures(null,null,100).toCompletableFuture().join());
            assertTrue(next.facts().stream().anyMatch(f->f.attemptId().equals(held)),"next full cycle finds facts settled after the pinned scan");
            assertTrue(next.facts().stream().anyMatch(f->f.attemptId().equals(later)),"next full cycle finds facts inserted beyond the pinned upper");
        } finally { jdbc.execute("drop trigger if exists test_pending_scan on provider_attempt"); jdbc.execute("drop function if exists test_pending_scan()"); }
    }

}
