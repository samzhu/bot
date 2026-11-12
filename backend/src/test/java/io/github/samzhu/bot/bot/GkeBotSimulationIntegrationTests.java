package io.github.samzhu.bot.bot;

import io.github.samzhu.bot.PostgresTestImage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.samzhu.bot.BotApplication;
import io.github.samzhu.bot.ControlledChatModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(
        classes = {BotApplication.class, GkeBotSimulationIntegrationTests.PostgresTestConfig.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@ActiveProfiles("test")
@TestPropertySource(properties = "bot.gke.execution-mode=SIMULATED")
public class GkeBotSimulationIntegrationTests {

    @TestConfiguration(proxyBeanMethods = false)
    public static class PostgresTestConfig {
        @Bean
        @ServiceConnection
        PostgreSQLContainer postgresContainer() {
            return PostgresTestImage.container();
        }
    }

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @TestBean(name = "mockChatModel", methodName = "controlledChatModel", enforceOverride = true)
    ChatModel mockChatModel;

    ControlledChatModel controlledChatModel = ControlledChatModel.getInstance();

    static ControlledChatModel controlledChatModel() {
        return ControlledChatModel.getInstance();
    }

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private String baseUrl;

    @BeforeEach
    void setUp() {
        baseUrl = "http://127.0.0.1:" + port;
        controlledChatModel.reset();
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
    @DisplayName("1. defaultLocalBot query returns local default bot explicitly with NOT_APPLICABLE status")
    void testDefaultLocalBotQuery() throws Exception {
        String payload = objectMapper.writeValueAsString(Map.of(
                "query", "query { defaultLocalBot { id name target canChat connectionStatus executionMode } }"
        ));
        JsonNode root = executeGraphQL(payload);
        JsonNode bot = root.path("data").path("defaultLocalBot");
        assertFalse(bot.isMissingNode());
        assertEquals("bot-general", bot.path("id").asText());
        assertEquals("LOCAL", bot.path("target").asText());
        assertTrue(bot.path("canChat").asBoolean());
        assertEquals("NOT_APPLICABLE", bot.path("connectionStatus").asText());
        assertEquals("LOCAL", bot.path("executionMode").asText());
    }

    @Test
    @DisplayName("2. defaultLocalBot query returns null when other LOCAL bots exist but default bot-general is absent")
    void testDefaultLocalBotReturnsNullWhenGeneralAbsent() throws Exception {
        String otherLocalId = "bot-other-local-" + UUID.randomUUID();
        jdbcTemplate.update("""
            INSERT INTO bot (id, name, target, description, created_at, updated_at)
            VALUES (?, 'Secondary Local Bot', 'LOCAL', 'Non-default local', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
        """, otherLocalId);

        // Temporarily delete bot-general
        jdbcTemplate.update("DELETE FROM bot WHERE id = 'bot-general'");
        try {
            String payload = objectMapper.writeValueAsString(Map.of(
                    "query", "query { defaultLocalBot { id name } }"
            ));
            JsonNode root = executeGraphQL(payload);
            assertTrue(root.path("data").path("defaultLocalBot").isNull(),
                    "defaultLocalBot must return null when bot-general is missing, never fallback to secondary local bot");
        } finally {
            // Restore bot-general for subsequent tests
            jdbcTemplate.update("""
                INSERT INTO bot (id, name, system_prompt, target, created_at, updated_at)
                VALUES ('bot-general', 'General Assistant', 'System prompt', 'LOCAL', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                ON CONFLICT (id) DO NOTHING;
            """);
        }
    }

    @Test
    @DisplayName("3. createBot succeeds with trimmed name, dev-user owner, NOT_CONNECTED status, and SIMULATED canChat=true")
    void testCreateBot_Success() throws Exception {
        String payload = objectMapper.writeValueAsString(Map.of(
                "query", "mutation CreateBot($input: CreateBotInput!) { createBot(input: $input) { id name target description instructions executionMode connectionStatus canChat statusReason } }",
                "variables", Map.of("input", Map.of(
                        "name", "  Code Review Agent  ",
                        "description", "Reviews pull requests",
                        "instructions", "Be strict and concise"
                ))
        ));
        JsonNode root = executeGraphQL(payload);
        assertFalse(root.has("errors"), "GraphQL mutation should not have errors: " + root);
        JsonNode bot = root.path("data").path("createBot");
        assertFalse(bot.isMissingNode());
        assertEquals("Code Review Agent", bot.path("name").asText());
        assertEquals("GKE_REMOTE", bot.path("target").asText());
        assertEquals("Reviews pull requests", bot.path("description").asText());
        assertEquals("Be strict and concise", bot.path("instructions").asText());
        assertEquals("SPRING_AI_SIMULATION", bot.path("executionMode").asText());
        assertEquals("NOT_CONNECTED", bot.path("connectionStatus").asText());
        assertTrue(bot.path("canChat").asBoolean());
        assertEquals("本機模擬，GKE尚未串接", bot.path("statusReason").asText());
    }

    @Test
    @DisplayName("4. createBot validation: 100-char name with whitespace succeeds, 101-char trimmed fails, blank/super-long rejected with 0 DB delta")
    void testCreateBot_ValidationRejections() throws Exception {
        int botCountBefore = jdbcTemplate.queryForObject("SELECT count(*) FROM bot", Integer.class);

        // 1. Boundary: 100-char name with leading and trailing whitespace MUST succeed
        String name100WithSpaces = "   " + "x".repeat(100) + "   ";
        String valid100Payload = objectMapper.writeValueAsString(Map.of(
                "query", "mutation CreateBot($input: CreateBotInput!) { createBot(input: $input) { id name description instructions } }",
                "variables", Map.of("input", Map.of(
                        "name", name100WithSpaces,
                        "description", "  Preserve raw desc spaces  ",
                        "instructions", "  Preserve raw instructions spaces  "
                ))
        ));
        JsonNode res100 = executeGraphQL(valid100Payload);
        assertFalse(res100.has("errors"), "100-char name with padding spaces must succeed after trim normalization: " + res100);
        JsonNode bot100 = res100.path("data").path("createBot");
        assertEquals("x".repeat(100), bot100.path("name").asText(), "Name must be normalized and trimmed to 100 chars");
        assertEquals("  Preserve raw desc spaces  ", bot100.path("description").asText(), "Description must not be trimmed");
        assertEquals("  Preserve raw instructions spaces  ", bot100.path("instructions").asText(), "Instructions must not be trimmed");

        // 2. Boundary: 101-char name after trim MUST fail
        String name101WithSpaces = "   " + "y".repeat(101) + "   ";
        String invalid101Payload = objectMapper.writeValueAsString(Map.of(
                "query", "mutation CreateBot($input: CreateBotInput!) { createBot(input: $input) { id } }",
                "variables", Map.of("input", Map.of("name", name101WithSpaces))
        ));
        JsonNode res101 = executeGraphQL(invalid101Payload);
        assertTrue(res101.has("errors"), "101-char trimmed name must produce validation errors: " + res101);

        // 3. Blank name
        String blankNamePayload = objectMapper.writeValueAsString(Map.of(
                "query", "mutation CreateBot($input: CreateBotInput!) { createBot(input: $input) { id } }",
                "variables", Map.of("input", Map.of("name", "   "))
        ));
        JsonNode res1 = executeGraphQL(blankNamePayload);
        assertTrue(res1.has("errors"), "Blank name must produce errors: " + res1);

        // 4. Long description > 2000
        String longDescStr = "b".repeat(2001);
        String longDescPayload = objectMapper.writeValueAsString(Map.of(
                "query", "mutation CreateBot($input: CreateBotInput!) { createBot(input: $input) { id } }",
                "variables", Map.of("input", Map.of("name", "Valid Name", "description", longDescStr))
        ));
        JsonNode res3 = executeGraphQL(longDescPayload);
        assertTrue(res3.has("errors"), "Desc > 2000 must produce errors: " + res3);

        // 5. Long instructions > 10000
        String longInstrStr = "c".repeat(10001);
        String longInstrPayload = objectMapper.writeValueAsString(Map.of(
                "query", "mutation CreateBot($input: CreateBotInput!) { createBot(input: $input) { id } }",
                "variables", Map.of("input", Map.of("name", "Valid Name", "instructions", longInstrStr))
        ));
        JsonNode res4 = executeGraphQL(longInstrPayload);
        assertTrue(res4.has("errors"), "Instr > 10000 must produce errors: " + res4);

        int botCountAfter = jdbcTemplate.queryForObject("SELECT count(*) FROM bot", Integer.class);
        assertEquals(botCountBefore + 1, botCountAfter, "Database bot row count delta must be exactly 1 (only the successful 100-char bot)");
    }

    @Test
    @DisplayName("5. Visibility: foreign-owned GKE bot is hidden from dev-user, LOCAL bot is shared")
    void testBotVisibilityIsolation() throws Exception {
        String foreignBotId = "bot-foreign-" + UUID.randomUUID();
        jdbcTemplate.update("""
            INSERT INTO bot (id, name, target, description, instructions, owner_id, created_at, updated_at)
            VALUES (?, 'Foreign Secret Bot', 'GKE_REMOTE', 'Classified', 'Top secret', 'foreign-user', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
        """, foreignBotId);

        // Single query for foreign bot should return null
        String queryForeign = objectMapper.writeValueAsString(Map.of(
                "query", "query GetBot($id: ID!) { bot(id: $id) { id name } }",
                "variables", Map.of("id", foreignBotId)
        ));
        JsonNode resForeign = executeGraphQL(queryForeign);
        assertTrue(resForeign.path("data").path("bot").isNull(), "Foreign GKE bot should not be visible to dev-user");

        // List query should not contain foreign bot
        String listQuery = objectMapper.writeValueAsString(Map.of(
                "query", "query { bots { id name } }"
        ));
        JsonNode resList = executeGraphQL(listQuery);
        JsonNode botsList = resList.path("data").path("bots");
        for (JsonNode b : botsList) {
            assertNotEquals(foreignBotId, b.path("id").asText(), "Foreign bot must not appear in bots list");
        }

        // But LOCAL bot is visible
        String queryLocal = objectMapper.writeValueAsString(Map.of(
                "query", "query { bot(id: \"bot-general\") { id name target } }"
        ));
        JsonNode resLocal = executeGraphQL(queryLocal);
        assertEquals("bot-general", resLocal.path("data").path("bot").path("id").asText());
    }

    @Test
    @DisplayName("6. Simulation mode allows conversation creation and records run executionMode provenance")
    void testSimulationMode_RunProvenance() throws Exception {
        // Create GKE bot
        String createPayload = objectMapper.writeValueAsString(Map.of(
                "query", "mutation CreateBot($input: CreateBotInput!) { createBot(input: $input) { id } }",
                "variables", Map.of("input", Map.of(
                        "name", "Sim Agent",
                        "instructions", "Sim instructions"
                ))
        ));
        JsonNode createRes = executeGraphQL(createPayload);
        String botId = createRes.path("data").path("createBot").path("id").asText();

        // Create conversation with GKE bot (succeeds in SIMULATED mode)
        String convPayload = objectMapper.writeValueAsString(Map.of(
                "query", "mutation CreateConv($botId: ID!, $title: String) { createConversation(botId: $botId, title: $title) { id botId } }",
                "variables", Map.of("botId", botId, "title", "Sim Conversation")
        ));
        JsonNode convRes = executeGraphQL(convPayload);
        assertFalse(convRes.has("errors"), "createConversation should succeed in simulation mode: " + convRes);
        String convId = convRes.path("data").path("createConversation").path("id").asText();

        // Start run
        HttpRequest runReq = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/conversations/" + convId + "/runs"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(Map.of(
                        "clientRequestId", "req-sim-01",
                        "message", "Hello Sim"
                ))))
                .build();
        HttpResponse<String> runResp = httpClient.send(runReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, runResp.statusCode());
        assertTrue(runResp.headers().firstValue("Content-Type").orElse("").contains("text/event-stream"));

        // Check database run provenance
        String runMode = jdbcTemplate.queryForObject(
                "SELECT execution_mode FROM chat_run WHERE conversation_id = ? AND client_request_id = 'req-sim-01'",
                String.class,
                convId
        );
        assertEquals("SPRING_AI_SIMULATION", runMode, "Execution mode in chat_run must be recorded as SPRING_AI_SIMULATION");

        // Check GraphQL query for run executionMode (only executionMode, no mode alias)
        String runId = jdbcTemplate.queryForObject(
                "SELECT id FROM chat_run WHERE conversation_id = ? AND client_request_id = 'req-sim-01'",
                String.class,
                convId
        );
        String runQuery = objectMapper.writeValueAsString(Map.of(
                "query", "query GetRun($id: ID!) { run(id: $id) { id executionMode status } }",
                "variables", Map.of("id", runId)
        ));
        JsonNode runQueryRes = executeGraphQL(runQuery);
        assertEquals("SPRING_AI_SIMULATION", runQueryRes.path("data").path("run").path("executionMode").asText());
    }

    @Test
    @DisplayName("7. Different GKE bots maintain strict instruction and conversation session isolation")
    void testMultipleBots_InstructionAndSessionIsolation() throws Exception {
        // Create Bot A with distinct instructions
        String createBotAPayload = objectMapper.writeValueAsString(Map.of(
                "query", "mutation CreateBot($input: CreateBotInput!) { createBot(input: $input) { id } }",
                "variables", Map.of("input", Map.of("name", "Alpha Bot", "instructions", "INSTRUCTION_ALPHA_ONLY"))
        ));
        String botAId = executeGraphQL(createBotAPayload).path("data").path("createBot").path("id").asText();

        // Create Bot B with distinct instructions
        String createBotBPayload = objectMapper.writeValueAsString(Map.of(
                "query", "mutation CreateBot($input: CreateBotInput!) { createBot(input: $input) { id } }",
                "variables", Map.of("input", Map.of("name", "Beta Bot", "instructions", "INSTRUCTION_BETA_ONLY"))
        ));
        String botBId = executeGraphQL(createBotBPayload).path("data").path("createBot").path("id").asText();

        // Create Conv A for Bot A
        String convAPayload = objectMapper.writeValueAsString(Map.of(
                "query", "mutation CreateConv($botId: ID!, $title: String) { createConversation(botId: $botId, title: $title) { id } }",
                "variables", Map.of("botId", botAId, "title", "Conv A")
        ));
        String convAId = executeGraphQL(convAPayload).path("data").path("createConversation").path("id").asText();

        // Create Conv B for Bot B
        String convBPayload = objectMapper.writeValueAsString(Map.of(
                "query", "mutation CreateConv($botId: ID!, $title: String) { createConversation(botId: $botId, title: $title) { id } }",
                "variables", Map.of("botId", botBId, "title", "Conv B")
        ));
        String convBId = executeGraphQL(convBPayload).path("data").path("createConversation").path("id").asText();

        // Run message in Conv A
        HttpRequest runAReq = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/conversations/" + convAId + "/runs"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(Map.of(
                        "clientRequestId", "req-iso-a1",
                        "message", "Message for Alpha"
                ))))
                .build();
        HttpResponse<String> respA = httpClient.send(runAReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, respA.statusCode());

        // Verify prompt captured by ControlledChatModel for Conv A
        List<Prompt> promptsA = controlledChatModel.getRecordedPrompts();
        assertEquals(1, promptsA.size());
        Prompt promptA = promptsA.get(0);
        String systemA = extractSystemText(promptA);
        assertTrue(systemA.contains("INSTRUCTION_ALPHA_ONLY"), "Prompt A must contain Alpha instructions");
        assertFalse(systemA.contains("INSTRUCTION_BETA_ONLY"), "Prompt A must not contain Beta instructions");

        // Verify JDBC Session persisted for Conv A in AI_SESSION and AI_SESSION_EVENT
        Integer convASessionEvents = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM AI_SESSION_EVENT WHERE session_id = ?",
                Integer.class,
                convAId
        );
        assertNotNull(convASessionEvents);
        assertTrue(convASessionEvents >= 2, "Session A must persist USER and ASSISTANT events in Postgres: " + convASessionEvents);

        Integer convBSessionEventsBefore = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM AI_SESSION_EVENT WHERE session_id = ?",
                Integer.class,
                convBId
        );
        assertEquals(0, convBSessionEventsBefore, "Session B must have zero events before being run");

        // Run message in Conv B
        HttpRequest runBReq = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/conversations/" + convBId + "/runs"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(Map.of(
                        "clientRequestId", "req-iso-b1",
                        "message", "Message for Beta"
                ))))
                .build();
        HttpResponse<String> respB = httpClient.send(runBReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, respB.statusCode());

        // Verify prompt captured for Conv B
        List<Prompt> promptsAfterB = controlledChatModel.getRecordedPrompts();
        assertEquals(2, promptsAfterB.size());
        Prompt promptB = promptsAfterB.get(1);
        String systemB = extractSystemText(promptB);
        assertTrue(systemB.contains("INSTRUCTION_BETA_ONLY"), "Prompt B must contain Beta instructions");
        assertFalse(systemB.contains("INSTRUCTION_ALPHA_ONLY"), "Prompt B must not contain Alpha instructions");

        // Verify prompt B contains only Conv B's message, not Conv A's message
        String promptBText = promptB.getInstructions().stream()
                .map(Message::getText)
                .reduce("", (acc, s) -> acc + " " + s);
        assertTrue(promptBText.contains("Message for Beta"));
        assertFalse(promptBText.contains("Message for Alpha"), "Prompt B must not cross-contaminate history from Conv A");

        // Verify JDBC Session persisted for Conv B
        Integer convBSessionEventsAfter = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM AI_SESSION_EVENT WHERE session_id = ?",
                Integer.class,
                convBId
        );
        assertNotNull(convBSessionEventsAfter);
        assertTrue(convBSessionEventsAfter >= 2, "Session B must persist independently in Postgres: " + convBSessionEventsAfter);
    }

    @Test
    @DisplayName("8. Deduplication: duplicate clientRequestId on GKE bot returns 200 with 0 additional model calls")
    void testDeduplication_ZeroNewModelCalls() throws Exception {
        // Create bot and conversation
        String createBotPayload = objectMapper.writeValueAsString(Map.of(
                "query", "mutation CreateBot($input: CreateBotInput!) { createBot(input: $input) { id } }",
                "variables", Map.of("input", Map.of("name", "Dedup Bot", "instructions", "Dedup Instructions"))
        ));
        String botId = executeGraphQL(createBotPayload).path("data").path("createBot").path("id").asText();

        String convPayload = objectMapper.writeValueAsString(Map.of(
                "query", "mutation CreateConv($botId: ID!, $title: String) { createConversation(botId: $botId, title: $title) { id } }",
                "variables", Map.of("botId", botId, "title", "Dedup Conv")
        ));
        String convId = executeGraphQL(convPayload).path("data").path("createConversation").path("id").asText();

        // First execution
        HttpRequest req1 = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/conversations/" + convId + "/runs"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(Map.of(
                        "clientRequestId", "req-dedup-01",
                        "message", "Unique message"
                ))))
                .build();
        HttpResponse<String> resp1 = httpClient.send(req1, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp1.statusCode());
        assertTrue(resp1.headers().firstValue("Content-Type").orElse("").contains("text/event-stream"));

        int modelCallsAfterFirst = controlledChatModel.getRecordedPrompts().size();
        assertEquals(1, modelCallsAfterFirst, "One model call should have occurred for the initial run");

        // Second duplicate execution with SAME clientRequestId and SAME message
        HttpRequest req2 = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/conversations/" + convId + "/runs"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(Map.of(
                        "clientRequestId", "req-dedup-01",
                        "message", "Unique message"
                ))))
                .build();
        HttpResponse<String> resp2 = httpClient.send(req2, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp2.statusCode());
        assertTrue(resp2.headers().firstValue("Content-Type").orElse("").contains("application/json"),
                "Duplicate request must return existing JSON run without streaming");

        int modelCallsAfterSecond = controlledChatModel.getRecordedPrompts().size();
        assertEquals(modelCallsAfterFirst, modelCallsAfterSecond, "Deduplicated request must make 0 new model calls");
    }

    @Test
    @DisplayName("9. Model failure on GKE bot does not append failed assistant message into JDBC session")
    void testModelFailure_DoesNotAppendAssistantSessionEvent() throws Exception {
        // Create bot and conversation
        String createBotPayload = objectMapper.writeValueAsString(Map.of(
                "query", "mutation CreateBot($input: CreateBotInput!) { createBot(input: $input) { id } }",
                "variables", Map.of("input", Map.of("name", "Fail Bot", "instructions", "Fail Instructions"))
        ));
        String botId = executeGraphQL(createBotPayload).path("data").path("createBot").path("id").asText();

        String convPayload = objectMapper.writeValueAsString(Map.of(
                "query", "mutation CreateConv($botId: ID!, $title: String) { createConversation(botId: $botId, title: $title) { id } }",
                "variables", Map.of("botId", botId, "title", "Fail Conv")
        ));
        String convId = executeGraphQL(convPayload).path("data").path("createConversation").path("id").asText();

        // Message containing "[ERROR]" triggers ControlledChatModel streaming error
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/conversations/" + convId + "/runs"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(Map.of(
                        "clientRequestId", "req-fail-01",
                        "message", "Trigger [ERROR] failure"
                ))))
                .build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode());

        // Wait brief moment for terminal callback to commit
        Thread.sleep(300);

        // Verify chat_run status is FAILED
        String runStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM chat_run WHERE conversation_id = ? AND client_request_id = 'req-fail-01'",
                String.class,
                convId
        );
        assertEquals("FAILED", runStatus);

        // Verify that in AI_SESSION_EVENT, NO ASSISTANT message was appended
        Integer assistantEvents = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM AI_SESSION_EVENT WHERE session_id = ? AND message_type = 'ASSISTANT'",
                Integer.class,
                convId
        );
        assertEquals(0, assistantEvents, "Failed run must not append ASSISTANT event into Session memory");
    }

    @Test
    @DisplayName("10. Multi-turn isolation: A/B each run turn 1, then A runs turn 2; prompt 3 contains A's own history and no B")
    void testMultiTurn_A_Then_B_Then_A_SecondTurn_HistoryAndSessionIsolation() throws Exception {
        // Create Bot A and Bot B
        String createBotAPayload = objectMapper.writeValueAsString(Map.of(
                "query", "mutation CreateBot($input: CreateBotInput!) { createBot(input: $input) { id } }",
                "variables", Map.of("input", Map.of("name", "Alpha MultiBot", "instructions", "ALPHA_SPECIFIC_INSTRUCTION"))
        ));
        String botAId = executeGraphQL(createBotAPayload).path("data").path("createBot").path("id").asText();

        String createBotBPayload = objectMapper.writeValueAsString(Map.of(
                "query", "mutation CreateBot($input: CreateBotInput!) { createBot(input: $input) { id } }",
                "variables", Map.of("input", Map.of("name", "Beta MultiBot", "instructions", "BETA_SPECIFIC_INSTRUCTION"))
        ));
        String botBId = executeGraphQL(createBotBPayload).path("data").path("createBot").path("id").asText();

        // Create Conv A and Conv B
        String convAPayload = objectMapper.writeValueAsString(Map.of(
                "query", "mutation CreateConv($botId: ID!, $title: String) { createConversation(botId: $botId, title: $title) { id } }",
                "variables", Map.of("botId", botAId, "title", "Conv A Multi")
        ));
        String convAId = executeGraphQL(convAPayload).path("data").path("createConversation").path("id").asText();

        String convBPayload = objectMapper.writeValueAsString(Map.of(
                "query", "mutation CreateConv($botId: ID!, $title: String) { createConversation(botId: $botId, title: $title) { id } }",
                "variables", Map.of("botId", botBId, "title", "Conv B Multi")
        ));
        String convBId = executeGraphQL(convBPayload).path("data").path("createConversation").path("id").asText();

        // 1. Turn 1 on Conv A
        HttpRequest runA1 = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/conversations/" + convAId + "/runs"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(Map.of(
                        "clientRequestId", "req-a-turn-1",
                        "message", "Question A1"
                ))))
                .build();
        assertEquals(200, httpClient.send(runA1, HttpResponse.BodyHandlers.ofString()).statusCode());

        // 2. Turn 1 on Conv B
        HttpRequest runB1 = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/conversations/" + convBId + "/runs"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(Map.of(
                        "clientRequestId", "req-b-turn-1",
                        "message", "Question B1"
                ))))
                .build();
        assertEquals(200, httpClient.send(runB1, HttpResponse.BodyHandlers.ofString()).statusCode());

        // 3. Turn 2 on Conv A
        HttpRequest runA2 = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/conversations/" + convAId + "/runs"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(Map.of(
                        "clientRequestId", "req-a-turn-2",
                        "message", "Question A2"
                ))))
                .build();
        assertEquals(200, httpClient.send(runA2, HttpResponse.BodyHandlers.ofString()).statusCode());

        // Assert 3 total model prompts recorded
        List<Prompt> recorded = controlledChatModel.getRecordedPrompts();
        assertEquals(3, recorded.size());

        // Prompt 3 (Conv A Turn 2) verification:
        Prompt promptA2 = recorded.get(2);
        String systemA2 = extractSystemText(promptA2);
        assertTrue(systemA2.contains("ALPHA_SPECIFIC_INSTRUCTION"), "Prompt A2 must have Bot A instruction");
        assertFalse(systemA2.contains("BETA_SPECIFIC_INSTRUCTION"), "Prompt A2 must not have Bot B instruction");

        String fullTextA2 = promptA2.getInstructions().stream()
                .map(Message::getText)
                .reduce("", (acc, s) -> acc + " " + s);
        assertTrue(fullTextA2.contains("Question A1"), "Prompt A2 must contain previous turn Question A1");
        assertTrue(fullTextA2.contains("Question A2"), "Prompt A2 must contain current turn Question A2");
        assertFalse(fullTextA2.contains("Question B1"), "Prompt A2 must NOT contain Question B1 from Conv B");

        // Verify Postgres Session event counts
        Integer convAEvents = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM AI_SESSION_EVENT WHERE session_id = ?",
                Integer.class,
                convAId
        );
        Integer convBEvents = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM AI_SESSION_EVENT WHERE session_id = ?",
                Integer.class,
                convBId
        );
        assertEquals(4, convAEvents, "Conv A must have exactly 4 session events (2 turns x 2 messages)");
        assertEquals(2, convBEvents, "Conv B must have exactly 2 session events (1 turn x 2 messages)");
    }

    @Test
    @DisplayName("11. In-flight cancel on GKE simulated streaming marks CANCELLED, interrupted=true, and session delta=0")
    void testGkeSimulatedStream_MidwayCancel_Interrupted_And_SessionDeltaZero() throws Exception {
        // Create bot and conversation
        String createBotPayload = objectMapper.writeValueAsString(Map.of(
                "query", "mutation CreateBot($input: CreateBotInput!) { createBot(input: $input) { id } }",
                "variables", Map.of("input", Map.of("name", "Cancel Bot", "instructions", "Cancel Instructions"))
        ));
        String botId = executeGraphQL(createBotPayload).path("data").path("createBot").path("id").asText();

        String convPayload = objectMapper.writeValueAsString(Map.of(
                "query", "mutation CreateConv($botId: ID!, $title: String) { createConversation(botId: $botId, title: $title) { id } }",
                "variables", Map.of("botId", botId, "title", "Cancel Conv")
        ));
        String convId = executeGraphQL(convPayload).path("data").path("createConversation").path("id").asText();

        controlledChatModel.setHoldMode(true);

        HttpRequest runReq = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/conversations/" + convId + "/runs"))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(Map.of(
                        "clientRequestId", "req-cancel-01",
                        "message", "Cancel test message"
                ))))
                .build();

        HttpResponse<java.io.InputStream> runResp = httpClient.send(runReq, HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200, runResp.statusCode());

        assertTrue(controlledChatModel.awaitSubscribed(5, java.util.concurrent.TimeUnit.SECONDS));
        controlledChatModel.emitChunk("Partial delta on GKE");

        java.util.concurrent.CountDownLatch deltaLatch = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<String> runIdRef = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.List<String> terminalEvents = new java.util.concurrent.CopyOnWriteArrayList<>();

        java.util.concurrent.CompletableFuture<Void> readerFuture = java.util.concurrent.CompletableFuture.runAsync(() -> {
            try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(runResp.body(), java.nio.charset.StandardCharsets.UTF_8))) {
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
                            deltaLatch.countDown();
                        }
                    }
                }
            } catch (Exception ignored) {}
        });

        assertTrue(deltaLatch.await(5, java.util.concurrent.TimeUnit.SECONDS), "Partial delta should arrive on SSE stream");
        String activeRunId = runIdRef.get();
        assertNotNull(activeRunId, "Active runId must be obtained from SSE stream");

        // Send cancel request
        HttpRequest cancelReq = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/runs/" + activeRunId + "/cancel"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> cancelResp = httpClient.send(cancelReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, cancelResp.statusCode());
        JsonNode cancelJson = objectMapper.readTree(cancelResp.body());
        assertEquals("CANCELLED", cancelJson.path("status").asText());
        assertTrue(cancelJson.path("cancelled").asBoolean());

        readerFuture.get(5, java.util.concurrent.TimeUnit.SECONDS);
        assertTrue(terminalEvents.contains("run.cancelled"), "SSE terminal event must be run.cancelled");

        // Verify DB chat_run state
        String dbRunStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM chat_run WHERE id = ?",
                String.class,
                activeRunId
        );
        assertEquals("CANCELLED", dbRunStatus);

        // Verify DB message state: partial assistant message saved with interrupted = true
        List<Map<String, Object>> msgs = jdbcTemplate.queryForList(
                "SELECT role, content, interrupted FROM message WHERE run_id = ? AND role = 'ASSISTANT'",
                activeRunId
        );
        assertEquals(1, msgs.size());
        assertEquals("Partial delta on GKE", msgs.get(0).get("content"));
        assertEquals(Boolean.TRUE, msgs.get(0).get("interrupted"));

        // Verify Session event delta = 0 (no ASSISTANT message appended to session memory)
        Integer sessionAssistantCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM AI_SESSION_EVENT WHERE session_id = ? AND message_type = 'ASSISTANT'",
                Integer.class,
                convId
        );
        assertEquals(0, sessionAssistantCount, "Cancelled run must not append ASSISTANT event to Session memory");
    }

    private static String extractSystemText(Prompt prompt) {
        return prompt.getInstructions().stream()
                .filter(m -> m instanceof SystemMessage)
                .map(Message::getText)
                .reduce("", (acc, s) -> acc + " " + s);
    }
}
