package io.github.samzhu.bot;

import io.github.samzhu.bot.conversation.ConversationOperations;
import io.github.samzhu.bot.usage.BudgetCancellationSink;
import io.github.samzhu.bot.usage.WorkspaceAdmission;
import io.github.samzhu.bot.usage.WorkspaceMonthlyBudgetOperations;
import io.github.samzhu.bot.workspace.WorkspaceOperations;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@SpringBootTest(properties={"bot.budget.effects.initial-delay-ms=0","bot.budget.effects.delay-ms=100","bot.pricing.reconcile.initial-delay-ms=3600000"})
@Import({TestcontainersConfiguration.class,WorkspaceCancellationLeaseTests.ControlledScheduling.class})
@ActiveProfiles("test")
class WorkspaceCancellationLeaseTests {
    static final CountDownLatch RELEASE_SCHEDULER=new CountDownLatch(1);
    @TestConfiguration
    @EnableScheduling
    static class ControlledScheduling {
        @Bean(name="taskScheduler") ThreadPoolTaskScheduler taskScheduler() {
            var scheduler=new ThreadPoolTaskScheduler() {
                @Override public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task,Instant start,Duration delay) {
                    return super.scheduleWithFixedDelay(()-> {
                        try { if (RELEASE_SCHEDULER.await(20,TimeUnit.SECONDS)) task.run(); }
                        catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
                    },start,delay);
                }
            };
            scheduler.setPoolSize(3); return scheduler;
        }
    }
    @Autowired WorkspaceAdmission admission;
    @Autowired WorkspaceMonthlyBudgetOperations budgets;
    @Autowired WorkspaceOperations workspaces;
    @Autowired ConversationOperations conversations;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @MockitoBean BudgetCancellationSink sink;

    @Test void queuedAckAfterLeaseExpiryCannotAcknowledgeOldDelivery() throws Exception {
        String actor="lease-user-"+UUID.randomUUID(),workspace=workspaces.personal(actor).id();
        String bot="lease-bot-"+UUID.randomUUID(),run="lease-run-"+UUID.randomUUID();
        var calls=new AtomicInteger(); var retried=new CountDownLatch(1); var delivered=new CountDownLatch(1);
        var permitFreshAck=new java.util.concurrent.atomic.AtomicBoolean();
        var firstReply=new CompletableFuture<BudgetCancellationSink.Result>();
        var firstEffect=new java.util.concurrent.atomic.AtomicReference<BudgetCancellationSink.Effect>();
        when(sink.cancel(any())).thenAnswer(invocation -> {
            var effect=(BudgetCancellationSink.Effect)invocation.getArgument(0);
            if (!workspace.equals(effect.workspaceId())) return CompletableFuture.completedFuture(new BudgetCancellationSink.Result.Retry(effect.effectId(),effect.token(),effect.target()));
            if (calls.incrementAndGet()==1) { firstEffect.set(effect); delivered.countDown(); return firstReply; }
            retried.countDown();
            if (permitFreshAck.get()) return CompletableFuture.completedFuture(new BudgetCancellationSink.Result.Stale(effect.effectId(),effect.token(),effect.target()));
            return CompletableFuture.completedFuture(new BudgetCancellationSink.Result.Retry(effect.effectId(),effect.token(),effect.target()));
        });
        jdbc.update("insert into bot(id,name,target) values (?,?,'LOCAL')",bot,bot);
        var conversation=conversations.createConversation(bot,"fixture",actor,workspace);
        var registration=assertInstanceOf(WorkspaceAdmission.RegisterResult.Registered.class,admission.register(
                new WorkspaceAdmission.Register(workspace,run,actor,conversation.id(),WorkspaceAdmission.Partition.REAL)).toCompletableFuture().join()).registration();
        jdbc.update("insert into chat_run(id,conversation_id,client_request_id,input_text,status) values (?,?,?,'test','COMPLETED')",run,conversation.id(),UUID.randomUUID().toString());
        var scope=new WorkspaceMonthlyBudgetOperations.Scope(workspace,WorkspaceAdmission.Partition.REAL,YearMonth.now(ZoneId.of("Asia/Taipei")));
        budgets.configure(new WorkspaceMonthlyBudgetOperations.ConfigureCommand(scope,actor,true,BigDecimal.ZERO,0)).toCompletableFuture().join();
        String effect=jdbc.queryForObject("select effect_id from workspace_admission_effect_outbox where registration_token=?",String.class,registration.token());
        RELEASE_SCHEDULER.countDown();
        assertTrue(delivered.await(8,TimeUnit.SECONDS),"first effect must reach the sink");
        String firstLease=jdbc.queryForObject("select delivery_lease_id from workspace_admission_effect_outbox where effect_id=?",String.class,effect);
        assertNotNull(firstLease);
        try(var connection=dataSource.getConnection()) {
            connection.setAutoCommit(false); int blocker;
            try(var s=connection.createStatement();var rs=s.executeQuery("select pg_backend_pid()")) { rs.next(); blocker=rs.getInt(1); }
            try(var s=connection.prepareStatement("select effect_id from workspace_admission_effect_outbox where effect_id=? for update")) { s.setString(1,effect); s.executeQuery().close(); }
            var old=firstEffect.get();
            firstReply.complete(new BudgetCancellationSink.Result.Stale(old.effectId(),old.token(),old.target()));
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8); boolean blocked;
            do { blocked=Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from pg_stat_activity where ?=any(pg_blocking_pids(pid)))",Boolean.class,blocker));
                if(blocked) break; Thread.sleep(20); } while(System.nanoTime()<deadline);
            assertTrue(blocked,"acknowledgement must reach the locked outbox row");
            assertFalse(new CountDownLatch(1).await(2500,TimeUnit.MILLISECONDS));
            connection.commit();
        } finally { RELEASE_SCHEDULER.countDown(); }
        assertTrue(retried.await(8,TimeUnit.SECONDS),"expired delivery must be eligible for retry");
        String nextLease=jdbc.queryForObject("select delivery_lease_id from workspace_admission_effect_outbox where effect_id=?",String.class,effect);
        assertNotNull(nextLease);
        assertNotEquals(firstLease,nextLease,"the retry must use a new lease identity");
        assertEquals("PENDING",jdbc.queryForObject("select delivery_status from workspace_admission_effect_outbox where effect_id=?",String.class,effect));
        assertNull(jdbc.queryForObject("select ack_receipt from workspace_admission_effect_outbox where effect_id=?",String.class,effect));
        permitFreshAck.set(true);
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8); String state;
        do {
            state=jdbc.queryForObject("select delivery_status from workspace_admission_effect_outbox where effect_id=?",String.class,effect);
            if ("STALE".equals(state)) break; Thread.sleep(25);
        } while (System.nanoTime()<deadline);
        assertEquals("STALE",state,"a fresh delivery with authoritative stale evidence can acknowledge");
    }
}
