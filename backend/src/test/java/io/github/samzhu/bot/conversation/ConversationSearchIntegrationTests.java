package io.github.samzhu.bot.conversation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.samzhu.bot.BackendIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.*;

@BackendIntegrationTest
class ConversationSearchIntegrationTests {
    private static final String CALLER = "dev-user";
    private static final Instant TIME = Instant.parse("2026-01-01T00:00:00Z");

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();
    private final String prefix = "search-" + UUID.randomUUID() + "-";
    private ConversationTestData data;
    private String workspaceId;

    @BeforeEach
    void setup() {
        data = new ConversationTestData(jdbc);
        workspaceId = data.workspace(CALLER);
    }

    @AfterEach
    void cleanUp() {
        try {
            data.close();
            jdbc.update("DELETE FROM bot WHERE id = ? AND owner_id = ?", prefix + "bot", CALLER);
        } finally {
            http.close();
        }
    }

    private void insert(String suffix, String title, boolean pinned) {
        data.insertConversation(prefix + suffix, "bot-general", title, CALLER, pinned, TIME, workspaceId);
    }

    private JsonNode query(String search, Boolean pinned, Integer limit, Integer offset) throws Exception {
        Map<String, Object> variables = new HashMap<>();
        variables.put("workspaceId", workspaceId);
        if (search != null) variables.put("search", search);
        if (pinned != null) variables.put("pinned", pinned);
        if (limit != null) variables.put("limit", limit);
        if (offset != null) variables.put("offset", offset);
        String body = json.writeValueAsString(Map.of("query", """
                query Search($workspaceId: ID!, $search: String, $pinned: Boolean, $limit: Int, $offset: Int) {
                  conversations(workspaceId: $workspaceId, search: $search, pinned: $pinned, limit: $limit, offset: $offset) {
                    id title pinned
                  }
                }
                """, "variables", variables));
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/graphql"))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        return json.readTree(response.body());
    }

    private List<String> ids(JsonNode root) {
        assertFalse(root.has("errors"), root.toString());
        JsonNode conversations = root.path("data").path("conversations");
        assertTrue(conversations.isArray(), root.toString());
        return StreamSupport.stream(conversations.spliterator(), false).map(n -> n.path("id").asText()).toList();
    }

    @Test void titleSearchRespectsOwnerBotPinnedOrderingAndPagination() throws Exception {
        jdbc.update("INSERT INTO bot(id,name,system_prompt,target,owner_id,created_at) VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                prefix + "bot", "Other bot", "secret-needle", "GKE_REMOTE", CALLER);
        insert("a", "Target Alpha", false);
        data.insertConversation(prefix + "b", prefix + "bot", "target Beta", CALLER, true, TIME, workspaceId);
        insert("d", "Unrelated", false);

        String foreign = "foreign-" + UUID.randomUUID();
        data.addMember(workspaceId, foreign);
        // Both distractors would precede the expected first item if they reached the server's pagination.
        data.insertConversation(prefix + "foreign", "bot-general", "Target Foreign", foreign, true,
                TIME.plusSeconds(1), workspaceId);
        data.insertConversation(prefix + "other-workspace", "bot-general", "Target Other Workspace", CALLER,
                true, TIME.plusSeconds(1), data.workspace(CALLER));
        assertEquals(List.of(prefix + "b", prefix + "a"), ids(query("  TaRgEt  ", null, 100, 0)));
        assertEquals(List.of(prefix + "b"), ids(query("target", true, 20, 0)));
        assertEquals(List.of(prefix + "a"), ids(query("target", false, 20, 0)));
        assertEquals(List.of(prefix + "a"), ids(query("target", null, 1, 1)));
        assertTrue(ids(query("secret-needle", null, 100, 0)).isEmpty());
    }

    @Test void literalSpecialCharactersAndBoundaryValidation() throws Exception {
        insert("literal", "literal 100% under_score back\\slash", false);
        insert("wild", "literal 100X underZscore backZslash", false);
        for (String value : List.of("100%", "under_score", "back\\slash")) {
            assertEquals(List.of(prefix + "literal"), ids(query(value, null, 100, 0)));
        }
        insert("unicode", "臺灣助手", false);
        assertEquals(List.of(prefix + "unicode"), ids(query("臺灣", null, 100, 0)));
        assertTrue(ids(query("' OR 1=1 --", null, 100, 0)).isEmpty());
        insert("long", "a".repeat(100), false);
        assertEquals(List.of(prefix + "long"), ids(query(" " + "a".repeat(100) + " ", null, 100, 0)));
        JsonNode invalid = query("a".repeat(101), null, 20, 0);
        assertEquals("BAD_REQUEST", invalid.path("errors").path(0).path("extensions").path("classification").asText());
        assertEquals(ids(query(null, null, 100, 0)), ids(query("   ", null, 100, 0)));
        assertEquals(ids(query(null, null, 100, 0)), ids(query("　", null, 100, 0)));
    }
}
