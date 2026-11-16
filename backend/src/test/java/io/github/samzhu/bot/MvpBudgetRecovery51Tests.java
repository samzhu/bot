package io.github.samzhu.bot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.convention.TestBean;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={"bot.pricing.reconcile.initial-delay-ms=3600000"})
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class MvpBudgetRecovery51Tests {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @TestBean(name="mockChatModel",methodName="model",enforceOverride=true) ChatModel model;
    @TestBean(name="usageClock",methodName="clock",enforceOverride=true) Clock clock;
    static ControlledChatModel model(){return ControlledChatModel.getInstance();}
    static final java.util.concurrent.atomic.AtomicReference<Instant> NOW = new java.util.concurrent.atomic.AtomicReference<>(Instant.parse("2026-11-16T04:00:00Z"));
    static Clock clock(){return new Clock(){
        public java.time.ZoneId getZone(){return ZoneOffset.UTC;}
        public Clock withZone(java.time.ZoneId zone){return Clock.fixed(NOW.get(),zone);}
        public Instant instant(){return NOW.get();}
    };}
    final ObjectMapper json=new ObjectMapper();
    final HttpClient http=HttpClient.newHttpClient();
    @BeforeEach void reset(){NOW.set(Instant.parse("2026-11-16T04:00:00Z"));model().reset();jdbc.update("insert into bot(id,name,system_prompt,created_at) values ('bot-general','General','test',current_timestamp) on conflict(id) do nothing");}
    HttpResponse<String> post(String path,Object body,String accept) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).header("Content-Type","application/json").header("Accept",accept).POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
    }
    JsonNode graph(String query) throws Exception {
        var result=json.readTree(post("/graphql",Map.of("query",query),"application/json").body());
        assertFalse(result.has("errors"),result.toString());return result.path("data");
    }
    HttpResponse<String> start(String conversation) throws Exception {
        return post("/api/ag-ui",Map.of("threadId",conversation,"runId",UUID.randomUUID().toString(),"messages",List.of(Map.of("id",UUID.randomUUID().toString(),"role","user","content","fixture request")),"tools",List.of()),"text/event-stream");
    }
    @Test void unknownDenialReconcilesAndAllowsSameEnabledBudgetWithoutReset() throws Exception {
        String s=UUID.randomUUID().toString(),w="mvp51-"+s,snap="snapshot-"+s,price="price-"+s,old="old-"+s,cat="catalog-"+s;
        jdbc.update("insert into workspace(id,name) values (?,?)",w,"fixture");
        jdbc.update("insert into workspace_member(workspace_id,user_id,role,active) values (?,'dev-user','MANAGER',true)",w);
        String conv=graph("mutation { createConversation(botId:\"bot-general\",title:\"fixture\",workspaceId:\""+w+"\"){id} }").path("createConversation").path("id").asText();assertFalse(conv.isBlank());
        var seed=start(conv);assertEquals(200,seed.statusCode(),seed.body());assertTrue(seed.body().contains("RUN_FINISHED"));
        String a=jdbc.queryForObject("select id from provider_attempt where workspace_id=?",String.class,w);
        int historyRuns=jdbc.queryForObject("select count(*) from chat_run where conversation_id=?",Integer.class,conv);
        int historyMessages=jdbc.queryForObject("select count(*) from message where conversation_id=?",Integer.class,conv);
        assertEquals(1,historyRuns);assertTrue(historyMessages>0);
        String historyQuery="{ conversation(id:\""+conv+"\"){id messages(limit:20,offset:0){id role content} runs(limit:20,offset:0){id status}} }";
        JsonNode history=graph(historyQuery).path("conversation").deepCopy();
        assertEquals("SUCCEEDED",jdbc.queryForObject("select execution_outcome from provider_attempt where id=?",String.class,a));
        // The Fake run creates the authoritative unknown gate; the following trusted synthetic
        // raw and missing-price history are explicit fixture setup for the public reconcile command.
        String raw="{\"promptTokenCount\":1000,\"cachedContentTokenCount\":200,\"candidatesTokenCount\":300,\"thoughtsTokenCount\":100,\"toolUsePromptTokenCount\":0,\"totalTokenCount\":1400}";
        long version=jdbc.queryForObject("select projection_version from provider_attempt where id=?",Long.class,a);
        jdbc.update("insert into usage_snapshot(id,attempt_id,event_id,payload_hash,ordinal,semantic,source_layer,schema_version,adapter_version,capture_version,capture_completeness,raw_state,raw_usage,captured_at) values (?,?,?,'fixture',1,'FINAL','FAKE_RAW','pricing-fixture-v1','1','1','COMPLETE','JSONB',?::jsonb,?)",snap,a,"event-"+s,raw,java.sql.Timestamp.from(clock.instant()));
        jdbc.update("update provider_attempt set provider='FAKE_GEMINI',model='fixture-gemini',endpoint='generateContent',channel='FAKE',attempt_coverage='VERIFIED',usage_presence='PRESENT',capture_completeness='COMPLETE',projection_state='VALID',projection_snapshot_id=? where id=?",snap,a);
        jdbc.update("insert into usage_price_snapshot(id,attempt_id,selection_state,provider,endpoint,model,channel) values (?,?,'MISSING','FAKE_GEMINI','generateContent','fixture-gemini','FAKE')",price,a);
        jdbc.update("insert into usage_cost_estimate(id,attempt_id,source_version,source_snapshot_id,price_snapshot_id,calculator_version,status,details,payload_hash) values (?,?,?,?,?,'old','UNKNOWN','{\"reasons\":[\"MISSING_PRICE\"],\"lines\":[]}'::jsonb,'old-hash')",old,a,version,snap,price);
        jdbc.update("insert into usage_cost_current(attempt_id,estimate_id) values (?,?) on conflict(attempt_id) do update set estimate_id=excluded.estimate_id",a,old);
        jdbc.update("insert into pricing_catalog_revision(id,source_reference,source_revision,content_hash) values (?,'fixture',?,'hash')",cat,"revision-"+s);
        jdbc.update("insert into pricing_catalog_entry(id,revision_id,provider,endpoint,model,channel,price_revision,effective_from,effective_until,rates) values (?,?,'FAKE_GEMINI','generateContent','fixture-gemini','FAKE','fixture-v2','2026-01-01T00:00:00Z','2027-01-01T00:00:00Z','{\"input\":\"1\",\"cache_read\":\"0.1\",\"output\":\"2\",\"thoughts\":\"2\",\"tool_input\":\"1\"}'::jsonb)","entry-"+s,cat);
        var configured=graph("mutation { configureWorkspaceMonthlyBudget(input:{workspaceId:\""+w+"\",simulation:true,enabled:true,capUsd:\"100\",expectedConfigVersion:\"0\",idempotencyKey:\"budget\"}){budget{enabled capUsd configVersion period terminalUnknownCount state}} }").path("configureWorkspaceMonthlyBudget").path("budget");
        assertTrue(configured.path("enabled").asBoolean());assertEquals("1",configured.path("terminalUnknownCount").asText());
        model().reset();var denied=start(conv);assertEquals(503,denied.statusCode());assertEquals("MONTH_UNKNOWN",json.readTree(denied.body()).path("code").asText());assertTrue(model().getRecordedPrompts().isEmpty());
        assertEquals(historyRuns,jdbc.queryForObject("select count(*) from chat_run where conversation_id=?",Integer.class,conv));
        assertEquals(historyMessages,jdbc.queryForObject("select count(*) from message where conversation_id=?",Integer.class,conv));
        assertEquals(history,graph(historyQuery).path("conversation"));
        String command="mutation { reconcileCost(input:{workspaceId:\""+w+"\",attemptId:\""+a+"\",expectedRevision:\""+old+"\",expectedSourceProjectionVersion:\""+version+"\",idempotencyKey:\"reconcile\",catalogRevisionId:\""+cat+"\",evidenceReference:\"fixture\",reason:\"verified\"}){outcome revision{id status completeAmount}} }";
        var revision=graph(command).path("reconcileCost");assertEquals("APPLIED",revision.path("outcome").asText());assertEquals("COMPLETE",revision.path("revision").path("status").asText());assertEquals("0.001620000000",revision.path("revision").path("completeAmount").asText());
        var replay=graph(command).path("reconcileCost");assertEquals("REPLAYED",replay.path("outcome").asText());
        assertEquals(revision.path("revision").path("id"),replay.path("revision").path("id"));
        assertEquals(revision.path("revision").path("id").asText(),jdbc.queryForObject("select estimate_id from usage_cost_current where attempt_id=?",String.class,a));
        assertEquals(historyRuns,jdbc.queryForObject("select count(*) from chat_run where conversation_id=?",Integer.class,conv));
        assertEquals(historyMessages,jdbc.queryForObject("select count(*) from message where conversation_id=?",Integer.class,conv));
        assertEquals(history,graph(historyQuery).path("conversation"));
        var budget=graph("{ workspaceMonthlyBudget(workspaceId:\""+w+"\",simulation:true){enabled capUsd configVersion period terminalUnknownCount state} }").path("workspaceMonthlyBudget");assertTrue(budget.path("enabled").asBoolean());assertEquals(configured.path("period"),budget.path("period"));assertEquals(configured.path("capUsd"),budget.path("capUsd"));assertEquals(configured.path("configVersion"),budget.path("configVersion"));assertEquals("0",budget.path("terminalUnknownCount").asText());assertEquals("OPEN",budget.path("state").asText());
        assertTrue(jdbc.queryForObject("select raw_usage=?::jsonb from usage_snapshot where id=?",Boolean.class,raw,snap));assertEquals("MISSING",jdbc.queryForObject("select selection_state from usage_price_snapshot where id=?",String.class,price));assertEquals(1,jdbc.queryForObject("select count(*) from usage_cost_revision_audit where attempt_id=?",Integer.class,a));assertEquals(old,jdbc.queryForObject("select id from usage_cost_estimate where id=?",String.class,old));
        var allowed=start(conv);assertEquals(200,allowed.statusCode(),allowed.body());assertTrue(allowed.body().contains("RUN_STARTED"));assertFalse(model().getRecordedPrompts().isEmpty());
    }
    @Test void reportFiltersDoNotChangeUnknownBudgetOrAdmission() throws Exception {
        String s=UUID.randomUUID().toString(),w="filter51-"+s,a="known-"+s,estimate="estimate-"+s;
        jdbc.update("insert into workspace(id,name) values (?,?)",w,"fixture");
        jdbc.update("insert into workspace_member(workspace_id,user_id,role,active) values (?,'dev-user','MANAGER',true)",w);
        var now=java.sql.Timestamp.from(clock.instant());
        // Known historical estimate is synthetic data, inserted before the Fake run so the
        // real terminal admission transition recomputes the complete authoritative projection.
        jdbc.update("insert into provider_attempt(id,workspace_id,actor_id,provider,endpoint,model,channel,adapter_version,simulation,attempt_coverage,prepared_at,started_at,finished_at,execution_outcome,usage_presence,capture_completeness,projection_state,projection_version) values (?,?,'dev-user','FAKE_GEMINI','generateContent','known-model','FAKE','1',true,'VERIFIED',?,?,?,'SUCCEEDED','PRESENT','COMPLETE','VALID',1)",a,w,now,now,now);
        jdbc.update("insert into usage_cost_estimate(id,attempt_id,source_version,calculator_version,status,known_subtotal,complete_amount,details,payload_hash) values (?,?,1,'fixture','COMPLETE',0.25,0.25,'{\"reasons\":[],\"lines\":[]}'::jsonb,'known-hash')",estimate,a);
        jdbc.update("insert into usage_cost_current(attempt_id,estimate_id) values (?,?)",a,estimate);
        String conv=graph("mutation { createConversation(botId:\"bot-general\",title:\"filter fixture\",workspaceId:\""+w+"\"){id} }").path("createConversation").path("id").asText();assertFalse(conv.isBlank());
        var seeded=start(conv);assertEquals(200,seeded.statusCode(),seeded.body());assertTrue(seeded.body().contains("RUN_FINISHED"));
        NOW.set(NOW.get().plusSeconds(1));
        var configured=graph("mutation { configureWorkspaceMonthlyBudget(input:{workspaceId:\""+w+"\",simulation:true,enabled:true,capUsd:\"100\",expectedConfigVersion:\"0\",idempotencyKey:\"budget\"}){budget{enabled period terminalUnknownCount state reason knownSubtotalUsd}} }").path("configureWorkspaceMonthlyBudget").path("budget");
        assertEquals("1",configured.path("terminalUnknownCount").asText());assertEquals("BLOCKED_UNKNOWN",configured.path("state").asText());assertEquals("0.250000000000",configured.path("knownSubtotalUsd").asText());
        model().reset();var before=start(conv);assertEquals(503,before.statusCode());assertEquals("MONTH_UNKNOWN",json.readTree(before.body()).path("code").asText());assertTrue(model().getRecordedPrompts().isEmpty());
        var all=graph("{ workspaceUsageReport(workspaceId:\""+w+"\",periodDays:7,simulation:true){summary{knownSubtotal unknownCount recordedAttemptCount}} }").path("workspaceUsageReport").path("summary");
        var filtered=graph("{ workspaceUsageReport(workspaceId:\""+w+"\",periodDays:7,simulation:true,provider:\"FAKE_GEMINI\",model:\"known-model\"){summary{knownSubtotal unknownCount recordedAttemptCount}} }").path("workspaceUsageReport").path("summary");
        assertEquals("2",all.path("recordedAttemptCount").asText());assertEquals("1",all.path("unknownCount").asText());assertEquals("1",filtered.path("recordedAttemptCount").asText());assertEquals("0",filtered.path("unknownCount").asText());assertEquals("0.250000000000",filtered.path("knownSubtotal").asText());
        var after=graph("{ workspaceMonthlyBudget(workspaceId:\""+w+"\",simulation:true){enabled period terminalUnknownCount state reason knownSubtotalUsd} }").path("workspaceMonthlyBudget");assertEquals(configured,after);
        var denied=start(conv);assertEquals(503,denied.statusCode());assertEquals("MONTH_UNKNOWN",json.readTree(denied.body()).path("code").asText());assertTrue(model().getRecordedPrompts().isEmpty());
    }

}
