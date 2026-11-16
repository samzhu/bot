package io.github.samzhu.bot;

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
class UsageCostReconcileIntegrationTests {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean CurrentUserProvider actor;
    final ObjectMapper json=new ObjectMapper();

    @Test void verifiedFakeReconcilesStoredFinalWithExplicitCatalogWithoutChangingRawOrOriginalPrice() throws Exception {
        String suffix=UUID.randomUUID().toString().substring(0,8), workspace="recon-w-"+suffix, id="recon-a-"+suffix, snapshot="recon-s-"+suffix, catalog="recon-c-"+suffix, oldPrice="recon-p-"+suffix;
        when(actor.getCurrentUserId()).thenReturn("recon-manager");
        jdbc.update("insert into workspace(id,name) values (?,?)",workspace,"reconcile fixture");
        jdbc.update("insert into workspace_member(workspace_id,user_id,role,active) values (?,'recon-manager','MANAGER',true)",workspace);
        jdbc.update("insert into provider_attempt(id,workspace_id,actor_id,provider,endpoint,model,channel,adapter_version,simulation,attempt_coverage,prepared_at,started_at,finished_at,execution_outcome,usage_presence,capture_completeness,projection_state,projection_version) values (?,?,'recon-manager','FAKE_GEMINI','generateContent','fixture-gemini','FAKE','1',true,'VERIFIED',now(),now(),now(),'SUCCEEDED','PRESENT','COMPLETE','VALID',1)",id,workspace);
        String raw="{\"promptTokenCount\":1000,\"cachedContentTokenCount\":200,\"candidatesTokenCount\":300,\"thoughtsTokenCount\":100,\"toolUsePromptTokenCount\":0,\"totalTokenCount\":1400}";
        jdbc.update("insert into usage_snapshot(id,attempt_id,event_id,payload_hash,ordinal,semantic,source_layer,schema_version,adapter_version,capture_version,capture_completeness,raw_state,raw_usage,captured_at) values (?,?,?,'fixture',1,'FINAL','FAKE_RAW','pricing-fixture-v1','1','1','COMPLETE','JSONB',?::jsonb,now())",snapshot,id,"event-"+suffix,raw);
        jdbc.update("update provider_attempt set projection_snapshot_id=? where id=?",snapshot,id);
        jdbc.update("insert into usage_price_snapshot(id,attempt_id,selection_state,provider,endpoint,model,channel) values (?,?,'MISSING','FAKE_GEMINI','generateContent','fixture-gemini','FAKE')",oldPrice,id);
        String oldEstimate="recon-old-"+suffix;
        jdbc.update("insert into usage_cost_estimate(id,attempt_id,source_version,source_snapshot_id,price_snapshot_id,calculator_version,status,details,payload_hash) values (?,?,1,?,?,'old','UNKNOWN','{\"reasons\":[\"MISSING_PRICE\"],\"lines\":[]}'::jsonb,'old-hash')",oldEstimate,id,snapshot,oldPrice);
        jdbc.update("insert into usage_cost_current(attempt_id,estimate_id) values (?,?)",id,oldEstimate);
        jdbc.update("insert into pricing_catalog_revision(id,source_reference,source_revision,content_hash) values (?,'fixture',?,'hash')",catalog,"revision-"+suffix);
        jdbc.update("insert into pricing_catalog_entry(id,revision_id,provider,endpoint,model,channel,price_revision,effective_from,effective_until,rates) values (?,?,'FAKE_GEMINI','generateContent','fixture-gemini','FAKE','fixture-v2',now()-interval '1 day',now()+interval '1 day','{\"input\":\"1\",\"cache_read\":\"0.1\",\"output\":\"2\",\"thoughts\":\"2\",\"tool_input\":\"1\"}'::jsonb)","entry-"+suffix,catalog);
        String command="mutation { reconcileCost(input:{workspaceId:\""+workspace+"\",attemptId:\""+id+"\",expectedRevision:\""+oldEstimate+"\",expectedSourceProjectionVersion:\"1\",idempotencyKey:\"test\",catalogRevisionId:\""+catalog+"\",evidenceReference:\"fixture-evidence\",reason:\"verified\"}) { outcome revision { id status knownSubtotal completeAmount sourceSnapshotId priceSnapshotId catalogRevisionId } effectiveCost { amount completeAmount } } }";
        var response=graph(command);
        assertFalse(response.has("errors"),response.toString());
        var revision=response.path("data").path("reconcileCost").path("revision");
        assertEquals("COMPLETE",revision.path("status").asText());
        assertEquals("0.001620000000",revision.path("completeAmount").asText());
        assertEquals("0.001620000000",response.path("data").path("reconcileCost").path("effectiveCost").path("amount").asText());
        assertEquals(snapshot,revision.path("sourceSnapshotId").asText());
        assertEquals(catalog,revision.path("catalogRevisionId").asText());
        assertNotEquals(oldPrice,revision.path("priceSnapshotId").asText());
        assertTrue(jdbc.queryForObject("select raw_usage=?::jsonb from usage_snapshot where id=?",Boolean.class,raw,snapshot));
        assertEquals("MISSING",jdbc.queryForObject("select selection_state from usage_price_snapshot where id=?",String.class,oldPrice));
        assertEquals("fixture-evidence",jdbc.queryForObject("select evidence_reference from usage_cost_revision_audit where estimate_id=?",String.class,revision.path("id").asText()));
        var history=graph("{ costRevisions(workspaceId:\""+workspace+"\",attemptId:\""+id+"\") { nodes { id status reasons sourceSnapshotId priceSnapshotId priceSelectionState priceRatesJson } } }");
        assertFalse(history.has("errors"),history.toString());
        var old=java.util.stream.StreamSupport.stream(history.path("data").path("costRevisions").path("nodes").spliterator(),false).filter(n->oldEstimate.equals(n.path("id").asText())).findFirst().orElseThrow();
        assertEquals("UNKNOWN",old.path("status").asText()); assertEquals("MISSING_PRICE",old.path("reasons").get(0).asText());
        assertEquals(snapshot,old.path("sourceSnapshotId").asText()); assertEquals(oldPrice,old.path("priceSnapshotId").asText());
        assertEquals("MISSING",old.path("priceSelectionState").asText()); assertTrue(old.path("priceRatesJson").isNull());
        var updated=java.util.stream.StreamSupport.stream(history.path("data").path("costRevisions").path("nodes").spliterator(),false).filter(n->revision.path("id").asText().equals(n.path("id").asText())).findFirst().orElseThrow();
        assertEquals("MATCHED",updated.path("priceSelectionState").asText()); assertEquals("0.1",json.readTree(updated.path("priceRatesJson").asText()).path("cache_read").asText());
        var replay=graph(command);
        assertFalse(replay.has("errors"),replay.toString());
        assertEquals("REPLAYED",replay.path("data").path("reconcileCost").path("outcome").asText());
        assertEquals(revision.path("id").asText(),replay.path("data").path("reconcileCost").path("revision").path("id").asText());
        assertEquals("0.001620000000",replay.path("data").path("reconcileCost").path("effectiveCost").path("completeAmount").asText());
        var duplicate=graph("mutation { reconcileCost(input:{workspaceId:\""+workspace+"\",attemptId:\""+id+"\",expectedRevision:\""+oldEstimate+"\",expectedSourceProjectionVersion:\"1\",idempotencyKey:\"test\",catalogRevisionId:\""+catalog+"\",evidenceReference:\"different-evidence\",reason:\"verified\"}) { outcome } }");
        assertEquals("CONFLICT",duplicate.path("errors").get(0).path("extensions").path("code").asText());
        assertEquals(1,jdbc.queryForObject("select count(*) from usage_cost_revision_audit where attempt_id=?",Integer.class,id));
        assertEquals(revision.path("id").asText(),jdbc.queryForObject("select estimate_id from usage_cost_current where attempt_id=?",String.class,id));
        var summaries=graph("{ workspaceUsageAttempt(workspaceId:\""+workspace+"\",id:\""+id+"\") { cost { knownSubtotal completeAmount currentRevision } } workspaceUsageReport(workspaceId:\""+workspace+"\",periodDays:7,simulation:true) { summary { knownSubtotal completeTotal unknownCount } } workspaceMonthlyBudget(workspaceId:\""+workspace+"\",simulation:true) { knownSubtotalUsd terminalUnknownCount } }");
        assertFalse(summaries.has("errors"),summaries.toString());
        var data=summaries.path("data");
        assertEquals("0.001620000000",data.path("workspaceUsageAttempt").path("cost").path("knownSubtotal").asText());
        assertEquals(revision.path("id").asText(),data.path("workspaceUsageAttempt").path("cost").path("currentRevision").asText());
        assertEquals("0.001620000000",data.path("workspaceUsageReport").path("summary").path("knownSubtotal").asText());
        assertEquals("0.001620000000",data.path("workspaceMonthlyBudget").path("knownSubtotalUsd").asText());
        assertEquals("0",data.path("workspaceMonthlyBudget").path("terminalUnknownCount").asText());
    }
    @Test void googleUnknownCoverageCannotBecomeCompleteEvenWithFinalAndPrice() throws Exception {
        String s=UUID.randomUUID().toString().substring(0,8), w="google-w-"+s,id="google-a-"+s,snap="google-s-"+s,cat="google-c-"+s;
        when(actor.getCurrentUserId()).thenReturn("google-manager");
        jdbc.update("insert into workspace(id,name) values (?,'fixture')",w);
        jdbc.update("insert into workspace_member(workspace_id,user_id,role,active) values (?,'google-manager','MANAGER',true)",w);
        jdbc.update("insert into provider_attempt(id,workspace_id,actor_id,provider,endpoint,model,channel,adapter_version,simulation,attempt_coverage,prepared_at,started_at,finished_at,execution_outcome,usage_presence,capture_completeness,projection_state,projection_version) values (?,?,'google-manager','GOOGLE','generateContent','fixture-gemini','GEMINI_API','1',false,'UNKNOWN',now(),now(),now(),'SUCCEEDED','PRESENT','INCOMPLETE','VALID',1)",id,w);
        jdbc.update("insert into usage_snapshot(id,attempt_id,event_id,payload_hash,ordinal,semantic,source_layer,schema_version,adapter_version,capture_version,capture_completeness,raw_state,raw_usage,captured_at) values (?,?,?,'fixture',1,'FINAL','SDK_NATIVE','1','1','1','INCOMPLETE','JSONB','{\"promptTokenCount\":1000,\"cachedContentTokenCount\":200,\"candidatesTokenCount\":300,\"thoughtsTokenCount\":100,\"toolUsePromptTokenCount\":0}'::jsonb,now())",snap,id,"google-e-"+s);
        jdbc.update("update provider_attempt set projection_snapshot_id=? where id=?",snap,id);
        jdbc.update("insert into pricing_catalog_revision(id,source_reference,source_revision,content_hash) values (?,'fixture',?,'hash')",cat,"google-rev-"+s);
        jdbc.update("insert into pricing_catalog_entry(id,revision_id,provider,endpoint,model,channel,price_revision,effective_from,effective_until,rates) values (?,?,'GOOGLE','generateContent','fixture-gemini','GEMINI_API','fixture-v1',now()-interval '1 day',now()+interval '1 day','{\"input\":\"1\",\"cache_read\":\"0.1\",\"output\":\"2\",\"thoughts\":\"2\",\"tool_input\":\"1\"}'::jsonb)","google-entry-"+s,cat);
        var result=graph("mutation { reconcileCost(input:{workspaceId:\""+w+"\",attemptId:\""+id+"\",expectedRevision:null,expectedSourceProjectionVersion:\"1\",idempotencyKey:\"test\",catalogRevisionId:\""+cat+"\",evidenceReference:\"fixture-evidence\",reason:\"verified\"}) { revision { id status knownSubtotal completeAmount } } }");
        assertFalse(result.has("errors"),result.toString());
        var revision=result.path("data").path("reconcileCost").path("revision");
        assertEquals("PARTIAL",revision.path("status").asText()); assertTrue(revision.path("completeAmount").isNull());
        assertTrue(jdbc.queryForObject("select details::text like '%COVERAGE_UNKNOWN%' from usage_cost_estimate where id=?",Boolean.class,revision.path("id").asText()));
        var resultViews=graph("{ workspaceUsageAttempt(workspaceId:\""+w+"\",id:\""+id+"\") { cost { status knownSubtotal completeAmount needsReprice reasons } } workspaceUsageReport(workspaceId:\""+w+"\",periodDays:7,simulation:false) { summary { status knownSubtotal completeTotal unknownCount coverageUnknownCount } } workspaceMonthlyBudget(workspaceId:\""+w+"\",simulation:false) { knownSubtotalUsd terminalUnknownCount } }");
        assertFalse(resultViews.has("errors"),resultViews.toString());
        var data=resultViews.path("data"); var cost=data.path("workspaceUsageAttempt").path("cost");
        var summary=data.path("workspaceUsageReport").path("summary"); var budget=data.path("workspaceMonthlyBudget");
        assertEquals("PARTIAL",cost.path("status").asText()); assertTrue(cost.path("completeAmount").isNull()); assertFalse(cost.path("needsReprice").asBoolean());
        assertEquals("0.001620000000",cost.path("knownSubtotal").asText());
        assertEquals("PARTIAL",summary.path("status").asText()); assertEquals("0.001620000000",summary.path("knownSubtotal").asText());
        assertTrue(summary.path("completeTotal").isNull()); assertEquals("1",summary.path("unknownCount").asText()); assertEquals("1",summary.path("coverageUnknownCount").asText());
        assertEquals("0.001620000000",budget.path("knownSubtotalUsd").asText()); assertEquals("1",budget.path("terminalUnknownCount").asText());
    }
    private com.fasterxml.jackson.databind.JsonNode graph(String query) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/graphql")).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("query",query)))).build();
        return json.readTree(HttpClient.newHttpClient().send(request,HttpResponse.BodyHandlers.ofString()).body());
    }
}
