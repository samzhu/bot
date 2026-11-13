package io.github.samzhu.bot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.samzhu.bot.identity.CurrentUserProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class UsageCostReviewQueueIntegrationTests {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean CurrentUserProvider actor;
    final ObjectMapper json=new ObjectMapper();
    @Test void oldAttemptRemainsPageableAfterAnchorIsFixedAndManagerIsRechecked() throws Exception {
        String suffix=UUID.randomUUID().toString().substring(0,8),workspace="queue-w-"+suffix,a="queue-a-"+suffix,b="queue-b-"+suffix;
        when(actor.getCurrentUserId()).thenReturn("queue-manager");
        jdbc.update("insert into workspace(id,name) values (?,'fixture')",workspace);
        jdbc.update("insert into workspace_member(workspace_id,user_id,role,active) values (?,'queue-manager','MANAGER',true)",workspace);
        for(int i=0;i<4;i++) {
            String id=switch(i){case 0->a;case 1->b;case 2->"progress-"+suffix;default->"undispatched-"+suffix;};
            String outcome=i==2?"IN_PROGRESS":i==3?"NOT_DISPATCHED":"SUCCEEDED";
            jdbc.update("insert into provider_attempt(id,workspace_id,actor_id,provider,endpoint,model,channel,adapter_version,simulation,attempt_coverage,prepared_at,started_at,execution_outcome,usage_presence,capture_completeness,projection_state,projection_version) values (?,?,'queue-manager','QUEUE','test','fixture','test','1',false,'VERIFIED',now()-(?*interval '1 day'),case when ?='NOT_DISPATCHED' then null else now()-interval '59 days' end,?,'UNKNOWN','UNKNOWN','MISSING',1)",id,workspace,60+i,outcome,outcome);
        }
        JsonNode page=graph("{ costReviewAttempts(workspaceId:\""+workspace+"\",first:1) { nodes { id cost { status } } pageInfo { hasNextPage endCursor } } }");
        assertFalse(page.has("errors"),page.toString());
        var result=page.path("data").path("costReviewAttempts");
        assertEquals(a,result.path("nodes").get(0).path("id").asText()); assertTrue(result.path("pageInfo").path("hasNextPage").asBoolean());
        String cursor=result.path("pageInfo").path("endCursor").asText();
        jdbc.update("insert into usage_price_snapshot(id,attempt_id,selection_state,provider,endpoint,model,channel) values (?,?,'MISSING','QUEUE','test','fixture','test')","queue-price-"+suffix,a);
        jdbc.update("insert into usage_cost_estimate(id,attempt_id,source_version,price_snapshot_id,calculator_version,status,known_subtotal,complete_amount,details,payload_hash) values (?,?,1,?,'fixture','COMPLETE',1,1,'{\"reasons\":[],\"lines\":[]}'::jsonb,'hash')","queue-est-"+suffix,a,"queue-price-"+suffix);
        jdbc.update("insert into usage_cost_current(attempt_id,estimate_id) values (?,?)",a,"queue-est-"+suffix);
        JsonNode next=graph("{ costReviewAttempts(workspaceId:\""+workspace+"\",first:1,after:\""+cursor+"\") { nodes { id } pageInfo { hasNextPage } } }");
        assertFalse(next.has("errors"),next.toString()); assertEquals(b,next.path("data").path("costReviewAttempts").path("nodes").get(0).path("id").asText());
        jdbc.update("update workspace_member set role='MEMBER' where workspace_id=? and user_id='queue-manager'",workspace);
        JsonNode denied=graph("{ costReviewAttempts(workspaceId:\""+workspace+"\",first:1,after:\""+cursor+"\") { nodes { id } } }");
        assertEquals("WORKSPACE_MANAGER_REQUIRED",denied.path("errors").get(0).path("extensions").path("code").asText());
    }
    private JsonNode graph(String query) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/graphql")).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("query",query)))).build();
        return json.readTree(HttpClient.newHttpClient().send(request,HttpResponse.BodyHandlers.ofString()).body());
    }
}
