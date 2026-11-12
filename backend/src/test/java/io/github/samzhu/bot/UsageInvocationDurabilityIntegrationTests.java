package io.github.samzhu.bot;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.samzhu.bot.usage.UsageLedger;
import io.github.samzhu.bot.conversation.ConversationOperations;
import io.github.samzhu.bot.execution.internal.DispatchCommitObserver;
import io.github.samzhu.bot.execution.internal.capture.UsageCaptureAdapter;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.session.SessionService;
import org.springframework.ai.session.CreateSessionRequest;
import io.github.samzhu.bot.bootstrap.DatabaseInitializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import reactor.core.publisher.Flux;
import reactor.core.Disposable;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class UsageInvocationDurabilityIntegrationTests {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired DatabaseInitializer databaseInitializer;
    @MockitoSpyBean UsageLedger ledger;
    @MockitoSpyBean ConversationOperations conversations;
    @MockitoSpyBean SessionService sessions;
    @MockitoSpyBean UsageCaptureAdapter captureAdapter;
    @MockitoSpyBean DispatchCommitObserver dispatchObserver;
    @TestBean(name = "usageClock", methodName = "testClock", enforceOverride = true) Clock usageClock;
    @TestBean(name = "providerDispatchScheduler", methodName = "dispatchScheduler", enforceOverride = true) Scheduler dispatchScheduler;
    @TestBean(name = "mockChatModel", methodName = "countingModel", enforceOverride = true) ChatModel model;

    private static final CountingModel FAKE = new CountingModel();
    private static final MutableClock CLOCK = new MutableClock();

    static Clock testClock() { return CLOCK; }

    static final class MutableClock extends Clock {
        private final AtomicReference<Instant> current = new AtomicReference<>(Instant.parse("2026-01-31T15:59:59Z"));
        void set(Instant instant) { current.set(instant); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(instant(), zone); }
        @Override public Instant instant() { return current.get(); }
    }

    @BeforeEach void resetClock() { CLOCK.set(Instant.parse("2026-01-31T15:59:59Z")); }
    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    static ChatModel countingModel() { return FAKE; }

    static Scheduler dispatchScheduler() { return new QueueScheduler(); }

    static final class QueueScheduler implements Scheduler {
        final Scheduler delegate = Schedulers.newSingle("boundedElastic-bot33-test");
        volatile CountDownLatch queued;
        @Override public Disposable schedule(Runnable task) {
            Disposable scheduled = delegate.schedule(task);
            CountDownLatch signal = queued;
            if (signal != null) signal.countDown();
            return scheduled;
        }
        @Override public Worker createWorker() {
            Worker worker = delegate.createWorker();
            return new Worker() {
                @Override public Disposable schedule(Runnable task) {
                    Disposable scheduled = worker.schedule(task);
                    CountDownLatch signal = queued;
                    if (signal != null) signal.countDown();
                    return scheduled;
                }
                @Override public void dispose() { worker.dispose(); }
                @Override public boolean isDisposed() { return worker.isDisposed(); }
            };
        }
        @Override public void dispose() { delegate.dispose(); }
        @Override public boolean isDisposed() { return delegate.isDisposed(); }
    }

    static final class CountingModel implements ChatModel {
        final AtomicInteger calls = new AtomicInteger();
        final AtomicInteger streams = new AtomicInteger();
        final AtomicInteger subscriptions = new AtomicInteger();
        final AtomicInteger attemptsVisibleAtDispatch = new AtomicInteger(-1);
        JdbcTemplate jdbc;
        String conversation;
        volatile boolean emitNativeUsage;
        volatile CountDownLatch streamEntered;
        volatile CountDownLatch streamRelease;

        @Override public ChatResponse call(Prompt prompt) {
            calls.incrementAndGet();
            return response();
        }

        @Override public Flux<ChatResponse> stream(Prompt prompt) {
            streams.incrementAndGet();
            CountDownLatch entered = streamEntered;
            CountDownLatch release = streamRelease;
            if (entered != null && release != null) {
                entered.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Timed out waiting for dispatch release");
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Provider interrupted", ex);
                }
            }
            attemptsVisibleAtDispatch.set(jdbc.queryForObject(
                    "SELECT count(*) FROM provider_attempt a JOIN chat_run r ON r.id=a.run_id WHERE r.conversation_id=?",
                    Integer.class, conversation));
            return Flux.defer(() -> {
                subscriptions.incrementAndGet();
                return Flux.just(response());
            });
        }

        private ChatResponse response() {
            var generation = new Generation(new AssistantMessage("Synthetic response"));
            if (!emitNativeUsage) return new ChatResponse(List.of(generation));
            var usage = new DefaultUsage(0, 0, 0, "{\"input\":1,\"privateFixture\":\"issue33-raw-secret-sentinel\"}");
            return new ChatResponse(List.of(generation), ChatResponseMetadata.builder().id("issue33-response").usage(usage).build());
        }
    }

    private HttpResponse<String> post(String path, Object body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(15)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    @Test
    void failedAttemptWritePreventsDispatchAndSessionThenRecoveryDispatches() throws Exception {
        assertTrue(count("SHOW server_version_num") >= 180000 && count("SHOW server_version_num") < 190000);
        FAKE.calls.set(0);
        FAKE.streams.set(0);
        FAKE.subscriptions.set(0);
        jdbc.update("INSERT INTO bot(id,name,system_prompt,created_at) VALUES ('bot-general','General','test',CURRENT_TIMESTAMP) ON CONFLICT (id) DO NOTHING");
        var create = post("/graphql", Map.of("query", "mutation { createConversation(botId: \"bot-general\", title: \"durability\") { id } }"));
        assertEquals(200, create.statusCode());
        var createJson = json.readTree(create.body());
        assertFalse(createJson.has("errors"), create.body());
        String conversation = createJson.path("data").path("createConversation").path("id").asText();
        assertFalse(conversation.isBlank());
        FAKE.jdbc = jdbc;
        FAKE.conversation = conversation;
        FAKE.attemptsVisibleAtDispatch.set(-1);
        assertEquals(0, count("SELECT count(*) FROM ai_session WHERE id=?", conversation));
        assertEquals(0, count("SELECT count(*) FROM ai_session_event WHERE session_id=?", conversation));

        // Fail at the public ledger boundary before the actual provider can be invoked.
        doThrow(new IllegalStateException("synthetic attempt persistence failure")).when(ledger).begin(any());
        try {
            var failed = post("/api/conversations/" + conversation + "/runs", Map.of(
                    "clientRequestId", "durability-fail-" + UUID.randomUUID(), "message", "test input"));
            assertTrue(failed.statusCode() >= 500 || (failed.statusCode() == 200
                    && failed.body().contains("run.failed") && !failed.body().contains("run.completed")),
                    "A failed ledger write must be observable as an error: HTTP " + failed.statusCode() + " " + failed.body());
            verify(ledger, times(1)).begin(any());
            assertEquals(0, FAKE.calls.get());
            assertEquals(0, FAKE.streams.get());
            assertEquals(0, FAKE.subscriptions.get());
            assertEquals(0, count("SELECT count(*) FROM ai_session WHERE id=?", conversation));
            assertEquals(0, count("SELECT count(*) FROM ai_session_event WHERE session_id=?", conversation));
            assertEquals(0, count("SELECT count(*) FROM provider_attempt a JOIN chat_run r ON r.id=a.run_id WHERE r.conversation_id=?", conversation));
            assertEquals(0, count("SELECT count(*) FROM chat_run WHERE conversation_id=? AND status IN ('RUNNING','COMPLETED')", conversation));
        } finally {
            reset(ledger);
        }

        var recovered = post("/api/conversations/" + conversation + "/runs", Map.of(
                "clientRequestId", "durability-recover-" + UUID.randomUUID(), "message", "recovery input"));
        assertEquals(200, recovered.statusCode(), recovered.body());
        assertTrue(recovered.body().contains("run.completed"), recovered.body());
        assertEquals(0, FAKE.calls.get());
        assertEquals(1, FAKE.streams.get());
        assertEquals(1, FAKE.subscriptions.get());
        assertEquals(1, FAKE.attemptsVisibleAtDispatch.get(), "Attempt must be visible before provider dispatch");
        assertEquals(1, count("SELECT count(*) FROM provider_attempt a JOIN chat_run r ON r.id=a.run_id WHERE r.conversation_id=?", conversation));
        assertEquals(1, count("SELECT count(*) FROM ai_session WHERE id=?", conversation));
    }

    @Test
    void failedAttemptFinishCannotSucceedAndRestartMarksAttemptUnknown() throws Exception {
        FAKE.calls.set(0);
        FAKE.streams.set(0);
        FAKE.subscriptions.set(0);
        jdbc.update("INSERT INTO bot(id,name,system_prompt,created_at) VALUES ('bot-general','General','test',CURRENT_TIMESTAMP) ON CONFLICT (id) DO NOTHING");
        var create = post("/graphql", Map.of("query", "mutation { createConversation(botId: \"bot-general\", title: \"finish durability\") { id } }"));
        assertEquals(200, create.statusCode());
        var createJson = json.readTree(create.body());
        assertFalse(createJson.has("errors"), create.body());
        String conversation = createJson.path("data").path("createConversation").path("id").asText();
        assertFalse(conversation.isBlank());
        FAKE.jdbc = jdbc;
        FAKE.conversation = conversation;
        FAKE.attemptsVisibleAtDispatch.set(-1);

        // Fail the terminal ledger write after the durable attempt and model dispatch exist.
        doThrow(new IllegalStateException("synthetic finish persistence failure"))
                .when(ledger).finish(anyString(), eq("SUCCEEDED"));
        try {
            var response = post("/api/conversations/" + conversation + "/runs", Map.of(
                    "clientRequestId", "finish-fail-" + UUID.randomUUID(), "message", "test input"));
            assertTrue(response.statusCode() >= 500 || (response.statusCode() == 200
                    && response.body().contains("run.failed") && !response.body().contains("run.completed")),
                    "A failed finish write must not report a successful run: HTTP " + response.statusCode() + " " + response.body());
            verify(ledger, times(1)).finish(anyString(), eq("SUCCEEDED"));
            assertEquals(0, FAKE.calls.get());
            assertEquals(1, FAKE.streams.get());
            assertEquals(1, FAKE.subscriptions.get());
            assertEquals(1, FAKE.attemptsVisibleAtDispatch.get());
            assertEquals(0, count("SELECT count(*) FROM chat_run WHERE conversation_id=? AND status='COMPLETED'", conversation));
            assertEquals(1, count("SELECT count(*) FROM provider_attempt a JOIN chat_run r ON r.id=a.run_id WHERE r.conversation_id=? AND a.execution_outcome='IN_PROGRESS'", conversation));
        } finally {
            reset(ledger);
        }

        String attemptId = jdbc.queryForObject("SELECT a.id FROM provider_attempt a JOIN chat_run r ON r.id=a.run_id WHERE r.conversation_id=?", String.class, conversation);
        long versionBefore = jdbc.queryForObject("SELECT projection_version FROM provider_attempt WHERE id=?", Long.class, attemptId);
        // This initializer uses only the isolated Testcontainers datasource for this test context.
        databaseInitializer.run(null);
        long versionAfter = jdbc.queryForObject("SELECT projection_version FROM provider_attempt WHERE id=?", Long.class, attemptId);
        assertEquals(versionBefore + 1, versionAfter, "Restart recovery must invalidate the old projection version");
        assertEquals(0, count("SELECT count(*) FROM provider_attempt a JOIN chat_run r ON r.id=a.run_id WHERE r.conversation_id=? AND a.execution_outcome='IN_PROGRESS'", conversation));
        assertEquals(1, count("SELECT count(*) FROM provider_attempt a JOIN chat_run r ON r.id=a.run_id WHERE r.conversation_id=? AND a.execution_outcome='UNKNOWN' AND a.usage_presence='UNKNOWN' AND a.projection_state='UNKNOWN' AND a.projection_error='PROCESS_RESTART'", conversation));
    }


    @Test
    void membershipRevokedAfterDurableBeginPreventsProviderDispatch() throws Exception {
        FAKE.calls.set(0);
        FAKE.streams.set(0);
        FAKE.subscriptions.set(0);
        jdbc.update("INSERT INTO bot(id,name,system_prompt,created_at) VALUES ('bot-general','General','test',CURRENT_TIMESTAMP) ON CONFLICT (id) DO NOTHING");
        var create = post("/graphql", Map.of("query", "mutation { createConversation(botId: \"bot-general\", title: \"revocation before dispatch\") { id workspaceId } }"));
        assertEquals(200, create.statusCode());
        var result = json.readTree(create.body());
        assertFalse(result.has("errors"), create.body());
        String conversation = result.path("data").path("createConversation").path("id").asText();
        String workspace = result.path("data").path("createConversation").path("workspaceId").asText();
        assertFalse(conversation.isBlank());
        assertFalse(workspace.isBlank());
        FAKE.jdbc = jdbc;
        FAKE.conversation = conversation;
        FAKE.attemptsVisibleAtDispatch.set(-1);
        CountDownLatch persisted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        String requestId = "revocation-" + UUID.randomUUID();

        // The real insert runs first; another database connection can then revoke membership.
        doAnswer(invocation -> {
            Object attempt = invocation.callRealMethod();
            persisted.countDown();
            if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Timed out waiting for revocation");
            return attempt;
        }).when(ledger).begin(any());

        CompletableFuture<HttpResponse<String>> pending = CompletableFuture.supplyAsync(() -> {
            try {
                return post("/api/conversations/" + conversation + "/runs", Map.of(
                        "clientRequestId", requestId, "message", "test input"));
            } catch (Exception ex) {
                throw new java.util.concurrent.CompletionException(ex);
            }
        });
        try {
            assertTrue(persisted.await(10, TimeUnit.SECONDS), "attempt must be persisted before revocation");
            assertEquals(1, count("SELECT count(*) FROM provider_attempt a JOIN chat_run r ON r.id=a.run_id WHERE r.conversation_id=? AND r.client_request_id=?", conversation, requestId));
            assertEquals(1, jdbc.update("UPDATE workspace_member SET active=false WHERE workspace_id=? AND user_id='dev-user' AND active=true", workspace));
            release.countDown();
            var response = pending.get(15, TimeUnit.SECONDS);
            assertTrue(response.statusCode() >= 400 || (response.statusCode() == 200
                    && response.body().contains("run.failed") && !response.body().contains("run.completed")),
                    "Revocation before dispatch must be observable as failure: HTTP " + response.statusCode() + " " + response.body());
            assertEquals(0, FAKE.calls.get());
            assertEquals(0, FAKE.streams.get());
            assertEquals(0, FAKE.subscriptions.get());
            assertEquals(0, count("SELECT count(*) FROM chat_run WHERE conversation_id=? AND client_request_id=? AND status IN ('RUNNING','COMPLETED')", conversation, requestId));
            String attemptId = jdbc.queryForObject("SELECT a.id FROM provider_attempt a JOIN chat_run r ON r.id=a.run_id WHERE r.conversation_id=? AND r.client_request_id=?", String.class, conversation, requestId);
            var stored = jdbc.queryForMap("SELECT execution_outcome, execution_reason FROM provider_attempt WHERE id=?", attemptId);
            assertEquals("NOT_DISPATCHED", stored.get("execution_outcome"));
            assertEquals("WORKSPACE_ACCESS_DENIED", stored.get("execution_reason"));
            var pricingInput = ledger.readPricingInput(attemptId);
            assertNotNull(pricingInput);
            assertEquals(attemptId, pricingInput.attemptId());
            assertEquals("NOT_DISPATCHED", pricingInput.executionOutcome());
            assertEquals("WORKSPACE_ACCESS_DENIED", pricingInput.executionReason());
        } finally {
            release.countDown();
            jdbc.update("UPDATE workspace_member SET active=true WHERE workspace_id=? AND user_id='dev-user'", workspace);
            reset(ledger);
        }
    }


    private String createRaceConversation() throws Exception {
        FAKE.calls.set(0); FAKE.streams.set(0); FAKE.subscriptions.set(0);
        FAKE.streamEntered = null; FAKE.streamRelease = null; FAKE.emitNativeUsage = false;
        jdbc.update("INSERT INTO bot(id,name,system_prompt,created_at) VALUES ('bot-general','General','test',CURRENT_TIMESTAMP) ON CONFLICT (id) DO NOTHING");
        var response = post("/graphql", Map.of("query", "mutation { createConversation(botId: \"bot-general\", title: \"dispatch race\") { id } }"));
        assertEquals(200, response.statusCode());
        var parsed = json.readTree(response.body());
        assertFalse(parsed.has("errors"), response.body());
        String id = parsed.path("data").path("createConversation").path("id").asText();
        assertFalse(id.isBlank());
        FAKE.jdbc = jdbc; FAKE.conversation = id; FAKE.attemptsVisibleAtDispatch.set(-1);
        return id;
    }

    private CompletableFuture<HttpResponse<String>> startAsync(String conversation, String requestId) {
        return CompletableFuture.supplyAsync(() -> {
            try { return post("/api/conversations/" + conversation + "/runs", Map.of("clientRequestId", requestId, "message", "race input")); }
            catch (Exception ex) { throw new java.util.concurrent.CompletionException(ex); }
        });
    }

    private String runId(String conversation, String requestId) {
        return jdbc.queryForObject("SELECT id FROM chat_run WHERE conversation_id=? AND client_request_id=?", String.class, conversation, requestId);
    }

    private void cancelRun(String id) throws Exception {
        var response = post("/api/runs/" + id + "/cancel", Map.of());
        assertEquals(200, response.statusCode(), response.body());
        var payload = json.readTree(response.body());
        assertEquals("CANCELLED", payload.path("status").asText(), response.body());
        assertTrue(payload.path("cancelled").asBoolean(), response.body());
    }

    private CountDownLatch watchNotDispatched() {
        CountDownLatch recorded = new CountDownLatch(1);
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            recorded.countDown();
            return result;
        }).when(ledger).notDispatched(anyString(), anyString());
        return recorded;
    }

    private void assertCancelledWithoutDispatch(String id, CountDownLatch recorded) throws InterruptedException {
        assertTrue(recorded.await(5, TimeUnit.SECONDS), "NOT_DISPATCHED ledger write did not finish");
        assertEquals(0, FAKE.calls.get()); assertEquals(0, FAKE.streams.get()); assertEquals(0, FAKE.subscriptions.get());
        assertEquals("CANCELLED", jdbc.queryForObject("SELECT status FROM chat_run WHERE id=?", String.class, id));
        assertEquals("NOT_DISPATCHED", jdbc.queryForObject("SELECT execution_outcome FROM provider_attempt WHERE run_id=?", String.class, id));
    }

    @Test
    void cancelAfterBeginBeforeSubscriptionPreventsDispatch() throws Exception {
        String conversation = createRaceConversation();
        String requestId = "cancel-before-subscribe-" + UUID.randomUUID();
        CountDownLatch persisted = new CountDownLatch(1), release = new CountDownLatch(1);
        CountDownLatch recorded = watchNotDispatched();
        doAnswer(invocation -> {
            Object attempt = invocation.callRealMethod();
            persisted.countDown();
            if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("begin latch timed out");
            return attempt;
        }).when(ledger).begin(any());
        var pending = startAsync(conversation, requestId);
        try {
            assertTrue(persisted.await(10, TimeUnit.SECONDS));
            String id = runId(conversation, requestId);
            assertEquals(1, count("SELECT count(*) FROM provider_attempt WHERE run_id=?", id));
            cancelRun(id);
            release.countDown();
            pending.get(15, TimeUnit.SECONDS);
            assertCancelledWithoutDispatch(id, recorded);
        } finally {
            release.countDown();
            reset(ledger);
        }
    }

    @Test
    void cancelDuringWorkerAuthorizationPreventsDispatch() throws Exception {
        String conversation = createRaceConversation();
        String requestId = "cancel-in-auth-" + UUID.randomUUID();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        CountDownLatch recorded = watchNotDispatched();
        doAnswer(invocation -> {
            if (Thread.currentThread().getName().contains("boundedElastic") && conversation.equals(invocation.getArgument(0))) {
                entered.countDown();
                if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("authorization latch timed out");
            }
            return invocation.callRealMethod();
        }).when(conversations).findConversationById(anyString(), anyString());
        var pending = startAsync(conversation, requestId);
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            String id = runId(conversation, requestId);
            assertEquals(1, count("SELECT count(*) FROM provider_attempt WHERE run_id=?", id));
            cancelRun(id);
            release.countDown();
            pending.get(15, TimeUnit.SECONDS);
            assertCancelledWithoutDispatch(id, recorded);
        } finally {
            release.countDown();
            reset(conversations);
            reset(ledger);
        }
    }

    @Test
    void providerDispatchBeforeCancellationIsNotReportedAsNotDispatched() throws Exception {
        String conversation = createRaceConversation();
        String requestId = "dispatch-before-cancel-" + UUID.randomUUID();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        CountDownLatch finalized = new CountDownLatch(1);
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            finalized.countDown();
            return result;
        }).when(ledger).finish(anyString(), eq("CANCELLED"));
        FAKE.streamEntered = entered; FAKE.streamRelease = release;
        var pending = startAsync(conversation, requestId);
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            String id = runId(conversation, requestId);
            cancelRun(id);
            release.countDown();
            pending.get(15, TimeUnit.SECONDS);
            assertEquals(1, FAKE.streams.get());
            assertEquals("CANCELLED", jdbc.queryForObject("SELECT status FROM chat_run WHERE id=?", String.class, id));
            assertTrue(finalized.await(5, TimeUnit.SECONDS), "CANCELLED attempt finalization did not finish");
            var attempt = jdbc.queryForMap("SELECT execution_outcome, usage_presence FROM provider_attempt WHERE run_id=?", id);
            assertEquals("CANCELLED", attempt.get("execution_outcome"));
            assertEquals("UNKNOWN", attempt.get("usage_presence"));
            assertNotEquals("NOT_DISPATCHED", attempt.get("execution_outcome"));
        } finally {
            release.countDown();
            FAKE.streamEntered = null; FAKE.streamRelease = null;
            reset(ledger);
        }
    }


    @Test
    void cancelWhileProviderSubscriptionQueuedPreventsDispatch() throws Exception {
        String conversation = createRaceConversation();
        String requestId = "cancel-queued-" + UUID.randomUUID();
        CountDownLatch workerOccupied = new CountDownLatch(1), releaseWorker = new CountDownLatch(1);
        CountDownLatch queued = new CountDownLatch(1), recorded = watchNotDispatched();
        QueueScheduler scheduler = (QueueScheduler) dispatchScheduler;
        scheduler.delegate.schedule(() -> {
            workerOccupied.countDown();
            try { releaseWorker.await(10, TimeUnit.SECONDS); }
            catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
        });
        assertTrue(workerOccupied.await(5, TimeUnit.SECONDS));
        scheduler.queued = queued;
        var pending = startAsync(conversation, requestId);
        try {
            assertTrue(queued.await(10, TimeUnit.SECONDS), "provider subscription was not queued");
            String id = runId(conversation, requestId);
            assertEquals(1, count("SELECT count(*) FROM provider_attempt WHERE run_id=?", id));
            cancelRun(id);
            releaseWorker.countDown();
            pending.get(15, TimeUnit.SECONDS);
            assertCancelledWithoutDispatch(id, recorded);
        } finally {
            scheduler.queued = null;
            releaseWorker.countDown();
            reset(ledger);
        }
    }

    @Test
    void failedSessionAssemblyIsRecordedAsNotDispatched() throws Exception {
        String conversation = createRaceConversation();
        String requestId = "session-failure-" + UUID.randomUUID();
        doThrow(new IllegalStateException("synthetic session failure")).when(sessions).findOrCreate(any(CreateSessionRequest.class));
        try {
            var response = post("/api/conversations/" + conversation + "/runs", Map.of("clientRequestId", requestId, "message", "test input"));
            assertTrue(response.statusCode() >= 500 || (response.statusCode() == 200
                    && response.body().contains("run.failed") && !response.body().contains("run.completed")), response.body());
            assertEquals(0, FAKE.calls.get()); assertEquals(0, FAKE.streams.get()); assertEquals(0, FAKE.subscriptions.get());
            String id = runId(conversation, requestId);
            assertEquals("FAILED", jdbc.queryForObject("SELECT status FROM chat_run WHERE id=?", String.class, id));
            var attempt = jdbc.queryForMap("SELECT execution_outcome, execution_reason FROM provider_attempt WHERE run_id=?", id);
            assertEquals("NOT_DISPATCHED", attempt.get("execution_outcome"));
            assertEquals("PROMPT_CONTEXT_UNAVAILABLE", attempt.get("execution_reason"));
        } finally {
            reset(sessions);
        }
    }


    @Test
    void preparedInPreviousTaipeiMonthUsesActualNextMonthDispatchTime() throws Exception {
        String conversation = createRaceConversation();
        String requestId = "month-boundary-" + UUID.randomUUID();
        Instant preparedAt = Instant.parse("2026-01-31T15:59:59Z");
        Instant dispatchedAt = Instant.parse("2026-01-31T16:00:01Z");
        CountDownLatch persisted = new CountDownLatch(1), release = new CountDownLatch(1);
        doAnswer(invocation -> {
            Object id = invocation.callRealMethod();
            persisted.countDown();
            if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("begin latch timed out");
            return id;
        }).when(ledger).begin(any());
        var pending = startAsync(conversation, requestId);
        try {
            assertTrue(persisted.await(10, TimeUnit.SECONDS));
            String id = runId(conversation, requestId);
            var prepared = jdbc.queryForMap("SELECT prepared_at, started_at FROM provider_attempt WHERE run_id=?", id);
            assertEquals(preparedAt, ((java.sql.Timestamp) prepared.get("prepared_at")).toInstant());
            assertNull(prepared.get("started_at"));
            CLOCK.set(dispatchedAt);
            release.countDown();
            var response = pending.get(15, TimeUnit.SECONDS);
            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("run.completed"), response.body());
            var stored = jdbc.queryForMap("SELECT prepared_at, started_at FROM provider_attempt WHERE run_id=?", id);
            assertEquals(preparedAt, ((java.sql.Timestamp) stored.get("prepared_at")).toInstant());
            assertEquals(dispatchedAt, ((java.sql.Timestamp) stored.get("started_at")).toInstant());
            assertEquals(1, FAKE.streams.get());
            assertEquals(1, FAKE.subscriptions.get());
        } finally {
            release.countDown();
            reset(ledger);
        }
    }

    @Test
    void cancellationDuringDispatchTimestampWriteLeavesNoDispatchTimestamp() throws Exception {
        String conversation = createRaceConversation();
        String requestId = "cancel-mark-dispatch-" + UUID.randomUUID();
        CountDownLatch marked = new CountDownLatch(1), release = new CountDownLatch(1);
        CountDownLatch recorded = watchNotDispatched();
        doAnswer(invocation -> {
            Object updated = invocation.callRealMethod();
            assertEquals(Boolean.TRUE, updated);
            marked.countDown();
            if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("dispatch mark latch timed out");
            return updated;
        }).when(ledger).markDispatched(anyString(), any());
        var pending = startAsync(conversation, requestId);
        try {
            assertTrue(marked.await(10, TimeUnit.SECONDS));
            String id = runId(conversation, requestId);
            // The spy is still inside the transaction: another connection cannot see the uncommitted update.
            assertNull(jdbc.queryForMap("SELECT started_at FROM provider_attempt WHERE run_id=?", id).get("started_at"));
            cancelRun(id);
            release.countDown();
            pending.get(15, TimeUnit.SECONDS);
            assertCancelledWithoutDispatch(id, recorded);
            assertNull(jdbc.queryForMap("SELECT started_at FROM provider_attempt WHERE run_id=?", id).get("started_at"));
        } finally {
            release.countDown();
            reset(ledger);
        }
    }

    @Test
    void failedDispatchTimestampWriteCannotInvokeProvider() throws Exception {
        String conversation = createRaceConversation();
        String requestId = "failed-mark-dispatch-" + UUID.randomUUID();
        CountDownLatch recorded = watchNotDispatched();
        doThrow(new IllegalStateException("synthetic dispatch timestamp failure"))
                .when(ledger).markDispatched(anyString(), any());
        try {
            var response = post("/api/conversations/" + conversation + "/runs", Map.of(
                    "clientRequestId", requestId, "message", "test input"));
            assertTrue(response.statusCode() >= 500 || (response.statusCode() == 200
                    && response.body().contains("run.failed") && !response.body().contains("run.completed")), response.body());
            assertEquals(0, FAKE.calls.get()); assertEquals(0, FAKE.streams.get()); assertEquals(0, FAKE.subscriptions.get());
            String id = runId(conversation, requestId);
            assertEquals("FAILED", jdbc.queryForObject("SELECT status FROM chat_run WHERE id=?", String.class, id));
            assertTrue(recorded.await(5, TimeUnit.SECONDS), "NOT_DISPATCHED ledger write did not finish");
            var attempt = jdbc.queryForMap("SELECT execution_outcome, started_at FROM provider_attempt WHERE run_id=?", id);
            assertEquals("NOT_DISPATCHED", attempt.get("execution_outcome"));
            assertNull(attempt.get("started_at"));
        } finally {
            reset(ledger);
        }
    }


    @Test
    void dispatchTimestampIsTakenAfterAttemptRowLockAcrossTaipeiMonth() throws Exception {
        String conversation = createRaceConversation();
        String requestId = "locked-dispatch-month-" + UUID.randomUUID();
        Instant beforeMonth = Instant.parse("2026-01-31T15:59:59.999Z");
        Instant afterMonth = Instant.parse("2026-01-31T16:00:00.001Z");
        CLOCK.set(beforeMonth);
        CountDownLatch persisted = new CountDownLatch(1), releaseBegin = new CountDownLatch(1), markEntered = new CountDownLatch(1);
        AtomicReference<String> attemptId = new AtomicReference<>();
        doAnswer(invocation -> {
            String id = (String) invocation.callRealMethod();
            attemptId.set(id);
            persisted.countDown();
            if (!releaseBegin.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("begin latch timed out");
            return id;
        }).when(ledger).begin(any());
        doAnswer(invocation -> {
            markEntered.countDown();
            return invocation.callRealMethod();
        }).when(ledger).markDispatched(anyString(), any());
        var pending = startAsync(conversation, requestId);
        try (Connection lockConnection = dataSource.getConnection()) {
            lockConnection.setAutoCommit(false);
            try {
                assertTrue(persisted.await(10, TimeUnit.SECONDS), "attempt was not durably prepared");
                int lockerPid;
                try (var statement = lockConnection.createStatement(); var row = statement.executeQuery("SELECT pg_backend_pid()")) {
                    assertTrue(row.next());
                    lockerPid = row.getInt(1);
                }
                try (var lock = lockConnection.prepareStatement("SELECT id FROM provider_attempt WHERE id=? FOR UPDATE")) {
                    lock.setString(1, attemptId.get());
                    try (var row = lock.executeQuery()) { assertTrue(row.next()); }
                }
                releaseBegin.countDown();
                assertTrue(markEntered.await(10, TimeUnit.SECONDS), "dispatch marker was not invoked");
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                boolean waiting = false;
                while (System.nanoTime() < deadline) {
                    waiting = Boolean.TRUE.equals(jdbc.queryForObject(
                            "SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE ? = ANY(pg_blocking_pids(pid)))",
                            Boolean.class, lockerPid));
                    if (waiting) break;
                    Thread.onSpinWait();
                }
                assertTrue(waiting, "dispatch write did not block on the held PostgreSQL row lock");
                assertEquals(0, FAKE.calls.get()); assertEquals(0, FAKE.streams.get()); assertEquals(0, FAKE.subscriptions.get());
                CLOCK.set(afterMonth);
                lockConnection.commit();
                var response = pending.get(15, TimeUnit.SECONDS);
                assertEquals(200, response.statusCode(), response.body());
                assertTrue(response.body().contains("run.completed"), response.body());
                var stored = jdbc.queryForMap("SELECT prepared_at, started_at FROM provider_attempt WHERE id=?", attemptId.get());
                assertEquals(beforeMonth, ((java.sql.Timestamp) stored.get("prepared_at")).toInstant());
                assertEquals(afterMonth, ((java.sql.Timestamp) stored.get("started_at")).toInstant(),
                        "started_at must reflect the post-lock dispatch time");
                assertEquals(1, FAKE.streams.get());
                assertEquals(1, FAKE.subscriptions.get());
            } finally {
                releaseBegin.countDown();
                lockConnection.rollback();
                reset(ledger);
            }
        }
    }


    private void assertCaptureFailure(String source, boolean finalizationAlsoFails) throws Exception {
        String conversation = createRaceConversation();
        String requestId = "capture-fault-" + UUID.randomUUID();
        FAKE.emitNativeUsage = true;
        String sentinel = "issue33-exception-secret-sentinel";
        try {
            switch (source) {
                case "append" -> doThrow(new IllegalStateException(sentinel)).doCallRealMethod().when(ledger).append(anyString(), any());
                case "responseId" -> doThrow(new IllegalStateException(sentinel)).doCallRealMethod().when(ledger).recordResponseId(anyString(), anyString());
                case "capture" -> doThrow(new IllegalStateException(sentinel)).doCallRealMethod().when(captureAdapter).capture(any(), anyString(), anyLong());
                default -> throw new IllegalArgumentException(source);
            }
            if (finalizationAlsoFails) doThrow(new IllegalStateException(sentinel)).when(ledger).finish(anyString(), anyString());
            var response = post("/api/conversations/" + conversation + "/runs", Map.of("clientRequestId", requestId, "message", "fixture input"));
            assertTrue(response.statusCode() >= 500 || (response.statusCode() == 200
                    && response.body().contains("run.failed") && !response.body().contains("run.completed")), response.body());
            assertFalse(response.body().contains(sentinel));
            assertFalse(response.body().contains("issue33-raw-secret-sentinel"));
            assertEquals(1, FAKE.streams.get());
            String run = runId(conversation, requestId);
            assertEquals("FAILED", jdbc.queryForObject("SELECT status FROM chat_run WHERE id=?", String.class, run));
            String attempt = jdbc.queryForObject("SELECT id FROM provider_attempt WHERE run_id=?", String.class, run);
            var state = jdbc.queryForMap("SELECT execution_outcome,execution_reason,usage_presence,projection_state,projection_error,projection_version FROM provider_attempt WHERE id=?", attempt);
            if (finalizationAlsoFails) {
                assertEquals("IN_PROGRESS", state.get("execution_outcome"));
                reset(ledger);
                databaseInitializer.run(null);
                var recovered = jdbc.queryForMap("SELECT execution_outcome,projection_error FROM provider_attempt WHERE id=?", attempt);
                assertEquals("UNKNOWN", recovered.get("execution_outcome"));
                assertEquals("PROCESS_RESTART", recovered.get("projection_error"));
            } else {
                assertEquals("UNKNOWN", state.get("execution_outcome"));
                assertEquals("CAPTURE_FAILED", state.get("execution_reason"));
                assertEquals("UNKNOWN", state.get("usage_presence"));
                assertEquals("UNKNOWN", state.get("projection_state"));
                assertEquals("CAPTURE_FAILED", state.get("projection_error"));
                assertTrue(((Number) state.get("projection_version")).longValue() > 0);
                assertFalse(state.toString().contains(sentinel));
                assertFalse(state.toString().contains("issue33-raw-secret-sentinel"));
            }
        } finally {
            FAKE.emitNativeUsage = false;
            reset(ledger, captureAdapter);
        }
    }

    @Test void appendFailureBecomesSafeUnknown() throws Exception { assertCaptureFailure("append", false); }
    @Test void responseIdFailureBecomesSafeUnknown() throws Exception { assertCaptureFailure("responseId", false); }
    @Test void adapterCaptureFailureBecomesSafeUnknown() throws Exception { assertCaptureFailure("capture", false); }
    @Test void captureAndFinishFailureRemainInProgressUntilRecovery() throws Exception { assertCaptureFailure("append", true); }

    @Test
    void committedDecisionTimeDoesNotChangeWhenProviderEntryCrossesMonth() throws Exception {
        String conversation = createRaceConversation();
        String requestId = "after-commit-month-" + UUID.randomUUID();
        Instant decision = Instant.parse("2026-01-31T15:59:59.999Z");
        Instant later = Instant.parse("2026-01-31T16:00:00.001Z");
        CLOCK.set(decision);
        CountDownLatch committed = new CountDownLatch(1), release = new CountDownLatch(1);
        doAnswer(invocation -> {
            committed.countDown();
            if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("commit observer timed out");
            return null;
        }).when(dispatchObserver).afterCommit(anyString());
        var pending = startAsync(conversation, requestId);
        try {
            assertTrue(committed.await(10, TimeUnit.SECONDS));
            String run = runId(conversation, requestId);
            var before = jdbc.queryForMap("SELECT prepared_at,started_at FROM provider_attempt WHERE run_id=?", run);
            assertEquals(decision, ((java.sql.Timestamp) before.get("started_at")).toInstant(), "mark must already be committed at observer");
            assertEquals(0, FAKE.streams.get());
            CLOCK.set(later);
            release.countDown();
            var response = pending.get(15, TimeUnit.SECONDS);
            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("run.completed"), response.body());
            var after = jdbc.queryForMap("SELECT prepared_at,started_at FROM provider_attempt WHERE run_id=?", run);
            assertEquals(decision, ((java.sql.Timestamp) after.get("started_at")).toInstant());
            assertEquals(before.get("prepared_at"), after.get("prepared_at"));
            assertEquals(1, FAKE.streams.get());
        } finally {
            release.countDown();
            reset(dispatchObserver);
        }
    }

    @Test
    void failedCommitAfterDecisionDoesNotInvokeProvider() throws Exception {
        String conversation = createRaceConversation();
        String requestId = "mark-after-decision-failure-" + UUID.randomUUID();
        CountDownLatch recorded = watchNotDispatched();
        doAnswer(invocation -> {
            assertEquals(Boolean.TRUE, invocation.callRealMethod());
            throw new IllegalStateException("synthetic transaction failure after decision");
        }).when(ledger).markDispatched(anyString(), any());
        try {
            var response = post("/api/conversations/" + conversation + "/runs", Map.of("clientRequestId", requestId, "message", "test input"));
            assertTrue(response.statusCode() >= 500 || (response.statusCode() == 200 && response.body().contains("run.failed") && !response.body().contains("run.completed")), response.body());
            assertTrue(recorded.await(5, TimeUnit.SECONDS));
            assertEquals(0, FAKE.calls.get()); assertEquals(0, FAKE.streams.get()); assertEquals(0, FAKE.subscriptions.get());
            String run = runId(conversation, requestId);
            var attempt = jdbc.queryForMap("SELECT execution_outcome,started_at FROM provider_attempt WHERE run_id=?", run);
            assertEquals("NOT_DISPATCHED", attempt.get("execution_outcome"));
            assertNull(attempt.get("started_at"));
        } finally { reset(ledger); }
    }

}
