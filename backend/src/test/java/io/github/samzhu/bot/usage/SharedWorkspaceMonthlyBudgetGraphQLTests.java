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

    @Test
    void authorizationPrecedesMutationValidationAndMemberCanRead() throws Exception {
        setMembership("MEMBER", true);
        var read = graph("""
                query Budget($workspace: ID!) {
                  workspaceMonthlyBudget(workspaceId: $workspace, simulation: false) { workspaceId state }
                }
                """, Map.of("workspace", workspace));
        assertFalse(read.has("errors"), read.toString());
        assertEquals(workspace, read.path("data").path("workspaceMonthlyBudget").path("workspaceId").asText());
        assertCode("WORKSPACE_MANAGER_REQUIRED", configure("authorize", "bad", "bad", false));

        setMembership("MEMBER", false);
        assertCode("WORKSPACE_ACCESS_DENIED", configure("authorize", "bad", "bad", false));
        assertCode("WORKSPACE_ACCESS_DENIED",
                configureWorkspace("missing-" + UUID.randomUUID(), "authorize", "bad", "bad", false));
        assertEquals(0, jdbc.queryForObject("""
                select count(*) from workspace_monthly_budget_command where workspace_id=?
                """, Integer.class, workspace));
    }

    @Test
    void revokedManagerCannotReplayOrReadPreviousResult() throws Exception {
        assertEquals("APPLIED", configured(configure("revoked", "0", "5", false)).path("outcome").asText());

        setMembership("MANAGER", false);
        var replay = configure("revoked", "0", "5.00", false);
        assertCode("WORKSPACE_ACCESS_DENIED", replay);
        assertTrue(replay.path("data").isNull());
        assertConfigVersion(1);
        assertEquals(1, jdbc.queryForObject("""
                select count(*) from workspace_monthly_budget_command where workspace_id=?
                """, Integer.class, workspace));
    }

    @Test
    void untouchedLegacyRowGetsDefaultButSavedNullAndDisabledChoiceStaySaved() throws Exception {
        seedUnconfiguredPolicy();
        var first = budget();
        assertMoney("10", first.path("capUsd"));
        assertFalse(first.path("enabled").asBoolean());
        assertEquals("0", first.path("configVersion").asText());

        String command = """
                mutation Disable($workspace: ID!) {
                  configureWorkspaceMonthlyBudget(input: {workspaceId: $workspace, simulation: false, enabled: false,
                    capUsd: null, expectedConfigVersion: "0", idempotencyKey: "legacy-null"}) { outcome }
                }
                """;
        assertEquals("APPLIED", configured(graph(command, Map.of("workspace", workspace))).path("outcome").asText());
        assertEquals("REPLAYED", configured(graph(command, Map.of("workspace", workspace))).path("outcome").asText());
        var saved = budget();
        assertTrue(saved.path("capUsd").isNull());
        assertFalse(saved.path("enabled").asBoolean());
        assertEquals("1", saved.path("configVersion").asText());
        assertEquals(1, jdbc.queryForObject("""
                select count(*) from workspace_monthly_budget_command where workspace_id=? and partition='REAL'
                """, Integer.class, workspace));
    }

    @Test
    void versionZeroWithCommandEvidenceIsNotOverwritten() throws Exception {
        seedUnconfiguredPolicy();
        jdbc.update("""
                insert into workspace_monthly_budget_command(workspace_id,partition,actor_id,idempotency_key,
                    expected_config_version,enabled,cap_usd) values (?,'REAL','dev-user','legacy',0,false,null)
                """, workspace);
        var result = budget();
        assertTrue(result.path("capUsd").isNull());
        assertEquals("0", result.path("configVersion").asText());
    }

    @Test
    void receiptFromPastPeriodReplaysAgainstCurrentPolicyWithoutASecondEffect() throws Exception {
        jdbc.update("""
                insert into workspace_monthly_budget(workspace_id,partition,enabled,cap_usd,config_version)
                values (?,'REAL',true,8,2)
                """, workspace);
        jdbc.update("""
                insert into workspace_monthly_budget_command(workspace_id,partition,actor_id,idempotency_key,
                    expected_config_version,enabled,cap_usd,applied_at)
                values (?,'REAL','dev-user','past',0,true,5,current_timestamp-interval '2 months')
                """, workspace);
        var result = configured(configure("past", "0", "5.00", false));
        assertEquals("REPLAYED", result.path("outcome").asText());
        assertMoney("8", result.path("budget").path("capUsd"));
        assertConfigVersion(2);
        assertEquals(1, jdbc.queryForObject("""
                select count(*) from workspace_monthly_budget_command where workspace_id=? and partition='REAL'
                """, Integer.class, workspace));
        assertEquals(0, jdbc.queryForObject("""
                select count(*) from workspace_admission_effect_outbox where target_workspace_id=?
                """, Integer.class, workspace));
    }

    private void seedUnconfiguredPolicy() {
        jdbc.update("""
                insert into workspace_monthly_budget(workspace_id,partition,enabled,cap_usd,config_version)
                values (?,'REAL',false,null,0)
                """, workspace);
    }

    private JsonNode budget() throws Exception {
        var response = graph("""
                query Budget($workspace: ID!) {
                  workspaceMonthlyBudget(workspaceId: $workspace, simulation: false) { capUsd enabled configVersion }
                }
                """, Map.of("workspace", workspace));
        assertFalse(response.has("errors"), response.toString());
        return response.path("data").path("workspaceMonthlyBudget");
    }

    private void setMembership(String role, boolean active) {
        assertEquals(1, jdbc.update("""
                update workspace_member set role=?, active=? where workspace_id=? and user_id='dev-user'
                """, role, active, workspace));
    }

    private JsonNode configure(String key, String version, String cap, boolean simulation) throws Exception {
        return configureWorkspace(workspace, key, version, cap, simulation);
    }

    private JsonNode configureWorkspace(String targetWorkspace, String key, String version, String cap,
                                        boolean simulation) throws Exception {
        return graph(CONFIGURE, Map.of("input", Map.of(
                "workspaceId", targetWorkspace, "simulation", simulation, "enabled", true, "capUsd", cap,
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
