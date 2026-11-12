package io.github.samzhu.bot.conversation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.samzhu.bot.BotApplication;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = {BotApplication.class, ConversationSearchIntegrationTests.PostgresConfig.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ConversationSearchIntegrationTests {
    @TestConfiguration(proxyBeanMethods = false)
    static class PostgresConfig {
        @Bean @ServiceConnection
        PostgreSQLContainer postgresContainer() {
            return new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"));
        }
    }

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    final ObjectMapper json = new ObjectMapper();
    final HttpClient http = HttpClient.newHttpClient();
    final String prefix = "search-" + UUID.randomUUID() + "-";
    final Instant time = Instant.parse("2026-01-01T00:00:00Z");

    @BeforeEach
    void setup() {
        jdbc.update("INSERT INTO bot(id,name,system_prompt,created_at) VALUES ('bot-general','General','private-needle',CURRENT_TIMESTAMP) ON CONFLICT (id) DO NOTHING");
        jdbc.update("INSERT INTO bot(id,name,system_prompt,target,owner_id,created_at) VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                prefix + "bot", "Other bot", "secret-needle", "GKE_REMOTE", "dev-user");
    }

    void insert(String suffix, String title, String owner, boolean pinned, String bot) {
        jdbc.update("INSERT INTO conversation(id,bot_id,title,user_id,pinned,created_at,updated_at) VALUES (?,?,?,?,?,?,?)",
                prefix + suffix, bot, title, owner, pinned, Timestamp.from(time), Timestamp.from(time));
    }

    JsonNode query(String search, Boolean pinned, Integer limit, Integer offset) throws Exception {
        Map<String, Object> variables = new HashMap<>();
        if (search != null) variables.put("search", search);
        if (pinned != null) variables.put("pinned", pinned);
        if (limit != null) variables.put("limit", limit);
        if (offset != null) variables.put("offset", offset);
        String body = json.writeValueAsString(Map.of("query", "query($search:String,$pinned:Boolean,$limit:Int,$offset:Int){ conversations(search:$search,pinned:$pinned,limit:$limit,offset:$offset){id title pinned} }", "variables", variables));
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/graphql"))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return json.readTree(http.send(request, HttpResponse.BodyHandlers.ofString()).body());
    }

    java.util.List<String> ids(JsonNode root) {
        assertFalse(root.has("errors"), root.toString());
        return StreamSupport.stream(root.path("data").path("conversations").spliterator(), false)
                .map(n -> n.path("id").asText()).filter(id -> id.startsWith(prefix)).toList();
    }

    @Test void titleSearchRespectsOwnerBotPinnedOrderingAndPagination() throws Exception {
        insert("a", "Target Alpha", "dev-user", false, "bot-general");
        insert("b", "target Beta", "dev-user", true, prefix + "bot");
        insert("c", "Target Foreign", "someone-else", true, "bot-general");
        insert("d", "Unrelated", "dev-user", false, "bot-general");
        assertEquals(java.util.List.of(prefix + "b", prefix + "a"), ids(query("  TaRgEt  ", null, 100, 0)));
        assertEquals(java.util.List.of(prefix + "b"), ids(query("target", true, 20, 0)));
        assertEquals(java.util.List.of(prefix + "a"), ids(query("target", false, 20, 0)));
        assertEquals(java.util.List.of(prefix + "a"), ids(query("target", null, 1, 1)));
        assertTrue(ids(query("private-needle", null, 100, 0)).isEmpty());
    }

    @Test void literalSpecialCharactersAndBoundaryValidation() throws Exception {
        insert("literal", "literal 100% under_score back\\slash", "dev-user", false, "bot-general");
        insert("wild", "literal 100X underZscore backZslash", "dev-user", false, "bot-general");
        for (String value : java.util.List.of("100%", "under_score", "back\\slash")) {
            assertEquals(java.util.List.of(prefix + "literal"), ids(query(value, null, 100, 0)));
        }
        insert("unicode", "臺灣助手", "dev-user", false, "bot-general");
        assertEquals(java.util.List.of(prefix + "unicode"), ids(query("臺灣", null, 100, 0)));
        assertTrue(ids(query("' OR 1=1 --", null, 100, 0)).isEmpty());
        insert("long", "a".repeat(100), "dev-user", false, "bot-general");
        assertEquals(java.util.List.of(prefix + "long"), ids(query(" " + "a".repeat(100) + " ", null, 100, 0)));
        JsonNode invalid = query("a".repeat(101), null, 20, 0);
        assertEquals("BAD_REQUEST", invalid.path("errors").path(0).path("extensions").path("classification").asText());
        assertEquals(ids(query(null, null, 100, 0)), ids(query("   ", null, 100, 0)));
        assertEquals(ids(query(null, null, 100, 0)), ids(query("　", null, 100, 0)));
    }
}
