package io.github.samzhu.bot;

import io.github.samzhu.bot.conversation.ConversationOperations;
import io.github.samzhu.bot.usage.BudgetCancellationSink;
import io.github.samzhu.bot.usage.WorkspaceAdmission;
import io.github.samzhu.bot.usage.WorkspaceMonthlyBudgetOperations;
import io.github.samzhu.bot.workspace.WorkspaceOperations;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@SpringBootTest(properties={"bot.budget.effects.initial-delay-ms=100","bot.budget.effects.delay-ms=100"})
@Import({TestcontainersConfiguration.class, WorkspaceCancellationDeliveryTests.Scheduling.class})
@ActiveProfiles("test")
class WorkspaceCancellationDeliveryTests {
    @org.springframework.boot.test.context.TestConfiguration
    @org.springframework.scheduling.annotation.EnableScheduling
    static class Scheduling {}
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean WorkspaceAdmission admission;
    @Autowired WorkspaceMonthlyBudgetOperations budgets;
    @Autowired WorkspaceOperations workspaces;
    @Autowired ConversationOperations conversations;
    @Autowired JdbcTemplate jdbc;
    @Autowired javax.sql.DataSource dataSource;
    @MockitoBean BudgetCancellationSink sink;

    @Test void transientDeliveryFailureRetainsIntentAndRetriesSameEffect() throws Exception {
        String owner="delivery-user-"+UUID.randomUUID(),workspace=workspaces.personal(owner).id();
        var seen=new java.util.concurrent.ConcurrentLinkedQueue<BudgetCancellationSink.Effect>();
        var deliveries=new CountDownLatch(2);
        when(sink.cancel(any())).thenAnswer(invocation -> {
            var effect=(BudgetCancellationSink.Effect)invocation.getArgument(0);
            if (!workspace.equals(effect.workspaceId())) return CompletableFuture.completedFuture(new BudgetCancellationSink.Result.Retry(effect.effectId(),effect.token(),effect.target()));
            seen.add(effect); deliveries.countDown();
            return CompletableFuture.completedFuture(new BudgetCancellationSink.Result.Retry(effect.effectId(),effect.token(),effect.target()));
        });
        String bot="delivery-bot-"+UUID.randomUUID(),run="delivery-run-"+UUID.randomUUID();
        jdbc.update("insert into bot(id,name,target) values (?,?,'LOCAL')",bot,bot);
        var conversation=conversations.createConversation(bot,"fixture",owner,workspace);
        var registration=assertInstanceOf(WorkspaceAdmission.RegisterResult.Registered.class,admission.register(
                new WorkspaceAdmission.Register(workspace,run,owner,conversation.id(),WorkspaceAdmission.Partition.REAL)).toCompletableFuture().join()).registration();
        var scope=new WorkspaceMonthlyBudgetOperations.Scope(workspace,WorkspaceAdmission.Partition.REAL,YearMonth.now(ZoneId.of("Asia/Taipei")));
        budgets.configure(new WorkspaceMonthlyBudgetOperations.ConfigureCommand(scope,owner,true,BigDecimal.ZERO,0)).toCompletableFuture().join();
        assertTrue(deliveries.await(8,TimeUnit.SECONDS),"pending intent must be retried");
        var iterator=seen.iterator(); var first=iterator.next(); var second=iterator.next();
        assertEquals(first,second);
        assertNotNull(first.intentReceipt());
        assertEquals("PENDING",jdbc.queryForObject("select delivery_status from workspace_admission_effect_outbox where effect_id=?",String.class,first.effectId()));
        assertNull(jdbc.queryForObject("select ack_receipt from workspace_admission_effect_outbox where effect_id=?",String.class,first.effectId()));
        assertEquals(registration.token(),first.token());
    }
    @Test void acceptedExactReceiptIsPersistentlyAcknowledged() throws Exception {
        String owner="ack-user-"+UUID.randomUUID(),workspace=workspaces.personal(owner).id();
        var delivered=new CountDownLatch(1);
        var observed=new java.util.concurrent.atomic.AtomicReference<BudgetCancellationSink.Effect>();
        when(sink.inspectAcceptance(any(),org.mockito.ArgumentMatchers.anyString())).thenAnswer(invocation ->
                CompletableFuture.completedFuture(new BudgetCancellationSink.ReceiptInspection.Matched(invocation.getArgument(0),invocation.getArgument(1))));
        when(sink.cancel(any())).thenAnswer(invocation -> {
            var effect=(BudgetCancellationSink.Effect)invocation.getArgument(0);
            if (!workspace.equals(effect.workspaceId())) return CompletableFuture.completedFuture(new BudgetCancellationSink.Result.Retry(effect.effectId(),effect.token(),effect.target()));
            var fence=((BudgetCancellationSink.Target.Pending)effect.target()).fence();
            String ack="ack-"+effect.effectId();
            jdbc.update("""
                    insert into execution_budget_cancellation_receipt(effect_id,registration_token,workspace_id,partition,run_id,
                      target_kind,target_id,target_period_start,target_period_end,target_epoch_or_generation,intent_receipt,acceptance_receipt)
                    values (?,?,?,?,?,'PENDING',?,?,?,?,?,?) on conflict (effect_id) do nothing
                    """,effect.effectId(),effect.token(),effect.workspaceId(),effect.partition().name(),effect.runId(),fence.pendingId(),
                    java.sql.Timestamp.from(fence.periodStart()),java.sql.Timestamp.from(fence.periodEnd()),fence.epoch(),effect.intentReceipt(),ack);
            observed.set(effect); delivered.countDown();
            return CompletableFuture.completedFuture(new BudgetCancellationSink.Result.Accepted(effect.effectId(),effect.token(),effect.target(),ack));
        });
        String bot="ack-bot-"+UUID.randomUUID(),run="ack-run-"+UUID.randomUUID();
        jdbc.update("insert into bot(id,name,target) values (?,?,'LOCAL')",bot,bot);
        var conversation=conversations.createConversation(bot,"fixture",owner,workspace);
        admission.register(new WorkspaceAdmission.Register(workspace,run,owner,conversation.id(),WorkspaceAdmission.Partition.REAL)).toCompletableFuture().join();
        var scope=new WorkspaceMonthlyBudgetOperations.Scope(workspace,WorkspaceAdmission.Partition.REAL,YearMonth.now(ZoneId.of("Asia/Taipei")));
        budgets.configure(new WorkspaceMonthlyBudgetOperations.ConfigureCommand(scope,owner,true,BigDecimal.ZERO,0)).toCompletableFuture().join();
        assertTrue(delivered.await(8,TimeUnit.SECONDS));
        var effect=observed.get(); long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        WorkspaceAdmission.EffectView view;
        do { view=assertInstanceOf(WorkspaceAdmission.EffectInspection.Found.class,admission.inspectCancellationEffect(effect.effectId()).toCompletableFuture().get(2,TimeUnit.SECONDS)).effect(); }
        while (view.status()==WorkspaceAdmission.EffectStatus.PENDING && System.nanoTime()<deadline);
        assertEquals(WorkspaceAdmission.EffectStatus.ACKNOWLEDGED,view.status());
        assertEquals("ack-"+effect.effectId(),view.ackReceipt());
        assertEquals(effect.intentReceipt(),view.intentReceipt());
    }

    @Test void acceptedResultWithoutMatchingDurableReceiptRemainsPending() throws Exception {
        String owner="forged-user-"+UUID.randomUUID(),workspace=workspaces.personal(owner).id();
        var delivered=new CountDownLatch(3);
        var observed=new java.util.concurrent.atomic.AtomicReference<BudgetCancellationSink.Effect>();
        when(sink.cancel(any())).thenAnswer(invocation -> {
            var effect=(BudgetCancellationSink.Effect)invocation.getArgument(0);
            if (!workspace.equals(effect.workspaceId())) return CompletableFuture.completedFuture(new BudgetCancellationSink.Result.Retry(effect.effectId(),effect.token(),effect.target())); observed.set(effect); delivered.countDown();
            return CompletableFuture.completedFuture(new BudgetCancellationSink.Result.Accepted(effect.effectId(),effect.token(),effect.target(),"unbacked-receipt"));
        });
        String bot="forged-bot-"+UUID.randomUUID(),run="forged-run-"+UUID.randomUUID();
        jdbc.update("insert into bot(id,name,target) values (?,?,'LOCAL')",bot,bot);
        var conversation=conversations.createConversation(bot,"fixture",owner,workspace);
        admission.register(new WorkspaceAdmission.Register(workspace,run,owner,conversation.id(),WorkspaceAdmission.Partition.REAL)).toCompletableFuture().join();
        var scope=new WorkspaceMonthlyBudgetOperations.Scope(workspace,WorkspaceAdmission.Partition.REAL,YearMonth.now(ZoneId.of("Asia/Taipei")));
        budgets.configure(new WorkspaceMonthlyBudgetOperations.ConfigureCommand(scope,owner,true,BigDecimal.ZERO,0)).toCompletableFuture().join();
        assertTrue(delivered.await(8,TimeUnit.SECONDS));
        var view=assertInstanceOf(WorkspaceAdmission.EffectInspection.Found.class,admission.inspectCancellationEffect(observed.get().effectId()).toCompletableFuture().join()).effect();
        assertEquals(WorkspaceAdmission.EffectStatus.PENDING,view.status());
        assertNull(view.ackReceipt());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"MISMATCH","UNKNOWN","FORGED_NESTED"})
    void receiptInspectionWithoutExactMatchNeverAcknowledges(String scenario) throws Exception {
        String owner="receipt-check-"+UUID.randomUUID(),workspace=workspaces.personal(owner).id();
        var inspected=new CountDownLatch(1); var observed=new java.util.concurrent.atomic.AtomicReference<BudgetCancellationSink.Effect>();
        when(sink.cancel(any())).thenAnswer(invocation -> {
            var effect=(BudgetCancellationSink.Effect)invocation.getArgument(0);
            if (!workspace.equals(effect.workspaceId())) return CompletableFuture.completedFuture(new BudgetCancellationSink.Result.Retry(effect.effectId(),effect.token(),effect.target()));
            observed.set(effect);
            return CompletableFuture.completedFuture(new BudgetCancellationSink.Result.Accepted(effect.effectId(),effect.token(),effect.target(),"execution-receipt"));
        });
        when(sink.inspectAcceptance(any(),org.mockito.ArgumentMatchers.anyString())).thenAnswer(invocation -> {
            BudgetCancellationSink.Effect effect=invocation.getArgument(0); inspected.countDown();
            if (scenario.equals("MISMATCH")) return CompletableFuture.completedFuture(new BudgetCancellationSink.ReceiptInspection.Mismatch());
            if (scenario.equals("UNKNOWN")) return CompletableFuture.completedFuture(new BudgetCancellationSink.ReceiptInspection.Unknown());
            var pending=((BudgetCancellationSink.Target.Pending)effect.target()).fence();
            var forged=new WorkspaceAdmission.PendingFence(pending.token(),pending.pendingId(),"different-workspace",pending.partition(),pending.periodStart(),pending.periodEnd(),pending.epoch());
            var altered=new BudgetCancellationSink.Effect(effect.effectId(),effect.token(),effect.workspaceId(),effect.partition(),effect.runId(),new BudgetCancellationSink.Target.Pending(forged),effect.intentReceipt());
            return CompletableFuture.completedFuture(new BudgetCancellationSink.ReceiptInspection.Matched(altered,"execution-receipt"));
        });
        String bot="receipt-bot-"+UUID.randomUUID(),run="receipt-run-"+UUID.randomUUID();
        jdbc.update("insert into bot(id,name,target) values (?,?,'LOCAL')",bot,bot);
        var conversation=conversations.createConversation(bot,"fixture",owner,workspace);
        var registration=assertInstanceOf(WorkspaceAdmission.RegisterResult.Registered.class,admission.register(
                new WorkspaceAdmission.Register(workspace,run,owner,conversation.id(),WorkspaceAdmission.Partition.REAL)).toCompletableFuture().join()).registration();
        var scope=new WorkspaceMonthlyBudgetOperations.Scope(workspace,WorkspaceAdmission.Partition.REAL,YearMonth.now(ZoneId.of("Asia/Taipei")));
        budgets.configure(new WorkspaceMonthlyBudgetOperations.ConfigureCommand(scope,owner,true,BigDecimal.ZERO,0)).toCompletableFuture().join();
        assertTrue(inspected.await(8,TimeUnit.SECONDS));
        var view=assertInstanceOf(WorkspaceAdmission.EffectInspection.Found.class,admission.inspectCancellationEffect(observed.get().effectId()).toCompletableFuture().join()).effect();
        assertEquals(WorkspaceAdmission.EffectStatus.PENDING,view.status());
        assertNull(view.ackReceipt());
        assertEquals(registration.token(),view.token());
    }

    @Test void staleResultIsAcknowledgedOnlyWhenAuthoritativeRunCompleted() throws Exception {
        String owner="stale-user-"+UUID.randomUUID(),workspace=workspaces.personal(owner).id();
        var delivered=new CountDownLatch(1);
        var observed=new java.util.concurrent.atomic.AtomicReference<BudgetCancellationSink.Effect>();
        when(sink.cancel(any())).thenAnswer(invocation -> {
            var effect=(BudgetCancellationSink.Effect)invocation.getArgument(0);
            if (!workspace.equals(effect.workspaceId())) return CompletableFuture.completedFuture(new BudgetCancellationSink.Result.Retry(effect.effectId(),effect.token(),effect.target()));
            observed.set(effect); delivered.countDown();
            return CompletableFuture.completedFuture(new BudgetCancellationSink.Result.Stale(effect.effectId(),effect.token(),effect.target()));
        });
        String bot="stale-bot-"+UUID.randomUUID(),run="stale-run-"+UUID.randomUUID();
        jdbc.update("insert into bot(id,name,target) values (?,?,'LOCAL')",bot,bot);
        var conversation=conversations.createConversation(bot,"fixture",owner,workspace);
        admission.register(new WorkspaceAdmission.Register(workspace,run,owner,conversation.id(),WorkspaceAdmission.Partition.REAL)).toCompletableFuture().join();
        jdbc.update("insert into chat_run(id,conversation_id,client_request_id,input_text,status) values (?,?,?,'test','COMPLETED')",run,conversation.id(),UUID.randomUUID().toString());
        var scope=new WorkspaceMonthlyBudgetOperations.Scope(workspace,WorkspaceAdmission.Partition.REAL,YearMonth.now(ZoneId.of("Asia/Taipei")));
        budgets.configure(new WorkspaceMonthlyBudgetOperations.ConfigureCommand(scope,owner,true,BigDecimal.ZERO,0)).toCompletableFuture().join();
        assertTrue(delivered.await(8,TimeUnit.SECONDS));
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5); WorkspaceAdmission.EffectView view;
        do { view=assertInstanceOf(WorkspaceAdmission.EffectInspection.Found.class,admission.inspectCancellationEffect(observed.get().effectId()).toCompletableFuture().get(2,TimeUnit.SECONDS)).effect(); }
        while (view.status()==WorkspaceAdmission.EffectStatus.PENDING && System.nanoTime()<deadline);
        assertEquals(WorkspaceAdmission.EffectStatus.STALE,view.status()); assertNotNull(view.ackReceipt());
    }

    @Test void stalledFirstHundredEffectsDoNotStarveLaterWorkspace() throws Exception {
        String owner="fairness-a-"+UUID.randomUUID(),workspace=workspaces.personal(owner).id();
        String otherOwner="fairness-b-"+UUID.randomUUID(),otherWorkspace=workspaces.personal(otherOwner).id();
        var firstPage=new CountDownLatch(100); var laterDelivered=new CountDownLatch(1);
        var stalled=new java.util.concurrent.ConcurrentHashMap<BudgetCancellationSink.Effect,CompletableFuture<BudgetCancellationSink.Result>>();
        when(sink.cancel(any())).thenAnswer(invocation -> {
            var effect=(BudgetCancellationSink.Effect)invocation.getArgument(0);
            if (workspace.equals(effect.workspaceId())) {
                var future=new CompletableFuture<BudgetCancellationSink.Result>();
                if (stalled.putIfAbsent(effect,future)==null) firstPage.countDown();
                return future;
            }
            if (otherWorkspace.equals(effect.workspaceId())) {
                laterDelivered.countDown();
                return CompletableFuture.completedFuture(new BudgetCancellationSink.Result.Stale(effect.effectId(),effect.token(),effect.target()));
            }
            return CompletableFuture.completedFuture(new BudgetCancellationSink.Result.Retry(effect.effectId(),effect.token(),effect.target()));
        });
        String bot="fair-bot-"+UUID.randomUUID();
        jdbc.update("insert into bot(id,name,target) values (?,?,'LOCAL')",bot,bot);
        var firstConversation=conversations.createConversation(bot,"first",owner,workspace);
        var secondConversation=conversations.createConversation(bot,"second",otherOwner,otherWorkspace);
        for (int i=0;i<100;i++) assertInstanceOf(WorkspaceAdmission.RegisterResult.Registered.class,admission.register(
                new WorkspaceAdmission.Register(workspace,"first-run-"+UUID.randomUUID(),owner,firstConversation.id(),WorkspaceAdmission.Partition.REAL)).toCompletableFuture().join());
        var firstScope=new WorkspaceMonthlyBudgetOperations.Scope(workspace,WorkspaceAdmission.Partition.REAL,YearMonth.now(ZoneId.of("Asia/Taipei")));
        var secondScope=new WorkspaceMonthlyBudgetOperations.Scope(otherWorkspace,WorkspaceAdmission.Partition.REAL,YearMonth.now(ZoneId.of("Asia/Taipei")));
        try {
            budgets.configure(new WorkspaceMonthlyBudgetOperations.ConfigureCommand(firstScope,owner,true,BigDecimal.ZERO,0)).toCompletableFuture().join();
            assertTrue(firstPage.await(15,TimeUnit.SECONDS),"the first full page must be in flight");
            String otherRun="later-run-"+UUID.randomUUID();
            var later=assertInstanceOf(WorkspaceAdmission.RegisterResult.Registered.class,admission.register(
                    new WorkspaceAdmission.Register(otherWorkspace,otherRun,otherOwner,secondConversation.id(),WorkspaceAdmission.Partition.REAL)).toCompletableFuture().join()).registration();
            jdbc.update("insert into chat_run(id,conversation_id,client_request_id,input_text,status) values (?,?,?,'test','COMPLETED')",otherRun,secondConversation.id(),UUID.randomUUID().toString());
            budgets.configure(new WorkspaceMonthlyBudgetOperations.ConfigureCommand(secondScope,otherOwner,true,BigDecimal.ZERO,0)).toCompletableFuture().join();
            assertTrue(laterDelivered.await(5,TimeUnit.SECONDS),"a pending later page must not be starved by stalled earlier deliveries");
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5); String state;
            do {
                state=jdbc.queryForObject("select delivery_status from workspace_admission_effect_outbox where registration_token=?",String.class,later.token());
                if ("STALE".equals(state)) break; Thread.sleep(25);
            } while(System.nanoTime()<deadline);
            assertEquals("STALE",state,"the later authoritative stale effect must be acknowledged");
            assertEquals(100,jdbc.queryForObject("select count(*) from workspace_admission_effect_outbox where target_workspace_id=? and delivery_status='PENDING'",Integer.class,workspace));
        } finally {
            stalled.forEach((effect,future)->future.complete(new BudgetCancellationSink.Result.Retry(effect.effectId(),effect.token(),effect.target())));
        }
    }

    @Test void timedOutDeliveryRetriesWithoutAcknowledgingLateStaleReply() throws Exception {
        String owner="late-effect-"+UUID.randomUUID(),workspace=workspaces.personal(owner).id();
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        var second=new CountDownLatch(1); var third=new CountDownLatch(1);
        var first=new CompletableFuture<BudgetCancellationSink.Result>();
        var original=new java.util.concurrent.atomic.AtomicReference<BudgetCancellationSink.Effect>();
        when(sink.cancel(any())).thenAnswer(invocation -> {
            var effect=(BudgetCancellationSink.Effect)invocation.getArgument(0);
            if (!workspace.equals(effect.workspaceId())) return CompletableFuture.completedFuture(new BudgetCancellationSink.Result.Retry(effect.effectId(),effect.token(),effect.target()));
            int call=calls.incrementAndGet();
            if (call==1) { original.set(effect); return first; }
            if (call==2) second.countDown();
            if (call>=3) third.countDown();
            return CompletableFuture.completedFuture(new BudgetCancellationSink.Result.Retry(effect.effectId(),effect.token(),effect.target()));
        });
        String bot="late-bot-"+UUID.randomUUID(),run="late-run-"+UUID.randomUUID();
        jdbc.update("insert into bot(id,name,target) values (?,?,'LOCAL')",bot,bot);
        var conversation=conversations.createConversation(bot,"fixture",owner,workspace);
        var registration=assertInstanceOf(WorkspaceAdmission.RegisterResult.Registered.class,admission.register(
                new WorkspaceAdmission.Register(workspace,run,owner,conversation.id(),WorkspaceAdmission.Partition.REAL)).toCompletableFuture().join()).registration();
        jdbc.update("insert into chat_run(id,conversation_id,client_request_id,input_text,status) values (?,?,?,'test','COMPLETED')",run,conversation.id(),UUID.randomUUID().toString());
        var scope=new WorkspaceMonthlyBudgetOperations.Scope(workspace,WorkspaceAdmission.Partition.REAL,YearMonth.now(ZoneId.of("Asia/Taipei")));
        budgets.configure(new WorkspaceMonthlyBudgetOperations.ConfigureCommand(scope,owner,true,BigDecimal.ZERO,0)).toCompletableFuture().join();
        assertFalse(second.await(3,TimeUnit.SECONDS),"an unfinished shared call must retain its effect's capacity");
        var effect=original.get();
        first.complete(new BudgetCancellationSink.Result.Stale(effect.effectId(),effect.token(),effect.target()));
        assertTrue(second.await(8,TimeUnit.SECONDS),"the effect must be retried after the old call completes");
        assertTrue(third.await(8,TimeUnit.SECONDS),"retry after a late reply must still be scheduled");
        assertEquals("PENDING",jdbc.queryForObject("select delivery_status from workspace_admission_effect_outbox where registration_token=?",String.class,registration.token()));
        assertNull(jdbc.queryForObject("select ack_receipt from workspace_admission_effect_outbox where registration_token=?",String.class,registration.token()));
    }

    @Test void stalledInspectionStagesDoNotStarveLaterWorkspace() throws Exception {
        String owner="inspect-a-"+UUID.randomUUID(),workspace=workspaces.personal(owner).id();
        String otherOwner="inspect-b-"+UUID.randomUUID(),otherWorkspace=workspaces.personal(otherOwner).id();
        var inspected=new CountDownLatch(100); var laterDelivered=new CountDownLatch(1);
        var seen=java.util.concurrent.ConcurrentHashMap.<String>newKeySet();
        var held=new java.util.concurrent.ConcurrentLinkedQueue<CompletableFuture<WorkspaceAdmission.EffectInspection>>();
        org.mockito.Mockito.doAnswer(invocation -> {
            String id=invocation.getArgument(0);
            var scopes=jdbc.query("select target_workspace_id from workspace_admission_effect_outbox where effect_id=?",(rs,n)->rs.getString(1),id);
            if (!scopes.isEmpty() && workspace.equals(scopes.getFirst())) {
                if (seen.add(id)) inspected.countDown();
                var pending=new CompletableFuture<WorkspaceAdmission.EffectInspection>(); held.add(pending); return pending;
            }
            return invocation.callRealMethod();
        }).when(admission).inspectCancellationEffect(org.mockito.ArgumentMatchers.anyString());
        when(sink.cancel(any())).thenAnswer(invocation -> {
            var effect=(BudgetCancellationSink.Effect)invocation.getArgument(0);
            if (otherWorkspace.equals(effect.workspaceId())) {
                laterDelivered.countDown();
                return CompletableFuture.completedFuture(new BudgetCancellationSink.Result.Stale(effect.effectId(),effect.token(),effect.target()));
            }
            return CompletableFuture.completedFuture(new BudgetCancellationSink.Result.Retry(effect.effectId(),effect.token(),effect.target()));
        });
        String bot="inspect-bot-"+UUID.randomUUID(); jdbc.update("insert into bot(id,name,target) values (?,?,'LOCAL')",bot,bot);
        var firstConversation=conversations.createConversation(bot,"first",owner,workspace);
        var secondConversation=conversations.createConversation(bot,"second",otherOwner,otherWorkspace);
        for(int i=0;i<100;i++) assertInstanceOf(WorkspaceAdmission.RegisterResult.Registered.class,admission.register(
                new WorkspaceAdmission.Register(workspace,"inspect-run-"+UUID.randomUUID(),owner,firstConversation.id(),WorkspaceAdmission.Partition.REAL)).toCompletableFuture().join());
        var firstScope=new WorkspaceMonthlyBudgetOperations.Scope(workspace,WorkspaceAdmission.Partition.REAL,YearMonth.now(ZoneId.of("Asia/Taipei")));
        var secondScope=new WorkspaceMonthlyBudgetOperations.Scope(otherWorkspace,WorkspaceAdmission.Partition.REAL,YearMonth.now(ZoneId.of("Asia/Taipei")));
        try {
            budgets.configure(new WorkspaceMonthlyBudgetOperations.ConfigureCommand(firstScope,owner,true,BigDecimal.ZERO,0)).toCompletableFuture().join();
            assertTrue(inspected.await(15,TimeUnit.SECONDS),"first page inspections must be in flight");
            String run="inspect-later-"+UUID.randomUUID();
            var registration=assertInstanceOf(WorkspaceAdmission.RegisterResult.Registered.class,admission.register(
                    new WorkspaceAdmission.Register(otherWorkspace,run,otherOwner,secondConversation.id(),WorkspaceAdmission.Partition.REAL)).toCompletableFuture().join()).registration();
            jdbc.update("insert into chat_run(id,conversation_id,client_request_id,input_text,status) values (?,?,?,'test','COMPLETED')",run,secondConversation.id(),UUID.randomUUID().toString());
            budgets.configure(new WorkspaceMonthlyBudgetOperations.ConfigureCommand(secondScope,otherOwner,true,BigDecimal.ZERO,0)).toCompletableFuture().join();
            assertTrue(laterDelivered.await(8,TimeUnit.SECONDS));
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5); String status;
            do { status=jdbc.queryForObject("select delivery_status from workspace_admission_effect_outbox where registration_token=?",String.class,registration.token());
                if ("STALE".equals(status)) break; Thread.sleep(25); } while(System.nanoTime()<deadline);
            assertEquals("STALE",status);
            assertEquals(100,jdbc.queryForObject("select count(*) from workspace_admission_effect_outbox where target_workspace_id=? and delivery_status='PENDING'",Integer.class,workspace));
        } finally { held.forEach(future->future.complete(new WorkspaceAdmission.EffectInspection.Unknown())); }
    }

    @Test void timedOutUnfinishedSinkStagesRemainBoundedAcrossRetries() throws Exception {
        String owner="bounded-stage-"+UUID.randomUUID(),workspace=workspaces.personal(owner).id();
        var first128=new CountDownLatch(128); var overflow=new CountDownLatch(1);
        var count=new java.util.concurrent.atomic.AtomicInteger();
        record Held(BudgetCancellationSink.Effect effect,CompletableFuture<BudgetCancellationSink.Result> future) {}
        var held=new java.util.concurrent.ConcurrentLinkedQueue<Held>();
        when(sink.cancel(any())).thenAnswer(invocation -> {
            var effect=(BudgetCancellationSink.Effect)invocation.getArgument(0);
            if (!workspace.equals(effect.workspaceId())) return CompletableFuture.completedFuture(new BudgetCancellationSink.Result.Retry(effect.effectId(),effect.token(),effect.target()));
            var pending=new CompletableFuture<BudgetCancellationSink.Result>(); held.add(new Held(effect,pending));
            if (count.incrementAndGet()<=128) first128.countDown(); else overflow.countDown();
            return pending;
        });
        String bot="bounded-bot-"+UUID.randomUUID();
        jdbc.update("insert into bot(id,name,target) values (?,?,'LOCAL')",bot,bot);
        var conversation=conversations.createConversation(bot,"fixture",owner,workspace);
        for (int i=0;i<128;i++) assertInstanceOf(WorkspaceAdmission.RegisterResult.Registered.class,admission.register(
                new WorkspaceAdmission.Register(workspace,"bounded-run-"+UUID.randomUUID(),owner,conversation.id(),WorkspaceAdmission.Partition.REAL)).toCompletableFuture().join());
        var scope=new WorkspaceMonthlyBudgetOperations.Scope(workspace,WorkspaceAdmission.Partition.REAL,YearMonth.now(ZoneId.of("Asia/Taipei")));
        try {
            budgets.configure(new WorkspaceMonthlyBudgetOperations.ConfigureCommand(scope,owner,true,BigDecimal.ZERO,0)).toCompletableFuture().join();
            assertTrue(first128.await(15,TimeUnit.SECONDS),"128 original sink stages must be pending");
            assertFalse(overflow.await(5,TimeUnit.SECONDS),"timeout must not create more than 128 unfinished sink stages");
            assertEquals(128,held.stream().filter(h->!h.future().isDone()).count());
            assertEquals(128,jdbc.queryForObject("select count(*) from workspace_admission_effect_outbox where target_workspace_id=? and delivery_status='PENDING'",Integer.class,workspace));
        } finally {
            held.forEach(h->h.future().complete(new BudgetCancellationSink.Result.Retry(h.effect().effectId(),h.effect().token(),h.effect().target())));
        }
    }

    @Test void inspectionCompletingAfterTimeoutDoesNotStartSink() throws Exception {
        String owner="late-inspection-"+UUID.randomUUID(),workspace=workspaces.personal(owner).id();
        var reached=new CountDownLatch(1); var sinkCalled=new CountDownLatch(1);
        var firstId=new java.util.concurrent.atomic.AtomicReference<String>();
        var held=new CompletableFuture<WorkspaceAdmission.EffectInspection>();
        org.mockito.Mockito.doAnswer(invocation -> {
            String id=invocation.getArgument(0);
            var scopes=jdbc.query("select target_workspace_id from workspace_admission_effect_outbox where effect_id=?",(rs,n)->rs.getString(1),id);
            if (!scopes.isEmpty() && workspace.equals(scopes.getFirst())) { firstId.set(id); reached.countDown(); return held; }
            return invocation.callRealMethod();
        }).when(admission).inspectCancellationEffect(org.mockito.ArgumentMatchers.anyString());
        when(sink.cancel(any())).thenAnswer(invocation -> {
            var effect=(BudgetCancellationSink.Effect)invocation.getArgument(0);
            if (workspace.equals(effect.workspaceId())) sinkCalled.countDown();
            return CompletableFuture.completedFuture(new BudgetCancellationSink.Result.Retry(effect.effectId(),effect.token(),effect.target()));
        });
        String bot="inspect-late-bot-"+UUID.randomUUID(),run="inspect-late-run-"+UUID.randomUUID();
        jdbc.update("insert into bot(id,name,target) values (?,?,'LOCAL')",bot,bot);
        var conversation=conversations.createConversation(bot,"fixture",owner,workspace);
        var registration=assertInstanceOf(WorkspaceAdmission.RegisterResult.Registered.class,admission.register(
                new WorkspaceAdmission.Register(workspace,run,owner,conversation.id(),WorkspaceAdmission.Partition.REAL)).toCompletableFuture().join()).registration();
        var scope=new WorkspaceMonthlyBudgetOperations.Scope(workspace,WorkspaceAdmission.Partition.REAL,YearMonth.now(ZoneId.of("Asia/Taipei")));
        budgets.configure(new WorkspaceMonthlyBudgetOperations.ConfigureCommand(scope,owner,true,BigDecimal.ZERO,0)).toCompletableFuture().join();
        assertTrue(reached.await(8,TimeUnit.SECONDS));
        String effectId=firstId.get();
        var row=jdbc.queryForMap("select pending_id,pending_epoch,period_start,period_end,intent_receipt from workspace_admission_effect_outbox where effect_id=?",effectId);
        var zone=ZoneId.of("Asia/Taipei");
        var fence=new WorkspaceAdmission.PendingFence(registration.token(),(String)row.get("pending_id"),workspace,WorkspaceAdmission.Partition.REAL,
                ((java.sql.Date)row.get("period_start")).toLocalDate().atStartOfDay(zone).toInstant(),
                ((java.sql.Date)row.get("period_end")).toLocalDate().atStartOfDay(zone).toInstant(),((Number)row.get("pending_epoch")).longValue());
        var view=new WorkspaceAdmission.EffectView(effectId,registration.token(),workspace,WorkspaceAdmission.Partition.REAL,
                new BudgetCancellationSink.Target.Pending(fence),WorkspaceAdmission.EffectStatus.PENDING,(String)row.get("intent_receipt"),null);
        assertFalse(new CountDownLatch(1).await(2500,TimeUnit.MILLISECONDS));
        try(var connection=dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try(var lock=connection.prepareStatement("select effect_id from workspace_admission_effect_outbox where effect_id=? for update")) { lock.setString(1,effectId); lock.executeQuery().close(); }
            held.complete(new WorkspaceAdmission.EffectInspection.Found(view));
            assertFalse(sinkCalled.await(1,TimeUnit.SECONDS),"an expired inspection must not start downstream sink work");
            assertEquals("PENDING",jdbc.queryForObject("select delivery_status from workspace_admission_effect_outbox where effect_id=?",String.class,effectId));
            assertNull(jdbc.queryForObject("select ack_receipt from workspace_admission_effect_outbox where effect_id=?",String.class,effectId));
            connection.rollback();
        }
    }

}
