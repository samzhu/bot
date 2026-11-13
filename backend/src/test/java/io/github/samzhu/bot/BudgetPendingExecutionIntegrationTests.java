package io.github.samzhu.bot;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.samzhu.bot.usage.BudgetCancellationSink;
import io.github.samzhu.bot.usage.WorkspaceAdmission;
import io.github.samzhu.bot.usage.WorkspaceMonthlyBudgetOperations;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties={"bot.run.timeout=2s","bot.budget.effects.initial-delay-ms=100","bot.budget.effects.delay-ms=100",
                "bot.execution.completion-sweep.initial-delay-ms=100","bot.execution.completion-sweep.delay-ms=100",
                "bot.pricing.reconcile.initial-delay-ms=3600000","bot.pricing.reconcile.delay-ms=3600000"})
@Import({TestcontainersConfiguration.class,AgUiIntegrationTests.Scheduling.class})
@ActiveProfiles("test")
class BudgetPendingExecutionIntegrationTests {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired WorkspaceMonthlyBudgetOperations budgets;
    @Autowired Clock clock;
    @MockitoSpyBean WorkspaceAdmission admission;
    @MockitoSpyBean BudgetCancellationSink sink;
    @TestBean(name="mockChatModel",methodName="model",enforceOverride=true) ChatModel chatModel;
    static ControlledChatModel model() { return ControlledChatModel.getInstance(); }
    final ObjectMapper json=new ObjectMapper();
    final HttpClient http=HttpClient.newHttpClient();
    @BeforeEach void setup() {
        model().reset();
        jdbc.update("insert into bot(id,name,system_prompt,created_at) values ('bot-general','General','test',current_timestamp) on conflict(id) do nothing");
    }
    HttpRequest request(String path,Object body) throws Exception {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
    }
    @Test void durableBudgetPendingRetriesPastDeadlineThenRealSinkCancels() throws Exception {
        String workspace="budget-pending-"+UUID.randomUUID();
        jdbc.update("insert into workspace(id,name) values (?,?)",workspace,"synthetic pending fixture");
        jdbc.update("insert into workspace_member(workspace_id,user_id,role,active) values (?,'dev-user','MANAGER',true)",workspace);
        var created=http.send(request("/graphql",Map.of("query","mutation { createConversation(botId: \"bot-general\", title: \"pending retry\", workspaceId: \""+workspace+"\") { id } }")),HttpResponse.BodyHandlers.ofString());
        String conversation=json.readTree(created.body()).path("data").path("createConversation").path("id").asText();
        assertFalse(conversation.isBlank(),created.body());
        String run=UUID.randomUUID().toString();
        var dispatchEntered=new CountDownLatch(1); var releaseDispatch=new CompletableFuture<Void>();
        var held=new AtomicBoolean(); var forceRetry=new AtomicBoolean(true); var retryCount=new AtomicInteger();
        doAnswer(call -> {
            WorkspaceAdmission.Dispatch command=call.getArgument(0);
            if(run.equals(command.registration().runId()) && held.compareAndSet(false,true)) {
                dispatchEntered.countDown();
                return releaseDispatch.thenCompose(ignored -> admission.dispatch(command));
            }
            return call.callRealMethod();
        }).when(admission).dispatch(any());
        doAnswer(call -> {
            BudgetCancellationSink.Effect effect=call.getArgument(0);
            if(run.equals(effect.runId()) && forceRetry.get()) {
                retryCount.incrementAndGet();
                return CompletableFuture.completedFuture(new BudgetCancellationSink.Result.Retry(effect.effectId(),effect.token(),effect.target()));
            }
            return call.callRealMethod();
        }).when(sink).cancel(any());
        var body=Map.of("threadId",conversation,"runId",run,"state",Map.of(),"messages",List.of(Map.of("id",UUID.randomUUID().toString(),"role","user","content","synthetic pending")),"tools",List.of(),"context",List.of(),"forwardedProps",Map.of());
        var stream=http.sendAsync(request("/api/ag-ui",body),HttpResponse.BodyHandlers.ofString());
        try {
            assertTrue(dispatchEntered.await(8,TimeUnit.SECONDS));
            var scope=new WorkspaceMonthlyBudgetOperations.Scope(workspace,WorkspaceAdmission.Partition.SIMULATION,YearMonth.from(clock.instant().atZone(ZoneId.of("Asia/Taipei"))));
            budgets.configure(new WorkspaceMonthlyBudgetOperations.ConfigureCommand(scope,"dev-user",true,BigDecimal.ZERO,0)).toCompletableFuture().get(10,TimeUnit.SECONDS);
            String effectId=jdbc.queryForObject("select effect_id from workspace_admission_effect_outbox where target_run_id=? and pending_id is not null",String.class,run);
            releaseDispatch.complete(null);
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8); String outcome=null;
            while(System.nanoTime()<deadline) {
                outcome=jdbc.queryForObject("select execution_outcome from provider_attempt where run_id=?",String.class,run);
                if("NOT_DISPATCHED".equals(outcome) && retryCount.get()>0) break;
                Thread.yield();
            }
            assertEquals("NOT_DISPATCHED",outcome); assertTrue(retryCount.get()>0,"the real outbox scheduler must observe the injected Retry");
            assertEquals(0,jdbc.queryForObject("select count(*) from workspace_admission_claim c join workspace_admission_registration r on r.token=c.registration_token where r.run_id=?",Integer.class,run));
            assertEquals(0,jdbc.queryForObject("select count(*) from execution_budget_cancellation_receipt where effect_id=?",Integer.class,effectId));
            try { stream.get(3,TimeUnit.SECONDS); } catch(java.util.concurrent.TimeoutException expected) { /* Deadline may close transport safely. */ }
            assertEquals("RUNNING",jdbc.queryForObject("select status from chat_run where id=?",String.class,run),"pending durable budget intent must not become generic FAILED at timeout");
            assertEquals("PENDING",jdbc.queryForObject("select delivery_status from workspace_admission_effect_outbox where effect_id=?",String.class,effectId));
            assertTrue(model().getRecordedPrompts().isEmpty());
            forceRetry.set(false);
            var response=stream.get(15,TimeUnit.SECONDS);
            assertTrue(response.body().contains("cancelled"),response.body());
            long ackDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(12); String state=null;
            while(System.nanoTime()<ackDeadline) {
                state=jdbc.queryForObject("select delivery_status from workspace_admission_effect_outbox where effect_id=?",String.class,effectId);
                if("ACKNOWLEDGED".equals(state)) break;
                Thread.yield();
            }
            assertEquals("ACKNOWLEDGED",state);
            assertEquals("CANCELLED",jdbc.queryForObject("select status from chat_run where id=?",String.class,run));
            String receipt=jdbc.queryForObject("select acceptance_receipt from execution_budget_cancellation_receipt where effect_id=?",String.class,effectId);
            assertEquals(receipt,jdbc.queryForObject("select ack_receipt from workspace_admission_effect_outbox where effect_id=?",String.class,effectId));
            assertTrue(model().getRecordedPrompts().isEmpty());
        } finally { forceRetry.set(false); releaseDispatch.complete(null); reset(admission,sink); }
    }
}
