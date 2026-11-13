package io.github.samzhu.bot;

import io.github.samzhu.bot.conversation.ConversationOperations;
import io.github.samzhu.bot.execution.ExecutionOperations;
import io.github.samzhu.bot.execution.RunStatus;
import io.github.samzhu.bot.usage.UsageLedger;
import io.github.samzhu.bot.usage.WorkspaceAdmission;
import io.github.samzhu.bot.workspace.WorkspaceOperations;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties={"bot.budget.finalization.initial-delay-ms=100","bot.budget.finalization.delay-ms=100",
        "bot.execution.completion-sweep.initial-delay-ms=100","bot.execution.completion-sweep.delay-ms=100",
        "bot.budget.effects.initial-delay-ms=3600000","bot.pricing.reconcile.initial-delay-ms=3600000"})
@Import({TestcontainersConfiguration.class,SupersededExecutionIntegrationTests.Scheduling.class})
@ActiveProfiles("test")
class SupersededExecutionIntegrationTests {
    @TestConfiguration @EnableScheduling static class Scheduling {}
    @MockitoSpyBean WorkspaceAdmission admission;
    @Autowired WorkspaceOperations workspaces;
    @Autowired ConversationOperations conversations;
    @Autowired ExecutionOperations execution;
    @Autowired UsageLedger ledger;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @TestBean(name="mockChatModel",methodName="model",enforceOverride=true) ChatModel chatModel;
    static ControlledChatModel model() { return ControlledChatModel.getInstance(); }

    record Fixture(WorkspaceAdmission.Registration registration,List<String> attempts,String effectId) {}
    Fixture fixture(int count) {
        model().reset();
        String actor="superseded-execution-"+UUID.randomUUID(), workspace=workspaces.personal(actor).id();
        String bot="superseded-bot-"+UUID.randomUUID(), run="superseded-run-"+UUID.randomUUID();
        jdbc.update("insert into bot(id,name,target) values (?,?,'LOCAL')",bot,bot);
        var conversation=conversations.createConversation(bot,"synthetic superseded",actor,workspace);
        var registration=assertInstanceOf(WorkspaceAdmission.RegisterResult.Registered.class,admission.register(
                new WorkspaceAdmission.Register(workspace,run,actor,conversation.id(),WorkspaceAdmission.Partition.REAL)).toCompletableFuture().join()).registration();
        jdbc.update("insert into chat_run(id,conversation_id,client_request_id,input_text,status) values (?,?,?,'synthetic','PENDING')",run,conversation.id(),UUID.randomUUID().toString());
        assertInstanceOf(WorkspaceAdmission.PublicationResult.Ready.class,admission.publish(registration).toCompletableFuture().join());
        var attempts=new ArrayList<String>();
        for(int i=0;i<count;i++) attempts.add(ledger.begin(new UsageLedger.Dispatch(run,workspace,actor,"FAKE","test","model","FAKE","1",false,"UNKNOWN")));
        return new Fixture(registration,List.copyOf(attempts),UUID.randomUUID().toString());
    }
    void insertSyntheticStale(Fixture f) {
        var r=f.registration();
        var row=jdbc.queryForMap("select pending_id,pending_epoch,pending_period_start from workspace_admission_registration where token=?",r.token());
        var next=((java.sql.Date)row.get("pending_period_start")).toLocalDate();
        // Synthetic prior-period STALE isolates durable no-caller recovery from delivery timing.
        jdbc.update("""
                insert into workspace_admission_effect_outbox(effect_id,registration_token,target_workspace_id,target_partition,target_run_id,
                  pending_id,pending_epoch,period_start,period_end,generation,delivery_status,intent_receipt,ack_receipt,acknowledged_at)
                values (?,?,?,'REAL',?,?,?,?,?,0,'STALE',?,'synthetic-stale',current_timestamp)
                """,f.effectId(),r.token(),r.workspaceId(),r.runId(),row.get("pending_id"),row.get("pending_epoch"),next.minusMonths(1),next,UUID.randomUUID().toString());
    }
    void awaitConvergence(Fixture f,RunStatus expected,String reason) throws Exception {
        var r=f.registration(); long deadline=System.nanoTime()+Duration.ofSeconds(20).toNanos();
        String run=null,registration=null;
        while(System.nanoTime()<deadline) {
            run=jdbc.queryForObject("select status from chat_run where id=?",String.class,r.runId());
            registration=jdbc.queryForObject("select status from workspace_admission_registration where token=?",String.class,r.token());
            if(expected.name().equals(run) && "TERMINAL".equals(registration)) break;
            Thread.sleep(25);
        }
        assertEquals(expected.name(),run); assertEquals("TERMINAL",registration);
        if(reason!=null) assertEquals(reason,jdbc.queryForObject("select error_message from chat_run where id=?",String.class,r.runId()));
        assertEquals(f.attempts().size(),jdbc.queryForObject("select count(*) from provider_attempt where run_id=? and execution_outcome='NOT_DISPATCHED' and started_at is null",Integer.class,r.runId()));
        assertEquals(f.attempts().size(),jdbc.queryForObject("select count(*) from workspace_admission_finalization where registration_token=? and state='SETTLED'",Integer.class,r.token()));
        assertEquals(0,jdbc.queryForObject("select count(*) from workspace_admission_claim where registration_token=?",Integer.class,r.token()));
        assertEquals(0,jdbc.queryForObject("select count(*) from execution_budget_cancellation_receipt where effect_id=?",Integer.class,f.effectId()));
        assertEquals("STALE",jdbc.queryForObject("select delivery_status from workspace_admission_effect_outbox where effect_id=?",String.class,f.effectId()));
        assertTrue(model().getRecordedPrompts().isEmpty());
    }
    @Test void zeroAttemptStaleFactAutomaticallyCompletesRun() throws Exception {
        var fixture=fixture(0); insertSyntheticStale(fixture);
        awaitConvergence(fixture,RunStatus.FAILED,"BUDGET_CANCELLATION_SUPERSEDED");
    }
    @Test void twoAttemptsAndUnknownScanAutomaticallyRecoverWithoutCaller() throws Exception {
        var fixture=fixture(2); var injected=new AtomicBoolean(true); var observed=new CountDownLatch(1);
        doAnswer(call -> {
            if(injected.compareAndSet(true,false)) { observed.countDown(); return java.util.concurrent.CompletableFuture.completedFuture(new WorkspaceAdmission.SupersededPage.Unknown()); }
            return call.callRealMethod();
        }).when(admission).scanSupersededNotDispatched(any(),any(),anyInt());
        try {
            insertSyntheticStale(fixture);
            assertTrue(observed.await(5,TimeUnit.SECONDS),"scanner must observe an injected Unknown and retry");
            awaitConvergence(fixture,RunStatus.FAILED,"BUDGET_CANCELLATION_SUPERSEDED");
            for(String attempt:fixture.attempts()) assertEquals("BUDGET_CANCELLATION_SUPERSEDED",jdbc.queryForObject("select terminal_reason from workspace_admission_finalization where attempt_id=?",String.class,attempt));
        } finally { reset(admission); }
    }
    @Test void cancelWinsBeforeReadyFactIsConsumed() throws Exception {
        var fixture=fixture(0); var blocked=new AtomicBoolean(true);
        doAnswer(call -> blocked.get() ? java.util.concurrent.CompletableFuture.completedFuture(new WorkspaceAdmission.SupersededPage.Unknown()) : call.callRealMethod())
                .when(admission).scanSupersededNotDispatched(any(),any(),anyInt());
        try {
            insertSyntheticStale(fixture);
            long deadline=System.nanoTime()+Duration.ofSeconds(12).toNanos(); int facts=0;
            while(System.nanoTime()<deadline) {
                facts=jdbc.queryForObject("select count(*) from workspace_admission_superseded where registration_token=?",Integer.class,fixture.registration().token());
                if(facts==1) break; Thread.sleep(25);
            }
            assertEquals(1,facts);
            var cancelled=execution.cancelRun(fixture.registration().runId(),fixture.registration().actorId());
            assertEquals(RunStatus.CANCELLED,cancelled.finalStatus());
            blocked.set(false);
            awaitConvergence(fixture,RunStatus.CANCELLED,null);
        } finally { blocked.set(false); reset(admission); }
    }

    @Test void freshContextSweepsOrphanWithoutInventingReceipt() throws Exception {
        var fixture=fixture(1); var blocked=new AtomicBoolean(true);
        doAnswer(call -> blocked.get() ? java.util.concurrent.CompletableFuture.completedFuture(new WorkspaceAdmission.SupersededPage.Unknown()) : call.callRealMethod())
                .when(admission).scanSupersededNotDispatched(any(),any(),anyInt());
        try {
            insertSyntheticStale(fixture);
            long deadline=System.nanoTime()+Duration.ofSeconds(15).toNanos(); int ready=0;
            while(System.nanoTime()<deadline) {
                ready=jdbc.queryForObject("select count(*) from workspace_admission_superseded s join workspace_admission_finalization f on f.registration_token=s.registration_token where s.registration_token=? and f.state='SETTLED'",Integer.class,fixture.registration().token());
                if(ready==1) break; Thread.sleep(25);
            }
            assertEquals(1,ready,"durable disposition and attempt must be settled before the fresh context starts");
            assertEquals("PENDING",jdbc.queryForObject("select status from chat_run where id=?",String.class,fixture.registration().runId()));
            var ds=(HikariDataSource)dataSource;
            // A fresh context on the same isolated PG while the test context remains alive; not an OS restart.
            try(var fresh=new SpringApplicationBuilder(BotApplication.class).web(WebApplicationType.NONE).profiles("test")
                    .properties("spring.datasource.url="+ds.getJdbcUrl(),"spring.datasource.username="+ds.getUsername(),
                            "spring.datasource.password="+ds.getPassword(),"spring.docker.compose.enabled=false").run()) {
                awaitConvergence(fixture,RunStatus.FAILED,"SYSTEM_RESTART_ORPHAN");
                assertEquals("BUDGET_CANCELLATION_SUPERSEDED",jdbc.queryForObject("select terminal_reason from workspace_admission_finalization where attempt_id=?",String.class,fixture.attempts().getFirst()));
            }
        } finally { blocked.set(false); reset(admission); }
    }
}
