package io.github.samzhu.bot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.*;

@BackendIntegrationTest
class SharedWorkspaceMonthlyBudgetGraphQLTests {
    private static final String CONFIGURE = """
            mutation Configure($input: ConfigureWorkspaceMonthlyBudgetInput!) {
              configureWorkspaceMonthlyBudget(input: $input) { outcome budget { capUsd configVersion } }
            }
            """;

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate transactions;

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();
    private final String workspace = "monthly-graphql-" + UUID.randomUUID();

    @BeforeEach
    void createWorkspace() {
        transactions.executeWithoutResult(status -> {
            jdbc.update("insert into workspace(id,name) values (?,'Monthly budget fixture')", workspace);
            jdbc.update("""
                    insert into workspace_member(workspace_id,user_id,role,active)
                    values (?,'dev-user','MANAGER',true)
                    """, workspace);
        });
    }

    @AfterEach
    void cleanUp() {
        try {
            transactions.executeWithoutResult(status -> {
                jdbc.update("delete from workspace_monthly_budget_command where workspace_id=?", workspace);
                jdbc.update("delete from workspace_monthly_budget_period where workspace_id=?", workspace);
                jdbc.update("delete from workspace_monthly_budget where workspace_id=?", workspace);
                jdbc.update("delete from workspace_member where workspace_id=?", workspace);
                jdbc.update("delete from workspace where id=?", workspace);
            });
        } finally {
            http.close();
        }
    }

    @Test
    void emptyPeriodShowsDisabledBudgetAndExactZero() throws Exception {
        var result = graph("""
                query Budget($workspace: ID!) {
                  workspaceMonthlyBudget(workspaceId: $workspace, simulation: false) {
                    workspaceId simulation period periodStart periodEnd enabled capUsd knownSubtotalUsd
                    terminalUnknownCount state reason configVersion generation
                  }
                }
                """, Map.of("workspace", workspace));
        assertFalse(result.has("errors"), result.toString());
        var budget = result.path("data").path("workspaceMonthlyBudget");
        assertEquals(workspace, budget.path("workspaceId").asText());
        assertFalse(budget.path("enabled").asBoolean());
        assertMoney("10", budget.path("capUsd"));
        assertMoney("0", budget.path("knownSubtotalUsd"));
        assertEquals("0", budget.path("terminalUnknownCount").asText());
        assertEquals("DISABLED", budget.path("state").asText());
        assertEquals("DISABLED", budget.path("reason").asText());
        assertEquals("0", budget.path("configVersion").asText());
    }

    @Test
    void durableReplayUsesCurrentBudgetAndConflictingPayloadDoesNotMutate() throws Exception {
        assertEquals("APPLIED", configured(configure("replay", "0", "5.0", false)).path("outcome").asText());
        configured(configure("later", "1", "8", false));

        var replay = configured(configure("replay", "0", "5.00", false));
        assertEquals("REPLAYED", replay.path("outcome").asText());
        assertMoney("8", replay.path("budget").path("capUsd"));
        assertEquals("2", replay.path("budget").path("configVersion").asText());
        assertCode("BUDGET_CONFIGURATION_CONFLICT", configure("replay", "0", "6", false));
        assertStoredCap("REAL", "8");
        assertConfigVersion(2);
        assertEquals(0, jdbc.queryForObject(
                "select count(*) from workspace_admission_effect_outbox where target_workspace_id=?",
                Integer.class, workspace));
    }

    @Test
    void sameKeyIsIsolatedBetweenRealAndSimulation() throws Exception {
        String key = "partition";
        assertEquals("APPLIED", configured(configure(key, "0", "5", false)).path("outcome").asText());
        assertEquals("APPLIED", configured(configure(key, "0", "9", true)).path("outcome").asText());
        assertEquals(2, jdbc.queryForObject(
                "select count(*) from workspace_monthly_budget_command where workspace_id=? and idempotency_key=?",
                Integer.class, workspace, key));
        assertStoredCap("REAL", "5");
        assertStoredCap("SIMULATION", "9");
    }

    @Test
    void invalidInputAndCasConflictUseExplicitCodesWithoutChangingConfiguration() throws Exception {
        assertCode("INVALID_BUDGET_CONFIGURATION", configure("invalid", "0", "-1", false));
        assertCode("INVALID_BUDGET_CONFIGURATION", configure("invalid", "0", "0.0000000000001", false));
        assertCode("INVALID_BUDGET_CONFIGURATION", configure("invalid", "bad", "1", false));
        configured(configure("first", "0", "5", false));
        assertCode("BUDGET_CONFIGURATION_CONFLICT", configure("stale", "0", "7", false));
        assertConfigVersion(1);
        assertEquals(1, jdbc.queryForObject(
                "select count(*) from workspace_monthly_budget_command where workspace_id=?",
                Integer.class, workspace));
    }

    private JsonNode configure(String key, String version, String cap, boolean simulation) throws Exception {
        return graph(CONFIGURE, Map.of("input", Map.of(
                "workspaceId", workspace, "simulation", simulation, "enabled", true, "capUsd", cap,
                "expectedConfigVersion", version, "idempotencyKey", key)));
    }

    private JsonNode configured(JsonNode response) {
        assertFalse(response.has("errors"), response.toString());
        return response.path("data").path("configureWorkspaceMonthlyBudget");
    }

    private void assertStoredCap(String partition, String cap) {
        var stored = jdbc.queryForObject(
                "select cap_usd from workspace_monthly_budget where workspace_id=? and partition=?",
                BigDecimal.class, workspace, partition);
        assertEquals(0, new BigDecimal(cap).compareTo(stored), partition);
    }

    private void assertConfigVersion(long version) {
        assertEquals(version, jdbc.queryForObject(
                "select config_version from workspace_monthly_budget where workspace_id=? and partition='REAL'",
                Long.class, workspace));
    }

    private void assertCode(String expected, JsonNode response) {
        assertEquals(expected, response.path("errors").path(0).path("extensions").path("code").asText(),
                response.toString());
    }

    private void assertMoney(String expected, JsonNode actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(new BigDecimal(actual.asText())), actual.toString());
    }

    private JsonNode graph(String query, Map<String, ?> variables) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/graphql"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(
                        Map.of("query", query, "variables", variables))))
                .build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        return json.readTree(response.body());
    }
}
