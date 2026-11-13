package io.github.samzhu.bot;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.samzhu.bot.usage.UsageLedger;
import io.github.samzhu.bot.usage.WorkspaceAdmission;
import io.github.samzhu.bot.conversation.ConversationOperations;
import io.github.samzhu.bot.execution.internal.DispatchCommitObserver;
import io.github.samzhu.bot.execution.internal.capture.UsageCaptureAdapter;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.session.SessionService;
import org.springframework.ai.session.CreateSessionRequest;
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
    @MockitoSpyBean UsageLedger ledger;
    @MockitoSpyBean WorkspaceAdmission admission;
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
        volatile CountDownLatch subscribed;
        volatile CountDownLatch disposed;

        @Override public ChatResponse call(Prompt prompt) {
            calls.incrementAndGet();
            return response();
        }

        @Override public Flux<ChatResponse> stream(Prompt prompt) {
            streams.incrementAndGet();
            if (subscribed != null && disposed != null) {
                var onSubscribe=subscribed; var onDispose=disposed;
                return Flux.<ChatResponse>never().doOnSubscribe(ignored -> { subscriptions.incrementAndGet(); onSubscribe.countDown(); })
                        .doOnCancel(onDispose::countDown);
            }
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

    private void rejectTerminalWrites(String conversation) {
        jdbc.execute("create or replace function test_reject_durable_terminal() returns trigger language plpgsql as $$ begin if new.execution_outcome <> 'IN_PROGRESS' and exists(select 1 from chat_run where id=new.run_id and conversation_id='" + conversation + "') then raise exception 'injected terminal write failure'; end if; return new; end $$");
        jdbc.execute("create trigger test_reject_durable_terminal before update on provider_attempt for each row execute function test_reject_durable_terminal()");
    }

    private void allowTerminalWrites() {
        jdbc.execute("drop trigger if exists test_reject_durable_terminal on provider_attempt");
        jdbc.execute("drop function if exists test_reject_durable_terminal()");
    }

    private WorkspaceAdmission.Registration registration(String run) {
        var row=jdbc.queryForMap("select token,workspace_id,actor_id,conversation_id,partition from workspace_admission_registration where run_id=?",run);
        return new WorkspaceAdmission.Registration((String)row.get("token"),(String)row.get("workspace_id"),run,
                (String)row.get("actor_id"),(String)row.get("conversation_id"),WorkspaceAdmission.Partition.valueOf((String)row.get("partition")));
    }

    @Test
    void failedAttemptFinishCannotSucceedAndDurableIntentRecovers() throws Exception {
        String conversation=createRaceConversation();
        String requestId="finish-fail-"+UUID.randomUUID();
        rejectTerminalWrites(conversation);
        try {
            var response=post("/api/conversations/"+conversation+"/runs",Map.of("clientRequestId",requestId,"message","test input"));
            assertTrue(response.statusCode()>=500 || (response.statusCode()==200 && response.body().contains("run.failed") && !response.body().contains("run.completed")),response.body());
            assertEquals(0,FAKE.calls.get()); assertEquals(1,FAKE.streams.get()); assertEquals(1,FAKE.subscriptions.get());
            String run=runId(conversation,requestId);
            assertNotEquals("COMPLETED",jdbc.queryForObject("select status from chat_run where id=?",String.class,run));
            String attempt=jdbc.queryForObject("select id from provider_attempt where run_id=?",String.class,run);
            assertEquals("IN_PROGRESS",jdbc.queryForObject("select execution_outcome from provider_attempt where id=?",String.class,attempt));
            assertEquals("PENDING",jdbc.queryForObject("select state from workspace_admission_finalization where attempt_id=?",String.class,attempt));
            assertNotEquals("TERMINAL",jdbc.queryForObject("select status from workspace_admission_registration where run_id=?",String.class,run));
            allowTerminalWrites();
            assertEquals(WorkspaceAdmission.FinalizationResult.SETTLED,admission.recoverFinalization(registration(run),attempt).toCompletableFuture().get(10,TimeUnit.SECONDS));
            assertEquals("SUCCEEDED",jdbc.queryForObject("select execution_outcome from provider_attempt where id=?",String.class,attempt));
            assertEquals("SETTLED",jdbc.queryForObject("select state from workspace_admission_finalization where attempt_id=?",String.class,attempt));
        } finally { allowTerminalWrites(); }
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
            WorkspaceAdmission.FinalizeAttempt command = invocation.getArgument(0);
            @SuppressWarnings("unchecked")
            var result = (java.util.concurrent.CompletionStage<WorkspaceAdmission.FinalizationResult>) invocation.callRealMethod();
            return result.thenApply(outcome -> {
                if (command.kind() == WorkspaceAdmission.TerminalKind.NOT_DISPATCHED
                        && outcome == WorkspaceAdmission.FinalizationResult.SETTLED) recorded.countDown();
                return outcome;
            });
        }).when(admission).finalizeAttempt(any());
        return recorded;
    }

    private void assertCancelledWithoutDispatch(String id, CountDownLatch recorded) throws InterruptedException {
        boolean settled = recorded.await(5, TimeUnit.SECONDS);
        assertTrue(settled, () -> "NOT_DISPATCHED ledger write did not finish; " + cancellationDiagnostics(id));
        assertEquals(0, FAKE.calls.get()); assertEquals(0, FAKE.streams.get()); assertEquals(0, FAKE.subscriptions.get());
        assertEquals("CANCELLED", jdbc.queryForObject("SELECT status FROM chat_run WHERE id=?", String.class, id));
        assertEquals("NOT_DISPATCHED", jdbc.queryForObject("SELECT execution_outcome FROM provider_attempt WHERE run_id=?", String.class, id));
    }

    private String cancellationDiagnostics(String runId) {
        try {
            var rows = jdbc.queryForList("""
                select r.status as run_status, ar.status as registration_status,
                       a.execution_outcome as attempt_outcome, (a.started_at is not null) as has_started_at,
                       d.state as dispatch_state, c.status as claim_status,
                       f.terminal_kind as finalization_kind, f.state as finalization_state
                  from chat_run r
                  left join workspace_admission_registration ar on ar.run_id=r.id
                  left join provider_attempt a on a.run_id=r.id
                  left join workspace_admission_dispatch d on d.attempt_id=a.id
                  left join workspace_admission_claim c on c.attempt_id=a.id
                  left join workspace_admission_finalization f on f.attempt_id=a.id
                 where r.id=?
                """, runId);
            var calls = org.mockito.Mockito.mockingDetails(admission).getInvocations().stream()
                    .map(call -> call.getMethod().getName())
                    .filter(name -> name.equals("dispatch") || name.equals("recoverClaim")
                            || name.equals("finalizeAttempt") || name.equals("cancelRun"))
                    .collect(java.util.stream.Collectors.groupingBy(java.util.function.Function.identity(),
                            java.util.stream.Collectors.counting()));
            return "database=" + rows + "; admissionCalls=" + calls;
        } catch (RuntimeException ex) {
            // Do not expose SQL text, payloads or credentials in test failure output.
            return "diagnostics unavailable (" + ex.getClass().getSimpleName() + ")";
        }
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
        CountDownLatch entered = new CountDownLatch(1), disposed = new CountDownLatch(1);
        CountDownLatch finalized = new CountDownLatch(1);
        doAnswer(invocation -> {
            WorkspaceAdmission.FinalizeAttempt command=invocation.getArgument(0);
            @SuppressWarnings("unchecked") var result=(java.util.concurrent.CompletionStage<WorkspaceAdmission.FinalizationResult>)invocation.callRealMethod();
            if(command.kind()==WorkspaceAdmission.TerminalKind.CANCELLED)
                result.thenAccept(value -> { if(value==WorkspaceAdmission.FinalizationResult.SETTLED) finalized.countDown(); });
            return result;
        }).when(admission).finalizeAttempt(any());
        FAKE.subscribed = entered; FAKE.disposed = disposed;
        var pending = startAsync(conversation, requestId);
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            String id = runId(conversation, requestId);
            cancelRun(id);
            pending.get(15, TimeUnit.SECONDS);
            assertTrue(disposed.await(5,TimeUnit.SECONDS),"provider subscription must be disposed");
            assertEquals(1, FAKE.streams.get());
            assertEquals(1, FAKE.subscriptions.get());
            assertEquals("CANCELLED", jdbc.queryForObject("SELECT status FROM chat_run WHERE id=?", String.class, id));
            assertTrue(finalized.await(5, TimeUnit.SECONDS), "CANCELLED attempt finalization did not finish");
            var attempt = jdbc.queryForMap("SELECT execution_outcome, usage_presence FROM provider_attempt WHERE run_id=?", id);
            assertEquals("CANCELLED", attempt.get("execution_outcome"));
            assertEquals("UNKNOWN", attempt.get("usage_presence"));
            assertNotEquals("NOT_DISPATCHED", attempt.get("execution_outcome"));
            assertEquals(1,count("select count(*) from workspace_admission_claim where attempt_id=(select id from provider_attempt where run_id=?)",id));
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8); String registrationStatus=null;
            while(System.nanoTime()<deadline) {
                registrationStatus=jdbc.queryForObject("select status from workspace_admission_registration where run_id=?",String.class,id);
                if("TERMINAL".equals(registrationStatus)) break;
                Thread.yield();
            }
            assertEquals("TERMINAL",registrationStatus,"durable cancellation must eventually release its registration");
        } finally {
            FAKE.subscribed = null; FAKE.disposed = null;
            reset(admission);
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
    void promptContextFailureDoesNotExposeExceptionInPublicRun() throws Exception {
        String conversation=createRaceConversation();
        String requestId="prompt-sentinel-"+UUID.randomUUID();
        String sentinel="synthetic-prompt-context-private-marker";
        doThrow(new IllegalStateException(sentinel)).when(sessions).findOrCreate(any(CreateSessionRequest.class));
        try {
            var response=post("/api/conversations/"+conversation+"/runs",Map.of("clientRequestId",requestId,"message","test input"));
            assertEquals(200,response.statusCode());
            assertTrue(response.body().contains("run.failed"),response.body());
            String id=runId(conversation,requestId);
            var result=post("/graphql",Map.of("query","query { run(id: \""+id+"\") { id status errorMessage } }"));
            assertEquals(200,result.statusCode());
            assertEquals("FAILED",json.readTree(result.body()).path("data").path("run").path("status").asText(),result.body());
            assertEquals("NOT_DISPATCHED",jdbc.queryForObject("select execution_outcome from provider_attempt where run_id=?",String.class,id));
            assertEquals("PROMPT_CONTEXT_UNAVAILABLE",jdbc.queryForObject("select execution_reason from provider_attempt where run_id=?",String.class,id));
            assertEquals(0,FAKE.calls.get()); assertEquals(0,FAKE.streams.get()); assertEquals(0,FAKE.subscriptions.get());
            assertEquals(0,count("select count(*) from ai_session where id=?",conversation));
            assertFalse(response.body().contains(sentinel),"SSE must not expose prompt context exceptions");
            assertFalse(result.body().contains(sentinel),"GraphQL must not expose prompt context exceptions");
            assertFalse(jdbc.queryForObject("select error_message from chat_run where id=?",String.class,id).contains(sentinel));
        } finally { reset(sessions); }
    }

    @Test
    void promptFailureWaitsForDurableAttemptSettlementBeforeRunTerminal() throws Exception {
        String conversation=createRaceConversation();
        String requestId="prompt-settlement-lock-"+UUID.randomUUID();
        var sessionEntered=new CountDownLatch(1); var releaseSession=new CountDownLatch(1);
        doAnswer(call -> {
            sessionEntered.countDown();
            if(!releaseSession.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("session latch timed out");
            throw new IllegalStateException("synthetic prompt failure");
        }).when(sessions).findOrCreate(any(CreateSessionRequest.class));
        jdbc.execute("create function test_hold_prompt_settlement() returns trigger language plpgsql as $$ begin if new.execution_outcome <> 'IN_PROGRESS' and exists(select 1 from chat_run where id=new.run_id and conversation_id='"+conversation+"') then perform pg_advisory_xact_lock(370037001); end if; return new; end $$");
        jdbc.execute("create trigger test_hold_prompt_settlement before update on provider_attempt for each row execute function test_hold_prompt_settlement()");
        var pending=startAsync(conversation,requestId);
        try (var connection=dataSource.getConnection()) {
            assertTrue(sessionEntered.await(10,TimeUnit.SECONDS));
            String run=runId(conversation,requestId);
            connection.setAutoCommit(false);
            int blocker;
            try(var statement=connection.createStatement(); var rows=statement.executeQuery("select pg_backend_pid()")) { assertTrue(rows.next()); blocker=rows.getInt(1); }
            try(var statement=connection.createStatement()) { statement.execute("select pg_advisory_xact_lock(370037001)"); }
            releaseSession.countDown();
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5); boolean waiting=false;
            while(System.nanoTime()<deadline) {
                waiting=Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from pg_stat_activity where ?=any(pg_blocking_pids(pid)))",Boolean.class,blocker));
                if(waiting) break;
                Thread.yield();
            }
            assertTrue(waiting,"settlement did not reach the advisory-lock trigger");
            assertEquals("PENDING",jdbc.queryForObject("select f.state from workspace_admission_finalization f join provider_attempt a on a.id=f.attempt_id where a.run_id=?",String.class,run),"finalization intent must already be committed");
            assertEquals("IN_PROGRESS",jdbc.queryForObject("select execution_outcome from provider_attempt where run_id=?",String.class,run));
            assertNotEquals("TERMINAL",jdbc.queryForObject("select status from workspace_admission_registration where run_id=?",String.class,run));
            assertThrows(java.util.concurrent.TimeoutException.class,() -> pending.get(2,TimeUnit.SECONDS),"HTTP stream must not complete before attempt settlement");
            assertEquals("RUNNING",jdbc.queryForObject("select status from chat_run where id=?",String.class,run),"Run must not publish terminal before durable attempt settlement");
            connection.rollback();
            var response=pending.get(15,TimeUnit.SECONDS);
            assertTrue(response.body().contains("run.failed"),response.body());
            assertEquals("NOT_DISPATCHED",jdbc.queryForObject("select execution_outcome from provider_attempt where run_id=?",String.class,run));
            assertEquals("PROMPT_CONTEXT_UNAVAILABLE",jdbc.queryForObject("select execution_reason from provider_attempt where run_id=?",String.class,run));
            assertEquals(0,FAKE.calls.get()); assertEquals(0,FAKE.streams.get()); assertEquals(0,FAKE.subscriptions.get());
        } finally {
            releaseSession.countDown();
            jdbc.execute("drop trigger if exists test_hold_prompt_settlement on provider_attempt");
            jdbc.execute("drop function if exists test_hold_prompt_settlement()");
            reset(sessions);
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
    void cancellationBeforeAdmissionClaimLeavesNoDispatchTimestamp() throws Exception {
        String conversation = createRaceConversation();
        String requestId = "cancel-mark-dispatch-" + UUID.randomUUID();
        CountDownLatch marked = new CountDownLatch(1), release = new CountDownLatch(1);
        CountDownLatch recorded = watchNotDispatched();
        doAnswer(invocation -> {
            marked.countDown();
            if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("admission dispatch latch timed out");
            return invocation.callRealMethod();
        }).when(admission).dispatch(any());
        var pending = startAsync(conversation, requestId);
        try {
            assertTrue(marked.await(10, TimeUnit.SECONDS));
            String id = runId(conversation, requestId);
            // The public dispatch has not yet entered the lane, so cancellation can win the claim.
            assertNull(jdbc.queryForMap("SELECT started_at FROM provider_attempt WHERE run_id=?", id).get("started_at"));
            cancelRun(id);
            release.countDown();
            pending.get(15, TimeUnit.SECONDS);
            assertCancelledWithoutDispatch(id, recorded);
            assertNull(jdbc.queryForMap("SELECT started_at FROM provider_attempt WHERE run_id=?", id).get("started_at"));
        } finally {
            release.countDown();
            reset(ledger);
            reset(admission);
        }
    }

    @Test
    void failedDispatchTimestampWriteCannotInvokeProvider() throws Exception {
        String conversation = createRaceConversation();
        String requestId = "failed-mark-dispatch-" + UUID.randomUUID();
        CountDownLatch recorded = watchNotDispatched();
        jdbc.execute("""
            CREATE FUNCTION dispatch_timestamp_failure_fixture() RETURNS trigger LANGUAGE plpgsql AS $$
            BEGIN IF NEW.started_at IS NOT NULL AND OLD.started_at IS NULL THEN
              RAISE EXCEPTION 'synthetic dispatch timestamp failure'; END IF; RETURN NEW; END $$
            """);
        jdbc.execute("CREATE TRIGGER dispatch_timestamp_failure_fixture BEFORE UPDATE ON provider_attempt FOR EACH ROW EXECUTE FUNCTION dispatch_timestamp_failure_fixture()");
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
            jdbc.execute("DROP TRIGGER IF EXISTS dispatch_timestamp_failure_fixture ON provider_attempt");
            jdbc.execute("DROP FUNCTION IF EXISTS dispatch_timestamp_failure_fixture()");
            reset(ledger);
            reset(admission);
        }
    }


    @Test
    void databaseCatalogMarkerFailureCannotInvokeProvider() throws Exception {
        String conversation=createRaceConversation();
        String requestId="failed-catalog-marker-"+UUID.randomUUID();
        CountDownLatch recorded=watchNotDispatched();
        jdbc.execute("""
            CREATE FUNCTION pricing_marker_failure_fixture() RETURNS trigger LANGUAGE plpgsql AS $$
            BEGIN IF NEW.started_at IS NOT NULL AND OLD.started_at IS NULL THEN
              RAISE EXCEPTION 'marker fixture failure'; END IF; RETURN NEW; END $$
            """);
        jdbc.execute("CREATE TRIGGER pricing_marker_failure_fixture BEFORE UPDATE ON provider_attempt FOR EACH ROW EXECUTE FUNCTION pricing_marker_failure_fixture()");
        try {
            var response=post("/api/conversations/"+conversation+"/runs",Map.of("clientRequestId",requestId,"message","fixture"));
            assertTrue(response.statusCode()>=500 || (response.statusCode()==200 && response.body().contains("run.failed")),response.body());
            assertEquals(0,FAKE.calls.get()); assertEquals(0,FAKE.streams.get()); assertEquals(0,FAKE.subscriptions.get());
            assertTrue(recorded.await(5,TimeUnit.SECONDS));
            String id=runId(conversation,requestId);
            var attempt=jdbc.queryForMap("select started_at,catalog_marker,execution_outcome from provider_attempt where run_id=?",id);
            assertNull(attempt.get("started_at"));
            assertEquals("NOT_DISPATCHED",attempt.get("execution_outcome"));
            assertEquals("LEGACY_UNPRICED",attempt.get("catalog_marker"));
        } finally {
            jdbc.execute("DROP TRIGGER pricing_marker_failure_fixture ON provider_attempt");
            jdbc.execute("DROP FUNCTION pricing_marker_failure_fixture()");
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
        }).when(admission).dispatch(any());
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
                reset(admission);
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
            if (finalizationAlsoFails) rejectTerminalWrites(conversation);
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
                assertEquals("PENDING",jdbc.queryForObject("select state from workspace_admission_finalization where attempt_id=?",String.class,attempt));
                assertNotEquals("TERMINAL",jdbc.queryForObject("select status from workspace_admission_registration where run_id=?",String.class,run));
                allowTerminalWrites();
                assertEquals(WorkspaceAdmission.FinalizationResult.SETTLED,admission.recoverFinalization(registration(run),attempt).toCompletableFuture().get(10,TimeUnit.SECONDS));
                var recovered = jdbc.queryForMap("SELECT execution_outcome,projection_error FROM provider_attempt WHERE id=?", attempt);
                assertEquals("UNKNOWN", recovered.get("execution_outcome"));
                assertEquals("CAPTURE_FAILED", recovered.get("projection_error"));
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
            if (finalizationAlsoFails) allowTerminalWrites();
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
        jdbc.execute("""
            CREATE FUNCTION dispatch_claim_failure_fixture() RETURNS trigger LANGUAGE plpgsql AS $$
            BEGIN RAISE EXCEPTION 'synthetic transaction failure after started_at update'; END $$
            """);
        jdbc.execute("CREATE TRIGGER dispatch_claim_failure_fixture BEFORE INSERT ON workspace_admission_claim FOR EACH ROW EXECUTE FUNCTION dispatch_claim_failure_fixture()");
        try {
            var response = post("/api/conversations/" + conversation + "/runs", Map.of("clientRequestId", requestId, "message", "test input"));
            assertTrue(response.statusCode() >= 500 || (response.statusCode() == 200 && response.body().contains("run.failed") && !response.body().contains("run.completed")), response.body());
            assertTrue(recorded.await(5, TimeUnit.SECONDS));
            assertEquals(0, FAKE.calls.get()); assertEquals(0, FAKE.streams.get()); assertEquals(0, FAKE.subscriptions.get());
            String run = runId(conversation, requestId);
            var attempt = jdbc.queryForMap("SELECT execution_outcome,started_at FROM provider_attempt WHERE run_id=?", run);
            assertEquals("NOT_DISPATCHED", attempt.get("execution_outcome"));
            assertNull(attempt.get("started_at"));
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS dispatch_claim_failure_fixture ON workspace_admission_claim");
            jdbc.execute("DROP FUNCTION IF EXISTS dispatch_claim_failure_fixture()");
            reset(ledger);
            reset(admission);
        }
    }

}
