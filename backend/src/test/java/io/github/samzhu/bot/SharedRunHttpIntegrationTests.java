package io.github.samzhu.bot;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genai.types.GenerateContentResponseUsageMetadata;
import com.zaxxer.hikari.HikariDataSource;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.session.SessionService;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@BackendIntegrationTest
class SharedRunHttpIntegrationTests {
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final String workspace = "test-run-" + UUID.randomUUID();

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired TransactionTemplate transactions;
    @Autowired BackendTestChatModel model;
    @Autowired SessionService sessions;

    @BeforeEach
    void createWorkspace() {
        model.reset();
        transactions.executeWithoutResult(status -> {
            jdbc.update("insert into workspace(id, name) values (?, 'Run HTTP test')", workspace);
            jdbc.update("""
                    insert into workspace_member(workspace_id, user_id, role) values (?, 'dev-user', 'MANAGER')
                    """, workspace);
        });
    }

    @AfterEach
    void cleanWorkspace() throws InterruptedException {
        try {
            awaitTerminalWork();
            var sql = new NamedParameterJdbcTemplate(jdbc);
            var scope = Map.of("workspace", workspace);
            transactions.executeWithoutResult(status -> {
                // Ledger and admission rows reference Runs and Conversations without cascade.
                for (String table : List.of("usage_cost_revision_audit", "usage_cost_manual_source_checkpoint",
                        "usage_cost_current", "usage_cost_estimate", "usage_price_snapshot",
                        "workspace_admission_finalization", "workspace_admission_dispatch")) {
                    sql.update("delete from " + table + """
                             where attempt_id in (select id from provider_attempt where workspace_id = :workspace)
                            """, scope);
                }
                sql.update("update provider_attempt set projection_snapshot_id = null where workspace_id = :workspace", scope);
                sql.update("""
                        delete from usage_snapshot where attempt_id in
                        (select id from provider_attempt where workspace_id = :workspace)
                        """, scope);
                sql.update("delete from workspace_title_reservation where workspace_id = :workspace", scope);
                sql.update("delete from provider_attempt where workspace_id = :workspace", scope);
                sql.update("""
                        delete from workspace_admission_claim where registration_token in
                        (select token from workspace_admission_registration where workspace_id = :workspace)
                        """, scope);
                sql.update("delete from workspace_admission_registration where workspace_id = :workspace", scope);
                sql.update("""
                        delete from ai_session where id in (select id from conversation where workspace_id = :workspace)
                        """, scope);
                sql.update("delete from conversation where workspace_id = :workspace", scope);
                sql.update("delete from workspace_monthly_budget_period where workspace_id = :workspace", scope);
                sql.update("delete from workspace_monthly_budget where workspace_id = :workspace", scope);
                sql.update("delete from workspace_member where workspace_id = :workspace", scope);
                sql.update("delete from workspace where id = :workspace", scope);
            });
        } finally {
            http.shutdownNow();
            model.reset();
        }
    }

    @Test
    void legacySseCompletesRunAndPersistsMessagesAndUsage() throws Exception {
        String conversation = conversation();
        model.streamWith(Flux.just(chunk("Fixture "), usageChunk("reply")));
        var response = post(legacyPath(conversation), legacyInput("Hello Real SSE"));
        var events = events(response);
        assertThat(events).extracting(Event::name)
                .startsWith("run.started").contains("message.delta").endsWith("run.completed");
        assertThat(events).extracting(event -> event.data().path("seq").asInt())
                .containsExactlyElementsOf(IntStream.rangeClosed(1, events.size()).boxed().toList());
        String run = events.getFirst().data().path("runId").asText();
        assertThat(run).isNotBlank();
        assertThat(events).allMatch(event -> event.data().path("runId").asText().equals(run));
        assertThat(runStatus(run)).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForList("select role, content from message where run_id = ? order by role", run))
                .containsExactly(Map.of("role", "ASSISTANT", "content", "Fixture reply"),
                        Map.of("role", "USER", "content", "Hello Real SSE"));
        assertThat(jdbc.queryForMap("select id, interrupted from message where run_id = ? and role = 'ASSISTANT'", run))
                .isEqualTo(Map.of("id", events.getLast().data().path("messageId").asText(), "interrupted", false));
        assertThat(sessions.getActiveMessages(conversation)).hasSize(2);
        assertThat(jdbc.queryForList("""
                select s.source_layer, s.raw_usage ->> 'promptTokenCount' as prompt_tokens
                from usage_snapshot s join provider_attempt a on a.id = s.attempt_id
                where a.run_id = ?
                """, run)).containsExactly(Map.of("source_layer", "SDK_NATIVE", "prompt_tokens", "3"));
    }

    @Test
    void legacySseRetainsSessionContextAcrossTurns() throws Exception {
        String conversation = conversation();
        model.streamWith(Flux.just(usageChunk("Your code is 42")));
        assertThat(events(post(legacyPath(conversation), legacyInput("My secret code is 42"))).getLast().name())
                .isEqualTo("run.completed");
        assertThat(events(post(legacyPath(conversation), legacyInput("Repeat my secret code"))).getLast().name())
                .isEqualTo("run.completed");

        assertThat(sessions.getActiveMessages(conversation)).hasSize(4);
        assertThat(model.prompts()).hasSize(2);
        var instructions = model.prompts().getLast().getInstructions();
        assertThat(instructions).hasSize(4);
        assertThat(instructions.subList(1, 4)).extracting(message -> message.getText())
                .containsExactly("My secret code is 42", "Your code is 42", "Repeat my secret code");

        for (int turn = 3; turn <= 12; turn++) {
            assertThat(events(post(legacyPath(conversation), legacyInput("Turn " + turn))).getLast().name())
                    .isEqualTo("run.completed");
        }
        assertThat(model.prompts().getLast().getInstructions()).hasSize(22)
                .extracting(message -> message.getText())
                .doesNotContain("My secret code is 42").contains("Repeat my secret code", "Turn 11", "Turn 12");
    }

    @Test
    void legacySseReplaysCompletedRunAndRejectsChangedInput() throws Exception {
        String conversation = conversation();
        String path = legacyPath(conversation);
        var input = legacyInput("Original prompt");
        model.streamWith(Flux.just(usageChunk("Completed reply")));
        String run = events(post(path, input)).getLast().data().path("runId").asText();
        var duplicate = post(path, input);
        assertThat(duplicate.statusCode()).isEqualTo(200);
        assertThat(duplicate.headers().firstValue("Content-Type")).hasValueSatisfying(type ->
                assertThat(type).startsWith("application/json"));
        assertThat(json.readTree(duplicate.body()).path("clientRequestId").asText()).isEqualTo(input.get("clientRequestId"));
        assertThat(post(path, Map.of("clientRequestId", input.get("clientRequestId"), "message", "Changed prompt"))
                .statusCode()).isEqualTo(409);
        assertThat(model.invocations()).isEqualTo(1);
        assertThat(jdbc.queryForList("select id from chat_run where conversation_id = ?", String.class, conversation))
                .containsExactly(run);
        assertCancel(run, "COMPLETED", false);
        assertCancel(run, "COMPLETED", false);
        assertThat(runStatus(run)).isEqualTo("COMPLETED");
    }

    @Test
    void legacySseFailurePreservesPartialOutsideSessionMemory() throws Exception {
        String conversation = conversation();
        model.streamWith(Flux.concat(Flux.just(chunk("Partial delta chunk")),
                Flux.error(new IllegalStateException("Simulated upstream model error"))));
        var events = events(post(legacyPath(conversation), legacyInput("Fail the model")));
        assertThat(events).extracting(Event::name).contains("message.delta").endsWith("run.failed");
        assertThat(events).filteredOn(event -> event.name().equals("run.failed")).hasSize(1);
        var failure = events.getLast().data();
        assertThat(failure.path("status").asText()).isEqualTo("FAILED");
        assertThat(failure.path("reason").asText()).contains("Simulated upstream model error");
        String run = failure.path("runId").asText();
        assertThat(runStatus(run)).isEqualTo("FAILED");
        assertThat(jdbc.queryForMap("select content, interrupted from message where run_id = ? and role = 'ASSISTANT'", run))
                .isEqualTo(Map.of("content", "Partial delta chunk", "interrupted", true));
        assertThat(sessions.getActiveMessages(conversation)).isEmpty();
    }

    @Test
    void legacySseOnlyAllowsOneActiveRunAndKeepsPartialAfterCancellation() throws Exception {
        String conversation = conversation();
        String path = legacyPath(conversation);
        model.streamWith(Flux.just(usageChunk("Earlier completed reply")));
        assertThat(events(post(path, legacyInput("Earlier completed turn"))).getLast().name()).isEqualTo("run.completed");
        var pending = Sinks.<ChatResponse>one();
        var disposed = new CompletableFuture<Void>();
        model.streamWith(Flux.concat(Flux.just(usageChunk("Partial delta 1")), pending.asMono())
                .doFinally(signal -> disposed.complete(null)));
        try {
            var open = openStream(path, legacyInput("Cancel prompt"), "message.delta");
            String run = open.event().get(10, TimeUnit.SECONDS).data().path("runId").asText();
            assertThat(run).isNotBlank();
            var conflict = post(path, legacyInput("Parallel prompt"));
            assertThat(conflict.statusCode()).isEqualTo(409);
            assertThat(conflict.headers().firstValue("Content-Type")).hasValueSatisfying(type ->
                    assertThat(type).startsWith("application/json"));
            var conflictData = json.readTree(conflict.body());
            assertThat(conflictData.path("error").asText()).isEqualTo("Another active run is in progress for this conversation");
            assertThat(conflictData.path("runId").asText()).isEqualTo(run);
            assertCancel(run, "CANCELLED", true);
            var events = open.finished().get(10, TimeUnit.SECONDS);
            assertThat(events).extracting(Event::name).doesNotContain("run.completed", "run.failed").endsWith("run.cancelled");
            assertThat(events).filteredOn(event -> event.name().equals("run.cancelled")).hasSize(1);
            assertThat(jdbc.queryForMap("select content, interrupted from message where run_id = ? and role = 'ASSISTANT'", run))
                    .isEqualTo(Map.of("content", "Partial delta 1", "interrupted", true));
            assertThat(runStatus(run)).isEqualTo("CANCELLED");
            assertThat(sessions.getActiveMessages(conversation)).extracting(message -> message.getText())
                    .containsExactly("Earlier completed turn", "Earlier completed reply");
            disposed.get(10, TimeUnit.SECONDS);

            awaitTerminalWork();
            model.streamWith(Flux.just(usageChunk("Next turn reply")));
            assertThat(events(post(path, legacyInput("Next turn"))).getLast().name()).isEqualTo("run.completed");
            assertThat(model.invocations()).isEqualTo(3);
            var instructions = model.prompts().getLast().getInstructions();
            assertThat(instructions).hasSize(4);
            assertThat(instructions.subList(1, 4)).extracting(message -> message.getText())
                    .containsExactly("Earlier completed turn", "Earlier completed reply", "Next turn");
        } finally {
            pending.tryEmitEmpty();
        }
    }

    @Test
    void legacySseCompletesAfterRejectingContenderAndAllowsNextRun() throws Exception {
        String conversation = conversation();
        String path = legacyPath(conversation);
        var pending = Sinks.<ChatResponse>one();
        model.streamWith(Flux.concat(Flux.just(chunk("Run 1 ")), pending.asMono()));
        try {
            var original = openStream(path, legacyInput("Active Run 1"), "message.delta");
            String run = original.event().get(10, TimeUnit.SECONDS).data().path("runId").asText();

            var conflict = post(path, legacyInput("Contender"));
            assertThat(conflict.statusCode()).isEqualTo(409);
            assertThat(json.readTree(conflict.body()).path("runId").asText()).isEqualTo(run);
            assertThat(pending.tryEmitValue(usageChunk("finished"))).isEqualTo(Sinks.EmitResult.OK);
            var originalEvents = original.finished().get(10, TimeUnit.SECONDS);
            assertThat(originalEvents).filteredOn(event -> event.name().equals("run.completed")).hasSize(1);
            assertThat(originalEvents.getLast().name()).isEqualTo("run.completed");
            assertThat(runStatus(run)).isEqualTo("COMPLETED");
            assertThat(jdbc.queryForObject("select content from message where run_id = ? and role = 'ASSISTANT'", String.class, run))
                    .isEqualTo("Run 1 finished");

            awaitTerminalWork();
            model.streamWith(Flux.just(usageChunk("Next reply")));
            var nextEvents = events(post(path, legacyInput("Next Run")));
            assertThat(nextEvents).filteredOn(event -> event.name().equals("run.completed")).hasSize(1);
            assertThat(nextEvents.getLast().name()).isEqualTo("run.completed");
            assertThat(runStatus(nextEvents.getLast().data().path("runId").asText())).isEqualTo("COMPLETED");
            assertThat(model.invocations()).isEqualTo(2);
        } finally {
            pending.tryEmitEmpty();
        }
    }

    @Test
    void legacySseCompletionAndCancellationRaceHasOneAuthoritativeOutcome() throws Exception {
        String conversation = conversation();
        String path = legacyPath(conversation);
        String partial = "Partial delta before race";
        var pending = Sinks.<ChatResponse>one();
        var disposed = new CompletableFuture<Void>();
        model.streamWith(Flux.concat(Flux.just(usageChunk(partial)), pending.asMono())
                .doFinally(signal -> disposed.complete(null)));
        try {
            var open = openStream(path, legacyInput("Race prompt"), "message.delta");
            String run = open.event().get(10, TimeUnit.SECONDS).data().path("runId").asText();
            assertThat(run).isNotBlank();
            String cancelPath = "/api/runs/" + run + "/cancel";
            var contenders = race(pending::tryEmitEmpty, () -> post(cancelPath, Map.of()));
            assertThat(contenders.first()).isIn(Sinks.EmitResult.OK, Sinks.EmitResult.FAIL_CANCELLED);
            var cancel = contenders.second();
            assertThat(cancel.statusCode()).isEqualTo(200);

            var allEvents = open.finished().get(10, TimeUnit.SECONDS);
            Event terminal = onlyLegacyTerminal(allEvents);
            assertThat(terminal.name()).isIn("run.completed", "run.cancelled");
            assertThat(terminal.data().path("runId").asText()).isEqualTo(run);
            boolean cancelled = terminal.name().equals("run.cancelled");
            String expectedStatus = cancelled ? "CANCELLED" : "COMPLETED";
            assertThat(runStatus(run)).isEqualTo(expectedStatus);
            assertThat(terminal.data().path("status").asText()).isEqualTo(expectedStatus);

            var cancellation = json.readTree(cancel.body());
            assertThat(cancellation.path("status").asText()).isEqualTo(expectedStatus);
            assertThat(cancellation.path("cancelled").asBoolean()).isEqualTo(cancelled);
            assertCancel(run, expectedStatus, cancelled);
            assertThat(jdbc.queryForMap("select content, interrupted from message where run_id = ? and role = 'ASSISTANT'", run))
                    .isEqualTo(Map.of("content", partial, "interrupted", cancelled));
            var activeMessages = sessions.getActiveMessages(conversation);
            if (cancelled) {
                assertThat(activeMessages).isEmpty();
            } else {
                assertThat(activeMessages).extracting(message -> message.getText())
                        .containsExactly("Race prompt", partial);
            }
            assertThat(model.invocations()).isEqualTo(1);
            disposed.get(10, TimeUnit.SECONDS);
        } finally {
            pending.tryEmitEmpty();
        }
    }

    @Test
    void concurrentLegacyRequestsWithSameInputReturnOneStreamAndOneReplay() throws Exception {
        String conversation = conversation();
        String path = legacyPath(conversation);
        String prompt = "Concurrent dedup message";
        var input = legacyInput(prompt);
        model.streamWith(Flux.just(usageChunk("Single reply")));
        var contenders = race(() -> post(path, input), () -> post(path, input));
        boolean firstIsStream = hasContentType(contenders.first(), "text/event-stream");
        var stream = firstIsStream ? contenders.first() : contenders.second();
        var replay = firstIsStream ? contenders.second() : contenders.first();
        assertThat(replay.statusCode()).isEqualTo(200);
        assertThat(hasContentType(replay, "application/json")).isTrue();
        var replayData = json.readTree(replay.body());

        String run = assertSingleCompletedRun(conversation, input.get("clientRequestId"), prompt, stream);
        assertThat(replayData.path("runId").asText()).isEqualTo(run);
        assertThat(replayData.path("id").asText()).isEqualTo(run);
        assertThat(replayData.path("clientRequestId").asText()).isEqualTo(input.get("clientRequestId"));
        assertThat(replayData.path("promptText").asText()).isEqualTo(prompt);
    }

    @Test
    void concurrentLegacyRequestsWithDifferentInputReturnOneStreamAndOneConflict() throws Exception {
        String conversation = conversation();
        String path = legacyPath(conversation);
        String requestId = UUID.randomUUID().toString();
        String alpha = "Payload Alpha";
        String beta = "Payload Beta";
        model.streamWith(Flux.just(usageChunk("Single reply")));
        var contenders = race(
                () -> post(path, Map.of("clientRequestId", requestId, "message", alpha)),
                () -> post(path, Map.of("clientRequestId", requestId, "message", beta)));
        boolean firstWon = contenders.first().statusCode() == 200;
        var stream = firstWon ? contenders.first() : contenders.second();
        var conflict = firstWon ? contenders.second() : contenders.first();
        assertThat(conflict.statusCode()).isEqualTo(409);
        assertThat(hasContentType(conflict, "application/json")).isTrue();
        var conflictData = json.readTree(conflict.body());
        assertThat(conflictData.path("error").asText()).isEqualTo("Conflicting message for clientRequestId");
        String run = assertSingleCompletedRun(conversation, requestId, firstWon ? alpha : beta, stream);
        assertThat(conflictData.path("runId").asText()).isEqualTo(run);
    }

    @Test
    void completedRunReordersOnlyItsConversationInWorkspacePagination() throws Exception {
        String older = conversation();
        String newer = conversation();
        Instant later = Instant.now().minus(Duration.ofHours(1)).truncatedTo(ChronoUnit.SECONDS);
        Instant earlier = later.minus(Duration.ofHours(1));
        assertThat(jdbc.update("update conversation set updated_at = ? where workspace_id = ? and id = ?",
                Timestamp.from(earlier), workspace, older)).isEqualTo(1);
        assertThat(jdbc.update("update conversation set updated_at = ? where workspace_id = ? and id = ?",
                Timestamp.from(later), workspace, newer)).isEqualTo(1);
        assertThat(List.of(conversationAtOffset(0), conversationAtOffset(1))).containsExactly(newer, older);

        model.streamWith(Flux.just(usageChunk("Order reply")));
        Event terminal = onlyLegacyTerminal(events(post(legacyPath(older), legacyInput("Order message"))));
        assertThat(terminal.name()).isEqualTo("run.completed");
        assertThat(runStatus(terminal.data().path("runId").asText())).isEqualTo("COMPLETED");

        assertThat(List.of(conversationAtOffset(0), conversationAtOffset(1))).containsExactly(older, newer);
        assertThat(conversationUpdatedAt(older)).isAfter(later);
        assertThat(conversationUpdatedAt(newer)).isEqualTo(later);
    }

    @Test
    void messageForeignKeyRequiresTheConversationThatOwnsItsRun() throws Exception {
        String owner = conversation();
        String other = conversation();
        String run = UUID.randomUUID().toString();
        jdbc.update("""
                insert into chat_run(id, conversation_id, client_request_id, input_text, status)
                values (?, ?, ?, 'fixture', 'COMPLETED')
                """, run, owner, UUID.randomUUID().toString());
        String insertMessage = """
                insert into message(id, conversation_id, run_id, role, content)
                values (?, ?, ?, 'USER', ?)
                """;
        assertThatThrownBy(() -> jdbc.update(insertMessage, UUID.randomUUID().toString(), other, run, "mismatched"))
                .isInstanceOfSatisfying(DataIntegrityViolationException.class, failure ->
                        assertThat(failure.getMostSpecificCause()).isInstanceOfSatisfying(SQLException.class,
                                cause -> assertThat(cause.getSQLState()).isEqualTo("23503")));
        assertThat(jdbc.update(insertMessage, UUID.randomUUID().toString(), owner, run, "matched")).isEqualTo(1);
        assertThat(jdbc.queryForList("select conversation_id from message where run_id = ?", String.class, run))
                .containsExactly(owner);
        assertThat(model.invocations()).isZero();
    }

    @Test
    void legacyGraphQlListsDefaultLocalBotAndReadsNewConversationHistory() throws Exception {
        String conversation = conversation();
        var response = post("/graphql", Map.of("query", """
                query($id: ID!) {
                    bots(limit: 10, offset: 0) { id name target }
                    defaultLocalBot { id name target }
                    conversation(id: $id) {
                        id title
                        messages(limit: 5, offset: 0) { id }
                        runs(limit: 5, offset: 0) { id }
                    }
                }
                """, "variables", Map.of("id", conversation)));
        assertThat(response.statusCode()).isEqualTo(200);
        var result = json.readTree(response.body());
        assertThat(result.has("errors")).isFalse();
        var data = result.path("data");
        assertThat(data.path("bots").isArray()).isTrue();
        assertThat(data.path("bots").findValuesAsText("id")).contains("bot-general");
        assertThat(data.path("defaultLocalBot").path("id").asText()).isEqualTo("bot-general");
        assertThat(data.path("defaultLocalBot").path("target").asText()).isEqualTo("LOCAL");
        var created = data.path("conversation");
        assertThat(created.path("id").asText()).isEqualTo(conversation);
        assertThat(created.path("messages").isArray()).isTrue();
        assertThat(created.path("messages").size()).isZero();
        assertThat(created.path("runs").isArray()).isTrue();
        assertThat(created.path("runs").size()).isZero();
    }

    @Test
    void cancellationWinsBeforeLateModelCompletion() throws Exception {
        String conversation = conversation();
        var held = holdModelAfter("Partial delta cancel first");
        try {
            var open = openStream(legacyPath(conversation), legacyInput("Cancel first prompt"), "message.delta");
            String run = open.event().get(10, TimeUnit.SECONDS).data().path("runId").asText();
            assertCancel(run, "CANCELLED", true);
            held.pending().tryEmitEmpty();

            assertThat(onlyLegacyTerminal(open.finished().get(10, TimeUnit.SECONDS)).name()).isEqualTo("run.cancelled");
            assertThat(runStatus(run)).isEqualTo("CANCELLED");
            assertThat(sessions.getActiveMessages(conversation)).isEmpty();
            assertCancel(run, "CANCELLED", true);
            held.disposed().get(10, TimeUnit.SECONDS);
        } finally {
            held.pending().tryEmitEmpty();
        }
    }

    @Test
    void completionWinsBeforeLateCancellation() throws Exception {
        String conversation = conversation();
        var held = holdModelAfter("Full response part 1");
        try {
            var open = openStream(legacyPath(conversation), legacyInput("Complete first prompt"), "message.delta");
            String run = open.event().get(10, TimeUnit.SECONDS).data().path("runId").asText();
            assertThat(held.pending().tryEmitEmpty()).isEqualTo(Sinks.EmitResult.OK);
            assertThat(onlyLegacyTerminal(open.finished().get(10, TimeUnit.SECONDS)).name()).isEqualTo("run.completed");
            assertThat(runStatus(run)).isEqualTo("COMPLETED");

            assertCancel(run, "COMPLETED", false);
            assertThat(sessions.getActiveMessages(conversation)).extracting(message -> message.getText())
                    .containsExactly("Complete first prompt", "Full response part 1");
            assertCancel(run, "COMPLETED", false);
            assertThat(runStatus(run)).isEqualTo("COMPLETED");
            held.disposed().get(10, TimeUnit.SECONDS);
        } finally {
            held.pending().tryEmitEmpty();
        }
    }

    @Test
    void stalledStreamReleasesPostgresConnectionsForConcurrentQueries() throws Exception {
        String conversation = conversation();
        var held = holdModelAfter("Partial reply");
        try {
            var open = openStream(legacyPath(conversation), legacyInput("Hold while querying"), "message.delta");
            open.event().get(10, TimeUnit.SECONDS);
            var pool = dataSource.unwrap(HikariDataSource.class).getHikariPoolMXBean();
            assertThat(pool).isNotNull();
            assertThat(pool.getActiveConnections()).isZero();
            assertThat(idleTransactions()).isZero();

            Callable<String> readConversationBot = () -> jdbc.queryForObject("""
                    select b.id from conversation c join bot b on b.id = c.bot_id
                    where c.workspace_id = ? and c.id = ?
                    """, String.class, workspace, conversation);
            try (var workers = Executors.newFixedThreadPool(10)) {
                var queries = workers.invokeAll(java.util.Collections.nCopies(50, readConversationBot), 10, TimeUnit.SECONDS);
                for (var query : queries) {
                    assertThat(query.get()).isEqualTo("bot-general");
                }
            }
            assertThat(pool.getActiveConnections()).isZero();
            assertThat(idleTransactions()).isZero();
            assertThat(open.finished()).isNotDone();

            assertThat(held.pending().tryEmitValue(usageChunk(" finished"))).isEqualTo(Sinks.EmitResult.OK);
            assertThat(onlyLegacyTerminal(open.finished().get(10, TimeUnit.SECONDS)).name()).isEqualTo("run.completed");
            held.disposed().get(10, TimeUnit.SECONDS);
        } finally {
            held.pending().tryEmitEmpty();
        }
    }

    @Test
    void blockingModelSubscriptionStillAllowsSseStartAndCancellation() throws Exception {
        String conversation = conversation();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var disposed = new CountDownLatch(1);
        var exited = new CountDownLatch(1);
        // A synchronous generator blocks the subscribing thread like an SDK iterator waiting for bytes.
        model.streamWith(Flux.<ChatResponse>generate(sink -> {
            entered.countDown();
            try {
                if (!release.await(30, TimeUnit.SECONDS)) {
                    sink.error(new IllegalStateException("Blocked test model was not released"));
                    return;
                }
                sink.complete();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                sink.complete();
            } finally {
                exited.countDown();
            }
        }).doOnCancel(disposed::countDown));

        try {
            var open = openStream(legacyPath(conversation), legacyInput("Blocking model"), "run.started");
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            var started = open.event().get(10, TimeUnit.SECONDS);
            assertThat(started.data().path("seq").asInt()).isEqualTo(1);
            String run = started.data().path("runId").asText();
            assertThat(run).isNotBlank();
            assertThat(exited.getCount()).isEqualTo(1);

            assertCancel(run, "CANCELLED", true);
            assertThat(disposed.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(onlyLegacyTerminal(open.finished().get(10, TimeUnit.SECONDS)).name()).isEqualTo("run.cancelled");
            assertThat(runStatus(run)).isEqualTo("CANCELLED");
            assertThat(jdbc.queryForList("select interrupted from message where run_id = ? and role = 'ASSISTANT'",
                    Boolean.class, run)).allMatch(Boolean.TRUE::equals);
            assertThat(model.invocations()).isEqualTo(1);
        } finally {
            release.countDown();
            if (entered.getCount() == 0) {
                assertThat(exited.await(10, TimeUnit.SECONDS)).isTrue();
            }
        }
    }

    @Test
    void legacyRunValidatesPayloadAndHidesForeignConversation() throws Exception {
        String conversation = conversation();
        String path = legacyPath(conversation);
        for (var invalid : List.of(
                Map.of("clientRequestId", " ", "message", "valid"),
                Map.of("clientRequestId", "a".repeat(129), "message", "valid"),
                legacyInput(" "), legacyInput("m".repeat(10001)))) {
            assertThat(post(path, invalid).statusCode()).isEqualTo(400);
        }
        var pending = Sinks.<ChatResponse>one();
        model.streamWith(Flux.concat(Flux.just(usageChunk("Owned reply")), pending.asMono()));
        try {
            var open = openStream(path, legacyInput("Authorized"), "message.delta");
            String run = open.event().get(10, TimeUnit.SECONDS).data().path("runId").asText();
            jdbc.update("update conversation set user_id = 'another-owner' where id = ? and workspace_id = ?", conversation, workspace);
            var denied = post(path, legacyInput("Unauthorized"));
            var unknown = post(legacyPath(UUID.randomUUID().toString()), legacyInput("Unknown conversation"));
            assertThat(denied.statusCode()).isEqualTo(404);
            assertThat(unknown.statusCode()).isEqualTo(404);
            assertThat(json.readTree(denied.body())).isEqualTo(json.readTree(unknown.body()));
            assertThat(json.readTree(denied.body()).path("code").asText()).isEqualTo("CONVERSATION_ACCESS_DENIED");
            var cancel = post("/api/runs/" + run + "/cancel", Map.of());
            assertThat(cancel.statusCode()).isEqualTo(404);
            assertThat(json.readTree(cancel.body()).path("code").asText()).isEqualTo("RUN_ACCESS_DENIED");
            assertThat(runStatus(run)).isEqualTo("RUNNING");
            jdbc.update("update conversation set user_id = 'dev-user' where id = ? and workspace_id = ?", conversation, workspace);
            assertCancel(run, "CANCELLED", true);
            assertThat(open.finished().get(10, TimeUnit.SECONDS).getLast().name()).isEqualTo("run.cancelled");
            assertThat(runStatus(run)).isEqualTo("CANCELLED");
            assertThat(model.invocations()).isEqualTo(1);
        } finally {
            pending.tryEmitEmpty();
        }
    }

    @Test
    void agUiStreamsStandardEventsAndDoesNotDispatchDuplicate() throws Exception {
        String conversation = conversation();
        String run = UUID.randomUUID().toString();
        model.streamWith(Flux.just(chunk("  leading"), usageChunk(" text")));
        var input = agUiInput(conversation, run, "hello");
        var events = events(post("/api/ag-ui", input));
        assertThat(events).extracting(Event::name).containsExactly("RUN_STARTED", "TEXT_MESSAGE_START",
                "TEXT_MESSAGE_CONTENT", "TEXT_MESSAGE_CONTENT", "TEXT_MESSAGE_END", "RUN_FINISHED");
        assertThat(events.getFirst().data().path("runId").asText()).isEqualTo(run);
        assertThat(events.getFirst().data().path("threadId").asText()).isEqualTo(conversation);
        assertThat(events.get(2).data().path("delta").asText()).isEqualTo("  leading");
        assertThat(jdbc.queryForObject("select id from message where run_id = ? and role = 'ASSISTANT'", String.class, run))
                .isEqualTo(events.get(1).data().path("messageId").asText());
        assertThat(runStatus(run)).isEqualTo("COMPLETED");

        var duplicate = post("/api/ag-ui", input);
        assertThat(duplicate.statusCode()).isEqualTo(409);
        assertThat(json.readTree(duplicate.body()).path("code").asText()).isEqualTo("DUPLICATE_RUN");
        assertThat(model.invocations()).isEqualTo(1);
        assertThat(jdbc.queryForList("select id from chat_run where conversation_id = ?", String.class, conversation))
                .containsExactly(run);
        assertThat(jdbc.queryForObject("select count(*) from ai_session where id = ?", Integer.class, conversation))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("select execution_outcome from provider_attempt where run_id = ?", String.class, run))
                .isEqualTo("SUCCEEDED");
        awaitTerminalWork();
        assertThat(jdbc.queryForObject("select status from workspace_admission_registration where run_id = ?", String.class, run))
                .isEqualTo("TERMINAL");
    }

    @Test
    void agUiRejectsIdCollisionAndChangedInputWithoutDisclosingExistingRun() throws Exception {
        String first = conversation();
        String second = conversation();
        String run = UUID.randomUUID().toString();
        model.streamWith(Flux.just(usageChunk("Completed reply")));
        assertThat(events(post("/api/ag-ui", agUiInput(first, run, "first"))).getLast().name()).isEqualTo("RUN_FINISHED");
        assertAgUiConflict(post("/api/ag-ui", agUiInput(first, run, "changed")), "RUN_CONFLICT");

        jdbc.update("update conversation set user_id = 'another-owner' where id = ? and workspace_id = ?", first, workspace);
        var response = post("/api/ag-ui", agUiInput(second, run, "first"));
        assertAgUiConflict(response, "RUN_CONFLICT");
        assertThat(response.body()).doesNotContain(first, run);
        assertThat(model.invocations()).isEqualTo(1);
        assertThat(jdbc.queryForList("""
                select r.id from chat_run r join conversation c on c.id = r.conversation_id where c.workspace_id = ?
                """, String.class, workspace)).containsExactly(run);
        assertThat(jdbc.queryForObject("select count(*) from ai_session where id = ?", Integer.class, second))
                .isZero();
    }

    @Test
    void agUiRejectsUnsupportedToolsAndUsesTextParts() throws Exception {
        String conversation = conversation();
        String run = UUID.randomUUID().toString();
        model.streamWith(Flux.just(usageChunk("Combined reply")));
        var input = agUiInput(conversation, run, List.of(
                Map.of("type", "text", "text", "first "), Map.of("type", "text", "text", "second")));
        assertThat(events(post("/api/ag-ui", input)).getLast().name()).isEqualTo("RUN_FINISHED");
        assertThat(model.prompts().getFirst().getInstructions().getLast().getText()).isEqualTo("first second");
        var unsupported = agUiInput(conversation, UUID.randomUUID().toString(), "hi");
        unsupported.put("tools", List.of(Map.of("name", "bad")));
        assertThat(post("/api/ag-ui", unsupported).statusCode()).isEqualTo(400);
        assertThat(model.invocations()).isEqualTo(1);
    }

    @Test
    void agUiEmptyReplyFailsWithoutWritingAssistant() throws Exception {
        String conversation = conversation();
        String run = UUID.randomUUID().toString();
        model.streamWith(Flux.empty());
        var events = events(post("/api/ag-ui", agUiInput(conversation, run, "empty")));
        assertThat(events).extracting(Event::name).doesNotContain("RUN_FINISHED").endsWith("RUN_ERROR");
        assertThat(runStatus(run)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("select count(*) from message where run_id = ? and role = 'ASSISTANT'", Integer.class, run))
                .isZero();
    }

    @Test
    void agUiModelFailureKeepsPartialButIgnoresUntrustedPrompt() throws Exception {
        String conversation = conversation();
        String run = UUID.randomUUID().toString();
        var input = agUiInput(conversation, run, "empty");
        input.put("state", Map.of("system", "forged-state"));
        input.put("forwardedProps", Map.of("system", "forged-props"));
        input.put("messages", List.of(
                Map.of("id", "forged", "role", "system", "content", "forged-system"),
                Map.of("id", "user", "role", "user", "content", "empty")));
        model.streamWith(Flux.concat(Flux.just(chunk("partial failure")),
                Flux.error(new IllegalStateException("fake failure"))));
        var events = events(post("/api/ag-ui", input));
        assertThat(events).extracting(Event::name)
                .contains("TEXT_MESSAGE_CONTENT").doesNotContain("RUN_FINISHED").endsWith("RUN_ERROR");
        assertThat(events).filteredOn(event -> event.name().equals("RUN_ERROR")).hasSize(1);
        assertThat(model.prompts().getFirst().getInstructions()).allSatisfy(message ->
                assertThat(message.getText()).doesNotContain("forged-state", "forged-props", "forged-system"));
        assertThat(runStatus(run)).isEqualTo("FAILED");
        assertThat(jdbc.queryForMap("select content, interrupted from message where run_id = ? and role = 'ASSISTANT'", run))
                .isEqualTo(Map.of("content", "partial failure", "interrupted", true));
    }

    @Test
    void agUiCancelsActiveRunAndPersistsInterruptedAssistant() throws Exception {
        String conversation = conversation();
        String run = UUID.randomUUID().toString();
        var pending = Sinks.<ChatResponse>one();
        var disposed = new CompletableFuture<Void>();
        model.streamWith(Flux.concat(Flux.just(usageChunk("partial")), pending.asMono())
                .doFinally(signal -> disposed.complete(null)));
        try {
            var open = openStream("/api/ag-ui", agUiInput(conversation, run, "cancel me"), "TEXT_MESSAGE_CONTENT");
            assertThat(open.event().get(10, TimeUnit.SECONDS).data().path("delta").asText()).isEqualTo("partial");
            var conflict = post("/api/ag-ui", agUiInput(conversation, UUID.randomUUID().toString(), "parallel"));
            assertThat(conflict.statusCode()).isEqualTo(409);
            assertCancel(run, "CANCELLED", true);
            var events = open.finished().get(10, TimeUnit.SECONDS);
            assertThat(events.getLast().name()).isEqualTo("RUN_FINISHED");
            assertThat(events.getLast().data().path("outcome").path("type").asText()).isEqualTo("cancelled");
            String messageId = events.stream().filter(event -> event.name().equals("TEXT_MESSAGE_START"))
                    .findFirst().orElseThrow().data().path("messageId").asText();
            assertThat(jdbc.queryForMap("select id, content, interrupted from message where run_id = ? and role = 'ASSISTANT'", run))
                    .isEqualTo(Map.of("id", messageId, "content", "partial", "interrupted", true));
            assertCancel(run, "CANCELLED", true);
            assertThat(runStatus(run)).isEqualTo("CANCELLED");
            assertThat(sessions.getActiveMessages(conversation)).isEmpty();
            assertThat(model.invocations()).isEqualTo(1);
            disposed.get(10, TimeUnit.SECONDS);
        } finally {
            pending.tryEmitEmpty();
        }
    }

    private String conversation() throws Exception {
        var response = post("/graphql", Map.of(
                "query", "mutation($workspace: ID!) { createConversation(botId: \"bot-general\", title: \"Run fixture\", workspaceId: $workspace) { id } }",
                "variables", Map.of("workspace", workspace)));
        assertThat(response.statusCode()).isEqualTo(200);
        var data = json.readTree(response.body());
        assertThat(data.has("errors")).isFalse();
        String id = data.path("data").path("createConversation").path("id").asText();
        assertThat(id).isNotBlank();
        return id;
    }

    private String runStatus(String run) {
        return jdbc.queryForObject("select status from chat_run where id = ?", String.class, run);
    }

    private String conversationAtOffset(int offset) throws Exception {
        var response = post("/graphql", Map.of("query", """
                query($workspace: ID!, $offset: Int!) {
                    conversations(workspaceId: $workspace, limit: 1, offset: $offset) { id }
                }
                """, "variables", Map.of("workspace", workspace, "offset", offset)));
        assertThat(response.statusCode()).isEqualTo(200);
        var result = json.readTree(response.body());
        assertThat(result.has("errors")).isFalse();
        var conversations = result.path("data").path("conversations");
        assertThat(conversations.isArray()).isTrue();
        assertThat(conversations.size()).isEqualTo(1);
        return conversations.get(0).path("id").asText();
    }

    private Instant conversationUpdatedAt(String conversation) {
        return jdbc.queryForObject("select updated_at from conversation where workspace_id = ? and id = ?",
                Timestamp.class, workspace, conversation).toInstant();
    }

    private void awaitTerminalWork() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (unfinishedWork() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(unfinishedWork()).as("owned Runs and admission registrations must be terminal before cleanup").isFalse();
    }

    private boolean unfinishedWork() {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from chat_run r join conversation c on c.id = r.conversation_id
                    where c.workspace_id = ? and r.status in ('PENDING', 'RUNNING'))
                    or exists(select 1 from workspace_admission_registration where workspace_id = ? and status <> 'TERMINAL')
                """, Boolean.class, workspace, workspace));
    }

    private HttpResponse<String> post(String path, Map<String, ?> input) throws Exception {
        return http.send(request(path, input), HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest request(String path, Map<String, ?> input) throws Exception {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                .header("Accept", "*/*").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(input))).build();
    }

    private void assertEventStream(HttpResponse<?> response) {
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(type ->
                assertThat(type).startsWith("text/event-stream"));
    }

    private List<Event> events(HttpResponse<String> response) throws JsonProcessingException {
        assertEventStream(response);
        return readEvents(response.body().lines(), event -> {});
    }

    private static boolean hasContentType(HttpResponse<?> response, String type) {
        return response.headers().firstValue("Content-Type").orElse("").startsWith(type);
    }

    private OpenStream openStream(String path, Map<String, ?> input, String eventName) throws Exception {
        var selectedEvent = new CompletableFuture<Event>();
        var finished = http.sendAsync(request(path, input), HttpResponse.BodyHandlers.ofLines()).thenApplyAsync(response -> {
            try (var lines = response.body()) {
                assertEventStream(response);
                return readEvents(lines, event -> {
                    if (event.name().equals(eventName)) {
                        selectedEvent.complete(event);
                    }
                });
            } catch (JsonProcessingException failure) {
                throw new CompletionException(failure);
            }
        });
        finished.whenComplete((ignored, failure) -> selectedEvent.completeExceptionally(failure == null
                ? new AssertionError("event stream ended before " + eventName) : failure));
        return new OpenStream(selectedEvent, finished);
    }

    private List<Event> readEvents(Stream<String> lines, Consumer<Event> onEvent) throws JsonProcessingException {
        var result = new ArrayList<Event>();
        String name = "";
        var iterator = lines.iterator();
        while (iterator.hasNext()) {
            String line = iterator.next();
            if (line.startsWith("event:")) {
                name = line.substring(6).strip();
            } else if (line.startsWith("data:")) {
                JsonNode data = json.readTree(line.substring(5));
                var event = new Event(name.isEmpty() ? data.path("type").asText() : name, data);
                result.add(event);
                onEvent.accept(event);
                name = "";
            }
        }
        return result;
    }

    private static Event onlyLegacyTerminal(List<Event> events) {
        var terminals = events.stream().filter(event ->
                List.of("run.completed", "run.cancelled", "run.failed").contains(event.name())).toList();
        assertThat(terminals).hasSize(1);
        assertThat(events.getLast()).isEqualTo(terminals.getFirst());
        return terminals.getFirst();
    }

    private HeldModel holdModelAfter(String text) {
        var pending = Sinks.<ChatResponse>one();
        var disposed = new CompletableFuture<Void>();
        model.streamWith(Flux.concat(Flux.just(usageChunk(text)), pending.asMono())
                .doFinally(signal -> disposed.complete(null)));
        return new HeldModel(pending, disposed);
    }

    private int idleTransactions() {
        return jdbc.queryForObject("""
                select count(*) from pg_stat_activity
                where datname = current_database() and state in ('idle in transaction', 'idle in transaction (aborted)')
                """, Integer.class);
    }

    private String assertSingleCompletedRun(String conversation, String requestId, String prompt,
            HttpResponse<String> response) throws JsonProcessingException {
        Event terminal = onlyLegacyTerminal(events(response));
        assertThat(terminal.name()).isEqualTo("run.completed");
        String run = terminal.data().path("runId").asText();
        assertThat(run).isNotBlank();
        assertThat(jdbc.queryForList("select id, client_request_id, status from chat_run where conversation_id = ?", conversation))
                .containsExactly(Map.of("id", run, "client_request_id", requestId, "status", "COMPLETED"));
        assertThat(jdbc.queryForList("select role, content from message where run_id = ? order by role", run))
                .containsExactly(Map.of("role", "ASSISTANT", "content", "Single reply"),
                        Map.of("role", "USER", "content", prompt));
        assertThat(model.invocations()).isEqualTo(1);
        return run;
    }

    private static <A, B> RaceResult<A, B> race(Callable<A> first, Callable<B> second) throws Exception {
        var startingLine = new CyclicBarrier(2);
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var firstResult = workers.submit(() -> {
                startingLine.await(5, TimeUnit.SECONDS);
                return first.call();
            });
            var secondResult = workers.submit(() -> {
                startingLine.await(5, TimeUnit.SECONDS);
                return second.call();
            });
            try {
                return new RaceResult<>(firstResult.get(15, TimeUnit.SECONDS), secondResult.get(15, TimeUnit.SECONDS));
            } finally {
                firstResult.cancel(true);
                secondResult.cancel(true);
            }
        }
    }

    private static String legacyPath(String conversation) {
        return "/api/conversations/" + conversation + "/runs";
    }

    private static Map<String, String> legacyInput(String text) {
        return Map.of("clientRequestId", UUID.randomUUID().toString(), "message", text);
    }

    private static Map<String, Object> agUiInput(String conversation, String run, Object content) {
        return new LinkedHashMap<>(Map.of("threadId", conversation, "runId", run, "state", Map.of(),
                "messages", List.of(Map.of("id", UUID.randomUUID().toString(), "role", "user", "content", content)),
                "tools", List.of(), "context", List.of(), "forwardedProps", Map.of()));
    }

    private void assertAgUiConflict(HttpResponse<String> response, String code) throws Exception {
        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(json.readTree(response.body()).path("code").asText()).isEqualTo(code);
    }

    private void assertCancel(String run, String status, boolean cancelled) throws Exception {
        var response = post("/api/runs/" + run + "/cancel", Map.of());
        assertThat(response.statusCode()).isEqualTo(200);
        var data = json.readTree(response.body());
        assertThat(data.path("runId").asText()).isEqualTo(run);
        assertThat(data.path("status").asText()).isEqualTo(status);
        assertThat(data.path("cancelled").asBoolean()).isEqualTo(cancelled);
    }

    private static ChatResponse chunk(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    private static ChatResponse usageChunk(String text) {
        var usage = GenerateContentResponseUsageMetadata.builder()
                .promptTokenCount(3).candidatesTokenCount(2).totalTokenCount(5).build();
        var metadata = ChatResponseMetadata.builder().usage(new DefaultUsage(3, 2, 5, usage)).build();
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))), metadata);
    }

    private record Event(String name, JsonNode data) {}
    private record OpenStream(CompletableFuture<Event> event, CompletableFuture<List<Event>> finished) {}
    private record HeldModel(Sinks.One<ChatResponse> pending, CompletableFuture<Void> disposed) {}
    private record RaceResult<A, B>(A first, B second) {}
}
