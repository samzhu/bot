package io.github.samzhu.bot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.samzhu.bot.bootstrap.DatabaseInitializer;
import io.github.samzhu.bot.bot.internal.BotRepository;
import io.github.samzhu.bot.conversation.ConversationOperations;
import io.github.samzhu.bot.conversation.ConversationView;
import io.github.samzhu.bot.conversation.internal.ConversationRepository;
import io.github.samzhu.bot.conversation.internal.Message;
import io.github.samzhu.bot.conversation.internal.MessageRepository;
import io.github.samzhu.bot.execution.RunStatus;
import io.github.samzhu.bot.execution.internal.AgentRun;
import io.github.samzhu.bot.execution.internal.AgentRunRepository;
import io.github.samzhu.bot.identity.CurrentUserProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.session.SessionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.data.jdbc.core.JdbcAggregateTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class LocalChatBackendIntegrationTests {

    @LocalServerPort
    private int port;

    @Autowired
    private ConversationOperations conversationOperations;

    @Autowired
    private ConversationRepository conversationRepository;

    @Autowired
    private MessageRepository messageRepository;

    @Autowired
    private AgentRunRepository agentRunRepository;

    @Autowired
    private BotRepository botRepository;

    @Autowired
    private DatabaseInitializer databaseInitializer;

    @Autowired
    private SessionService sessionService;

    @Autowired
    private CurrentUserProvider currentUserProvider;

    @Autowired
    private JdbcAggregateTemplate jdbcAggregateTemplate;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private final ObjectMapper objectMapper = new ObjectMapper();

    private String baseUrl;

    @BeforeEach
    void setUp() {
        baseUrl = "http://127.0.0.1:" + port;
    }

    private String createTestConversation(String title) throws Exception {
        String query = "{\"query\":\"mutation { createConversation(botId: \\\"bot-general\\\", title: \\\"" + title + "\\\") { id title createdAt updatedAt } }\"}";
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/graphql"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(query))
                .build();

        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode());

        JsonNode root = objectMapper.readTree(resp.body());
        JsonNode data = root.path("data").path("createConversation");
        assertFalse(data.isMissingNode());
        return data.path("id").asText();
    }

    @Test
    void test1_SeedBotAndGraphQLQueriesAndPagination() throws Exception {
        // Query bots
        String query = "{\"query\":\"query { bots(limit: 10, offset: 0) { id name } }\"}";
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/graphql"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(query))
                .build();

        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode());
        JsonNode root = objectMapper.readTree(resp.body());
        JsonNode bots = root.path("data").path("bots");
        assertTrue(bots.isArray() && bots.size() > 0);
        assertEquals("bot-general", bots.get(0).path("id").asText());

        // Create conversation and query it
        String convId = createTestConversation("Test 1 Conv");
        String convQuery = "{\"query\":\"query { conversation(id: \\\"" + convId + "\\\") { id title messages(limit: 5, offset: 0) { id } runs(limit: 5, offset: 0) { id } } }\"}";
        HttpRequest convReq = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/graphql"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(convQuery))
                .build();
        HttpResponse<String> convResp = httpClient.send(convReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, convResp.statusCode());
        JsonNode convNode = objectMapper.readTree(convResp.body()).path("data").path("conversation");
        assertEquals(convId, convNode.path("id").asText());
    }

    @Test
    void test2_RunExecutionStreamingSuccessWithRealHttp() throws Exception {
        String convId = createTestConversation("Streaming Conv");
        String clientReqId = "req-stream-" + UUID.randomUUID();

        String body = objectMapper.writeValueAsString(Map.of(
                "clientRequestId", clientReqId,
                "message", "Hello Real SSE"
        ));

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/conversations/" + convId + "/runs"))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<InputStream> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200, resp.statusCode());
        assertTrue(resp.headers().firstValue("Content-Type").orElse("").contains("text/event-stream"));

        List<String> eventNames = new ArrayList<>();
        List<Integer> seqNumbers = new ArrayList<>();
        AtomicReference<String> completedMessageId = new AtomicReference<>();
        AtomicReference<String> runIdRef = new AtomicReference<>();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(resp.body(), StandardCharsets.UTF_8))) {
            String line;
            String currentEvent = null;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.startsWith("event:")) {
                    currentEvent = line.substring(6).trim();
                    eventNames.add(currentEvent);
                } else if (line.startsWith("data:")) {
                    String dataJson = line.substring(5).trim();
                    JsonNode dataNode = objectMapper.readTree(dataJson);
                    if (dataNode.has("seq")) {
                        seqNumbers.add(dataNode.get("seq").asInt());
                    }
                    if (dataNode.has("runId")) {
                        runIdRef.set(dataNode.get("runId").asText());
                    }
                    if ("run.completed".equals(currentEvent)) {
                        completedMessageId.set(dataNode.path("messageId").asText());
                        break;
                    }
                }
            }
        }

        // Verify SSE Sequence: started is seq 1, deltas are 2, 3, ... completed is final seq
        assertTrue(eventNames.contains("run.started"), "Must contain run.started event");
        assertTrue(eventNames.contains("message.delta"), "Must contain message.delta event");
        assertTrue(eventNames.contains("run.completed"), "Must contain run.completed event");
        assertEquals(1, seqNumbers.get(0), "First sequence must be 1 (run.started)");
        for (int i = 1; i < seqNumbers.size(); i++) {
            assertEquals(seqNumbers.get(i - 1) + 1, (int) seqNumbers.get(i), "Sequence numbers must be strictly consecutive");
        }

        assertNotNull(runIdRef.get());
        String runId = runIdRef.get();

        // Verify DB AgentRun state
        AgentRun run = agentRunRepository.findById(runId).orElseThrow();
        assertEquals(RunStatus.COMPLETED.name(), run.status());

        // Verify DB Messages
        List<Message> messages = messageRepository.findByRunId(runId);
        assertEquals(2, messages.size());
        Message userMsg = messages.stream().filter(m -> "USER".equals(m.role())).findFirst().orElseThrow();
        Message assistantMsg = messages.stream().filter(m -> "ASSISTANT".equals(m.role())).findFirst().orElseThrow();
        assertEquals("Hello Real SSE", userMsg.content());
        assertFalse(assistantMsg.interrupted());
        assertEquals(assistantMsg.id(), completedMessageId.get(), "Completed messageId must match assistant message in DB");

        // Verify Session Memory
        List<org.springframework.ai.chat.messages.Message> active = sessionService.getActiveMessages(convId);
        assertEquals(2, active.size(), "Session must store exactly 1 turn (2 messages)");
    }

    @Test
    void test3_TwoTurnContextInSession() throws Exception {
        String convId = createTestConversation("Two Turn Conv");

        // Turn 1
        String req1 = objectMapper.writeValueAsString(Map.of(
                "clientRequestId", "turn-1-" + UUID.randomUUID(),
                "message", "My secret code is 42"
        ));
        HttpRequest httpReq1 = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/conversations/" + convId + "/runs"))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(req1))
                .build();
        HttpResponse<InputStream> resp1 = httpClient.send(httpReq1, HttpResponse.BodyHandlers.ofInputStream());
        consumeSseStream(resp1.body());

        // Turn 2
        String req2 = objectMapper.writeValueAsString(Map.of(
                "clientRequestId", "turn-2-" + UUID.randomUUID(),
                "message", "Repeat my secret code"
        ));
        HttpRequest httpReq2 = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/conversations/" + convId + "/runs"))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(req2))
                .build();
        HttpResponse<InputStream> resp2 = httpClient.send(httpReq2, HttpResponse.BodyHandlers.ofInputStream());
        consumeSseStream(resp2.body());

        // Verify Session Memory now contains both turns (4 active messages)
        List<org.springframework.ai.chat.messages.Message> active = sessionService.getActiveMessages(convId);
        assertEquals(4, active.size(), "Session must retain both turns (4 active messages)");
    }

    @Test
    void test4_RunDeduplicationSamePayloadReturns200JsonAndDifferentPayloadReturns409() throws Exception {
        String convId = createTestConversation("Dedup Conv");
        String clientReqId = "req-dedup-" + UUID.randomUUID();

        String body1 = objectMapper.writeValueAsString(Map.of(
                "clientRequestId", clientReqId,
                "message", "Original Prompt"
        ));

        // First call: returns SSE text/event-stream
        HttpRequest req1 = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/conversations/" + convId + "/runs"))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body1))
                .build();
        HttpResponse<InputStream> resp1 = httpClient.send(req1, HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200, resp1.statusCode());
        assertTrue(resp1.headers().firstValue("Content-Type").orElse("").contains("text/event-stream"));
        consumeSseStream(resp1.body());

        // Second call with same clientRequestId & same message: returns 200 application/json
        HttpRequest req2 = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/conversations/" + convId + "/runs"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body1))
                .build();
        HttpResponse<String> resp2 = httpClient.send(req2, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp2.statusCode());
        assertTrue(resp2.headers().firstValue("Content-Type").orElse("").contains("application/json"));
        JsonNode dedupNode = objectMapper.readTree(resp2.body());
        assertEquals(clientReqId, dedupNode.path("clientRequestId").asText());

        // Third call with same clientRequestId & DIFFERENT message: returns 409 Conflict
        String bodyDiff = objectMapper.writeValueAsString(Map.of(
                "clientRequestId", clientReqId,
                "message", "Conflicting Prompt"
        ));
        HttpRequest req3 = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/conversations/" + convId + "/runs"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(bodyDiff))
                .build();
        HttpResponse<String> resp3 = httpClient.send(req3, HttpResponse.BodyHandlers.ofString());
        assertEquals(409, resp3.statusCode());
    }

    @Test
    void test5_SingleActiveRunConflictReturns409() throws Exception {
        String convId = createTestConversation("Single Active Conv");
        String clientReqId1 = "req-active-1-" + UUID.randomUUID();
        String clientReqId2 = "req-active-2-" + UUID.randomUUID();

        // Start Run 1
        String body1 = objectMapper.writeValueAsString(Map.of(
                "clientRequestId", clientReqId1,
                "message", "Long slow prompt"
        ));
        HttpRequest req1 = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/conversations/" + convId + "/runs"))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body1))
                .build();

        CompletableFuture<HttpResponse<InputStream>> futureResp1 = httpClient.sendAsync(req1, HttpResponse.BodyHandlers.ofInputStream());

        // Wait briefly for Run 1 to start and become active in DB
        Thread.sleep(100);

        // Attempt concurrent Run 2 with different clientRequestId on same conversation
        String body2 = objectMapper.writeValueAsString(Map.of(
                "clientRequestId", clientReqId2,
                "message", "Concurrent prompt"
        ));
        HttpRequest req2 = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/conversations/" + convId + "/runs"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body2))
                .build();

        HttpResponse<String> resp2 = httpClient.send(req2, HttpResponse.BodyHandlers.ofString());
        assertEquals(409, resp2.statusCode(), "Concurrent run on same conversation must be rejected with 409 Conflict");

        // Finish reading run 1
        HttpResponse<InputStream> resp1 = futureResp1.get(10, TimeUnit.SECONDS);
        consumeSseStream(resp1.body());
    }

    @Test
    void test6_DeterministicErrorInjection() throws Exception {
        String convId = createTestConversation("Error Injection Conv");
        String clientReqId = "req-err-" + UUID.randomUUID();

        String body = objectMapper.writeValueAsString(Map.of(
                "clientRequestId", clientReqId,
                "message", "Trigger [ERROR] model failure"
        ));

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/conversations/" + convId + "/runs"))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<InputStream> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200, resp.statusCode());

        List<String> events = new ArrayList<>();
        AtomicReference<String> failedReason = new AtomicReference<>();
        AtomicReference<String> runIdRef = new AtomicReference<>();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(resp.body(), StandardCharsets.UTF_8))) {
            String line;
            String curEvent = null;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.startsWith("event:")) {
                    curEvent = line.substring(6).trim();
                    events.add(curEvent);
                } else if (line.startsWith("data:")) {
                    JsonNode node = objectMapper.readTree(line.substring(5).trim());
                    if (node.has("runId")) runIdRef.set(node.get("runId").asText());
                    if ("run.failed".equals(curEvent)) {
                        failedReason.set(node.path("reason").asText());
                        break;
                    }
                }
            }
        }

        assertTrue(events.contains("run.failed"));
        assertNotNull(failedReason.get());
        assertTrue(failedReason.get().contains("Simulated upstream streaming error"));

        // Verify DB status is FAILED
        String runId = runIdRef.get();
        AgentRun run = agentRunRepository.findById(runId).orElseThrow();
        assertEquals(RunStatus.FAILED.name(), run.status());

        // Verify Session Memory excludes this failed turn
        List<org.springframework.ai.chat.messages.Message> active = sessionService.getActiveMessages(convId);
        assertTrue(active.isEmpty(), "Session memory must not contain failed turn");
    }

    @Test
    void test7_CancelRunAndPreservePartial() throws Exception {
        String convId = createTestConversation("Cancel Conv");
        String clientReqId = "req-cancel-" + UUID.randomUUID();

        String body = objectMapper.writeValueAsString(Map.of(
                "clientRequestId", clientReqId,
                "message", "Cancel prompt"
        ));

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/conversations/" + convId + "/runs"))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<InputStream> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200, resp.statusCode());

        CountDownLatch firstDeltaLatch = new CountDownLatch(1);
        AtomicReference<String> runIdRef = new AtomicReference<>();
        List<String> events = new CopyOnWriteArrayList<>();

        CompletableFuture<Void> readerFuture = CompletableFuture.runAsync(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(resp.body(), StandardCharsets.UTF_8))) {
                String line;
                String cur = null;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.startsWith("event:")) {
                        cur = line.substring(6).trim();
                        events.add(cur);
                    } else if (line.startsWith("data:")) {
                        JsonNode n = objectMapper.readTree(line.substring(5).trim());
                        if (n.has("runId")) runIdRef.set(n.get("runId").asText());
                        if ("message.delta".equals(cur)) {
                            firstDeltaLatch.countDown();
                        } else if ("run.cancelled".equals(cur)) {
                            break;
                        }
                    }
                }
            } catch (Exception ignored) {}
        });

        // Await first delta
        assertTrue(firstDeltaLatch.await(5, TimeUnit.SECONDS), "First delta should arrive within 5s");
        String runId = runIdRef.get();
        assertNotNull(runId);

        // Cancel run via HTTP POST
        HttpRequest cancelReq = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/runs/" + runId + "/cancel"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> cancelResp = httpClient.send(cancelReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, cancelResp.statusCode());
        JsonNode cancelJson = objectMapper.readTree(cancelResp.body());
        assertEquals(RunStatus.CANCELLED.name(), cancelJson.path("status").asText());
        assertTrue(cancelJson.path("cancelled").asBoolean());

        readerFuture.get(5, TimeUnit.SECONDS);

        // Verify DB state
        AgentRun run = agentRunRepository.findById(runId).orElseThrow();
        assertEquals(RunStatus.CANCELLED.name(), run.status());

        // Verify assistant message is saved with interrupted = true
        List<Message> msgs = messageRepository.findByRunId(runId);
        Message assistantMsg = msgs.stream().filter(m -> "ASSISTANT".equals(m.role())).findFirst().orElse(null);
        if (assistantMsg != null) {
            assertTrue(assistantMsg.interrupted(), "Cancelled assistant message must be marked interrupted = true");
        }

        // Verify Session memory excludes this cancelled turn
        List<org.springframework.ai.chat.messages.Message> active = sessionService.getActiveMessages(convId);
        assertTrue(active.isEmpty(), "Session memory must not contain cancelled turn");
    }

    @Test
    void test8_CancelCompletedRaceAndIdempotency() throws Exception {
        String convId = createTestConversation("Cancel Race Conv");
        String clientReqId = "req-comp-" + UUID.randomUUID();

        // 1. Pre-insert a COMPLETED run
        String runId = UUID.randomUUID().toString();
        AgentRun completedRun = new AgentRun(
                runId, convId, clientReqId, "prompt",
                RunStatus.COMPLETED.name(), null, Instant.now(), Instant.now()
        );
        jdbcAggregateTemplate.insert(completedRun);

        // 2. Call cancel on completed run -> returns DB final status COMPLETED, cancelled = false
        HttpRequest cancelReq = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/runs/" + runId + "/cancel"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> cancelResp = httpClient.send(cancelReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, cancelResp.statusCode());
        JsonNode node = objectMapper.readTree(cancelResp.body());
        assertEquals(RunStatus.COMPLETED.name(), node.path("status").asText());
        assertFalse(node.path("cancelled").asBoolean());

        // DB state remains COMPLETED
        AgentRun after = agentRunRepository.findById(runId).orElseThrow();
        assertEquals(RunStatus.COMPLETED.name(), after.status(), "COMPLETED run must not be overwritten to CANCELLED");
    }

    @Test
    void test9_ServerRestartOrphanRecovery() throws Exception {
        String convId = createTestConversation("Orphan Conv");
        String orphanRunId = UUID.randomUUID().toString();

        AgentRun orphan = new AgentRun(
                orphanRunId, convId, "req-orphan", "prompt",
                RunStatus.RUNNING.name(), null, Instant.now(), Instant.now()
        );
        jdbcAggregateTemplate.insert(orphan);

        // Trigger DatabaseInitializer simulation of server restart
        databaseInitializer.run(null);

        AgentRun recovered = agentRunRepository.findById(orphanRunId).orElseThrow();
        assertEquals(RunStatus.FAILED.name(), recovered.status());
        assertEquals("SYSTEM_RESTART_ORPHAN", recovered.errorMessage());

        // Re-run databaseInitializer to test SQL re-entrancy
        assertDoesNotThrow(() -> databaseInitializer.run(null));
    }

    private void consumeSseStream(InputStream is) throws Exception {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("event: run.completed") || line.startsWith("event: run.failed") || line.startsWith("event: run.cancelled")) {
                    break;
                }
            }
        }
    }
}
