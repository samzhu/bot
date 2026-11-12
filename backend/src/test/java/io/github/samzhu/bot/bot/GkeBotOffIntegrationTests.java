package io.github.samzhu.bot.bot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.samzhu.bot.BotApplication;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(
        classes = {BotApplication.class, GkeBotOffIntegrationTests.PostgresTestConfig.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@ActiveProfiles("test")
@TestPropertySource(properties = "bot.gke.execution-mode=OFF")
public class GkeBotOffIntegrationTests {

    @TestConfiguration(proxyBeanMethods = false)
    public static class PostgresTestConfig {
        @Bean
        @ServiceConnection
        PostgreSQLContainer postgresContainer() {
            return new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"));
        }
    }

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private String baseUrl;

    @BeforeEach
    void setUp() {
        baseUrl = "http://127.0.0.1:" + port;
        jdbcTemplate.update("""
            INSERT INTO bot (id, name, system_prompt, target, created_at, updated_at)
            VALUES ('bot-general', 'General Assistant', 'System prompt', 'LOCAL', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            ON CONFLICT (id) DO UPDATE SET target = 'LOCAL';
        """);
    }

    private JsonNode executeGraphQL(String payload) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/graphql"))
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
        String botId = "bot-gke-off-" + UUID.randomUUID();
        jdbcTemplate.update("""
            INSERT INTO bot (id, name, target, description, instructions, owner_id, created_at, updated_at)
            VALUES (?, 'GKE Off Bot', 'GKE_REMOTE', 'Desc', 'Instr', 'dev-user', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
        """, botId);

        int convCountBefore = jdbcTemplate.queryForObject("SELECT count(*) FROM conversation", Integer.class);

        String convPayload = objectMapper.writeValueAsString(Map.of(
                "query", "mutation CreateConv($botId: ID!, $title: String) { createConversation(botId: $botId, title: $title) { id } }",
                "variables", Map.of("botId", botId, "title", "Should Fail")
        ));
        JsonNode res = executeGraphQL(convPayload);

        assertTrue(res.has("errors"), "Must return GraphQL error: " + res);
        String errorCode = res.path("errors").get(0).path("extensions").path("code").asText();
        assertEquals("GKE_AGENT_NOT_CONNECTED", errorCode);

        int convCountAfter = jdbcTemplate.queryForObject("SELECT count(*) FROM conversation", Integer.class);
        assertEquals(convCountBefore, convCountAfter, "Conversation row count must not increase on rejection");
    }

    @Test
    @DisplayName("2. When OFF: startRun on GKE bot returns HTTP 503 GKE_AGENT_NOT_CONNECTED with zero side-effects")
    void testOffMode_StartRunRejected_503_ZeroSideEffects() throws Exception {
        String botId = "bot-gke-off-" + UUID.randomUUID();
        jdbcTemplate.update("""
            INSERT INTO bot (id, name, target, description, instructions, owner_id, created_at, updated_at)
            VALUES (?, 'GKE Off Bot', 'GKE_REMOTE', 'Desc', 'Instr', 'dev-user', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
        """, botId);

        String convId = "conv-off-" + UUID.randomUUID();
        jdbcTemplate.update("""
            INSERT INTO conversation (id, bot_id, title, user_id, pinned, created_at, updated_at)
            VALUES (?, ?, 'Pre-existing GKE Conv', 'dev-user', false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
        """, convId, botId);

        int runCountBefore = jdbcTemplate.queryForObject("SELECT count(*) FROM chat_run", Integer.class);
        int msgCountBefore = jdbcTemplate.queryForObject("SELECT count(*) FROM message", Integer.class);

        HttpRequest runReq = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/conversations/" + convId + "/runs"))
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

        int runCountAfter = jdbcTemplate.queryForObject("SELECT count(*) FROM chat_run", Integer.class);
        int msgCountAfter = jdbcTemplate.queryForObject("SELECT count(*) FROM message", Integer.class);

        assertEquals(runCountBefore, runCountAfter, "Zero DB chat_run rows inserted on 503");
        assertEquals(msgCountBefore, msgCountAfter, "Zero DB message rows inserted on 503");
    }

    @Test
    @DisplayName("3. When OFF: LOCAL bot conversation and run execution remain fully functional and unaffected")
    void testOffMode_LocalBotUnaffected() throws Exception {
        // Create conversation with LOCAL bot
        String convPayload = objectMapper.writeValueAsString(Map.of(
                "query", "mutation CreateConv($botId: ID!, $title: String) { createConversation(botId: $botId, title: $title) { id botId } }",
                "variables", Map.of("botId", "bot-general", "title", "Local Bot Conv")
        ));
        JsonNode convRes = executeGraphQL(convPayload);
        assertFalse(convRes.has("errors"), "createConversation should succeed for LOCAL bot: " + convRes);
        String convId = convRes.path("data").path("createConversation").path("id").asText();

        // Start run for LOCAL bot
        HttpRequest runReq = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/conversations/" + convId + "/runs"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(Map.of(
                        "clientRequestId", "req-local-off-01",
                        "message", "Hello Local"
                ))))
                .build();
        HttpResponse<String> runResp = httpClient.send(runReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, runResp.statusCode());
        assertTrue(runResp.headers().firstValue("Content-Type").orElse("").contains("text/event-stream"));

        // Verify execution_mode in chat_run is LOCAL
        String runMode = jdbcTemplate.queryForObject(
                "SELECT execution_mode FROM chat_run WHERE conversation_id = ? AND client_request_id = 'req-local-off-01'",
                String.class,
                convId
        );
        assertEquals("LOCAL", runMode);
    }
}
