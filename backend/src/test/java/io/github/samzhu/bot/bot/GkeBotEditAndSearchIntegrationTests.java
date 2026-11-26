package io.github.samzhu.bot.bot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.samzhu.bot.BackendIntegrationTest;
import io.github.samzhu.bot.usage.pricing.UsageCaseData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Public Bot management, visibility, and search with the default OFF configuration. */
@BackendIntegrationTest
public class GkeBotEditAndSearchIntegrationTests {
    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final BotCaseRows bots = new BotCaseRows();

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @AfterEach
    void cleanUp() {
        try {
            bots.deleteFrom(jdbcTemplate);
        } finally {
            httpClient.close();
        }
    }

    private JsonNode executeGraphQL(String payload) throws Exception {
        return executeGraphQL(httpClient, objectMapper, port, payload);
    }

    static JsonNode executeGraphQL(HttpClient client, ObjectMapper mapper, int port, String payload) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/graphql"))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode());
        return mapper.readTree(resp.body());
    }

    @Test
    @DisplayName("1. updateBot: Owner successfully updates GKE bot with trimmed name, raw description/instructions, and refreshed updated_at")
    void testUpdateBot_Success() throws Exception {
        String botId = bots.ownedId("remote");
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
        String localBotId = bots.ownedId("local");
        jdbcTemplate.update("INSERT INTO bot (id, name, target) VALUES (?, 'Read-only Local', 'LOCAL')",
                localBotId);
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
                        "id", localBotId,
                        "name", "Attempt to Rename Local Bot"
                ))
        ));

        JsonNode res = executeGraphQL(payload);
        assertTrue(res.has("errors"), "Updating LOCAL bot must return GraphQL errors: " + res);
        assertTrue(res.path("errors").get(0).path("message").asText().contains("LOCAL bot is read-only"));

        String nameInDb = jdbcTemplate.queryForObject("SELECT name FROM bot WHERE id = ?", String.class, localBotId);
        assertEquals("Read-only Local", nameInDb, "LOCAL bot name must not have been modified");
    }

    @Test
    @DisplayName("3. updateBot: Non-existent bot and foreign-owned bot fail with 'Bot not found' (zero leakage) and 0 DB writes")
    void testUpdateBot_UnknownAndForeignBotZeroLeakage() throws Exception {
        String foreignBotId = bots.ownedId("foreign");
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
                        "id", bots.id("missing"),
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
        String botId = bots.ownedId("remote");
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
        assertTrue(bots.findValuesAsText("id").contains("bot-general"), "Must contain the seed bot-general");
    }

    @Test
    @DisplayName("7. Search: case-insensitive name/description match, excludes instructions, trims whitespace, empty search is no-op")
    void testSearch_CaseInsensitive_ExcludesInstructions_TrimsWhitespace() throws Exception {
        String bot1 = bots.ownedId("name");
        String bot2 = bots.ownedId("description");
        String bot3 = bots.ownedId("instructions");
        String foreignBot = bots.ownedId("foreign");
        String keyword = "Kubernetes " + bots.id("search");

        // bot1: keyword in name (different casing)
        jdbcTemplate.update("""
            INSERT INTO bot (id, name, target, description, instructions, owner_id, created_at, updated_at)
            VALUES (?, ?, 'GKE_REMOTE', 'Cluster management', 'Standard worker', 'dev-user',
                    TIMESTAMP '2026-01-01 12:00:00', TIMESTAMP '2026-01-01 12:00:00')
        """, bot1, keyword + " Orchestrator Agent");

        // bot2: keyword in description (different casing)
        jdbcTemplate.update("""
            INSERT INTO bot (id, name, target, description, instructions, owner_id, created_at, updated_at)
            VALUES (?, 'Log Analyzer', 'GKE_REMOTE', ?, 'Standard worker', 'dev-user',
                    TIMESTAMP '2026-01-01 11:00:00', TIMESTAMP '2026-01-01 11:00:00')
        """, bot2, "Analyzes " + keyword.toUpperCase(Locale.ROOT) + " cluster events");

        // bot3: keyword ONLY in instructions (must NOT be matched)
        jdbcTemplate.update("""
            INSERT INTO bot (id, name, target, description, instructions, owner_id, created_at, updated_at)
            VALUES (?, 'Metric Collector', 'GKE_REMOTE', 'Prometheus scraper', ?, 'dev-user',
                    TIMESTAMP '2026-01-01 10:00:00', TIMESTAMP '2026-01-01 10:00:00')
        """, bot3, "Specialized in " + keyword + " metrics collection");

        // foreignBot: keyword matches in name, but owned by another user (must NOT be visible)
        jdbcTemplate.update("""
            INSERT INTO bot (id, name, target, description, instructions, owner_id, created_at, updated_at)
            VALUES (?, ?, 'GKE_REMOTE', 'External cluster', 'Top secret', 'other-user',
                    TIMESTAMP '2026-01-01 13:00:00', TIMESTAMP '2026-01-01 13:00:00')
        """, foreignBot, keyword + " Foreign Secret Agent");

        String searchQuery = """
            query SearchBots($search: String, $target: BotTarget) {
              bots(search: $search, target: $target, limit: 100) {
                id
                name
              }
            }
        """;

        // Leading/trailing spaces and different case still match name or description.
        JsonNode resSearch = executeGraphQL(objectMapper.writeValueAsString(Map.of(
                "query", searchQuery,
                "variables", Map.of("search", "  " + keyword.toLowerCase(Locale.ROOT) + "  ")
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
                "variables", Map.of("search", keyword, "target", "GKE_REMOTE")
        )));
        assertFalse(resCombined.has("errors"));
        List<String> combinedIds = resCombined.path("data").path("bots").findValuesAsText("id");
        assertTrue(combinedIds.contains(bot1), "Must match own GKE remote bot");
        assertFalse(combinedIds.contains(foreignBot), "Foreign bot must remain invisible under search+target combination");
    }

    @Test
    @DisplayName("8. Search safe escaping: '%' and '_' treated as literal characters without wildcard expansion")
    void testSearch_SafeEscapingPercentAndUnderscore() throws Exception {
        String botLiteralPercent = bots.ownedId("literal-percent");
        String botNonPercent = bots.ownedId("plain-percent");
        String botLiteralUnderscore = bots.ownedId("literal-underscore");
        String botNonUnderscore = bots.ownedId("plain-underscore");

        String marker = bots.id("literal");

        jdbcTemplate.update("""
            INSERT INTO bot (id, name, target, description, instructions, owner_id, created_at, updated_at)
            VALUES (?, ?, 'GKE_REMOTE', 'Batch run', '', 'dev-user', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
        """, botLiteralPercent, marker + " Data Pipeline 100% Complete");

        jdbcTemplate.update("""
            INSERT INTO bot (id, name, target, description, instructions, owner_id, created_at, updated_at)
            VALUES (?, ?, 'GKE_REMOTE', 'Batch run', '', 'dev-user', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
        """, botNonPercent, marker + " Data Pipeline 1000 Complete");

        jdbcTemplate.update("""
            INSERT INTO bot (id, name, target, description, instructions, owner_id, created_at, updated_at)
            VALUES (?, ?, 'GKE_REMOTE', 'Worker', '', 'dev-user', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
        """, botLiteralUnderscore, marker + " agent_special_worker");

        jdbcTemplate.update("""
            INSERT INTO bot (id, name, target, description, instructions, owner_id, created_at, updated_at)
            VALUES (?, ?, 'GKE_REMOTE', 'Worker', '', 'dev-user', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
        """, botNonUnderscore, marker + " agentXspecialXworker");

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
                "variables", Map.of("search", marker + " Data Pipeline 100%")
        )));
        assertFalse(resPercent.has("errors"));
        List<String> percentIds = resPercent.path("data").path("bots").findValuesAsText("id");
        assertTrue(percentIds.contains(botLiteralPercent), "Must match literal 100%");
        assertFalse(percentIds.contains(botNonPercent), "Must NOT wildcard-match 1000");

        // 2. Search for literal 'agent_special'
        JsonNode resUnderscore = executeGraphQL(objectMapper.writeValueAsString(Map.of(
                "query", searchQuery,
                "variables", Map.of("search", marker + " agent_special")
        )));
        assertFalse(resUnderscore.has("errors"));
        List<String> underscoreIds = resUnderscore.path("data").path("bots").findValuesAsText("id");
        assertTrue(underscoreIds.contains(botLiteralUnderscore), "Must match literal underscore");
        assertFalse(underscoreIds.contains(botNonUnderscore), "Must NOT wildcard-match 'agentXspecial'");
    }

    @Test
    @DisplayName("9. Target Filtering: target enum filters LOCAL vs GKE_REMOTE properly")
    void testTargetFiltering() throws Exception {
        String gkeBotId = bots.ownedId("remote");
        jdbcTemplate.update("""
            INSERT INTO bot (id, name, target, description, instructions, owner_id, created_at, updated_at)
            VALUES (?, 'GKE Target Only', 'GKE_REMOTE', 'GKE Bot', '', 'dev-user', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
        """, gkeBotId);

        String query = """
            query FilterBots($target: BotTarget) {
              bots(target: $target, limit: 100) {
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
        assertFalse(localTargets.isEmpty());
        assertTrue(localTargets.stream().allMatch(t -> t.equals("LOCAL")), "All results must be LOCAL");
        assertFalse(resLocal.path("data").path("bots").findValuesAsText("id").contains(gkeBotId));

        // 2. Filter target: GKE_REMOTE
        JsonNode resGke = executeGraphQL(objectMapper.writeValueAsString(Map.of(
                "query", query,
                "variables", Map.of("target", "GKE_REMOTE")
        )));
        assertFalse(resGke.has("errors"));
        List<String> gkeTargets = resGke.path("data").path("bots").findValuesAsText("target");
        assertFalse(gkeTargets.isEmpty());
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
            String id = bots.ownedId("staggered-" + i);
            Timestamp updated = Timestamp.from(Instant.parse(String.format("2026-05-%02dT12:00:00Z", i)));
            jdbcTemplate.update("""
                INSERT INTO bot (id, name, target, description, instructions, owner_id, created_at, updated_at)
                VALUES (?, ?, 'GKE_REMOTE', 'Staggered', '', 'dev-user', ?, ?)
            """, id, bots.id("staggered") + " " + i, baseCreated, updated);
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
                "variables", Map.of("search", bots.id("staggered"), "target", "GKE_REMOTE", "limit", 2, "offset", 0)
        )));
        assertFalse(page1.has("errors"));
        List<String> page1Ids = page1.path("data").path("bots").findValuesAsText("id");
        assertEquals(2, page1Ids.size());
        assertEquals(bots.id("staggered-5"), page1Ids.get(0));
        assertEquals(bots.id("staggered-4"), page1Ids.get(1));

        // Page 2: limit 2, offset 2 -> expected bot-gke-staggered-03, 02
        JsonNode page2 = executeGraphQL(objectMapper.writeValueAsString(Map.of(
                "query", query,
                "variables", Map.of("search", bots.id("staggered"), "target", "GKE_REMOTE", "limit", 2, "offset", 2)
        )));
        assertFalse(page2.has("errors"));
        List<String> page2Ids = page2.path("data").path("bots").findValuesAsText("id");
        assertEquals(2, page2Ids.size());
        assertEquals(bots.id("staggered-3"), page2Ids.get(0));
        assertEquals(bots.id("staggered-2"), page2Ids.get(1));

        // Page 3: limit 2, offset 4 -> expected bot-gke-staggered-01
        JsonNode page3 = executeGraphQL(objectMapper.writeValueAsString(Map.of(
                "query", query,
                "variables", Map.of("search", bots.id("staggered"), "target", "GKE_REMOTE", "limit", 2, "offset", 4)
        )));
        assertFalse(page3.has("errors"));
        List<String> page3Ids = page3.path("data").path("bots").findValuesAsText("id");
        assertEquals(1, page3Ids.size());
        assertEquals(bots.id("staggered-1"), page3Ids.get(0));

        // 4. Identical updated_at tie-breaker boundary: 4 bots with EXACT same updated_at resolved by id DESC
        Timestamp tieUpdated = Timestamp.from(Instant.parse("2026-07-01T12:00:00Z"));
        for (int i = 1; i <= 4; i++) {
            String id = bots.ownedId("tie-" + i);
            jdbcTemplate.update("""
                INSERT INTO bot (id, name, target, description, instructions, owner_id, created_at, updated_at)
                VALUES (?, ?, 'GKE_REMOTE', 'TieBreaker', '', 'dev-user', ?, ?)
            """, id, bots.id("tie") + " " + i, baseCreated, tieUpdated);
        }

        // Tie Page 1: limit 2, offset 0 -> expected bot-gke-tie-04, bot-gke-tie-03 (id DESC)
        JsonNode tiePage1 = executeGraphQL(objectMapper.writeValueAsString(Map.of(
                "query", query,
                "variables", Map.of("search", bots.id("tie"), "target", "GKE_REMOTE", "limit", 2, "offset", 0)
        )));
        assertFalse(tiePage1.has("errors"));
        List<String> tiePage1Ids = tiePage1.path("data").path("bots").findValuesAsText("id");
        assertEquals(List.of(bots.id("tie-4"), bots.id("tie-3")), tiePage1Ids,
                "Identical updated_at must be deterministically ordered by id DESC on first page");

        // Tie Page 2: limit 2, offset 2 -> expected bot-gke-tie-02, bot-gke-tie-01 (id DESC)
        JsonNode tiePage2 = executeGraphQL(objectMapper.writeValueAsString(Map.of(
                "query", query,
                "variables", Map.of("search", bots.id("tie"), "target", "GKE_REMOTE", "limit", 2, "offset", 2)
        )));
        assertFalse(tiePage2.has("errors"));
        List<String> tiePage2Ids = tiePage2.path("data").path("bots").findValuesAsText("id");
        assertEquals(List.of(bots.id("tie-2"), bots.id("tie-1")), tiePage2Ids,
                "Identical updated_at must be deterministically ordered by id DESC on second page without drift");
    }

    @Test
    @DisplayName("11. Managed scope (#20): empty query fixed to GKE_REMOTE never mixes newer LOCAL or other-owner bots across pages")
    void testManagedScopeEmptyQueryAcrossPages() throws Exception {
        Timestamp created = Timestamp.from(Instant.parse("2026-01-01T00:00:00Z"));
        // Own GKE bots are OLDER than every foreign/LOCAL row, so a "fetch a page then drop rows" implementation
        // would return short or empty pages instead of the owner's own independent bots.
        for (int i = 1; i <= 3; i++) {
            jdbcTemplate.update("""
                INSERT INTO bot (id, name, target, description, instructions, owner_id, created_at, updated_at)
                VALUES (?, ?, 'GKE_REMOTE', 'own', '', 'dev-user', ?, ?)
            """, bots.ownedId("scope-own-" + i), "Scope Own " + i, created,
                    Timestamp.from(Instant.parse("2026-01-0" + i + "T00:00:00Z")));
        }
        for (int i = 1; i <= 4; i++) {
            jdbcTemplate.update("""
                INSERT INTO bot (id, name, target, description, instructions, owner_id, created_at, updated_at)
                VALUES (?, ?, 'LOCAL', 'local', '', 'dev-user', ?, ?)
            """, bots.ownedId("scope-local-" + i), "Scope Local " + i, created,
                    Timestamp.from(Instant.parse("2030-01-0" + i + "T00:00:00Z")));
        }
        for (int i = 1; i <= 3; i++) {
            jdbcTemplate.update("""
                INSERT INTO bot (id, name, target, description, instructions, owner_id, created_at, updated_at)
                VALUES (?, ?, 'GKE_REMOTE', 'foreign', '', 'someone-else', ?, ?)
            """, bots.ownedId("scope-foreign-" + i), "Scope Foreign " + i, created,
                    Timestamp.from(Instant.parse("2031-01-0" + i + "T00:00:00Z")));
        }

        String query = """
            query ManagedBots($limit: Int, $offset: Int) {
              defaultLocalBot { id target }
              bots(target: GKE_REMOTE, limit: $limit, offset: $offset) { id target }
            }
        """;

        List<String> collected = new ArrayList<>();
        for (int offset = 0; offset < 200; offset += 2) {
            JsonNode res = executeGraphQL(objectMapper.writeValueAsString(Map.of(
                    "query", query,
                    "variables", Map.of("limit", 2, "offset", offset))));
            assertFalse(res.has("errors"));
            // defaultLocalBot stays available and untouched alongside the managed query
            assertEquals("LOCAL", res.path("data").path("defaultLocalBot").path("target").asText());
            JsonNode page = res.path("data").path("bots");
            for (JsonNode b : page) {
                assertEquals("GKE_REMOTE", b.path("target").asText());
                collected.add(b.path("id").asText());
            }
            if (page.size() < 2) {
                break;
            }
        }

        assertEquals(collected.size(), collected.stream().distinct().count(), "No duplicates across pages");
        for (int i = 1; i <= 3; i++) {
            assertTrue(collected.contains(bots.id("scope-own-" + i)));
        }
        assertTrue(collected.stream().noneMatch(id -> id.startsWith(bots.id("scope-local-"))));
        assertTrue(collected.stream().noneMatch(id -> id.startsWith(bots.id("scope-foreign-"))));
        assertTrue(collected.stream().noneMatch(id -> id.equals("bot-general")));

        // Existing LOCAL data is preserved (no delete/modify of defaultLocalBot or LOCAL rows)
        Integer localRows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM bot WHERE id LIKE ?", Integer.class, bots.id("scope-local-") + "%");
        assertEquals(4, localRows);
        assertEquals("LOCAL", jdbcTemplate.queryForObject(
                "SELECT target FROM bot WHERE id = 'bot-general'", String.class));
    }
}

/** Successful creation of a conversation with a disconnected GKE Bot requires SIMULATED execution mode. */
@BackendIntegrationTest
@TestPropertySource(properties = "bot.gke.execution-mode=SIMULATED")
class GkeBotHistoryBindingIntegrationTests {
    @LocalServerPort private int port;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate transactions;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final BotCaseRows bots = new BotCaseRows();
    private UsageCaseData data;

    @AfterEach
    void cleanUp() throws InterruptedException {
        try {
            if (data != null) {
                data.cleanUp();
            }
            bots.deleteFrom(jdbcTemplate);
        } finally {
            httpClient.close();
        }
    }

    private JsonNode executeGraphQL(String payload) throws Exception {
        return GkeBotEditAndSearchIntegrationTests.executeGraphQL(httpClient, objectMapper, port, payload);
    }

    @Test
    @DisplayName("5. History Binding: conversation.bot_id remains permanently associated after bot update")
    void testHistoryBinding_ConversationBotIdPreserved() throws Exception {
        data = new UsageCaseData(jdbcTemplate, transactions);
        String workspaceId = "gke-history-" + UUID.randomUUID();
        data.createWorkspace(workspaceId, "dev-user");
        String botId = bots.ownedId("history");
        jdbcTemplate.update("""
            INSERT INTO bot (id, name, target, description, instructions, owner_id, created_at, updated_at)
            VALUES (?, 'Target Bot', 'GKE_REMOTE', 'Desc', 'Instr', 'dev-user', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
        """, botId);

        // Create a conversation bound to this bot
        String createConvMutation = """
            mutation CreateConversation($botId: ID!, $title: String, $workspaceId: ID!) {
              createConversation(botId: $botId, title: $title, workspaceId: $workspaceId) {
                id
                botId
                title
              }
            }
        """;
        JsonNode convRes = executeGraphQL(objectMapper.writeValueAsString(Map.of(
                "query", createConvMutation,
                "variables", Map.of("botId", botId, "title", "Test Conversation", "workspaceId", workspaceId)
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
        JsonNode update = executeGraphQL(objectMapper.writeValueAsString(Map.of(
                "query", updateMutation,
                "variables", Map.of("input", Map.of("id", botId, "name", "Renamed Target Bot"))
        )));

        assertFalse(update.has("errors"), update.toString());
        assertEquals("Renamed Target Bot", update.path("data").path("updateBot").path("name").asText());

        // Verify conversation still points to the same botId
        Map<String, Object> convInDb = jdbcTemplate.queryForMap("SELECT * FROM conversation WHERE id = ?", convId);
        assertEquals(botId, convInDb.get("bot_id"), "conversation.bot_id must remain strictly unchanged");
    }
}
