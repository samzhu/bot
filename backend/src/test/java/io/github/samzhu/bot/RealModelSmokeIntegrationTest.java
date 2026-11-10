package io.github.samzhu.bot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.samzhu.bot.conversation.ConversationOperations;
import io.github.samzhu.bot.conversation.MessageView;
import io.github.samzhu.bot.execution.ExecutionOperations;
import io.github.samzhu.bot.execution.RunStatus;
import io.github.samzhu.bot.execution.RunView;
import io.github.samzhu.bot.execution.internal.model.MockChatModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.session.SessionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("real-ai")
@EnabledIfSystemProperty(named = "smoke.real-ai", matches = "true")
@Import(TestcontainersConfiguration.class)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.config.additional-location=file:./config/"
        }
)
@ActiveProfiles({"local", "real-ai"})
class RealModelSmokeIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(RealModelSmokeIntegrationTest.class);

    @LocalServerPort
    private int port;

    @Autowired
    private ChatModel chatModel;

    @Autowired
    private ConversationOperations conversationOperations;

    @Autowired
    private ExecutionOperations executionOperations;

    @Autowired
    private SessionService sessionService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private String createConversationViaGraphQL(String title) throws Exception {
        String query = objectMapper.writeValueAsString(Map.of(
                "query", "mutation CreateConv($title: String) { createConversation(botId: \"bot-general\", title: $title) { id title } }",
                "variables", Map.of("title", title)
        ));

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/graphql"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(query))
                .build();

        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).as("GraphQL createConversation must succeed with 200").isEqualTo(200);

        JsonNode root = objectMapper.readTree(resp.body());
        String convId = root.path("data").path("createConversation").path("id").asText();
        assertThat(convId).as("Created conversation ID must be present").isNotBlank();
        return convId;
    }

    @Test
    @DisplayName("Smoke Test: Genuine GoogleGenAiChatModel round-trip SSE stream, strict seq, and DB/Session persistence")
    void testRealModelRoundTripStreaming() throws Exception {
        log.info("--- Starting Genuine GoogleGenAiChatModel Live Smoke Test ---");

        // 1. Assert ChatModel is genuinely GoogleGenAiChatModel, NOT MockChatModel
        assertThat(chatModel)
                .as("ChatModel must be genuine GoogleGenAiChatModel under real-ai profile")
                .isInstanceOf(GoogleGenAiChatModel.class)
                .isNotInstanceOf(MockChatModel.class);

        GoogleGenAiChatModel realGenAiModel = (GoogleGenAiChatModel) chatModel;
        String configuredModel = realGenAiModel.getOptions().getModel();
        log.info("Verified active ChatModel bean: {}, configured model: {}", realGenAiModel.getClass().getName(), configuredModel);
        assertThat(configuredModel).as("Configured model name must be non-blank").isNotBlank();

        // 2. Create fresh conversation via GraphQL (loopback developer identity)
        String convId = createConversationViaGraphQL("Live Model Smoke Test Conv");
        String clientRequestId = "live-smoke-" + UUID.randomUUID();
        String shortPrompt = "Reply with exactly one word: PONG";

        // 3. Dispatch POST to SSE endpoint
        String url = "http://127.0.0.1:" + port + "/api/conversations/" + convId + "/runs";
        String payload = objectMapper.writeValueAsString(Map.of(
                "clientRequestId", clientRequestId,
                "message", shortPrompt
        ));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();

        log.info("Dispatching live inference request to external model provider...");
        HttpResponse<java.io.InputStream> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (Exception ex) {
            log.error("Transport error connecting to SSE endpoint: {}", ex.getMessage());
            throw new AssertionError("Failed to connect to SSE endpoint: " + ex.getMessage(), ex);
        }

        if (response.statusCode() != 200) {
            String errorBody = "";
            try (BufferedReader errReader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                errorBody = errReader.readLine();
            } catch (Exception ignored) {}
            log.error("Provider or server error: HTTP {} body: {}", response.statusCode(), errorBody);
            throw new AssertionError(String.format("SSE request rejected with HTTP %d: %s", response.statusCode(), errorBody));
        }

        // 4. Parse incoming SSE lines with robust prefix handling and strict non-vacuous assertions
        StringBuilder rawFullReply = new StringBuilder();
        List<String> visibleDeltas = new ArrayList<>();
        int terminalEventCount = 0;
        String completedMessageId = null;
        String observedRunId = null;
        int expectedSeq = 1;
        boolean isFirstEvent = true;

        String currentEvent = null;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("event:")) {
                    currentEvent = line.substring(6).trim();
                } else if (line.startsWith("data:")) {
                    String dataJson = line.substring(5).trim();
                    if (dataJson.isEmpty()) continue;

                    JsonNode node = objectMapper.readTree(dataJson);

                    // Assert runId non-blank and consistent across every business data frame
                    assertThat(node.hasNonNull("runId"))
                            .as("Every business frame must contain non-null runId")
                            .isTrue();
                    String eventRunId = node.path("runId").asText();
                    assertThat(eventRunId)
                            .as("runId must be a non-blank string")
                            .isNotBlank();

                    if (observedRunId == null) {
                        observedRunId = eventRunId;
                    } else {
                        assertThat(eventRunId)
                                .as("All SSE frames must share identical runId")
                                .isEqualTo(observedRunId);
                    }

                    // Assert seq exists, is integer, and increments strictly monotonically from 1
                    assertThat(node.hasNonNull("seq"))
                            .as("Every business frame must contain non-null seq")
                            .isTrue();
                    assertThat(node.path("seq").isIntegralNumber())
                            .as("seq must be an integer")
                            .isTrue();
                    int eventSeq = node.path("seq").asInt();
                    assertThat(eventSeq)
                            .as("SSE event seq must strictly monotonically increase from 1")
                            .isEqualTo(expectedSeq++);

                    // Assert first event is strictly run.started with seq 1
                    if (isFirstEvent) {
                        isFirstEvent = false;
                        assertThat(currentEvent)
                                .as("First SSE event must strictly be run.started")
                                .isEqualTo("run.started");
                        assertThat(eventSeq)
                                .as("First event seq must be 1")
                                .isEqualTo(1);
                    }

                    if ("message.delta".equals(currentEvent)) {
                        String delta = node.path("delta").asText(null);
                        // Legal Google GenAI metadata/empty chunk check: only non-empty counts as visible text delta
                        if (delta != null && !delta.isEmpty()) {
                            visibleDeltas.add(delta);
                            rawFullReply.append(delta);
                        }
                    } else if ("run.completed".equals(currentEvent)) {
                        terminalEventCount++;
                        completedMessageId = node.path("messageId").asText(null);
                    } else if ("run.failed".equals(currentEvent) || "run.cancelled".equals(currentEvent)) {
                        terminalEventCount++;
                    }
                }
            }
        }

        String aggregatedAnswer = rawFullReply.toString();
        log.info("Live streaming finished. Visible deltas: {}, Aggregated answer: '{}'", visibleDeltas.size(), aggregatedAnswer);

        // 5. Assert streaming protocol invariants
        assertThat(terminalEventCount).as("Must receive exactly 1 terminal event").isEqualTo(1);
        assertThat(completedMessageId).as("run.completed must contain non-blank messageId").isNotBlank();
        assertThat(visibleDeltas).as("Must receive at least one visible text delta").isNotEmpty();
        assertThat(aggregatedAnswer.trim()).as("Aggregated text must not be blank").isNotBlank();

        // 6. Verify database records
        List<MessageView> dbMessages = conversationOperations.listMessages(convId, 10, 0);
        assertThat(dbMessages).hasSize(2);

        MessageView userMsg = dbMessages.stream().filter(m -> "user".equalsIgnoreCase(m.role())).findFirst().orElseThrow();
        MessageView assistantMsg = dbMessages.stream().filter(m -> "assistant".equalsIgnoreCase(m.role())).findFirst().orElseThrow();

        assertThat(userMsg.content()).isEqualTo(shortPrompt);
        assertThat(assistantMsg.content()).as("DB assistant content must match raw aggregated reply").isEqualTo(aggregatedAnswer);
        assertThat(assistantMsg.id()).as("DB assistant message ID must equal completedMessageId").isEqualTo(completedMessageId);
        assertThat(assistantMsg.interrupted()).isFalse();

        // 7. Verify Run record in database
        List<RunView> runs = executionOperations.listRunsByConversation(convId, 10, 0);
        assertThat(runs).hasSize(1);
        RunView run = runs.get(0);
        assertThat(run.id()).as("DB run ID must equal SSE observedRunId").isEqualTo(observedRunId);
        assertThat(run.status()).as("Run status must equal COMPLETED").isEqualTo(RunStatus.COMPLETED.name());
        assertThat(run.errorMessage()).isNull();

        // 8. Verify Session Memory records (1 complete turn = exactly 2 active messages)
        List<org.springframework.ai.chat.messages.Message> sessionMsgs = sessionService.getActiveMessages(convId);
        assertThat(sessionMsgs)
                .as("Session Memory must contain exactly 2 active messages for completed turn")
                .hasSize(2);
        assertThat(sessionMsgs.get(0).getText()).isEqualTo(shortPrompt);
        assertThat(sessionMsgs.get(1).getText()).as("Session assistant text must match raw aggregated reply").isEqualTo(aggregatedAnswer);

        log.info("=== Genuine GoogleGenAiChatModel Live Smoke Test PASSED ===");
    }
}
