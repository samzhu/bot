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
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.session.SessionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.data.jdbc.core.JdbcAggregateTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

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

    @MockitoSpyBean
    private SessionService sessionService;

    @Autowired
    private io.github.samzhu.bot.execution.internal.RunExecutionServiceImpl runExecutionService;

    @Autowired
    private CurrentUserProvider currentUserProvider;

    @Autowired
    private JdbcAggregateTemplate jdbcAggregateTemplate;

    @Autowired
    private javax.sql.DataSource dataSource;

    @TestBean(name = "mockChatModel", methodName = "controlledChatModel", enforceOverride = true)
    ChatModel mockChatModel;

    ControlledChatModel controlledChatModel = ControlledChatModel.getInstance();

    static ControlledChatModel controlledChatModel() {
        return ControlledChatModel.getInstance();
    }

    @TestBean(name = "runProperties", methodName = "testRunProperties", enforceOverride = true)
    io.github.samzhu.bot.execution.internal.RunProperties runProperties;

    TestRunProperties testRunProperties = TestRunProperties.getInstance();

    static TestRunProperties testRunProperties() {
        return TestRunProperties.getInstance();
    }

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private final ObjectMapper objectMapper = new ObjectMapper();

    private String baseUrl;

    @BeforeEach
    void setUp() {
        baseUrl = "http://127.0.0.1:" + port;
        controlledChatModel.reset();
        testRunProperties.reset();
        reset(sessionService);
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

        // Verify Turn 2 prompt context passed to ChatModel includes Turn 1 history
        List<Prompt> recorded = controlledChatModel.getRecordedPrompts();
        assertTrue(recorded.size() >= 2);
        Prompt turn2Prompt = recorded.get(recorded.size() - 1);
        List<org.springframework.ai.chat.messages.Message> turn2Instructions = turn2Prompt.getInstructions();
        // system prompt (1) + turn 1 user (1) + turn 1 assistant (1) + turn 2 user (1) = 4 messages
        assertEquals(4, turn2Instructions.size());
        assertEquals("My secret code is 42", turn2Instructions.get(1).getText());
        assertTrue(turn2Instructions.get(2).getText().contains("My secret code is 42"));
        assertEquals("Repeat my secret code", turn2Instructions.get(3).getText());
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

        // Use hold mode to deterministically guarantee Run 1 is active in DB and handle registry
        controlledChatModel.setHoldMode(true);

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

        // Deterministically await Run 1 subscription via ControlledChatModel hook (Thread.sleep completely removed)
        assertTrue(controlledChatModel.awaitSubscribed(5, TimeUnit.SECONDS), "Run 1 must be actively subscribed before Run 2 fires");

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

        // Complete Run 1 and finish reading stream
        controlledChatModel.complete();
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

        // Put model in hold mode so it does not auto-complete before cancel arrives
        controlledChatModel.setHoldMode(true);

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

        assertTrue(controlledChatModel.awaitSubscribed(5, TimeUnit.SECONDS), "Model must be subscribed");
        controlledChatModel.emitChunk("Partial delta 1");

        CountDownLatch firstDeltaLatch = new CountDownLatch(1);
        AtomicReference<String> runIdRef = new AtomicReference<>();
        List<String> events = new CopyOnWriteArrayList<>();
        List<String> terminalEvents = new CopyOnWriteArrayList<>();

        CompletableFuture<Void> readerFuture = CompletableFuture.runAsync(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(resp.body(), StandardCharsets.UTF_8))) {
                String line;
                String cur = null;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.startsWith("event:")) {
                        cur = line.substring(6).trim();
                        events.add(cur);
                        if ("run.completed".equals(cur) || "run.cancelled".equals(cur) || "run.failed".equals(cur)) {
                            terminalEvents.add(cur);
                        }
                    } else if (line.startsWith("data:")) {
                        JsonNode n = objectMapper.readTree(line.substring(5).trim());
                        if (n.has("runId")) runIdRef.set(n.get("runId").asText());
                        if ("message.delta".equals(cur)) {
                            firstDeltaLatch.countDown();
                        }
                    }
                }
            } catch (Exception e) {
                throw new RuntimeException("Error reading SSE stream in test7: " + e.getMessage(), e);
            }
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

        // Verify exactly one terminal event occurred across stream to EOF
        assertEquals(1, terminalEvents.size(), "Stream to EOF must contain exactly 1 terminal event");
        assertEquals("run.cancelled", terminalEvents.get(0));

        // Verify DB state
        AgentRun run = agentRunRepository.findById(runId).orElseThrow();
        assertEquals(RunStatus.CANCELLED.name(), run.status());

        // Verify assistant message is saved with interrupted = true
        List<Message> msgs = messageRepository.findByRunId(runId);
        Message assistantMsg = msgs.stream().filter(m -> "ASSISTANT".equals(m.role())).findFirst().orElse(null);
        assertNotNull(assistantMsg, "Cancelled assistant message must be saved");
        assertEquals("Partial delta 1", assistantMsg.content(), "Cancelled assistant message must preserve non-empty partial content");
        assertTrue(assistantMsg.interrupted(), "Cancelled assistant message must be marked interrupted = true");

        // Verify Session memory excludes this cancelled turn
        List<org.springframework.ai.chat.messages.Message> active = sessionService.getActiveMessages(convId);
        assertTrue(active.isEmpty(), "Session memory must not contain cancelled turn");
    }

    @Test
    void test8a_CancelIdempotencyOnTerminalState() throws Exception {
        String convId = createTestConversation("Cancel Terminal State Conv");
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

        // Repeated cancel on COMPLETED run is idempotent
        HttpResponse<String> repeatResp = httpClient.send(cancelReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, repeatResp.statusCode());
        JsonNode repeatNode = objectMapper.readTree(repeatResp.body());
        assertEquals(RunStatus.COMPLETED.name(), repeatNode.path("status").asText());
        assertFalse(repeatNode.path("cancelled").asBoolean());

        // DB state remains COMPLETED
        AgentRun after = agentRunRepository.findById(runId).orElseThrow();
        assertEquals(RunStatus.COMPLETED.name(), after.status(), "COMPLETED run must not be overwritten to CANCELLED");

        // 3. Repeated cancel on a CANCELLED run
        String cancelledRunId = UUID.randomUUID().toString();
        AgentRun cancelledRun = new AgentRun(
                cancelledRunId, convId, "req-cancel-dup", "prompt",
                RunStatus.CANCELLED.name(), "Run cancelled by user", Instant.now(), Instant.now()
        );
        jdbcAggregateTemplate.insert(cancelledRun);

        HttpRequest cancelReq2 = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/runs/" + cancelledRunId + "/cancel"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> cancelResp2 = httpClient.send(cancelReq2, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, cancelResp2.statusCode());
        JsonNode node2 = objectMapper.readTree(cancelResp2.body());
        assertEquals(RunStatus.CANCELLED.name(), node2.path("status").asText());
        assertTrue(node2.path("cancelled").asBoolean());

        // DB state remains CANCELLED
        AgentRun after2 = agentRunRepository.findById(cancelledRunId).orElseThrow();
        assertEquals(RunStatus.CANCELLED.name(), after2.status());
    }

    @Test
    void test8b_RealConcurrentCompleteVsCancel() throws Exception {
        String convId = createTestConversation("Real Concurrent Race Conv");
        String clientReqId = "req-race-" + UUID.randomUUID();

        controlledChatModel.setHoldMode(true);

        String body = objectMapper.writeValueAsString(Map.of(
                "clientRequestId", clientReqId,
                "message", "Race prompt"
        ));

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/conversations/" + convId + "/runs"))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<InputStream> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200, resp.statusCode());

        assertTrue(controlledChatModel.awaitSubscribed(5, TimeUnit.SECONDS), "Model must be subscribed");
        controlledChatModel.emitChunk("Partial delta before race");

        CountDownLatch firstDeltaLatch = new CountDownLatch(1);
        AtomicReference<String> runIdRef = new AtomicReference<>();
        List<String> eventNames = new CopyOnWriteArrayList<>();
        List<String> terminalEvents = new CopyOnWriteArrayList<>();

        CompletableFuture<Void> readerFuture = CompletableFuture.runAsync(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(resp.body(), StandardCharsets.UTF_8))) {
                String line;
                String curEvent = null;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.startsWith("event:")) {
                        curEvent = line.substring(6).trim();
                        eventNames.add(curEvent);
                        if ("run.completed".equals(curEvent) || "run.cancelled".equals(curEvent) || "run.failed".equals(curEvent)) {
                            terminalEvents.add(curEvent);
                        }
                    } else if (line.startsWith("data:")) {
                        JsonNode n = objectMapper.readTree(line.substring(5).trim());
                        if (n.has("runId")) runIdRef.set(n.get("runId").asText());
                        if ("message.delta".equals(curEvent)) {
                            firstDeltaLatch.countDown();
                        }
                    }
                }
            } catch (Exception e) {
                throw new RuntimeException("Error reading SSE stream in test8b: " + e.getMessage(), e);
            }
        });

        assertTrue(firstDeltaLatch.await(5, TimeUnit.SECONDS), "First delta must arrive");
        String runId = runIdRef.get();
        assertNotNull(runId);

        // Synchronized trigger for concurrent completion vs cancellation:
        // Both workers signal readyLatch, then are released from the same synchronization gate (raceGate)
        CountDownLatch readyLatch = new CountDownLatch(2);
        CountDownLatch raceGate = new CountDownLatch(1);

        CompletableFuture<Void> completeWorker = CompletableFuture.runAsync(() -> {
            try {
                readyLatch.countDown();
                raceGate.await();
                controlledChatModel.complete();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        CompletableFuture<HttpResponse<String>> cancelWorker = CompletableFuture.supplyAsync(() -> {
            try {
                readyLatch.countDown();
                raceGate.await();
                HttpRequest cancelReq = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/api/runs/" + runId + "/cancel"))
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build();
                return httpClient.send(cancelReq, HttpResponse.BodyHandlers.ofString());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        // Ensure both workers are ready and waiting on the gate before release
        assertTrue(readyLatch.await(5, TimeUnit.SECONDS), "Both workers must be ready at raceGate");
        // Released from the same synchronization gate
        raceGate.countDown();
        completeWorker.get(5, TimeUnit.SECONDS);
        HttpResponse<String> cancelResp = cancelWorker.get(5, TimeUnit.SECONDS);
        assertEquals(200, cancelResp.statusCode());

        // Wait for SSE reader to read all frames until stream EOF
        readerFuture.get(5, TimeUnit.SECONDS);

        // Verify exactly one terminal event occurred across the entire stream to EOF
        assertEquals(1, terminalEvents.size(), "Stream to EOF must contain exactly 1 terminal event, got: " + terminalEvents);
        String finalEvent = terminalEvents.get(0);
        assertTrue("run.completed".equals(finalEvent) || "run.cancelled".equals(finalEvent));

        // DB status must match terminal event exactly
        AgentRun finalRun = agentRunRepository.findById(runId).orElseThrow();
        if ("run.completed".equals(finalEvent)) {
            assertEquals(RunStatus.COMPLETED.name(), finalRun.status());
            // Session must have exactly 1 pair (2 messages)
            List<org.springframework.ai.chat.messages.Message> active = sessionService.getActiveMessages(convId);
            assertEquals(2, active.size(), "Session must contain completed turn");
        } else {
            assertEquals(RunStatus.CANCELLED.name(), finalRun.status());
            // Session must have 0 messages
            List<org.springframework.ai.chat.messages.Message> active = sessionService.getActiveMessages(convId);
            assertTrue(active.isEmpty(), "Session must exclude cancelled turn");
            // Partial message must be preserved with interrupted = true
            List<Message> msgs = messageRepository.findByRunId(runId);
            Message assistantMsg = msgs.stream().filter(m -> "ASSISTANT".equals(m.role())).findFirst().orElseThrow();
            assertFalse(assistantMsg.content().isBlank(), "Partial assistant content must not be blank");
            assertTrue(assistantMsg.interrupted(), "Cancelled assistant message must have interrupted = true");
        }

        // Verify first cancelResp JSON payload matches final authoritative DB state
        JsonNode cancelJson = objectMapper.readTree(cancelResp.body());
        assertEquals(finalRun.status(), cancelJson.path("status").asText(), "First cancel HTTP response status must match final DB status");
        assertEquals(RunStatus.CANCELLED.name().equals(finalRun.status()), cancelJson.path("cancelled").asBoolean(), "First cancel 'cancelled' boolean must match whether CANCELLED won");

        // Repeated cancel returns 200 with the authoritative DB status and boolean
        HttpRequest repeatCancelReq = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/runs/" + runId + "/cancel"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> repeatResp = httpClient.send(repeatCancelReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, repeatResp.statusCode());
        JsonNode repeatNode = objectMapper.readTree(repeatResp.body());
        assertEquals(finalRun.status(), repeatNode.path("status").asText(), "Repeated cancel status must return authoritative DB terminal status");
        assertEquals(RunStatus.CANCELLED.name().equals(finalRun.status()), repeatNode.path("cancelled").asBoolean(), "Repeated cancel 'cancelled' boolean must match authoritative DB terminal status");
    }

    @Test
    void test8c_DeterministicCancelFirstWins() throws Exception {
        String convId = createTestConversation("Deterministic Cancel First Conv");
        String clientReqId = "req-cancel-first-" + UUID.randomUUID();

        controlledChatModel.setHoldMode(true);

        String body = objectMapper.writeValueAsString(Map.of(
                "clientRequestId", clientReqId,
                "message", "Cancel first prompt"
        ));

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/conversations/" + convId + "/runs"))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<InputStream> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofInputStream());
        assertTrue(controlledChatModel.awaitSubscribed(5, TimeUnit.SECONDS));
        controlledChatModel.emitChunk("Partial delta cancel first");

        CountDownLatch firstDeltaLatch = new CountDownLatch(1);
        AtomicReference<String> runIdRef = new AtomicReference<>();
        List<String> terminalEvents = new CopyOnWriteArrayList<>();

        CompletableFuture<Void> readerFuture = CompletableFuture.runAsync(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(resp.body(), StandardCharsets.UTF_8))) {
                String line;
                String curEvent = null;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.startsWith("event:")) {
                        curEvent = line.substring(6).trim();
                        if ("run.completed".equals(curEvent) || "run.cancelled".equals(curEvent) || "run.failed".equals(curEvent)) {
                            terminalEvents.add(curEvent);
                        }
                    } else if (line.startsWith("data:")) {
                        JsonNode n = objectMapper.readTree(line.substring(5).trim());
                        if (n.has("runId")) runIdRef.set(n.get("runId").asText());
                        if ("message.delta".equals(curEvent)) {
                            firstDeltaLatch.countDown();
                        }
                    }
                }
            } catch (Exception e) {
                throw new RuntimeException("Error reading SSE stream in test8c: " + e.getMessage(), e);
            }
        });

        assertTrue(firstDeltaLatch.await(5, TimeUnit.SECONDS));
        String runId = runIdRef.get();

        // 1. Cancel arrives and wins
        HttpRequest cancelReq = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/runs/" + runId + "/cancel"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> cancelResp = httpClient.send(cancelReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, cancelResp.statusCode());
        JsonNode cancelJson = objectMapper.readTree(cancelResp.body());
        assertEquals(RunStatus.CANCELLED.name(), cancelJson.path("status").asText());
        assertTrue(cancelJson.path("cancelled").asBoolean());

        // 2. Late model completion arrives afterwards -> must NOT overwrite DB status
        controlledChatModel.complete();

        // Reader must read to EOF and observe exactly 1 terminal event
        readerFuture.get(5, TimeUnit.SECONDS);
        assertEquals(1, terminalEvents.size(), "Must receive exactly 1 terminal event before EOF");
        assertEquals("run.cancelled", terminalEvents.get(0));

        AgentRun run = agentRunRepository.findById(runId).orElseThrow();
        assertEquals(RunStatus.CANCELLED.name(), run.status(), "Cancelled run must not be overwritten by late model completion");

        // Verify Session has 0 messages
        List<org.springframework.ai.chat.messages.Message> active = sessionService.getActiveMessages(convId);
        assertTrue(active.isEmpty(), "Session must not contain cancelled turn");

        // 3. Repeated cancel returns 200 CANCELLED
        HttpResponse<String> repeatResp = httpClient.send(cancelReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, repeatResp.statusCode());
        JsonNode repeatJson = objectMapper.readTree(repeatResp.body());
        assertEquals(RunStatus.CANCELLED.name(), repeatJson.path("status").asText(), "Repeated cancel status must be CANCELLED");
        assertTrue(repeatJson.path("cancelled").asBoolean(), "Repeated cancel boolean must be true");
    }

    @Test
    void test8d_DeterministicCompleteFirstWins() throws Exception {
        String convId = createTestConversation("Deterministic Complete First Conv");
        String clientReqId = "req-complete-first-" + UUID.randomUUID();

        controlledChatModel.setHoldMode(true);

        String body = objectMapper.writeValueAsString(Map.of(
                "clientRequestId", clientReqId,
                "message", "Complete first prompt"
        ));

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/conversations/" + convId + "/runs"))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<InputStream> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofInputStream());
        assertTrue(controlledChatModel.awaitSubscribed(5, TimeUnit.SECONDS));
        controlledChatModel.emitChunk("Full response part 1");

        CountDownLatch firstDeltaLatch = new CountDownLatch(1);
        AtomicReference<String> runIdRef = new AtomicReference<>();
        List<String> terminalEvents = new CopyOnWriteArrayList<>();

        CompletableFuture<Void> readerFuture = CompletableFuture.runAsync(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(resp.body(), StandardCharsets.UTF_8))) {
                String line;
                String curEvent = null;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.startsWith("event:")) {
                        curEvent = line.substring(6).trim();
                        if ("run.completed".equals(curEvent) || "run.cancelled".equals(curEvent) || "run.failed".equals(curEvent)) {
                            terminalEvents.add(curEvent);
                        }
                    } else if (line.startsWith("data:")) {
                        JsonNode n = objectMapper.readTree(line.substring(5).trim());
                        if (n.has("runId")) runIdRef.set(n.get("runId").asText());
                        if ("message.delta".equals(curEvent)) {
                            firstDeltaLatch.countDown();
                        }
                    }
                }
            } catch (Exception e) {
                throw new RuntimeException("Error reading SSE stream in test8d: " + e.getMessage(), e);
            }
        });

        assertTrue(firstDeltaLatch.await(5, TimeUnit.SECONDS));
        String runId = runIdRef.get();

        // 1. Model completes first
        controlledChatModel.complete();
        readerFuture.get(5, TimeUnit.SECONDS);

        // Reader must read to EOF and observe exactly 1 terminal event
        assertEquals(1, terminalEvents.size(), "Must receive exactly 1 terminal event before EOF");
        assertEquals("run.completed", terminalEvents.get(0));

        AgentRun run = agentRunRepository.findById(runId).orElseThrow();
        assertEquals(RunStatus.COMPLETED.name(), run.status());

        // 2. Late cancel arrives afterwards -> must NOT overwrite DB status
        HttpRequest cancelReq = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/runs/" + runId + "/cancel"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> cancelResp = httpClient.send(cancelReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, cancelResp.statusCode());
        JsonNode cancelNode = objectMapper.readTree(cancelResp.body());
        assertEquals(RunStatus.COMPLETED.name(), cancelNode.path("status").asText(), "Completed run must not be overwritten to CANCELLED");
        assertFalse(cancelNode.path("cancelled").asBoolean());

        // Verify Session has 1 turn (2 messages)
        List<org.springframework.ai.chat.messages.Message> active = sessionService.getActiveMessages(convId);
        assertEquals(2, active.size(), "Session must retain completed turn");

        // 3. Repeated cancel returns 200 COMPLETED
        HttpResponse<String> repeatResp = httpClient.send(cancelReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, repeatResp.statusCode());
        JsonNode repeatNode = objectMapper.readTree(repeatResp.body());
        assertEquals(RunStatus.COMPLETED.name(), repeatNode.path("status").asText(), "Repeated cancel status must be COMPLETED");
        assertFalse(repeatNode.path("cancelled").asBoolean(), "Repeated cancel boolean must be false");
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

    @Test
    void test10_MultiTurnPromptExcludesInterruptedAndEnforcesSlidingWindow() throws Exception {
        String convId = createTestConversation("MultiTurn Window Conv");
        controlledChatModel.reset();

        // Turn 1: Complete
        executePromptToCompletion(convId, "req-t1", "Hello Luna 1");
        // Turn 2: Complete
        executePromptToCompletion(convId, "req-t2", "Hello Luna 2");

        // Turn 3: Cancel halfway
        String clientReqId3 = "req-t3-" + UUID.randomUUID();
        String body3 = objectMapper.writeValueAsString(Map.of(
                "clientRequestId", clientReqId3,
                "message", "Hello Luna 3 Cancel"
        ));
        HttpRequest req3 = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/conversations/" + convId + "/runs"))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body3))
                .build();
        HttpResponse<InputStream> resp3 = httpClient.send(req3, HttpResponse.BodyHandlers.ofInputStream());
        CountDownLatch deltaLatch3 = new CountDownLatch(1);
        AtomicReference<String> runId3 = new AtomicReference<>();
        CompletableFuture<Void> t3ReaderFuture = CompletableFuture.runAsync(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(resp3.body(), StandardCharsets.UTF_8))) {
                String line;
                String cur = null;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.startsWith("event:")) cur = line.substring(6).trim();
                    else if (line.startsWith("data:")) {
                        JsonNode n = objectMapper.readTree(line.substring(5).trim());
                        if (n.has("runId")) runId3.set(n.get("runId").asText());
                        if ("message.delta".equals(cur)) deltaLatch3.countDown();
                    }
                }
            } catch (Exception e) {
                throw new RuntimeException("Error reading SSE stream in test10 Turn 3: " + e.getMessage(), e);
            }
        });
        assertTrue(deltaLatch3.await(5, TimeUnit.SECONDS));
        HttpRequest cancelReq = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/runs/" + runId3.get() + "/cancel"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> cancelResp3 = httpClient.send(cancelReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, cancelResp3.statusCode());
        t3ReaderFuture.get(5, TimeUnit.SECONDS);

        // Verify Turn 3 in DB has interrupted = true
        List<Message> t3Msgs = messageRepository.findByRunId(runId3.get());
        Message t3Assistant = t3Msgs.stream().filter(m -> "ASSISTANT".equals(m.role())).findFirst().orElseThrow();
        assertTrue(t3Assistant.interrupted(), "Cancelled Turn 3 must have interrupted = true");

        // Turn 4: Complete
        executePromptToCompletion(convId, "req-t4", "Hello Luna 4");

        // Check Turn 4 Prompt:
        // Must contain Turn 1 User, Turn 1 Assistant, Turn 2 User, Turn 2 Assistant, Turn 4 User.
        // Must NOT contain Turn 3 anywhere!
        List<Prompt> recorded = controlledChatModel.getRecordedPrompts();
        Prompt turn4Prompt = recorded.get(recorded.size() - 1);
        List<org.springframework.ai.chat.messages.Message> t4Instructions = turn4Prompt.getInstructions();
        // system prompt (1) + turn 1 (2) + turn 2 (2) + turn 4 user (1) = 6
        assertEquals(6, t4Instructions.size(), "Turn 4 prompt must contain system prompt + 2 completed turns + current user");
        String fullPromptText = t4Instructions.stream().map(org.springframework.ai.chat.messages.Message::getText).reduce("", (a, b) -> a + " " + b);
        assertTrue(fullPromptText.contains("Hello Luna 1"));
        assertTrue(fullPromptText.contains("Hello Luna 2"));
        assertFalse(fullPromptText.contains("Hello Luna 3 Cancel"), "Turn 3 cancelled turn must NOT contaminate prompt");
        assertTrue(fullPromptText.contains("Hello Luna 4"));

        // Now test sliding window: complete turns 5 through 11 (so we reach 10 completed turns total: 1, 2, 4, 5, 6, 7, 8, 9, 10, 11)
        for (int i = 5; i <= 11; i++) {
            executePromptToCompletion(convId, "req-t" + i, "Turn " + i);
        }

        // Now Turn 12: with default windowTurns = 10, maxTurnMessages = 20.
        // Total completed turns before Turn 12 = 10 turns (turns 1, 2, 4, 5, 6, 7, 8, 9, 10, 11).
        // Since there are 10 completed turns in Session memory, activeMessages.size() == 20.
        // Complete Turn 12, then send Turn 13:
        executePromptToCompletion(convId, "req-t12", "Turn 12");
        executePromptToCompletion(convId, "req-t13", "Turn 13");

        // In Turn 13 prompt:
        // Completed turns before Turn 13: 11 completed turns (22 messages).
        // Sliding window keeps max 20 messages (the most recent 10 turns).
        // The oldest turn (Turn 1: "Hello Luna 1") must have slid out of the window!
        List<Prompt> recordedAfter = controlledChatModel.getRecordedPrompts();
        Prompt turn13Prompt = recordedAfter.get(recordedAfter.size() - 1);
        List<org.springframework.ai.chat.messages.Message> t13Instructions = turn13Prompt.getInstructions();
        // system prompt (1) + 20 sliding window messages + turn 13 user (1) = 22 messages
        assertEquals(22, t13Instructions.size(), "Turn 13 prompt must be capped to 1 system + 20 windowed messages + 1 user");
        String t13PromptText = t13Instructions.stream().map(org.springframework.ai.chat.messages.Message::getText).reduce("", (a, b) -> a + " " + b);
        assertFalse(t13PromptText.contains("Hello Luna 1"), "Turn 1 must be discarded by sliding window");
        assertTrue(t13PromptText.contains("Turn 12"), "Recent turn must be retained in sliding window");
        assertTrue(t13PromptText.contains("Turn 13"), "Current user prompt must be present");
    }

    private void executePromptToCompletion(String convId, String clientReqPrefix, String message) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "clientRequestId", clientReqPrefix + "-" + UUID.randomUUID(),
                "message", message
        ));
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/conversations/" + convId + "/runs"))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<InputStream> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofInputStream());
        consumeSseStream(resp.body());
    }

    private void consumeSseStream(InputStream is) throws Exception {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                // Read until true EOF
            }
        }
    }

    record SseReadResult(
        List<String> eventNames,
        List<String> terminalEvents,
        Map<String, List<JsonNode>> eventsData,
        String runId
    ) {}

    private SseReadResult readFullSseStream(InputStream is) throws Exception {
        List<String> eventNames = new CopyOnWriteArrayList<>();
        List<String> terminalEvents = new CopyOnWriteArrayList<>();
        Map<String, List<JsonNode>> eventsData = new ConcurrentHashMap<>();
        AtomicReference<String> rId = new AtomicReference<>();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            String curEvent = null;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.startsWith("event:")) {
                    curEvent = line.substring(6).trim();
                    eventNames.add(curEvent);
                    if ("run.completed".equals(curEvent) || "run.cancelled".equals(curEvent) || "run.failed".equals(curEvent)) {
                        terminalEvents.add(curEvent);
                    }
                } else if (line.startsWith("data:")) {
                    String jsonText = line.substring(5).trim();
                    if (!jsonText.isBlank()) {
                        JsonNode n = objectMapper.readTree(jsonText);
                        if (curEvent != null) {
                            eventsData.computeIfAbsent(curEvent, k -> new CopyOnWriteArrayList<>()).add(n);
                        }
                        if (n.has("runId") && rId.get() == null) {
                            rId.set(n.get("runId").asText());
                        }
                    }
                }
            }
        }
        return new SseReadResult(eventNames, terminalEvents, eventsData, rId.get());
    }

    @Test
    void test14_ConcurrentDedupSameRequestIdSamePayload() throws Exception {
        String convId = createTestConversation("Concurrent Dedup Conv");
        String clientReqId = "req-14-" + UUID.randomUUID();
        String jsonPayload = objectMapper.writeValueAsString(Map.of(
                "clientRequestId", clientReqId,
                "message", "Concurrent dedup message"
        ));

        CountDownLatch readyLatch = new CountDownLatch(2);
        CountDownLatch raceGate = new CountDownLatch(1);

        CompletableFuture<HttpResponse<InputStream>> worker1 = CompletableFuture.supplyAsync(() -> {
            try {
                readyLatch.countDown();
                raceGate.await();
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/api/conversations/" + convId + "/runs"))
                        .header("Content-Type", "application/json")
                        .header("Accept", "text/event-stream, application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                        .build();
                return httpClient.send(req, HttpResponse.BodyHandlers.ofInputStream());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        CompletableFuture<HttpResponse<InputStream>> worker2 = CompletableFuture.supplyAsync(() -> {
            try {
                readyLatch.countDown();
                raceGate.await();
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/api/conversations/" + convId + "/runs"))
                        .header("Content-Type", "application/json")
                        .header("Accept", "text/event-stream, application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                        .build();
                return httpClient.send(req, HttpResponse.BodyHandlers.ofInputStream());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        assertTrue(readyLatch.await(5, TimeUnit.SECONDS));
        raceGate.countDown();

        HttpResponse<InputStream> resp1 = worker1.get(5, TimeUnit.SECONDS);
        HttpResponse<InputStream> resp2 = worker2.get(5, TimeUnit.SECONDS);

        assertEquals(200, resp1.statusCode());
        assertEquals(200, resp2.statusCode());

        // One response must be text/event-stream, the other application/json duplicate
        HttpResponse<InputStream> sseResp;
        HttpResponse<InputStream> jsonResp;
        String ct1 = resp1.headers().firstValue("Content-Type").orElse("");
        String ct2 = resp2.headers().firstValue("Content-Type").orElse("");

        if (ct1.contains("text/event-stream")) {
            assertTrue(ct2.contains("application/json"), "Second response must be application/json duplicate");
            sseResp = resp1;
            jsonResp = resp2;
        } else {
            assertTrue(ct1.contains("application/json"), "First response must be application/json duplicate");
            assertTrue(ct2.contains("text/event-stream"), "Second response must be text/event-stream");
            sseResp = resp2;
            jsonResp = resp1;
        }

        // Read SSE stream to EOF
        SseReadResult sseResult = readFullSseStream(sseResp.body());
        assertEquals(1, sseResult.terminalEvents().size(), "SSE must yield exactly 1 terminal event");
        assertEquals("run.completed", sseResult.terminalEvents().get(0));
        String runId = sseResult.runId();
        assertNotNull(runId, "Run ID must not be null");

        // Parse JSON duplicate response
        String jsonText = new String(jsonResp.body().readAllBytes(), StandardCharsets.UTF_8);
        JsonNode jsonNode = objectMapper.readTree(jsonText);
        assertEquals(runId, jsonNode.path("runId").asText(), "Duplicate JSON runId must match SSE runId");
        assertEquals(runId, jsonNode.path("id").asText(), "Duplicate JSON id must match SSE runId");
        assertEquals(clientReqId, jsonNode.path("clientRequestId").asText());
        assertEquals("Concurrent dedup message", jsonNode.path("promptText").asText());

        // Database assertions: exactly 1 run, 1 USER message, 1 ASSISTANT message
        List<AgentRun> runs = agentRunRepository.findByConversationIdWithPaging(convId, 10, 0);
        assertEquals(1, runs.size(), "DB must contain exactly 1 run for this conversation");
        assertEquals(clientReqId, runs.get(0).clientRequestId());

        List<Message> msgs = messageRepository.findByRunId(runId);
        assertEquals(2, msgs.size(), "DB must contain exactly 1 USER and 1 ASSISTANT message for this run");

        // ControlledChatModel assertions: exactly 1 model subscription
        assertEquals(1, controlledChatModel.getRecordedPrompts().size(), "Model must be subscribed exactly once");
    }

    @Test
    void test15_ConcurrentConflictSameRequestIdDifferentPayload() throws Exception {
        String convId = createTestConversation("Conflict Request Conv");
        String clientReqId = "req-15-" + UUID.randomUUID();

        // Real concurrent race with same clientRequestId but different payloads released from a shared gate
        String payloadAlpha = objectMapper.writeValueAsString(Map.of(
                "clientRequestId", clientReqId,
                "message", "Payload Alpha"
        ));
        String payloadBeta = objectMapper.writeValueAsString(Map.of(
                "clientRequestId", clientReqId,
                "message", "Payload Beta"
        ));

        CountDownLatch readyLatch = new CountDownLatch(2);
        CountDownLatch raceGate = new CountDownLatch(1);

        CompletableFuture<HttpResponse<InputStream>> worker1 = CompletableFuture.supplyAsync(() -> {
            try {
                readyLatch.countDown();
                raceGate.await();
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/api/conversations/" + convId + "/runs"))
                        .header("Content-Type", "application/json")
                        .header("Accept", "text/event-stream, application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(payloadAlpha))
                        .build();
                return httpClient.send(req, HttpResponse.BodyHandlers.ofInputStream());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        CompletableFuture<HttpResponse<InputStream>> worker2 = CompletableFuture.supplyAsync(() -> {
            try {
                readyLatch.countDown();
                raceGate.await();
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/api/conversations/" + convId + "/runs"))
                        .header("Content-Type", "application/json")
                        .header("Accept", "text/event-stream, application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(payloadBeta))
                        .build();
                return httpClient.send(req, HttpResponse.BodyHandlers.ofInputStream());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        assertTrue(readyLatch.await(5, TimeUnit.SECONDS));
        raceGate.countDown();

        HttpResponse<InputStream> resp1 = worker1.get(5, TimeUnit.SECONDS);
        HttpResponse<InputStream> resp2 = worker2.get(5, TimeUnit.SECONDS);

        // One response must be 200 text/event-stream (winner), the other must be 409 application/json (conflicting loser)
        HttpResponse<InputStream> winnerSse;
        HttpResponse<InputStream> loserConflict;

        if (resp1.statusCode() == 200) {
            assertEquals(409, resp2.statusCode(), "Losing request with conflicting payload must return 409 Conflict");
            winnerSse = resp1;
            loserConflict = resp2;
        } else {
            assertEquals(409, resp1.statusCode(), "Losing request with conflicting payload must return 409 Conflict");
            assertEquals(200, resp2.statusCode(), "Winning request must return 200 SSE");
            winnerSse = resp2;
            loserConflict = resp1;
        }

        assertTrue(winnerSse.headers().firstValue("Content-Type").orElse("").contains("text/event-stream"));
        assertTrue(loserConflict.headers().firstValue("Content-Type").orElse("").contains("application/json"));

        // Parse 409 JSON body
        String conflictJsonText = new String(loserConflict.body().readAllBytes(), StandardCharsets.UTF_8);
        JsonNode conflictJson = objectMapper.readTree(conflictJsonText);
        assertEquals("Conflicting message for clientRequestId", conflictJson.path("error").asText());
        assertFalse(conflictJson.path("runId").asText().isBlank());

        // Read winning SSE stream to EOF
        SseReadResult sseResult = readFullSseStream(winnerSse.body());
        assertEquals(1, sseResult.terminalEvents().size());
        assertEquals("run.completed", sseResult.terminalEvents().get(0));
        assertEquals(conflictJson.path("runId").asText(), sseResult.runId(), "409 runId must match winning runId");

        // DB assertions: exactly 1 run in DB, exactly 1 USER message, model subscribed exactly once
        List<AgentRun> runs = agentRunRepository.findByConversationIdWithPaging(convId, 10, 0);
        assertEquals(1, runs.size(), "DB must contain exactly 1 run for this conversation");
        assertEquals(clientReqId, runs.get(0).clientRequestId());

        List<Message> msgs = messageRepository.findByRunId(sseResult.runId());
        assertEquals(2, msgs.size(), "DB must contain exactly 1 USER and 1 ASSISTANT message");

        assertEquals(1, controlledChatModel.getRecordedPrompts().size(), "Model must be subscribed exactly once");
    }

    @Test
    void test16_SingleActiveRunDifferentRequestIdSameConversation() throws Exception {
        String convId = createTestConversation("Single Active Run Conv");
        controlledChatModel.setHoldMode(true);

        String runId1Req = "req-16a-" + UUID.randomUUID();
        String body1 = objectMapper.writeValueAsString(Map.of(
                "clientRequestId", runId1Req,
                "message", "Active run 1 message"
        ));

        HttpRequest req1 = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/conversations/" + convId + "/runs"))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body1))
                .build();

        HttpResponse<InputStream> resp1 = httpClient.send(req1, HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200, resp1.statusCode());
        assertTrue(controlledChatModel.awaitSubscribed(5, TimeUnit.SECONDS), "Model must be subscribed for run 1");

        // While run 1 is active, send run 2 with a DIFFERENT clientRequestId on the same conversation
        String runId2Req = "req-16b-" + UUID.randomUUID();
        String body2 = objectMapper.writeValueAsString(Map.of(
                "clientRequestId", runId2Req,
                "message", "Active run 2 conflicting message"
        ));

        HttpRequest req2 = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/conversations/" + convId + "/runs"))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body2))
                .build();

        HttpResponse<String> resp2 = httpClient.send(req2, HttpResponse.BodyHandlers.ofString());
        assertEquals(409, resp2.statusCode(), "Concurrent run on same conversation must return 409 Conflict");
        assertTrue(resp2.headers().firstValue("Content-Type").orElse("").contains("application/json"));

        JsonNode json2 = objectMapper.readTree(resp2.body());
        assertEquals("Another active run is in progress for this conversation", json2.path("error").asText());
        assertFalse(json2.path("runId").asText().isBlank(), "Must return active runId");

        // Finish run 1
        controlledChatModel.emitChunk("Run 1 finish reply");
        controlledChatModel.complete();

        SseReadResult result1 = readFullSseStream(resp1.body());
        assertEquals(1, result1.terminalEvents().size());
        assertEquals("run.completed", result1.terminalEvents().get(0));

        // Now run 3 on same conversation must succeed (run 1 has reached terminal)
        controlledChatModel.setHoldMode(false);
        String runId3Req = "req-16c-" + UUID.randomUUID();
        String body3 = objectMapper.writeValueAsString(Map.of(
                "clientRequestId", runId3Req,
                "message", "Run 3 next turn message"
        ));

        HttpRequest req3 = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/conversations/" + convId + "/runs"))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body3))
                .build();

        HttpResponse<InputStream> resp3 = httpClient.send(req3, HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200, resp3.statusCode());
        SseReadResult result3 = readFullSseStream(resp3.body());
        assertEquals(1, result3.terminalEvents().size());
        assertEquals("run.completed", result3.terminalEvents().get(0));
    }

    @Test
    void test17_HeartbeatDetectsClientDisconnect_AbortsModel_AllowsNextRun() throws Exception {
        testRunProperties.setHeartbeatInterval(Duration.ofMillis(100));
        String convId = createTestConversation("Heartbeat Disconnect Conv");
        controlledChatModel.setHoldMode(true);

        String clientReqId = "req-17a-" + UUID.randomUUID();
        String body = objectMapper.writeValueAsString(Map.of(
                "clientRequestId", clientReqId,
                "message", "Disconnect test message"
        ));

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/conversations/" + convId + "/runs"))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<InputStream> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200, resp.statusCode());

        // Read until run.started to ensure run is active and registered
        InputStream is = resp.body();
        BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
        String line;
        String runId = null;
        while ((line = reader.readLine()) != null) {
            line = line.trim();
            if (line.startsWith("data:")) {
                JsonNode node = objectMapper.readTree(line.substring(5).trim());
                if (node.has("runId")) {
                    runId = node.get("runId").asText();
                    break;
                }
            }
        }
        assertNotNull(runId);

        // Abruptly close the client socket
        is.close();

        // Model stream subscription must be cancelled when the 100ms heartbeat write fails
        assertTrue(controlledChatModel.awaitCancelled(5, TimeUnit.SECONDS), "Model stream subscription must be cancelled on client disconnect");

        // Semantic await: observe transition to CANCELLED in DB up to 5 seconds without fixed sleep
        long deadline = System.currentTimeMillis() + 5000;
        AgentRun run = null;
        while (System.currentTimeMillis() < deadline) {
            var opt = agentRunRepository.findById(runId);
            if (opt.isPresent() && RunStatus.CANCELLED.name().equals(opt.get().status())) {
                run = opt.get();
                break;
            }
            Thread.yield();
        }
        assertNotNull(run, "Run must transition to CANCELLED in DB upon client disconnect");
        assertEquals(RunStatus.CANCELLED.name(), run.status());

        // Next run on the same conversation must succeed immediately (not 409)
        controlledChatModel.setHoldMode(false);
        String clientReqId2 = "req-17b-" + UUID.randomUUID();
        String body2 = objectMapper.writeValueAsString(Map.of(
                "clientRequestId", clientReqId2,
                "message", "Next run after disconnect"
        ));

        HttpRequest req2 = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/conversations/" + convId + "/runs"))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body2))
                .build();

        HttpResponse<InputStream> resp2 = httpClient.send(req2, HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200, resp2.statusCode(), "Next run after disconnect must succeed");
        SseReadResult result2 = readFullSseStream(resp2.body());
        assertEquals(1, result2.terminalEvents().size());
        assertEquals("run.completed", result2.terminalEvents().get(0));
    }

    @Test
    void test18_ApplicationDeadline_EmitsRunFailedTimeout() throws Exception {
        testRunProperties.setTimeout(Duration.ofMillis(400));
        String convId = createTestConversation("App Deadline Conv");
        controlledChatModel.setHoldMode(true);

        String clientReqId = "req-18a-" + UUID.randomUUID();
        String body = objectMapper.writeValueAsString(Map.of(
                "clientRequestId", clientReqId,
                "message", "Deadline timeout message"
        ));

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/conversations/" + convId + "/runs"))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<InputStream> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200, resp.statusCode());

        // Stream will be terminated by application deadline at 400ms emitting run.failed
        SseReadResult result = readFullSseStream(resp.body());
        assertEquals(1, result.terminalEvents().size(), "Must receive exactly 1 terminal event");
        assertEquals("run.failed", result.terminalEvents().get(0));

        JsonNode failData = result.eventsData().get("run.failed").get(0);
        assertEquals("FAILED", failData.path("status").asText());
        assertTrue(failData.path("reason").asText().toLowerCase().contains("timeout"), "Error reason must specify timeout");

        // DB status must be FAILED
        String runId = result.runId();
        assertNotNull(runId);
        AgentRun run = agentRunRepository.findById(runId).orElseThrow();
        assertEquals(RunStatus.FAILED.name(), run.status());
        assertTrue(run.errorMessage().toLowerCase().contains("timeout"));

        // Reset timeout and verify next run on same conversation succeeds
        testRunProperties.reset();
        controlledChatModel.setHoldMode(false);

        String clientReqId2 = "req-18b-" + UUID.randomUUID();
        String body2 = objectMapper.writeValueAsString(Map.of(
                "clientRequestId", clientReqId2,
                "message", "Subsequent turn after timeout"
        ));

        HttpRequest req2 = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/conversations/" + convId + "/runs"))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body2))
                .build();

        HttpResponse<InputStream> resp2 = httpClient.send(req2, HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200, resp2.statusCode());
        SseReadResult result2 = readFullSseStream(resp2.body());
        assertEquals(1, result2.terminalEvents().size());
        assertEquals("run.completed", result2.terminalEvents().get(0));
    }

    @Test
    void test19_PartialDeltaFollowedByModelError() throws Exception {
        String convId = createTestConversation("Model Error Conv");
        controlledChatModel.setHoldMode(true);

        String clientReqId = "req-19-" + UUID.randomUUID();
        String body = objectMapper.writeValueAsString(Map.of(
                "clientRequestId", clientReqId,
                "message", "Model error test message"
        ));

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/conversations/" + convId + "/runs"))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<InputStream> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200, resp.statusCode());

        assertTrue(controlledChatModel.awaitSubscribed(5, TimeUnit.SECONDS), "Model must be subscribed");
        // Emit chunk and error immediately in sequence without Thread.sleep
        controlledChatModel.emitChunk("Partial delta chunk");
        controlledChatModel.error(new RuntimeException("Simulated upstream model error"));

        SseReadResult result = readFullSseStream(resp.body());
        assertEquals(1, result.terminalEvents().size(), "Must receive exactly 1 terminal event");
        assertEquals("run.failed", result.terminalEvents().get(0));

        JsonNode failData = result.eventsData().get("run.failed").get(0);
        assertEquals("FAILED", failData.path("status").asText());
        assertTrue(failData.path("reason").asText().contains("Simulated upstream model error"));

        // DB checks: assistant message persisted with interrupted = true
        String runId = result.runId();
        assertNotNull(runId);
        List<Message> msgs = messageRepository.findByRunId(runId);
        Message assistantMsg = msgs.stream().filter(m -> "ASSISTANT".equals(m.role())).findFirst().orElseThrow();
        assertTrue(assistantMsg.interrupted(), "Assistant message must be marked interrupted");
        assertEquals("Partial delta chunk", assistantMsg.content());

        // Session service must NOT contain this failed turn in active messages
        List<org.springframework.ai.chat.messages.Message> activeMsgs = sessionService.getActiveMessages(convId);
        assertTrue(activeMsgs == null || activeMsgs.isEmpty(), "Failed run must not contaminate SessionService memory");
    }

    @Test
    void test20_SessionAppendFalse_TriggersRollback_FallbackToFailed() throws Exception {
        String convId = createTestConversation("Session Append False Conv");

        // Simulate SessionService.appendEvents returning false (optimistic lock mismatch)
        doReturn(false).when(sessionService).appendEvents(eq(convId), anyList(), anyLong());

        String clientReqId = "req-20-" + UUID.randomUUID();
        String promptMsg = "Session append false message";
        String body = objectMapper.writeValueAsString(Map.of(
                "clientRequestId", clientReqId,
                "message", promptMsg
        ));

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/conversations/" + convId + "/runs"))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<InputStream> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200, resp.statusCode());

        SseReadResult result = readFullSseStream(resp.body());
        assertEquals(1, result.terminalEvents().size(), "Must receive exactly 1 terminal event");
        assertEquals("run.failed", result.terminalEvents().get(0));

        JsonNode failData = result.eventsData().get("run.failed").get(0);
        assertEquals("FAILED", failData.path("status").asText());
        assertTrue(failData.path("reason").asText().contains("SessionService.appendEvents failed"));
        String eventMsgId = failData.path("messageId").asText();
        assertNotNull(eventMsgId);
        assertFalse(eventMsgId.isBlank());

        // DB state: status FAILED, assistant message is unique, marked interrupted, ID matches event, content matches
        String runId = result.runId();
        assertNotNull(runId);
        AgentRun run = agentRunRepository.findById(runId).orElseThrow();
        assertEquals(RunStatus.FAILED.name(), run.status());

        List<Message> msgs = messageRepository.findByRunId(runId);
        assertEquals(2, msgs.size(), "DB must contain exactly 1 USER and 1 ASSISTANT message");
        Message assistantMsg = msgs.stream().filter(m -> "ASSISTANT".equals(m.role())).findFirst().orElseThrow();
        assertEquals(eventMsgId, assistantMsg.id(), "Assistant message ID must match run.failed event messageId");
        assertTrue(assistantMsg.interrupted(), "Assistant message must be marked interrupted on rollback");
        assertTrue(assistantMsg.content().contains("mock streaming response for: " + promptMsg));

        // Fresh Session query: verify no message pair exists in SessionService
        List<org.springframework.ai.chat.messages.Message> activeMsgs = sessionService.getActiveMessages(convId);
        assertTrue(activeMsgs == null || activeMsgs.isEmpty(), "Session store must NOT contain any message pair for failed run");

        // Subsequent run succeeds and is uncontaminated
        reset(sessionService);
        String nextReqId = "req-20-next-" + UUID.randomUUID();
        String nextBody = objectMapper.writeValueAsString(Map.of(
                "clientRequestId", nextReqId,
                "message", "Next clean message"
        ));
        HttpRequest nextReq = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/conversations/" + convId + "/runs"))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(nextBody))
                .build();
        HttpResponse<InputStream> nextResp = httpClient.send(nextReq, HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200, nextResp.statusCode());
        SseReadResult nextResult = readFullSseStream(nextResp.body());
        assertEquals(1, nextResult.terminalEvents().size());
        assertEquals("run.completed", nextResult.terminalEvents().get(0));

        // In controlledChatModel, the prompt for next run must NOT contain promptMsg
        Prompt lastPrompt = controlledChatModel.getLastPrompt();
        assertNotNull(lastPrompt);
        String lastPromptText = lastPrompt.getInstructions().stream()
                .map(org.springframework.ai.chat.messages.Message::getText)
                .reduce("", (a, b) -> a + " " + b);
        assertFalse(lastPromptText.contains(promptMsg), "Subsequent prompt must not contain rolled-back turn");
        assertTrue(lastPromptText.contains("Next clean message"));
    }

    @Test
    void test21_SessionAppendThrows_TriggersRollback_FallbackToFailed() throws Exception {
        String convId = createTestConversation("Session Append Throws Conv");

        // Spy call real method to perform actual SQL insert, then throw to force rollback
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new RuntimeException("Simulated post-append failure for transaction rollback");
        }).when(sessionService).appendEvents(eq(convId), anyList(), anyLong());

        String clientReqId = "req-21-" + UUID.randomUUID();
        String promptMsg = "Session append throws message";
        String body = objectMapper.writeValueAsString(Map.of(
                "clientRequestId", clientReqId,
                "message", promptMsg
        ));

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/conversations/" + convId + "/runs"))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<InputStream> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200, resp.statusCode());

        SseReadResult result = readFullSseStream(resp.body());
        assertEquals(1, result.terminalEvents().size(), "Must receive exactly 1 terminal event");
        assertEquals("run.failed", result.terminalEvents().get(0));

        JsonNode failData = result.eventsData().get("run.failed").get(0);
        assertEquals("FAILED", failData.path("status").asText());
        assertTrue(failData.path("reason").asText().contains("Simulated post-append failure"));
        String eventMsgId = failData.path("messageId").asText();
        assertNotNull(eventMsgId);
        assertFalse(eventMsgId.isBlank());

        // DB state: status FAILED, assistant message is unique, interrupted, ID matches event, content matches
        String runId = result.runId();
        assertNotNull(runId);
        AgentRun run = agentRunRepository.findById(runId).orElseThrow();
        assertEquals(RunStatus.FAILED.name(), run.status());

        List<Message> msgs = messageRepository.findByRunId(runId);
        assertEquals(2, msgs.size(), "DB must contain exactly 1 USER and 1 ASSISTANT message");
        Message assistantMsg = msgs.stream().filter(m -> "ASSISTANT".equals(m.role())).findFirst().orElseThrow();
        assertEquals(eventMsgId, assistantMsg.id(), "Assistant message ID must match run.failed event messageId");
        assertTrue(assistantMsg.interrupted(), "Assistant message must be marked interrupted on rollback");
        assertTrue(assistantMsg.content().contains("mock streaming response for: " + promptMsg));

        // Fresh Session query: prove that even though real appendEvents ran, the transaction rollback reverted Session SQL!
        List<org.springframework.ai.chat.messages.Message> activeMsgs = sessionService.getActiveMessages(convId);
        assertTrue(activeMsgs == null || activeMsgs.isEmpty(), "Session store must be empty because transaction rollback reverted Session SQL");

        // Subsequent run succeeds and is uncontaminated
        reset(sessionService);
        String nextReqId = "req-21-next-" + UUID.randomUUID();
        String nextBody = objectMapper.writeValueAsString(Map.of(
                "clientRequestId", nextReqId,
                "message", "Next clean message after throws"
        ));
        HttpRequest nextReq = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/conversations/" + convId + "/runs"))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(nextBody))
                .build();
        HttpResponse<InputStream> nextResp = httpClient.send(nextReq, HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200, nextResp.statusCode());
        SseReadResult nextResult = readFullSseStream(nextResp.body());
        assertEquals(1, nextResult.terminalEvents().size());
        assertEquals("run.completed", nextResult.terminalEvents().get(0));

        // In controlledChatModel, the prompt for next run must NOT contain promptMsg
        Prompt lastPrompt = controlledChatModel.getLastPrompt();
        assertNotNull(lastPrompt);
        String lastPromptText = lastPrompt.getInstructions().stream()
                .map(org.springframework.ai.chat.messages.Message::getText)
                .reduce("", (a, b) -> a + " " + b);
        assertFalse(lastPromptText.contains(promptMsg), "Subsequent prompt must not contain rolled-back turn");
        assertTrue(lastPromptText.contains("Next clean message after throws"));
    }

    @Test
    void test22_StalledStreamDoesNotHoldDbConnection() throws Exception {
        String convId = createTestConversation("Stalled Stream Connection Conv");
        controlledChatModel.setHoldMode(true);

        String clientReqId = "req-22-" + UUID.randomUUID();
        String body = objectMapper.writeValueAsString(Map.of(
                "clientRequestId", clientReqId,
                "message", "Connection pool test message"
        ));

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/conversations/" + convId + "/runs"))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<InputStream> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200, resp.statusCode());
        assertTrue(controlledChatModel.awaitSubscribed(5, TimeUnit.SECONDS), "Model must be subscribed");

        // Verify active connections in Hikari pool is 0 while model stream is stalled in HOLD mode
        com.zaxxer.hikari.HikariDataSource hikariDs = (com.zaxxer.hikari.HikariDataSource) dataSource;
        com.zaxxer.hikari.HikariPoolMXBean mxBean = hikariDs.getHikariPoolMXBean();
        assertNotNull(mxBean, "HikariPoolMXBean must be available");
        assertEquals(0, mxBean.getActiveConnections(), "Active connection count in pool must be 0 while stream is held outside DB transaction");

        // While the model stream is stalled in HOLD mode, execute 50 repository queries concurrently
        ExecutorService pool = Executors.newFixedThreadPool(10);
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            futures.add(CompletableFuture.runAsync(() -> {
                var found = conversationRepository.findById(convId);
                assertTrue(found.isPresent(), "Conversation must be readable during held stream");
                botRepository.findAll();
            }, pool));
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(5, TimeUnit.SECONDS);
        pool.shutdown();

        // Verify connection count returns to 0 immediately after concurrent queries complete
        assertEquals(0, mxBean.getActiveConnections(), "Active connection count must return to 0");

        // Now resume and finish stream
        controlledChatModel.emitChunk("finished reply");
        controlledChatModel.complete();

        SseReadResult result = readFullSseStream(resp.body());
        assertEquals(1, result.terminalEvents().size());
        assertEquals("run.completed", result.terminalEvents().get(0));
    }

    @Test
    void test23_ValidationBoundaries() throws Exception {
        String convId = createTestConversation("Validation Boundaries Conv");

        // 1. Blank clientRequestId -> 400 Bad Request
        String bodyBlankReqId = objectMapper.writeValueAsString(Map.of(
                "clientRequestId", "   ",
                "message", "valid prompt"
        ));
        HttpRequest req1 = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/conversations/" + convId + "/runs"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(bodyBlankReqId))
                .build();
        assertEquals(400, httpClient.send(req1, HttpResponse.BodyHandlers.ofString()).statusCode());

        // 2. clientRequestId > 128 characters -> 400 Bad Request
        String bodyOverlongReqId = objectMapper.writeValueAsString(Map.of(
                "clientRequestId", "a".repeat(129),
                "message", "valid prompt"
        ));
        HttpRequest req2 = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/conversations/" + convId + "/runs"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(bodyOverlongReqId))
                .build();
        assertEquals(400, httpClient.send(req2, HttpResponse.BodyHandlers.ofString()).statusCode());

        // 3. Blank message -> 400 Bad Request
        String bodyBlankMsg = objectMapper.writeValueAsString(Map.of(
                "clientRequestId", "req-23-valid",
                "message", "   "
        ));
        HttpRequest req3 = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/conversations/" + convId + "/runs"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(bodyBlankMsg))
                .build();
        assertEquals(400, httpClient.send(req3, HttpResponse.BodyHandlers.ofString()).statusCode());

        // 4. Overlong message (> 10000 chars) -> 400 Bad Request
        String bodyOverlongMsg = objectMapper.writeValueAsString(Map.of(
                "clientRequestId", "req-23-valid-2",
                "message", "m".repeat(10001)
        ));
        HttpRequest req4 = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/conversations/" + convId + "/runs"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(bodyOverlongMsg))
                .build();
        assertEquals(400, httpClient.send(req4, HttpResponse.BodyHandlers.ofString()).statusCode());

        // 5. Foreign user owner guard: real foreign owner data verification (not just unknown conv)
        String foreignConvId = createTestConversation("Foreign Owner Guard Conv");
        // Start run with foreign user ("user-bob") directly via service
        var bobStartOutcome = runExecutionService.startRun(foreignConvId, "req-23-bob", "Bob unauthorized message", "user-bob");
        assertEquals(io.github.samzhu.bot.execution.ExecutionOperations.StartRunOutcome.Type.CONFLICT, bobStartOutcome.type(), "Foreign user startRun must be rejected");
        assertEquals("Conversation not found or access denied", bobStartOutcome.conflictMessage());

        // Start valid run with rightful owner (default user)
        var aliceStartOutcome = runExecutionService.startRun(foreignConvId, "req-23-alice", "Alice authorized message", currentUserProvider.getCurrentUserId());
        assertEquals(io.github.samzhu.bot.execution.ExecutionOperations.StartRunOutcome.Type.STARTED, aliceStartOutcome.type());
        String aliceRunId = aliceStartOutcome.existingRun().id();

        // Foreign user bob tries to cancel alice's run
        var bobCancelOutcome = runExecutionService.cancelRun(aliceRunId, "user-bob");
        assertEquals(RunStatus.FAILED, bobCancelOutcome.finalStatus());
        assertFalse(bobCancelOutcome.cancelled(), "Foreign user cancelRun must fail");

        // Rightful owner cancels run
        var aliceCancelOutcome = runExecutionService.cancelRun(aliceRunId, currentUserProvider.getCurrentUserId());
        assertEquals(RunStatus.CANCELLED, aliceCancelOutcome.finalStatus());
        assertTrue(aliceCancelOutcome.cancelled());

        // 6. Unknown conversation ID -> 409 Conflict ("Conversation not found or access denied")
        String bodyUnknownConv = objectMapper.writeValueAsString(Map.of(
                "clientRequestId", "req-23-valid-3",
                "message", "valid prompt"
        ));
        HttpRequest req6 = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/conversations/conv-non-existent/runs"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(bodyUnknownConv))
                .build();
        HttpResponse<String> resp6 = httpClient.send(req6, HttpResponse.BodyHandlers.ofString());
        assertEquals(409, resp6.statusCode());
        JsonNode json6 = objectMapper.readTree(resp6.body());
        assertEquals("Conversation not found or access denied", json6.path("error").asText());
    }
}
