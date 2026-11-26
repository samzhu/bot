package io.github.samzhu.bot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.samzhu.bot.usage.pricing.UsageCaseData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@BackendIntegrationTest
class UsageCostReviewQueueIntegrationTests {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate transactions;
    private UsageCaseData data;
    final ObjectMapper json=new ObjectMapper();
    @BeforeEach
    void setUp() {
        data = new UsageCaseData(jdbc, transactions);
    }

    @AfterEach
    void cleanUp() throws InterruptedException {
        data.cleanUp();
    }

    @Test void oldAttemptRemainsPageableAfterAnchorIsFixedAndManagerIsRechecked() throws Exception {
        String suffix=UUID.randomUUID().toString(),workspace="queue-w-"+suffix,a="queue-a-"+suffix,b="queue-b-"+suffix;
        data.createWorkspace(workspace, "dev-user");
        for(int i=0;i<4;i++) {
            String id=switch(i){case 0->a;case 1->b;case 2->"progress-"+suffix;default->"undispatched-"+suffix;};
            String outcome=i==2?"IN_PROGRESS":i==3?"NOT_DISPATCHED":"SUCCEEDED";
            jdbc.update("insert into provider_attempt(id,workspace_id,actor_id,provider,endpoint,model,channel,adapter_version,simulation,attempt_coverage,prepared_at,started_at,execution_outcome,usage_presence,capture_completeness,projection_state,projection_version) values (?,?,'dev-user','QUEUE','test','fixture','test','1',false,'VERIFIED',now()-(?*interval '1 day'),case when ?='NOT_DISPATCHED' then null else now()-interval '59 days' end,?,'UNKNOWN','UNKNOWN','MISSING',1)",id,workspace,60+i,outcome,outcome);
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
        jdbc.update("update workspace_member set role='MEMBER' where workspace_id=? and user_id='dev-user'",workspace);
        JsonNode denied=graph("{ costReviewAttempts(workspaceId:\""+workspace+"\",first:1,after:\""+cursor+"\") { nodes { id } } }");
        assertEquals("WORKSPACE_MANAGER_REQUIRED",denied.path("errors").get(0).path("extensions").path("code").asText());
    }
    @Test
    void costReviewSelectorIncludesEachIncompleteBranchAndExcludesCompleteVerifiedCurrent() throws Exception {
        String workspace = "review-branches-" + UUID.randomUUID();
        data.createWorkspace(workspace, "dev-user");
        String missing="review-selector-missing-"+UUID.randomUUID();
        String stale="review-selector-stale-"+UUID.randomUUID();
        String partial="review-selector-partial-"+UUID.randomUUID();
        String unknownCoverage="review-selector-coverage-"+UUID.randomUUID();
        String complete="review-selector-complete-"+UUID.randomUUID();

        insertReviewAttempt(workspace,missing,"VERIFIED",11,null,null,null);
        insertReviewAttempt(workspace,stale,"VERIFIED",11,10L,"COMPLETE","2");
        insertReviewAttempt(workspace,partial,"VERIFIED",11,11L,"PARTIAL","2");
        insertReviewAttempt(workspace,unknownCoverage,"UNKNOWN",11,11L,"COMPLETE","2");
        insertReviewAttempt(workspace,complete,"VERIFIED",11,11L,"COMPLETE","2");

        JsonNode result=graph("{ costReviewAttempts(workspaceId:\""+workspace+"\",simulation:false,first:100){nodes{id}} }");
        assertFalse(result.has("errors"),result.toString());
        var nodes=result.path("data").path("costReviewAttempts").path("nodes");
        java.util.Set<String> ids=new java.util.HashSet<>();
        nodes.forEach(node->ids.add(node.path("id").asText()));
        assertTrue(ids.contains(missing),"attempt without a current estimate must be reviewed");
        assertTrue(ids.contains(stale),"source-version mismatch must be reviewed");
        assertTrue(ids.contains(partial),"non-COMPLETE current estimate must be reviewed");
        assertTrue(ids.contains(unknownCoverage),"unknown dispatch coverage must be reviewed");
        assertFalse(ids.contains(complete),"current COMPLETE estimate with matching source and VERIFIED coverage is not review work");
    }

    private void insertReviewAttempt(String workspace,String id,String coverage,long projectionVersion,
                                     Long sourceVersion,String costStatus,String amount) {
        jdbc.update("""
            insert into provider_attempt(id,workspace_id,actor_id,provider,endpoint,model,channel,adapter_version,
              simulation,attempt_coverage,prepared_at,started_at,finished_at,execution_outcome,usage_presence,
              capture_completeness,projection_state,projection_version)
            values (?,?, 'dev-user','FAKE','local','review-model','test','fixture',false,?,now(),now(),now(),
              'SUCCEEDED','UNKNOWN','UNKNOWN','UNKNOWN',?)
            """,id,workspace,coverage,projectionVersion);
        if(sourceVersion==null) return;

        String suffix=UUID.randomUUID().toString().substring(0,12);
        String price="review-p-"+suffix;
        String estimate="review-e-"+suffix;
        jdbc.update("insert into usage_price_snapshot(id,attempt_id,selection_state,provider,endpoint,model,channel) values (?,?,'MISSING','FAKE','local','review-model','test')",price,id);
        jdbc.update("""
            insert into usage_cost_estimate(id,attempt_id,source_version,price_snapshot_id,calculator_version,status,
              known_subtotal,complete_amount,details,payload_hash)
            values (?,?,?,?,'review-fixture',?,?,?,'{\"reasons\":[],\"lines\":[]}'::jsonb,?)
            """,estimate,id,sourceVersion,price,costStatus,
                amount==null?null:new java.math.BigDecimal(amount),
                "COMPLETE".equals(costStatus)?new java.math.BigDecimal(amount):null,"hash-"+suffix);
        jdbc.update("insert into usage_cost_current(attempt_id,estimate_id) values (?,?)",id,estimate);
    }

    private JsonNode graph(String query) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/graphql"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("query", query))))
                .build();
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode(), response.body());
            return json.readTree(response.body());
        }
    }
}
