package io.github.samzhu.bot;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.samzhu.bot.conversation.ConversationTitleGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@io.github.samzhu.bot.UseTestFakes
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"bot.conversation.title.execution-timeout=2s", "bot.conversation.title.queue-timeout=200ms"})
@Import({TestcontainersConfiguration.class, ConversationTitleHttpIntegrationTests.TitleFakeConfig.class})
@ActiveProfiles("test")
class ConversationTitleHttpIntegrationTests {
    @TestConfiguration(proxyBeanMethods = false)
    static class TitleFakeConfig implements org.springframework.web.servlet.config.annotation.WebMvcConfigurer {
        static volatile CountDownLatch readinessEntered = new CountDownLatch(1);
        static volatile CountDownLatch releaseReadiness = new CountDownLatch(1);
        @Bean ControlledTitleGenerator titleGenerator() { return new ControlledTitleGenerator(); }
        static volatile CountDownLatch contentFlushFailed = new CountDownLatch(1);
        static volatile jakarta.servlet.AsyncContext failedAsync;
        @Bean jakarta.servlet.Filter testFlushFailureFilter() {
            return (request, response, chain) -> {
                var httpRequest = (jakarta.servlet.http.HttpServletRequest) request;
                if (!"fail-content".equals(httpRequest.getHeader("X-Test-Flush"))) { chain.doFilter(request, response); return; }
                var wrapped = new jakarta.servlet.http.HttpServletResponseWrapper((jakarta.servlet.http.HttpServletResponse) response) {
                    private final java.io.ByteArrayOutputStream pending = new java.io.ByteArrayOutputStream();
                    @Override public jakarta.servlet.ServletOutputStream getOutputStream() throws java.io.IOException {
                        var delegate = super.getOutputStream();
                        return new jakarta.servlet.ServletOutputStream() {
                            @Override public boolean isReady() { return delegate.isReady(); }
                            @Override public void setWriteListener(jakarta.servlet.WriteListener listener) { delegate.setWriteListener(listener); }
                            @Override public void write(int value) throws java.io.IOException { pending.write(value); delegate.write(value); }
                            @Override public void write(byte[] bytes, int offset, int length) throws java.io.IOException { pending.write(bytes, offset, length); delegate.write(bytes, offset, length); }
                        };
                    }
                    @Override public void flushBuffer() throws java.io.IOException {
                        String bytes = pending.toString(java.nio.charset.StandardCharsets.UTF_8);
                        pending.reset();
                        if (bytes.contains("TEXT_MESSAGE_CONTENT")) {
                            failedAsync = httpRequest.getAsyncContext();
                            contentFlushFailed.countDown();
                            throw new java.io.IOException("synthetic content flush failure");
                        }
                        super.flushBuffer();
                    }
                };
                chain.doFilter(request, wrapped);
            };
        }
        @Override public void addInterceptors(org.springframework.web.servlet.config.annotation.InterceptorRegistry registry) {
            registry.addInterceptor(new org.springframework.web.servlet.AsyncHandlerInterceptor() {
                @Override public void afterConcurrentHandlingStarted(jakarta.servlet.http.HttpServletRequest request, jakarta.servlet.http.HttpServletResponse response, Object handler) throws Exception {
                    if ("hold".equals(request.getHeader("X-Test-Readiness"))) {
                        readinessEntered.countDown();
                        if (!releaseReadiness.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("readiness test timeout");
                    }
                }
            }).addPathPatterns("/api/ag-ui").order(org.springframework.core.Ordered.LOWEST_PRECEDENCE);
        }
    }
    static class ControlledTitleGenerator implements ConversationTitleGenerator {
        volatile CountDownLatch entered = new CountDownLatch(1);
        volatile CountDownLatch release = new CountDownLatch(1);
        final AtomicInteger calls = new AtomicInteger();
        volatile String input;
        volatile boolean virtual;
        volatile boolean transactionActive;
        volatile boolean ignoreInterrupt;
        volatile CountDownLatch returned = new CountDownLatch(1);
        void reset() { entered = new CountDownLatch(1); release = new CountDownLatch(1); returned = new CountDownLatch(1); calls.set(0); input = null; virtual = false; transactionActive = false; ignoreInterrupt = false; }
        @Override public String generate(String firstUserMessage) throws InterruptedException {
            input = firstUserMessage;
            virtual = Thread.currentThread().isVirtual();
            transactionActive = TransactionSynchronizationManager.isActualTransactionActive();
            calls.incrementAndGet();
            entered.countDown();
            try {
                while (true) {
                    try {
                        if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("test fake timed out");
                        return "Synthetic title";
                    } catch (InterruptedException ex) {
                        if (!ignoreInterrupt) throw ex;
                    }
                }
            } finally { returned.countDown(); }
        }
    }

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired javax.sql.DataSource dataSource;
    @Autowired ControlledTitleGenerator title;
    @TestBean(name = "mockChatModel", methodName = "chatModel", enforceOverride = true) ChatModel chat;
    static ControlledChatModel chatModel() { return ControlledChatModel.getInstance(); }
    final HttpClient http = HttpClient.newHttpClient();
    final ObjectMapper json = new ObjectMapper();

    HttpResponse<String> post(String path, Object body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @BeforeEach void reset() { chatModel().reset(); title.reset(); }

    @Test void timeoutRacingTitleCommitCannotSplitTitleAndWorkState() throws Exception {
        String scope = "title-test-" + UUID.randomUUID();
        jdbc.update("insert into workspace(id,name) values (?,?)", scope, "Synthetic title test");
        jdbc.update("insert into workspace_member(workspace_id,user_id,role,active) values (?,'dev-user','MANAGER',true)", scope);
        jdbc.update("insert into bot (id,name,system_prompt,created_at) values ('bot-general','General Assistant','trusted',CURRENT_TIMESTAMP) on conflict (id) do nothing");
        var created = json.readTree(post("/graphql", Map.of("query", "mutation($workspace: ID!) { createConversation(botId: \"bot-general\", workspaceId: $workspace) { id } }", "variables", Map.of("workspace", scope))).body());
        String conversation = created.path("data").path("createConversation").path("id").asText();
        var body = Map.of("threadId", conversation, "runId", UUID.randomUUID().toString(), "state", Map.of(),
                "messages", List.of(Map.of("id", UUID.randomUUID().toString(), "role", "user", "content", "Synthetic race")),
                "tools", List.of(), "context", List.of(), "forwardedProps", Map.of());
        jdbc.execute("create function test_title_gate_68() returns trigger language plpgsql as $$ begin perform pg_advisory_xact_lock(686868); return new; end $$");
        jdbc.execute("create trigger test_title_gate_68 after update of title on conversation for each row when (new.id = '" + conversation + "') execute function test_title_gate_68()");
        try (var lock = dataSource.getConnection()) {
            lock.setAutoCommit(false);
            try (var statement = lock.createStatement()) {
                statement.execute("select pg_advisory_lock(686868)");
                assertTrue(post("/api/ag-ui", body).body().contains("RUN_FINISHED"));
                assertTrue(title.entered.await(5, TimeUnit.SECONDS));
                title.release.countDown();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4); int waiting = 0;
                while (System.nanoTime() < deadline) {
                    waiting = jdbc.queryForObject("select count(*) from pg_stat_activity where wait_event='advisory' and query like 'update conversation set title=%'", Integer.class);
                    if (waiting > 0) break;
                    Thread.sleep(10);
                }
                assertTrue(waiting > 0, "title update must reach the test trigger after changing its row");
                deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5); boolean timeoutObserved = false;
                while (System.nanoTime() < deadline) {
                    String state = jdbc.queryForObject("select state from conversation_title_work where conversation_id=?", String.class, conversation);
                    Integer blocked = jdbc.queryForObject("select count(*) from pg_stat_activity where wait_event_type='Lock' and query like 'update conversation_title_work set state=%'", Integer.class);
                    if ("FAILED".equals(state) || blocked > 0) { timeoutObserved = true; break; }
                    Thread.sleep(10);
                }
                assertTrue(timeoutObserved, "deadline must reach the work row while title commit is held");
                statement.execute("select pg_advisory_unlock(686868)"); lock.commit();
                deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5); String state = "";
                while (System.nanoTime() < deadline) {
                    state = jdbc.queryForObject("select state from conversation_title_work where conversation_id=?", String.class, conversation);
                    int active = jdbc.queryForObject("select count(*) from pg_stat_activity where state='active' and query like 'update conversation set title=%'", Integer.class);
                    if ("FAILED".equals(state) && active == 0) break;
                    Thread.sleep(10);
                }
                assertEquals("FAILED", state);
                assertEquals("New Conversation", jdbc.queryForObject("select title from conversation where id=?", String.class, conversation));
                assertEquals(0L, jdbc.queryForObject("select version from conversation_title_work where conversation_id=?", Long.class, conversation));
            } finally {
                try (var cleanup = lock.createStatement()) { cleanup.execute("select pg_advisory_unlock(686868)"); }
                catch (java.sql.SQLException ignored) { }
                try { lock.rollback(); } catch (java.sql.SQLException ignored) { }
                title.release.countDown();
            }
        } finally {
            jdbc.execute("drop trigger if exists test_title_gate_68 on conversation");
            jdbc.execute("drop function if exists test_title_gate_68()");
        }
    }

    @Test void saturatedTitleWorkersDoNotBlockChatRuns() throws Exception {
        String scope = "title-test-" + UUID.randomUUID();
        jdbc.update("insert into workspace(id,name) values (?,?)", scope, "Synthetic title test");
        jdbc.update("insert into workspace_member(workspace_id,user_id,role,active) values (?,'dev-user','MANAGER',true)", scope);
        jdbc.update("insert into bot (id,name,system_prompt,created_at) values ('bot-general','General Assistant','trusted',CURRENT_TIMESTAMP) on conflict (id) do nothing");
        var ids = new java.util.ArrayList<String>();
        try {
            for (int i = 0; i < 3; i++) {
                var created = json.readTree(post("/graphql", Map.of("query", "mutation($workspace: ID!) { createConversation(botId: \"bot-general\", workspaceId: $workspace) { id } }", "variables", Map.of("workspace", scope))).body());
                String conversation = created.path("data").path("createConversation").path("id").asText(); ids.add(conversation);
                var body = Map.of("threadId", conversation, "runId", UUID.randomUUID().toString(), "state", Map.of(),
                        "messages", List.of(Map.of("id", UUID.randomUUID().toString(), "role", "user", "content", "Synthetic saturation")),
                        "tools", List.of(), "context", List.of(), "forwardedProps", Map.of());
                assertTrue(post("/api/ag-ui", body).body().contains("RUN_FINISHED"));
            }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3); String third = "";
            while (System.nanoTime() < deadline) {
                third = jdbc.queryForObject("select state from conversation_title_work where conversation_id=?", String.class, ids.get(2));
                if ("FAILED".equals(third)) break;
                Thread.sleep(20);
            }
            assertEquals("FAILED", third);
            assertEquals(2, title.calls.get());
            assertEquals("New Conversation", jdbc.queryForObject("select title from conversation where id=?", String.class, ids.get(2)));
        } finally {
            title.release.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (ids.size() >= 2 && System.nanoTime() < deadline) {
                Integer pending = jdbc.queryForObject("select count(*) from conversation_title_work where conversation_id in (?,?) and state='CLAIMED'", Integer.class, ids.get(0), ids.get(1));
                if (pending == 0) break;
                Thread.sleep(20);
            }
        }
    }

    @Test void lateFakeResultAfterTimeoutCannotCommitTitle() throws Exception {
        String scope = "title-test-" + UUID.randomUUID();
        jdbc.update("insert into workspace(id,name) values (?,?)", scope, "Synthetic title test");
        jdbc.update("insert into workspace_member(workspace_id,user_id,role,active) values (?,'dev-user','MANAGER',true)", scope);
        jdbc.update("insert into bot (id,name,system_prompt,created_at) values ('bot-general','General Assistant','trusted',CURRENT_TIMESTAMP) on conflict (id) do nothing");
        var created = json.readTree(post("/graphql", Map.of("query", "mutation($workspace: ID!) { createConversation(botId: \"bot-general\", workspaceId: $workspace) { id } }", "variables", Map.of("workspace", scope))).body());
        String conversation = created.path("data").path("createConversation").path("id").asText();
        var body = Map.of("threadId", conversation, "runId", UUID.randomUUID().toString(), "state", Map.of(),
                "messages", List.of(Map.of("id", UUID.randomUUID().toString(), "role", "user", "content", "Synthetic late result")),
                "tools", List.of(), "context", List.of(), "forwardedProps", Map.of());
        title.ignoreInterrupt = true;
        try {
            assertTrue(post("/api/ag-ui", body).body().contains("RUN_FINISHED"));
            assertTrue(title.entered.await(5, TimeUnit.SECONDS));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(6); String state = "";
            while (System.nanoTime() < deadline) {
                state = jdbc.queryForObject("select state from conversation_title_work where conversation_id=?", String.class, conversation);
                if ("FAILED".equals(state)) break;
                Thread.sleep(20);
            }
            assertEquals("FAILED", state);
            title.release.countDown();
            assertTrue(title.returned.await(5, TimeUnit.SECONDS));
            assertEquals("New Conversation", jdbc.queryForObject("select title from conversation where id=?", String.class, conversation));
        } finally { title.release.countDown(); }
    }

    @Test void heldTitleTimesOutWithoutChangingConversation() throws Exception {
        String scope = "title-test-" + UUID.randomUUID();
        jdbc.update("insert into workspace(id,name) values (?,?)", scope, "Synthetic title test");
        jdbc.update("insert into workspace_member(workspace_id,user_id,role,active) values (?,'dev-user','MANAGER',true)", scope);
        jdbc.update("insert into bot (id,name,system_prompt,created_at) values ('bot-general','General Assistant','trusted',CURRENT_TIMESTAMP) on conflict (id) do nothing");
        var created = json.readTree(post("/graphql", Map.of("query", "mutation($workspace: ID!) { createConversation(botId: \"bot-general\", workspaceId: $workspace) { id } }", "variables", Map.of("workspace", scope))).body());
        String conversation = created.path("data").path("createConversation").path("id").asText();
        var body = Map.of("threadId", conversation, "runId", UUID.randomUUID().toString(), "state", Map.of(),
                "messages", List.of(Map.of("id", UUID.randomUUID().toString(), "role", "user", "content", "Synthetic timeout")),
                "tools", List.of(), "context", List.of(), "forwardedProps", Map.of());
        try {
            assertTrue(post("/api/ag-ui", body).body().contains("RUN_FINISHED"));
            assertTrue(title.entered.await(5, TimeUnit.SECONDS));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(6); String state = "";
            while (System.nanoTime() < deadline) {
                state = jdbc.queryForObject("select state from conversation_title_work where conversation_id=?", String.class, conversation);
                if ("FAILED".equals(state)) break;
                Thread.sleep(20);
            }
            assertEquals("FAILED", state);
            assertEquals("New Conversation", jdbc.queryForObject("select title from conversation where id=?", String.class, conversation));
        } finally { title.release.countDown(); }
    }

    @Test void contentFlushFailureDoesNotDispatchTitle() throws Exception {
        String scope = "title-test-" + UUID.randomUUID();
        jdbc.update("insert into workspace(id,name) values (?,?)", scope, "Synthetic title test");
        jdbc.update("insert into workspace_member(workspace_id,user_id,role,active) values (?,'dev-user','MANAGER',true)", scope);
        jdbc.update("insert into bot (id,name,system_prompt,created_at) values ('bot-general','General Assistant','trusted',CURRENT_TIMESTAMP) on conflict (id) do nothing");
        var created = json.readTree(post("/graphql", Map.of("query", "mutation($workspace: ID!) { createConversation(botId: \"bot-general\", workspaceId: $workspace) { id } }", "variables", Map.of("workspace", scope))).body());
        String conversation = created.path("data").path("createConversation").path("id").asText();
        String run = UUID.randomUUID().toString();
        var body = Map.of("threadId", conversation, "runId", run, "state", Map.of(),
                "messages", List.of(Map.of("id", UUID.randomUUID().toString(), "role", "user", "content", "Synthetic flush")),
                "tools", List.of(), "context", List.of(), "forwardedProps", Map.of());
        TitleFakeConfig.contentFlushFailed = new CountDownLatch(1);
        TitleFakeConfig.failedAsync = null;
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/ag-ui"))
                .header("Content-Type", "application/json").header("X-Test-Flush", "fail-content")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
        var response = http.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
        try {
            assertTrue(TitleFakeConfig.contentFlushFailed.await(5, TimeUnit.SECONDS), "content event must reach the injected flush failure");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            String status = "";
            while (System.nanoTime() < deadline) {
                status = jdbc.queryForObject("select status from chat_run where id=?", String.class, run);
                if ("CANCELLED".equals(status)) break;
                Thread.sleep(20);
            }
            assertEquals("CANCELLED", status);
            assertEquals(0, title.calls.get());
            assertEquals("New Conversation", jdbc.queryForObject("select title from conversation where id=?", String.class, conversation));
        } finally {
            var context = TitleFakeConfig.failedAsync;
            if (context != null) try { context.complete(); } catch (IllegalStateException ignored) { }
            if (response.isDone() && !response.isCompletedExceptionally()) response.join().body().close();
            else response.cancel(true);
        }
    }

    @Test void cancellationBeforeTransportReadyDoesNotDispatchModel() throws Exception {
        String scope = "title-test-" + UUID.randomUUID();
        jdbc.update("insert into workspace(id,name) values (?,?)", scope, "Synthetic title test");
        jdbc.update("insert into workspace_member(workspace_id,user_id,role,active) values (?,'dev-user','MANAGER',true)", scope);
        jdbc.update("insert into bot (id,name,system_prompt,created_at) values ('bot-general','General Assistant','trusted',CURRENT_TIMESTAMP) on conflict (id) do nothing");
        var created = json.readTree(post("/graphql", Map.of("query", "mutation($workspace: ID!) { createConversation(botId: \"bot-general\", workspaceId: $workspace) { id } }", "variables", Map.of("workspace", scope))).body());
        String conversation = created.path("data").path("createConversation").path("id").asText();
        String run = UUID.randomUUID().toString();
        var body = Map.of("threadId", conversation, "runId", run, "state", Map.of(),
                "messages", List.of(Map.of("id", UUID.randomUUID().toString(), "role", "user", "content", "Synthetic readiness")),
                "tools", List.of(), "context", List.of(), "forwardedProps", Map.of());
        TitleFakeConfig.readinessEntered = new CountDownLatch(1);
        TitleFakeConfig.releaseReadiness = new CountDownLatch(1);
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/ag-ui"))
                .header("Content-Type", "application/json").header("X-Test-Readiness", "hold")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
        var result = http.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        try {
            assertTrue(TitleFakeConfig.readinessEntered.await(5, TimeUnit.SECONDS));
            assertEquals(0, chatModel().getRecordedPrompts().size());
            var stop = post("/api/runs/" + run + "/cancel", Map.of());
            assertEquals("CANCELLED", json.readTree(stop.body()).path("status").asText());
            assertEquals(0, title.calls.get());
        } finally { TitleFakeConfig.releaseReadiness.countDown(); title.release.countDown(); }
        assertTrue(result.get(8, TimeUnit.SECONDS).body().contains("cancelled"));
        assertEquals(0, chatModel().getRecordedPrompts().size());
        assertEquals(0, title.calls.get());
    }

    @Test void stopCompletesWhileTitleGeneratorIsHeld() throws Exception {
        String scope = "title-test-" + UUID.randomUUID();
        jdbc.update("insert into workspace(id,name) values (?,?)", scope, "Synthetic title test");
        jdbc.update("insert into workspace_member(workspace_id,user_id,role,active) values (?,'dev-user','MANAGER',true)", scope);
        jdbc.update("insert into bot (id,name,system_prompt,created_at) values ('bot-general','General Assistant','trusted',CURRENT_TIMESTAMP) on conflict (id) do nothing");
        var created = json.readTree(post("/graphql", Map.of("query", "mutation($workspace: ID!) { createConversation(botId: \"bot-general\", workspaceId: $workspace) { id } }", "variables", Map.of("workspace", scope))).body());
        String conversation = created.path("data").path("createConversation").path("id").asText();
        String run = UUID.randomUUID().toString();
        var body = Map.of("threadId", conversation, "runId", run, "state", Map.of(),
                "messages", List.of(Map.of("id", UUID.randomUUID().toString(), "role", "user", "content", "Synthetic stop")),
                "tools", List.of(), "context", List.of(), "forwardedProps", Map.of());
        chatModel().setHoldMode(true);
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/ag-ui"))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
        var result = http.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        try {
            assertTrue(chatModel().awaitSubscribed(5, TimeUnit.SECONDS));
            chatModel().emitChunk("Visible reply");
            assertTrue(title.entered.await(5, TimeUnit.SECONDS));
            var stop = post("/api/runs/" + run + "/cancel", Map.of());
            assertEquals("CANCELLED", json.readTree(stop.body()).path("status").asText());
            assertTrue(result.get(8, TimeUnit.SECONDS).body().contains("cancelled"));
            assertEquals(1, title.calls.get());
        } finally { title.release.countDown(); chatModel().complete(); }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        String saved = "";
        while (System.nanoTime() < deadline) {
            saved = jdbc.queryForObject("select title from conversation where id=?", String.class, conversation);
            if ("Synthetic title".equals(saved)) break;
            Thread.sleep(20);
        }
        assertEquals("Synthetic title", saved);
        assertEquals("CANCELLED", jdbc.queryForObject("select status from chat_run where id=?", String.class, run));
    }

    @Test void failedFirstRunEndsItsTitleSubscriptionWithoutCreatingTitle() throws Exception {
        String scope = "title-test-" + UUID.randomUUID();
        jdbc.update("insert into workspace(id,name) values (?,?)", scope, "Synthetic title test");
        jdbc.update("insert into workspace_member(workspace_id,user_id,role,active) values (?,'dev-user','MANAGER',true)", scope);
        jdbc.update("insert into bot (id,name,system_prompt,created_at) values ('bot-general','General Assistant','trusted',CURRENT_TIMESTAMP) on conflict (id) do nothing");
        var created = json.readTree(post("/graphql", Map.of("query", "mutation($workspace: ID!) { createConversation(botId: \"bot-general\", workspaceId: $workspace) { id } }", "variables", Map.of("workspace", scope))).body());
        String conversation = created.path("data").path("createConversation").path("id").asText();
        var body = Map.of("threadId", conversation, "runId", UUID.randomUUID().toString(), "state", Map.of(),
                "messages", List.of(Map.of("id", UUID.randomUUID().toString(), "role", "user", "content", "Synthetic [ERROR]")),
                "tools", List.of(), "context", List.of(), "forwardedProps", Map.of());
        assertTrue(post("/api/ag-ui", body).body().contains("RUN_ERROR"));
        var subscription = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/graphql"))
                .header("Content-Type", "application/json").header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("query", "subscription($id: ID!) { conversationTitleChanged(conversationId: $id) { id version state } }", "variables", Map.of("id", conversation))))).build();
        var result = http.send(subscription, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, result.statusCode());
        assertTrue(result.body().contains("UNAVAILABLE"), result.body());
        assertTrue(result.body().contains("event:complete"), result.body());
        assertEquals(0, title.calls.get());
        assertEquals("New Conversation", jdbc.queryForObject("select title from conversation where id=?", String.class, conversation));
    }

    @Test void firstVisibleReplyAfterFailedRunUsesFirstSavedUserMessage() throws Exception {
        String scope = "title-test-" + UUID.randomUUID();
        jdbc.update("insert into workspace(id,name) values (?,?)", scope, "Synthetic title test");
        jdbc.update("insert into workspace_member(workspace_id,user_id,role,active) values (?,'dev-user','MANAGER',true)", scope);
        jdbc.update("insert into bot (id,name,system_prompt,created_at) values ('bot-general','General Assistant','trusted',CURRENT_TIMESTAMP) on conflict (id) do nothing");
        var created = json.readTree(post("/graphql", Map.of("query", "mutation($workspace: ID!) { createConversation(botId: \"bot-general\", workspaceId: $workspace) { id } }", "variables", Map.of("workspace", scope))).body());
        String conversation = created.path("data").path("createConversation").path("id").asText();
        var first = Map.of("threadId", conversation, "runId", UUID.randomUUID().toString(), "state", Map.of(),
                "messages", List.of(Map.of("id", UUID.randomUUID().toString(), "role", "user", "content", "First topic [ERROR]")),
                "tools", List.of(), "context", List.of(), "forwardedProps", Map.of());
        assertTrue(post("/api/ag-ui", first).body().contains("RUN_ERROR"));
        assertEquals(0, title.calls.get());
        var second = Map.of("threadId", conversation, "runId", UUID.randomUUID().toString(), "state", Map.of(),
                "messages", List.of(Map.of("id", UUID.randomUUID().toString(), "role", "user", "content", "Second topic")),
                "tools", List.of(), "context", List.of(), "forwardedProps", Map.of());
        try {
            assertTrue(post("/api/ag-ui", second).body().contains("RUN_FINISHED"));
            assertTrue(title.entered.await(5, TimeUnit.SECONDS));
            assertEquals("First topic [ERROR]", title.input);
            assertEquals(1, title.calls.get());
        } finally { title.release.countDown(); }
    }

    @Test void failureAndCancellationBeforeVisibleTextKeepDefaultTitle() throws Exception {
        for (boolean cancel : new boolean[]{false, true}) {
            chatModel().reset(); title.reset();
            String scope = "title-test-" + UUID.randomUUID();
            jdbc.update("insert into workspace(id,name) values (?,?)", scope, "Synthetic title test");
            jdbc.update("insert into workspace_member(workspace_id,user_id,role,active) values (?,'dev-user','MANAGER',true)", scope);
            jdbc.update("insert into bot (id,name,system_prompt,created_at) values ('bot-general','General Assistant','trusted',CURRENT_TIMESTAMP) on conflict (id) do nothing");
            var created = json.readTree(post("/graphql", Map.of("query", "mutation($workspace: ID!) { createConversation(botId: \"bot-general\", workspaceId: $workspace) { id } }", "variables", Map.of("workspace", scope))).body());
            assertFalse(created.has("errors"), created.toString());
            String conversation = created.path("data").path("createConversation").path("id").asText();
            String run = UUID.randomUUID().toString();
            var body = Map.of("threadId", conversation, "runId", run, "state", Map.of(),
                    "messages", List.of(Map.of("id", UUID.randomUUID().toString(), "role", "user", "content", "Synthetic prefirst")),
                    "tools", List.of(), "context", List.of(), "forwardedProps", Map.of());
            chatModel().setHoldMode(true);
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/ag-ui"))
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
            var result = http.sendAsync(request, HttpResponse.BodyHandlers.ofString());
            try {
                assertTrue(chatModel().awaitSubscribed(5, TimeUnit.SECONDS));
                if (cancel) assertEquals(200, post("/api/runs/" + run + "/cancel", Map.of()).statusCode());
                else chatModel().error(new IllegalStateException("synthetic failure"));
                String terminal = result.get(8, TimeUnit.SECONDS).body();
                assertTrue(terminal.contains(cancel ? "RUN_FINISHED" : "RUN_ERROR"), terminal);
                if (cancel) assertTrue(terminal.contains("cancelled"), terminal);
                assertEquals(0, title.calls.get());
                var read = json.readTree(post("/graphql", Map.of("query", "query($id: ID!) { conversation(id: $id) { title } }", "variables", Map.of("id", conversation))).body());
                assertEquals("New Conversation", read.path("data").path("conversation").path("title").asText());
            } finally { title.release.countDown(); chatModel().complete(); }
        }
    }

    @Test void committedTitleNotifiesIndependentlyAfterRun() throws Exception {
        String scope = "title-test-" + UUID.randomUUID();
        jdbc.update("insert into workspace(id,name) values (?,?)", scope, "Synthetic title test");
        jdbc.update("insert into workspace_member(workspace_id,user_id,role,active) values (?,'dev-user','MANAGER',true)", scope);
        jdbc.update("insert into bot (id,name,system_prompt,created_at) values ('bot-general','General Assistant','trusted',CURRENT_TIMESTAMP) on conflict (id) do nothing");
        var created = json.readTree(post("/graphql", Map.of("query", "mutation($workspace: ID!) { createConversation(botId: \"bot-general\", workspaceId: $workspace) { id } }", "variables", Map.of("workspace", scope))).body());
        assertFalse(created.has("errors"), created.toString());
        String conversation = created.path("data").path("createConversation").path("id").asText();
        var subscription = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/graphql"))
                .header("Content-Type", "application/json").header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("query", "subscription($conversation: ID!) { conversationTitleChanged(conversationId: $conversation) { id version } }", "variables", Map.of("conversation", conversation))))).build();
        var notification = http.sendAsync(subscription, HttpResponse.BodyHandlers.ofInputStream());
        String run = UUID.randomUUID().toString();
        var body = Map.of("threadId", conversation, "runId", run, "state", Map.of(),
                "messages", List.of(Map.of("id", UUID.randomUUID().toString(), "role", "user", "content", "Synthetic notification")),
                "tools", List.of(), "context", List.of(), "forwardedProps", Map.of());
        try {
            var result = post("/api/ag-ui", body);
            assertTrue(result.body().contains("RUN_FINISHED"), result.body());
            assertTrue(title.entered.await(5, TimeUnit.SECONDS));
            title.release.countDown();
            var response = notification.get(8, TimeUnit.SECONDS);
            assertEquals(200, response.statusCode());
            var reader = new java.io.BufferedReader(new java.io.InputStreamReader(response.body(), java.nio.charset.StandardCharsets.UTF_8));
            String line; StringBuilder events = new StringBuilder();
            do { line = reader.readLine(); assertNotNull(line, () -> "subscription ended: " + events); events.append(line).append('\n'); } while (!line.contains(conversation));
            var change = json.readTree(line.substring("data:".length())).path("data").path("conversationTitleChanged");
            assertEquals(conversation, change.path("id").asText());
            assertEquals("1", change.path("version").asText());
            assertEquals("Synthetic title", jdbc.queryForObject("select title from conversation where id=?", String.class, conversation));
            assertEquals(1, jdbc.queryForObject("select count(*) from chat_run where conversation_id=?", Integer.class, conversation));
            assertEquals(2, jdbc.queryForObject("select count(*) from message where conversation_id=?", Integer.class, conversation));
            var read = json.readTree(post("/graphql", Map.of("query", "query($id: ID!) { conversation(id: $id) { title } }", "variables", Map.of("id", conversation))).body());
            assertEquals("Synthetic title", read.path("data").path("conversation").path("title").asText());
            reader.close();
        } finally { title.release.countDown(); notification.cancel(true); }
    }

    @Test void streamedReplyMakesTitleEligibleOnlyAfterNonblankText() throws Exception {
        String scope = "title-test-" + UUID.randomUUID();
        jdbc.update("insert into workspace(id,name) values (?,?)", scope, "Synthetic title test");
        jdbc.update("insert into workspace_member(workspace_id,user_id,role,active) values (?,'dev-user','MANAGER',true)", scope);
        jdbc.update("insert into bot (id,name,system_prompt,created_at) values ('bot-general','General Assistant','trusted',CURRENT_TIMESTAMP) on conflict (id) do nothing");
        var created = json.readTree(post("/graphql", Map.of("query", "mutation($workspace: ID!) { createConversation(botId: \"bot-general\", workspaceId: $workspace) { id } }", "variables", Map.of("workspace", scope))).body());
        assertFalse(created.has("errors"), created.toString());
        String conversation = created.path("data").path("createConversation").path("id").asText();
        String run = UUID.randomUUID().toString();
        var body = Map.of("threadId", conversation, "runId", run, "state", Map.of(),
                "messages", List.of(Map.of("id", UUID.randomUUID().toString(), "role", "user", "content", "Synthetic stream")),
                "tools", List.of(), "context", List.of(), "forwardedProps", Map.of());
        chatModel().setHoldMode(true);
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/ag-ui"))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
        var stream = http.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
        try {
            assertTrue(chatModel().awaitSubscribed(5, TimeUnit.SECONDS));
            var response = stream.get(5, TimeUnit.SECONDS);
            assertEquals(200, response.statusCode());
            var reader = new java.io.BufferedReader(new java.io.InputStreamReader(response.body(), java.nio.charset.StandardCharsets.UTF_8));
            chatModel().emitChunk("   ");
            String line;
            do { line = reader.readLine(); assertNotNull(line); } while (!line.contains("TEXT_MESSAGE_CONTENT"));
            assertEquals(0, title.calls.get(), "whitespace must not activate title generation");
            chatModel().emitChunk("Visible reply");
            do { line = reader.readLine(); assertNotNull(line); } while (!line.contains("Visible reply"));
            assertTrue(title.entered.await(5, TimeUnit.SECONDS));
            chatModel().emitChunk("Continued reply");
            do { line = reader.readLine(); assertNotNull(line); } while (!line.contains("Continued reply"));
            chatModel().complete();
            do { line = reader.readLine(); assertNotNull(line); } while (!line.contains("RUN_FINISHED"));
            assertEquals(1, title.calls.get());
            reader.close();
        } finally { title.release.countDown(); chatModel().complete(); }
    }

    @Test void firstVisibleReplyDispatchesTitleWithoutHoldingRunCompletion() throws Exception {
        chatModel().reset();
        String scope = "title-test-" + UUID.randomUUID();
        jdbc.update("insert into workspace(id,name) values (?,?)", scope, "Synthetic title test");
        jdbc.update("insert into workspace_member(workspace_id,user_id,role,active) values (?,'dev-user','MANAGER',true)", scope);
        jdbc.update("insert into bot (id,name,system_prompt,created_at) values ('bot-general','General Assistant','trusted',CURRENT_TIMESTAMP) on conflict (id) do nothing");
        var created = post("/graphql", Map.of("query", "mutation($workspace: ID!) { createConversation(botId: \"bot-general\", workspaceId: $workspace) { id title } }", "variables", Map.of("workspace", scope)));
        var node = json.readTree(created.body());
        assertFalse(node.has("errors"), node.toString());
        String conversation = node.path("data").path("createConversation").path("id").asText();
        assertEquals("New Conversation", node.path("data").path("createConversation").path("title").asText());
        String run = UUID.randomUUID().toString();
        var request = Map.of("threadId", conversation, "runId", run, "state", Map.of(),
                "messages", List.of(Map.of("id", UUID.randomUUID().toString(), "role", "user", "content", "Synthetic first message")),
                "tools", List.of(), "context", List.of(), "forwardedProps", Map.of());
        try {
            var result = post("/api/ag-ui", request);
            assertEquals(200, result.statusCode());
            assertTrue(result.body().contains("TEXT_MESSAGE_CONTENT"), result.body());
            assertTrue(result.body().contains("RUN_FINISHED"), result.body());
            assertTrue(title.entered.await(5, TimeUnit.SECONDS), "title generation must start after visible reply");
            assertEquals(1, title.calls.get());
            assertEquals("Synthetic first message", title.input);
            assertTrue(title.virtual);
            assertFalse(title.transactionActive);
        } finally {
            title.release.countDown();
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        String observed = "";
        while (System.nanoTime() < deadline) {
            var result = post("/graphql", Map.of("query", "query($id: ID!) { conversation(id: $id) { id title } }", "variables", Map.of("id", conversation)));
            observed = json.readTree(result.body()).path("data").path("conversation").path("title").asText();
            if ("Synthetic title".equals(observed)) break;
            Thread.sleep(20);
        }
        assertEquals("Synthetic title", observed);
        var duplicate = post("/api/ag-ui", request);
        assertEquals(409, duplicate.statusCode());
        assertEquals(1, title.calls.get());
        assertEquals(1, jdbc.queryForObject("select count(*) from chat_run where conversation_id=?", Integer.class, conversation));
        assertEquals(2, jdbc.queryForObject("select count(*) from message where conversation_id=?", Integer.class, conversation));
    }
}
