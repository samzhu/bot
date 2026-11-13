package io.github.samzhu.bot;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.samzhu.bot.execution.RunStatus;
import io.github.samzhu.bot.conversation.ConversationRecoveryReader;
import io.github.samzhu.bot.execution.internal.ChatRunRepository;
import io.github.samzhu.bot.execution.internal.RunHandle;
import io.github.samzhu.bot.execution.internal.RunRegistry;
import io.github.samzhu.bot.usage.BudgetCancellationSink;
import io.github.samzhu.bot.usage.WorkspaceAdmission;
import io.github.samzhu.bot.usage.WorkspaceMonthlyBudgetOperations;
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
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
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
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties={"bot.run.timeout=2s","bot.budget.effects.initial-delay-ms=100","bot.budget.effects.delay-ms=100",
                "bot.budget.finalization.initial-delay-ms=100","bot.budget.finalization.delay-ms=100",
                "bot.execution.completion-sweep.initial-delay-ms=100","bot.execution.completion-sweep.delay-ms=100",
                "bot.pricing.reconcile.initial-delay-ms=3600000"})
@Import({TestcontainersConfiguration.class,AgUiIntegrationTests.Scheduling.class})
@ActiveProfiles("test")
class SupersededPostCommitResponseLossIntegrationTests {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired WorkspaceMonthlyBudgetOperations budgets;
    @Autowired Clock clock;
    @Autowired RunRegistry registry;
    @MockitoSpyBean WorkspaceAdmission admission;
    @MockitoSpyBean BudgetCancellationSink sink;
    @MockitoSpyBean TransactionTemplate transactions;
    @MockitoSpyBean ChatRunRepository runs;
    @MockitoSpyBean ConversationRecoveryReader recoveryReader;
    @TestBean(name="mockChatModel",methodName="model",enforceOverride=true) ChatModel chatModel;
    static ControlledChatModel model() { return ControlledChatModel.getInstance(); }
    final ObjectMapper json=new ObjectMapper();
    final HttpClient http=HttpClient.newHttpClient();
    HttpRequest request(String path,Object body) throws Exception {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
    }
    @Test void committedSupersededRunWithLostResponseReconcilesLiveHandle() throws Exception {
        model().reset();
        String workspace="postcommit-"+UUID.randomUUID();
        jdbc.update("insert into workspace(id,name) values (?,?)",workspace,"synthetic response loss");
        jdbc.update("insert into workspace_member(workspace_id,user_id,role,active) values (?,'dev-user','MANAGER',true)",workspace);
        jdbc.update("insert into bot(id,name,system_prompt,created_at) values ('bot-general','General','test',current_timestamp) on conflict(id) do nothing");
        var created=http.send(request("/graphql",Map.of("query","mutation { createConversation(botId: \"bot-general\", title: \"response loss\", workspaceId: \""+workspace+"\") { id } }")),HttpResponse.BodyHandlers.ofString());
        String conversation=json.readTree(created.body()).path("data").path("createConversation").path("id").asText();
        assertFalse(conversation.isBlank()); String run=UUID.randomUUID().toString();
        var dispatchEntered=new CountDownLatch(1); var releaseDispatch=new CompletableFuture<Void>();
        var held=new AtomicBoolean(); var injected=new AtomicInteger();
        var denyRecoveryRead=new AtomicBoolean(); var unknownReadObserved=new CountDownLatch(1);
        doAnswer(call -> {
            WorkspaceAdmission.Dispatch command=call.getArgument(0);
            if(run.equals(command.registration().runId()) && held.compareAndSet(false,true)) {
                dispatchEntered.countDown(); return releaseDispatch.thenCompose(ignored -> admission.dispatch(command));
            }
            return call.callRealMethod();
        }).when(admission).dispatch(any());
        doAnswer(call -> {
            BudgetCancellationSink.Effect effect=call.getArgument(0);
            if(run.equals(effect.runId())) return CompletableFuture.completedFuture(new BudgetCancellationSink.Result.Retry(effect.effectId(),effect.token(),effect.target()));
            return call.callRealMethod();
        }).when(sink).cancel(any());
        doAnswer(call -> {
            if(denyRecoveryRead.get()) {
                unknownReadObserved.countDown();
                throw new org.springframework.dao.DataAccessResourceFailureException("synthetic recovery read unavailable");
            }
            return call.callRealMethod();
        }).when(recoveryReader).readIdentity(eq(conversation));
        doAnswer(call -> {
            Object result=call.callRealMethod();
            if(result instanceof RunHandle.TerminalResult terminal && terminal.casSuccess() && terminal.finalStatus()==RunStatus.FAILED
                    && "BUDGET_CANCELLATION_SUPERSEDED".equals(terminal.errorMessage()) && injected.get()==0) {
                // The true transaction has returned: independently verify committed PG state before losing the reply.
                try(var connection=dataSource.getConnection(); var statement=connection.prepareStatement("select status,error_message from chat_run where id=?")) {
                    statement.setString(1,run);
                    try(var rows=statement.executeQuery()) {
                        if(rows.next() && "FAILED".equals(rows.getString(1)) && "BUDGET_CANCELLATION_SUPERSEDED".equals(rows.getString(2))
                                && injected.compareAndSet(0,1)) {
                            denyRecoveryRead.set(true);
                            throw new IllegalStateException("synthetic post-commit response loss");
                        }
                    }
                }
            }
            return result;
        }).when(transactions).execute(any());
        var body=Map.of("threadId",conversation,"runId",run,"state",Map.of(),"messages",List.of(Map.of("id",UUID.randomUUID().toString(),"role","user","content","synthetic pending")),"tools",List.of(),"context",List.of(),"forwardedProps",Map.of());
        var stream=http.sendAsync(request("/api/ag-ui",body),HttpResponse.BodyHandlers.ofString());
        try {
            assertTrue(dispatchEntered.await(8,TimeUnit.SECONDS));
            var handle=registry.get(run); assertNotNull(handle);
            var scope=new WorkspaceMonthlyBudgetOperations.Scope(workspace,WorkspaceAdmission.Partition.SIMULATION,YearMonth.from(clock.instant().atZone(ZoneId.of("Asia/Taipei"))));
            budgets.configure(new WorkspaceMonthlyBudgetOperations.ConfigureCommand(scope,"dev-user",true,BigDecimal.ZERO,0)).toCompletableFuture().get(10,TimeUnit.SECONDS);
            String effect=jdbc.queryForObject("select effect_id from workspace_admission_effect_outbox where target_run_id=? and pending_id is not null",String.class,run);
            releaseDispatch.complete(null);
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10); String outcome=null;
            while(System.nanoTime()<deadline) {
                outcome=jdbc.queryForObject("select execution_outcome from provider_attempt where run_id=?",String.class,run);
                if("NOT_DISPATCHED".equals(outcome)) break; Thread.yield();
            }
            assertEquals("NOT_DISPATCHED",outcome);
            // Synthetic authoritative STALE fixture isolates execution reconciliation from delivery timing.
            assertEquals(1,jdbc.update("update workspace_admission_effect_outbox set delivery_status='STALE',ack_receipt=?,acknowledged_at=current_timestamp,delivery_lease_id=null,delivery_lease_until=null where effect_id=? and delivery_status='PENDING'",UUID.randomUUID().toString(),effect));
            deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(12);
            while(System.nanoTime()<deadline && injected.get()==0) Thread.yield();
            assertEquals(1,injected.get(),"the Run terminal transaction must commit before the injected loss");
            assertEquals("FAILED",jdbc.queryForObject("select status from chat_run where id=?",String.class,run));
            assertEquals("BUDGET_CANCELLATION_SUPERSEDED",jdbc.queryForObject("select error_message from chat_run where id=?",String.class,run));
            assertTrue(unknownReadObserved.await(8,TimeUnit.SECONDS),"recovery must encounter the injected DB read uncertainty");
            assertSame(handle,registry.get(run),"an unknown DB read must not remove the live handle");
            assertFalse(handle.durableTerminal().toCompletableFuture().isDone(),"an unknown DB read must not claim local terminal cleanup");
            denyRecoveryRead.set(false);
            HttpResponse<String> response=null;
            try { response=stream.get(8,TimeUnit.SECONDS); } catch(java.util.concurrent.TimeoutException ignored) { }
            String registration=null; long completionDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
            while(System.nanoTime()<completionDeadline) {
                registration=jdbc.queryForObject("select status from workspace_admission_registration where run_id=?",String.class,run);
                if("TERMINAL".equals(registration)) break;
                Thread.yield();
            }
            boolean live=registry.get(run)!=null, durable=handle.durableTerminal().toCompletableFuture().isDone();
            System.out.println("POST_COMMIT_LOSS_OBSERVATION response="+(response==null?"PENDING":response.body())+" registryLive="+live+" durableTerminal="+durable+" registration="+registration);
            assertNotNull(response,"a committed terminal Run must close the live HTTP stream after recovery");
            assertEquals(200,response.statusCode());
            assertTrue(response.body().contains("RUN_STARTED"),response.body());
            assertFalse(response.body().contains("RUN_FINISHED"),"recovery must not invent a success or duplicate terminal event");
            assertFalse(live,"the matching live handle must leave the registry");
            assertTrue(durable,"durable terminal observation must complete");
            assertEquals("TERMINAL",registration);
            assertEquals(0,jdbc.queryForObject("select count(*) from execution_budget_cancellation_receipt where effect_id=?",Integer.class,effect));
            assertEquals(1,jdbc.queryForObject("select count(*) from provider_attempt where run_id=?",Integer.class,run));
            assertTrue(model().getRecordedPrompts().isEmpty());
            verify(runs,times(1)).failDurableRecovery(eq(run),eq(conversation),eq("BUDGET_CANCELLATION_SUPERSEDED"),any());
        } finally { denyRecoveryRead.set(false); releaseDispatch.complete(null); reset(admission,sink,transactions,runs,recoveryReader); }
    }
}
