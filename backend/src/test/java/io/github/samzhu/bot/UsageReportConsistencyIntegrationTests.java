package io.github.samzhu.bot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.samzhu.bot.identity.CurrentUserProvider;
import io.github.samzhu.bot.usage.UsageCostOperations;
import io.github.samzhu.bot.workspace.WorkspaceOperations;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class UsageReportConsistencyIntegrationTests {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired WorkspaceOperations workspaces;
    @org.springframework.test.context.bean.override.convention.TestBean(name="usageClock",methodName="fixedClock",enforceOverride=true)
    Clock clock;
    static Clock fixedClock() { return Clock.fixed(Instant.parse("2026-11-13T04:00:00.000000123Z"),java.time.ZoneOffset.UTC); }
    @MockitoBean CurrentUserProvider actor;
    @MockitoSpyBean UsageCostOperations costs;
    final ObjectMapper json=new ObjectMapper();

    @Test void reportAndNestedCostShareOneDatabaseSnapshotWhileDetailReadsLatest() throws Exception {
        when(actor.getCurrentUserId()).thenReturn("consistency-user");
        String workspace=workspaces.personal("consistency-user").id();
        String suffix=UUID.randomUUID().toString(); String id="cons-"+suffix;
        Instant started=clock.instant().minusSeconds(60);
        jdbc.update("""
          insert into provider_attempt(id,workspace_id,actor_id,provider,endpoint,model,channel,adapter_version,
            simulation,attempt_coverage,prepared_at,started_at,execution_outcome,usage_presence,capture_completeness,projection_state,projection_version)
          values (?,?,?,'CONSISTENCY','test','fixture','test','1',false,'VERIFIED',?,?, 'SUCCEEDED','ABSENT','UNKNOWN','MISSING',1)
          """,id,workspace,"consistency-user",java.sql.Timestamp.from(started),java.sql.Timestamp.from(started));
        String price="price-"+suffix,first="est1-"+suffix,second="est2-"+suffix;
        jdbc.update("insert into usage_price_snapshot(id,attempt_id,selection_state,provider,endpoint,model,channel) values (?,?,'MISSING','CONSISTENCY','test','fixture','test')",price,id);
        jdbc.update("insert into usage_cost_estimate(id,attempt_id,source_version,price_snapshot_id,calculator_version,status,known_subtotal,complete_amount,details,payload_hash) values (?,?,1,?,'test','COMPLETE',1,1,'{\"reasons\":[],\"lines\":[]}'::jsonb,'hash1')",first,id,price);
        jdbc.update("insert into usage_cost_estimate(id,attempt_id,source_version,price_snapshot_id,calculator_version,status,known_subtotal,complete_amount,details,payload_hash) values (?,?,2,?,'test','COMPLETE',2,2,'{\"reasons\":[],\"lines\":[]}'::jsonb,'hash2')",second,id,price);
        jdbc.update("insert into usage_cost_current(attempt_id,estimate_id) values (?,?)",id,first);
        AtomicBoolean changed=new AtomicBoolean();
        doAnswer(invocation -> {
            Object result=invocation.callRealMethod();
            if (changed.compareAndSet(false,true)) CompletableFuture.runAsync(() -> tx.executeWithoutResult(status -> {
                jdbc.update("update provider_attempt set projection_version=2 where id=?",id);
                jdbc.update("update usage_cost_current set estimate_id=? where attempt_id=?",second,id);
            })).join();
            return result;
        }).when(costs).readCosts(anyList());
        try {
            var response=graph("{ myUsageReport(workspaceId:\""+workspace+"\") { summary { completeTotal } attempts { nodes { id cost { completeAmount sourceProjectionVersion estimateSourceVersion } } } } }");
            assertFalse(response.has("errors"),response.toString());
            var report=response.path("data").path("myUsageReport");
            assertEquals("1.000000000000",report.path("summary").path("completeTotal").asText());
            var node=report.path("attempts").path("nodes").get(0).path("cost");
            assertEquals("1.000000000000",node.path("completeAmount").asText());
            assertEquals("1",node.path("sourceProjectionVersion").asText());
            var detail=graph("{ myUsageAttempt(workspaceId:\""+workspace+"\",id:\""+id+"\") { cost { completeAmount sourceProjectionVersion } } }");
            assertFalse(detail.has("errors"),detail.toString());
            assertEquals("2.000000000000",detail.path("data").path("myUsageAttempt").path("cost").path("completeAmount").asText());
        } finally { reset(costs); }
    }
    @Test void managerReadScopeDoesNotGrantChatAndNestedCostRechecksRevocation() throws Exception {
        String suffix=UUID.randomUUID().toString().substring(0,8),workspace="report-w-"+suffix,manager="mgr-"+suffix,member="member-"+suffix;
        String attempt="attempt-"+suffix,conversation="conversation-"+suffix,bot="bot-"+suffix;
        jdbc.update("insert into workspace(id,name) values (?,?)",workspace,"fixture");
        jdbc.update("insert into workspace_member(workspace_id,user_id,role,active) values (?,?,'MANAGER',true),(?,?,'MEMBER',true)",workspace,manager,workspace,member);
        jdbc.update("insert into bot(id,name) values (?,?)",bot,"fixture");
        jdbc.update("insert into conversation(id,bot_id,title,user_id,workspace_id) values (?,?,?,?,?)",conversation,bot,"private chat",member,workspace);
        Instant started=clock.instant().minusSeconds(30);
        jdbc.update("""
          insert into provider_attempt(id,workspace_id,actor_id,provider,endpoint,model,channel,adapter_version,
            simulation,attempt_coverage,prepared_at,started_at,execution_outcome,usage_presence,capture_completeness,projection_state,projection_version)
          values (?,?,?,'CONSISTENCY','test','fixture','test','1',false,'VERIFIED',?,?,'SUCCEEDED','PRESENT','COMPLETE','VALID',1)
          """,attempt,workspace,member,java.sql.Timestamp.from(started),java.sql.Timestamp.from(started));
        String snapshot="snap-"+suffix;
        jdbc.update("insert into usage_snapshot(id,attempt_id,event_id,payload_hash,ordinal,semantic,source_layer,schema_version,adapter_version,capture_version,capture_completeness,raw_state,raw_usage,captured_at) values (?,?,?,'fixture',1,'FINAL','FAKE_RAW','1','1','1','COMPLETE','JSONB','{\"input\":12}'::jsonb,?)",snapshot,attempt,"event-"+suffix,java.sql.Timestamp.from(started));
        jdbc.update("update provider_attempt set projection_snapshot_id=? where id=?",snapshot,attempt);
        when(actor.getCurrentUserId()).thenReturn(manager);
        var detail=graph("{ workspaceUsageAttempt(workspaceId:\""+workspace+"\",id:\""+attempt+"\") { cost { status } snapshots { nodes { rawUsage } } } }");
        assertFalse(detail.has("errors"),detail.toString());
        assertEquals("{\"input\": 12}",detail.path("data").path("workspaceUsageAttempt").path("snapshots").path("nodes").get(0).path("rawUsage").asText());
        var chat=graph("{ conversation(id:\""+conversation+"\") { id } }");
        assertTrue(chat.path("data").path("conversation").isNull(),chat.toString());
        AtomicBoolean changed=new AtomicBoolean();
        doAnswer(invocation -> {
            Object result=invocation.callRealMethod();
            if (changed.compareAndSet(false,true)) CompletableFuture.runAsync(() -> jdbc.update("update workspace_member set role='MEMBER' where workspace_id=? and user_id=?",workspace,manager)).join();
            return result;
        }).when(costs).readCosts(anyList());
        try {
            var revoked=graph("{ workspaceUsageReport(workspaceId:\""+workspace+"\") { attempts { nodes { id cost { status } } } } }");
            assertTrue(revoked.has("errors"),revoked.toString());
            assertEquals("WORKSPACE_ACCESS_DENIED",revoked.path("errors").get(0).path("extensions").path("code").asText());
        } finally { reset(costs); }
    }
    @Test void exclusiveSubMicrosecondClockBoundaryIncludesOnlyEarlierStoredMicros() throws Exception {
        when(actor.getCurrentUserId()).thenReturn("micro-user");
        String workspace=workspaces.personal("micro-user").id();
        String suffix=UUID.randomUUID().toString().substring(0,8);
        for (int i=0;i<2;i++) {
            Instant started=Instant.parse("2026-11-13T04:00:00Z").plusNanos(i*1000L);
            jdbc.update("""
              insert into provider_attempt(id,workspace_id,actor_id,provider,endpoint,model,channel,adapter_version,
                simulation,attempt_coverage,prepared_at,started_at,execution_outcome,usage_presence,capture_completeness,projection_state)
              values (?,?,?,'MICRO_TEST','test','fixture','test','1',false,'VERIFIED',?,?,'SUCCEEDED','UNKNOWN','UNKNOWN','MISSING')
              ""","micro-"+suffix+"-"+i,workspace,"micro-user",java.sql.Timestamp.from(started),java.sql.Timestamp.from(started));
        }
        var response=graph("{ myUsageReport(workspaceId:\""+workspace+"\") { to summary { recordedAttemptCount } } }");
        assertFalse(response.has("errors"),response.toString());
        assertEquals("2026-11-13T04:00:00.000000123Z",response.path("data").path("myUsageReport").path("to").asText());
        assertEquals("1",response.path("data").path("myUsageReport").path("summary").path("recordedAttemptCount").asText());
    }
    @Test void staleCurrentIsExcludedFromReportTotalsAndNestedCost() throws Exception {
        String suffix=UUID.randomUUID().toString().substring(0,8), user="stale-"+suffix;
        when(actor.getCurrentUserId()).thenReturn(user);
        String workspace=workspaces.personal(user).id(), id="attempt-"+suffix, price="price-"+suffix, estimate="est-"+suffix;
        var started=java.sql.Timestamp.from(clock.instant().minusSeconds(60));
        jdbc.update("insert into provider_attempt(id,workspace_id,actor_id,provider,endpoint,model,channel,adapter_version,simulation,attempt_coverage,prepared_at,started_at,execution_outcome,usage_presence,capture_completeness,projection_state,projection_version) values (?,?,?,'STALE','test','fixture','test','1',false,'VERIFIED',?,?,'SUCCEEDED','UNKNOWN','UNKNOWN','MISSING',2)",id,workspace,user,started,started);
        jdbc.update("insert into usage_price_snapshot(id,attempt_id,selection_state,provider,endpoint,model,channel) values (?,?,'MISSING','STALE','test','fixture','test')",price,id);
        jdbc.update("insert into usage_cost_estimate(id,attempt_id,source_version,price_snapshot_id,calculator_version,status,known_subtotal,complete_amount,details,payload_hash) values (?,?,1,?,'test','COMPLETE',12,12,'{\"reasons\":[],\"lines\":[]}'::jsonb,'hash')",estimate,id,price);
        jdbc.update("insert into usage_cost_current(attempt_id,estimate_id) values (?,?)",id,estimate);
        var response=graph("{ myUsageReport(workspaceId:\""+workspace+"\") { summary { knownSubtotal completeTotal unknownCount } attempts { nodes { cost { status knownSubtotal completeAmount needsReprice sourceProjectionVersion estimateSourceVersion currentRevision reasons } } } } }");
        assertFalse(response.has("errors"),response.toString());
        var report=response.path("data").path("myUsageReport");
        var summary=report.path("summary");
        var cost=report.path("attempts").path("nodes").get(0).path("cost");
        assertTrue(summary.path("knownSubtotal").isNull()); assertTrue(summary.path("completeTotal").isNull()); assertEquals("1",summary.path("unknownCount").asText());
        assertEquals("UNKNOWN",cost.path("status").asText()); assertTrue(cost.path("needsReprice").asBoolean());
        assertTrue(cost.path("knownSubtotal").isNull()); assertTrue(cost.path("completeAmount").isNull()); assertTrue(cost.path("currentRevision").isNull());
        assertEquals("2",cost.path("sourceProjectionVersion").asText()); assertEquals("1",cost.path("estimateSourceVersion").asText());
        assertEquals("SOURCE_CHANGED",cost.path("reasons").get(0).asText());
    }
    private JsonNode graph(String query) throws Exception {
        var req=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/graphql")).header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("query",query)))).build();
        return json.readTree(HttpClient.newHttpClient().send(req,HttpResponse.BodyHandlers.ofString()).body());
    }
}
