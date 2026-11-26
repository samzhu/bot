package io.github.samzhu.bot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.samzhu.bot.usage.BudgetCancellationSink;
import io.github.samzhu.bot.usage.WorkspaceAdmission;
import io.github.samzhu.bot.usage.pricing.UsageCaseData;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Sinks;

import static org.assertj.core.api.Assertions.assertThat;

@BackendIntegrationTest
class SharedAgUiDenialIntegrationTests {
    @LocalServerPort private int port;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TransactionTemplate transactions;
    @Autowired private BackendTestChatModel model;
    @Autowired private WorkspaceAdmission admission;
    @Autowired private BudgetCancellationSink cancellation;

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();
    private final String workspace = "agui-denial-" + UUID.randomUUID();
    private UsageCaseData data;
    private String remoteBot;

    @BeforeEach
    void createWorkspace() {
        model.reset();
        data = new UsageCaseData(jdbc, transactions);
        data.createWorkspace(workspace, "dev-user");
    }

    @AfterEach
    void cleanUp() throws InterruptedException {
        try {
            awaitTerminalWork();
            transactions.executeWithoutResult(status -> {
                jdbc.update("delete from execution_budget_cancellation_receipt where workspace_id=?", workspace);
                jdbc.update("delete from workspace_admission_effect_outbox where target_workspace_id=?", workspace);
                jdbc.update("delete from member_weekly_budget_command where workspace_id=?", workspace);
                jdbc.update("delete from member_weekly_budget where workspace_id=?", workspace);
            });
            data.cleanUp();
            if (remoteBot != null) {
                jdbc.update("delete from bot where id = ?", remoteBot);
            }
        } finally {
            http.close();
            model.reset();
        }
    }

    private void awaitTerminalWork() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from workspace_admission_registration
                    where workspace_id=? and status <> 'TERMINAL')
                """, Boolean.class, workspace))) {
            assertThat(System.nanoTime()).as("Run admission must settle before case cleanup").isLessThan(deadline);
            Thread.sleep(20);
        }
    }

    @Test
    void capZeroReturnsBudgetCodeForSseAcceptWithoutRunOrProvider() throws Exception {
        String conversation = conversation();
        assertThat(configureMonthlyCap("0").path("state").asText()).isEqualTo("BLOCKED_CAP");

        assertDenial("MONTH_CAP", conversation, UUID.randomUUID().toString(), 0);
    }

    @Test
    void unknownOnlyReturnsBudgetCodeForSseAcceptWithoutRunOrProvider() throws Exception {
        String denied = conversation();
        recordTerminalUnknownUsage();

        JsonNode budget = configureMonthlyCap("100");
        assertThat(budget.path("state").asText()).isEqualTo("BLOCKED_UNKNOWN");
        assertThat(budget.path("terminalUnknownCount").asText()).isEqualTo("1");
        JsonNode known = budget.path("knownSubtotalUsd");
        if (!known.isNull()) {
            assertThat(known.asText()).matches("0(?:\\.0+)?");
        }

        assertDenial("MONTH_UNKNOWN", denied, UUID.randomUUID().toString(), 1);
    }

    @Test
    void weeklyCapRejectsBeforeStartingARun() throws Exception {
        String conversation = conversation();
        assertThat(configureWeeklyCap("0").path("state").asText()).isEqualTo("BLOCKED_CAP");
        assertDenial("WEEK_CAP", conversation, UUID.randomUUID().toString(), 0);
    }

    @Test
    void weeklyUnknownCostRejectsBeforeStartingARun() throws Exception {
        String conversation = conversation();
        recordTerminalUnknownUsage();
        var budget = configureWeeklyCap("100");
        assertThat(budget.path("state").asText()).isEqualTo("BLOCKED_UNKNOWN");
        assertThat(budget.path("terminalUnknownCount").asText()).isEqualTo("1");
        assertDenial("WEEK_UNKNOWN", conversation, UUID.randomUUID().toString(), 1);
    }

    @Test
    void loweringMonthlyCapCancelsActiveRunAndReplaysItsReceipt() throws Exception {
        String conversation = conversation();
        String run = UUID.randomUUID().toString();
        var subscribed = new CountDownLatch(1);
        var cancelled = new CountDownLatch(1);
        var pending = Sinks.<ChatResponse>one();
        model.streamWith(pending.asMono().flux()
                .doOnSubscribe(subscription -> subscribed.countDown())
                .doOnCancel(cancelled::countDown));
        var stream = http.sendAsync(agUiRequest(conversation, run), HttpResponse.BodyHandlers.ofString());

        try {
            assertThat(subscribed.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(configureMonthlyCap("0").path("state").asText()).isEqualTo("BLOCKED_CAP");
            String effectId = jdbc.queryForObject("""
                    select effect_id from workspace_admission_effect_outbox
                    where target_workspace_id=? and target_run_id=? and claim_id is not null
                    """, String.class, workspace, run);
            var found = (WorkspaceAdmission.EffectInspection.Found) admission.inspectCancellationEffect(effectId)
                    .toCompletableFuture().get(5, TimeUnit.SECONDS);
            var fact = found.effect();
            // The test profile disables scheduling. Feed the real sink the exact durable policy effect.
            var effect = new BudgetCancellationSink.Effect(effectId, fact.token(), workspace, fact.partition(),
                    run, fact.target(), fact.intentReceipt());
            var accepted = (BudgetCancellationSink.Result.Accepted) cancellation.cancel(effect)
                    .toCompletableFuture().get(5, TimeUnit.SECONDS);

            var response = stream.get(5, TimeUnit.SECONDS);
            assertThat(response.statusCode()).isEqualTo(200);
            var terminal = response.body().lines().filter(line -> line.startsWith("data:")).toList().getLast();
            JsonNode event = json.readTree(terminal.substring(5));
            assertThat(event.path("type").asText()).isEqualTo("RUN_FINISHED");
            assertThat(event.path("outcome").path("type").asText()).isEqualTo("cancelled");
            assertThat(cancelled.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(jdbc.queryForObject("select status from chat_run where id=?", String.class, run))
                    .isEqualTo("CANCELLED");
            var replay = cancellation.cancel(effect).toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertThat(replay).isEqualTo(accepted);
            assertThat(jdbc.queryForObject("""
                    select acceptance_receipt from execution_budget_cancellation_receipt where effect_id=?
                    """, String.class, effectId)).isEqualTo(accepted.receiptId());
            var claim = ((BudgetCancellationSink.Target.Claimed) fact.target()).fence();
            var wrongFence = new WorkspaceAdmission.ClaimFence(claim.token(), claim.claimId(), claim.attemptId(),
                    "different-workspace", claim.partition(), claim.periodStart(), claim.periodEnd(),
                    claim.policyGeneration());
            var mismatched = new BudgetCancellationSink.Effect(effectId, fact.token(), workspace, fact.partition(),
                    run, new BudgetCancellationSink.Target.Claimed(wrongFence), fact.intentReceipt());
            assertThat(cancellation.cancel(mismatched).toCompletableFuture().get(5, TimeUnit.SECONDS))
                    .isInstanceOf(BudgetCancellationSink.Result.Retry.class);
        } finally {
            pending.tryEmitEmpty();
            stream.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void disconnectedGkeKeepsItsOwnCodeForSseAccept() throws Exception {
        remoteBot = "agui-denial-bot-" + UUID.randomUUID();
        String conversation = UUID.randomUUID().toString();
        // The public API refuses new disconnected GKE conversations in this mode. Exercise an existing one.
        transactions.executeWithoutResult(status -> {
            jdbc.update("""
                    insert into bot(id,name,target,description,instructions,owner_id,created_at,updated_at)
                    values (?,'GKE','GKE_REMOTE','Fixture','Fixture','dev-user',current_timestamp,current_timestamp)
                    """, remoteBot);
            jdbc.update("""
                    insert into conversation(id,bot_id,title,user_id,pinned,created_at,updated_at,workspace_id)
                    values (?,?,'GKE fixture','dev-user',false,current_timestamp,current_timestamp,?)
                    """, conversation, remoteBot, workspace);
        });

        assertDenial("GKE_AGENT_NOT_CONNECTED", conversation, UUID.randomUUID().toString(), 0);
    }

    private String conversation() throws Exception {
        String id = graph("""
                mutation Create($workspace: ID!) {
                  createConversation(botId: "bot-general", title: "Denial fixture", workspaceId: $workspace) { id }
                }
                """, Map.of("workspace", workspace)).path("createConversation").path("id").asText();
        assertThat(id).isNotBlank();
        return id;
    }

    private void recordTerminalUnknownUsage() throws Exception {
        String sourceRun = UUID.randomUUID().toString();
        // The default fixture response has no native usage, so the real adapter records no priced usage.
        var response = start(conversation(), sourceRun);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("text/event-stream");
        assertThat(response.body()).contains("RUN_FINISHED");
        assertThat(jdbc.queryForObject("select status from chat_run where id = ?", String.class, sourceRun))
                .isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("""
                select count(*) from provider_attempt where run_id = ? and provider = 'GOOGLE' and not simulation
                """, Integer.class, sourceRun)).isEqualTo(1);

    }

    private JsonNode configureWeeklyCap(String cap) throws Exception {
        JsonNode configured = graph("""
                mutation Configure($workspace: ID!, $cap: String!) {
                  configureMemberWeeklyBudget(input: {
                    workspaceId: $workspace, memberUserId: "dev-user", simulation: false, enabled: true,
                    capUsd: $cap, expectedConfigVersion: "0", idempotencyKey: "agui-denial"
                  }) { outcome budget { state terminalUnknownCount } }
                }
                """, Map.of("workspace", workspace, "cap", cap)).path("configureMemberWeeklyBudget");
        assertThat(configured.path("outcome").asText()).isEqualTo("APPLIED");
        return configured.path("budget");
    }

    private JsonNode configureMonthlyCap(String cap) throws Exception {
        JsonNode configured = graph("""
                mutation Configure($workspace: ID!, $cap: String!) {
                  configureWorkspaceMonthlyBudget(input: {
                    workspaceId: $workspace, simulation: false, enabled: true, capUsd: $cap,
                    expectedConfigVersion: "0", idempotencyKey: "agui-denial"
                  }) { outcome budget { state terminalUnknownCount knownSubtotalUsd } }
                }
                """, Map.of("workspace", workspace, "cap", cap)).path("configureWorkspaceMonthlyBudget");
        assertThat(configured.path("outcome").asText()).isEqualTo("APPLIED");
        return configured.path("budget");
    }

    private void assertDenial(String code, String conversation, String run, int expectedModelCalls) throws Exception {
        var response = start(conversation, run);
        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("application/json");
        assertThat(json.readTree(response.body())).isEqualTo(json.createObjectNode().put("code", code));
        assertThat(jdbc.queryForObject("select count(*) from chat_run where id = ?", Integer.class, run)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from workspace_admission_registration where run_id = ?",
                Integer.class, run)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from provider_attempt where run_id = ?",
                Integer.class, run)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from ai_session where id = ?",
                Integer.class, conversation)).isZero();
        assertThat(model.invocations()).isEqualTo(expectedModelCalls);
    }

    private HttpResponse<String> start(String conversation, String run) throws Exception {
        return http.send(agUiRequest(conversation, run), HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest agUiRequest(String conversation, String run) throws Exception {
        return request("/api/ag-ui", "text/event-stream", Map.of(
                "threadId", conversation, "runId", run, "state", Map.of(), "tools", List.of(),
                "context", List.of(), "forwardedProps", Map.of(), "messages", List.of(Map.of(
                        "id", UUID.randomUUID().toString(), "role", "user", "content", "Synthetic denial"))));
    }

    private JsonNode graph(String query, Map<String, ?> variables) throws Exception {
        var response = post("/graphql", "application/json", Map.of("query", query, "variables", variables));
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode result = json.readTree(response.body());
        assertThat(result.has("errors")).as("%s", result).isFalse();
        return result.path("data");
    }

    private HttpResponse<String> post(String path, String accept, Object body) throws Exception {
        return http.send(request(path, accept, body), HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest request(String path, String accept, Object body) throws Exception {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("Accept", accept)
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                .build();
    }
}
