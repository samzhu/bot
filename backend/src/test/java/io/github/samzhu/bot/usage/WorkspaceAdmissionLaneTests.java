package io.github.samzhu.bot;

import io.github.samzhu.bot.conversation.ConversationOperations;
import io.github.samzhu.bot.usage.UsageLedger;
import io.github.samzhu.bot.usage.WorkspaceAdmission;
import io.github.samzhu.bot.workspace.WorkspaceOperations;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import javax.sql.DataSource;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Import({TestcontainersConfiguration.class, WorkspaceAdmissionLaneTests.Scheduling.class})
@ActiveProfiles("test")
class WorkspaceAdmissionLaneTests {
    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods=false)
    @org.springframework.scheduling.annotation.EnableScheduling
    static class Scheduling {}
    @Autowired WorkspaceAdmission admission;
    @Autowired WorkspaceOperations workspaces;
    @Autowired ConversationOperations conversations;
    @Autowired UsageLedger ledger;
    @Autowired io.github.samzhu.bot.usage.WorkspaceMonthlyBudgetOperations budgets;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    record Fixture(WorkspaceAdmission.Registration registration,String attempt) {}
    Fixture fixture(String actor,String workspace) {
        String bot="lane-bot-"+UUID.randomUUID(),run="lane-run-"+UUID.randomUUID();
        jdbc.update("insert into bot(id,name,target) values (?,?,'LOCAL')",bot,bot);
        var conversation=conversations.createConversation(bot,"fixture",actor,workspace);
        var registration=assertInstanceOf(WorkspaceAdmission.RegisterResult.Registered.class,
                admission.register(new WorkspaceAdmission.Register(workspace,run,actor,conversation.id(),WorkspaceAdmission.Partition.REAL)).toCompletableFuture().join()).registration();
        jdbc.update("insert into chat_run(id,conversation_id,client_request_id,input_text,status) values (?,?,?,'test','PENDING')",run,conversation.id(),UUID.randomUUID().toString());
        assertInstanceOf(WorkspaceAdmission.PublicationResult.Ready.class,admission.publish(registration).toCompletableFuture().join());
        String attempt=ledger.begin(new UsageLedger.Dispatch(run,workspace,actor,"FAKE","test","model","FAKE","1",false,"UNKNOWN"));
        return new Fixture(registration,attempt);
    }
    @Test void cancellingReturnedStageDoesNotCancelClaimJob() throws Exception {
        String actor="lane-user-"+UUID.randomUUID(),workspace=workspaces.personal(actor).id();
        var fixture=fixture(actor,workspace);
        try(var connection=dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try(var lock=connection.prepareStatement("select token from workspace_admission_registration where token=? for update")) {
                lock.setString(1,fixture.registration().token());
                lock.executeQuery().close();
            }
            var returned=admission.dispatch(new WorkspaceAdmission.Dispatch(fixture.registration(),fixture.attempt())).toCompletableFuture();
            assertTrue(returned.cancel(true));
            connection.commit();
        }
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        WorkspaceAdmission.ClaimInspection found;
        do {
            found=admission.inspectClaim(fixture.registration(),fixture.attempt()).toCompletableFuture().get(2,TimeUnit.SECONDS);
        } while (!(found instanceof WorkspaceAdmission.ClaimInspection.Found) && System.nanoTime()<deadline);
        assertInstanceOf(WorkspaceAdmission.ClaimInspection.Found.class,found);
        assertNotNull(jdbc.queryForObject("select started_at from provider_attempt where id=?",java.sql.Timestamp.class,fixture.attempt()));
    }
    @Test void blockedCompletionCallbackDoesNotBlockSameWorkspaceLane() throws Exception {
        String actor="lane-user-"+UUID.randomUUID(),workspace=workspaces.personal(actor).id();
        var first=fixture(actor,workspace); var second=fixture(actor,workspace);
        var entered=new CountDownLatch(1); var release=new CountDownLatch(1);
        try {
            var blocked=admission.dispatch(new WorkspaceAdmission.Dispatch(first.registration(),first.attempt())).thenApply(result -> {
                entered.countDown();
                try { if (!release.await(10,TimeUnit.SECONDS)) throw new AssertionError("callback timeout"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
                return result;
            });
            assertTrue(entered.await(5,TimeUnit.SECONDS));
            var next=admission.dispatch(new WorkspaceAdmission.Dispatch(second.registration(),second.attempt())).toCompletableFuture().get(5,TimeUnit.SECONDS);
            assertInstanceOf(WorkspaceAdmission.Decision.Allowed.class,next);
            release.countDown();
            assertInstanceOf(WorkspaceAdmission.Decision.Allowed.class,blocked.toCompletableFuture().get(5,TimeUnit.SECONDS));
        } finally { release.countDown(); }
    }
    @Test void acceptedClaimFinalizationSurvivesSaturatedCompletionDelivery() throws Exception {
        String actor="saturation-user-"+UUID.randomUUID(),workspace=workspaces.personal(actor).id();
        var fixture=fixture(actor,workspace);
        var claim=assertInstanceOf(WorkspaceAdmission.Decision.Allowed.class,admission.dispatch(
                new WorkspaceAdmission.Dispatch(fixture.registration(),fixture.attempt())).toCompletableFuture().join()).claim();
        var entered=new CountDownLatch(4); var release=new CountDownLatch(1);
        try {
            for (int i=0;i<4;i++) admission.cancellationState(fixture.registration()).thenApply(result -> {
                entered.countDown();
                try { if (!release.await(15,TimeUnit.SECONDS)) throw new AssertionError("callback timeout"); }
                catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new RuntimeException(ex); }
                return result;
            });
            assertTrue(entered.await(5,TimeUnit.SECONDS));
            for (int i=0;i<256;i++) admission.cancellationState(fixture.registration());
            var result=admission.finalizeAttempt(new WorkspaceAdmission.FinalizeAttempt(fixture.registration(),fixture.attempt(),claim,
                    WorkspaceAdmission.TerminalKind.UNKNOWN)).toCompletableFuture();
            boolean durable;
            try {
                var value=result.get(2,TimeUnit.SECONDS);
                durable=value==WorkspaceAdmission.FinalizationResult.SETTLED || Boolean.TRUE.equals(jdbc.queryForObject(
                        "select exists(select 1 from workspace_admission_finalization where attempt_id=?)",Boolean.class,fixture.attempt()));
            } catch (java.util.concurrent.TimeoutException blocked) {
                durable=Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from workspace_admission_finalization where attempt_id=?)",Boolean.class,fixture.attempt()));
            }
            assertTrue(durable,"an accepted claim's terminal evidence must survive completion saturation");
        } finally { release.countDown(); }
    }

    @Test void collidingWorkspaceMayOverloadButNeverInheritsOtherWorkspacePolicyOrCancellation() throws Exception {
        String suffix=UUID.randomUUID().toString().substring(0,8), a="Aa"+suffix, b="BB"+suffix;
        assertEquals(a.hashCode(),b.hashCode());
        String actor="collision-user-"+UUID.randomUUID();
        jdbc.update("insert into workspace(id,name) values (?,?),(?,?)",a,"A",b,"B");
        jdbc.update("insert into workspace_member(workspace_id,user_id,role,active) values (?,?,'MANAGER',true),(?,?,'MANAGER',true)",a,actor,b,actor);
        var first=fixture(actor,a); var other=fixture(actor,b);
        var entered=new CountDownLatch(4); var release=new CountDownLatch(1);
        var queued=new java.util.ArrayList<java.util.concurrent.CompletableFuture<?>>();
        try {
            for (int i=0;i<4;i++) admission.cancellationState(first.registration()).thenApply(result -> {
                entered.countDown();
                try { if (!release.await(15,TimeUnit.SECONDS)) throw new AssertionError("callback timeout"); }
                catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new RuntimeException(ex); }
                return result;
            });
            assertTrue(entered.await(5,TimeUnit.SECONDS));
            for (int i=0;i<256;i++) queued.add(admission.cancellationState(first.registration()).toCompletableFuture());
            var outcome=admission.register(new WorkspaceAdmission.Register(b,"probe-"+UUID.randomUUID(),actor,
                    other.registration().conversationId(),WorkspaceAdmission.Partition.REAL)).toCompletableFuture().get(2,TimeUnit.SECONDS);
            var denied=assertInstanceOf(WorkspaceAdmission.RegisterResult.Denied.class,outcome);
            assertEquals(WorkspaceAdmission.Reason.OVERLOADED,denied.reason());
        } finally { release.countDown(); }
        java.util.concurrent.CompletableFuture.allOf(queued.toArray(java.util.concurrent.CompletableFuture[]::new)).get(10,TimeUnit.SECONDS);
        assertEquals(WorkspaceAdmission.CancellationState.NONE,admission.cancellationState(other.registration()).toCompletableFuture().get(5,TimeUnit.SECONDS).state());
        assertInstanceOf(WorkspaceAdmission.Decision.Allowed.class,admission.dispatch(new WorkspaceAdmission.Dispatch(other.registration(),other.attempt())).toCompletableFuture().get(5,TimeUnit.SECONDS));
    }

    @Test void acceptedFinalizationsRemainRecoverableWhenCriticalNotificationsAlsoSaturate() throws Exception {
        String actor="critical-user-"+UUID.randomUUID(),workspace=workspaces.personal(actor).id();
        var items=new java.util.ArrayList<Fixture>(); var fences=new java.util.ArrayList<WorkspaceAdmission.ClaimFence>();
        for (int i=0;i<69;i++) {
            var f=fixture(actor,workspace); items.add(f);
            fences.add(assertInstanceOf(WorkspaceAdmission.Decision.Allowed.class,admission.dispatch(
                    new WorkspaceAdmission.Dispatch(f.registration(),f.attempt())).toCompletableFuture().join()).claim());
        }
        var entered=new CountDownLatch(4); var release=new CountDownLatch(1);
        try {
            for (int i=0;i<4;i++) {
                var f=items.get(i); var claim=fences.get(i);
                admission.finalizeAttempt(new WorkspaceAdmission.FinalizeAttempt(f.registration(),f.attempt(),claim,WorkspaceAdmission.TerminalKind.UNKNOWN)).thenApply(result -> {
                    entered.countDown();
                    try { if (!release.await(30,TimeUnit.SECONDS)) throw new AssertionError("callback timeout"); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
                    return result;
                });
            }
            assertTrue(entered.await(10,TimeUnit.SECONDS));
            for (int i=4;i<68;i++) {
                var f=items.get(i); admission.finalizeAttempt(new WorkspaceAdmission.FinalizeAttempt(f.registration(),f.attempt(),fences.get(i),WorkspaceAdmission.TerminalKind.UNKNOWN));
            }
            var last=items.get(68);
            var result=admission.finalizeAttempt(new WorkspaceAdmission.FinalizeAttempt(last.registration(),last.attempt(),fences.get(68),WorkspaceAdmission.TerminalKind.UNKNOWN)).toCompletableFuture();
            boolean persisted;
            try {
                var outcome=result.get(2,TimeUnit.SECONDS);
                persisted=outcome==WorkspaceAdmission.FinalizationResult.SETTLED || Boolean.TRUE.equals(jdbc.queryForObject(
                        "select exists(select 1 from workspace_admission_finalization where attempt_id=?)",Boolean.class,last.attempt()));
            } catch (java.util.concurrent.TimeoutException blocked) {
                persisted=Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from workspace_admission_finalization where attempt_id=?)",Boolean.class,last.attempt()));
            }
            assertTrue(persisted,"terminal evidence for every previously accepted claim must remain durable");
        } finally { release.countDown(); }
    }

    @Test void saturatedTerminalIntakeIsRetriedWithoutCallerDrain() throws Exception {
        String actor="inbox-user-"+UUID.randomUUID(), workspace=workspaces.personal(actor).id();
        var items=new java.util.ArrayList<Fixture>(); var fences=new java.util.ArrayList<WorkspaceAdmission.ClaimFence>();
        for (int i=0;i<69;i++) {
            var f=fixture(actor,workspace); items.add(f);
            fences.add(assertInstanceOf(WorkspaceAdmission.Decision.Allowed.class,admission.dispatch(
                    new WorkspaceAdmission.Dispatch(f.registration(),f.attempt())).toCompletableFuture().join()).claim());
        }
        var entered=new CountDownLatch(4); var release=new CountDownLatch(1);
        try {
            for (int i=0;i<4;i++) {
                var f=items.get(i); var claim=fences.get(i);
                admission.finalizeAttempt(new WorkspaceAdmission.FinalizeAttempt(f.registration(),f.attempt(),claim,WorkspaceAdmission.TerminalKind.UNKNOWN)).thenApply(result -> {
                    entered.countDown();
                    try { if (!release.await(30,TimeUnit.SECONDS)) throw new AssertionError("callback timeout"); }
                    catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new RuntimeException(ex); }
                    return result;
                });
            }
            assertTrue(entered.await(10,TimeUnit.SECONDS));
            for (int i=4;i<69;i++) {
                var f=items.get(i);
                admission.finalizeAttempt(new WorkspaceAdmission.FinalizeAttempt(f.registration(),f.attempt(),fences.get(i),WorkspaceAdmission.TerminalKind.UNKNOWN));
            }
            assertEquals("PENDING",jdbc.queryForObject("select state from workspace_admission_finalization where attempt_id=?",String.class,items.get(68).attempt()));
        } finally { release.countDown(); }
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        String state;
        do {
            state=jdbc.queryForObject("select state from workspace_admission_finalization where attempt_id=?",String.class,items.get(68).attempt());
            if ("SETTLED".equals(state)) break;
            Thread.sleep(50);
        } while (System.nanoTime()<deadline);
        assertEquals("SETTLED",state,"durable intake must be retried after notification capacity recovers");
    }

    @Test void terminalRegistrationRejectsLateAttemptBegin() {
        String actor="late-begin-user-"+UUID.randomUUID(),workspace=workspaces.personal(actor).id();
        var f=fixture(actor,workspace);
        admission.finalizeAttempt(new WorkspaceAdmission.FinalizeAttempt(f.registration(),f.attempt(),null,
                WorkspaceAdmission.TerminalKind.NOT_DISPATCHED)).toCompletableFuture().join();
        jdbc.update("update chat_run set status='FAILED' where id=?",f.registration().runId());
        assertEquals(WorkspaceAdmission.TerminalRunCompletion.COMPLETED,
                admission.completeTerminalRun(f.registration().runId()).toCompletableFuture().join());
        assertThrows(RuntimeException.class,() -> ledger.begin(new UsageLedger.Dispatch(f.registration().runId(),workspace,actor,
                "FAKE","test","model","FAKE","1",false,"UNKNOWN")));
        assertEquals(1,jdbc.queryForObject("select count(*) from provider_attempt where run_id=?",Integer.class,f.registration().runId()));
    }

    @Test void failedFinalizationIntakePreservesClaimForAuthoritativeRecovery() {
        String actor="intake-failure-"+UUID.randomUUID(),workspace=workspaces.personal(actor).id();
        var f=fixture(actor,workspace);
        var claim=assertInstanceOf(WorkspaceAdmission.Decision.Allowed.class,admission.dispatch(
                new WorkspaceAdmission.Dispatch(f.registration(),f.attempt())).toCompletableFuture().join()).claim();
        String function="fail_intake_"+UUID.randomUUID().toString().replace("-","");
        jdbc.execute("create function "+function+"() returns trigger language plpgsql as $$ begin if new.attempt_id='"+f.attempt()+"' then raise exception 'injected finalization intake failure'; end if; return new; end $$");
        jdbc.execute("create trigger "+function+" before insert on workspace_admission_finalization for each row execute function "+function+"()");
        var command=new WorkspaceAdmission.FinalizeAttempt(f.registration(),f.attempt(),claim,WorkspaceAdmission.TerminalKind.UNKNOWN);
        try {
            assertEquals(WorkspaceAdmission.FinalizationResult.RECOVERY_REQUIRED,admission.finalizeAttempt(command).toCompletableFuture().join());
            assertInstanceOf(WorkspaceAdmission.ClaimInspection.Unknown.class,admission.inspectClaim(f.registration(),f.attempt()).toCompletableFuture().join());
            assertEquals(1,jdbc.queryForObject("select count(*) from workspace_admission_claim where claim_id=? and attempt_id=?",Integer.class,claim.claimId(),f.attempt()));
            assertEquals("IN_PROGRESS",jdbc.queryForObject("select execution_outcome from provider_attempt where id=?",String.class,f.attempt()));
            jdbc.execute("drop trigger "+function+" on workspace_admission_finalization");
            assertEquals(claim,assertInstanceOf(WorkspaceAdmission.ClaimInspection.Found.class,
                    admission.recoverClaim(f.registration(),f.attempt()).toCompletableFuture().join()).claim());
            assertEquals(WorkspaceAdmission.FinalizationResult.SETTLED,admission.finalizeAttempt(command).toCompletableFuture().join());
        } finally {
            jdbc.execute("drop trigger if exists "+function+" on workspace_admission_finalization");
            jdbc.execute("drop function if exists "+function+"()");
        }
    }

    @Test void responseIdentityWriterCommitsBeforeLaterWorkspaceAdmission() throws Exception {
        String actor="response-order-"+UUID.randomUUID(),workspace=workspaces.personal(actor).id();
        var fixture=fixture(actor,workspace);
        try (var connection=dataSource.getConnection()) {
            connection.setAutoCommit(false);
            int blocker;
            try(var statement=connection.createStatement(); var rs=statement.executeQuery("select pg_backend_pid()")) { rs.next(); blocker=rs.getInt(1); }
            try(var lock=connection.prepareStatement("select id from provider_attempt where id=? for update")) {
                lock.setString(1,fixture.attempt()); lock.executeQuery().close();
            }
            var write=java.util.concurrent.CompletableFuture.runAsync(()->ledger.recordResponseId(fixture.attempt(),"synthetic-response"));
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            boolean blocked;
            do {
                blocked=Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from pg_stat_activity where ?=any(pg_blocking_pids(pid)))",Boolean.class,blocker));
                if (blocked) break;
                Thread.sleep(20);
            } while (System.nanoTime()<deadline);
            assertTrue(blocked,"response writer must reach the held attempt lock");
            var next=admission.register(new WorkspaceAdmission.Register(workspace,"later-"+UUID.randomUUID(),actor,
                    fixture.registration().conversationId(),WorkspaceAdmission.Partition.REAL)).toCompletableFuture();
            try {
                assertThrows(java.util.concurrent.TimeoutException.class,()->next.get(500,TimeUnit.MILLISECONDS));
            } finally { connection.commit(); }
            write.get(5,TimeUnit.SECONDS);
            assertInstanceOf(WorkspaceAdmission.RegisterResult.Registered.class,next.get(5,TimeUnit.SECONDS));
            assertEquals("synthetic-response",jdbc.queryForObject("select provider_response_id from provider_attempt where id=?",String.class,fixture.attempt()));
        }
    }

    @Test void legacyDispatchWriterCommitsBeforeLaterWorkspaceAdmission() throws Exception {
        String actor="legacy-order-"+UUID.randomUUID(),workspace=workspaces.personal(actor).id();
        var fixture=fixture(actor,workspace);
        try (var connection=dataSource.getConnection()) {
            connection.setAutoCommit(false);
            int blocker;
            try(var statement=connection.createStatement(); var rs=statement.executeQuery("select pg_backend_pid()")) { rs.next(); blocker=rs.getInt(1); }
            try(var lock=connection.prepareStatement("select id from provider_attempt where id=? for update")) {
                lock.setString(1,fixture.attempt()); lock.executeQuery().close();
            }
            var write=java.util.concurrent.CompletableFuture.supplyAsync(()->ledger.markDispatched(fixture.attempt(),java.time.Clock::instant));
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            boolean blocked;
            do {
                blocked=Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from pg_stat_activity where ?=any(pg_blocking_pids(pid)))",Boolean.class,blocker));
                if (blocked) break;
                Thread.sleep(20);
            } while (System.nanoTime()<deadline);
            assertTrue(blocked,"legacy writer must reach the held attempt lock");
            var next=admission.register(new WorkspaceAdmission.Register(workspace,"later-"+UUID.randomUUID(),actor,
                    fixture.registration().conversationId(),WorkspaceAdmission.Partition.REAL)).toCompletableFuture();
            try { assertThrows(java.util.concurrent.TimeoutException.class,()->next.get(500,TimeUnit.MILLISECONDS)); }
            finally { connection.commit(); }
            assertTrue(write.get(5,TimeUnit.SECONDS));
            assertInstanceOf(WorkspaceAdmission.RegisterResult.Registered.class,next.get(5,TimeUnit.SECONDS));
        }
    }

    @Test void terminalIntakeSurvivesBothNotificationPoolsSaturatedAndAutomaticallyCompletes() throws Exception {
        String actor="both-pools-"+UUID.randomUUID(),workspace=workspaces.personal(actor).id();
        var items=new java.util.ArrayList<Fixture>(); var fences=new java.util.ArrayList<WorkspaceAdmission.ClaimFence>();
        for (int i=0;i<69;i++) {
            var f=fixture(actor,workspace); items.add(f);
            fences.add(assertInstanceOf(WorkspaceAdmission.Decision.Allowed.class,admission.dispatch(
                    new WorkspaceAdmission.Dispatch(f.registration(),f.attempt())).toCompletableFuture().join()).claim());
        }
        var last=items.getLast();
        jdbc.update("update chat_run set status='FAILED' where id=?",last.registration().runId());
        var normalEntered=new CountDownLatch(4); var criticalEntered=new CountDownLatch(4); var release=new CountDownLatch(1);
        try {
            for (int i=0;i<4;i++) admission.cancellationState(last.registration()).thenApply(value -> {
                normalEntered.countDown();
                try { if (!release.await(30,TimeUnit.SECONDS)) throw new AssertionError("normal callback timeout"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
                return value;
            });
            assertTrue(normalEntered.await(10,TimeUnit.SECONDS));
            for (int i=0;i<256;i++) admission.cancellationState(last.registration());
            for (int i=0;i<4;i++) {
                var f=items.get(i); var claim=fences.get(i);
                admission.finalizeAttempt(new WorkspaceAdmission.FinalizeAttempt(f.registration(),f.attempt(),claim,WorkspaceAdmission.TerminalKind.UNKNOWN)).thenApply(value -> {
                    criticalEntered.countDown();
                    try { if (!release.await(30,TimeUnit.SECONDS)) throw new AssertionError("critical callback timeout"); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
                    return value;
                });
            }
            assertTrue(criticalEntered.await(10,TimeUnit.SECONDS));
            for (int i=4;i<68;i++) {
                var f=items.get(i);
                admission.finalizeAttempt(new WorkspaceAdmission.FinalizeAttempt(f.registration(),f.attempt(),fences.get(i),WorkspaceAdmission.TerminalKind.UNKNOWN));
            }
            var denied=assertInstanceOf(WorkspaceAdmission.RegisterResult.Denied.class,admission.register(new WorkspaceAdmission.Register(
                    workspace,"overload-"+UUID.randomUUID(),actor,last.registration().conversationId(),WorkspaceAdmission.Partition.REAL)).toCompletableFuture().get(5,TimeUnit.SECONDS));
            assertEquals(WorkspaceAdmission.Reason.OVERLOADED,denied.reason());
            admission.finalizeAttempt(new WorkspaceAdmission.FinalizeAttempt(last.registration(),last.attempt(),fences.getLast(),WorkspaceAdmission.TerminalKind.UNKNOWN));
            assertEquals("PENDING",jdbc.queryForObject("select state from workspace_admission_finalization where attempt_id=?",String.class,last.attempt()));
        } finally { release.countDown(); }
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
        String settled=null,registration=null;
        do {
            settled=jdbc.queryForObject("select state from workspace_admission_finalization where attempt_id=?",String.class,last.attempt());
            registration=jdbc.queryForObject("select status from workspace_admission_registration where token=?",String.class,last.registration().token());
            if ("SETTLED".equals(settled) && "TERMINAL".equals(registration)) break;
            Thread.sleep(50);
        } while (System.nanoTime()<deadline);
        assertEquals("SETTLED",settled,"durable intent must settle after capacity returns");
        assertEquals("TERMINAL",registration,"terminal Run registration must recover without caller drain");
    }

    @Test void sourceAppendWaitsForRegistrationBeforeLockingAttempt() throws Exception {
        String actor="lock-order-"+UUID.randomUUID(),workspace=workspaces.personal(actor).id();
        var fixture=fixture(actor,workspace);
        assertInstanceOf(WorkspaceAdmission.Decision.Allowed.class,admission.dispatch(
                new WorkspaceAdmission.Dispatch(fixture.registration(),fixture.attempt())).toCompletableFuture().join());
        long advisory=Math.abs(UUID.randomUUID().getMostSignificantBits());
        String function="pause_source_"+UUID.randomUUID().toString().replace("-","");
        jdbc.execute("create function "+function+"() returns trigger language plpgsql as $$ begin if new.attempt_id='"+fixture.attempt()+"' then perform pg_advisory_xact_lock("+advisory+"); end if; return new; end $$");
        jdbc.execute("create trigger "+function+" before insert on usage_snapshot for each row execute function "+function+"()");
        java.util.concurrent.CompletableFuture<Void> append=null;
        try(var registrationLock=dataSource.getConnection();var sourceLatch=dataSource.getConnection()) {
            registrationLock.setAutoCommit(false); sourceLatch.setAutoCommit(false);
            int registrationPid,sourcePid;
            try(var s=registrationLock.createStatement();var rs=s.executeQuery("select pg_backend_pid()")) { rs.next(); registrationPid=rs.getInt(1); }
            try(var s=sourceLatch.createStatement();var rs=s.executeQuery("select pg_backend_pid()")) { rs.next(); sourcePid=rs.getInt(1); }
            try(var s=registrationLock.prepareStatement("select token from workspace_admission_registration where token=? for update")) { s.setString(1,fixture.registration().token()); s.executeQuery().close(); }
            try(var s=sourceLatch.prepareStatement("select pg_advisory_xact_lock(?)")) { s.setLong(1,advisory); s.executeQuery().close(); }
            append=java.util.concurrent.CompletableFuture.runAsync(()->ledger.append(fixture.attempt(),new UsageLedger.Snapshot(
                    "lock-order-event",1,"FINAL","FAKE_RAW","1","1","1","COMPLETE","{\"input_tokens\":1}")));
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5); boolean blocked;
            do {
                blocked=Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from pg_stat_activity where ?=any(pg_blocking_pids(pid)) or ?=any(pg_blocking_pids(pid)))",Boolean.class,registrationPid,sourcePid));
                if (blocked) break; Thread.sleep(20);
            } while (System.nanoTime()<deadline);
            assertTrue(blocked,"append must reach one of the controlled PG latches");
            try(var probe=dataSource.getConnection()) {
                probe.setAutoCommit(false);
                assertDoesNotThrow(()-> { try(var s=probe.prepareStatement("select id from provider_attempt where id=? for update nowait")) { s.setString(1,fixture.attempt()); s.executeQuery().close(); } },
                        "a queued source writer must not lock attempt before registration");
                probe.rollback();
            } finally {
                registrationLock.rollback(); sourceLatch.rollback();
            }
            append.get(5,TimeUnit.SECONDS);
        } finally {
            jdbc.execute("drop trigger if exists "+function+" on usage_snapshot");
            jdbc.execute("drop function if exists "+function+"()");
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"SOURCE_FIRST","INTAKE_FIRST"})
    void sourceAndFinalizationConvergeWithCapOutboxInBothLockOrders(String order) throws Exception {
        String actor="intake-source-"+UUID.randomUUID(),workspace=workspaces.personal(actor).id();
        var fixture=fixture(actor,workspace);
        var claim=assertInstanceOf(WorkspaceAdmission.Decision.Allowed.class,admission.dispatch(
                new WorkspaceAdmission.Dispatch(fixture.registration(),fixture.attempt())).toCompletableFuture().join()).claim();
        var scope=new io.github.samzhu.bot.usage.WorkspaceMonthlyBudgetOperations.Scope(workspace,WorkspaceAdmission.Partition.REAL,
                java.time.YearMonth.now(java.time.ZoneId.of("Asia/Taipei")));
        budgets.configure(new io.github.samzhu.bot.usage.WorkspaceMonthlyBudgetOperations.ConfigureCommand(scope,actor,true,java.math.BigDecimal.ZERO,0)).toCompletableFuture().join();
        long advisory=Math.abs(UUID.randomUUID().getMostSignificantBits());
        String function="pause_order_"+UUID.randomUUID().toString().replace("-","");
        String table=order.equals("SOURCE_FIRST")?"usage_snapshot":"workspace_admission_finalization";
        String field=order.equals("SOURCE_FIRST")?"attempt_id":"attempt_id";
        jdbc.execute("create function "+function+"() returns trigger language plpgsql as $$ begin if new."+field+"='"+fixture.attempt()+"' then perform pg_advisory_xact_lock("+advisory+"); end if; return new; end $$");
        jdbc.execute("create trigger "+function+" before insert on "+table+" for each row execute function "+function+"()");
        try(var latch=dataSource.getConnection()) {
            latch.setAutoCommit(false); int latchPid;
            try(var s=latch.createStatement();var rs=s.executeQuery("select pg_backend_pid()")) { rs.next(); latchPid=rs.getInt(1); }
            try(var s=latch.prepareStatement("select pg_advisory_xact_lock(?)")) { s.setLong(1,advisory); s.executeQuery().close(); }
            java.util.function.Supplier<Object> source=()-> { ledger.append(fixture.attempt(),new UsageLedger.Snapshot("ordered-source",1,"FINAL","FAKE_RAW","1","1","1","COMPLETE","{\"input_tokens\":1}")); return null; };
            java.util.function.Supplier<Object> terminal=()->admission.finalizeAttempt(new WorkspaceAdmission.FinalizeAttempt(
                    fixture.registration(),fixture.attempt(),claim,WorkspaceAdmission.TerminalKind.SUCCEEDED)).toCompletableFuture().join();
            var first=java.util.concurrent.CompletableFuture.supplyAsync(order.equals("SOURCE_FIRST")?source:terminal);
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5); Integer firstPid=null;
            do {
                var blocked=jdbc.query("select pid from pg_stat_activity where ?=any(pg_blocking_pids(pid))",(rs,n)->rs.getInt(1),latchPid);
                if (!blocked.isEmpty()) { firstPid=blocked.getFirst(); break; }
                Thread.sleep(20);
            } while(System.nanoTime()<deadline);
            assertNotNull(firstPid,"first writer must reach the controlled PG latch");
            var second=java.util.concurrent.CompletableFuture.supplyAsync(order.equals("SOURCE_FIRST")?terminal:source);
            boolean secondBlocked=false; deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            try {
                do {
                    secondBlocked=Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from pg_stat_activity where ?=any(pg_blocking_pids(pid)))",Boolean.class,firstPid));
                    if(secondBlocked) break; Thread.sleep(20);
                } while(System.nanoTime()<deadline);
                assertTrue(secondBlocked,"the second writer must wait on the first writer's transaction");
            } finally { latch.rollback(); }
            Object firstResult=first.get(10,TimeUnit.SECONDS),secondResult=second.get(10,TimeUnit.SECONDS);
            assertEquals(WorkspaceAdmission.FinalizationResult.SETTLED,order.equals("SOURCE_FIRST")?secondResult:firstResult);
            assertEquals("SUCCEEDED",jdbc.queryForObject("select execution_outcome from provider_attempt where id=?",String.class,fixture.attempt()));
            assertEquals("SETTLED",jdbc.queryForObject("select state from workspace_admission_finalization where attempt_id=?",String.class,fixture.attempt()));
            assertEquals(1,jdbc.queryForObject("select count(*) from usage_snapshot where attempt_id=? and event_id='ordered-source'",Integer.class,fixture.attempt()));
            assertEquals(1,jdbc.queryForObject("select count(*) from workspace_admission_effect_outbox where registration_token=? and claim_id=? and target_attempt_id=?",Integer.class,fixture.registration().token(),claim.claimId(),fixture.attempt()));
        } finally {
            jdbc.execute("drop trigger if exists "+function+" on "+table);
            jdbc.execute("drop function if exists "+function+"()");
        }
    }

    @Test void failedClaimCommitRequiresAuthorityCheckBeforeRetry() {
        String actor="claim-recovery-"+UUID.randomUUID(),workspace=workspaces.personal(actor).id();
        var fixture=fixture(actor,workspace);
        String function="fail_claim_"+UUID.randomUUID().toString().replace("-","");
        jdbc.execute("create function "+function+"() returns trigger language plpgsql as $$ begin if new.attempt_id='"+fixture.attempt()+"' then raise exception 'injected claim failure'; end if; return new; end $$");
        jdbc.execute("create trigger "+function+" before insert on workspace_admission_claim for each row execute function "+function+"()");
        try {
            var denied=assertInstanceOf(WorkspaceAdmission.Decision.Denied.class,admission.dispatch(new WorkspaceAdmission.Dispatch(fixture.registration(),fixture.attempt())).toCompletableFuture().join());
            assertEquals(WorkspaceAdmission.Reason.RECOVERY_REQUIRED,denied.reason());
            assertNull(jdbc.queryForObject("select started_at from provider_attempt where id=?",java.sql.Timestamp.class,fixture.attempt()));
            assertInstanceOf(WorkspaceAdmission.ClaimInspection.Unknown.class,admission.inspectClaim(fixture.registration(),fixture.attempt()).toCompletableFuture().join());
            assertEquals(WorkspaceAdmission.FinalizationResult.RECOVERY_REQUIRED,admission.finalizeAttempt(new WorkspaceAdmission.FinalizeAttempt(
                    fixture.registration(),fixture.attempt(),null,WorkspaceAdmission.TerminalKind.NOT_DISPATCHED)).toCompletableFuture().join());
            assertEquals("IN_PROGRESS",jdbc.queryForObject("select execution_outcome from provider_attempt where id=?",String.class,fixture.attempt()));
            assertThrows(RuntimeException.class,() -> ledger.notDispatched(fixture.attempt(),"test"));
            assertEquals("IN_PROGRESS",jdbc.queryForObject("select execution_outcome from provider_attempt where id=?",String.class,fixture.attempt()));
            jdbc.execute("drop trigger "+function+" on workspace_admission_claim");
            assertInstanceOf(WorkspaceAdmission.ClaimInspection.Absent.class,admission.recoverClaim(fixture.registration(),fixture.attempt()).toCompletableFuture().join());
            assertInstanceOf(WorkspaceAdmission.Decision.Denied.class,admission.dispatch(new WorkspaceAdmission.Dispatch(fixture.registration(),fixture.attempt())).toCompletableFuture().join());
            assertEquals(WorkspaceAdmission.FinalizationResult.SETTLED,admission.finalizeAttempt(new WorkspaceAdmission.FinalizeAttempt(
                    fixture.registration(),fixture.attempt(),null,WorkspaceAdmission.TerminalKind.NOT_DISPATCHED)).toCompletableFuture().join());
        } finally {
            jdbc.execute("drop trigger if exists "+function+" on workspace_admission_claim");
            jdbc.execute("drop function if exists "+function+"()");
        }
    }

    @Test void mismatchedAttemptPartitionIsRejectedAsUnauthorizedWithoutClaim() {
        String actor="partition-user-"+UUID.randomUUID(),workspace=workspaces.personal(actor).id();
        var f=fixture(actor,workspace);
        jdbc.update("update provider_attempt set simulation=true where id=?",f.attempt());
        var decision=assertInstanceOf(WorkspaceAdmission.Decision.Denied.class,admission.dispatch(
                new WorkspaceAdmission.Dispatch(f.registration(),f.attempt())).toCompletableFuture().join());
        assertEquals(WorkspaceAdmission.Reason.UNAUTHORIZED,decision.reason());
        assertNull(jdbc.queryForObject("select started_at from provider_attempt where id=?",java.sql.Timestamp.class,f.attempt()));
        assertEquals(0,jdbc.queryForObject("select count(*) from workspace_admission_dispatch where attempt_id=?",Integer.class,f.attempt()));
    }

    @Test void forgedFinalizationCannotBlockLegitimateWorkspace() {
        String actor="forgery-user-"+UUID.randomUUID(),workspace=workspaces.personal(actor).id();
        var f=fixture(actor,workspace);
        var fake=new WorkspaceAdmission.Registration("forged-token",workspace,f.registration().runId(),actor,
                f.registration().conversationId(),WorkspaceAdmission.Partition.REAL);
        assertEquals(WorkspaceAdmission.FinalizationResult.RECOVERY_REQUIRED,admission.finalizeAttempt(
                new WorkspaceAdmission.FinalizeAttempt(fake,f.attempt(),null,WorkspaceAdmission.TerminalKind.NOT_DISPATCHED)).toCompletableFuture().join());
        assertEquals(WorkspaceAdmission.CancellationState.NONE,admission.cancellationState(f.registration()).toCompletableFuture().join().state());
        assertInstanceOf(WorkspaceAdmission.Decision.Allowed.class,admission.dispatch(new WorkspaceAdmission.Dispatch(f.registration(),f.attempt())).toCompletableFuture().join());
    }

    @Test void attemptBeginWaitsBehindEarlierWorkspaceClaim() throws Exception {
        String actor="begin-order-"+UUID.randomUUID(),workspace=workspaces.personal(actor).id();
        var fixture=fixture(actor,workspace);
        java.util.concurrent.CompletableFuture<String> later;
        java.util.concurrent.CompletionStage<WorkspaceAdmission.Decision> claim;
        try(var connection=dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try(var lock=connection.prepareStatement("select token from workspace_admission_registration where token=? for update")) {
                lock.setString(1,fixture.registration().token()); lock.executeQuery().close();
            }
            claim=admission.dispatch(new WorkspaceAdmission.Dispatch(fixture.registration(),fixture.attempt()));
            var metadata=new UsageLedger.Dispatch(fixture.registration().runId(),workspace,actor,"FAKE","test","model","FAKE","1",false,"UNKNOWN");
            later=java.util.concurrent.CompletableFuture.supplyAsync(() -> ledger.begin(metadata));
            assertThrows(java.util.concurrent.TimeoutException.class,() -> later.get(500,TimeUnit.MILLISECONDS));
            connection.commit();
        }
        assertInstanceOf(WorkspaceAdmission.Decision.Allowed.class,claim.toCompletableFuture().get(5,TimeUnit.SECONDS));
        assertNotNull(later.get(5,TimeUnit.SECONDS));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value=WorkspaceAdmission.TerminalKind.class,names={"UNKNOWN","CANCELLED"})
    void fullLaneQueueAndCriticalNotificationsPreserveAcceptedTerminalIntent(WorkspaceAdmission.TerminalKind kind) throws Exception {
        String actor="full-lane-"+UUID.randomUUID(),workspace=workspaces.personal(actor).id();
        var target=fixture(actor,workspace); var blocker=fixture(actor,workspace);
        var claim=assertInstanceOf(WorkspaceAdmission.Decision.Allowed.class,admission.dispatch(new WorkspaceAdmission.Dispatch(target.registration(),target.attempt())).toCompletableFuture().join()).claim();
        jdbc.update("update chat_run set status=? where id=?",kind==WorkspaceAdmission.TerminalKind.CANCELLED?"CANCELLED":"FAILED",target.registration().runId());
        var entered=new CountDownLatch(4); var release=new CountDownLatch(1);
        try {
            for(int i=0;i<4;i++) admission.recoverClaim(target.registration(),target.attempt()).thenApply(value->{
                entered.countDown(); try { if(!release.await(30,TimeUnit.SECONDS)) throw new AssertionError("callback timeout"); }
                catch(InterruptedException ex) { Thread.currentThread().interrupt(); throw new RuntimeException(ex); } return value;
            });
            assertTrue(entered.await(10,TimeUnit.SECONDS));
            for(int i=0;i<64;i++) admission.recoverClaim(target.registration(),target.attempt());
            assertInstanceOf(WorkspaceAdmission.ClaimInspection.Unknown.class,admission.recoverClaim(target.registration(),target.attempt()).toCompletableFuture().get(3,TimeUnit.SECONDS),"critical notification slots are exhausted");
            try(var connection=dataSource.getConnection()) {
                connection.setAutoCommit(false); int blockerPid;
                try(var stmt=connection.createStatement();var rows=stmt.executeQuery("select pg_backend_pid()")) { rows.next(); blockerPid=rows.getInt(1); }
                try(var stmt=connection.prepareStatement("select token from workspace_admission_registration where token=? for update")) { stmt.setString(1,blocker.registration().token()); stmt.executeQuery().close(); }
                var occupying=admission.publish(blocker.registration()).toCompletableFuture();
                long wait=System.nanoTime()+TimeUnit.SECONDS.toNanos(8); boolean blocked=false;
                while(System.nanoTime()<wait) { blocked=Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from pg_stat_activity where ?=any(pg_blocking_pids(pid)))",Boolean.class,blockerPid)); if(blocked) break; Thread.yield(); }
                assertTrue(blocked,"lane worker must be blocked on a PG row");
                var queued=new java.util.ArrayList<java.util.concurrent.CompletableFuture<?>>();
                for(int i=0;i<256;i++) queued.add(admission.cancellationState(target.registration()).toCompletableFuture());
                var denied=assertInstanceOf(WorkspaceAdmission.RegisterResult.Denied.class,admission.register(new WorkspaceAdmission.Register(workspace,"extra-"+UUID.randomUUID(),actor,target.registration().conversationId(),WorkspaceAdmission.Partition.REAL)).toCompletableFuture().get(3,TimeUnit.SECONDS));
                assertEquals(WorkspaceAdmission.Reason.OVERLOADED,denied.reason(),"the actual workspace queue rejects its 257th waiting admission");
                assertEquals(WorkspaceAdmission.FinalizationResult.RECOVERY_REQUIRED,admission.finalizeAttempt(new WorkspaceAdmission.FinalizeAttempt(target.registration(),target.attempt(),claim,kind)).toCompletableFuture().get(3,TimeUnit.SECONDS));
                assertEquals("PENDING",jdbc.queryForObject("select state from workspace_admission_finalization where attempt_id=?",String.class,target.attempt()));
                connection.rollback(); release.countDown();
                occupying.handle((v,e)->null).get(10,TimeUnit.SECONDS);
                java.util.concurrent.CompletableFuture.allOf(queued.toArray(java.util.concurrent.CompletableFuture[]::new)).get(15,TimeUnit.SECONDS);
            }
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20); String state=null,registration=null;
            do {
                state=jdbc.queryForObject("select state from workspace_admission_finalization where attempt_id=?",String.class,target.attempt());
                registration=jdbc.queryForObject("select status from workspace_admission_registration where token=?",String.class,target.registration().token());
                if("SETTLED".equals(state)&&"TERMINAL".equals(registration)) break;
                Thread.sleep(50);
            } while(System.nanoTime()<deadline);
            assertEquals("SETTLED",state); assertEquals("TERMINAL",registration);
        } finally { release.countDown(); }
    }

}
