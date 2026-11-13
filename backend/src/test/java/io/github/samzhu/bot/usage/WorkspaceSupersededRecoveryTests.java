package io.github.samzhu.bot;

import io.github.samzhu.bot.conversation.ConversationOperations;
import io.github.samzhu.bot.usage.UsageLedger;
import io.github.samzhu.bot.usage.WorkspaceAdmission;
import io.github.samzhu.bot.usage.WorkspaceMonthlyBudgetOperations;
import io.github.samzhu.bot.workspace.WorkspaceOperations;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={"bot.budget.finalization.initial-delay-ms=50", "bot.budget.finalization.delay-ms=100", "bot.budget.effect.initial-delay-ms=3600000"})
@Import({TestcontainersConfiguration.class, WorkspaceSupersededRecoveryTests.Scheduling.class})
@ActiveProfiles("test")
class WorkspaceSupersededRecoveryTests {
    @TestConfiguration @EnableScheduling static class Scheduling {}
    @Autowired WorkspaceAdmission admission;
    @Autowired WorkspaceOperations workspaces;
    @Autowired ConversationOperations conversations;
    @Autowired UsageLedger ledger;
    @Autowired WorkspaceMonthlyBudgetOperations budgets;
    @Autowired JdbcTemplate jdbc;

    record Fixture(WorkspaceAdmission.Registration registration, String attempt) {}

    private Fixture fixture(boolean attempt, boolean claim) {
        String owner="superseded-"+UUID.randomUUID(), workspace=workspaces.personal(owner).id();
        String bot="bot-"+UUID.randomUUID(), run="run-"+UUID.randomUUID();
        jdbc.update("insert into bot(id,name,target) values (?,?,'LOCAL')",bot,bot);
        var conversation=conversations.createConversation(bot,"fixture",owner,workspace);
        var registration=assertInstanceOf(WorkspaceAdmission.RegisterResult.Registered.class,
                admission.register(new WorkspaceAdmission.Register(workspace,run,owner,conversation.id(),WorkspaceAdmission.Partition.REAL)).toCompletableFuture().join()).registration();
        jdbc.update("insert into chat_run(id,conversation_id,client_request_id,input_text,status) values (?,?,?,'test','PENDING')",run,conversation.id(),UUID.randomUUID().toString());
        assertInstanceOf(WorkspaceAdmission.PublicationResult.Ready.class,admission.publish(registration).toCompletableFuture().join());
        String id=attempt?ledger.begin(new UsageLedger.Dispatch(run,workspace,owner,"FAKE","test","model","FAKE","1",false,"UNKNOWN")):null;
        if (claim) assertInstanceOf(WorkspaceAdmission.Decision.Allowed.class,admission.dispatch(new WorkspaceAdmission.Dispatch(registration,id)).toCompletableFuture().join());
        return new Fixture(registration,id);
    }

    private void insertSyntheticStale(WorkspaceAdmission.Registration r) {
        var row=jdbc.queryForMap("select pending_id,pending_epoch,pending_period_start,pending_period_end from workspace_admission_registration where token=?",r.token());
        jdbc.update("""
                insert into workspace_admission_effect_outbox(effect_id,registration_token,target_workspace_id,target_partition,target_run_id,
                    pending_id,pending_epoch,period_start,period_end,generation,delivery_status,intent_receipt,ack_receipt,acknowledged_at)
                values (?,?,?,'REAL',?,?,?,?,?,0,'STALE',?,'stale',current_timestamp)
                """,UUID.randomUUID().toString(),r.token(),r.workspaceId(),r.runId(),row.get("pending_id"),row.get("pending_epoch"),
                ((java.sql.Date)row.get("pending_period_start")).toLocalDate().minusMonths(1),row.get("pending_period_start"),UUID.randomUUID().toString());
    }

    private boolean ready(WorkspaceAdmission.Registration r) {
        var result=admission.scanSupersededNotDispatched(null,null,100).toCompletableFuture().join();
        return result instanceof WorkspaceAdmission.SupersededPage.Page page && page.facts().stream().anyMatch(f->f.registration().equals(r));
    }

    @Test void zeroAttemptIsRecoverableWithoutInventingAttempt() throws Exception {
        var f=fixture(false,false); insertSyntheticStale(f.registration());
        long deadline=System.nanoTime()+Duration.ofSeconds(15).toNanos();
        while (!ready(f.registration()) && System.nanoTime()<deadline) Thread.sleep(50);
        assertTrue(ready(f.registration()));
        assertEquals(0,jdbc.queryForObject("select count(*) from provider_attempt where run_id=?",Integer.class,f.registration().runId()));
    }

    @Test void newPeriodClaimNeverCreatesSupersededDispositionForSyntheticOldPeriodEffect() throws Exception {
        var f=fixture(true,true); insertSyntheticStale(f.registration());
        Thread.sleep(1500);
        assertEquals(0,jdbc.queryForObject("select count(*) from workspace_admission_superseded where registration_token=?",Integer.class,f.registration().token()));
        assertFalse(ready(f.registration()));
        assertEquals("IN_PROGRESS",jdbc.queryForObject("select execution_outcome from provider_attempt where id=?",String.class,f.attempt()));
    }

    @Test void settledExistingIntentKeepsItsOriginalReason() throws Exception {
        var f=fixture(true,false);
        assertEquals(WorkspaceAdmission.FinalizationResult.SETTLED,admission.finalizeAttempt(
                new WorkspaceAdmission.FinalizeAttempt(f.registration(),f.attempt(),null,WorkspaceAdmission.TerminalKind.NOT_DISPATCHED,
                        WorkspaceAdmission.TerminalReason.PROMPT_CONTEXT_UNAVAILABLE)).toCompletableFuture().join());
        insertSyntheticStale(f.registration());
        long deadline=System.nanoTime()+Duration.ofSeconds(15).toNanos();
        while (!ready(f.registration()) && System.nanoTime()<deadline) Thread.sleep(50);
        assertTrue(ready(f.registration()));
        assertEquals("PROMPT_CONTEXT_UNAVAILABLE",jdbc.queryForObject("select terminal_reason from workspace_admission_finalization where attempt_id=?",String.class,f.attempt()));
    }

    @Test void everyAttemptIsSettledBeforeWholeRunFactBecomesReady() throws Exception {
        var f=fixture(true,false); var r=f.registration();
        String second=ledger.begin(new UsageLedger.Dispatch(r.runId(),r.workspaceId(),r.actorId(),"FAKE","test","model","FAKE","1",false,"UNKNOWN"));
        assertEquals(WorkspaceAdmission.FinalizationResult.SETTLED,admission.finalizeAttempt(
                new WorkspaceAdmission.FinalizeAttempt(r,f.attempt(),null,WorkspaceAdmission.TerminalKind.NOT_DISPATCHED,
                        WorkspaceAdmission.TerminalReason.PROMPT_CONTEXT_UNAVAILABLE)).toCompletableFuture().join());
        insertSyntheticStale(r);
        long deadline=System.nanoTime()+Duration.ofSeconds(15).toNanos();
        while (!ready(r) && System.nanoTime()<deadline) Thread.sleep(50);
        assertTrue(ready(r));
        assertEquals(2,jdbc.queryForObject("select count(*) from workspace_admission_finalization where registration_token=? and state='SETTLED'",Integer.class,r.token()));
        assertEquals("PROMPT_CONTEXT_UNAVAILABLE",jdbc.queryForObject("select terminal_reason from workspace_admission_finalization where attempt_id=?",String.class,f.attempt()));
        assertEquals("BUDGET_CANCELLATION_SUPERSEDED",jdbc.queryForObject("select terminal_reason from workspace_admission_finalization where attempt_id=?",String.class,second));
    }

    @Test void budgetCancellationDoesNotImpersonateUserAndUserReplayKeepsFirstTimestamp() {
        var f=fixture(true,false); var r=f.registration();
        var scope=new WorkspaceMonthlyBudgetOperations.Scope(r.workspaceId(),r.partition(),java.time.YearMonth.now(java.time.ZoneId.of("Asia/Taipei")));
        budgets.configure(new WorkspaceMonthlyBudgetOperations.ConfigureCommand(scope,r.actorId(),true,java.math.BigDecimal.ZERO,0)).toCompletableFuture().join();
        assertNull(jdbc.queryForObject("select user_cancel_requested_at from workspace_admission_registration where token=?",java.sql.Timestamp.class,r.token()));
        assertInstanceOf(WorkspaceAdmission.Decision.BudgetCancellationPending.class,admission.dispatch(new WorkspaceAdmission.Dispatch(r,f.attempt())).toCompletableFuture().join());
        assertEquals(WorkspaceAdmission.CancelStatus.BEFORE_CLAIM,admission.cancelRun(r).toCompletableFuture().join().status());
        var first=jdbc.queryForObject("select user_cancel_requested_at from workspace_admission_registration where token=?",java.sql.Timestamp.class,r.token());
        assertNotNull(first);
        assertEquals(WorkspaceAdmission.CancelStatus.BEFORE_CLAIM,admission.cancelRun(r).toCompletableFuture().join().status());
        assertEquals(first,jdbc.queryForObject("select user_cancel_requested_at from workspace_admission_registration where token=?",java.sql.Timestamp.class,r.token()));
        assertInstanceOf(WorkspaceAdmission.Decision.BudgetCancellationPending.class,admission.dispatch(new WorkspaceAdmission.Dispatch(r,f.attempt())).toCompletableFuture().join());
    }

    @Test void revokedUserCannotCreateCancelMarker() {
        var f=fixture(true,false); var r=f.registration();
        jdbc.update("update workspace_member set active=false where workspace_id=? and user_id=?",r.workspaceId(),r.actorId());
        assertEquals(WorkspaceAdmission.CancelStatus.UNKNOWN,admission.cancelRun(r).toCompletableFuture().join().status());
        assertNull(jdbc.queryForObject("select user_cancel_requested_at from workspace_admission_registration where token=?",java.sql.Timestamp.class,r.token()));
    }

    @Test void cancellationWithoutBudgetEffectOrUserMarkerDoesNotPretendUserCancelled() {
        var f=fixture(true,false); var r=f.registration();
        jdbc.update("update workspace_admission_registration set status='CANCEL_REQUESTED' where token=?",r.token());
        var decision=assertInstanceOf(WorkspaceAdmission.Decision.Denied.class,admission.dispatch(new WorkspaceAdmission.Dispatch(r,f.attempt())).toCompletableFuture().join());
        assertEquals(WorkspaceAdmission.Reason.RECOVERY_REQUIRED,decision.reason());
    }

    @Test void stalePendingAutomaticallySettlesCommittedAttemptBeforePublishingReadyFact() throws Exception {
        String owner="superseded-"+UUID.randomUUID(), workspace=workspaces.personal(owner).id();
        String bot="bot-"+UUID.randomUUID(), run="run-"+UUID.randomUUID();
        jdbc.update("insert into bot(id,name,target) values (?,?,'LOCAL')",bot,bot);
        var conversation=conversations.createConversation(bot,"fixture",owner,workspace);
        var registration=assertInstanceOf(WorkspaceAdmission.RegisterResult.Registered.class,
                admission.register(new WorkspaceAdmission.Register(workspace,run,owner,conversation.id(),WorkspaceAdmission.Partition.REAL)).toCompletableFuture().join()).registration();
        jdbc.update("insert into chat_run(id,conversation_id,client_request_id,input_text,status) values (?,?,?,'test','PENDING')",run,conversation.id(),UUID.randomUUID().toString());
        assertInstanceOf(WorkspaceAdmission.PublicationResult.Ready.class,admission.publish(registration).toCompletableFuture().join());
        String attempt=ledger.begin(new UsageLedger.Dispatch(run,workspace,owner,"FAKE","test","model","FAKE","1",false,"UNKNOWN"));
        var row=jdbc.queryForMap("select pending_id,pending_epoch,pending_period_start,pending_period_end from workspace_admission_registration where token=?",registration.token());
        String effect=UUID.randomUUID().toString(), receipt=UUID.randomUUID().toString();
        // A synthetic already-stale outbox fact isolates recovery from sink delivery timing.
        jdbc.update("""
                insert into workspace_admission_effect_outbox(effect_id,registration_token,target_workspace_id,target_partition,target_run_id,
                    pending_id,pending_epoch,period_start,period_end,generation,delivery_status,intent_receipt,ack_receipt,acknowledged_at)
                values (?,?,?,'REAL',?,?,?,?,?,0,'STALE',?,'stale',current_timestamp)
                """,effect,registration.token(),workspace,run,row.get("pending_id"),row.get("pending_epoch"),row.get("pending_period_start"),row.get("pending_period_end"),receipt);
        long deadline=System.nanoTime()+Duration.ofSeconds(15).toNanos();
        WorkspaceAdmission.SupersededNotDispatched fact=null;
        while (System.nanoTime()<deadline) {
            var page=admission.scanSupersededNotDispatched(null,null,100).toCompletableFuture().join();
            if (page instanceof WorkspaceAdmission.SupersededPage.Page ready)
                fact=ready.facts().stream().filter(f->f.registration().equals(registration)).findFirst().orElse(null);
            if (fact!=null) break;
            Thread.sleep(50);
        }
        assertNotNull(fact,"scheduled recovery must publish a ready fact without caller drain");
        assertEquals(effect,fact.oldEffect().effectId());
        assertEquals("NOT_DISPATCHED",jdbc.queryForObject("select execution_outcome from provider_attempt where id=?",String.class,attempt));
        assertEquals("SETTLED",jdbc.queryForObject("select state from workspace_admission_finalization where attempt_id=?",String.class,attempt));
        assertEquals("BUDGET_CANCELLATION_SUPERSEDED",jdbc.queryForObject("select terminal_reason from workspace_admission_finalization where attempt_id=?",String.class,attempt));
        assertThrows(IllegalStateException.class,()->ledger.begin(new UsageLedger.Dispatch(run,workspace,owner,"FAKE","test","model","FAKE","1",false,"UNKNOWN")));
    }
}
