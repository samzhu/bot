package io.github.samzhu.bot;

import io.github.samzhu.bot.conversation.ConversationOperations;
import io.github.samzhu.bot.usage.UsageLedger;
import io.github.samzhu.bot.usage.WorkspaceAdmission;
import io.github.samzhu.bot.usage.WorkspaceMonthlyBudgetOperations;
import io.github.samzhu.bot.workspace.WorkspaceOperations;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.context.ActiveProfiles;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={"bot.pricing.reconcile.initial-delay-ms=0","bot.pricing.reconcile.delay-ms=200","bot.budget.effects.initial-delay-ms=3600000"})
@Import({TestcontainersConfiguration.class,WorkspacePricingGateTests.ControlledScheduling.class})
@ActiveProfiles("test")
class WorkspacePricingGateTests {
    static final CountDownLatch SCHEDULER_RELEASE=new CountDownLatch(1);
    @org.springframework.boot.test.context.TestConfiguration
    @EnableScheduling
    static class ControlledScheduling {
        @Bean(name="taskScheduler") ThreadPoolTaskScheduler taskScheduler() {
            var scheduler=new ThreadPoolTaskScheduler() {
                @Override public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, Instant start, Duration delay) {
                    return super.scheduleWithFixedDelay(() -> {
                        try { if (SCHEDULER_RELEASE.await(20,TimeUnit.SECONDS)) task.run(); }
                        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    },start,delay);
                }
            };
            scheduler.setPoolSize(2);
            return scheduler;
        }
    }
    @Autowired WorkspaceAdmission admission;
    @Autowired WorkspaceMonthlyBudgetOperations budgets;
    @Autowired WorkspaceOperations workspaces;
    @Autowired ConversationOperations conversations;
    @Autowired UsageLedger ledger;
    @Autowired JdbcTemplate jdbc;
    @Autowired javax.sql.DataSource dataSource;
    @Autowired io.github.samzhu.bot.usage.UsageCostRevisionOperations revisions;
    @org.springframework.test.context.bean.override.mockito.MockitoBean io.github.samzhu.bot.identity.CurrentUserProvider currentUser;

    @Test void trustedPricePublicationRefreshesUnknownGateBeforeNextAdmission() {
        try {
            String actor="pricing-gate-"+UUID.randomUUID(),workspace=workspaces.personal(actor).id();
            org.mockito.Mockito.when(currentUser.getCurrentUserId()).thenReturn(actor);
            String bot="pricing-bot-"+UUID.randomUUID(),run="pricing-run-"+UUID.randomUUID();
            String model="fixture-"+UUID.randomUUID();
            jdbc.update("insert into bot(id,name,target) values (?,?,'LOCAL')",bot,bot);
            var conversation=conversations.createConversation(bot,"fixture",actor,workspace);
            var scope=new WorkspaceMonthlyBudgetOperations.Scope(workspace,WorkspaceAdmission.Partition.SIMULATION,YearMonth.now(ZoneId.of("Asia/Taipei")));
            budgets.configure(new WorkspaceMonthlyBudgetOperations.ConfigureCommand(scope,actor,true,new BigDecimal("100"),0)).toCompletableFuture().join();
            var registration=assertInstanceOf(WorkspaceAdmission.RegisterResult.Registered.class,admission.register(new WorkspaceAdmission.Register(workspace,run,actor,conversation.id(),WorkspaceAdmission.Partition.SIMULATION)).toCompletableFuture().join()).registration();
            jdbc.update("insert into chat_run(id,conversation_id,client_request_id,input_text,status) values (?,?,?,'test','PENDING')",run,conversation.id(),UUID.randomUUID().toString());
            assertInstanceOf(WorkspaceAdmission.PublicationResult.Ready.class,admission.publish(registration).toCompletableFuture().join());
            String attempt=ledger.begin(new UsageLedger.Dispatch(run,workspace,actor,"FAKE_GEMINI","generateContent",model,"FAKE","1",true,"VERIFIED"));
            assertInstanceOf(WorkspaceAdmission.Decision.Allowed.class,admission.dispatch(new WorkspaceAdmission.Dispatch(registration,attempt)).toCompletableFuture().join());
            String catalog="fixture-catalog-"+UUID.randomUUID(),entry="fixture-entry-"+UUID.randomUUID();
            jdbc.update("insert into pricing_catalog_revision(id,source_reference,source_revision,content_hash) values (?,'synthetic',?,'fixture')",catalog,catalog);
            jdbc.update("""
                    insert into pricing_catalog_entry(id,revision_id,provider,endpoint,model,channel,price_revision,effective_from,effective_until,rates)
                    values (?,?,'FAKE_GEMINI','generateContent',?,'FAKE','fixture',now()-interval '1 day',now()+interval '1 day',
                      '{"input":"1","cache_read":"0.1","output":"2","thoughts":"2","tool_input":"1"}'::jsonb)
                    """,entry,catalog,model);
            jdbc.update("update provider_attempt set catalog_marker='CAPTURED',catalog_revision_id=? where id=?",catalog,attempt);
            ledger.append(attempt,new UsageLedger.Snapshot("raw",1,"FINAL","FAKE_RAW","pricing-fixture-v1","1","1","COMPLETE",
                    "{\"promptTokenCount\":1000,\"cachedContentTokenCount\":200,\"candidatesTokenCount\":300,\"thoughtsTokenCount\":100,\"toolUsePromptTokenCount\":0}"));
            ledger.finish(attempt,"SUCCEEDED");
            assertTrue(budgets.read(new WorkspaceMonthlyBudgetOperations.ReadCommand(scope,actor)).toCompletableFuture().join().terminalEffectiveUnknown());
            SCHEDULER_RELEASE.countDown();
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
            String costStatus=null;
            while (System.nanoTime()<deadline) {
                var rows=jdbc.query("select e.status from usage_cost_current c join usage_cost_estimate e on e.id=c.estimate_id where c.attempt_id=?",(rs,n)->rs.getString(1),attempt);
                if (!rows.isEmpty()) { costStatus=rows.getFirst(); break; }
                Thread.yield();
            }
            assertEquals("COMPLETE",costStatus,"controlled pricing scheduler must publish a complete result");
            var result=budgets.read(new WorkspaceMonthlyBudgetOperations.ReadCommand(scope,actor)).toCompletableFuture().join();
            assertFalse(result.terminalEffectiveUnknown(),"published trusted current must clear unknown in the same lane");
            assertEquals(new BigDecimal("0.001620000000"),result.knownSubtotalUsd());
            String current=jdbc.queryForObject("select estimate_id from usage_cost_current where attempt_id=?",String.class,attempt);
            String version=jdbc.queryForObject("select projection_version::text from provider_attempt where id=?",String.class,attempt);
            var manual=revisions.manual(new io.github.samzhu.bot.usage.UsageCostRevisionOperations.ManualCommand(workspace,attempt,current,version,
                    "manual-key","5","MANUAL_ESTIMATE","fixture evidence","fixture reason"));
            assertEquals("APPLIED",manual.outcome());
            var after=budgets.read(new WorkspaceMonthlyBudgetOperations.ReadCommand(scope,actor)).toCompletableFuture().join();
            assertTrue(after.terminalEffectiveUnknown(),"manual declaration is not trusted complete pricing");
            assertNull(after.knownSubtotalUsd());
            var reconciled=revisions.reconcile(new io.github.samzhu.bot.usage.UsageCostRevisionOperations.ReconcileCommand(
                    workspace,attempt,manual.revision().id(),version,"reconcile-key",catalog,"fixture evidence","fixture reason"));
            assertEquals("APPLIED",reconciled.outcome());
            var recovered=budgets.read(new WorkspaceMonthlyBudgetOperations.ReadCommand(scope,actor)).toCompletableFuture().join();
            assertFalse(recovered.terminalEffectiveUnknown());
            assertEquals(new BigDecimal("0.001620000000"),recovered.knownSubtotalUsd());
        } finally { SCHEDULER_RELEASE.countDown(); }
    }

    @Test void committedManualCurrentBlocksClaimThatWasQueuedAfterWriter() throws Exception {
        String actor="manual-window-"+UUID.randomUUID(),workspace=workspaces.personal(actor).id();
        org.mockito.Mockito.when(currentUser.getCurrentUserId()).thenReturn(actor);
        String bot="manual-bot-"+UUID.randomUUID(),run="manual-run-"+UUID.randomUUID(),nextRun="next-run-"+UUID.randomUUID();
        String model="fixture-"+UUID.randomUUID();
        jdbc.update("insert into bot(id,name,target) values (?,?,'LOCAL')",bot,bot);
        var conversation=conversations.createConversation(bot,"fixture",actor,workspace);
        var nextConversation=conversations.createConversation(bot,"fixture-next",actor,workspace);
        var scope=new WorkspaceMonthlyBudgetOperations.Scope(workspace,WorkspaceAdmission.Partition.SIMULATION,YearMonth.now(ZoneId.of("Asia/Taipei")));
        budgets.configure(new WorkspaceMonthlyBudgetOperations.ConfigureCommand(scope,actor,true,new BigDecimal("100"),0)).toCompletableFuture().join();
        var first=assertInstanceOf(WorkspaceAdmission.RegisterResult.Registered.class,admission.register(new WorkspaceAdmission.Register(workspace,run,actor,conversation.id(),WorkspaceAdmission.Partition.SIMULATION)).toCompletableFuture().join()).registration();
        var second=assertInstanceOf(WorkspaceAdmission.RegisterResult.Registered.class,admission.register(new WorkspaceAdmission.Register(workspace,nextRun,actor,nextConversation.id(),WorkspaceAdmission.Partition.SIMULATION)).toCompletableFuture().join()).registration();
        jdbc.update("insert into chat_run(id,conversation_id,client_request_id,input_text,status) values (?,?,?,'test','PENDING'),(?,?,?,'test','PENDING')",run,conversation.id(),UUID.randomUUID().toString(),nextRun,nextConversation.id(),UUID.randomUUID().toString());
        assertInstanceOf(WorkspaceAdmission.PublicationResult.Ready.class,admission.publish(first).toCompletableFuture().join());
        assertInstanceOf(WorkspaceAdmission.PublicationResult.Ready.class,admission.publish(second).toCompletableFuture().join());
        String attempt=ledger.begin(new UsageLedger.Dispatch(run,workspace,actor,"FAKE_GEMINI","generateContent",model,"FAKE","1",true,"VERIFIED"));
        String nextAttempt=ledger.begin(new UsageLedger.Dispatch(nextRun,workspace,actor,"FAKE_GEMINI","generateContent",model,"FAKE","1",true,"VERIFIED"));
        assertInstanceOf(WorkspaceAdmission.Decision.Allowed.class,admission.dispatch(new WorkspaceAdmission.Dispatch(first,attempt)).toCompletableFuture().join());
        String catalog="manual-catalog-"+UUID.randomUUID(),entry="manual-entry-"+UUID.randomUUID();
        jdbc.update("insert into pricing_catalog_revision(id,source_reference,source_revision,content_hash) values (?,'synthetic',?,'fixture')",catalog,catalog);
        jdbc.update("""
                insert into pricing_catalog_entry(id,revision_id,provider,endpoint,model,channel,price_revision,effective_from,effective_until,rates)
                values (?,?,'FAKE_GEMINI','generateContent',?,'FAKE','fixture',now()-interval '1 day',now()+interval '1 day',
                  '{"input":"1","cache_read":"0.1","output":"2","thoughts":"2","tool_input":"1"}'::jsonb)
                """,entry,catalog,model);
        jdbc.update("update provider_attempt set catalog_marker='CAPTURED',catalog_revision_id=? where id=?",catalog,attempt);
        ledger.append(attempt,new UsageLedger.Snapshot("raw",1,"FINAL","FAKE_RAW","pricing-fixture-v1","1","1","COMPLETE",
                "{\"promptTokenCount\":1000,\"cachedContentTokenCount\":200,\"candidatesTokenCount\":300,\"thoughtsTokenCount\":100,\"toolUsePromptTokenCount\":0}"));
        ledger.finish(attempt,"SUCCEEDED");
        SCHEDULER_RELEASE.countDown();
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
        while (System.nanoTime()<deadline && Boolean.TRUE.equals(budgets.read(new WorkspaceMonthlyBudgetOperations.ReadCommand(scope,actor)).toCompletableFuture().join().terminalEffectiveUnknown())) Thread.yield();
        assertFalse(budgets.read(new WorkspaceMonthlyBudgetOperations.ReadCommand(scope,actor)).toCompletableFuture().join().terminalEffectiveUnknown());
        String current=jdbc.queryForObject("select estimate_id from usage_cost_current where attempt_id=?",String.class,attempt);
        String version=jdbc.queryForObject("select projection_version::text from provider_attempt where id=?",String.class,attempt);
        try(var connection=dataSource.getConnection()) {
            connection.setAutoCommit(false);
            int blocker;
            try(var statement=connection.createStatement(); var rs=statement.executeQuery("select pg_backend_pid()")) { rs.next(); blocker=rs.getInt(1); }
            try(var lock=connection.prepareStatement("select id from provider_attempt where id=? for update")) { lock.setString(1,attempt); lock.executeQuery().close(); }
            var manual=java.util.concurrent.CompletableFuture.supplyAsync(()->revisions.manual(new io.github.samzhu.bot.usage.UsageCostRevisionOperations.ManualCommand(
                    workspace,attempt,current,version,"manual-window-key","5","MANUAL_ESTIMATE","fixture evidence","fixture reason")));
            long wait=System.nanoTime()+TimeUnit.SECONDS.toNanos(5); boolean blocked;
            do { blocked=Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from pg_stat_activity where ?=any(pg_blocking_pids(pid)))",Boolean.class,blocker));
                if(blocked) break; Thread.sleep(20); } while(System.nanoTime()<wait);
            assertTrue(blocked,"manual current write must wait on the attempt row");
            var decision=admission.dispatch(new WorkspaceAdmission.Dispatch(second,nextAttempt)).toCompletableFuture();
            try { assertThrows(java.util.concurrent.TimeoutException.class,()->decision.get(500,TimeUnit.MILLISECONDS)); }
            finally { connection.commit(); }
            assertEquals("APPLIED",manual.get(5,TimeUnit.SECONDS).outcome());
            var denied=assertInstanceOf(WorkspaceAdmission.Decision.Denied.class,decision.get(5,TimeUnit.SECONDS));
            assertEquals(WorkspaceAdmission.Reason.MONTH_UNKNOWN,denied.reason());
            assertNull(jdbc.queryForObject("select started_at from provider_attempt where id=?",java.sql.Timestamp.class,nextAttempt));
        } finally { SCHEDULER_RELEASE.countDown(); }
    }
}
