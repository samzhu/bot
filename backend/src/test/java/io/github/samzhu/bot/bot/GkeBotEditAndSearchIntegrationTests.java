package io.github.samzhu.bot.bot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.samzhu.bot.BotApplication;
import io.github.samzhu.bot.ControlledChatModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
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
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration Tests for Ticket #17: Edit and Search Bot.
 *
 * <p>OFFICIAL REFERENCE:
 * <ul>
 *   <li>Spring for GraphQL 2.0.6 Controllers & Mutations: https://docs.spring.io/spring-graphql/reference/controllers.html</li>
 *   <li>Jakarta Bean Validation 3.1: https://jakarta.ee/specifications/bean-validation/3.1/</li>
 *   <li>PostgreSQL 16 Pattern Matching Chapter 9.7: https://www.postgresql.org/docs/16/functions-matching.html</li>
 *   <li>Checked: 2026-11-12</li>
 * </ul>
 *
 * <p>ZERO PAID CALLS PROTECTION:
 * Verified with ControlledChatModel mock bean override; 0 cloud tokens or costs incurred.
 */
@SpringBootTest(
        classes = {BotApplication.class, GkeBotEditAndSearchIntegrationTests.PostgresTestConfig.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@ActiveProfiles("test")
@TestPropertySource(properties = "bot.gke.execution-mode=SIMULATED")
public class GkeBotEditAndSearchIntegrationTests {

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
        // Reset and ensure standard seed bot
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
    @DisplayName("1. updateBot: Owner successfully updates GKE bot with trimmed name, raw description/instructions, and refreshed updated_at")
    void testUpdateBot_Success() throws Exception {
        String botId = "bot-gke-" + UUID.randomUUID();
        Instant initialCreatedAt = Instant.parse("2026-01-01T10:00:00Z");
        Timestamp initialTimestamp = Timestamp.from(initialCreatedAt);
        jdbcTemplate.update("""
            INSERT INTO bot (id, name, target, description, instructions, owner_id, created_at, updated_at)
            VALUES (?, 'Original Name', 'GKE_REMOTE', 'Original Desc', 'Original Instructions', 'dev-user', ?, ?)
        """, botId, initialTimestamp, initialTimestamp);

        String mutation = """
            mutation UpdateBot($input: UpdateBotInput!) {
              updateBot(input: $input) {
                id
                name
                description
                instructions
                target
                updatedAt
                createdAt
              }
            }
        """;

        String updatedNameWithSpaces = "   Updated Bot Name   ";
        String rawDesc = "  Updated Raw Desc with spaces and \n newline  ";
        String rawInstructions = "  Updated Raw Instructions with spaces and \n newline  ";

        String payload = objectMapper.writeValueAsString(Map.of(
                "query", mutation,
                "variables", Map.of("input", Map.of(
                        "id", botId,
                        "name", updatedNameWithSpaces,
                        "description", rawDesc,
                        "instructions", rawInstructions
                ))
        ));

        JsonNode res = executeGraphQL(payload);
        assertFalse(res.has("errors"), "Mutation must succeed without errors: " + res);

        JsonNode botNode = res.path("data").path("updateBot");
        assertEquals(botId, botNode.path("id").asText());
        assertEquals("Updated Bot Name", botNode.path("name").asText(), "Name must be trimmed");
        assertEquals(rawDesc, botNode.path("description").asText(), "Description must preserve raw input");
        assertEquals(rawInstructions, botNode.path("instructions").asText(), "Instructions must preserve raw input");
        assertEquals("GKE_REMOTE", botNode.path("target").asText());
        assertEquals(initialCreatedAt.toString(), botNode.path("createdAt").asText(), "createdAt must remain unchanged");
        assertNotEquals(initialCreatedAt.toString(), botNode.path("updatedAt").asText(), "updatedAt must be refreshed");

        // Verify in DB directly
        Map<String, Object> row = jdbcTemplate.queryForMap("SELECT * FROM bot WHERE id = ?", botId);
        assertEquals("Updated Bot Name", row.get("name"));
        assertEquals(rawDesc, row.get("description"));
        assertEquals(rawInstructions, row.get("instructions"));
        assertEquals("dev-user", row.get("owner_id"));
        assertEquals("GKE_REMOTE", row.get("target"));
    }

    @Test
    @DisplayName("2. updateBot: Attempting to update LOCAL bot is rejected with 0 DB writes")
    void testUpdateBot_LocalBotRejected() throws Exception {
        String mutation = """
            mutation UpdateBot($input: UpdateBotInput!) {
              updateBot(input: $input) {
                id
                name
              }
            }
        """;

        String payload = objectMapper.writeValueAsString(Map.of(
                "query", mutation,
                "variables", Map.of("input", Map.of(
                        "id", "bot-general",
                        "name", "Attempt to Rename General Assistant"
                ))
        ));

        JsonNode res = executeGraphQL(payload);
        assertTrue(res.has("errors"), "Updating LOCAL bot must return GraphQL errors: " + res);
        assertTrue(res.path("errors").get(0).path("message").asText().contains("LOCAL bot is read-only"));

        // Verify General Assistant unchanged in DB
        String nameInDb = jdbcTemplate.queryForObject("SELECT name FROM bot WHERE id = 'bot-general'", String.class);
        assertEquals("General Assistant", nameInDb, "LOCAL bot name must not have been modified");
    }

    @Test
    @DisplayName("3. updateBot: Non-existent bot and foreign-owned bot fail with 'Bot not found' (zero leakage) and 0 DB writes")
    void testUpdateBot_UnknownAndForeignBotZeroLeakage() throws Exception {
        String foreignBotId = "bot-gke-" + UUID.randomUUID();
        jdbcTemplate.update("""
            INSERT INTO bot (id, name, target, description, instructions, owner_id, created_at, updated_at)
            VALUES (?, 'Secret Foreign Bot', 'GKE_REMOTE', 'Secret Desc', 'Secret Instr', 'other-user',
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
        """, foreignBotId);

        String mutation = """
            mutation UpdateBot($input: UpdateBotInput!) {
              updateBot(input: $input) {
                id
                name
              }
            }
        """;

        // 1. Non-existent bot
        String unknownPayload = objectMapper.writeValueAsString(Map.of(
                "query", mutation,
                "variables", Map.of("input", Map.of(
                        "id", "bot-non-existent-12345",
                        "name", "Updated Name"
                ))
        ));
        JsonNode resUnknown = executeGraphQL(unknownPayload);
        assertTrue(resUnknown.has("errors"), "Non-existent bot must fail: " + resUnknown);
        assertTrue(resUnknown.path("errors").get(0).path("message").asText().contains("Bot not found"));

        // 2. Foreign-owned bot: must produce identical 'Bot not found' error without leaking existence
        String foreignPayload = objectMapper.writeValueAsString(Map.of(
                "query", mutation,
                "variables", Map.of("input", Map.of(
                        "id", foreignBotId,
                        "name", "Hacked Foreign Bot Name"
                ))
        ));
        JsonNode resForeign = executeGraphQL(foreignPayload);
        assertTrue(resForeign.has("errors"), "Foreign bot update must fail: " + resForeign);
        assertTrue(resForeign.path("errors").get(0).path("message").asText().contains("Bot not found"),
                "Foreign bot must report 'Bot not found' to prevent information leakage");

        // Verify foreign bot was NOT touched
        String foreignNameInDb = jdbcTemplate.queryForObject("SELECT name FROM bot WHERE id = ?", String.class, foreignBotId);
        assertEquals("Secret Foreign Bot", foreignNameInDb);
    }

    @Test
    @DisplayName("4. updateBot validation: 100-char name succeeds; 101-char, blank, desc>2000, instr>10000 fail with 0 writes")
    void testUpdateBot_ValidationRulesAndZeroWrites() throws Exception {
        String botId = "bot-gke-" + UUID.randomUUID();
        jdbcTemplate.update("""
            INSERT INTO bot (id, name, target, description, instructions, owner_id, created_at, updated_at)
            VALUES (?, 'Baseline Bot', 'GKE_REMOTE', 'Baseline Desc', 'Baseline Instr', 'dev-user',
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
        """, botId);

        String mutation = """
            mutation UpdateBot($input: UpdateBotInput!) {
              updateBot(input: $input) {
                id
                name
              }
            }
        """;

        // A. 100-char name after trim succeeds
        String name100 = "   " + "a".repeat(100) + "   ";
        JsonNode res100 = executeGraphQL(objectMapper.writeValueAsString(Map.of(
                "query", mutation,
                "variables", Map.of("input", Map.of("id", botId, "name", name100))
        )));
        assertFalse(res100.has("errors"), "100-char trimmed name must succeed: " + res100);
        assertEquals("a".repeat(100), res100.path("data").path("updateBot").path("name").asText());

        // B. 101-char name after trim fails
        String name101 = "   " + "b".repeat(101) + "   ";
        JsonNode res101 = executeGraphQL(objectMapper.writeValueAsString(Map.of(
                "query", mutation,
                "variables", Map.of("input", Map.of("id", botId, "name", name101))
        )));
        assertTrue(res101.has("errors"), "101-char trimmed name must fail: " + res101);

        // C. Blank name fails
        JsonNode resBlank = executeGraphQL(objectMapper.writeValueAsString(Map.of(
                "query", mutation,
                "variables", Map.of("input", Map.of("id", botId, "name", "    "))
        )));
        assertTrue(resBlank.has("errors"), "Blank name must fail: " + resBlank);

        // D. Description > 2000 chars fails
        JsonNode resDesc = executeGraphQL(objectMapper.writeValueAsString(Map.of(
                "query", mutation,
                "variables", Map.of("input", Map.of("id", botId, "name", "Valid Name", "description", "d".repeat(2001)))
        )));
        assertTrue(resDesc.has("errors"), "Desc > 2000 chars must fail: " + resDesc);

        // E. Instructions > 10000 chars fails
        JsonNode resInstr = executeGraphQL(objectMapper.writeValueAsString(Map.of(
                "query", mutation,
                "variables", Map.of("input", Map.of("id", botId, "name", "Valid Name", "instructions", "i".repeat(10001)))
        )));
        assertTrue(resInstr.has("errors"), "Instr > 10000 chars must fail: " + resInstr);

        // Verify DB state retained the last successful value (100 'a's) and none of the failed mutations wrote
        String currentName = jdbcTemplate.queryForObject("SELECT name FROM bot WHERE id = ?", String.class, botId);
        assertEquals("a".repeat(100), currentName);
    }

    @Test
    @DisplayName("5. History Binding: conversation.bot_id remains permanently associated after bot update")
    void testHistoryBinding_ConversationBotIdPreserved() throws Exception {
        String botId = "bot-gke-" + UUID.randomUUID();
        jdbcTemplate.update("""
            INSERT INTO bot (id, name, target, description, instructions, owner_id, created_at, updated_at)
            VALUES (?, 'Target Bot', 'GKE_REMOTE', 'Desc', 'Instr', 'dev-user', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
        """, botId);

        // Create a conversation bound to this bot
        String createConvMutation = """
            mutation CreateConversation($botId: ID!, $title: String) {
              createConversation(botId: $botId, title: $title) {
                id
                botId
                title
              }
            }
        """;
        JsonNode convRes = executeGraphQL(objectMapper.writeValueAsString(Map.of(
                "query", createConvMutation,
                "variables", Map.of("botId", botId, "title", "Test Conversation")
        )));
        assertFalse(convRes.has("errors"), "Conversation creation must succeed: " + convRes);
        String convId = convRes.path("data").path("createConversation").path("id").asText();
        assertEquals(botId, convRes.path("data").path("createConversation").path("botId").asText());

        // Update the bot
        String updateMutation = """
            mutation UpdateBot($input: UpdateBotInput!) {
              updateBot(input: $input) {
                id
                name
              }
            }
        """;
        executeGraphQL(objectMapper.writeValueAsString(Map.of(
                "query", updateMutation,
                "variables", Map.of("input", Map.of("id", botId, "name", "Renamed Target Bot"))
        )));

        // Verify conversation still points to the same botId
        Map<String, Object> convInDb = jdbcTemplate.queryForMap("SELECT * FROM conversation WHERE id = ?", convId);
        assertEquals(botId, convInDb.get("bot_id"), "conversation.bot_id must remain strictly unchanged");
    }

    @Test
    @DisplayName("6. Backward Compatibility: bots query with 0 arguments returns visible bots without error")
    void testBotsQuery_BackwardCompatibleNoArgs() throws Exception {
        String query = """
            query {
              bots {
                id
                name
                target
              }
            }
        """;
        JsonNode res = executeGraphQL(objectMapper.writeValueAsString(Map.of("query", query)));
        assertFalse(res.has("errors"), "0-argument bots query must succeed: " + res);
        JsonNode bots = res.path("data").path("bots");
        assertTrue(bots.isArray());
        assertTrue(bots.size() >= 1, "Must contain at least the seed bot-general");
    }

    @Test
    @DisplayName("7. Search: case-insensitive name/description match, excludes instructions, trims whitespace, empty search is no-op")
    void testSearch_CaseInsensitive_ExcludesInstructions_TrimsWhitespace() throws Exception {
        String bot1 = "bot-gke-" + UUID.randomUUID();
        String bot2 = "bot-gke-" + UUID.randomUUID();
        String bot3 = "bot-gke-" + UUID.randomUUID();
        String foreignBot = "bot-gke-foreign-" + UUID.randomUUID();

        // bot1: keyword in name (different casing)
        jdbcTemplate.update("""
            INSERT INTO bot (id, name, target, description, instructions, owner_id, created_at, updated_at)
            VALUES (?, 'Kubernetes Orchestrator Agent', 'GKE_REMOTE', 'Cluster management', 'Standard worker', 'dev-user',
                    TIMESTAMP '2026-01-01 12:00:00', TIMESTAMP '2026-01-01 12:00:00')
        """, bot1);

        // bot2: keyword in description (different casing)
        jdbcTemplate.update("""
            INSERT INTO bot (id, name, target, description, instructions, owner_id, created_at, updated_at)
            VALUES (?, 'Log Analyzer', 'GKE_REMOTE', 'Analyzes KUBERNETES cluster events', 'Standard worker', 'dev-user',
                    TIMESTAMP '2026-01-01 11:00:00', TIMESTAMP '2026-01-01 11:00:00')
        """, bot2);

        // bot3: keyword ONLY in instructions (must NOT be matched)
        jdbcTemplate.update("""
            INSERT INTO bot (id, name, target, description, instructions, owner_id, created_at, updated_at)
            VALUES (?, 'Metric Collector', 'GKE_REMOTE', 'Prometheus scraper', 'Specialized in kubernetes metrics collection', 'dev-user',
                    TIMESTAMP '2026-01-01 10:00:00', TIMESTAMP '2026-01-01 10:00:00')
        """, bot3);

        // foreignBot: keyword matches in name, but owned by another user (must NOT be visible)
        jdbcTemplate.update("""
            INSERT INTO bot (id, name, target, description, instructions, owner_id, created_at, updated_at)
            VALUES (?, 'Kubernetes Foreign Secret Agent', 'GKE_REMOTE', 'External cluster', 'Top secret', 'other-user',
                    TIMESTAMP '2026-01-01 13:00:00', TIMESTAMP '2026-01-01 13:00:00')
        """, foreignBot);

        String searchQuery = """
            query SearchBots($search: String, $target: BotTarget) {
              bots(search: $search, target: $target) {
                id
                name
              }
            }
        """;

        // 1. Search with leading/trailing spaces and mixed case: '  kUbeRNetes  '
        JsonNode resSearch = executeGraphQL(objectMapper.writeValueAsString(Map.of(
                "query", searchQuery,
                "variables", Map.of("search", "  kUbeRNetes  ")
        )));
        assertFalse(resSearch.has("errors"), "Search query must succeed: " + resSearch);
        List<String> matchedIds = resSearch.path("data").path("bots").findValuesAsText("id");
        assertTrue(matchedIds.contains(bot1), "Must match bot1 by name");
        assertTrue(matchedIds.contains(bot2), "Must match bot2 by description");
        assertFalse(matchedIds.contains(bot3), "Must NOT match bot3 since keyword is only in instructions");
        assertFalse(matchedIds.contains(foreignBot), "Foreign bot matching search keyword must NOT be visible");

        // 2. Empty string or whitespace-only search: acts as no filter, but foreign bot remains invisible
        JsonNode resEmpty = executeGraphQL(objectMapper.writeValueAsString(Map.of(
                "query", searchQuery,
                "variables", Map.of("search", "    ")
        )));
        assertFalse(resEmpty.has("errors"));
        List<String> emptyMatchedIds = resEmpty.path("data").path("bots").findValuesAsText("id");
        assertTrue(emptyMatchedIds.contains(bot1));
        assertTrue(emptyMatchedIds.contains(bot2));
        assertTrue(emptyMatchedIds.contains(bot3));
        assertTrue(emptyMatchedIds.contains("bot-general"));
        assertFalse(emptyMatchedIds.contains(foreignBot), "Foreign bot must NOT be visible in empty search");

        // 3. Combined search and target filter: foreign bot remains strictly invisible
        JsonNode resCombined = executeGraphQL(objectMapper.writeValueAsString(Map.of(
                "query", searchQuery,
                "variables", Map.of("search", "Kubernetes", "target", "GKE_REMOTE")
        )));
        assertFalse(resCombined.has("errors"));
        List<String> combinedIds = resCombined.path("data").path("bots").findValuesAsText("id");
        assertTrue(combinedIds.contains(bot1), "Must match own GKE remote bot");
        assertFalse(combinedIds.contains(foreignBot), "Foreign bot must remain invisible under search+target combination");
    }

    @Test
    @DisplayName("8. Search safe escaping: '%' and '_' treated as literal characters without wildcard expansion")
    void testSearch_SafeEscapingPercentAndUnderscore() throws Exception {
        String botLiteralPercent = "bot-gke-" + UUID.randomUUID();
        String botNonPercent = "bot-gke-" + UUID.randomUUID();
        String botLiteralUnderscore = "bot-gke-" + UUID.randomUUID();
        String botNonUnderscore = "bot-gke-" + UUID.randomUUID();

        jdbcTemplate.update("""
            INSERT INTO bot (id, name, target, description, instructions, owner_id, created_at, updated_at)
            VALUES (?, 'Data Pipeline 100% Complete', 'GKE_REMOTE', 'Batch run', '', 'dev-user', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
        """, botLiteralPercent);

        jdbcTemplate.update("""
            INSERT INTO bot (id, name, target, description, instructions, owner_id, created_at, updated_at)
            VALUES (?, 'Data Pipeline 1000 Complete', 'GKE_REMOTE', 'Batch run', '', 'dev-user', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
        """, botNonPercent);

        jdbcTemplate.update("""
            INSERT INTO bot (id, name, target, description, instructions, owner_id, created_at, updated_at)
            VALUES (?, 'agent_special_worker', 'GKE_REMOTE', 'Worker', '', 'dev-user', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
        """, botLiteralUnderscore);

        jdbcTemplate.update("""
            INSERT INTO bot (id, name, target, description, instructions, owner_id, created_at, updated_at)
            VALUES (?, 'agentXspecialXworker', 'GKE_REMOTE', 'Worker', '', 'dev-user', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
        """, botNonUnderscore);

        String searchQuery = """
            query SearchBots($search: String) {
              bots(search: $search) {
                id
                name
              }
            }
        """;

        // 1. Search for literal '100%'
        JsonNode resPercent = executeGraphQL(objectMapper.writeValueAsString(Map.of(
                "query", searchQuery,
                "variables", Map.of("search", "100%")
        )));
        assertFalse(resPercent.has("errors"));
        List<String> percentIds = resPercent.path("data").path("bots").findValuesAsText("id");
        assertTrue(percentIds.contains(botLiteralPercent), "Must match literal 100%");
        assertFalse(percentIds.contains(botNonPercent), "Must NOT wildcard-match 1000");

        // 2. Search for literal 'agent_special'
        JsonNode resUnderscore = executeGraphQL(objectMapper.writeValueAsString(Map.of(
                "query", searchQuery,
                "variables", Map.of("search", "agent_special")
        )));
        assertFalse(resUnderscore.has("errors"));
        List<String> underscoreIds = resUnderscore.path("data").path("bots").findValuesAsText("id");
        assertTrue(underscoreIds.contains(botLiteralUnderscore), "Must match literal underscore");
        assertFalse(underscoreIds.contains(botNonUnderscore), "Must NOT wildcard-match 'agentXspecial'");
    }

    @Test
    @DisplayName("9. Target Filtering: target enum filters LOCAL vs GKE_REMOTE properly")
    void testTargetFiltering() throws Exception {
        String gkeBotId = "bot-gke-" + UUID.randomUUID();
        jdbcTemplate.update("""
            INSERT INTO bot (id, name, target, description, instructions, owner_id, created_at, updated_at)
            VALUES (?, 'GKE Target Only', 'GKE_REMOTE', 'GKE Bot', '', 'dev-user', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
        """, gkeBotId);

        String query = """
            query FilterBots($target: BotTarget) {
              bots(target: $target) {
                id
                target
              }
            }
        """;

        // 1. Filter target: LOCAL
        JsonNode resLocal = executeGraphQL(objectMapper.writeValueAsString(Map.of(
                "query", query,
                "variables", Map.of("target", "LOCAL")
        )));
        assertFalse(resLocal.has("errors"));
        List<String> localTargets = resLocal.path("data").path("bots").findValuesAsText("target");
        assertTrue(localTargets.stream().allMatch(t -> t.equals("LOCAL")), "All results must be LOCAL");
        assertFalse(resLocal.path("data").path("bots").findValuesAsText("id").contains(gkeBotId));

        // 2. Filter target: GKE_REMOTE
        JsonNode resGke = executeGraphQL(objectMapper.writeValueAsString(Map.of(
                "query", query,
                "variables", Map.of("target", "GKE_REMOTE")
        )));
        assertFalse(resGke.has("errors"));
        List<String> gkeTargets = resGke.path("data").path("bots").findValuesAsText("target");
        assertTrue(gkeTargets.stream().allMatch(t -> t.equals("GKE_REMOTE")), "All results must be GKE_REMOTE");
        assertTrue(resGke.path("data").path("bots").findValuesAsText("id").contains(gkeBotId));
        assertFalse(resGke.path("data").path("bots").findValuesAsText("id").contains("bot-general"));
    }

    @Test
    @DisplayName("10. Pagination and Ordering: updated_at DESC, id DESC ordering with limit and offset")
    void testPaginationAndOrdering() throws Exception {
        // Create 5 bots with strictly staggered updated_at timestamps
        Timestamp baseCreated = Timestamp.from(Instant.parse("2026-01-01T00:00:00Z"));
        for (int i = 1; i <= 5; i++) {
            String id = String.format("bot-gke-staggered-%02d", i);
            Timestamp updated = Timestamp.from(Instant.parse(String.format("2026-05-%02dT12:00:00Z", i)));
            jdbcTemplate.update("""
                INSERT INTO bot (id, name, target, description, instructions, owner_id, created_at, updated_at)
                VALUES (?, ?, 'GKE_REMOTE', 'Staggered', '', 'dev-user', ?, ?)
                ON CONFLICT (id) DO UPDATE SET updated_at = EXCLUDED.updated_at
            """, id, "Staggered Bot " + i, baseCreated, updated);
        }

        String query = """
            query PaginateBots($search: String, $target: BotTarget, $limit: Int, $offset: Int) {
              bots(search: $search, target: $target, limit: $limit, offset: $offset) {
                id
                name
              }
            }
        """;

        // Page 1: limit 2, offset 0 -> expected bot-gke-staggered-05, 04
        JsonNode page1 = executeGraphQL(objectMapper.writeValueAsString(Map.of(
                "query", query,
                "variables", Map.of("search", "Staggered", "target", "GKE_REMOTE", "limit", 2, "offset", 0)
        )));
        assertFalse(page1.has("errors"));
        List<String> page1Ids = page1.path("data").path("bots").findValuesAsText("id");
        assertEquals(2, page1Ids.size());
        assertEquals("bot-gke-staggered-05", page1Ids.get(0));
        assertEquals("bot-gke-staggered-04", page1Ids.get(1));

        // Page 2: limit 2, offset 2 -> expected bot-gke-staggered-03, 02
        JsonNode page2 = executeGraphQL(objectMapper.writeValueAsString(Map.of(
                "query", query,
                "variables", Map.of("search", "Staggered", "target", "GKE_REMOTE", "limit", 2, "offset", 2)
        )));
        assertFalse(page2.has("errors"));
        List<String> page2Ids = page2.path("data").path("bots").findValuesAsText("id");
        assertEquals(2, page2Ids.size());
        assertEquals("bot-gke-staggered-03", page2Ids.get(0));
        assertEquals("bot-gke-staggered-02", page2Ids.get(1));

        // Page 3: limit 2, offset 4 -> expected bot-gke-staggered-01
        JsonNode page3 = executeGraphQL(objectMapper.writeValueAsString(Map.of(
                "query", query,
                "variables", Map.of("search", "Staggered", "target", "GKE_REMOTE", "limit", 2, "offset", 4)
        )));
        assertFalse(page3.has("errors"));
        List<String> page3Ids = page3.path("data").path("bots").findValuesAsText("id");
        assertEquals(1, page3Ids.size());
        assertEquals("bot-gke-staggered-01", page3Ids.get(0));

        // 4. Identical updated_at tie-breaker boundary: 4 bots with EXACT same updated_at resolved by id DESC
        Timestamp tieUpdated = Timestamp.from(Instant.parse("2026-07-01T12:00:00Z"));
        for (int i = 1; i <= 4; i++) {
            String id = String.format("bot-gke-tie-%02d", i);
            jdbcTemplate.update("""
                INSERT INTO bot (id, name, target, description, instructions, owner_id, created_at, updated_at)
                VALUES (?, ?, 'GKE_REMOTE', 'TieBreaker', '', 'dev-user', ?, ?)
                ON CONFLICT (id) DO UPDATE SET updated_at = EXCLUDED.updated_at
            """, id, "Tie Bot " + i, baseCreated, tieUpdated);
        }

        // Tie Page 1: limit 2, offset 0 -> expected bot-gke-tie-04, bot-gke-tie-03 (id DESC)
        JsonNode tiePage1 = executeGraphQL(objectMapper.writeValueAsString(Map.of(
                "query", query,
                "variables", Map.of("search", "TieBreaker", "target", "GKE_REMOTE", "limit", 2, "offset", 0)
        )));
        assertFalse(tiePage1.has("errors"));
        List<String> tiePage1Ids = tiePage1.path("data").path("bots").findValuesAsText("id");
        assertEquals(List.of("bot-gke-tie-04", "bot-gke-tie-03"), tiePage1Ids,
                "Identical updated_at must be deterministically ordered by id DESC on first page");

        // Tie Page 2: limit 2, offset 2 -> expected bot-gke-tie-02, bot-gke-tie-01 (id DESC)
        JsonNode tiePage2 = executeGraphQL(objectMapper.writeValueAsString(Map.of(
                "query", query,
                "variables", Map.of("search", "TieBreaker", "target", "GKE_REMOTE", "limit", 2, "offset", 2)
        )));
        assertFalse(tiePage2.has("errors"));
        List<String> tiePage2Ids = tiePage2.path("data").path("bots").findValuesAsText("id");
        assertEquals(List.of("bot-gke-tie-02", "bot-gke-tie-01"), tiePage2Ids,
                "Identical updated_at must be deterministically ordered by id DESC on second page without drift");
    }
}
