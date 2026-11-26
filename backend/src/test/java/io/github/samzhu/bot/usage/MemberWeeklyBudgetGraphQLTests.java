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
class MemberWeeklyBudgetGraphQLTests {
    private static final String CALLER = "dev-user";
    private static final String MEMBER_PAGE = """
            query MemberPage($workspace: ID!, $first: Int, $after: String) {
              workspaceActiveMembers(workspaceId: $workspace, first: $first, after: $after) {
                nodes { userId role }
                pageInfo { hasNextPage endCursor }
              }
            }
            """;
    private static final String CONFIGURE = """
            mutation Configure($input: ConfigureMemberWeeklyBudgetInput!) {
              configureMemberWeeklyBudget(input: $input) { outcome budget { memberUserId capUsd configVersion } }
            }
            """;

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate transactions;

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();
    private final String workspace = "weekly-graphql-" + UUID.randomUUID();
    private final String member = "member-" + UUID.randomUUID();

    @BeforeEach
    void createWorkspace() {
        transactions.executeWithoutResult(status -> {
            jdbc.update("insert into workspace(id,name) values (?,'Weekly budget fixture')", workspace);
            jdbc.update("""
                    insert into workspace_member(workspace_id,user_id,role,active)
                    values (?,?,'MANAGER',true),(?,?,'MEMBER',true)
                    """, workspace, CALLER, workspace, member);
        });
    }

    @AfterEach
    void cleanUp() {
        try {
            transactions.executeWithoutResult(status -> {
                jdbc.update("delete from member_weekly_budget_command where workspace_id=?", workspace);
                jdbc.update("delete from member_weekly_budget where workspace_id=?", workspace);
                // Weekly configuration obtains its lock from the monthly policy row.
                jdbc.update("delete from workspace_monthly_budget where workspace_id=?", workspace);
                jdbc.update("delete from workspace_member where workspace_id=?", workspace);
                jdbc.update("delete from workspace where id=?", workspace);
            });
        } finally {
            http.close();
        }
    }

    @Test
    void managerCanPageActiveMembers() throws Exception {
        var result = graph(MEMBER_PAGE, Map.of("workspace", workspace, "first", 1));
        assertFalse(result.has("errors"), result.toString());
        var page = result.path("data").path("workspaceActiveMembers");
        assertEquals(1, page.path("nodes").size());
        assertEquals(CALLER, page.path("nodes").get(0).path("userId").asText());
        assertEquals("MANAGER", page.path("nodes").get(0).path("role").asText());
        assertTrue(page.path("pageInfo").path("hasNextPage").asBoolean());
        String cursor = page.path("pageInfo").path("endCursor").asText();
        assertFalse(cursor.isBlank(), page.toString());

        var next = graph(MEMBER_PAGE, Map.of("workspace", workspace, "first", 1, "after", cursor));
        assertFalse(next.has("errors"), next.toString());
        var lastPage = next.path("data").path("workspaceActiveMembers");
        assertEquals(1, lastPage.path("nodes").size());
        assertEquals(member, lastPage.path("nodes").get(0).path("userId").asText());
        assertEquals("MEMBER", lastPage.path("nodes").get(0).path("role").asText());
        assertFalse(lastPage.path("pageInfo").path("hasNextPage").asBoolean());
    }

    @Test
    void memberPageValidatesScopeAndCursorAfterAuthorization() throws Exception {
        var invalidPage = Map.<String, Object>of("workspace", workspace, "first", 0);
        assertCode("INVALID_PAGE_SIZE", graph(MEMBER_PAGE, invalidPage));
        assertCode("INVALID_CURSOR", graph(MEMBER_PAGE, Map.of("workspace", workspace, "after", "bad")));
        demoteCaller();
        assertCode("WORKSPACE_MANAGER_REQUIRED", graph(MEMBER_PAGE, invalidPage));
    }

    @Test
    void weeklyCommandReplayAndAuthorizationAreScoped() throws Exception {
        assertEquals("APPLIED", configured(configure(member, "5.0", "replay")).path("outcome").asText());
        assertEquals("REPLAYED", configured(configure(member, "5.0", "replay")).path("outcome").asText());
        demoteCaller();
        assertCode("WORKSPACE_MANAGER_REQUIRED", configure(member, "5.0", "replay"));
        assertCode("WORKSPACE_MANAGER_REQUIRED", graph("""
                query MemberBudget($workspace: ID!, $member: ID!) {
                  memberWeeklyBudget(workspaceId: $workspace, memberUserId: $member, simulation: false) { memberUserId }
                }
                """, Map.of("workspace", workspace, "member", member)));
        assertEquals(1, jdbc.queryForObject(
                "select count(*) from member_weekly_budget_command where workspace_id=? and member_user_id=?",
                Integer.class, workspace, member));
    }

    @Test
    void managerConfiguresAndMemberReadsOwnWeeklyBudget() throws Exception {
        var other = configured(configure(member, "12.345", "other-member"));
        assertEquals("APPLIED", other.path("outcome").asText());
        assertEquals(member, other.path("budget").path("memberUserId").asText());
        assertMoney("12.345", other.path("budget").path("capUsd"));

        var own = configured(configure(CALLER, "7.25", "own-budget"));
        assertEquals("APPLIED", own.path("outcome").asText());
        demoteCaller();

        var read = graph("""
                query OwnBudget($workspace: ID!) {
                  myMemberWeeklyBudget(workspaceId: $workspace, simulation: false) {
                    memberUserId capUsd knownSubtotalUsd state configVersion timezone
                  }
                }
                """, Map.of("workspace", workspace));
        assertFalse(read.has("errors"), read.toString());
        var budget = read.path("data").path("myMemberWeeklyBudget");
        assertEquals(CALLER, budget.path("memberUserId").asText());
        assertMoney("7.25", budget.path("capUsd"));
        assertMoney("0", budget.path("knownSubtotalUsd"));
        assertEquals("OPEN", budget.path("state").asText());
        assertEquals("1", budget.path("configVersion").asText());
        assertEquals("Asia/Taipei", budget.path("timezone").asText());
    }

    private void demoteCaller() {
        // Keep the real caller an active member while testing the loss of management rights.
        assertEquals(1, jdbc.update("update workspace_member set role='MEMBER' where workspace_id=? and user_id=?",
                workspace, CALLER));
    }

    private JsonNode configure(String memberId, String capUsd, String key) throws Exception {
        return graph(CONFIGURE, Map.of("input", Map.of(
                "workspaceId", workspace, "memberUserId", memberId, "simulation", false, "enabled", true,
                "capUsd", capUsd, "expectedConfigVersion", "0", "idempotencyKey", key)));
    }

    private JsonNode configured(JsonNode response) {
        assertFalse(response.has("errors"), response.toString());
        return response.path("data").path("configureMemberWeeklyBudget");
    }

    private void assertCode(String expected, JsonNode response) {
        assertEquals(expected, response.path("errors").path(0).path("extensions").path("code").asText(),
                response.toString());
    }

    private void assertMoney(String expected, JsonNode value) {
        assertEquals(0, new BigDecimal(expected).compareTo(new BigDecimal(value.asText())), value.toString());
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
