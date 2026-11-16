package io.github.samzhu.bot;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "bot.budget.monthly.default-cap-usd=23.125")
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class WorkspaceMonthlyBudgetOverrideGraphQLTests {
    @LocalServerPort int port;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();

    @Test void freshWorkspaceUsesOverriddenDefaultWithoutEnablingBudget() throws Exception {
        var personal = graph("{ personalWorkspace { id } }");
        assertFalse(personal.has("errors"), personal.toString());
        String workspace = personal.path("data").path("personalWorkspace").path("id").asText();
        assertFalse(workspace.isBlank());
        var result = graph("{ workspaceMonthlyBudget(workspaceId:\"" + workspace
                + "\",simulation:false) { capUsd enabled configVersion } }");
        assertFalse(result.has("errors"), result.toString());
        var budget = result.path("data").path("workspaceMonthlyBudget");
        assertEquals(0, new BigDecimal("23.125").compareTo(new BigDecimal(budget.path("capUsd").asText())));
        assertFalse(budget.path("enabled").asBoolean());
        assertEquals("0", budget.path("configVersion").asText());
    }

    private com.fasterxml.jackson.databind.JsonNode graph(String query) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/graphql"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("query", query))))
                .build();
        return json.readTree(http.send(request, HttpResponse.BodyHandlers.ofString()).body());
    }
}
