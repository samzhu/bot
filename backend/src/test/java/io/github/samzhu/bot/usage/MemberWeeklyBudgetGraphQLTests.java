package io.github.samzhu.bot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import java.net.URI;
import java.net.http.*;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class MemberWeeklyBudgetGraphQLTests {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @org.springframework.test.context.bean.override.mockito.MockitoBean io.github.samzhu.bot.identity.CurrentUserProvider users;
    private final ObjectMapper json=new ObjectMapper();
    private final HttpClient http=HttpClient.newHttpClient();
    private JsonNode graph(String query) throws Exception {
        var req=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/graphql"))
                .header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("query",query)))).build();
        return json.readTree(http.send(req,HttpResponse.BodyHandlers.ofString()).body());
    }
    @Test void managerCanPageActiveMembers() throws Exception {
        String manager="a-manager-"+java.util.UUID.randomUUID();
        org.mockito.Mockito.when(users.getCurrentUserId()).thenReturn(manager);
        String workspace=graph("{ personalWorkspace { id } }").path("data").path("personalWorkspace").path("id").asText();
        String member="member-"+java.util.UUID.randomUUID();
        jdbc.update("insert into workspace_member(workspace_id,user_id,role,active) values (?,?,'MEMBER',true)",workspace,member);
        var result=graph("{ workspaceActiveMembers(workspaceId:\""+workspace+"\",first:1) { nodes { userId role } pageInfo { hasNextPage endCursor } } }");
        assertFalse(result.has("errors"),result.toString());
        var page=result.path("data").path("workspaceActiveMembers");
        assertEquals(manager,page.path("nodes").get(0).path("userId").asText());
        assertTrue(page.path("pageInfo").path("hasNextPage").asBoolean());
        String cursor=page.path("pageInfo").path("endCursor").asText();
        var next=graph("{ workspaceActiveMembers(workspaceId:\""+workspace+"\",first:1,after:\""+cursor+"\") { nodes { userId role } pageInfo { hasNextPage } } }");
        assertFalse(next.has("errors"),next.toString());
        assertEquals(member,next.path("data").path("workspaceActiveMembers").path("nodes").get(0).path("userId").asText());
    }
    @Test void memberPageValidatesScopeAndCursorAfterAuthorization() throws Exception {
        org.mockito.Mockito.when(users.getCurrentUserId()).thenReturn("dev-user");
        String workspace=graph("{ personalWorkspace { id } }").path("data").path("personalWorkspace").path("id").asText();
        String member="member-"+java.util.UUID.randomUUID();
        jdbc.update("insert into workspace_member(workspace_id,user_id,role,active) values (?,?,'MEMBER',true)",workspace,member);
        assertEquals("INVALID_PAGE_SIZE",graph("{ workspaceActiveMembers(workspaceId:\""+workspace+"\",first:0) { nodes { userId } } }").path("errors").get(0).path("extensions").path("code").asText());
        assertEquals("INVALID_CURSOR",graph("{ workspaceActiveMembers(workspaceId:\""+workspace+"\",after:\"bad\") { nodes { userId } } }").path("errors").get(0).path("extensions").path("code").asText());
        org.mockito.Mockito.when(users.getCurrentUserId()).thenReturn(member);
        assertEquals("WORKSPACE_MANAGER_REQUIRED",graph("{ workspaceActiveMembers(workspaceId:\""+workspace+"\",first:0) { nodes { userId } } }").path("errors").get(0).path("extensions").path("code").asText());
    }
    @Test void weeklyCommandReplayAndAuthorizationAreScoped() throws Exception {
        org.mockito.Mockito.when(users.getCurrentUserId()).thenReturn("dev-user");
        String workspace=graph("{ personalWorkspace { id } }").path("data").path("personalWorkspace").path("id").asText();
        String member="member-"+java.util.UUID.randomUUID();
        jdbc.update("insert into workspace_member(workspace_id,user_id,role,active) values (?,?,'MEMBER',true)",workspace,member);
        String prefix="mutation { configureMemberWeeklyBudget(input:{workspaceId:\""+workspace+"\",memberUserId:\""+member+"\",simulation:false,enabled:true,capUsd:\"5.0\",expectedConfigVersion:\"0\",idempotencyKey:\"replay\"}) { outcome budget { configVersion } } }";
        assertEquals("APPLIED",graph(prefix).path("data").path("configureMemberWeeklyBudget").path("outcome").asText());
        assertEquals("REPLAYED",graph(prefix).path("data").path("configureMemberWeeklyBudget").path("outcome").asText());
        org.mockito.Mockito.when(users.getCurrentUserId()).thenReturn(member);
        assertEquals("WORKSPACE_MANAGER_REQUIRED",graph(prefix).path("errors").get(0).path("extensions").path("code").asText());
        assertEquals("WORKSPACE_MANAGER_REQUIRED",graph("{ memberWeeklyBudget(workspaceId:\""+workspace+"\",memberUserId:\"dev-user\",simulation:false) { memberUserId } }").path("errors").get(0).path("extensions").path("code").asText());
    }
    @Test void managerConfiguresAndMemberReadsOwnWeeklyBudget() throws Exception {
        org.mockito.Mockito.when(users.getCurrentUserId()).thenReturn("dev-user");
        String workspace=graph("{ personalWorkspace { id } }").path("data").path("personalWorkspace").path("id").asText();
        String member="member-"+java.util.UUID.randomUUID();
        jdbc.update("insert into workspace_member(workspace_id,user_id,role,active) values (?,?,'MEMBER',true)",workspace,member);
        var configured=graph("mutation { configureMemberWeeklyBudget(input:{workspaceId:\""+workspace+"\",memberUserId:\""+member+"\",simulation:false,enabled:true,capUsd:\"12.345\",expectedConfigVersion:\"0\",idempotencyKey:\"first\"}) { outcome budget { memberUserId capUsd configVersion } } }");
        assertFalse(configured.has("errors"),configured.toString());
        assertEquals("APPLIED",configured.path("data").path("configureMemberWeeklyBudget").path("outcome").asText());
        org.mockito.Mockito.when(users.getCurrentUserId()).thenReturn(member);
        var read=graph("{ myMemberWeeklyBudget(workspaceId:\""+workspace+"\",simulation:false) { memberUserId capUsd knownSubtotalUsd state configVersion timezone } }");
        assertFalse(read.has("errors"),read.toString());
        var budget=read.path("data").path("myMemberWeeklyBudget");
        assertEquals(member,budget.path("memberUserId").asText());
        assertEquals(0,new java.math.BigDecimal("12.345").compareTo(new java.math.BigDecimal(budget.path("capUsd").asText())));
        assertEquals(0,new java.math.BigDecimal(budget.path("knownSubtotalUsd").asText()).signum());
        assertEquals("OPEN",budget.path("state").asText());
        assertEquals("1",budget.path("configVersion").asText());
        assertEquals("Asia/Taipei",budget.path("timezone").asText());
    }
}
