package io.github.samzhu.bot.bot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.samzhu.bot.BackendIntegrationTest;
import io.github.samzhu.bot.BackendTestChatModel;
import io.github.samzhu.bot.usage.pricing.UsageCaseData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@BackendIntegrationTest
public class GkeBotOffIntegrationTests {
    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private BackendTestChatModel model;

    private final BotCaseRows bots = new BotCaseRows();
    private final String workspaceId = "gke-off-" + UUID.randomUUID();
    private UsageCaseData data;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @BeforeEach
    void setUp() {
        model.reset();
        data = new UsageCaseData(jdbcTemplate, transactions);
        data.createWorkspace(workspaceId, "dev-user");
    }

    @AfterEach
    void cleanUp() throws InterruptedException {
        try {
            data.cleanUp();
            bots.deleteFrom(jdbcTemplate);
        } finally {
            model.reset();
            httpClient.close();
        }
    }

    private String insertRemoteBot() {
        String botId = bots.ownedId("remote");
        jdbcTemplate.update("""
                INSERT INTO bot (id, name, target, description, instructions, owner_id, created_at, updated_at)
                VALUES (?, 'GKE Off Bot', 'GKE_REMOTE', 'Desc', 'Instr', 'dev-user', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, botId);
        return botId;
    }

    private JsonNode executeGraphQL(String payload) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/graphql"))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode());
        return objectMapper.readTree(resp.body());
    }

    @Test
    @DisplayName("1. When OFF: canChat=false, createConversation rejected with GKE_AGENT_NOT_CONNECTED (0 DB delta)")
    void testOffMode_CreateConversationRejected() throws Exception {
        String botId = insertRemoteBot();

        String countConversations = "SELECT count(*) FROM conversation WHERE workspace_id = ?";
        int convCountBefore = jdbcTemplate.queryForObject(countConversations, Integer.class, workspaceId);

        String convPayload = objectMapper.writeValueAsString(Map.of(
                "query", "mutation CreateConv($botId: ID!, $title: String, $workspace: ID!) { createConversation(botId: $botId, title: $title, workspaceId: $workspace) { id } }",
                "variables", Map.of("botId", botId, "title", "Should Fail", "workspace", workspaceId)
        ));
        JsonNode res = executeGraphQL(convPayload);

        assertTrue(res.has("errors"), "Must return GraphQL error: " + res);
        String errorCode = res.path("errors").get(0).path("extensions").path("code").asText();
        assertEquals("GKE_AGENT_NOT_CONNECTED", errorCode);

        int convCountAfter = jdbcTemplate.queryForObject(countConversations, Integer.class, workspaceId);
        assertEquals(convCountBefore, convCountAfter, "Conversation row count must not increase on rejection");
    }

    @Test
    @DisplayName("2. When OFF: startRun on GKE bot returns HTTP 503 GKE_AGENT_NOT_CONNECTED with zero side-effects")
    void testOffMode_StartRunRejected_503_ZeroSideEffects() throws Exception {
        String botId = insertRemoteBot();

        String convId = "conv-off-" + UUID.randomUUID();
        jdbcTemplate.update("""
            INSERT INTO conversation (id, bot_id, title, user_id, pinned, created_at, updated_at, workspace_id)
            VALUES (?, ?, 'Pre-existing GKE Conv', 'dev-user', false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, ?)
        """, convId, botId, workspaceId);

        String countRuns = "SELECT count(*) FROM chat_run WHERE conversation_id = ?";
        String countMessages = "SELECT count(*) FROM message WHERE conversation_id = ?";
        int runCountBefore = jdbcTemplate.queryForObject(countRuns, Integer.class, convId);
        int msgCountBefore = jdbcTemplate.queryForObject(countMessages, Integer.class, convId);

        HttpRequest runReq = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/api/conversations/" + convId + "/runs"))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(Map.of(
                        "clientRequestId", "req-off-01",
                        "message", "Attempt chat"
                ))))
                .build();
        HttpResponse<String> runResp = httpClient.send(runReq, HttpResponse.BodyHandlers.ofString());

        assertEquals(503, runResp.statusCode(), "Must return HTTP 503");
        JsonNode body = objectMapper.readTree(runResp.body());
        assertEquals("GKE_AGENT_NOT_CONNECTED", body.path("code").asText());

        int runCountAfter = jdbcTemplate.queryForObject(countRuns, Integer.class, convId);
        int msgCountAfter = jdbcTemplate.queryForObject(countMessages, Integer.class, convId);

        assertEquals(runCountBefore, runCountAfter, "Zero DB chat_run rows inserted on 503");
        assertEquals(msgCountBefore, msgCountAfter, "Zero DB message rows inserted on 503");
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM provider_attempt WHERE workspace_id = ?", Integer.class, workspaceId));
        assertEquals(0, model.invocations());
    }

    @Test
    @DisplayName("3. When OFF: LOCAL bot conversation and run execution remain fully functional and unaffected")
    void testOffMode_LocalBotUnaffected() throws Exception {
        // Create conversation with LOCAL bot
        String convPayload = objectMapper.writeValueAsString(Map.of(
                "query", "mutation CreateConv($botId: ID!, $title: String, $workspace: ID!) { createConversation(botId: $botId, title: $title, workspaceId: $workspace) { id botId } }",
                "variables", Map.of("botId", "bot-general", "title", "Local Bot Conv", "workspace", workspaceId)
        ));
        JsonNode convRes = executeGraphQL(convPayload);
        assertFalse(convRes.has("errors"), "createConversation should succeed for LOCAL bot: " + convRes);
        JsonNode conversation = convRes.path("data").path("createConversation");
        assertEquals("bot-general", conversation.path("botId").asText());
        String convId = conversation.path("id").asText();
        assertFalse(convId.isBlank());

        // Start run for LOCAL bot
        HttpRequest runReq = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/api/conversations/" + convId + "/runs"))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(Map.of(
                        "clientRequestId", "req-local-off-01",
                        "message", "Hello Local"
                ))))
                .build();
        HttpResponse<String> runResp = httpClient.send(runReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, runResp.statusCode());
        assertTrue(runResp.body().contains("run.completed"), runResp.body());
        assertTrue(runResp.headers().firstValue("Content-Type").orElse("").contains("text/event-stream"));

        // Verify execution_mode in chat_run is LOCAL
        String runMode = jdbcTemplate.queryForObject(
                "SELECT execution_mode FROM chat_run WHERE conversation_id = ? AND client_request_id = 'req-local-off-01'",
                String.class,
                convId
        );
        assertEquals("LOCAL", runMode);
        assertEquals(1, model.invocations());
    }
}
