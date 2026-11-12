package io.github.samzhu.bot;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import io.github.samzhu.bot.identity.CurrentUserProvider;
import java.time.Clock;
import java.time.Instant;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class UsageReportIntegrationTests {
    @org.springframework.test.context.bean.override.convention.TestBean(name = "usageClock", methodName = "fixedReportClock")
    Clock clock;

    static Clock fixedReportClock() {
        return Clock.fixed(Instant.parse("2026-11-13T04:00:00Z"), java.time.ZoneOffset.UTC);
    }
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired io.github.samzhu.bot.workspace.WorkspaceOperations workspaces;
    @MockitoBean CurrentUserProvider actor;
    final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void cleanReportFixtures() {
        String where="provider='REPORT_TEST' or id like 'native-schema-%' or id like 'real-unknown-%' or id like 'sim-unknown-%'";
        jdbc.update("update provider_attempt set projection_snapshot_id=null where "+where);
        jdbc.update("delete from usage_snapshot where attempt_id in (select id from provider_attempt where "+where+")");
        jdbc.update("delete from provider_attempt where "+where);
    }

    @Test
    void myReportCountsOnlyCurrentActorDispatchedAttempts() throws Exception {
        org.mockito.Mockito.when(actor.getCurrentUserId()).thenReturn("dev-user");
        String workspace = workspaces.personal("dev-user").id();
        insertAttempt("report-own-dispatched", workspace, "dev-user", "2026-11-13T01:00:00Z");
        insertAttempt("report-other-dispatched", workspace, "other-user", "2026-11-13T01:00:00Z");
        insertAttempt("report-own-undispatched", workspace, "dev-user", null);

        JsonNode response = graph("query { myUsageReport(workspaceId: \"" + workspace
                + "\", periodDays: 7) { summary { recordedAttemptCount knownSubtotal completeTotal } } }");

        assertFalse(response.has("errors"), response.toString());
        JsonNode summary = response.path("data").path("myUsageReport").path("summary");
        assertEquals("1", summary.path("recordedAttemptCount").asText());
        assertTrue(summary.path("knownSubtotal").isNull());
        assertTrue(summary.path("completeTotal").isNull());
    }

    @Test
    void managerReportIncludesActorsAndUnassignedButMyReportRemainsPrivate() throws Exception {
        String workspace = "report-shared";
        jdbc.update("insert into workspace(id,name) values (?,?) on conflict do nothing", workspace, "Report");
        jdbc.update("""
          insert into workspace_member(workspace_id,user_id,role,active)
          values (?,'report-manager','MANAGER',true),(?,'dev-user','MEMBER',true)
          on conflict (workspace_id,user_id) do update set active=true,role=excluded.role
          """, workspace, workspace);
        insertAttempt("report-manager-own", workspace, "report-manager", "2026-11-13T01:00:00Z");
        insertAttempt("report-member-other", workspace, "dev-user", "2026-11-13T01:00:00Z");
        insertAttempt("report-unassigned", workspace, null, "2026-11-13T01:00:00Z");

        org.mockito.Mockito.when(actor.getCurrentUserId()).thenReturn("report-manager");
        JsonNode manager = graph("{ workspaceUsageReport(workspaceId:\"report-shared\") { summary { recordedAttemptCount unassignedCount } } }");
        assertFalse(manager.has("errors"), manager.toString());
        assertEquals("3", manager.path("data").path("workspaceUsageReport").path("summary").path("recordedAttemptCount").asText());
        org.mockito.Mockito.when(actor.getCurrentUserId()).thenReturn("dev-user");
        JsonNode mine = graph("{ myUsageReport(workspaceId:\"report-shared\") { summary { recordedAttemptCount } } }");
        assertFalse(mine.has("errors"), mine.toString());
        assertEquals("1", mine.path("data").path("myUsageReport").path("summary").path("recordedAttemptCount").asText());
    }

    @Test
    void managerCanReadUsageRawButRoleRevocationDeniesReportAndRaw() throws Exception {
        String workspace = "report-role-scope";
        jdbc.update("insert into workspace(id,name) values (?,?) on conflict do nothing", workspace, "Report");
        jdbc.update("insert into workspace_member(workspace_id,user_id,role,active) values (?,'report-manager','MANAGER',true),(?,'dev-user','MEMBER',true) on conflict (workspace_id,user_id) do update set active=true,role=excluded.role", workspace, workspace);
        insertAttempt("report-raw-target", workspace, "dev-user", "2026-11-13T01:00:00Z");
        jdbc.update("insert into usage_snapshot(id,attempt_id,event_id,payload_hash,ordinal,semantic,source_layer,schema_version,adapter_version,capture_version,capture_completeness,raw_state,raw_usage,captured_at) values ('report-raw-snap','report-raw-target','report-event','hash',1,'FINAL','FAKE_RAW','1','1','1','COMPLETE','JSONB','{\"input\":12}'::jsonb,now())");
        jdbc.update("update provider_attempt set projection_state='VALID',projection_snapshot_id='report-raw-snap',usage_presence='PRESENT' where id='report-raw-target'");
        org.mockito.Mockito.when(actor.getCurrentUserId()).thenReturn("report-manager");
        JsonNode detail = graph("{ workspaceUsageAttempt(workspaceId:\"report-role-scope\",id:\"report-raw-target\") { snapshots { nodes { rawUsage } } } }");
        assertFalse(detail.has("errors"), detail.toString());
        assertEquals("{\"input\": 12}", detail.path("data").path("workspaceUsageAttempt").path("snapshots").path("nodes").get(0).path("rawUsage").asText());
        jdbc.update("update workspace_member set role='MEMBER' where workspace_id=? and user_id='report-manager'", workspace);
        JsonNode denied = graph("{ workspaceUsageReport(workspaceId:\"report-role-scope\") { summary { recordedAttemptCount } } }");
        assertEquals("WORKSPACE_ACCESS_DENIED", denied.path("errors").get(0).path("extensions").path("code").asText());
        jdbc.update("update workspace_member set role='MANAGER',active=false where workspace_id=? and user_id='report-manager'", workspace);
        JsonNode rawDenied = graph("{ workspaceUsageAttempt(workspaceId:\"report-role-scope\",id:\"report-raw-target\") { snapshots { nodes { rawUsage } } } }");
        assertEquals("WORKSPACE_ACCESS_DENIED", rawDenied.path("errors").get(0).path("extensions").path("code").asText());
        jdbc.update("update workspace_member set active=true where workspace_id=? and user_id='report-manager'", workspace);
    }

    @Test
    void reportValidatesPeriodPageAndMutuallyExclusiveFilters() throws Exception {
        org.mockito.Mockito.when(actor.getCurrentUserId()).thenReturn("dev-user");
        String workspace=workspaces.personal("dev-user").id();
        assertCode(graph("{ myUsageReport(workspaceId:\""+workspace+"\",periodDays:8){workspaceId} }"),"INVALID_REPORT_PERIOD");
        assertCode(graph("{ myUsageReport(workspaceId:\""+workspace+"\",first:0){workspaceId} }"),"INVALID_PAGE_SIZE");
        jdbc.update("insert into workspace(id,name) values ('report-manager-filter','Report') on conflict do nothing");
        jdbc.update("insert into workspace_member(workspace_id,user_id,role,active) values ('report-manager-filter','report-manager','MANAGER',true) on conflict(workspace_id,user_id) do update set active=true,role='MANAGER'");
        org.mockito.Mockito.when(actor.getCurrentUserId()).thenReturn("report-manager");
        assertCode(graph("{ workspaceUsageReport(workspaceId:\"report-manager-filter\",memberId:\"dev-user\",unassignedOnly:true){workspaceId} }"),"INVALID_REPORT_FILTER");
    }

    @Test
    void fixedWindowIncludesFromExcludesToAndPreparedOnlyIsNotUsage() throws Exception {
        org.mockito.Mockito.when(actor.getCurrentUserId()).thenReturn("dev-user");
        String workspace=workspaces.personal("dev-user").id();
        insertAttempt("report-at-from",workspace,"dev-user","2026-11-07T16:00:00Z");
        insertAttempt("report-before-to",workspace,"dev-user","2026-11-13T03:59:59.999999Z");
        insertAttempt("report-at-to",workspace,"dev-user","2026-11-13T04:00:00Z");
        insertAttempt("report-prepared-only",workspace,"dev-user",null);
        JsonNode response=graph("{ myUsageReport(workspaceId:\""+workspace+"\",periodDays:7){from to asOf daily{day recordedAttemptCount} summary{recordedAttemptCount}} }");
        assertFalse(response.has("errors"),response.toString());
        JsonNode report=response.path("data").path("myUsageReport");
        assertEquals("2026-11-06T16:00:00Z",report.path("from").asText());
        assertEquals("2026-11-13T04:00:00Z",report.path("to").asText());
        assertEquals("2",report.path("summary").path("recordedAttemptCount").asText());
    }

    @Test
    void cursorPagesAttemptsButSummaryRemainsFullWindowAndCursorKeepsWindow() throws Exception {
        org.mockito.Mockito.when(actor.getCurrentUserId()).thenReturn("dev-user");
        String workspace=workspaces.personal("dev-user").id();
        for(int i=0;i<25;i++) insertAttempt("report-page-"+UUID.randomUUID(),workspace,"dev-user","2026-11-12T12:00:00Z");
        String query="{ myUsageReport(workspaceId:\""+workspace+"\",periodDays:7,first:20){from to asOf summary{recordedAttemptCount} daily{day recordedAttemptCount} attempts{nodes{id} pageInfo{hasNextPage endCursor}}} }";
        JsonNode first=graph(query);
        assertFalse(first.has("errors"),first.toString());
        JsonNode page1=first.path("data").path("myUsageReport");
        assertEquals(20,page1.path("attempts").path("nodes").size());
        assertEquals("25",page1.path("summary").path("recordedAttemptCount").asText());
        String cursor=page1.path("attempts").path("pageInfo").path("endCursor").asText();
        String secondQuery="{ myUsageReport(workspaceId:\""+workspace+"\",periodDays:7,first:20,after:\""+cursor+"\"){from to asOf summary{recordedAttemptCount} daily{day recordedAttemptCount} attempts{nodes{id} pageInfo{hasNextPage}}} }";
        JsonNode second=graph(secondQuery);
        assertFalse(second.has("errors"),second.toString());
        JsonNode page2=second.path("data").path("myUsageReport");
        assertEquals(page1.path("from"),page2.path("from"));
        assertEquals(page1.path("to"),page2.path("to"));
        assertEquals("25",page2.path("summary").path("recordedAttemptCount").asText());
        assertEquals(page1.path("daily"),page2.path("daily"));
        assertEquals(5,page2.path("attempts").path("nodes").size());
        assertFalse(page2.path("attempts").path("pageInfo").path("hasNextPage").asBoolean());
        assertEquals("2026-11-13T04:00:00Z",page2.path("asOf").asText());
    }

    @Test
    void cursorTamperingAndFilterOrActorSwitchAreRejected() throws Exception {
        org.mockito.Mockito.when(actor.getCurrentUserId()).thenReturn("dev-user");
        String workspace=workspaces.personal("dev-user").id();
        insertAttempt("report-cursor-a-"+UUID.randomUUID(),workspace,"dev-user","2026-11-12T12:00:00Z");
        insertAttempt("report-cursor-b-"+UUID.randomUUID(),workspace,"dev-user","2026-11-12T12:00:00Z");
        JsonNode first=graph("{ myUsageReport(workspaceId:\""+workspace+"\",periodDays:7,first:1){attempts{pageInfo{endCursor}}} }");
        assertFalse(first.has("errors"),first.toString());
        String cursor=first.path("data").path("myUsageReport").path("attempts").path("pageInfo").path("endCursor").asText();
        String altered=cursor.substring(0,cursor.length()-1)+(cursor.endsWith("A")?"B":"A");
        assertCode(graph("{ myUsageReport(workspaceId:\""+workspace+"\",periodDays:7,first:1,after:\""+altered+"\"){workspaceId} }"),"INVALID_CURSOR");
        assertCode(graph("{ myUsageReport(workspaceId:\""+workspace+"\",periodDays:7,provider:\"OTHER\",first:1,after:\""+cursor+"\"){workspaceId} }"),"INVALID_CURSOR");
        org.mockito.Mockito.when(actor.getCurrentUserId()).thenReturn("different-actor");
        assertCode(graph("{ myUsageReport(workspaceId:\""+workspace+"\",periodDays:7,first:1,after:\""+cursor+"\"){workspaceId} }"),"WORKSPACE_ACCESS_DENIED");
    }

    @Test
    void nativeMetricsPreserveZeroNullMissingLargeIntegersAndSplitSchemaGroups() throws Exception {
        org.mockito.Mockito.when(actor.getCurrentUserId()).thenReturn("dev-user");
        String workspace=workspaces.personal("dev-user").id();
        insertRaw("native-schema-a",workspace,"dev-user","GOOGLE","schema-a","{\"promptTokenCount\":0,\"candidatesTokenCount\":null,\"cachedContentTokenCount\":9007199254740993}",false);
        insertRaw("native-schema-b",workspace,"dev-user","GOOGLE","schema-b","{\"promptTokenCount\":7}",false);
        JsonNode response=graph("{ myUsageReport(workspaceId:\""+workspace+"\",periodDays:7){nativeGroups{provider schemaVersion input{knownValue completeValue unknownCount} output{knownValue completeValue unknownCount} cacheRead{knownValue completeValue unknownCount}} summary{knownSubtotal completeTotal unknownCount}} }");
        assertFalse(response.has("errors"),response.toString());
        JsonNode groups=response.path("data").path("myUsageReport").path("nativeGroups");
        assertEquals(2,groups.size());
        JsonNode a=groups.findValue("schemaVersion").asText().equals("schema-a")?groups.get(0):groups.get(1);
        assertEquals("0",a.path("input").path("knownValue").asText());
        assertTrue(a.path("output").path("knownValue").isNull());
        assertEquals("1",a.path("output").path("unknownCount").asText());
        assertEquals("9007199254740993",a.path("cacheRead").path("knownValue").asText());
        assertTrue(response.path("data").path("myUsageReport").path("summary").path("completeTotal").isNull());
    }

    @Test
    void simulationAndRealCostsStaySeparateAndUnknownCostsNeverBecomeZero() throws Exception {
        org.mockito.Mockito.when(actor.getCurrentUserId()).thenReturn("dev-user");
        String workspace=workspaces.personal("dev-user").id();
        insertAttempt("real-unknown-"+UUID.randomUUID(),workspace,"dev-user","2026-11-12T12:00:00Z");
        insertAttempt("sim-unknown-"+UUID.randomUUID(),workspace,"dev-user","2026-11-12T12:00:00Z",true);
        JsonNode real=graph("{ myUsageReport(workspaceId:\""+workspace+"\",simulation:false){simulation summary{knownSubtotal completeTotal unknownCount recordedAttemptCount}} }");
        JsonNode sim=graph("{ myUsageReport(workspaceId:\""+workspace+"\",simulation:true){simulation summary{knownSubtotal completeTotal unknownCount recordedAttemptCount}} }");
        assertFalse(real.has("errors"),real.toString()); assertFalse(sim.has("errors"),sim.toString());
        assertFalse(real.path("data").path("myUsageReport").path("simulation").asBoolean());
        assertTrue(sim.path("data").path("myUsageReport").path("simulation").asBoolean());
        for(JsonNode node:List.of(real.path("data").path("myUsageReport").path("summary"),sim.path("data").path("myUsageReport").path("summary"))) {
            assertTrue(node.path("knownSubtotal").isNull());
            assertTrue(node.path("completeTotal").isNull());
            assertEquals("1",node.path("unknownCount").asText());
        }
    }

    private void insertAttempt(String id,String workspace,String actor,String startedAt,boolean simulation) {
        jdbc.update("insert into provider_attempt(id,workspace_id,actor_id,provider,endpoint,model,channel,adapter_version,simulation,attempt_coverage,prepared_at,started_at,execution_outcome,usage_presence,capture_completeness,projection_state) values (?,?,?,'REPORT_TEST','test','test-model','test','test',?,'VERIFIED',now(),?::timestamptz,'SUCCEEDED','ABSENT','COMPLETE','MISSING')",id,workspace,actor,simulation,startedAt);
    }

    private void insertRaw(String id,String workspace,String actor,String provider,String schema,String raw,boolean simulation) {
        jdbc.update("insert into provider_attempt(id,workspace_id,actor_id,provider,endpoint,model,channel,adapter_version,simulation,attempt_coverage,prepared_at,started_at,execution_outcome,usage_presence,capture_completeness,projection_state) values (?,?,?,?,'generateContent','test-model','test','test',?,'UNKNOWN',now(),'2026-11-12T12:00:00Z','SUCCEEDED','PRESENT','COMPLETE','VALID')",id,workspace,actor,provider,simulation);
        String snapshot="snap-"+id;
        jdbc.update("insert into usage_snapshot(id,attempt_id,event_id,payload_hash,ordinal,semantic,source_layer,schema_version,adapter_version,capture_version,capture_completeness,raw_state,raw_usage,captured_at) values (?,?,?, ?,1,'FINAL','SDK_NATIVE',?,'1','1','COMPLETE','JSONB',?::jsonb,now())",snapshot,id,"event-"+id,"hash-"+id,schema,raw);
        jdbc.update("update provider_attempt set projection_snapshot_id=? where id=?",snapshot,id);
    }

    private void assertCode(JsonNode response,String code) {
        assertEquals(code,response.path("errors").get(0).path("extensions").path("code").asText(),response.toString());
    }

    private void insertAttempt(String id, String workspace, String actor, String startedAt) {
        jdbc.update("""
                insert into provider_attempt(id,workspace_id,actor_id,provider,endpoint,model,channel,
                  adapter_version,simulation,attempt_coverage,prepared_at,started_at,execution_outcome,
                  usage_presence,capture_completeness,projection_state)
                values (?,?,?,'REPORT_TEST','test','test-model','test','test',false,'VERIFIED',
                  now(),?::timestamptz,'SUCCEEDED','ABSENT','COMPLETE','MISSING')
                """, id, workspace, actor, startedAt);
    }

    private JsonNode graph(String query) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/graphql"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("query", query))))
                .build();
        return json.readTree(HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString()).body());
    }
}
