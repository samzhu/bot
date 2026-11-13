package io.github.samzhu.bot;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.samzhu.bot.usage.BudgetCancellationSink;
import io.github.samzhu.bot.usage.WorkspaceAdmission;
import io.github.samzhu.bot.usage.WorkspaceMonthlyBudgetOperations;
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
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class AgUiReceiptAtomicityIntegrationTests {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @MockitoSpyBean WorkspaceAdmission admission;
    @Autowired DataSource dataSource;
    @Autowired WorkspaceMonthlyBudgetOperations budgets;
    @Autowired BudgetCancellationSink sink;
    @Autowired Clock usageClock;
    @TestBean(name = "mockChatModel", methodName = "model", enforceOverride = true) ChatModel chatModel;
    static ControlledChatModel model() { return ControlledChatModel.getInstance(); }
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach void setup() {
        model().reset();
        jdbc.update("insert into bot(id,name,system_prompt,created_at) values ('bot-general','General Assistant','trusted',current_timestamp) on conflict(id) do nothing");
    }

    private HttpResponse<String> post(String path, Object body) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path))
                .header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
        return http.send(request,HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void lockedTerminalRunReturnsUnknownThenCompletesAfterUnlock() throws Exception {
        var created=post("/graphql",Map.of("query","mutation { createConversation(botId: \"bot-general\", title: \"recovery lock\") { id } }"));
        String conversation=json.readTree(created.body()).path("data").path("createConversation").path("id").asText();
        assertFalse(conversation.isBlank(),created.body());
        String run=UUID.randomUUID().toString();
        var notification=new java.util.concurrent.CountDownLatch(1);
        doAnswer(call -> {
            WorkspaceAdmission.Registration registration=call.getArgument(0);
            if (!run.equals(registration.runId())) return call.callRealMethod();
            notification.countDown();
            return java.util.concurrent.CompletableFuture.failedFuture(new IllegalStateException("injected lost completion notification"));
        }).when(admission).complete(any());
        try {
            var body=Map.of("threadId",conversation,"runId",run,"state",Map.of(),"messages",List.of(Map.of("id",UUID.randomUUID().toString(),"role","user","content","recovery lock")),"tools",List.of(),"context",List.of(),"forwardedProps",Map.of());
            var response=post("/api/ag-ui",body);
            assertEquals(200,response.statusCode()); assertTrue(response.body().contains("RUN_FINISHED"),response.body());
            assertTrue(notification.await(5,TimeUnit.SECONDS));
            assertEquals("COMPLETED",jdbc.queryForObject("select status from chat_run where id=?",String.class,run));
            assertEquals("SUCCEEDED",jdbc.queryForObject("select execution_outcome from provider_attempt where run_id=?",String.class,run));
            try (var connection=dataSource.getConnection()) {
                connection.setAutoCommit(false);
                int blocker;
                try (var statement=connection.createStatement(); var rows=statement.executeQuery("select pg_backend_pid()")) { assertTrue(rows.next()); blocker=rows.getInt(1); }
                try (var statement=connection.prepareStatement("select id from chat_run where id=? for update")) {
                    statement.setString(1,run); try (var rows=statement.executeQuery()) { assertTrue(rows.next()); }
                }
                var pending=admission.completeTerminalRun(run).toCompletableFuture();
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5); boolean waiting=false;
                while(System.nanoTime()<deadline) {
                    waiting=Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from pg_stat_activity where ? = any(pg_blocking_pids(pid)))",Boolean.class,blocker));
                    if(waiting) break;
                    Thread.yield();
                }
                assertTrue(waiting,"recovery did not reach the locked Run row");
                assertEquals(WorkspaceAdmission.TerminalRunCompletion.UNKNOWN,pending.get(10,TimeUnit.SECONDS));
                assertNotEquals("TERMINAL",jdbc.queryForObject("select status from workspace_admission_registration where run_id=?",String.class,run));
                connection.rollback();
            }
            var retried=admission.completeTerminalRun(run).toCompletableFuture().get(10,TimeUnit.SECONDS);
            assertTrue(retried==WorkspaceAdmission.TerminalRunCompletion.COMPLETED
                    || retried==WorkspaceAdmission.TerminalRunCompletion.ALREADY_COMPLETED,retried.toString());
            assertEquals("TERMINAL",jdbc.queryForObject("select status from workspace_admission_registration where run_id=?",String.class,run));
        } finally { reset(admission); }
    }

    @Test
    void receiptInsertFailureRollsBackRunCancellationAndSameEffectCanRetry() throws Exception {
        var created=post("/graphql",Map.of("query","mutation { createConversation(botId: \"bot-general\", title: \"receipt rollback\") { id } }"));
        String conversation=json.readTree(created.body()).path("data").path("createConversation").path("id").asText();
        assertFalse(conversation.isBlank(),created.body());
        String workspace=jdbc.queryForObject("select workspace_id from conversation where id=?",String.class,conversation);
        String run=UUID.randomUUID().toString();
        var scope=new WorkspaceMonthlyBudgetOperations.Scope(workspace,WorkspaceAdmission.Partition.SIMULATION,
                YearMonth.from(usageClock.instant().atZone(ZoneId.of("Asia/Taipei"))));
        var previous=budgets.read(new WorkspaceMonthlyBudgetOperations.ReadCommand(scope,"dev-user")).toCompletableFuture().join();
        var disabled=budgets.configure(new WorkspaceMonthlyBudgetOperations.ConfigureCommand(scope,"dev-user",false,previous.capUsd(),previous.configVersion())).toCompletableFuture().join();
        model().setHoldMode(true);
        var body=Map.of("threadId",conversation,"runId",run,"state",Map.of(),"messages",List.of(Map.of("id",UUID.randomUUID().toString(),"role","user","content","receipt rollback")),"tools",List.of(),"context",List.of(),"forwardedProps",Map.of());
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/ag-ui"))
                .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
        var stream=http.sendAsync(request,HttpResponse.BodyHandlers.ofString());
        try {
            assertTrue(model().awaitSubscribed(5,TimeUnit.SECONDS),"provider claim was not established");
            budgets.configure(new WorkspaceMonthlyBudgetOperations.ConfigureCommand(scope,"dev-user",true,BigDecimal.ZERO,disabled.configVersion())).toCompletableFuture().join();
            String effectId=jdbc.queryForObject("select effect_id from workspace_admission_effect_outbox where target_run_id=? and claim_id is not null",String.class,run);
            var view=assertInstanceOf(WorkspaceAdmission.EffectInspection.Found.class,admission.inspectCancellationEffect(effectId).toCompletableFuture().join()).effect();
            var effect=new BudgetCancellationSink.Effect(effectId,view.token(),view.workspaceId(),view.partition(),run,view.target(),view.intentReceipt());
            jdbc.execute("create function test_reject_execution_receipt() returns trigger language plpgsql as $$ begin raise exception 'injected receipt failure'; end $$");
            jdbc.execute("create trigger test_reject_execution_receipt before insert on execution_budget_cancellation_receipt for each row execute function test_reject_execution_receipt()");
            try {
                assertInstanceOf(BudgetCancellationSink.Result.Retry.class,sink.cancel(effect).toCompletableFuture().get(10,TimeUnit.SECONDS));
                assertEquals("RUNNING",jdbc.queryForObject("select status from chat_run where id=?",String.class,run));
                assertEquals(0,jdbc.queryForObject("select count(*) from execution_budget_cancellation_receipt where effect_id=?",Integer.class,effectId));
                assertEquals("PENDING",jdbc.queryForObject("select delivery_status from workspace_admission_effect_outbox where effect_id=?",String.class,effectId));
            } finally {
                jdbc.execute("drop trigger if exists test_reject_execution_receipt on execution_budget_cancellation_receipt");
                jdbc.execute("drop function if exists test_reject_execution_receipt()");
            }
            var accepted=assertInstanceOf(BudgetCancellationSink.Result.Accepted.class,sink.cancel(effect).toCompletableFuture().get(10,TimeUnit.SECONDS));
            assertEquals("CANCELLED",jdbc.queryForObject("select status from chat_run where id=?",String.class,run));
            assertEquals(accepted.receiptId(),jdbc.queryForObject("select acceptance_receipt from execution_budget_cancellation_receipt where effect_id=?",String.class,effectId));
            var matched=assertInstanceOf(BudgetCancellationSink.ReceiptInspection.Matched.class,
                    sink.inspectAcceptance(effect,accepted.receiptId()).toCompletableFuture().get(10,TimeUnit.SECONDS));
            assertEquals(effect,matched.effect());
            assertEquals(accepted.receiptId(),matched.acceptanceReceipt());
            assertInstanceOf(BudgetCancellationSink.ReceiptInspection.Mismatch.class,
                    sink.inspectAcceptance(effect,UUID.randomUUID().toString()).toCompletableFuture().get(10,TimeUnit.SECONDS));
            var claimed=((BudgetCancellationSink.Target.Claimed)effect.target()).fence();
            var wrongFence=new WorkspaceAdmission.ClaimFence(claimed.token(),claimed.claimId(),claimed.attemptId(),
                    UUID.randomUUID().toString(),claimed.partition(),claimed.periodStart(),claimed.periodEnd(),claimed.policyGeneration());
            var wrongEffect=new BudgetCancellationSink.Effect(effect.effectId(),effect.token(),effect.workspaceId(),effect.partition(),
                    effect.runId(),new BudgetCancellationSink.Target.Claimed(wrongFence),effect.intentReceipt());
            assertInstanceOf(BudgetCancellationSink.ReceiptInspection.Mismatch.class,
                    sink.inspectAcceptance(wrongEffect,accepted.receiptId()).toCompletableFuture().get(10,TimeUnit.SECONDS));
            var wrongToken=new WorkspaceAdmission.ClaimFence(UUID.randomUUID().toString(),claimed.claimId(),claimed.attemptId(),
                    claimed.workspaceId(),claimed.partition(),claimed.periodStart(),claimed.periodEnd(),claimed.policyGeneration());
            var wrongPartition=new WorkspaceAdmission.ClaimFence(claimed.token(),claimed.claimId(),claimed.attemptId(),
                    claimed.workspaceId(),WorkspaceAdmission.Partition.REAL,claimed.periodStart(),claimed.periodEnd(),claimed.policyGeneration());
            for (var forged : List.of(wrongToken,wrongPartition)) {
                var forgedEffect=new BudgetCancellationSink.Effect(effect.effectId(),effect.token(),effect.workspaceId(),effect.partition(),
                        effect.runId(),new BudgetCancellationSink.Target.Claimed(forged),effect.intentReceipt());
                assertInstanceOf(BudgetCancellationSink.ReceiptInspection.Mismatch.class,
                        sink.inspectAcceptance(forgedEffect,accepted.receiptId()).toCompletableFuture().get(10,TimeUnit.SECONDS));
            }
            var absent=new BudgetCancellationSink.Effect(UUID.randomUUID().toString(),effect.token(),effect.workspaceId(),
                    effect.partition(),effect.runId(),effect.target(),effect.intentReceipt());
            assertInstanceOf(BudgetCancellationSink.ReceiptInspection.Unknown.class,
                    sink.inspectAcceptance(absent,accepted.receiptId()).toCompletableFuture().get(10,TimeUnit.SECONDS));
            jdbc.execute("alter table execution_budget_cancellation_receipt rename to test_hidden_execution_receipt");
            try {
                assertInstanceOf(BudgetCancellationSink.ReceiptInspection.Unknown.class,
                        sink.inspectAcceptance(effect,accepted.receiptId()).toCompletableFuture().get(10,TimeUnit.SECONDS));
            } finally {
                jdbc.execute("alter table test_hidden_execution_receipt rename to execution_budget_cancellation_receipt");
            }
            var replay=assertInstanceOf(BudgetCancellationSink.Result.Accepted.class,sink.cancel(effect).toCompletableFuture().get(10,TimeUnit.SECONDS));
            assertEquals(accepted.receiptId(),replay.receiptId());
            assertTrue(stream.get(10,TimeUnit.SECONDS).body().contains("cancelled"));
        } finally {
            model().complete();
            var current=budgets.read(new WorkspaceMonthlyBudgetOperations.ReadCommand(scope,"dev-user")).toCompletableFuture().join();
            budgets.configure(new WorkspaceMonthlyBudgetOperations.ConfigureCommand(scope,"dev-user",previous.enabled(),previous.capUsd(),current.configVersion())).toCompletableFuture().join();
        }
    }
}
