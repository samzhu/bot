package io.github.samzhu.bot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genai.Client;
import com.google.genai.gaos.models.interactions.CreateModelInteraction;
import com.google.genai.gaos.models.interactions.InteractionsInput;
import com.google.genai.gaos.models.operations.CreateInteractionRequestBody;
import com.google.genai.gaos.utils.RetryConfig;
import com.google.genai.types.ClientOptions;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.HttpRetryOptions;
import com.sun.net.httpserver.HttpServer;
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
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

@io.github.samzhu.bot.UseTestFakes
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({TestcontainersConfiguration.class, ConversationTitleBudgetHttpIntegrationTests.LoopbackConfig.class})
@ActiveProfiles("test")
@org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
class ConversationTitleBudgetHttpIntegrationTests {
    static class Loopback implements AutoCloseable {
        final HttpServer server;
        final AtomicInteger requests = new AtomicInteger();
        volatile boolean hold;
        volatile boolean success;
        volatile boolean invalidTitle;
        volatile boolean incompleteUsage;
        volatile boolean parityUsage;
        volatile String failureMode = "503";
        volatile boolean validRequest;
        volatile boolean virtualThread;
        volatile boolean transactionActive;
        volatile CountDownLatch entered = new CountDownLatch(1);
        volatile CountDownLatch release = new CountDownLatch(1);
        final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
        Loopback() throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(workers);
            server.createContext("/", exchange -> {
                requests.incrementAndGet();
                var request = new ObjectMapper().readTree(exchange.getRequestBody().readAllBytes());
                validRequest = "POST".equals(exchange.getRequestMethod()) && exchange.getRequestURI().getPath().endsWith("/interactions")
                    && "gemini-3.5-flash-lite".equals(request.path("model").asText()) && request.has("store") && !request.path("store").asBoolean(true)
                    && !request.path("background").asBoolean(true) && !request.path("stream").asBoolean(true)
                    && request.path("generation_config").path("max_output_tokens").asInt() == 64
                    && "minimal".equals(request.path("generation_config").path("thinking_level").asText())
                    && !request.has("tools") && !request.has("previous_interaction_id")
                    && "Synthetic ledger question".equals(request.path("input").asText());
                entered.countDown();
                if (hold) {
                    try { release.await(30, TimeUnit.SECONDS); }
                    catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
                }
                if (!success && "disconnect".equals(failureMode)) { exchange.close(); return; }
                if (!success && "redirect".equals(failureMode)) {
                    exchange.getResponseHeaders().set("Location", base() + "/must-not-repeat");
                    exchange.sendResponseHeaders(307, -1); exchange.close(); return;
                }
                byte[] body = (success
                    ? "{\"id\":\"synthetic-response\",\"status\":\"completed\",\"steps\":[{\"type\":\"model_output\",\"content\":[{\"type\":\"text\",\"text\":\"Synthetic title\"}]}],\"usage\":{\"total_input_tokens\":7,\"total_cached_tokens\":0,\"total_output_tokens\":3,\"total_thought_tokens\":1,\"total_tool_use_tokens\":0,\"total_tokens\":11}}"
                    : "{\"error\":{\"code\":503,\"message\":\"synthetic\",\"status\":\"UNAVAILABLE\"}}" ).getBytes(StandardCharsets.UTF_8);
                if (success && parityUsage) body = new String(body, StandardCharsets.UTF_8).replace("\"total_input_tokens\":7", "\"total_input_tokens\":100").replace("\"total_cached_tokens\":0", "\"total_cached_tokens\":20").replace("\"total_output_tokens\":3", "\"total_output_tokens\":10").replace("\"total_thought_tokens\":1", "\"total_thought_tokens\":5").replace("\"total_tokens\":11", "\"total_tokens\":115").getBytes(StandardCharsets.UTF_8);
                if (success && incompleteUsage) body = new String(body, StandardCharsets.UTF_8).replace("\"total_thought_tokens\":1,", "").getBytes(StandardCharsets.UTF_8);
                if (success && invalidTitle) body = new String(body, StandardCharsets.UTF_8).replace("Synthetic title", "   ").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                if (!success) exchange.getResponseHeaders().set("Retry-After", "0");
                exchange.sendResponseHeaders(success ? 200 : 503, body.length);
                try (var output = exchange.getResponseBody()) { output.write(body); }
            });
            server.start();
        }
        String base() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
        @Override public void close() { release.countDown(); server.stop(0); workers.close(); }
    }
    @TestConfiguration(proxyBeanMethods = false)
    static class LoopbackConfig {
        @Bean Loopback loopback(Environment environment) throws Exception {
            if (!environment.matchesProfiles("test") || environment.getProperty("bot-ai-provider") != null) throw new IllegalStateException("test profile required");
            return new Loopback();
        }
        @Bean(destroyMethod = "close") Client localInteractionClient(Loopback loopback, Environment environment) {
            if (!environment.matchesProfiles("test") || environment.getProperty("bot-ai-provider") != null) throw new IllegalStateException("test profile required");
            int port = loopback.server.getAddress().getPort();
            var http = io.github.samzhu.bot.execution.internal.model.TitleHttpTransport.builder()
                    .proxy(Proxy.NO_PROXY).callTimeout(java.time.Duration.ofSeconds(15)).addInterceptor(chain -> {
                        var url = chain.request().url();
                        if (!"http".equals(url.scheme()) || !"127.0.0.1".equals(url.host()) || url.port() != port)
                            throw new java.io.IOException("test transport forbids non-loopback target");
                        return chain.proceed(chain.request());
                    }).build();
            var options = HttpOptions.builder().baseUrl(loopback.base()).timeout(15000)
                    .retryOptions(HttpRetryOptions.builder().attempts(1).build()).build();
            if (!options.baseUrl().orElseThrow().equals(loopback.base())) throw new IllegalStateException("loopback required");
            return Client.builder().enterprise(false).apiKey("synthetic-test-only")
                    .httpOptions(options).clientOptions(ClientOptions.builder().customHttpClient(http).build()).build();
        }
        @Bean AtomicInteger titleAdapterInvocations() { return new AtomicInteger(); }
        @Bean ConversationTitleGenerator titleFakeUsingLocalSdk(Client localInteractionClient, AtomicInteger titleAdapterInvocations, Loopback loopback) {
            var actual = new io.github.samzhu.bot.execution.internal.model.GeminiConversationTitleGenerator(localInteractionClient);
            return new ConversationTitleGenerator() {
                @Override public String generate(String input) throws Exception { return generateResult(input).title(); }
                @Override public Result generateResult(String input) throws Exception {
                    titleAdapterInvocations.incrementAndGet();
                    loopback.virtualThread = Thread.currentThread().isVirtual();
                    loopback.transactionActive = org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive();
                    return actual.generateResult(input);
                }
            };
        }
    }
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired PostgreSQLContainer postgres;
    @Autowired Loopback loopback;
    @Autowired Environment environment;
    @Autowired AtomicInteger titleAdapterInvocations;
    @Autowired io.github.samzhu.bot.usage.RunRecoverySweep recoverySweep;
    @Autowired io.github.samzhu.bot.usage.internal.pricing.CatalogActivation catalog;
    @TestBean(name="mockChatModel", methodName="chatModel", enforceOverride=true) ChatModel chat;
    static ControlledChatModel chatModel() { return ControlledChatModel.getInstance(); }
    static final java.util.concurrent.atomic.AtomicBoolean REAL_FIXTURE = new java.util.concurrent.atomic.AtomicBoolean();
    static final AtomicInteger PARTITION_READS = new AtomicInteger();
    @TestBean(name="activeTitlePartition", methodName="testTitlePartition", enforceOverride=true)
    io.github.samzhu.bot.usage.ConversationTitlePartition testPartition;
    static io.github.samzhu.bot.usage.ConversationTitlePartition testTitlePartition() {
        return () -> { PARTITION_READS.incrementAndGet(); return REAL_FIXTURE.get() ? io.github.samzhu.bot.usage.WorkspaceAdmission.Partition.REAL : io.github.samzhu.bot.usage.WorkspaceAdmission.Partition.SIMULATION; };
    }
    @TestBean(name="fakeUsageCaptureAdapter", methodName="testChatUsageAdapter", enforceOverride=true)
    io.github.samzhu.bot.execution.internal.capture.UsageCaptureAdapter fakeUsage;
    static io.github.samzhu.bot.execution.internal.capture.UsageCaptureAdapter testChatUsageAdapter() {
        var fake = new io.github.samzhu.bot.execution.internal.capture.FakeUsageCaptureAdapter();
        return new io.github.samzhu.bot.execution.internal.capture.UsageCaptureAdapter() {
            @Override public io.github.samzhu.bot.usage.UsageLedger.Dispatch dispatch(String run, String workspace, String actor) {
                var original = fake.dispatch(run, workspace, actor);
                return REAL_FIXTURE.get() ? new io.github.samzhu.bot.usage.UsageLedger.Dispatch(run,workspace,actor,
                    original.provider(),original.endpoint(),original.model(),original.channel(),original.adapterVersion(),false,original.attemptCoverage()) : original;
            }
            @Override public java.util.Optional<io.github.samzhu.bot.usage.UsageLedger.Snapshot> capture(org.springframework.ai.chat.model.ChatResponse r, String id, long n) { return fake.capture(r,id,n); }
        };
    }
    static final java.util.concurrent.atomic.AtomicReference<java.time.Instant> TEST_NOW = new java.util.concurrent.atomic.AtomicReference<>();
    @TestBean(name="usageClock", methodName="testClock", enforceOverride=true) java.time.Clock usageClock;
    static java.time.Clock testClock() {
        return new java.time.Clock() {
            @Override public java.time.ZoneId getZone() { return java.time.ZoneOffset.UTC; }
            @Override public java.time.Clock withZone(java.time.ZoneId zone) { return java.time.Clock.fixed(instant(), zone); }
            @Override public java.time.Instant instant() { var now=TEST_NOW.get(); return now == null ? java.time.Instant.now() : now; }
        };
    }
    @BeforeEach void resetPartition() { REAL_FIXTURE.set(false); PARTITION_READS.set(0); TEST_NOW.set(null); }
    final HttpClient http = HttpClient.newHttpClient();
    final ObjectMapper json = new ObjectMapper();
    HttpRequest request(String path, Object body) throws Exception {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
    }
    @Test void budgetDenialAfterChatAdmissionMakesNoTitleProviderRequest() throws Exception {
        assertTrue(environment.matchesProfiles("test") && environment.getProperty("bot-ai-provider") == null);
        try (var connection = dataSource.getConnection()) { assertEquals(postgres.getJdbcUrl(), connection.getMetaData().getURL()); }
        loopback.requests.set(0);
        assertEquals(0, loopback.requests.get());
        chatModel().reset(); chatModel().setHoldMode(true);
        String scope = "title-budget-" + UUID.randomUUID();
        jdbc.update("insert into workspace(id,name) values (?,?)", scope, "Synthetic budget");
        jdbc.update("insert into workspace_member(workspace_id,user_id,role,active) values (?,'dev-user','MANAGER',true)", scope);
        jdbc.update("insert into bot(id,name,system_prompt,created_at) values ('bot-general','General','test',current_timestamp) on conflict(id) do nothing");
        var create = http.send(request("/graphql", Map.of("query", "mutation($workspace: ID!) { createConversation(botId: \"bot-general\", workspaceId: $workspace) { id } }", "variables", Map.of("workspace", scope))), HttpResponse.BodyHandlers.ofString());
        String id = json.readTree(create.body()).path("data").path("createConversation").path("id").asText();
        assertFalse(id.isBlank(), create.body());
        String run = UUID.randomUUID().toString();
        var body = Map.of("threadId", id, "runId", run, "state", Map.of(),
                "messages", List.of(Map.of("id", UUID.randomUUID().toString(), "role", "user", "content", "Synthetic first question")),
                "tools", List.of(), "context", List.of(), "forwardedProps", Map.of());
        var pending = http.sendAsync(request("/api/ag-ui", body), HttpResponse.BodyHandlers.ofString());
        try {
            assertTrue(chatModel().awaitSubscribed(8, TimeUnit.SECONDS), "chat must already be admitted");
            assertEquals(1, jdbc.update("update workspace_monthly_budget set enabled=true, cap_usd=0 where workspace_id=? and partition='SIMULATION'", scope));
            chatModel().emitChunk("visible response"); chatModel().complete();
            String events = pending.get(10, TimeUnit.SECONDS).body();
            assertTrue(events.contains("TEXT_MESSAGE_CONTENT"), events);
            assertTrue(events.contains("RUN_FINISHED"), events);
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
            String state = "";
            while (System.nanoTime() < end) {
                state = jdbc.queryForObject("select state from conversation_title_work where conversation_id=?", String.class, id);
                if ("FAILED".equals(state) || "DONE".equals(state)) break;
                Thread.sleep(20);
            }
            assertEquals("FAILED", state);
            assertEquals(0, loopback.requests.get(), "budget denial must stop the title before any provider HTTP request");
            var read = http.send(request("/graphql", Map.of("query", "query($id: ID!) { conversation(id: $id) { title } }", "variables", Map.of("id", id))), HttpResponse.BodyHandlers.ofString());
            assertEquals("New Conversation", json.readTree(read.body()).path("data").path("conversation").path("title").asText());
        } finally { chatModel().complete(); }
    }
    @Test void fractionalRatesReserveEnoughForIndividuallyRoundedLines() throws Exception {
        assertTrue(environment.matchesProfiles("test") && environment.getProperty("bot-ai-provider") == null);
        try (var connection = dataSource.getConnection()) { assertEquals(postgres.getJdbcUrl(), connection.getMetaData().getURL()); }
        loopback.requests.set(0);
        assertEquals(0, loopback.requests.get());
        chatModel().reset(); chatModel().setHoldMode(true);
        String scope = "title-budget-" + UUID.randomUUID();
        jdbc.update("insert into workspace(id,name) values (?,?)", scope, "Synthetic budget");
        jdbc.update("insert into workspace_member(workspace_id,user_id,role,active) values (?,'dev-user','MANAGER',true)", scope);
        jdbc.update("insert into bot(id,name,system_prompt,created_at) values ('bot-general','General','test',current_timestamp) on conflict(id) do nothing");
        var price = new io.github.samzhu.bot.pricing.PriceSnapshot("GOOGLE", "interactions", "gemini-3.5-flash-lite", "GEMINI_API", "synthetic", Map.of("input","0.0000006", "cache_read","0.0000006", "output","0.0000006", "thoughts","0.0000006"));
        catalog.publish("fractional-bound-" + UUID.randomUUID(), "synthetic-test-only", List.of(new io.github.samzhu.bot.pricing.PriceCatalog.Entry(price, java.time.Instant.now().minusSeconds(60), java.time.Instant.now().plusSeconds(3600))));
        var create = http.send(request("/graphql", Map.of("query", "mutation($workspace: ID!) { createConversation(botId: \"bot-general\", workspaceId: $workspace) { id } }", "variables", Map.of("workspace", scope))), HttpResponse.BodyHandlers.ofString());
        String id = json.readTree(create.body()).path("data").path("createConversation").path("id").asText();
        assertFalse(id.isBlank(), create.body());
        String run = UUID.randomUUID().toString();
        var body = Map.of("threadId", id, "runId", run, "state", Map.of(),
                "messages", List.of(Map.of("id", UUID.randomUUID().toString(), "role", "user", "content", "Synthetic first question")),
                "tools", List.of(), "context", List.of(), "forwardedProps", Map.of());
        var pending = http.sendAsync(request("/api/ag-ui", body), HttpResponse.BodyHandlers.ofString());
        try {
            assertTrue(chatModel().awaitSubscribed(8, TimeUnit.SECONDS), "chat must already be admitted");
            assertEquals(1, jdbc.update("update workspace_monthly_budget set enabled=true, cap_usd=0.000000629184 where workspace_id=? and partition='SIMULATION'", scope));
            chatModel().emitChunk("visible response"); chatModel().complete();
            String events = pending.get(10, TimeUnit.SECONDS).body();
            assertTrue(events.contains("TEXT_MESSAGE_CONTENT"), events);
            assertTrue(events.contains("RUN_FINISHED"), events);
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
            String state = "";
            while (System.nanoTime() < end) {
                state = jdbc.queryForObject("select state from conversation_title_work where conversation_id=?", String.class, id);
                if ("FAILED".equals(state) || "DONE".equals(state)) break;
                Thread.sleep(20);
            }
            assertEquals("FAILED", state);
            // At 1,048,576 input (1 uncached) and 64 output (31 visible), the four
            // HALF_EVEN rounded lines total 0.000000629185, above the configured cap.
            assertEquals(0, loopback.requests.get(), "the reservation must cover the sum of rounded lines");
            var read = http.send(request("/graphql", Map.of("query", "query($id: ID!) { conversation(id: $id) { title } }", "variables", Map.of("id", id))), HttpResponse.BodyHandlers.ofString());
            assertEquals("New Conversation", json.readTree(read.body()).path("data").path("conversation").path("title").asText());
        } finally { chatModel().complete(); }
    }
    @Test void monthlyDenialReasonIsAuthorizedAndDoesNotDispatch() throws Exception {
        assertTrue(environment.matchesProfiles("test") && environment.getProperty("bot-ai-provider") == null);
        try (var connection = dataSource.getConnection()) { assertEquals(postgres.getJdbcUrl(), connection.getMetaData().getURL()); }
        loopback.requests.set(0);
        assertEquals(0, loopback.requests.get());
        chatModel().reset(); chatModel().setHoldMode(true);
        String scope = "title-budget-" + UUID.randomUUID();
        jdbc.update("insert into workspace(id,name) values (?,?)", scope, "Synthetic budget");
        jdbc.update("insert into workspace_member(workspace_id,user_id,role,active) values (?,'dev-user','MANAGER',true)", scope);
        jdbc.update("insert into bot(id,name,system_prompt,created_at) values ('bot-general','General','test',current_timestamp) on conflict(id) do nothing");
        var create = http.send(request("/graphql", Map.of("query", "mutation($workspace: ID!) { createConversation(botId: \"bot-general\", workspaceId: $workspace) { id } }", "variables", Map.of("workspace", scope))), HttpResponse.BodyHandlers.ofString());
        String id = json.readTree(create.body()).path("data").path("createConversation").path("id").asText();
        assertFalse(id.isBlank(), create.body());
        String run = UUID.randomUUID().toString();
        var body = Map.of("threadId", id, "runId", run, "state", Map.of(),
                "messages", List.of(Map.of("id", UUID.randomUUID().toString(), "role", "user", "content", "Synthetic first question")),
                "tools", List.of(), "context", List.of(), "forwardedProps", Map.of());
        var pending = http.sendAsync(request("/api/ag-ui", body), HttpResponse.BodyHandlers.ofString());
        try {
            assertTrue(chatModel().awaitSubscribed(8, TimeUnit.SECONDS), "chat must already be admitted");
            assertEquals(1, jdbc.update("update workspace_monthly_budget set enabled=true, cap_usd=0 where workspace_id=? and partition='SIMULATION'", scope));
            chatModel().emitChunk("visible response"); chatModel().complete();
            String events = pending.get(10, TimeUnit.SECONDS).body();
            assertTrue(events.contains("TEXT_MESSAGE_CONTENT"), events);
            assertTrue(events.contains("RUN_FINISHED"), events);
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
            String state = "";
            while (System.nanoTime() < end) {
                state = jdbc.queryForObject("select state from conversation_title_work where conversation_id=?", String.class, id);
                if ("FAILED".equals(state) || "DONE".equals(state)) break;
                Thread.sleep(20);
            }
            assertEquals("FAILED", state);
            assertEquals(0, loopback.requests.get(), "budget denial must stop the title before any provider HTTP request");
            var read = http.send(request("/graphql", Map.of("query", "query($id: ID!) { conversation(id: $id) { title } }", "variables", Map.of("id", id))), HttpResponse.BodyHandlers.ofString());
            assertEquals("New Conversation", json.readTree(read.body()).path("data").path("conversation").path("title").asText());
            var subscription = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/graphql"))
                    .header("Content-Type", "application/json").header("Accept", "text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("query", "subscription($id: ID!) { conversationTitleChanged(conversationId: $id) { id state reason } }", "variables", Map.of("id", id))))).build();
            var notice = http.send(subscription, HttpResponse.BodyHandlers.ofString());
            assertTrue(notice.body().contains("MONTH_CAP"), notice.body());
            jdbc.update("update workspace_member set active=false where workspace_id=? and user_id='dev-user'", scope);
            var forbidden = http.send(subscription, HttpResponse.BodyHandlers.ofString());
            assertFalse(forbidden.body().contains("MONTH_CAP"), forbidden.body());
            assertTrue(forbidden.body().contains("errors"), forbidden.body());
        } finally { chatModel().complete(); }
    }
    @Test void missingRateDenialIsVisibleAndDoesNotDispatch() throws Exception {
        assertTrue(environment.matchesProfiles("test") && environment.getProperty("bot-ai-provider") == null);
        try (var connection = dataSource.getConnection()) { assertEquals(postgres.getJdbcUrl(), connection.getMetaData().getURL()); }
        loopback.requests.set(0);
        assertEquals(0, loopback.requests.get());
        chatModel().reset(); chatModel().setHoldMode(true);
        String scope = "title-budget-" + UUID.randomUUID();
        jdbc.update("insert into workspace(id,name) values (?,?)", scope, "Synthetic budget");
        jdbc.update("insert into workspace_member(workspace_id,user_id,role,active) values (?,'dev-user','MANAGER',true)", scope);
        jdbc.update("insert into bot(id,name,system_prompt,created_at) values ('bot-general','General','test',current_timestamp) on conflict(id) do nothing");
        var price = new io.github.samzhu.bot.pricing.PriceSnapshot("GOOGLE", "generateContent", "other-model", "GEMINI_API", "synthetic", Map.of("input","1"));
        catalog.publish("missing-title-rate-" + UUID.randomUUID(), "synthetic-test-only", List.of(new io.github.samzhu.bot.pricing.PriceCatalog.Entry(price, java.time.Instant.now().minusSeconds(60), java.time.Instant.now().plusSeconds(3600))));
        var create = http.send(request("/graphql", Map.of("query", "mutation($workspace: ID!) { createConversation(botId: \"bot-general\", workspaceId: $workspace) { id } }", "variables", Map.of("workspace", scope))), HttpResponse.BodyHandlers.ofString());
        String id = json.readTree(create.body()).path("data").path("createConversation").path("id").asText();
        assertFalse(id.isBlank(), create.body());
        String run = UUID.randomUUID().toString();
        var body = Map.of("threadId", id, "runId", run, "state", Map.of(),
                "messages", List.of(Map.of("id", UUID.randomUUID().toString(), "role", "user", "content", "Synthetic first question")),
                "tools", List.of(), "context", List.of(), "forwardedProps", Map.of());
        var pending = http.sendAsync(request("/api/ag-ui", body), HttpResponse.BodyHandlers.ofString());
        try {
            assertTrue(chatModel().awaitSubscribed(8, TimeUnit.SECONDS), "chat must already be admitted");
            assertEquals(1, jdbc.update("update workspace_monthly_budget set enabled=true, cap_usd=3 where workspace_id=? and partition='SIMULATION'", scope));
            chatModel().emitChunk("visible response");
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
            String state = "";
            while (System.nanoTime() < end) {
                state = jdbc.queryForObject("select state from conversation_title_work where conversation_id=?", String.class, id);
                if ("FAILED".equals(state) || "DONE".equals(state)) break;
                Thread.sleep(20);
            }
            assertEquals("FAILED", state);
            chatModel().complete();
            String events = pending.get(10, TimeUnit.SECONDS).body();
            assertTrue(events.contains("TEXT_MESSAGE_CONTENT") && events.contains("RUN_FINISHED"), events);
            assertEquals(0, loopback.requests.get(), "budget denial must stop the title before any provider HTTP request");
            var read = http.send(request("/graphql", Map.of("query", "query($id: ID!) { conversation(id: $id) { title } }", "variables", Map.of("id", id))), HttpResponse.BodyHandlers.ofString());
            assertEquals("New Conversation", json.readTree(read.body()).path("data").path("conversation").path("title").asText());
            var subscription = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/graphql"))
                    .header("Content-Type", "application/json").header("Accept", "text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("query", "subscription($id: ID!) { conversationTitleChanged(conversationId: $id) { id state reason } }", "variables", Map.of("id", id))))).build();
            var notice = http.send(subscription, HttpResponse.BodyHandlers.ofString());
            assertTrue(notice.body().contains("RATE_UNAVAILABLE"), notice.body());
            jdbc.update("update workspace_member set active=false where workspace_id=? and user_id='dev-user'", scope);
            var forbidden = http.send(subscription, HttpResponse.BodyHandlers.ofString());
            assertFalse(forbidden.body().contains("RATE_UNAVAILABLE"), forbidden.body());
            assertTrue(forbidden.body().contains("errors"), forbidden.body());
        } finally { chatModel().complete(); }
    }
    @Test void concurrentTitlesReserveSharedBudgetBeforeSending() throws Exception {
        assertTrue(environment.matchesProfiles("test") && environment.getProperty("bot-ai-provider") == null);
        try (var connection = dataSource.getConnection()) { assertEquals(postgres.getJdbcUrl(), connection.getMetaData().getURL()); }
        chatModel().reset(); chatModel().setHoldMode(true);
        loopback.requests.set(0); loopback.hold = true; loopback.entered = new CountDownLatch(1); loopback.release = new CountDownLatch(1);
        String scope = "title-concurrent-" + UUID.randomUUID();
        jdbc.update("insert into workspace(id,name) values (?,?)", scope, "Synthetic concurrent budget");
        jdbc.update("insert into workspace_member(workspace_id,user_id,role,active) values (?,'dev-user','MANAGER',true)", scope);
        jdbc.update("insert into bot(id,name,system_prompt,created_at) values ('bot-general','General','test',current_timestamp) on conflict(id) do nothing");
        var price = new io.github.samzhu.bot.pricing.PriceSnapshot("GOOGLE", "interactions", "gemini-3.5-flash-lite", "GEMINI_API", "synthetic", Map.of("input","1", "cache_read","1", "output","1", "thoughts","1"));
        catalog.publish("synthetic-title-" + UUID.randomUUID(), "synthetic-test-only", List.of(new io.github.samzhu.bot.pricing.PriceCatalog.Entry(price, java.time.Instant.now().minusSeconds(60), java.time.Instant.now().plusSeconds(3600))));
        List<CompletableFuture<HttpResponse<String>>> streams = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        try {
            for (int i=0; i<2; i++) {
                var create = http.send(request("/graphql", Map.of("query", "mutation($workspace: ID!) { createConversation(botId: \"bot-general\", workspaceId: $workspace) { id } }", "variables", Map.of("workspace", scope))), HttpResponse.BodyHandlers.ofString());
                String id = json.readTree(create.body()).path("data").path("createConversation").path("id").asText();
                assertFalse(id.isBlank(), create.body()); ids.add(id);
                var body = Map.of("threadId", id, "runId", UUID.randomUUID().toString(), "state", Map.of(),
                        "messages", List.of(Map.of("id", UUID.randomUUID().toString(), "role", "user", "content", "Synthetic concurrent question")),
                        "tools", List.of(), "context", List.of(), "forwardedProps", Map.of());
                streams.add(http.sendAsync(request("/api/ag-ui", body), HttpResponse.BodyHandlers.ofString()));
            }
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
            while (chatModel().holdSubscriptions() < 2 && System.nanoTime() < end) Thread.sleep(10);
            assertEquals(2, chatModel().holdSubscriptions(), "both chats must already be subscribed");
            assertEquals(1, jdbc.update("update workspace_monthly_budget set enabled=true,cap_usd=1.5 where workspace_id=? and partition='SIMULATION'", scope));
            chatModel().emitChunk("visible reply");
            assertTrue(loopback.entered.await(5, TimeUnit.SECONDS));
            end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            int failed = 0;
            while (System.nanoTime() < end) {
                failed = jdbc.queryForObject("select count(*) from conversation_title_work where conversation_id in (?,?) and state='FAILED'", Integer.class, ids.get(0), ids.get(1));
                if (failed == 1) break;
                Thread.sleep(20);
            }
            assertEquals(1, failed, "the second title must be denied while the first hold is outstanding");
            assertEquals(1, loopback.requests.get(), "shared cap permits at most one reserved title request");
            chatModel().complete();
            for (var stream : streams) assertTrue(stream.get(10, TimeUnit.SECONDS).body().contains("RUN_FINISHED"));
        } finally {
            loopback.release.countDown(); loopback.hold = false; chatModel().complete();
            if (ids.size() == 2) {
                long cleanupUntil = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
                int active = 1;
                while (System.nanoTime() < cleanupUntil) {
                    active = jdbc.queryForObject("select count(*) from conversation_title_work where conversation_id in (?,?) and state in ('READY','CLAIMED')", Integer.class, ids.get(0), ids.get(1));
                    if (active == 0) break;
                    Thread.sleep(20);
                }
                assertEquals(0, active, "fixture title workers must finish before datasource shutdown");
            }
        }
    }

    @Test void uncappedRealTitleWithoutPriceFollowsVisibleReplyAndRecordsUsage() throws Exception {
        assertTrue(environment.matchesProfiles("test") && environment.getProperty("bot-ai-provider") == null);
        try (var connection = dataSource.getConnection()) { assertEquals(postgres.getJdbcUrl(), connection.getMetaData().getURL()); }
        REAL_FIXTURE.set(true);
        chatModel().reset(); chatModel().setHoldMode(true);
        loopback.requests.set(0); titleAdapterInvocations.set(0); loopback.success = true; loopback.hold = true;
        loopback.entered = new CountDownLatch(1); loopback.release = new CountDownLatch(1);
        String scope = "title-unpriced-" + UUID.randomUUID();
        jdbc.update("insert into workspace(id,name) values (?,?)", scope, "Synthetic unpriced");
        jdbc.update("insert into workspace_member(workspace_id,user_id,role,active) values (?,'dev-user','MANAGER',true)", scope);
        jdbc.update("insert into bot(id,name,system_prompt,created_at) values ('bot-general','General','test',current_timestamp) on conflict(id) do nothing");
        var price = new io.github.samzhu.bot.pricing.PriceSnapshot("GOOGLE", "generateContent", "other-model", "GEMINI_API", "synthetic", Map.of("input","1", "cache_read","1", "output","1", "thoughts","1"));
        catalog.publish("synthetic-unpriced-" + UUID.randomUUID(), "synthetic-test-only", List.of(new io.github.samzhu.bot.pricing.PriceCatalog.Entry(price, java.time.Instant.now().minusSeconds(60), java.time.Instant.now().plusSeconds(3600))));
        var create = http.send(request("/graphql", Map.of("query", "mutation($workspace: ID!) { createConversation(botId: \"bot-general\", workspaceId: $workspace) { id } }", "variables", Map.of("workspace", scope))), HttpResponse.BodyHandlers.ofString());
        String id = json.readTree(create.body()).path("data").path("createConversation").path("id").asText();
        assertFalse(id.isBlank(), create.body());
        var body = Map.of("threadId", id, "runId", UUID.randomUUID().toString(), "state", Map.of(), "messages", List.of(Map.of("id", UUID.randomUUID().toString(), "role", "user", "content", "Synthetic ledger question")), "tools", List.of(), "context", List.of(), "forwardedProps", Map.of());
        var stream = http.sendAsync(request("/api/ag-ui", body), HttpResponse.BodyHandlers.ofString());
        try {
            assertTrue(chatModel().awaitSubscribed(8, TimeUnit.SECONDS));
            chatModel().emitChunk("visible response");
            chatModel().complete();
            String events = stream.get(8, TimeUnit.SECONDS).body();
            assertTrue(events.contains("TEXT_MESSAGE_CONTENT") && events.contains("visible response"), events);
            assertTrue(loopback.entered.await(8, TimeUnit.SECONDS), "uncapped REAL title should dispatch without a matching price");
            var before = http.send(request("/graphql", Map.of("query", "query($id: ID!) { conversation(id: $id) { title } }", "variables", Map.of("id", id))), HttpResponse.BodyHandlers.ofString());
            assertEquals("New Conversation", json.readTree(before.body()).path("data").path("conversation").path("title").asText());
            loopback.release.countDown();
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
            String state = "";
            while (System.nanoTime() < end) {
                state = jdbc.queryForObject("select state from conversation_title_work where conversation_id=?", String.class, id);
                if ("DONE".equals(state) || "FAILED".equals(state)) break;
                Thread.sleep(20);
            }
            assertEquals("DONE", state);
            var after = http.send(request("/graphql", Map.of("query", "query($id: ID!) { conversation(id: $id) { title } }", "variables", Map.of("id", id))), HttpResponse.BodyHandlers.ofString());
            assertEquals("Synthetic title", json.readTree(after.body()).path("data").path("conversation").path("title").asText());
            assertEquals(1, loopback.requests.get());
            assertEquals(1, jdbc.queryForObject("select count(*) from provider_attempt where purpose='CONVERSATION_TITLE' and title_conversation_id=? and simulation=false", Integer.class, id));
            assertEquals(1, jdbc.queryForObject("select count(*) from usage_snapshot s join provider_attempt a on a.id=s.attempt_id where a.title_conversation_id=? and s.semantic='FINAL'", Integer.class, id));
            assertEquals(0, jdbc.queryForObject("select count(*) from workspace_title_reservation where conversation_id=?", Integer.class, id));
            assertEquals(1, jdbc.queryForObject("select count(*) from chat_run where conversation_id=?", Integer.class, id));
            assertEquals(2, jdbc.queryForObject("select count(*) from message where conversation_id=?", Integer.class, id));
            assertEquals(1, jdbc.queryForObject("select count(*) from provider_attempt a join usage_cost_current c on c.attempt_id=a.id join usage_cost_estimate e on e.id=c.estimate_id where a.title_conversation_id=? and e.status='UNKNOWN' and e.complete_amount is null and e.details::text like '%MISSING_PRICE%'", Integer.class, id));
        } finally { loopback.release.countDown(); loopback.hold = false; loopback.success = false; chatModel().complete(); REAL_FIXTURE.set(false); }
    }

    @Test void enablingCapsAfterUnknownUncappedTitleBlocksSpending() throws Exception {
        assertTrue(environment.matchesProfiles("test") && environment.getProperty("bot-ai-provider") == null);
        try (var connection = dataSource.getConnection()) { assertEquals(postgres.getJdbcUrl(), connection.getMetaData().getURL()); }
        REAL_FIXTURE.set(true);
        chatModel().reset(); chatModel().setHoldMode(true);
        loopback.requests.set(0); titleAdapterInvocations.set(0); loopback.success = true; loopback.hold = true;
        loopback.entered = new CountDownLatch(1); loopback.release = new CountDownLatch(1);
        String scope = "title-unpriced-" + UUID.randomUUID();
        jdbc.update("insert into workspace(id,name) values (?,?)", scope, "Synthetic unpriced");
        jdbc.update("insert into workspace_member(workspace_id,user_id,role,active) values (?,'dev-user','MANAGER',true)", scope);
        jdbc.update("insert into bot(id,name,system_prompt,created_at) values ('bot-general','General','test',current_timestamp) on conflict(id) do nothing");
        var price = new io.github.samzhu.bot.pricing.PriceSnapshot("GOOGLE", "generateContent", "other-model", "GEMINI_API", "synthetic", Map.of("input","1", "cache_read","1", "output","1", "thoughts","1"));
        catalog.publish("synthetic-unpriced-" + UUID.randomUUID(), "synthetic-test-only", List.of(new io.github.samzhu.bot.pricing.PriceCatalog.Entry(price, java.time.Instant.now().minusSeconds(60), java.time.Instant.now().plusSeconds(3600))));
        var create = http.send(request("/graphql", Map.of("query", "mutation($workspace: ID!) { createConversation(botId: \"bot-general\", workspaceId: $workspace) { id } }", "variables", Map.of("workspace", scope))), HttpResponse.BodyHandlers.ofString());
        String id = json.readTree(create.body()).path("data").path("createConversation").path("id").asText();
        assertFalse(id.isBlank(), create.body());
        String run = UUID.randomUUID().toString();
        var body = Map.of("threadId", id, "runId", run, "state", Map.of(), "messages", List.of(Map.of("id", UUID.randomUUID().toString(), "role", "user", "content", "Synthetic ledger question")), "tools", List.of(), "context", List.of(), "forwardedProps", Map.of());
        var stream = http.sendAsync(request("/api/ag-ui", body), HttpResponse.BodyHandlers.ofString());
        try {
            assertTrue(chatModel().awaitSubscribed(8, TimeUnit.SECONDS));
            chatModel().emitChunk("visible response");
            chatModel().complete();
            String events = stream.get(8, TimeUnit.SECONDS).body();
            assertTrue(events.contains("TEXT_MESSAGE_CONTENT") && events.contains("visible response"), events);
            assertTrue(loopback.entered.await(8, TimeUnit.SECONDS), "uncapped REAL title should dispatch without a matching price");
            var before = http.send(request("/graphql", Map.of("query", "query($id: ID!) { conversation(id: $id) { title } }", "variables", Map.of("id", id))), HttpResponse.BodyHandlers.ofString());
            assertEquals("New Conversation", json.readTree(before.body()).path("data").path("conversation").path("title").asText());
            String chatAttempt=jdbc.queryForObject("select id from provider_attempt where run_id=?",String.class,run);
            long version=jdbc.queryForObject("select projection_version from provider_attempt where id=?",Long.class,chatAttempt);
            String estimate=UUID.randomUUID().toString();
            jdbc.update("insert into usage_cost_estimate(id,attempt_id,source_version,price_snapshot_id,calculator_version,status,known_subtotal,complete_amount,details,payload_hash) values (?,?,?,null,'synthetic','COMPLETE',0,0,'{}'::jsonb,'synthetic')",estimate,chatAttempt,version);
            jdbc.update("insert into usage_cost_current(attempt_id,estimate_id) values (?,?) on conflict(attempt_id) do update set estimate_id=excluded.estimate_id",chatAttempt,estimate);
            loopback.release.countDown();
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
            String state = "";
            while (System.nanoTime() < end) {
                state = jdbc.queryForObject("select state from conversation_title_work where conversation_id=?", String.class, id);
                if ("DONE".equals(state) || "FAILED".equals(state)) break;
                Thread.sleep(20);
            }
            assertEquals("DONE", state);
            var after = http.send(request("/graphql", Map.of("query", "query($id: ID!) { conversation(id: $id) { title } }", "variables", Map.of("id", id))), HttpResponse.BodyHandlers.ofString());
            assertEquals("Synthetic title", json.readTree(after.body()).path("data").path("conversation").path("title").asText());
            assertEquals(1, loopback.requests.get());
            assertEquals(1, jdbc.queryForObject("select count(*) from provider_attempt where purpose='CONVERSATION_TITLE' and title_conversation_id=? and simulation=false", Integer.class, id));
            assertEquals(1, jdbc.queryForObject("select count(*) from usage_snapshot s join provider_attempt a on a.id=s.attempt_id where a.title_conversation_id=? and s.semantic='FINAL'", Integer.class, id));
            assertEquals(0, jdbc.queryForObject("select count(*) from workspace_title_reservation where conversation_id=?", Integer.class, id));
            assertEquals(1, jdbc.queryForObject("select count(*) from chat_run where conversation_id=?", Integer.class, id));
            assertEquals(2, jdbc.queryForObject("select count(*) from message where conversation_id=?", Integer.class, id));
            assertEquals(1, jdbc.queryForObject("select count(*) from provider_attempt a join usage_cost_current c on c.attempt_id=a.id join usage_cost_estimate e on e.id=c.estimate_id where a.title_conversation_id=? and e.status='UNKNOWN' and e.complete_amount is null and e.details::text like '%MISSING_PRICE%'", Integer.class, id));
            var configure=http.send(request("/graphql",Map.of("query","mutation($w: ID!) { month:configureWorkspaceMonthlyBudget(input:{workspaceId:$w,simulation:false,enabled:true,capUsd:\"10\",expectedConfigVersion:\"0\",idempotencyKey:\"unknown-month\"}) { budget { state } } week:configureMemberWeeklyBudget(input:{workspaceId:$w,memberUserId:\"dev-user\",simulation:false,enabled:true,capUsd:\"10\",expectedConfigVersion:\"0\",idempotencyKey:\"unknown-week\"}) { budget { state } } }","variables",Map.of("w",scope))),HttpResponse.BodyHandlers.ofString());
            var configured=json.readTree(configure.body()); assertFalse(configured.has("errors"),configured.toString());
            assertEquals("BLOCKED_UNKNOWN",configured.path("data").path("month").path("budget").path("state").asText(),configured.toString());
            assertEquals("BLOCKED_UNKNOWN",configured.path("data").path("week").path("budget").path("state").asText(),configured.toString());
        } finally { loopback.release.countDown(); loopback.hold = false; loopback.success = false; chatModel().complete(); REAL_FIXTURE.set(false); }
    }

    @Test void failedHoldReplacementRollsBackInitialCostPublication() throws Exception {
        assertTrue(environment.matchesProfiles("test") && environment.getProperty("bot-ai-provider") == null);
        try (var connection = dataSource.getConnection()) { assertEquals(postgres.getJdbcUrl(), connection.getMetaData().getURL()); }
        REAL_FIXTURE.set(false);
        chatModel().reset(); chatModel().setHoldMode(true);
        loopback.requests.set(0); titleAdapterInvocations.set(0); loopback.success = true; loopback.hold = true;
        loopback.entered = new CountDownLatch(1); loopback.release = new CountDownLatch(1);
        String scope = "title-unpriced-" + UUID.randomUUID();
        jdbc.update("insert into workspace(id,name) values (?,?)", scope, "Synthetic unpriced");
        jdbc.update("insert into workspace_member(workspace_id,user_id,role,active) values (?,'dev-user','MANAGER',true)", scope);
        jdbc.update("insert into bot(id,name,system_prompt,created_at) values ('bot-general','General','test',current_timestamp) on conflict(id) do nothing");
        var price = new io.github.samzhu.bot.pricing.PriceSnapshot("GOOGLE", "interactions", "gemini-3.5-flash-lite", "GEMINI_API", "synthetic", Map.of("input","1", "cache_read","1", "output","1", "thoughts","1"));
        catalog.publish("synthetic-unpriced-" + UUID.randomUUID(), "synthetic-test-only", List.of(new io.github.samzhu.bot.pricing.PriceCatalog.Entry(price, java.time.Instant.now().minusSeconds(60), java.time.Instant.now().plusSeconds(3600))));
        var create = http.send(request("/graphql", Map.of("query", "mutation($workspace: ID!) { createConversation(botId: \"bot-general\", workspaceId: $workspace) { id } }", "variables", Map.of("workspace", scope))), HttpResponse.BodyHandlers.ofString());
        String id = json.readTree(create.body()).path("data").path("createConversation").path("id").asText();
        assertFalse(id.isBlank(), create.body());
        var body = Map.of("threadId", id, "runId", UUID.randomUUID().toString(), "state", Map.of(), "messages", List.of(Map.of("id", UUID.randomUUID().toString(), "role", "user", "content", "Synthetic ledger question")), "tools", List.of(), "context", List.of(), "forwardedProps", Map.of());
        var stream = http.sendAsync(request("/api/ag-ui", body), HttpResponse.BodyHandlers.ofString());
        try {
            assertTrue(chatModel().awaitSubscribed(8, TimeUnit.SECONDS));
            assertEquals(1, jdbc.update("update workspace_monthly_budget set enabled=true,cap_usd=3 where workspace_id=? and partition='SIMULATION'", scope));
            chatModel().emitChunk("visible response");
            assertTrue(loopback.entered.await(8, TimeUnit.SECONDS));
            jdbc.execute("create function test_fail_hold_settlement() returns trigger language plpgsql as $$ begin if new.state='SETTLED' then raise exception 'synthetic settlement failure'; end if; return new; end $$");
            jdbc.execute("create trigger test_fail_hold_settlement before update on workspace_title_reservation for each row execute function test_fail_hold_settlement()");
            chatModel().complete();
            String events = stream.get(8, TimeUnit.SECONDS).body();
            assertTrue(events.contains("TEXT_MESSAGE_CONTENT") && events.contains("visible response"), events);
            assertTrue(loopback.entered.await(8, TimeUnit.SECONDS), "uncapped REAL title should dispatch without a matching price");
            var before = http.send(request("/graphql", Map.of("query", "query($id: ID!) { conversation(id: $id) { title } }", "variables", Map.of("id", id))), HttpResponse.BodyHandlers.ofString());
            assertEquals("New Conversation", json.readTree(before.body()).path("data").path("conversation").path("title").asText());
            loopback.release.countDown();
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
            String state = "";
            while (System.nanoTime() < end) {
                state = jdbc.queryForObject("select state from conversation_title_work where conversation_id=?", String.class, id);
                if ("DONE".equals(state) || "FAILED".equals(state)) break;
                Thread.sleep(20);
            }
            assertEquals("DONE", state);
            var after = http.send(request("/graphql", Map.of("query", "query($id: ID!) { conversation(id: $id) { title } }", "variables", Map.of("id", id))), HttpResponse.BodyHandlers.ofString());
            assertEquals("Synthetic title", json.readTree(after.body()).path("data").path("conversation").path("title").asText());
            assertEquals(1, loopback.requests.get());
            assertEquals(1, jdbc.queryForObject("select count(*) from provider_attempt where purpose='CONVERSATION_TITLE' and title_conversation_id=? and simulation=true", Integer.class, id));
            assertEquals(1, jdbc.queryForObject("select count(*) from usage_snapshot s join provider_attempt a on a.id=s.attempt_id where a.title_conversation_id=? and s.semantic='FINAL'", Integer.class, id));
            assertEquals("HELD", jdbc.queryForObject("select state from workspace_title_reservation where conversation_id=?", String.class, id));
            assertEquals(1, jdbc.queryForObject("select count(*) from chat_run where conversation_id=?", Integer.class, id));
            assertEquals(2, jdbc.queryForObject("select count(*) from message where conversation_id=?", Integer.class, id));
            assertEquals(0, jdbc.queryForObject("select count(*) from provider_attempt a join usage_cost_current c on c.attempt_id=a.id where a.title_conversation_id=?", Integer.class, id), "cost publication and failed hold replacement must roll back together");
        } finally { loopback.release.countDown(); loopback.hold = false; loopback.success = false; chatModel().complete(); REAL_FIXTURE.set(false);
            jdbc.execute("drop trigger if exists test_fail_hold_settlement on workspace_title_reservation");
            jdbc.execute("drop function if exists test_fail_hold_settlement()");
        }
    }

    @Test void auditedTitleReconciliationReplacesHeldReservation(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        TEST_NOW.set(java.time.Instant.parse("2026-05-31T15:59:50Z"));
        chatModel().reset(); chatModel().setHoldMode(true);
        loopback.requests.set(0); loopback.success=true; loopback.hold=true; loopback.parityUsage=true;
        loopback.entered=new CountDownLatch(1); loopback.release=new CountDownLatch(1);
        String scope="title-reconcile-"+UUID.randomUUID();
        jdbc.update("insert into workspace(id,name) values (?,?)",scope,"reconciliation fixture");
        jdbc.update("insert into workspace_member(workspace_id,user_id,role,active) values (?,'dev-user','MANAGER',true)",scope);
        jdbc.update("insert into bot(id,name,system_prompt,created_at) values ('bot-general','General','test',current_timestamp) on conflict(id) do nothing");
        var price=new io.github.samzhu.bot.pricing.PriceSnapshot("GOOGLE","interactions","gemini-3.5-flash-lite","GEMINI_API","synthetic",Map.of("input","1","cache_read","0.1","output","2","thoughts","2"));
        catalog.publish("initial-"+UUID.randomUUID(),"synthetic",List.of(new io.github.samzhu.bot.pricing.PriceCatalog.Entry(price,java.time.Instant.parse("2026-01-01T00:00:00Z"),java.time.Instant.parse("2027-01-01T00:00:00Z"))));
        var created=http.send(request("/graphql",Map.of("query","mutation($w:ID!){createConversation(botId:\"bot-general\",workspaceId:$w){id}}","variables",Map.of("w",scope))),HttpResponse.BodyHandlers.ofString());
        String id=json.readTree(created.body()).path("data").path("createConversation").path("id").asText();
        assertFalse(id.isBlank(),created.body());
        var body=Map.of("threadId",id,"runId",UUID.randomUUID().toString(),"state",Map.of(),"messages",List.of(Map.of("id",UUID.randomUUID().toString(),"role","user","content","Synthetic ledger question")),"tools",List.of(),"context",List.of(),"forwardedProps",Map.of());
        var stream=http.sendAsync(request("/api/ag-ui",body),HttpResponse.BodyHandlers.ofString());
        try {
            assertTrue(chatModel().awaitSubscribed(8,TimeUnit.SECONDS));
            jdbc.update("update workspace_monthly_budget set enabled=true,cap_usd=3 where workspace_id=? and partition='SIMULATION'",scope);
            jdbc.update("insert into member_weekly_budget(workspace_id,member_user_id,partition,enabled,cap_usd) values (?,'dev-user','SIMULATION',true,3)",scope);
            chatModel().emitChunk("visible response"); assertTrue(loopback.entered.await(8,TimeUnit.SECONDS));
            jdbc.execute("create function test_fail_reconcile_hold() returns trigger language plpgsql as $$ begin if new.state='SETTLED' then raise exception 'synthetic'; end if; return new; end $$");
            jdbc.execute("create trigger test_fail_reconcile_hold before update on workspace_title_reservation for each row execute function test_fail_reconcile_hold()");
            chatModel().complete(); assertTrue(stream.get(10,TimeUnit.SECONDS).body().contains("RUN_FINISHED"));
            loopback.release.countDown();
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(8); String state="";
            while(System.nanoTime()<until) { state=jdbc.queryForObject("select state from conversation_title_work where conversation_id=?",String.class,id); if("DONE".equals(state)) break; Thread.sleep(20); }
            assertEquals("DONE",state);
            jdbc.execute("drop trigger test_fail_reconcile_hold on workspace_title_reservation");
            jdbc.execute("drop function test_fail_reconcile_hold()");
            String attempt=jdbc.queryForObject("select id from provider_attempt where title_conversation_id=?",String.class,id);
            String version=jdbc.queryForObject("select projection_version::text from provider_attempt where id=?",String.class,attempt);
            assertEquals("HELD",jdbc.queryForObject("select state from workspace_title_reservation where conversation_id=?",String.class,id));
            var yaml=dir.resolve("pricing/catalog.yaml"); java.nio.file.Files.createDirectories(yaml.getParent());
            String fixture="""
                environments:
                  prod:
                    revision: synthetic-title-reconcile
                    sourceReference: https://example.org/synthetic
                    entries: [title]
                  dev:
                    revision: synthetic-title-reconcile-dev
                    sourceReference: https://example.org/synthetic
                    entries: [title]
                entries:
                  title:
                    provider: GOOGLE
                    endpoint: interactions
                    model: gemini-3.5-flash-lite
                    channel: GEMINI_API
                    priceRevision: synthetic-title
                    fromInclusive: '2026-05-31T15:59:50Z'
                    untilExclusive: '2026-06-01T00:00:00Z'
                    metadata:
                      serviceTier: STANDARD
                      tokenUnit: USD_PER_1000000_TOKENS
                      sources: [https://example.org/synthetic]
                      verifiedDate: '2026-01-01'
                    rates:
                      input: '1'
                      cache_read: '0.1'
                      output: '2'
                      thoughts: '2'
                """;
            java.nio.file.Files.writeString(yaml,fixture);
            var log=dir.resolve("admin.log");
            var process=new ProcessBuilder(java.nio.file.Path.of(System.getProperty("java.home"),"bin","java").toString(),"-cp",dir+java.io.File.pathSeparator+System.getProperty("release.test.classpath"),"io.github.samzhu.bot.bootstrap.ReleaseProvisioningApplication","--spring.datasource.url="+postgres.getJdbcUrl(),"--spring.datasource.username="+postgres.getUsername(),"--spring.datasource.password="+postgres.getPassword(),"--bot.release.action=initialize-prod","--management.otlp.metrics.export.enabled=false","--bot.release.expected-jdbc-url="+postgres.getJdbcUrl()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
            assertTrue(process.waitFor(90,TimeUnit.SECONDS)); assertEquals(0,process.exitValue(),()->"admin: "+readLog(log));
            String revision=jdbc.queryForObject("select id from pricing_catalog_revision where source_revision='synthetic-title-reconcile'",String.class);
            TEST_NOW.set(java.time.Instant.parse("2026-05-31T16:00:10Z"));
            String query="mutation($input:ReconcileCostInput!){reconcileCost(input:$input){outcome revision{id status completeAmount} }}";
            var input=new java.util.HashMap<String,Object>(); input.put("workspaceId",scope); input.put("attemptId",attempt); input.put("expectedRevision",null); input.put("expectedSourceProjectionVersion",version); input.put("idempotencyKey","title-reconcile"); input.put("catalogRevisionId",revision); input.put("evidenceReference","synthetic"); input.put("reason","verified");
            jdbc.update("update workspace_member set role='MEMBER' where workspace_id=? and user_id='dev-user'",scope);
            var forbidden=json.readTree(http.send(request("/graphql",Map.of("query",query,"variables",Map.of("input",input))),HttpResponse.BodyHandlers.ofString()).body());
            assertEquals("WORKSPACE_MANAGER_REQUIRED",forbidden.path("errors").path(0).path("extensions").path("code").asText(),forbidden.toString());
            assertEquals(0,jdbc.queryForObject("select count(*) from usage_cost_revision_audit where attempt_id=?",Integer.class,attempt));
            jdbc.update("update workspace_member set role='MANAGER' where workspace_id=? and user_id='dev-user'",scope);
            var response=json.readTree(http.send(request("/graphql",Map.of("query",query,"variables",Map.of("input",input))),HttpResponse.BodyHandlers.ofString()).body());
            assertFalse(response.has("errors"),response.toString()); assertEquals("COMPLETE",response.path("data").path("reconcileCost").path("revision").path("status").asText());
            assertEquals("0.000112000000",response.path("data").path("reconcileCost").path("revision").path("completeAmount").asText());
            assertEquals("SETTLED",jdbc.queryForObject("select state from workspace_title_reservation where conversation_id=?",String.class,id));
            assertEquals(0,new java.math.BigDecimal("0.000112").compareTo(jdbc.queryForObject("select known_subtotal_usd from workspace_monthly_budget_period where workspace_id=? and partition='SIMULATION' and period_start=date '2026-05-01'",java.math.BigDecimal.class,scope)));
            assertEquals(1,jdbc.queryForObject("select count(*) from usage_cost_revision_audit where attempt_id=? and kind='RECONCILIATION' and actor_id='dev-user'",Integer.class,attempt));
            var replay=json.readTree(http.send(request("/graphql",Map.of("query",query,"variables",Map.of("input",input))),HttpResponse.BodyHandlers.ofString()).body());
            assertEquals("REPLAYED",replay.path("data").path("reconcileCost").path("outcome").asText(),replay.toString());
            input.put("reason","different");
            var conflict=json.readTree(http.send(request("/graphql",Map.of("query",query,"variables",Map.of("input",input))),HttpResponse.BodyHandlers.ofString()).body());
            assertEquals("CONFLICT",conflict.path("errors").path(0).path("extensions").path("code").asText(),conflict.toString());
            var stale=new java.util.HashMap<String,Object>(input); stale.put("idempotencyKey","stale-source"); stale.put("expectedSourceProjectionVersion",Long.toString(Long.parseLong(version)+1));
            var staleResult=json.readTree(http.send(request("/graphql",Map.of("query",query,"variables",Map.of("input",stale))),HttpResponse.BodyHandlers.ofString()).body());
            assertEquals("CONFLICT",staleResult.path("errors").path(0).path("extensions").path("code").asText(),staleResult.toString());
            TEST_NOW.set(java.time.Instant.parse("2026-05-31T15:59:50Z"));
            var weekly=json.readTree(http.send(request("/graphql",Map.of("query","query($w:ID!){myMemberWeeklyBudget(workspaceId:$w,simulation:true){knownSubtotalUsd}}","variables",Map.of("w",scope))),HttpResponse.BodyHandlers.ofString()).body());
            assertEquals(0,new java.math.BigDecimal("0.000112").compareTo(new java.math.BigDecimal(weekly.path("data").path("myMemberWeeklyBudget").path("knownSubtotalUsd").asText())));
            assertEquals(1,loopback.requests.get());
            // A second real SDK-native response is priced automatically, then audited at the same rates.
            String scope2="title-parity-"+UUID.randomUUID();
            jdbc.update("insert into workspace(id,name) values (?,?)",scope2,"parity fixture");
            jdbc.update("insert into workspace_member(workspace_id,user_id,role,active) values (?,'dev-user','MANAGER',true)",scope2);
            chatModel().reset(); chatModel().setHoldMode(true); loopback.entered=new CountDownLatch(1); loopback.release=new CountDownLatch(1);
            var create2=json.readTree(http.send(request("/graphql",Map.of("query","mutation($w:ID!){createConversation(botId:\"bot-general\",workspaceId:$w){id}}","variables",Map.of("w",scope2))),HttpResponse.BodyHandlers.ofString()).body());
            String id2=create2.path("data").path("createConversation").path("id").asText(); assertFalse(id2.isBlank(),create2.toString());
            var body2=Map.of("threadId",id2,"runId",UUID.randomUUID().toString(),"state",Map.of(),"messages",List.of(Map.of("id",UUID.randomUUID().toString(),"role","user","content","Synthetic ledger question")),"tools",List.of(),"context",List.of(),"forwardedProps",Map.of());
            var stream2=http.sendAsync(request("/api/ag-ui",body2),HttpResponse.BodyHandlers.ofString());
            assertTrue(chatModel().awaitSubscribed(8,TimeUnit.SECONDS));
            jdbc.update("update workspace_monthly_budget set enabled=true,cap_usd=3 where workspace_id=? and partition='SIMULATION'",scope2);
            chatModel().emitChunk("visible response"); assertTrue(loopback.entered.await(8,TimeUnit.SECONDS));
            chatModel().complete(); assertTrue(stream2.get(10,TimeUnit.SECONDS).body().contains("RUN_FINISHED")); loopback.release.countDown();
            long end2=System.nanoTime()+TimeUnit.SECONDS.toNanos(8); String state2="";
            while(System.nanoTime()<end2) { state2=jdbc.queryForObject("select state from conversation_title_work where conversation_id=?",String.class,id2); if("DONE".equals(state2)) break; Thread.sleep(20); }
            assertEquals("DONE",state2);
            String attempt2=jdbc.queryForObject("select id from provider_attempt where title_conversation_id=?",String.class,id2);
            String original=jdbc.queryForObject("select estimate_id from usage_cost_current where attempt_id=?",String.class,attempt2);
            String originalPrice=jdbc.queryForObject("select price_snapshot_id from usage_cost_estimate where id=?",String.class,original);
            String version2=jdbc.queryForObject("select projection_version::text from provider_attempt where id=?",String.class,attempt2);
            var before2=json.readTree(http.send(request("/graphql",Map.of("query","query($w:ID!,$a:ID!){workspaceUsageAttempt(workspaceId:$w,id:$a){cost{completeAmount currentRevision}}}","variables",Map.of("w",scope2,"a",attempt2))),HttpResponse.BodyHandlers.ofString()).body());
            assertEquals("0.000112000000",before2.path("data").path("workspaceUsageAttempt").path("cost").path("completeAmount").asText(),before2.toString());
            var input2=new java.util.HashMap<String,Object>(input); input2.put("workspaceId",scope2); input2.put("attemptId",attempt2); input2.put("expectedRevision",original); input2.put("expectedSourceProjectionVersion",version2); input2.put("idempotencyKey","parity"); input2.put("reason","same-price parity");
            var audited=json.readTree(http.send(request("/graphql",Map.of("query",query,"variables",Map.of("input",input2))),HttpResponse.BodyHandlers.ofString()).body());
            assertFalse(audited.has("errors"),audited.toString());
            assertEquals("0.000112000000",audited.path("data").path("reconcileCost").path("revision").path("completeAmount").asText());
            assertEquals(originalPrice,jdbc.queryForObject("select price_snapshot_id from usage_cost_estimate where id=?",String.class,original));
            assertEquals(2,jdbc.queryForObject("select count(*) from usage_cost_estimate where attempt_id=?",Integer.class,attempt2));
            var expired=catalog.publish("exclusive-end-"+UUID.randomUUID(),"synthetic",List.of(new io.github.samzhu.bot.pricing.PriceCatalog.Entry(price,java.time.Instant.parse("2026-01-01T00:00:00Z"),java.time.Instant.parse("2026-05-31T15:59:50Z"))));
            input2.put("idempotencyKey","exclusive-end"); input2.put("catalogRevisionId",expired.id()); input2.put("expectedRevision",audited.path("data").path("reconcileCost").path("revision").path("id").asText());
            var outside=json.readTree(http.send(request("/graphql",Map.of("query",query,"variables",Map.of("input",input2))),HttpResponse.BodyHandlers.ofString()).body());
            assertFalse(outside.has("errors"),outside.toString());
            assertNotEquals("COMPLETE",outside.path("data").path("reconcileCost").path("revision").path("status").asText());
            assertTrue(outside.path("data").path("reconcileCost").path("revision").path("completeAmount").isNull());
            assertEquals(2,loopback.requests.get());
        } finally {
            loopback.release.countDown(); loopback.hold=false; loopback.success=false; loopback.parityUsage=false; chatModel().complete(); TEST_NOW.set(null);
            jdbc.execute("drop trigger if exists test_fail_reconcile_hold on workspace_title_reservation");
            jdbc.execute("drop function if exists test_fail_reconcile_hold()");
        }
    }
    private static String readLog(java.nio.file.Path path) { try { return java.nio.file.Files.readString(path); } catch (java.io.IOException ex) { return ex.toString(); } }

    @Test void durableUsageAndTitleSurvivePricingWriteFailure() throws Exception {
        assertTrue(environment.matchesProfiles("test") && environment.getProperty("bot-ai-provider") == null);
        try (var connection = dataSource.getConnection()) { assertEquals(postgres.getJdbcUrl(), connection.getMetaData().getURL()); }
        REAL_FIXTURE.set(true);
        chatModel().reset(); chatModel().setHoldMode(true);
        loopback.requests.set(0); titleAdapterInvocations.set(0); loopback.success = true; loopback.hold = true;
        loopback.entered = new CountDownLatch(1); loopback.release = new CountDownLatch(1);
        String scope = "title-unpriced-" + UUID.randomUUID();
        jdbc.update("insert into workspace(id,name) values (?,?)", scope, "Synthetic unpriced");
        jdbc.update("insert into workspace_member(workspace_id,user_id,role,active) values (?,'dev-user','MANAGER',true)", scope);
        jdbc.update("insert into bot(id,name,system_prompt,created_at) values ('bot-general','General','test',current_timestamp) on conflict(id) do nothing");
        var price = new io.github.samzhu.bot.pricing.PriceSnapshot("GOOGLE", "generateContent", "other-model", "GEMINI_API", "synthetic", Map.of("input","1", "cache_read","1", "output","1", "thoughts","1"));
        catalog.publish("synthetic-unpriced-" + UUID.randomUUID(), "synthetic-test-only", List.of(new io.github.samzhu.bot.pricing.PriceCatalog.Entry(price, java.time.Instant.now().minusSeconds(60), java.time.Instant.now().plusSeconds(3600))));
        var create = http.send(request("/graphql", Map.of("query", "mutation($workspace: ID!) { createConversation(botId: \"bot-general\", workspaceId: $workspace) { id } }", "variables", Map.of("workspace", scope))), HttpResponse.BodyHandlers.ofString());
        String id = json.readTree(create.body()).path("data").path("createConversation").path("id").asText();
        assertFalse(id.isBlank(), create.body());
        var body = Map.of("threadId", id, "runId", UUID.randomUUID().toString(), "state", Map.of(), "messages", List.of(Map.of("id", UUID.randomUUID().toString(), "role", "user", "content", "Synthetic ledger question")), "tools", List.of(), "context", List.of(), "forwardedProps", Map.of());
        jdbc.execute("create function test_fail_title_pricing() returns trigger language plpgsql as $$ begin if exists (select 1 from provider_attempt where id=new.attempt_id and purpose='CONVERSATION_TITLE') then raise exception 'synthetic pricing failure'; end if; return new; end $$");
        jdbc.execute("create trigger test_fail_title_pricing before insert on usage_cost_estimate for each row execute function test_fail_title_pricing()");
        var stream = http.sendAsync(request("/api/ag-ui", body), HttpResponse.BodyHandlers.ofString());
        try {
            assertTrue(chatModel().awaitSubscribed(8, TimeUnit.SECONDS));
            chatModel().emitChunk("visible response");
            chatModel().complete();
            String events = stream.get(8, TimeUnit.SECONDS).body();
            assertTrue(events.contains("TEXT_MESSAGE_CONTENT") && events.contains("visible response"), events);
            assertTrue(loopback.entered.await(8, TimeUnit.SECONDS), "uncapped REAL title should dispatch without a matching price");
            var before = http.send(request("/graphql", Map.of("query", "query($id: ID!) { conversation(id: $id) { title } }", "variables", Map.of("id", id))), HttpResponse.BodyHandlers.ofString());
            assertEquals("New Conversation", json.readTree(before.body()).path("data").path("conversation").path("title").asText());
            loopback.release.countDown();
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
            String state = "";
            while (System.nanoTime() < end) {
                state = jdbc.queryForObject("select state from conversation_title_work where conversation_id=?", String.class, id);
                if ("DONE".equals(state) || "FAILED".equals(state)) break;
                Thread.sleep(20);
            }
            assertEquals("DONE", state);
            var after = http.send(request("/graphql", Map.of("query", "query($id: ID!) { conversation(id: $id) { title } }", "variables", Map.of("id", id))), HttpResponse.BodyHandlers.ofString());
            assertEquals("Synthetic title", json.readTree(after.body()).path("data").path("conversation").path("title").asText());
            assertEquals(1, loopback.requests.get());
            assertEquals(1, jdbc.queryForObject("select count(*) from provider_attempt where purpose='CONVERSATION_TITLE' and title_conversation_id=? and simulation=false", Integer.class, id));
            assertEquals(1, jdbc.queryForObject("select count(*) from usage_snapshot s join provider_attempt a on a.id=s.attempt_id where a.title_conversation_id=? and s.semantic='FINAL'", Integer.class, id));
            assertEquals(0, jdbc.queryForObject("select count(*) from workspace_title_reservation where conversation_id=?", Integer.class, id));
            assertEquals(1, jdbc.queryForObject("select count(*) from chat_run where conversation_id=?", Integer.class, id));
            assertEquals(2, jdbc.queryForObject("select count(*) from message where conversation_id=?", Integer.class, id));
            assertEquals(0, jdbc.queryForObject("select count(*) from provider_attempt a join usage_cost_current c on c.attempt_id=a.id where a.title_conversation_id=?", Integer.class, id));
        } finally { loopback.release.countDown(); loopback.hold = false; loopback.success = false; chatModel().complete(); REAL_FIXTURE.set(false);
            jdbc.execute("drop trigger if exists test_fail_title_pricing on usage_cost_estimate");
            jdbc.execute("drop function if exists test_fail_title_pricing()");
        }
    }

    @Test void validUncappedTitleSurvivesIncompleteNativeUsage() throws Exception {
        assertTrue(environment.matchesProfiles("test") && environment.getProperty("bot-ai-provider") == null);
        try (var connection = dataSource.getConnection()) { assertEquals(postgres.getJdbcUrl(), connection.getMetaData().getURL()); }
        REAL_FIXTURE.set(true);
        chatModel().reset(); chatModel().setHoldMode(true);
        loopback.requests.set(0); titleAdapterInvocations.set(0); loopback.success = true; loopback.hold = true; loopback.incompleteUsage = true;
        loopback.entered = new CountDownLatch(1); loopback.release = new CountDownLatch(1);
        String scope = "title-unpriced-" + UUID.randomUUID();
        jdbc.update("insert into workspace(id,name) values (?,?)", scope, "Synthetic unpriced");
        jdbc.update("insert into workspace_member(workspace_id,user_id,role,active) values (?,'dev-user','MANAGER',true)", scope);
        jdbc.update("insert into bot(id,name,system_prompt,created_at) values ('bot-general','General','test',current_timestamp) on conflict(id) do nothing");
        var price = new io.github.samzhu.bot.pricing.PriceSnapshot("GOOGLE", "generateContent", "other-model", "GEMINI_API", "synthetic", Map.of("input","1", "cache_read","1", "output","1", "thoughts","1"));
        catalog.publish("synthetic-unpriced-" + UUID.randomUUID(), "synthetic-test-only", List.of(new io.github.samzhu.bot.pricing.PriceCatalog.Entry(price, java.time.Instant.now().minusSeconds(60), java.time.Instant.now().plusSeconds(3600))));
        var create = http.send(request("/graphql", Map.of("query", "mutation($workspace: ID!) { createConversation(botId: \"bot-general\", workspaceId: $workspace) { id } }", "variables", Map.of("workspace", scope))), HttpResponse.BodyHandlers.ofString());
        String id = json.readTree(create.body()).path("data").path("createConversation").path("id").asText();
        assertFalse(id.isBlank(), create.body());
        var body = Map.of("threadId", id, "runId", UUID.randomUUID().toString(), "state", Map.of(), "messages", List.of(Map.of("id", UUID.randomUUID().toString(), "role", "user", "content", "Synthetic ledger question")), "tools", List.of(), "context", List.of(), "forwardedProps", Map.of());
        var stream = http.sendAsync(request("/api/ag-ui", body), HttpResponse.BodyHandlers.ofString());
        try {
            assertTrue(chatModel().awaitSubscribed(8, TimeUnit.SECONDS));
            chatModel().emitChunk("visible response");
            chatModel().complete();
            String events = stream.get(8, TimeUnit.SECONDS).body();
            assertTrue(events.contains("TEXT_MESSAGE_CONTENT") && events.contains("visible response"), events);
            assertTrue(loopback.entered.await(8, TimeUnit.SECONDS), "uncapped REAL title should dispatch without a matching price");
            var before = http.send(request("/graphql", Map.of("query", "query($id: ID!) { conversation(id: $id) { title } }", "variables", Map.of("id", id))), HttpResponse.BodyHandlers.ofString());
            assertEquals("New Conversation", json.readTree(before.body()).path("data").path("conversation").path("title").asText());
            loopback.release.countDown();
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
            String state = "";
            while (System.nanoTime() < end) {
                state = jdbc.queryForObject("select state from conversation_title_work where conversation_id=?", String.class, id);
                if ("DONE".equals(state) || "FAILED".equals(state)) break;
                Thread.sleep(20);
            }
            assertEquals("DONE", state);
            var after = http.send(request("/graphql", Map.of("query", "query($id: ID!) { conversation(id: $id) { title } }", "variables", Map.of("id", id))), HttpResponse.BodyHandlers.ofString());
            assertEquals("Synthetic title", json.readTree(after.body()).path("data").path("conversation").path("title").asText());
            assertEquals(1, loopback.requests.get());
            assertEquals(1, jdbc.queryForObject("select count(*) from provider_attempt where purpose='CONVERSATION_TITLE' and title_conversation_id=? and simulation=false", Integer.class, id));
            assertEquals(1, jdbc.queryForObject("select count(*) from usage_snapshot s join provider_attempt a on a.id=s.attempt_id where a.title_conversation_id=? and s.capture_completeness='INCOMPLETE'", Integer.class, id));
            assertEquals(0, jdbc.queryForObject("select count(*) from workspace_title_reservation where conversation_id=?", Integer.class, id));
            assertEquals(1, jdbc.queryForObject("select count(*) from chat_run where conversation_id=?", Integer.class, id));
            assertEquals(2, jdbc.queryForObject("select count(*) from message where conversation_id=?", Integer.class, id));
            assertEquals(1, jdbc.queryForObject("select count(*) from provider_attempt a join usage_cost_current c on c.attempt_id=a.id join usage_cost_estimate e on e.id=c.estimate_id where a.title_conversation_id=? and e.status='UNKNOWN' and e.complete_amount is null and e.details::text like '%MISSING_PRICE%'", Integer.class, id));
            String attempt=jdbc.queryForObject("select id from provider_attempt where title_conversation_id=?",String.class,id);
            String old=jdbc.queryForObject("select estimate_id from usage_cost_current where attempt_id=?",String.class,attempt);
            String source=jdbc.queryForObject("select projection_version::text from provider_attempt where id=?",String.class,attempt);
            var matching=new io.github.samzhu.bot.pricing.PriceSnapshot("GOOGLE","interactions","gemini-3.5-flash-lite","GEMINI_API","synthetic",Map.of("input","1","cache_read","0.1","output","2","thoughts","2"));
            String chosen=catalog.publish("incomplete-"+UUID.randomUUID(),"synthetic",List.of(new io.github.samzhu.bot.pricing.PriceCatalog.Entry(matching,java.time.Instant.now().minusSeconds(60),java.time.Instant.now().plusSeconds(3600)))).id();
            var params=Map.of("workspaceId",scope,"attemptId",attempt,"expectedRevision",old,"expectedSourceProjectionVersion",source,"idempotencyKey","incomplete","catalogRevisionId",chosen,"evidenceReference","synthetic","reason","missing native counter");
            var revised=json.readTree(http.send(request("/graphql",Map.of("query","mutation($input:ReconcileCostInput!){reconcileCost(input:$input){revision{status completeAmount reasons}}}","variables",Map.of("input",params))),HttpResponse.BodyHandlers.ofString()).body());
            assertFalse(revised.has("errors"),revised.toString());
            assertNotEquals("COMPLETE",revised.path("data").path("reconcileCost").path("revision").path("status").asText());
            assertTrue(revised.path("data").path("reconcileCost").path("revision").path("completeAmount").isNull());
            assertEquals(1,loopback.requests.get());
        } finally { loopback.release.countDown(); loopback.hold = false; loopback.success = false; loopback.incompleteUsage = false; chatModel().complete(); REAL_FIXTURE.set(false); }
    }

    @Test void expiredUncappedRealAttemptIsUnknownAndNeverRedispatched() throws Exception {
        assertTrue(environment.matchesProfiles("test") && environment.getProperty("bot-ai-provider") == null);
        try (var connection = dataSource.getConnection()) { assertEquals(postgres.getJdbcUrl(), connection.getMetaData().getURL()); }
        REAL_FIXTURE.set(true);
        chatModel().reset(); chatModel().setHoldMode(true);
        loopback.requests.set(0); titleAdapterInvocations.set(0); loopback.success = true; loopback.hold = true;
        loopback.entered = new CountDownLatch(1); loopback.release = new CountDownLatch(1);
        String scope = "title-unpriced-" + UUID.randomUUID();
        jdbc.update("insert into workspace(id,name) values (?,?)", scope, "Synthetic unpriced");
        jdbc.update("insert into workspace_member(workspace_id,user_id,role,active) values (?,'dev-user','MANAGER',true)", scope);
        jdbc.update("insert into bot(id,name,system_prompt,created_at) values ('bot-general','General','test',current_timestamp) on conflict(id) do nothing");
        var price = new io.github.samzhu.bot.pricing.PriceSnapshot("GOOGLE", "generateContent", "other-model", "GEMINI_API", "synthetic", Map.of("input","1", "cache_read","1", "output","1", "thoughts","1"));
        catalog.publish("synthetic-unpriced-" + UUID.randomUUID(), "synthetic-test-only", List.of(new io.github.samzhu.bot.pricing.PriceCatalog.Entry(price, java.time.Instant.now().minusSeconds(60), java.time.Instant.now().plusSeconds(3600))));
        var create = http.send(request("/graphql", Map.of("query", "mutation($workspace: ID!) { createConversation(botId: \"bot-general\", workspaceId: $workspace) { id } }", "variables", Map.of("workspace", scope))), HttpResponse.BodyHandlers.ofString());
        String id = json.readTree(create.body()).path("data").path("createConversation").path("id").asText();
        assertFalse(id.isBlank(), create.body());
        var body = Map.of("threadId", id, "runId", UUID.randomUUID().toString(), "state", Map.of(), "messages", List.of(Map.of("id", UUID.randomUUID().toString(), "role", "user", "content", "Synthetic ledger question")), "tools", List.of(), "context", List.of(), "forwardedProps", Map.of());
        var stream = http.sendAsync(request("/api/ag-ui", body), HttpResponse.BodyHandlers.ofString());
        try {
            assertTrue(chatModel().awaitSubscribed(8, TimeUnit.SECONDS));
            chatModel().emitChunk("visible response");
            chatModel().complete();
            String events = stream.get(8, TimeUnit.SECONDS).body();
            assertTrue(events.contains("TEXT_MESSAGE_CONTENT") && events.contains("visible response"), events);
            assertTrue(loopback.entered.await(8, TimeUnit.SECONDS), "uncapped REAL title should dispatch without a matching price");
            assertEquals(0, jdbc.queryForObject("select count(*) from workspace_title_reservation where conversation_id=?", Integer.class, id));
            jdbc.update("update conversation_title_work set claimed_until=clock_timestamp()-interval '1 second' where conversation_id=?", id);
            recoverySweep.recoverExpiredBatch();
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
            String outcome = "";
            while (System.nanoTime() < end) {
                outcome = jdbc.queryForObject("select execution_outcome from provider_attempt where purpose='CONVERSATION_TITLE' and title_conversation_id=?", String.class, id);
                if ("UNKNOWN".equals(outcome)) break;
                Thread.sleep(20);
            }
            assertEquals("UNKNOWN", outcome, "expired dispatched attempt must be recovered without a reservation");
            loopback.release.countDown();
            Thread.sleep(1200);
            assertEquals(1, loopback.requests.get());
            var title = http.send(request("/graphql", Map.of("query", "query($id: ID!) { conversation(id: $id) { title } }", "variables", Map.of("id", id))), HttpResponse.BodyHandlers.ofString());
            assertEquals("New Conversation", json.readTree(title.body()).path("data").path("conversation").path("title").asText());
            assertEquals(1, jdbc.queryForObject("select count(*) from provider_attempt where purpose='CONVERSATION_TITLE' and title_conversation_id=?", Integer.class, id));
        } finally { loopback.release.countDown(); loopback.hold = false; loopback.success = false; chatModel().complete(); REAL_FIXTURE.set(false); }
    }

    @Test void completedTitleHasDistinctDurableUsageAttempt(org.springframework.boot.test.system.CapturedOutput output) throws Exception {
        assertTrue(environment.matchesProfiles("test") && environment.getProperty("bot-ai-provider") == null);
        try (var connection = dataSource.getConnection()) { assertEquals(postgres.getJdbcUrl(), connection.getMetaData().getURL()); }
        chatModel().reset(); chatModel().setHoldMode(true);
        loopback.requests.set(0); titleAdapterInvocations.set(0); loopback.validRequest = false; loopback.success = true; loopback.hold = true; loopback.entered = new CountDownLatch(1); loopback.release = new CountDownLatch(1);
        String scope = "title-ledger-" + UUID.randomUUID();
        jdbc.update("insert into workspace(id,name) values (?,?)", scope, "Synthetic ledger");
        jdbc.update("insert into workspace_member(workspace_id,user_id,role,active) values (?,'dev-user','MANAGER',true)", scope);
        jdbc.update("insert into bot(id,name,system_prompt,created_at) values ('bot-general','General','test',current_timestamp) on conflict(id) do nothing");
        var price = new io.github.samzhu.bot.pricing.PriceSnapshot("GOOGLE", "interactions", "gemini-3.5-flash-lite", "GEMINI_API", "synthetic", Map.of("input","1", "cache_read","1", "output","1", "thoughts","1"));
        catalog.publish("synthetic-ledger-" + UUID.randomUUID(), "synthetic-test-only", List.of(new io.github.samzhu.bot.pricing.PriceCatalog.Entry(price, java.time.Instant.now().minusSeconds(60), java.time.Instant.now().plusSeconds(3600))));
        var create = http.send(request("/graphql", Map.of("query", "mutation($workspace: ID!) { createConversation(botId: \"bot-general\", workspaceId: $workspace) { id } }", "variables", Map.of("workspace", scope))), HttpResponse.BodyHandlers.ofString());
        String id = json.readTree(create.body()).path("data").path("createConversation").path("id").asText();
        assertFalse(id.isBlank(), create.body());
        String run = UUID.randomUUID().toString();
        var body = Map.of("threadId", id, "runId", run, "state", Map.of(), "messages", List.of(Map.of("id", UUID.randomUUID().toString(), "role", "user", "content", "Synthetic ledger question")), "tools", List.of(), "context", List.of(), "forwardedProps", Map.of());
        var stream = http.sendAsync(request("/api/ag-ui", body), HttpResponse.BodyHandlers.ofString());
        try {
            assertTrue(chatModel().awaitSubscribed(8, TimeUnit.SECONDS));
            assertEquals(1, jdbc.update("update workspace_monthly_budget set enabled=true,cap_usd=3 where workspace_id=? and partition='SIMULATION'", scope));
            chatModel().emitChunk("visible response");
            long started = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (titleAdapterInvocations.get() == 0 && System.nanoTime() < started) Thread.sleep(20);
            assertEquals(1, titleAdapterInvocations.get(), "admission must have entered the adapter");
            assertEquals(1, jdbc.queryForObject("select count(*) from provider_attempt where purpose='CONVERSATION_TITLE' and title_conversation_id=?", Integer.class, id));
            assertTrue(loopback.entered.await(5, TimeUnit.SECONDS), "adapter must reach the isolated provider transport");
            chatModel().complete();
            String runEvents = stream.get(8, TimeUnit.SECONDS).body();
            assertTrue(runEvents.contains("TEXT_MESSAGE_CONTENT") && runEvents.contains("visible response"), runEvents);
            assertTrue(runEvents.contains("RUN_FINISHED"), runEvents);
            assertEquals("CLAIMED", jdbc.queryForObject("select state from conversation_title_work where conversation_id=?", String.class, id));
            var messagesBefore = jdbc.queryForList("select id from message where conversation_id=? order by id", String.class, id);
            assertEquals(2, messagesBefore.size());
            var sessionsBefore = jdbc.queryForList("select id from ai_session where id=? order by id", String.class, id);
            assertEquals(List.of(id), sessionsBefore);
            var sessionEventsBefore = jdbc.queryForList("select id from ai_session_event where session_id=? order by id", String.class, id);
            var subscription = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/graphql"))
                    .header("Content-Type", "application/json").header("Accept", "text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("query", "subscription($id: ID!) { conversationTitleChanged(conversationId: $id) { id version state } }", "variables", Map.of("id", id))))).build();
            var pendingNotice = http.send(subscription, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, pendingNotice.statusCode());
            assertTrue(pendingNotice.body().contains("CLAIMED"), pendingNotice.body());
            var completedNotice = http.sendAsync(subscription, HttpResponse.BodyHandlers.ofInputStream());
            loopback.release.countDown();
            var noticeResponse = completedNotice.get(8, TimeUnit.SECONDS);
            assertEquals(200, noticeResponse.statusCode());
            try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(noticeResponse.body(), StandardCharsets.UTF_8))) {
                String line; StringBuilder events = new StringBuilder();
                do { line = reader.readLine(); assertNotNull(line, () -> "subscription ended: " + events); events.append(line).append('\n'); } while (!line.contains("conversationTitleChanged"));
                var notice = json.readTree(line.substring("data:".length())).path("data").path("conversationTitleChanged");
                java.util.Set<String> fields = new java.util.HashSet<>();
                notice.fieldNames().forEachRemaining(fields::add);
                assertEquals(java.util.Set.of("id", "version", "state"), fields);
                assertEquals(id, notice.path("id").asText());
                assertEquals("1", notice.path("version").asText());
                assertEquals("DONE", notice.path("state").asText());
            }
            assertEquals("DONE", jdbc.queryForObject("select state from conversation_title_work where conversation_id=?", String.class, id));
            assertEquals(1L, jdbc.queryForObject("select version from conversation_title_work where conversation_id=?", Long.class, id));
            assertEquals(messagesBefore, jdbc.queryForList("select id from message where conversation_id=? order by id", String.class, id));
            assertEquals(sessionsBefore, jdbc.queryForList("select id from ai_session where id=? order by id", String.class, id));
            assertEquals(sessionEventsBefore, jdbc.queryForList("select id from ai_session_event where session_id=? order by id", String.class, id));
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(8); String state = "";
            while (System.nanoTime() < end) {
                state = jdbc.queryForObject("select state from conversation_title_work where conversation_id=?", String.class, id);
                if ("DONE".equals(state) || "FAILED".equals(state)) break;
                Thread.sleep(20);
            }
            assertEquals("DONE", state, "controlled SDK title must complete before checking ledger");
            assertEquals(1, loopback.requests.get());
            assertTrue(loopback.validRequest, "Interactions wire parameters must match the bounded stateless contract");
            assertTrue(loopback.virtualThread); assertFalse(loopback.transactionActive);
            var titleRead = http.send(request("/graphql", Map.of("query", "query($id: ID!) { conversation(id: $id) { title } }", "variables", Map.of("id", id))), HttpResponse.BodyHandlers.ofString());
            assertEquals("Synthetic title", json.readTree(titleRead.body()).path("data").path("conversation").path("title").asText());
            assertEquals(1, jdbc.queryForObject("select count(*) from provider_attempt where purpose='CONVERSATION_TITLE' and title_conversation_id=? and run_id is null and workspace_id=? and actor_id='dev-user'", Integer.class, id, scope));
            assertEquals(1, jdbc.queryForObject("select count(*) from usage_snapshot s join provider_attempt a on a.id=s.attempt_id where a.purpose='CONVERSATION_TITLE' and a.title_conversation_id=? and s.semantic='FINAL'", Integer.class, id), "the SDK native usage must be durably captured");
            end = System.nanoTime() + TimeUnit.SECONDS.toNanos(6);
            int priced = 0;
            while (System.nanoTime() < end) {
                priced = jdbc.queryForObject("select count(*) from provider_attempt a join usage_cost_current c on c.attempt_id=a.id join usage_cost_estimate e on e.id=c.estimate_id where a.title_conversation_id=? and e.status='COMPLETE' and e.complete_amount=0.000011", Integer.class, id);
                if (priced == 1) break;
                Thread.sleep(20);
            }
            assertEquals(1, priced, "native title usage must produce the controlled catalog cost");
            assertEquals("SETTLED", jdbc.queryForObject("select state from workspace_title_reservation where conversation_id=?", String.class, id));
            String attempt = jdbc.queryForObject("select id from provider_attempt where title_conversation_id=?", String.class, id);
            var usage = http.send(request("/graphql", Map.of("query", "query($w: ID!, $a: ID!) { myUsageAttempt(workspaceId: $w, id: $a) { id runId purpose titleConversationId input { value } output { value } cost { status completeAmount } } }", "variables", Map.of("w", scope, "a", attempt))), HttpResponse.BodyHandlers.ofString());
            var report = json.readTree(usage.body());
            assertFalse(report.has("errors"), report.toString());
            var observed = report.path("data").path("myUsageAttempt");
            assertEquals("CONVERSATION_TITLE", observed.path("purpose").asText());
            assertEquals(id, observed.path("titleConversationId").asText());
            assertTrue(observed.path("runId").isNull());
            assertEquals("7", observed.path("input").path("value").asText());
            assertEquals("3", observed.path("output").path("value").asText());
            assertEquals("COMPLETE", observed.path("cost").path("status").asText());
            assertEquals(0, new java.math.BigDecimal("0.000011").compareTo(new java.math.BigDecimal(observed.path("cost").path("completeAmount").asText())));
            var reportHttp = http.send(request("/graphql", Map.of("query", "query($w: ID!) { myUsageReport(workspaceId: $w, simulation: true, model: \"gemini-3.5-flash-lite\") { summary { recordedAttemptCount completeTotal } nativeGroups { endpoint input { knownValue } output { knownValue } cacheRead { knownValue } } attempts { nodes { id purpose titleConversationId } } } }", "variables", Map.of("w", scope))), HttpResponse.BodyHandlers.ofString());
            var aggregate = json.readTree(reportHttp.body());
            assertFalse(aggregate.has("errors"), aggregate.toString());
            var titleReport = aggregate.path("data").path("myUsageReport");
            assertEquals("1", titleReport.path("summary").path("recordedAttemptCount").asText());
            assertEquals(0,new java.math.BigDecimal("0.000011").compareTo(new java.math.BigDecimal(titleReport.path("summary").path("completeTotal").asText())));
            assertEquals("7", titleReport.path("nativeGroups").get(0).path("input").path("knownValue").asText());
            assertEquals("3", titleReport.path("nativeGroups").get(0).path("output").path("knownValue").asText());
            assertEquals("0", titleReport.path("nativeGroups").get(0).path("cacheRead").path("knownValue").asText());
            assertEquals("CONVERSATION_TITLE", titleReport.path("attempts").path("nodes").get(0).path("purpose").asText());
            assertEquals(1, jdbc.queryForObject("select count(*) from chat_run where conversation_id=?", Integer.class, id));
            assertNoSyntheticSecrets(output);
        } finally { loopback.release.countDown(); loopback.hold = false; loopback.success = false; chatModel().complete(); }
    }

    private static void assertNoSyntheticSecrets(org.springframework.boot.test.system.CapturedOutput output) {
        String logs = output.getAll();
        assertFalse(logs.contains("synthetic-test-only"), "synthetic key appeared in captured logs");
        assertFalse(logs.contains("Synthetic ledger question"), "synthetic success prompt appeared in captured logs");
        assertFalse(logs.contains("Synthetic failure question"), "synthetic failure prompt appeared in captured logs");
        assertFalse(logs.contains("Synthetic title"), "synthetic model output appeared in captured logs");
    }

    @Test void overBoundTitleIsUnknownWithoutCountingCostAndHoldTwice() throws Exception {
        assertTrue(environment.matchesProfiles("test") && environment.getProperty("bot-ai-provider") == null);
        try (var connection = dataSource.getConnection()) { assertEquals(postgres.getJdbcUrl(), connection.getMetaData().getURL()); }
        chatModel().reset(); chatModel().setHoldMode(true);
        loopback.requests.set(0); loopback.success=true; loopback.hold=true; loopback.entered=new CountDownLatch(1); loopback.release=new CountDownLatch(1);
        String scope="title-overbound-"+UUID.randomUUID();
        jdbc.update("insert into workspace(id,name) values (?,?)",scope,"Synthetic overbound");
        jdbc.update("insert into workspace_member(workspace_id,user_id,role,active) values (?,'dev-user','MANAGER',true)",scope);
        jdbc.update("insert into bot(id,name,system_prompt,created_at) values ('bot-general','General','test',current_timestamp) on conflict(id) do nothing");
        var price=new io.github.samzhu.bot.pricing.PriceSnapshot("GOOGLE","interactions","gemini-3.5-flash-lite","GEMINI_API","synthetic",Map.of("input","1","cache_read","1","output","1","thoughts","1"));
        catalog.publish("overbound-"+UUID.randomUUID(),"synthetic-test-only",List.of(new io.github.samzhu.bot.pricing.PriceCatalog.Entry(price,java.time.Instant.now().minusSeconds(60),java.time.Instant.now().plusSeconds(3600))));
        var created=http.send(request("/graphql",Map.of("query","mutation($workspace: ID!) { createConversation(botId: \"bot-general\", workspaceId: $workspace) { id } }","variables",Map.of("workspace",scope))),HttpResponse.BodyHandlers.ofString());
        String id=json.readTree(created.body()).path("data").path("createConversation").path("id").asText();
        String run=UUID.randomUUID().toString();
        var body=Map.of("threadId",id,"runId",run,"state",Map.of(),"messages",List.of(Map.of("id",UUID.randomUUID().toString(),"role","user","content","Synthetic ledger question")),"tools",List.of(),"context",List.of(),"forwardedProps",Map.of());
        var stream=http.sendAsync(request("/api/ag-ui",body),HttpResponse.BodyHandlers.ofString());
        try {
            assertTrue(chatModel().awaitSubscribed(8,TimeUnit.SECONDS));
            jdbc.update("update workspace_monthly_budget set enabled=true,cap_usd=3 where workspace_id=? and partition='SIMULATION'",scope);
            jdbc.update("insert into member_weekly_budget(workspace_id,member_user_id,partition,enabled,cap_usd) values (?,'dev-user','SIMULATION',true,3)",scope);
            chatModel().emitChunk("visible response");
            assertTrue(loopback.entered.await(8,TimeUnit.SECONDS));
            chatModel().complete(); assertTrue(stream.get(10,TimeUnit.SECONDS).body().contains("RUN_FINISHED"));
            // Controlled test-only evidence gives the chat zero cost; only the title affects the gate.
            String chatAttempt=jdbc.queryForObject("select id from provider_attempt where run_id=?",String.class,run);
            long version=jdbc.queryForObject("select projection_version from provider_attempt where id=?",Long.class,chatAttempt);
            String estimate=UUID.randomUUID().toString();
            jdbc.update("insert into usage_cost_estimate(id,attempt_id,source_version,price_snapshot_id,calculator_version,status,known_subtotal,complete_amount,details,payload_hash) values (?,?,?,null,'synthetic','COMPLETE',0,0,'{}'::jsonb,'synthetic')",estimate,chatAttempt,version);
            jdbc.update("insert into usage_cost_current(attempt_id,estimate_id) values (?,?) on conflict(attempt_id) do update set estimate_id=excluded.estimate_id",chatAttempt,estimate);
            jdbc.update("update workspace_title_reservation set amount_usd=0.000010 where conversation_id=?",id);
            loopback.release.countDown();
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(8); String state="";
            while(System.nanoTime()<end) { state=jdbc.queryForObject("select state from conversation_title_work where conversation_id=?",String.class,id); if("DONE".equals(state)||"FAILED".equals(state)) break; Thread.sleep(20); }
            assertEquals("DONE",state);
            assertEquals("HELD",jdbc.queryForObject("select state from workspace_title_reservation where conversation_id=?",String.class,id));
            String titleAttempt=jdbc.queryForObject("select id from provider_attempt where title_conversation_id=?",String.class,id);
            String titleVersion=jdbc.queryForObject("select projection_version::text from provider_attempt where id=?",String.class,titleAttempt);
            String titleRevision=jdbc.queryForObject("select estimate_id from usage_cost_current where attempt_id=?",String.class,titleAttempt);
            String titleCatalog=jdbc.queryForObject("select catalog_revision_id from provider_attempt where id=?",String.class,titleAttempt);
            String reconcile="mutation($input:ReconcileCostInput!){reconcileCost(input:$input){revision{status completeAmount}}}";
            var reconcileInput=Map.of("workspaceId",scope,"attemptId",titleAttempt,"expectedRevision",titleRevision,"expectedSourceProjectionVersion",titleVersion,"idempotencyKey","overbound-reconcile","catalogRevisionId",titleCatalog,"evidenceReference","synthetic","reason","verify bound");
            var reconciled=json.readTree(http.send(request("/graphql",Map.of("query",reconcile,"variables",Map.of("input",reconcileInput))),HttpResponse.BodyHandlers.ofString()).body());
            assertFalse(reconciled.has("errors"),reconciled.toString());
            assertEquals("COMPLETE",reconciled.path("data").path("reconcileCost").path("revision").path("status").asText());
            assertEquals("HELD",jdbc.queryForObject("select state from workspace_title_reservation where conversation_id=?",String.class,id));
            assertEquals(1,loopback.requests.get());
            jdbc.update("update workspace_monthly_budget set cap_usd=0.000015 where workspace_id=? and partition='SIMULATION'",scope);
            jdbc.update("update member_weekly_budget set cap_usd=0.000015 where workspace_id=? and partition='SIMULATION'",scope);
            var views=http.send(request("/graphql",Map.of("query","query($w: ID!) { workspaceMonthlyBudget(workspaceId:$w, simulation:true) { state reason knownSubtotalUsd } myMemberWeeklyBudget(workspaceId:$w, simulation:true) { state reason knownSubtotalUsd } }","variables",Map.of("w",scope))),HttpResponse.BodyHandlers.ofString());
            var result=json.readTree(views.body()); assertFalse(result.has("errors"),result.toString());
            for(String field:List.of("workspaceMonthlyBudget","myMemberWeeklyBudget")) {
                var budget=result.path("data").path(field);
                assertEquals("BLOCKED_UNKNOWN",budget.path("state").asText(),result.toString());
                assertEquals(0,new java.math.BigDecimal("0.000011").compareTo(new java.math.BigDecimal(budget.path("knownSubtotalUsd").asText())));
            }
            var secondCreated=http.send(request("/graphql",Map.of("query","mutation($workspace: ID!) { createConversation(botId: \"bot-general\", workspaceId: $workspace) { id } }","variables",Map.of("workspace",scope))),HttpResponse.BodyHandlers.ofString());
            String nextId=json.readTree(secondCreated.body()).path("data").path("createConversation").path("id").asText();
            var next=Map.of("threadId",nextId,"runId",UUID.randomUUID().toString(),"state",Map.of(),"messages",List.of(Map.of("id",UUID.randomUUID().toString(),"role","user","content","Synthetic next chat")),"tools",List.of(),"context",List.of(),"forwardedProps",Map.of());
            var denied=http.send(request("/api/ag-ui",next),HttpResponse.BodyHandlers.ofString());
            assertEquals("MONTH_UNKNOWN",json.readTree(denied.body()).path("code").asText(),denied.body());
            jdbc.update("update workspace_monthly_budget set enabled=false where workspace_id=? and partition='SIMULATION'",scope);
            var weeklyDenied=http.send(request("/api/ag-ui",next),HttpResponse.BodyHandlers.ofString());
            assertEquals("WEEK_UNKNOWN",json.readTree(weeklyDenied.body()).path("code").asText(),weeklyDenied.body());
        } finally { loopback.release.countDown(); loopback.hold=false; loopback.success=false; chatModel().complete(); }
    }

    @Test void peerAdmissionWaitsForAtomicTitleSettlement() throws Exception {
        var app=new org.springframework.boot.builder.SpringApplicationBuilder(BotApplication.class,TestChatModelConfiguration.class,TestUsageCaptureConfiguration.class,TestProvisioningConfiguration.class).profiles("test");
        app.initializers(context -> context.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("isolated-peer",Map.of(
                "server.port","0","spring.datasource.url",postgres.getJdbcUrl(),"spring.datasource.username",postgres.getUsername(),"spring.datasource.password",postgres.getPassword(),"spring.docker.compose.enabled","false"))));
        try (var peer=app.run(); var barrier=dataSource.getConnection()) {
            int peerPort=peer.getEnvironment().getRequiredProperty("local.server.port",Integer.class);
            chatModel().reset(); chatModel().setHoldMode(true);
            loopback.requests.set(0); loopback.success=true; loopback.hold=true; loopback.entered=new CountDownLatch(1); loopback.release=new CountDownLatch(1);
            String scope="title-atomic-peer-"+UUID.randomUUID();
            jdbc.update("insert into workspace(id,name) values (?,?)",scope,"Synthetic atomic peer");
            jdbc.update("insert into workspace_member(workspace_id,user_id,role,active) values (?,'dev-user','MANAGER',true)",scope);
            jdbc.update("insert into bot(id,name,system_prompt,created_at) values ('bot-general','General','test',current_timestamp) on conflict(id) do nothing");
            var price=new io.github.samzhu.bot.pricing.PriceSnapshot("GOOGLE","interactions","gemini-3.5-flash-lite","GEMINI_API","synthetic",Map.of("input","1","cache_read","1","output","1","thoughts","1"));
            catalog.publish("peer-"+UUID.randomUUID(),"synthetic-test-only",List.of(new io.github.samzhu.bot.pricing.PriceCatalog.Entry(price,java.time.Instant.now().minusSeconds(60),java.time.Instant.now().plusSeconds(3600))));
            var mutation=Map.of("query","mutation($workspace: ID!) { createConversation(botId: \"bot-general\", workspaceId: $workspace) { id } }","variables",Map.of("workspace",scope));
            String first=json.readTree(http.send(request("/graphql",mutation),HttpResponse.BodyHandlers.ofString()).body()).path("data").path("createConversation").path("id").asText();
            String second=json.readTree(http.send(request("/graphql",mutation),HttpResponse.BodyHandlers.ofString()).body()).path("data").path("createConversation").path("id").asText();
            String run=UUID.randomUUID().toString();
            var body=Map.of("threadId",first,"runId",run,"state",Map.of(),"messages",List.of(Map.of("id",UUID.randomUUID().toString(),"role","user","content","Synthetic ledger question")),"tools",List.of(),"context",List.of(),"forwardedProps",Map.of());
            var stream=http.sendAsync(request("/api/ag-ui",body),HttpResponse.BodyHandlers.ofString());
            try {
                assertTrue(chatModel().awaitSubscribed(8,TimeUnit.SECONDS));
                jdbc.update("update workspace_monthly_budget set enabled=true,cap_usd=3 where workspace_id=? and partition='SIMULATION'",scope);
                chatModel().emitChunk("visible response"); assertTrue(loopback.entered.await(8,TimeUnit.SECONDS));
                chatModel().complete(); assertTrue(stream.get(10,TimeUnit.SECONDS).body().contains("RUN_FINISHED"));
                String chatAttempt=jdbc.queryForObject("select id from provider_attempt where run_id=?",String.class,run);
                long version=jdbc.queryForObject("select projection_version from provider_attempt where id=?",Long.class,chatAttempt);
                String estimate=UUID.randomUUID().toString();
                jdbc.update("insert into usage_cost_estimate(id,attempt_id,source_version,price_snapshot_id,calculator_version,status,known_subtotal,complete_amount,details,payload_hash) values (?,?,?,null,'synthetic','COMPLETE',0,0,'{}'::jsonb,'synthetic')",estimate,chatAttempt,version);
                jdbc.update("insert into usage_cost_current(attempt_id,estimate_id) values (?,?) on conflict(attempt_id) do update set estimate_id=excluded.estimate_id",chatAttempt,estimate);
                jdbc.update("update workspace_monthly_budget_period set known_subtotal_usd=0,terminal_effective_unknown=false where workspace_id=? and partition='SIMULATION'",scope);
                jdbc.update("update workspace_monthly_budget set cap_usd=1.048645 where workspace_id=? and partition='SIMULATION'",scope);
                jdbc.execute("create function test_pause_title_settlement() returns trigger language plpgsql as $$ begin if new.state='SETTLED' then perform pg_advisory_xact_lock(987654321); end if; return new; end $$");
                jdbc.execute("create trigger test_pause_title_settlement before update on workspace_title_reservation for each row execute function test_pause_title_settlement()");
                try (var statement=barrier.createStatement()) { statement.execute("select pg_advisory_lock(987654321)"); }
                loopback.release.countDown();
                long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(8); int waiting=0;
                while(System.nanoTime()<end) { waiting=jdbc.queryForObject("select count(*) from pg_stat_activity where wait_event='advisory' and query like '%workspace_title_reservation%'",Integer.class); if(waiting>0) break; Thread.sleep(20); }
                assertTrue(waiting>0,"publication must reach the held settlement barrier");
                var next=Map.of("threadId",second,"runId",UUID.randomUUID().toString(),"state",Map.of(),"messages",List.of(Map.of("id",UUID.randomUUID().toString(),"role","user","content","Synthetic peer chat")),"tools",List.of(),"context",List.of(),"forwardedProps",Map.of());
                var req=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+peerPort+"/api/ag-ui")).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(next))).build();
                var peerRun=http.sendAsync(req,HttpResponse.BodyHandlers.ofString());
                Thread.sleep(300); assertFalse(peerRun.isDone(),"peer admission must wait for the atomic replacement");
                try (var statement=barrier.createStatement()) { statement.execute("select pg_advisory_unlock(987654321)"); }
                var response=peerRun.get(12,TimeUnit.SECONDS); assertEquals(200,response.statusCode(),response.body());
                assertTrue(response.body().contains("RUN_FINISHED"),response.body());
                assertEquals("SETTLED",jdbc.queryForObject("select state from workspace_title_reservation where conversation_id=?",String.class,first));
            } finally {
                try (var statement=barrier.createStatement()) { statement.execute("select pg_advisory_unlock(987654321)"); }
                loopback.release.countDown(); loopback.hold=false; loopback.success=false; chatModel().complete();
                jdbc.execute("drop trigger if exists test_pause_title_settlement on workspace_title_reservation");
                jdbc.execute("drop function if exists test_pause_title_settlement()");
            }
        }
    }

    @Test void peerWeeklyConfigurationWaitsForTitleAdmission() throws Exception {
        var app=new org.springframework.boot.builder.SpringApplicationBuilder(BotApplication.class,TestChatModelConfiguration.class,TestUsageCaptureConfiguration.class,TestProvisioningConfiguration.class).profiles("test");
        app.initializers(context -> context.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("isolated-peer",Map.of(
                "server.port","0","spring.datasource.url",postgres.getJdbcUrl(),"spring.datasource.username",postgres.getUsername(),"spring.datasource.password",postgres.getPassword(),"spring.docker.compose.enabled","false"))));
        try (var peer=app.run(); var barrier=dataSource.getConnection()) {
            int peerPort=peer.getEnvironment().getRequiredProperty("local.server.port",Integer.class);
            chatModel().reset(); chatModel().setHoldMode(true);
            loopback.requests.set(0); loopback.success=true; loopback.hold=true; loopback.entered=new CountDownLatch(1); loopback.release=new CountDownLatch(1);
            String scope="title-atomic-peer-"+UUID.randomUUID();
            jdbc.update("insert into workspace(id,name) values (?,?)",scope,"Synthetic atomic peer");
            jdbc.update("insert into workspace_member(workspace_id,user_id,role,active) values (?,'dev-user','MANAGER',true)",scope);
            jdbc.update("insert into bot(id,name,system_prompt,created_at) values ('bot-general','General','test',current_timestamp) on conflict(id) do nothing");
            var price=new io.github.samzhu.bot.pricing.PriceSnapshot("GOOGLE","interactions","gemini-3.5-flash-lite","GEMINI_API","synthetic",Map.of("input","1","cache_read","1","output","1","thoughts","1"));
            catalog.publish("peer-"+UUID.randomUUID(),"synthetic-test-only",List.of(new io.github.samzhu.bot.pricing.PriceCatalog.Entry(price,java.time.Instant.now().minusSeconds(60),java.time.Instant.now().plusSeconds(3600))));
            var mutation=Map.of("query","mutation($workspace: ID!) { createConversation(botId: \"bot-general\", workspaceId: $workspace) { id } }","variables",Map.of("workspace",scope));
            String first=json.readTree(http.send(request("/graphql",mutation),HttpResponse.BodyHandlers.ofString()).body()).path("data").path("createConversation").path("id").asText();
            String second=json.readTree(http.send(request("/graphql",mutation),HttpResponse.BodyHandlers.ofString()).body()).path("data").path("createConversation").path("id").asText();
            String run=UUID.randomUUID().toString();
            var body=Map.of("threadId",first,"runId",run,"state",Map.of(),"messages",List.of(Map.of("id",UUID.randomUUID().toString(),"role","user","content","Synthetic ledger question")),"tools",List.of(),"context",List.of(),"forwardedProps",Map.of());
            var stream=http.sendAsync(request("/api/ag-ui",body),HttpResponse.BodyHandlers.ofString());
            try {
                assertTrue(chatModel().awaitSubscribed(8,TimeUnit.SECONDS));
                jdbc.update("update workspace_monthly_budget set enabled=true,cap_usd=3 where workspace_id=? and partition='SIMULATION'",scope);
                jdbc.execute("create function test_pause_title_reservation() returns trigger language plpgsql as $$ begin perform pg_advisory_xact_lock(987654322); return new; end $$");
                jdbc.execute("create trigger test_pause_title_reservation before insert on workspace_title_reservation for each row execute function test_pause_title_reservation()");
                try (var statement=barrier.createStatement()) { statement.execute("select pg_advisory_lock(987654322)"); }
                chatModel().emitChunk("visible response");
                long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(8); int waiting=0;
                while(System.nanoTime()<end) { waiting=jdbc.queryForObject("select count(*) from pg_stat_activity where wait_event='advisory' and query like '%workspace_title_reservation%'",Integer.class); if(waiting>0) break; Thread.sleep(20); }
                assertTrue(waiting>0,"title admission must reach reservation barrier");
                var configure=Map.of("query","mutation($w: ID!) { configureMemberWeeklyBudget(input:{workspaceId:$w,memberUserId:\"dev-user\",simulation:true,enabled:true,capUsd:\"0\",expectedConfigVersion:\"0\",idempotencyKey:\"peer-weekly\"}) { outcome budget { state } } }","variables",Map.of("w",scope));
                var req=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+peerPort+"/graphql")).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(configure))).build();
                var configuration=http.sendAsync(req,HttpResponse.BodyHandlers.ofString());
                Thread.sleep(300); assertFalse(configuration.isDone(),"peer weekly config must wait for title admission");
                try (var statement=barrier.createStatement()) { statement.execute("select pg_advisory_unlock(987654322)"); }
                var result=json.readTree(configuration.get(12,TimeUnit.SECONDS).body());
                assertEquals("APPLIED",result.path("data").path("configureMemberWeeklyBudget").path("outcome").asText(),result.toString());
                assertTrue(loopback.entered.await(8,TimeUnit.SECONDS));
                var next=Map.of("threadId",second,"runId",UUID.randomUUID().toString(),"state",Map.of(),"messages",List.of(Map.of("id",UUID.randomUUID().toString(),"role","user","content","Synthetic peer chat")),"tools",List.of(),"context",List.of(),"forwardedProps",Map.of());
                var nextReq=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+peerPort+"/api/ag-ui")).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(next))).build();
                var denied=http.send(nextReq,HttpResponse.BodyHandlers.ofString());
                assertEquals("WEEK_CAP",json.readTree(denied.body()).path("code").asText(),denied.body());
            } finally {
                try (var statement=barrier.createStatement()) { statement.execute("select pg_advisory_unlock(987654322)"); }
                loopback.release.countDown(); loopback.hold=false; loopback.success=false; chatModel().complete();
                jdbc.execute("drop trigger if exists test_pause_title_reservation on workspace_title_reservation");
                jdbc.execute("drop function if exists test_pause_title_reservation()");
            }
        }
    }

    @Test void titleSettlementStaysInOriginalTaipeiWeekAndMonth() throws Exception {
        TEST_NOW.set(java.time.Instant.parse("2026-05-31T15:59:50Z"));
        chatModel().reset(); chatModel().setHoldMode(true);
        loopback.requests.set(0); loopback.success=true; loopback.hold=true; loopback.entered=new CountDownLatch(1); loopback.release=new CountDownLatch(1);
        String scope="title-period-"+UUID.randomUUID();
        jdbc.update("insert into workspace(id,name) values (?,?)",scope,"Synthetic period");
        jdbc.update("insert into workspace_member(workspace_id,user_id,role,active) values (?,'dev-user','MANAGER',true)",scope);
        jdbc.update("insert into bot(id,name,system_prompt,created_at) values ('bot-general','General','test',current_timestamp) on conflict(id) do nothing");
        var price=new io.github.samzhu.bot.pricing.PriceSnapshot("GOOGLE","interactions","gemini-3.5-flash-lite","GEMINI_API","synthetic",Map.of("input","1","cache_read","1","output","1","thoughts","1"));
        catalog.publish("period-"+UUID.randomUUID(),"synthetic-test-only",List.of(new io.github.samzhu.bot.pricing.PriceCatalog.Entry(price,java.time.Instant.parse("2026-01-01T00:00:00Z"),java.time.Instant.parse("2027-01-01T00:00:00Z"))));
        var created=http.send(request("/graphql",Map.of("query","mutation($workspace: ID!) { createConversation(botId: \"bot-general\", workspaceId: $workspace) { id } }","variables",Map.of("workspace",scope))),HttpResponse.BodyHandlers.ofString());
        String id=json.readTree(created.body()).path("data").path("createConversation").path("id").asText();
        var body=Map.of("threadId",id,"runId",UUID.randomUUID().toString(),"state",Map.of(),"messages",List.of(Map.of("id",UUID.randomUUID().toString(),"role","user","content","Synthetic ledger question")),"tools",List.of(),"context",List.of(),"forwardedProps",Map.of());
        var stream=http.sendAsync(request("/api/ag-ui",body),HttpResponse.BodyHandlers.ofString());
        try {
            assertTrue(chatModel().awaitSubscribed(8,TimeUnit.SECONDS));
            jdbc.update("update workspace_monthly_budget set enabled=true,cap_usd=3 where workspace_id=? and partition='SIMULATION'",scope);
            jdbc.update("insert into member_weekly_budget(workspace_id,member_user_id,partition,enabled,cap_usd) values (?,'dev-user','SIMULATION',true,3)",scope);
            chatModel().emitChunk("visible response"); assertTrue(loopback.entered.await(8,TimeUnit.SECONDS));
            assertEquals(java.time.LocalDate.parse("2026-05-01"),jdbc.queryForObject("select month_start from workspace_title_reservation where conversation_id=?",java.time.LocalDate.class,id));
            chatModel().complete(); assertTrue(stream.get(10,TimeUnit.SECONDS).body().contains("RUN_FINISHED"));
            TEST_NOW.set(java.time.Instant.parse("2026-05-31T16:00:10Z")); loopback.release.countDown();
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(8); String state="";
            while(System.nanoTime()<end) { state=jdbc.queryForObject("select state from conversation_title_work where conversation_id=?",String.class,id); if("DONE".equals(state)||"FAILED".equals(state)) break; Thread.sleep(20); }
            assertEquals("DONE",state); assertEquals("SETTLED",jdbc.queryForObject("select state from workspace_title_reservation where conversation_id=?",String.class,id));
            assertEquals(0,new java.math.BigDecimal("0.000011").compareTo(jdbc.queryForObject("select known_subtotal_usd from workspace_monthly_budget_period where workspace_id=? and partition='SIMULATION' and period_start=date '2026-05-01'",java.math.BigDecimal.class,scope)));
            var result=http.send(request("/graphql",Map.of("query","query($w: ID!) { workspaceMonthlyBudget(workspaceId:$w, simulation:true) { period knownSubtotalUsd } myMemberWeeklyBudget(workspaceId:$w, simulation:true) { periodStart knownSubtotalUsd } }","variables",Map.of("w",scope))),HttpResponse.BodyHandlers.ofString());
            var data=json.readTree(result.body()).path("data");
            assertEquals("2026-06",data.path("workspaceMonthlyBudget").path("period").asText());
            assertEquals("2026-05-31T16:00:00Z",data.path("myMemberWeeklyBudget").path("periodStart").asText());
            assertEquals(0,new java.math.BigDecimal(data.path("myMemberWeeklyBudget").path("knownSubtotalUsd").asText()).signum());
        } finally { loopback.release.countDown(); loopback.hold=false; loopback.success=false; chatModel().complete(); TEST_NOW.set(null); }
    }

    @Test void activeUnreservedRealChatDefersTitleUntilChatHasKnownCost() throws Exception {
        assertTrue(environment.matchesProfiles("test") && environment.getProperty("bot-ai-provider") == null);
        try (var connection = dataSource.getConnection()) { assertEquals(postgres.getJdbcUrl(), connection.getMetaData().getURL()); }
        REAL_FIXTURE.set(true); chatModel().reset(); chatModel().setHoldMode(true);
        loopback.requests.set(0); loopback.hold = true; loopback.success = true; loopback.entered = new CountDownLatch(1); loopback.release = new CountDownLatch(1);
        String scope = "title-defer-" + UUID.randomUUID();
        jdbc.update("insert into workspace(id,name) values (?,?)", scope, "Synthetic real-partition test");
        jdbc.update("insert into workspace_member(workspace_id,user_id,role,active) values (?,'dev-user','MANAGER',true)", scope);
        jdbc.update("insert into bot(id,name,system_prompt,created_at) values ('bot-general','General','test',current_timestamp) on conflict(id) do nothing");
        jdbc.update("insert into workspace_monthly_budget(workspace_id,partition,enabled,cap_usd) values (?,'REAL',true,10)", scope);
        jdbc.update("insert into member_weekly_budget(workspace_id,member_user_id,partition,enabled,cap_usd) values (?,'dev-user','REAL',true,10)", scope);
        var price = new io.github.samzhu.bot.pricing.PriceSnapshot("GOOGLE", "interactions", "gemini-3.5-flash-lite", "GEMINI_API", "synthetic", Map.of("input","1", "cache_read","1", "output","1", "thoughts","1"));
        catalog.publish("synthetic-defer-" + UUID.randomUUID(), "synthetic-test-only", List.of(new io.github.samzhu.bot.pricing.PriceCatalog.Entry(price, java.time.Instant.now().minusSeconds(60), java.time.Instant.now().plusSeconds(3600))));
        var created = http.send(request("/graphql", Map.of("query", "mutation($workspace: ID!) { createConversation(botId: \"bot-general\", workspaceId: $workspace) { id } }", "variables", Map.of("workspace", scope))), HttpResponse.BodyHandlers.ofString());
        String id = json.readTree(created.body()).path("data").path("createConversation").path("id").asText(); assertFalse(id.isBlank());
        String run = UUID.randomUUID().toString();
        var body = Map.of("threadId", id, "runId", run, "state", Map.of(), "messages", List.of(Map.of("id", UUID.randomUUID().toString(), "role", "user", "content", "Synthetic defer question")), "tools", List.of(), "context", List.of(), "forwardedProps", Map.of());
        var stream = http.sendAsync(request("/api/ag-ui", body), HttpResponse.BodyHandlers.ofString());
        try {
            assertTrue(chatModel().awaitSubscribed(8, TimeUnit.SECONDS));
            assertEquals(1, jdbc.queryForObject("select count(*) from provider_attempt where run_id=? and simulation=false and execution_outcome='IN_PROGRESS'", Integer.class, run));
            chatModel().emitChunk("visible response");
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5); String state = "";
            while (PARTITION_READS.get() == 0 && System.nanoTime() < end) Thread.sleep(20);
            assertTrue(PARTITION_READS.get() > 0, "title admission must inspect REAL partition");
            while (System.nanoTime() < end) {
                state = jdbc.queryForObject("select state from conversation_title_work where conversation_id=?", String.class, id);
                if ("READY".equals(state) || "FAILED".equals(state)) break;
                Thread.sleep(20);
            }
            assertEquals("READY", state, "known-undispatched title must remain eligible while real chat is unsettled");
            assertEquals(0, loopback.requests.get());
            assertEquals(0, jdbc.queryForObject("select count(*) from provider_attempt where title_conversation_id=?", Integer.class, id));
            chatModel().complete(); assertTrue(stream.get(10, TimeUnit.SECONDS).body().contains("RUN_FINISHED"));
            // Synthetic test-only trusted settlement; actual Google incomplete usage remains unknown and blocked.
            String attempt = jdbc.queryForObject("select id from provider_attempt where run_id=?", String.class, run);
            long version = jdbc.queryForObject("select projection_version from provider_attempt where id=?", Long.class, attempt);
            String estimate = UUID.randomUUID().toString();
            jdbc.update("insert into usage_cost_estimate(id,attempt_id,source_version,price_snapshot_id,calculator_version,status,known_subtotal,complete_amount,details,payload_hash) values (?,?,?,null,'synthetic','COMPLETE',0,0,'{}'::jsonb,'synthetic')", estimate, attempt, version);
            jdbc.update("insert into usage_cost_current(attempt_id,estimate_id) values (?,?) on conflict(attempt_id) do update set estimate_id=excluded.estimate_id", attempt, estimate);
            jdbc.update("update workspace_monthly_budget_period set known_subtotal_usd=0,terminal_effective_unknown=false where workspace_id=? and partition='REAL'", scope);
            assertTrue(loopback.entered.await(8, TimeUnit.SECONDS), "settled chat must permit the pending title");
            assertEquals(1, loopback.requests.get());
        } finally {
            loopback.release.countDown(); chatModel().complete();
            long cleanupUntil=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
            while (System.nanoTime()<cleanupUntil) {
                String work=jdbc.queryForObject("select state from conversation_title_work where conversation_id=?", String.class, id);
                if ("DONE".equals(work) || "FAILED".equals(work)) break;
                Thread.sleep(20);
            }
            loopback.hold=false; loopback.success=false; REAL_FIXTURE.set(false);
        }
    }

    @Test void monthlyOnlyRealBudgetDefersAndThenReservesTitle() throws Exception {
        assertTrue(environment.matchesProfiles("test") && environment.getProperty("bot-ai-provider") == null);
        try (var connection = dataSource.getConnection()) { assertEquals(postgres.getJdbcUrl(), connection.getMetaData().getURL()); }
        REAL_FIXTURE.set(true); chatModel().reset(); chatModel().setHoldMode(true);
        loopback.requests.set(0); loopback.hold = true; loopback.success = true; loopback.entered = new CountDownLatch(1); loopback.release = new CountDownLatch(1);
        String scope = "title-defer-" + UUID.randomUUID();
        jdbc.update("insert into workspace(id,name) values (?,?)", scope, "Synthetic real-partition test");
        jdbc.update("insert into workspace_member(workspace_id,user_id,role,active) values (?,'dev-user','MANAGER',true)", scope);
        jdbc.update("insert into bot(id,name,system_prompt,created_at) values ('bot-general','General','test',current_timestamp) on conflict(id) do nothing");
        jdbc.update("insert into workspace_monthly_budget(workspace_id,partition,enabled,cap_usd) values (?,'REAL',true,10)", scope);
        var price = new io.github.samzhu.bot.pricing.PriceSnapshot("GOOGLE", "interactions", "gemini-3.5-flash-lite", "GEMINI_API", "synthetic", Map.of("input","1", "cache_read","1", "output","1", "thoughts","1"));
        catalog.publish("synthetic-defer-" + UUID.randomUUID(), "synthetic-test-only", List.of(new io.github.samzhu.bot.pricing.PriceCatalog.Entry(price, java.time.Instant.now().minusSeconds(60), java.time.Instant.now().plusSeconds(3600))));
        var created = http.send(request("/graphql", Map.of("query", "mutation($workspace: ID!) { createConversation(botId: \"bot-general\", workspaceId: $workspace) { id } }", "variables", Map.of("workspace", scope))), HttpResponse.BodyHandlers.ofString());
        String id = json.readTree(created.body()).path("data").path("createConversation").path("id").asText(); assertFalse(id.isBlank());
        String run = UUID.randomUUID().toString();
        var body = Map.of("threadId", id, "runId", run, "state", Map.of(), "messages", List.of(Map.of("id", UUID.randomUUID().toString(), "role", "user", "content", "Synthetic defer question")), "tools", List.of(), "context", List.of(), "forwardedProps", Map.of());
        var stream = http.sendAsync(request("/api/ag-ui", body), HttpResponse.BodyHandlers.ofString());
        try {
            assertTrue(chatModel().awaitSubscribed(8, TimeUnit.SECONDS));
            assertEquals(1, jdbc.queryForObject("select count(*) from provider_attempt where run_id=? and simulation=false and execution_outcome='IN_PROGRESS'", Integer.class, run));
            chatModel().emitChunk("visible response");
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5); String state = "";
            while (PARTITION_READS.get() == 0 && System.nanoTime() < end) Thread.sleep(20);
            assertTrue(PARTITION_READS.get() > 0, "title admission must inspect REAL partition");
            while (System.nanoTime() < end) {
                state = jdbc.queryForObject("select state from conversation_title_work where conversation_id=?", String.class, id);
                if ("READY".equals(state) || "FAILED".equals(state)) break;
                Thread.sleep(20);
            }
            assertEquals("READY", state, "known-undispatched title must remain eligible while real chat is unsettled");
            assertEquals(0, loopback.requests.get());
            assertEquals(0, jdbc.queryForObject("select count(*) from provider_attempt where title_conversation_id=?", Integer.class, id));
            chatModel().complete(); assertTrue(stream.get(10, TimeUnit.SECONDS).body().contains("RUN_FINISHED"));
            // Synthetic test-only trusted settlement; actual Google incomplete usage remains unknown and blocked.
            String attempt = jdbc.queryForObject("select id from provider_attempt where run_id=?", String.class, run);
            long version = jdbc.queryForObject("select projection_version from provider_attempt where id=?", Long.class, attempt);
            String estimate = UUID.randomUUID().toString();
            jdbc.update("insert into usage_cost_estimate(id,attempt_id,source_version,price_snapshot_id,calculator_version,status,known_subtotal,complete_amount,details,payload_hash) values (?,?,?,null,'synthetic','COMPLETE',0,0,'{}'::jsonb,'synthetic')", estimate, attempt, version);
            jdbc.update("insert into usage_cost_current(attempt_id,estimate_id) values (?,?) on conflict(attempt_id) do update set estimate_id=excluded.estimate_id", attempt, estimate);
            jdbc.update("update workspace_monthly_budget_period set known_subtotal_usd=0,terminal_effective_unknown=false where workspace_id=? and partition='REAL'", scope);
            assertTrue(loopback.entered.await(8, TimeUnit.SECONDS), "settled chat must permit the pending title");
            assertEquals(1, loopback.requests.get());
            assertEquals(1, jdbc.queryForObject("select count(*) from workspace_title_reservation where conversation_id=? and partition='REAL' and state='HELD'", Integer.class, id));
        } finally {
            loopback.release.countDown(); chatModel().complete();
            long cleanupUntil=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
            while (System.nanoTime()<cleanupUntil) {
                String work=jdbc.queryForObject("select state from conversation_title_work where conversation_id=?", String.class, id);
                if ("DONE".equals(work) || "FAILED".equals(work)) break;
                Thread.sleep(20);
            }
            loopback.hold=false; loopback.success=false; REAL_FIXTURE.set(false);
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"MONTH,0.5,10,MONTH_CAP", "WEEK,10,0.5,WEEK_CAP", "BOTH,0.5,10,MONTH_CAP", "BOTH,10,0.5,WEEK_CAP"})
    void enabledRealBudgetDeniesWhenReservationExceedsCap(String mode, String monthlyCap, String weeklyCap, String expectedReason) throws Exception {
        assertTrue(environment.matchesProfiles("test") && environment.getProperty("bot-ai-provider") == null);
        try (var connection = dataSource.getConnection()) { assertEquals(postgres.getJdbcUrl(), connection.getMetaData().getURL()); }
        REAL_FIXTURE.set(true); chatModel().reset(); chatModel().setHoldMode(true);
        loopback.requests.set(0); loopback.hold = true; loopback.success = true; loopback.entered = new CountDownLatch(1); loopback.release = new CountDownLatch(1);
        String scope = "title-defer-" + UUID.randomUUID();
        jdbc.update("insert into workspace(id,name) values (?,?)", scope, "Synthetic real-partition test");
        jdbc.update("insert into workspace_member(workspace_id,user_id,role,active) values (?,'dev-user','MANAGER',true)", scope);
        jdbc.update("insert into bot(id,name,system_prompt,created_at) values ('bot-general','General','test',current_timestamp) on conflict(id) do nothing");
        if (!"WEEK".equals(mode)) jdbc.update("insert into workspace_monthly_budget(workspace_id,partition,enabled,cap_usd) values (?,'REAL',true,?::numeric)", scope, monthlyCap);
        if (!"MONTH".equals(mode)) jdbc.update("insert into member_weekly_budget(workspace_id,member_user_id,partition,enabled,cap_usd) values (?,'dev-user','REAL',true,?::numeric)", scope, weeklyCap);
        var price = new io.github.samzhu.bot.pricing.PriceSnapshot("GOOGLE", "interactions", "gemini-3.5-flash-lite", "GEMINI_API", "synthetic", Map.of("input","1", "cache_read","1", "output","1", "thoughts","1"));
        catalog.publish("synthetic-defer-" + UUID.randomUUID(), "synthetic-test-only", List.of(new io.github.samzhu.bot.pricing.PriceCatalog.Entry(price, java.time.Instant.now().minusSeconds(60), java.time.Instant.now().plusSeconds(3600))));
        var created = http.send(request("/graphql", Map.of("query", "mutation($workspace: ID!) { createConversation(botId: \"bot-general\", workspaceId: $workspace) { id } }", "variables", Map.of("workspace", scope))), HttpResponse.BodyHandlers.ofString());
        String id = json.readTree(created.body()).path("data").path("createConversation").path("id").asText(); assertFalse(id.isBlank());
        String run = UUID.randomUUID().toString();
        var body = Map.of("threadId", id, "runId", run, "state", Map.of(), "messages", List.of(Map.of("id", UUID.randomUUID().toString(), "role", "user", "content", "Synthetic defer question")), "tools", List.of(), "context", List.of(), "forwardedProps", Map.of());
        var stream = http.sendAsync(request("/api/ag-ui", body), HttpResponse.BodyHandlers.ofString());
        try {
            assertTrue(chatModel().awaitSubscribed(8, TimeUnit.SECONDS));
            assertEquals(1, jdbc.queryForObject("select count(*) from provider_attempt where run_id=? and simulation=false and execution_outcome='IN_PROGRESS'", Integer.class, run));
            chatModel().emitChunk("visible response");
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5); String state = "";
            while (PARTITION_READS.get() == 0 && System.nanoTime() < end) Thread.sleep(20);
            assertTrue(PARTITION_READS.get() > 0, "title admission must inspect REAL partition");
            while (System.nanoTime() < end) {
                state = jdbc.queryForObject("select state from conversation_title_work where conversation_id=?", String.class, id);
                if ("READY".equals(state) || "FAILED".equals(state)) break;
                Thread.sleep(20);
            }
            assertEquals("READY", state, "known-undispatched title must remain eligible while real chat is unsettled");
            assertEquals(0, loopback.requests.get());
            assertEquals(0, jdbc.queryForObject("select count(*) from provider_attempt where title_conversation_id=?", Integer.class, id));
            chatModel().complete(); assertTrue(stream.get(10, TimeUnit.SECONDS).body().contains("RUN_FINISHED"));
            // Synthetic test-only trusted settlement; actual Google incomplete usage remains unknown and blocked.
            String attempt = jdbc.queryForObject("select id from provider_attempt where run_id=?", String.class, run);
            long version = jdbc.queryForObject("select projection_version from provider_attempt where id=?", Long.class, attempt);
            String estimate = UUID.randomUUID().toString();
            jdbc.update("insert into usage_cost_estimate(id,attempt_id,source_version,price_snapshot_id,calculator_version,status,known_subtotal,complete_amount,details,payload_hash) values (?,?,?,null,'synthetic','COMPLETE',0,0,'{}'::jsonb,'synthetic')", estimate, attempt, version);
            jdbc.update("insert into usage_cost_current(attempt_id,estimate_id) values (?,?) on conflict(attempt_id) do update set estimate_id=excluded.estimate_id", attempt, estimate);
            jdbc.update("update workspace_monthly_budget_period set known_subtotal_usd=0,terminal_effective_unknown=false where workspace_id=? and partition='REAL'", scope);
            end=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
            while(System.nanoTime()<end) { state=jdbc.queryForObject("select state from conversation_title_work where conversation_id=?",String.class,id); if("FAILED".equals(state)) break; Thread.sleep(20); }
            assertEquals("FAILED",state);
            assertEquals(0,loopback.requests.get());
            assertEquals(0,jdbc.queryForObject("select count(*) from workspace_title_reservation where conversation_id=?",Integer.class,id));
            var sub=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/graphql")).header("Content-Type","application/json").header("Accept","text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("query","subscription($id: ID!) { conversationTitleChanged(conversationId:$id) { reason } }","variables",Map.of("id",id))))).build();
            assertTrue(http.send(sub,HttpResponse.BodyHandlers.ofString()).body().contains(expectedReason));
        } finally {
            loopback.release.countDown(); chatModel().complete();
            long cleanupUntil=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
            while (System.nanoTime()<cleanupUntil) {
                String work=jdbc.queryForObject("select state from conversation_title_work where conversation_id=?", String.class, id);
                if ("DONE".equals(work) || "FAILED".equals(work)) break;
                Thread.sleep(20);
            }
            loopback.hold=false; loopback.success=false; REAL_FIXTURE.set(false);
        }
    }

    @Test void weeklyOnlyRealBudgetDefersAndThenReservesTitle() throws Exception {
        assertTrue(environment.matchesProfiles("test") && environment.getProperty("bot-ai-provider") == null);
        try (var connection = dataSource.getConnection()) { assertEquals(postgres.getJdbcUrl(), connection.getMetaData().getURL()); }
        REAL_FIXTURE.set(true); chatModel().reset(); chatModel().setHoldMode(true);
        loopback.requests.set(0); loopback.hold = true; loopback.success = true; loopback.entered = new CountDownLatch(1); loopback.release = new CountDownLatch(1);
        String scope = "title-defer-" + UUID.randomUUID();
        jdbc.update("insert into workspace(id,name) values (?,?)", scope, "Synthetic real-partition test");
        jdbc.update("insert into workspace_member(workspace_id,user_id,role,active) values (?,'dev-user','MANAGER',true)", scope);
        jdbc.update("insert into bot(id,name,system_prompt,created_at) values ('bot-general','General','test',current_timestamp) on conflict(id) do nothing");
        jdbc.update("insert into member_weekly_budget(workspace_id,member_user_id,partition,enabled,cap_usd) values (?,'dev-user','REAL',true,10)", scope);
        var price = new io.github.samzhu.bot.pricing.PriceSnapshot("GOOGLE", "interactions", "gemini-3.5-flash-lite", "GEMINI_API", "synthetic", Map.of("input","1", "cache_read","1", "output","1", "thoughts","1"));
        catalog.publish("synthetic-defer-" + UUID.randomUUID(), "synthetic-test-only", List.of(new io.github.samzhu.bot.pricing.PriceCatalog.Entry(price, java.time.Instant.now().minusSeconds(60), java.time.Instant.now().plusSeconds(3600))));
        var created = http.send(request("/graphql", Map.of("query", "mutation($workspace: ID!) { createConversation(botId: \"bot-general\", workspaceId: $workspace) { id } }", "variables", Map.of("workspace", scope))), HttpResponse.BodyHandlers.ofString());
        String id = json.readTree(created.body()).path("data").path("createConversation").path("id").asText(); assertFalse(id.isBlank());
        String run = UUID.randomUUID().toString();
        var body = Map.of("threadId", id, "runId", run, "state", Map.of(), "messages", List.of(Map.of("id", UUID.randomUUID().toString(), "role", "user", "content", "Synthetic defer question")), "tools", List.of(), "context", List.of(), "forwardedProps", Map.of());
        var stream = http.sendAsync(request("/api/ag-ui", body), HttpResponse.BodyHandlers.ofString());
        try {
            assertTrue(chatModel().awaitSubscribed(8, TimeUnit.SECONDS));
            assertEquals(1, jdbc.queryForObject("select count(*) from provider_attempt where run_id=? and simulation=false and execution_outcome='IN_PROGRESS'", Integer.class, run));
            chatModel().emitChunk("visible response");
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5); String state = "";
            while (PARTITION_READS.get() == 0 && System.nanoTime() < end) Thread.sleep(20);
            assertTrue(PARTITION_READS.get() > 0, "title admission must inspect REAL partition");
            while (System.nanoTime() < end) {
                state = jdbc.queryForObject("select state from conversation_title_work where conversation_id=?", String.class, id);
                if ("READY".equals(state) || "FAILED".equals(state)) break;
                Thread.sleep(20);
            }
            assertEquals("READY", state, "known-undispatched title must remain eligible while real chat is unsettled");
            assertEquals(0, loopback.requests.get());
            assertEquals(0, jdbc.queryForObject("select count(*) from provider_attempt where title_conversation_id=?", Integer.class, id));
            chatModel().complete(); assertTrue(stream.get(10, TimeUnit.SECONDS).body().contains("RUN_FINISHED"));
            // Synthetic test-only trusted settlement; actual Google incomplete usage remains unknown and blocked.
            String attempt = jdbc.queryForObject("select id from provider_attempt where run_id=?", String.class, run);
            long version = jdbc.queryForObject("select projection_version from provider_attempt where id=?", Long.class, attempt);
            String estimate = UUID.randomUUID().toString();
            jdbc.update("insert into usage_cost_estimate(id,attempt_id,source_version,price_snapshot_id,calculator_version,status,known_subtotal,complete_amount,details,payload_hash) values (?,?,?,null,'synthetic','COMPLETE',0,0,'{}'::jsonb,'synthetic')", estimate, attempt, version);
            jdbc.update("insert into usage_cost_current(attempt_id,estimate_id) values (?,?) on conflict(attempt_id) do update set estimate_id=excluded.estimate_id", attempt, estimate);
            jdbc.update("update workspace_monthly_budget_period set known_subtotal_usd=0,terminal_effective_unknown=false where workspace_id=? and partition='REAL'", scope);
            assertTrue(loopback.entered.await(8, TimeUnit.SECONDS), "settled chat must permit the pending title");
            assertEquals(1, loopback.requests.get());
            assertEquals(1, jdbc.queryForObject("select count(*) from workspace_title_reservation where conversation_id=? and partition='REAL' and state='HELD'", Integer.class, id));
        } finally {
            loopback.release.countDown(); chatModel().complete();
            long cleanupUntil=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
            while (System.nanoTime()<cleanupUntil) {
                String work=jdbc.queryForObject("select state from conversation_title_work where conversation_id=?", String.class, id);
                if ("DONE".equals(work) || "FAILED".equals(work)) break;
                Thread.sleep(20);
            }
            loopback.hold=false; loopback.success=false; REAL_FIXTURE.set(false);
        }
    }

    @Test void provider503IsNotRetriedAndKeepsUnknownHold(org.springframework.boot.test.system.CapturedOutput output) throws Exception { checkProviderFailure("503"); assertNoSyntheticSecrets(output); }

    @Test void providerRedirectAndDisconnectCannotRepeatHttp() throws Exception {
        checkProviderFailure("redirect");
        checkProviderFailure("disconnect");
    }

    private void checkProviderFailure(String failureMode) throws Exception {
        assertTrue(environment.matchesProfiles("test") && environment.getProperty("bot-ai-provider") == null);
        try (var connection = dataSource.getConnection()) { assertEquals(postgres.getJdbcUrl(), connection.getMetaData().getURL()); }
        chatModel().reset(); chatModel().setHoldMode(true);
        loopback.requests.set(0); loopback.success=false; loopback.hold=false; loopback.failureMode=failureMode; loopback.entered=new CountDownLatch(1);
        String scope="title-error-" + UUID.randomUUID();
        jdbc.update("insert into workspace(id,name) values (?,?)", scope, "Synthetic provider failure");
        jdbc.update("insert into workspace_member(workspace_id,user_id,role,active) values (?,'dev-user','MANAGER',true)", scope);
        jdbc.update("insert into bot(id,name,system_prompt,created_at) values ('bot-general','General','test',current_timestamp) on conflict(id) do nothing");
        var price = new io.github.samzhu.bot.pricing.PriceSnapshot("GOOGLE", "interactions", "gemini-3.5-flash-lite", "GEMINI_API", "synthetic", Map.of("input","1", "cache_read","1", "output","1", "thoughts","1"));
        catalog.publish("synthetic-error-"+UUID.randomUUID(), "synthetic-test-only", List.of(new io.github.samzhu.bot.pricing.PriceCatalog.Entry(price, java.time.Instant.now().minusSeconds(60), java.time.Instant.now().plusSeconds(3600))));
        var created = http.send(request("/graphql", Map.of("query", "mutation($workspace: ID!) { createConversation(botId: \"bot-general\", workspaceId: $workspace) { id } }", "variables", Map.of("workspace", scope))), HttpResponse.BodyHandlers.ofString());
        String id = json.readTree(created.body()).path("data").path("createConversation").path("id").asText(); assertFalse(id.isBlank());
        var body = Map.of("threadId", id, "runId", UUID.randomUUID().toString(), "state", Map.of(), "messages", List.of(Map.of("id", UUID.randomUUID().toString(), "role", "user", "content", "Synthetic failure question")), "tools", List.of(), "context", List.of(), "forwardedProps", Map.of());
        var stream = http.sendAsync(request("/api/ag-ui", body), HttpResponse.BodyHandlers.ofString());
        try {
            assertTrue(chatModel().awaitSubscribed(8, TimeUnit.SECONDS));
            assertEquals(1, jdbc.update("update workspace_monthly_budget set enabled=true,cap_usd=3 where workspace_id=? and partition='SIMULATION'", scope));
            chatModel().emitChunk("visible response");
            assertTrue(loopback.entered.await(5, TimeUnit.SECONDS));
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(8); String state="";
            while (System.nanoTime()<end) {
                state=jdbc.queryForObject("select state from conversation_title_work where conversation_id=?", String.class,id);
                if ("FAILED".equals(state)) break;
                Thread.sleep(20);
            }
            assertEquals("FAILED",state);
            assertEquals("UNKNOWN",jdbc.queryForObject("select execution_outcome from provider_attempt where title_conversation_id=?",String.class,id));
            assertEquals("UNKNOWN",jdbc.queryForObject("select state from workspace_title_reservation where conversation_id=?",String.class,id));
            Thread.sleep(1500); // Observe at least one recovery scan after terminal state.
            assertEquals(1,loopback.requests.get());
            var read=http.send(request("/graphql",Map.of("query","query($id: ID!) { conversation(id: $id) { title } }","variables",Map.of("id",id))),HttpResponse.BodyHandlers.ofString());
            assertEquals("New Conversation",json.readTree(read.body()).path("data").path("conversation").path("title").asText());
            chatModel().complete(); assertTrue(stream.get(10,TimeUnit.SECONDS).body().contains("RUN_FINISHED"));
        } finally { chatModel().complete(); loopback.failureMode="503"; }
    }

    @Test void outstandingTitleHoldContributesToNewChatMonthAndWeekAdmission() throws Exception {
        assertTrue(environment.matchesProfiles("test") && environment.getProperty("bot-ai-provider") == null);
        try (var connection=dataSource.getConnection()) { assertEquals(postgres.getJdbcUrl(),connection.getMetaData().getURL()); }
        for (boolean weekly : new boolean[]{false,true}) {
            REAL_FIXTURE.set(false); chatModel().reset(); chatModel().setHoldMode(true);
            loopback.requests.set(0); loopback.success=false; loopback.hold=true; loopback.entered=new CountDownLatch(1); loopback.release=new CountDownLatch(1);
            String scope="title-hold-run-"+UUID.randomUUID();
            jdbc.update("insert into workspace(id,name) values (?,?)",scope,"Synthetic title hold");
            jdbc.update("insert into workspace_member(workspace_id,user_id,role,active) values (?,'dev-user','MANAGER',true)",scope);
            jdbc.update("insert into bot(id,name,system_prompt,created_at) values ('bot-general','General','test',current_timestamp) on conflict(id) do nothing");
            var price=new io.github.samzhu.bot.pricing.PriceSnapshot("GOOGLE","interactions","gemini-3.5-flash-lite","GEMINI_API","synthetic",Map.of("input","1","cache_read","1","output","1","thoughts","1"));
            catalog.publish("synthetic-run-"+UUID.randomUUID(),"synthetic-test-only",List.of(new io.github.samzhu.bot.pricing.PriceCatalog.Entry(price,java.time.Instant.now().minusSeconds(60),java.time.Instant.now().plusSeconds(3600))));
            var mutation=Map.of("query","mutation($workspace: ID!) { createConversation(botId: \"bot-general\", workspaceId: $workspace) { id } }","variables",Map.of("workspace",scope));
            String first=json.readTree(http.send(request("/graphql",mutation),HttpResponse.BodyHandlers.ofString()).body()).path("data").path("createConversation").path("id").asText();
            String second=json.readTree(http.send(request("/graphql",mutation),HttpResponse.BodyHandlers.ofString()).body()).path("data").path("createConversation").path("id").asText();
            assertFalse(first.isBlank()); assertFalse(second.isBlank());
            var initial=Map.of("threadId",first,"runId",UUID.randomUUID().toString(),"state",Map.of(),"messages",List.of(Map.of("id",UUID.randomUUID().toString(),"role","user","content","Synthetic existing chat")),"tools",List.of(),"context",List.of(),"forwardedProps",Map.of());
            var stream=http.sendAsync(request("/api/ag-ui",initial),HttpResponse.BodyHandlers.ofString());
            try {
                assertTrue(chatModel().awaitSubscribed(8,TimeUnit.SECONDS));
                if (weekly) jdbc.update("insert into member_weekly_budget(workspace_id,member_user_id,partition,enabled,cap_usd) values (?,'dev-user','SIMULATION',true,1.048640)",scope);
                else assertEquals(1,jdbc.update("update workspace_monthly_budget set enabled=true,cap_usd=1.048640 where workspace_id=? and partition='SIMULATION'",scope));
                chatModel().emitChunk("visible response"); assertTrue(loopback.entered.await(5,TimeUnit.SECONDS));
                assertEquals("HELD",jdbc.queryForObject("select state from workspace_title_reservation where conversation_id=?",String.class,first));
                var next=Map.of("threadId",second,"runId",UUID.randomUUID().toString(),"state",Map.of(),"messages",List.of(Map.of("id",UUID.randomUUID().toString(),"role","user","content","Synthetic next chat")),"tools",List.of(),"context",List.of(),"forwardedProps",Map.of());
                var denied=http.send(request("/api/ag-ui",next),HttpResponse.BodyHandlers.ofString());
                assertEquals(503,denied.statusCode(),denied.body());
                assertEquals(weekly?"WEEK_CAP":"MONTH_CAP",json.readTree(denied.body()).path("code").asText());
                assertEquals(1,chatModel().getRecordedPrompts().size());
                chatModel().complete(); assertTrue(stream.get(10,TimeUnit.SECONDS).body().contains("RUN_FINISHED"));
            } finally {
                loopback.release.countDown(); chatModel().complete();
                long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
                while (System.nanoTime()<end) {
                    String state=jdbc.queryForObject("select state from conversation_title_work where conversation_id=?",String.class,first);
                    if ("DONE".equals(state) || "FAILED".equals(state)) break;
                    Thread.sleep(20);
                }
                loopback.hold=false;
            }
        }
    }

    @Test void heldTitleDoesNotBlockAnotherAdmissibleChat() throws Exception {
        assertTrue(environment.matchesProfiles("test") && environment.getProperty("bot-ai-provider") == null);
        try (var connection=dataSource.getConnection()) { assertEquals(postgres.getJdbcUrl(),connection.getMetaData().getURL()); }
        for (boolean weekly : new boolean[]{false}) {
            REAL_FIXTURE.set(false); chatModel().reset(); chatModel().setHoldMode(true);
            loopback.requests.set(0); loopback.success=false; loopback.hold=true; loopback.entered=new CountDownLatch(1); loopback.release=new CountDownLatch(1);
            String scope="title-hold-run-"+UUID.randomUUID();
            jdbc.update("insert into workspace(id,name) values (?,?)",scope,"Synthetic title hold");
            jdbc.update("insert into workspace_member(workspace_id,user_id,role,active) values (?,'dev-user','MANAGER',true)",scope);
            jdbc.update("insert into bot(id,name,system_prompt,created_at) values ('bot-general','General','test',current_timestamp) on conflict(id) do nothing");
            var price=new io.github.samzhu.bot.pricing.PriceSnapshot("GOOGLE","interactions","gemini-3.5-flash-lite","GEMINI_API","synthetic",Map.of("input","1","cache_read","1","output","1","thoughts","1"));
            catalog.publish("synthetic-run-"+UUID.randomUUID(),"synthetic-test-only",List.of(new io.github.samzhu.bot.pricing.PriceCatalog.Entry(price,java.time.Instant.now().minusSeconds(60),java.time.Instant.now().plusSeconds(3600))));
            var mutation=Map.of("query","mutation($workspace: ID!) { createConversation(botId: \"bot-general\", workspaceId: $workspace) { id } }","variables",Map.of("workspace",scope));
            String first=json.readTree(http.send(request("/graphql",mutation),HttpResponse.BodyHandlers.ofString()).body()).path("data").path("createConversation").path("id").asText();
            String second=json.readTree(http.send(request("/graphql",mutation),HttpResponse.BodyHandlers.ofString()).body()).path("data").path("createConversation").path("id").asText();
            assertFalse(first.isBlank()); assertFalse(second.isBlank());
            var initial=Map.of("threadId",first,"runId",UUID.randomUUID().toString(),"state",Map.of(),"messages",List.of(Map.of("id",UUID.randomUUID().toString(),"role","user","content","Synthetic existing chat")),"tools",List.of(),"context",List.of(),"forwardedProps",Map.of());
            var stream=http.sendAsync(request("/api/ag-ui",initial),HttpResponse.BodyHandlers.ofString());
            try {
                assertTrue(chatModel().awaitSubscribed(8,TimeUnit.SECONDS));
                if (weekly) jdbc.update("insert into member_weekly_budget(workspace_id,member_user_id,partition,enabled,cap_usd) values (?,'dev-user','SIMULATION',true,1.048640)",scope);
                else assertEquals(1,jdbc.update("update workspace_monthly_budget set enabled=true,cap_usd=3 where workspace_id=? and partition='SIMULATION'",scope));
                chatModel().emitChunk("visible response"); assertTrue(loopback.entered.await(5,TimeUnit.SECONDS));
                assertEquals("HELD",jdbc.queryForObject("select state from workspace_title_reservation where conversation_id=?",String.class,first));
                var next=Map.of("threadId",second,"runId",UUID.randomUUID().toString(),"state",Map.of(),"messages",List.of(Map.of("id",UUID.randomUUID().toString(),"role","user","content","Synthetic next chat")),"tools",List.of(),"context",List.of(),"forwardedProps",Map.of());
                var accepted=http.sendAsync(request("/api/ag-ui",next),HttpResponse.BodyHandlers.ofString());
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
                while (chatModel().holdSubscriptions()<2 && System.nanoTime()<deadline) Thread.sleep(20);
                assertEquals(2,chatModel().holdSubscriptions(), "second chat must dispatch before held title returns");
                assertEquals("HELD",jdbc.queryForObject("select state from workspace_title_reservation where conversation_id=?",String.class,first));
                chatModel().emitChunk("second visible response");
                chatModel().complete();
                assertTrue(accepted.get(8,TimeUnit.SECONDS).body().contains("RUN_FINISHED"));
                assertTrue(stream.get(8,TimeUnit.SECONDS).body().contains("RUN_FINISHED"));
                assertTrue(loopback.requests.get() >= 1);
            } finally {
                loopback.release.countDown(); chatModel().complete();
                long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
                while (System.nanoTime()<end) {
                    int active=jdbc.queryForObject("select count(*) from conversation_title_work where conversation_id in (?,?) and state in ('READY','CLAIMED')",Integer.class,first,second);
                    if (active == 0) break;
                    Thread.sleep(20);
                }
                loopback.hold=false;
            }
        }
    }

    @Test void revokedMemberCannotDispatchTitleAfterChatStarted() throws Exception {
        assertTrue(environment.matchesProfiles("test") && environment.getProperty("bot-ai-provider") == null);
        try (var connection=dataSource.getConnection()) { assertEquals(postgres.getJdbcUrl(),connection.getMetaData().getURL()); }
        chatModel().reset(); chatModel().setHoldMode(true); loopback.requests.set(0); loopback.hold=false; loopback.success=false;
        String scope="title-revoked-"+UUID.randomUUID();
        jdbc.update("insert into workspace(id,name) values (?,?)",scope,"Synthetic revoked scope");
        jdbc.update("insert into workspace_member(workspace_id,user_id,role,active) values (?,'dev-user','MANAGER',true)",scope);
        jdbc.update("insert into bot(id,name,system_prompt,created_at) values ('bot-general','General','test',current_timestamp) on conflict(id) do nothing");
        var created=http.send(request("/graphql",Map.of("query","mutation($workspace: ID!) { createConversation(botId: \"bot-general\", workspaceId: $workspace) { id } }","variables",Map.of("workspace",scope))),HttpResponse.BodyHandlers.ofString());
        String id=json.readTree(created.body()).path("data").path("createConversation").path("id").asText(); assertFalse(id.isBlank());
        var body=Map.of("threadId",id,"runId",UUID.randomUUID().toString(),"state",Map.of(),"messages",List.of(Map.of("id",UUID.randomUUID().toString(),"role","user","content","Synthetic revoked request")),"tools",List.of(),"context",List.of(),"forwardedProps",Map.of());
        var stream=http.sendAsync(request("/api/ag-ui",body),HttpResponse.BodyHandlers.ofString());
        try {
            assertTrue(chatModel().awaitSubscribed(8,TimeUnit.SECONDS));
            assertEquals(1,jdbc.update("update workspace_member set active=false where workspace_id=? and user_id='dev-user'",scope));
            chatModel().emitChunk("visible response"); chatModel().complete();
            assertTrue(stream.get(10,TimeUnit.SECONDS).body().contains("RUN_FINISHED"));
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(8); String state="";
            while (System.nanoTime()<end) {
                state=jdbc.queryForObject("select state from conversation_title_work where conversation_id=?",String.class,id);
                if ("FAILED".equals(state)) break;
                Thread.sleep(20);
            }
            assertEquals("FAILED",state); assertEquals(0,loopback.requests.get());
            assertEquals(0,jdbc.queryForObject("select count(*) from provider_attempt where title_conversation_id=?",Integer.class,id));
            assertEquals("New Conversation",jdbc.queryForObject("select title from conversation where id=?",String.class,id));
        } finally { chatModel().complete(); }
    }

    @Test void stopFinishesWhileTitleHttpIsHeldThenTitleCommits() throws Exception {
        assertTrue(environment.matchesProfiles("test") && environment.getProperty("bot-ai-provider") == null);
        try (var connection=dataSource.getConnection()) { assertEquals(postgres.getJdbcUrl(),connection.getMetaData().getURL()); }
        chatModel().reset(); chatModel().setHoldMode(true); loopback.requests.set(0); loopback.success=true; loopback.hold=true; loopback.entered=new CountDownLatch(1); loopback.release=new CountDownLatch(1);
        String scope="title-stop-"+UUID.randomUUID();
        jdbc.update("insert into workspace(id,name) values (?,?)",scope,"Synthetic stop");
        jdbc.update("insert into workspace_member(workspace_id,user_id,role,active) values (?,'dev-user','MANAGER',true)",scope);
        jdbc.update("insert into bot(id,name,system_prompt,created_at) values ('bot-general','General','test',current_timestamp) on conflict(id) do nothing");
        var price=new io.github.samzhu.bot.pricing.PriceSnapshot("GOOGLE","interactions","gemini-3.5-flash-lite","GEMINI_API","synthetic",Map.of("input","1","cache_read","1","output","1","thoughts","1"));
        catalog.publish("synthetic-stop-"+UUID.randomUUID(),"synthetic-test-only",List.of(new io.github.samzhu.bot.pricing.PriceCatalog.Entry(price,java.time.Instant.now().minusSeconds(60),java.time.Instant.now().plusSeconds(3600))));
        var created=http.send(request("/graphql",Map.of("query","mutation($workspace: ID!) { createConversation(botId: \"bot-general\", workspaceId: $workspace) { id } }","variables",Map.of("workspace",scope))),HttpResponse.BodyHandlers.ofString());
        String id=json.readTree(created.body()).path("data").path("createConversation").path("id").asText(); assertFalse(id.isBlank());
        String run=UUID.randomUUID().toString();
        var body=Map.of("threadId",id,"runId",run,"state",Map.of(),"messages",List.of(Map.of("id",UUID.randomUUID().toString(),"role","user","content","Synthetic stop question")),"tools",List.of(),"context",List.of(),"forwardedProps",Map.of());
        var stream=http.sendAsync(request("/api/ag-ui",body),HttpResponse.BodyHandlers.ofString());
        try {
            assertTrue(chatModel().awaitSubscribed(8,TimeUnit.SECONDS));
            assertEquals(1,jdbc.update("update workspace_monthly_budget set enabled=true,cap_usd=3 where workspace_id=? and partition='SIMULATION'",scope));
            chatModel().emitChunk("visible response"); assertTrue(loopback.entered.await(5,TimeUnit.SECONDS));
            var cancel=http.send(request("/api/runs/"+run+"/cancel",Map.of()),HttpResponse.BodyHandlers.ofString());
            assertEquals(200,cancel.statusCode(),cancel.body());
            assertTrue(stream.get(8,TimeUnit.SECONDS).body().contains("RUN_FINISHED"));
            assertEquals("CANCELLED",jdbc.queryForObject("select status from chat_run where id=?",String.class,run));
            assertEquals("CLAIMED",jdbc.queryForObject("select state from conversation_title_work where conversation_id=?",String.class,id));
            loopback.release.countDown();
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(8); String state="";
            while (System.nanoTime()<end) {
                state=jdbc.queryForObject("select state from conversation_title_work where conversation_id=?",String.class,id);
                if ("DONE".equals(state) || "FAILED".equals(state)) break;
                Thread.sleep(20);
            }
            assertEquals("DONE",state); assertEquals(1,loopback.requests.get());
            assertEquals(1,jdbc.queryForObject("select count(*) from chat_run where conversation_id=?",Integer.class,id));
            var read=http.send(request("/graphql",Map.of("query","query($id: ID!) { conversation(id: $id) { title } }","variables",Map.of("id",id))),HttpResponse.BodyHandlers.ofString());
            assertEquals("Synthetic title",json.readTree(read.body()).path("data").path("conversation").path("title").asText());
        } finally { loopback.release.countDown(); loopback.hold=false; loopback.success=false; chatModel().complete(); }
    }

    @Test void invalidTitleKeepsDefaultAfterUsageSettles() throws Exception {
        assertTrue(environment.matchesProfiles("test") && environment.getProperty("bot-ai-provider") == null);
        try (var connection=dataSource.getConnection()) { assertEquals(postgres.getJdbcUrl(),connection.getMetaData().getURL()); }
        chatModel().reset(); chatModel().setHoldMode(true); loopback.requests.set(0); loopback.success=true; loopback.invalidTitle=true; loopback.hold=false; loopback.entered=new CountDownLatch(1);
        String scope="title-invalid-"+UUID.randomUUID();
        jdbc.update("insert into workspace(id,name) values (?,?)",scope,"Synthetic invalid");
        jdbc.update("insert into workspace_member(workspace_id,user_id,role,active) values (?,'dev-user','MANAGER',true)",scope);
        jdbc.update("insert into bot(id,name,system_prompt,created_at) values ('bot-general','General','test',current_timestamp) on conflict(id) do nothing");
        var price=new io.github.samzhu.bot.pricing.PriceSnapshot("GOOGLE","interactions","gemini-3.5-flash-lite","GEMINI_API","synthetic",Map.of("input","1","cache_read","1","output","1","thoughts","1"));
        catalog.publish("synthetic-invalid-"+UUID.randomUUID(),"synthetic-test-only",List.of(new io.github.samzhu.bot.pricing.PriceCatalog.Entry(price,java.time.Instant.now().minusSeconds(60),java.time.Instant.now().plusSeconds(3600))));
        var created=http.send(request("/graphql",Map.of("query","mutation($workspace: ID!) { createConversation(botId: \"bot-general\", workspaceId: $workspace) { id } }","variables",Map.of("workspace",scope))),HttpResponse.BodyHandlers.ofString());
        String id=json.readTree(created.body()).path("data").path("createConversation").path("id").asText(); assertFalse(id.isBlank());
        var body=Map.of("threadId",id,"runId",UUID.randomUUID().toString(),"state",Map.of(),"messages",List.of(Map.of("id",UUID.randomUUID().toString(),"role","user","content","Synthetic invalid title")),"tools",List.of(),"context",List.of(),"forwardedProps",Map.of());
        var stream=http.sendAsync(request("/api/ag-ui",body),HttpResponse.BodyHandlers.ofString());
        try {
            assertTrue(chatModel().awaitSubscribed(8,TimeUnit.SECONDS));
            assertEquals(1,jdbc.update("update workspace_monthly_budget set enabled=true,cap_usd=3 where workspace_id=? and partition='SIMULATION'",scope));
            int subscribers=chatModel().currentSubscribers();
            var emission=chatModel().tryEmitChunk("visible response");
            // Keep chat open until title admission has dispatched; otherwise its unknown usage can close the budget first.
            assertTrue(loopback.entered.await(5,TimeUnit.SECONDS), "title provider did not receive the request before chat completion");
            var completion=chatModel().tryComplete();
            var response=stream.get(8,TimeUnit.SECONDS);
            String diagnostic="status="+response.statusCode()+", body="+response.body()+", subscribers="+subscribers
                    +", emission="+emission+", completion="+completion+", loopbackRequests="+loopback.requests.get()
                    +", enteredCount="+loopback.entered.getCount()+", work="+jdbc.queryForList("select state from conversation_title_work where conversation_id=?",id)
                    +", reservation="+jdbc.queryForList("select state from workspace_title_reservation where conversation_id=?",id);
            assertEquals(200,response.statusCode(),diagnostic);
            assertTrue(response.body().contains("RUN_FINISHED"),diagnostic);
            assertTrue(loopback.entered.await(5,TimeUnit.SECONDS),diagnostic+", enteredCountAfterWait="+loopback.entered.getCount());
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(8); String state="";
            while (System.nanoTime()<end) { state=jdbc.queryForObject("select state from conversation_title_work where conversation_id=?",String.class,id); if ("FAILED".equals(state)) break; Thread.sleep(20); }
            assertEquals("FAILED",state); assertEquals(1,loopback.requests.get());
            assertEquals("SETTLED",jdbc.queryForObject("select state from workspace_title_reservation where conversation_id=?",String.class,id));
            var read=http.send(request("/graphql",Map.of("query","query($id: ID!) { conversation(id: $id) { title } }","variables",Map.of("id",id))),HttpResponse.BodyHandlers.ofString());
            assertEquals("New Conversation",json.readTree(read.body()).path("data").path("conversation").path("title").asText());
        } finally { loopback.invalidTitle=false; loopback.success=false; chatModel().complete(); }
    }

}
