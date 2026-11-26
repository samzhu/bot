package io.github.samzhu.bot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genai.types.GenerateContentResponseUsageMetadata;
import io.github.samzhu.bot.usage.pricing.UsageCaseData;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.*;

@BackendIntegrationTest
class UsageReportIntegrationTests {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate transactions;
    private UsageCaseData data;
    @Autowired BackendTestChatModel model;

    final ObjectMapper json = new ObjectMapper();
    final String recentStart = Instant.now().minusSeconds(60).toString();

    @BeforeEach
    void setUp() {
        data = new UsageCaseData(jdbc, transactions);
        model.reset();
    }

    @AfterEach
    void cleanUp() throws InterruptedException {
        try {
            data.cleanUp();
        } finally {
            model.reset();
        }
    }

    private String createWorkspace() {
        String workspace = "usage-report-" + UUID.randomUUID();
        data.createWorkspace(workspace, "dev-user");
        return workspace;
    }

    @Test
    void streamedModelUsageAppearsInReportAndInThePersistedCost() throws Exception {
        String workspace = createWorkspace();
        JsonNode create = graph("""
                mutation { createConversation(botId:"bot-general",workspaceId:"%s",title:"Usage fixture") { id } }
                """.formatted(workspace));
        assertFalse(create.has("errors"), create.toString());
        String conversation = create.path("data").path("createConversation").path("id").asText();
        assertFalse(conversation.isBlank());
        String run = UUID.randomUUID().toString();

        var usage = GenerateContentResponseUsageMetadata.builder()
                .promptTokenCount(1000).cachedContentTokenCount(200).candidatesTokenCount(300)
                .thoughtsTokenCount(100).toolUsePromptTokenCount(0).totalTokenCount(1400).build();
        var metadata = ChatResponseMetadata.builder().usage(new DefaultUsage(1000, 300, 1400, usage)).build();
        var reply = new ChatResponse(List.of(new Generation(new AssistantMessage("Usage fixture reply"))), metadata);
        model.respondWith(reply, List.of(reply));
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/ag-ui"))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of(
                        "threadId", conversation, "runId", run, "state", Map.of(),
                        "messages", List.of(Map.of("id", UUID.randomUUID().toString(),
                                "role", "user", "content", "Record synthetic usage")),
                        "tools", List.of(), "context", List.of(), "forwardedProps", Map.of()))))
                .build();
        try (var client = HttpClient.newHttpClient()) {
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode(), response.body());
            assertTrue(response.body().contains("RUN_FINISHED"), response.body());
            assertFalse(response.body().contains("RUN_ERROR"), response.body());
        }
        assertEquals(1, model.invocations());

        var result = graph("""
                { myUsageReport(workspaceId:"%s") {
                    summary { recordedAttemptCount knownSubtotal completeTotal coverageUnknownCount }
                    nativeGroups { provider input { knownValue } output { knownValue } cacheRead { knownValue } }
                    attempts { nodes { id runId startedAt effectiveSnapshotId usagePresence projectionVersion cost { status currentRevision } } }
                  } }
                """.formatted(workspace));
        assertFalse(result.has("errors"), result.toString());
        var report = result.path("data").path("myUsageReport");
        var summary = report.path("summary");
        assertEquals("1", summary.path("recordedAttemptCount").asText());
        assertEquals("1", summary.path("coverageUnknownCount").asText());
        assertTrue(summary.path("completeTotal").isNull());
        var nativeUsage = report.path("nativeGroups").get(0);
        assertEquals("GOOGLE", nativeUsage.path("provider").asText());
        assertEquals("1000", nativeUsage.path("input").path("knownValue").asText());
        assertEquals("300", nativeUsage.path("output").path("knownValue").asText());
        assertEquals("200", nativeUsage.path("cacheRead").path("knownValue").asText());
        var attempt = report.path("attempts").path("nodes").get(0);
        assertEquals(run, attempt.path("runId").asText());
        assertEquals("PRESENT", attempt.path("usagePresence").asText());
        var catalogResponse = graph("""
                { publishedPricingCatalogs(workspaceId:"%s") { nodes { id sourceRevision } } }
                """.formatted(workspace));
        assertFalse(catalogResponse.has("errors"), catalogResponse.toString());
        var catalogNodes = catalogResponse.path("data").path("publishedPricingCatalogs").path("nodes");
        String referenceCatalog = StreamSupport.stream(catalogNodes.spliterator(), false)
                .filter(node -> "gemini-standard-2026-11-16".equals(node.path("sourceRevision").asText()))
                .findFirst().orElseThrow().path("id").asText();
        assertEquals("UNPRICED", attempt.path("cost").path("status").asText());
        assertTrue(attempt.path("cost").path("currentRevision").isNull());

        // The shared test profile disables background pricing. Price the captured usage using the public command.
        var reconciliation = graph("""
                mutation { reconcileCost(input:{
                    workspaceId:"%s", attemptId:"%s", expectedRevision:null,
                    expectedSourceProjectionVersion:"%s", idempotencyKey:"stream-usage",
                    catalogRevisionId:"%s", evidenceReference:"synthetic-stream", reason:"price captured model usage"
                }) { revision { id status knownSubtotal completeAmount sourceSnapshotId catalogRevisionId } } }
                """.formatted(workspace, attempt.path("id").asText(),
                        attempt.path("projectionVersion").asText(), referenceCatalog));
        assertFalse(reconciliation.has("errors"), reconciliation.toString());
        var revision = reconciliation.path("data").path("reconcileCost").path("revision");
        assertEquals("PARTIAL", revision.path("status").asText());
        assertTrue(revision.path("completeAmount").isNull());
        assertEquals(attempt.path("effectiveSnapshotId"), revision.path("sourceSnapshotId"));
        assertEquals(referenceCatalog, revision.path("catalogRevisionId").asText());

        // Seeded Standard rates yield (800 × 0.75 + 200 × 0.075 + 400 × 3.75) / 1,000,000;
        // the seed doubles those rates from 2027-01-01 UTC.
        Instant startedAt = Instant.parse(attempt.path("startedAt").asText());
        BigDecimal expectedSubtotal = new BigDecimal(startedAt.isBefore(Instant.parse("2027-01-01T00:00:00Z"))
                ? "0.002115" : "0.004230");
        assertSubtotal(expectedSubtotal, revision);
        var priced = graph("""
                { myUsageReport(workspaceId:"%s") {
                    summary { knownSubtotal completeTotal }
                    attempts { nodes { cost { currentRevision knownSubtotal completeAmount } } }
                  } }
                """.formatted(workspace));
        assertFalse(priced.has("errors"), priced.toString());
        var pricedReport = priced.path("data").path("myUsageReport");
        assertSubtotal(expectedSubtotal, pricedReport.path("summary"));
        assertTrue(pricedReport.path("summary").path("completeTotal").isNull());
        var pricedAttempt = pricedReport.path("attempts").path("nodes").get(0).path("cost");
        assertEquals(revision.path("id"), pricedAttempt.path("currentRevision"));
        assertSubtotal(expectedSubtotal, pricedAttempt);
        assertTrue(pricedAttempt.path("completeAmount").isNull());

        var persisted = jdbc.queryForMap("""
                select s.id as snapshot_id,s.source_layer,s.raw_usage ->> 'promptTokenCount' as input,e.known_subtotal
                  from provider_attempt a
                  join usage_snapshot s on s.id=a.projection_snapshot_id
                  join usage_cost_current c on c.attempt_id=a.id
                  join usage_cost_estimate e on e.id=c.estimate_id
                 where a.workspace_id=? and a.run_id=? and a.id=? and a.execution_outcome='SUCCEEDED'
                """, workspace, run, attempt.path("id").asText());
        assertEquals(attempt.path("effectiveSnapshotId").asText(), persisted.get("snapshot_id"));
        assertEquals("SDK_NATIVE", persisted.get("source_layer"));
        assertEquals("1000", persisted.get("input"));
        assertEquals(0, expectedSubtotal.compareTo((BigDecimal) persisted.get("known_subtotal")));
    }

    @Test
    void myReportCountsOnlyCurrentActorDispatchedAttempts() throws Exception {
        String workspace = createWorkspace();
        insertAttempt("report-own-dispatched-" + UUID.randomUUID(), workspace, "dev-user", recentStart);
        insertAttempt("report-other-dispatched-" + UUID.randomUUID(), workspace, "other-user", recentStart);
        insertAttempt("report-own-undispatched-" + UUID.randomUUID(), workspace, "dev-user", null);

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
        String workspace = createWorkspace();
        jdbc.update("insert into workspace_member(workspace_id,user_id,role,active) values (?,'other-user','MEMBER',true)", workspace);
        insertAttempt("report-manager-own-" + UUID.randomUUID(), workspace, "dev-user", recentStart);
        insertAttempt("report-member-other-" + UUID.randomUUID(), workspace, "other-user", recentStart);
        insertAttempt("report-unassigned-" + UUID.randomUUID(), workspace, null, recentStart);

        JsonNode manager = graph("{ workspaceUsageReport(workspaceId:\""+workspace+"\") { summary { recordedAttemptCount unassignedCount } } }");
        assertFalse(manager.has("errors"), manager.toString());
        var managerSummary = manager.path("data").path("workspaceUsageReport").path("summary");
        assertEquals("3", managerSummary.path("recordedAttemptCount").asText());
        assertEquals("1", managerSummary.path("unassignedCount").asText());
        JsonNode mine = graph("{ myUsageReport(workspaceId:\""+workspace+"\") { summary { recordedAttemptCount } } }");
        assertFalse(mine.has("errors"), mine.toString());
        assertEquals("1", mine.path("data").path("myUsageReport").path("summary").path("recordedAttemptCount").asText());
    }

    @Test
    void managerCanReadUsageRawButRoleRevocationDeniesReportAndRaw() throws Exception {
        String workspace = createWorkspace();
        String attempt = "report-raw-" + UUID.randomUUID();
        String snapshot = "snap-" + attempt;
        insertAttempt(attempt, workspace, "other-user", recentStart);
        jdbc.update("""
                insert into usage_snapshot(id,attempt_id,event_id,payload_hash,ordinal,semantic,source_layer,
                  schema_version,adapter_version,capture_version,capture_completeness,raw_state,raw_usage,captured_at)
                values (?,?,?,'hash',1,'FINAL','FAKE_RAW','1','1','1','COMPLETE','JSONB','{"input":12}'::jsonb,now())
                """, snapshot, attempt, "event-" + attempt);
        jdbc.update("update provider_attempt set projection_state='VALID',projection_snapshot_id=?,usage_presence='PRESENT' where id=?",
                snapshot, attempt);
        String rawQuery = "{ workspaceUsageAttempt(workspaceId:\""+workspace+"\",id:\""+attempt+"\") { snapshots { nodes { rawUsage } } } }";
        JsonNode detail = graph(rawQuery);
        assertFalse(detail.has("errors"), detail.toString());
        assertEquals("{\"input\": 12}", detail.path("data").path("workspaceUsageAttempt").path("snapshots").path("nodes").get(0).path("rawUsage").asText());
        jdbc.update("update workspace_member set role='MEMBER' where workspace_id=? and user_id='dev-user'", workspace);
        assertCode(graph("{ workspaceUsageReport(workspaceId:\""+workspace+"\") { summary { recordedAttemptCount } } }"),
                "WORKSPACE_ACCESS_DENIED");
        jdbc.update("update workspace_member set role='MANAGER',active=false where workspace_id=? and user_id='dev-user'", workspace);
        assertCode(graph(rawQuery), "WORKSPACE_ACCESS_DENIED");
    }

    @Test
    void reportValidatesPeriodPageAndMutuallyExclusiveFilters() throws Exception {
        String workspace=createWorkspace();
        assertCode(graph("{ myUsageReport(workspaceId:\""+workspace+"\",periodDays:8){workspaceId} }"),"INVALID_REPORT_PERIOD");
        assertCode(graph("{ myUsageReport(workspaceId:\""+workspace+"\",first:0){workspaceId} }"),"INVALID_PAGE_SIZE");
        assertCode(graph("{ workspaceUsageReport(workspaceId:\""+workspace+"\",memberId:\"dev-user\",unassignedOnly:true){workspaceId} }"),"INVALID_REPORT_FILTER");
    }

    @Test
    void fixedWindowIncludesFromExcludesToAndPreparedOnlyIsNotUsage() throws Exception {
        String workspace = createWorkspace();
        String suffix = UUID.randomUUID().toString();
        String anchor = "at-from-" + suffix;
        // The first result supplies the server clock and a cursor that pins the exclusive upper bound.
        insertAttempt(anchor, workspace, "dev-user", recentStart);
        String reportQuery = "{ myUsageReport(workspaceId:\""+workspace+"\",periodDays:7%s)"
                + "{from to asOf summary{recordedAttemptCount} attempts{pageInfo{endCursor}}} }";
        JsonNode first = graph(reportQuery.formatted(""));
        assertFalse(first.has("errors"), first.toString());
        JsonNode report = first.path("data").path("myUsageReport");
        assertEquals("1", report.path("summary").path("recordedAttemptCount").asText());
        Instant from = Instant.parse(report.path("from").asText());
        Instant to = Instant.parse(report.path("to").asText());
        ZoneId taipei = ZoneId.of("Asia/Taipei");
        assertEquals(LocalDate.ofInstant(to, taipei).minusDays(6).atStartOfDay(taipei).toInstant(), from);
        assertEquals(report.path("to"), report.path("asOf"));
        jdbc.update("update provider_attempt set started_at=?::timestamptz where id=?", from.toString(), anchor);

        Instant lastIncluded = to.minusNanos(1).truncatedTo(ChronoUnit.MICROS);
        insertAttempt("last-before-" + suffix, workspace, "dev-user", lastIncluded.toString());
        insertAttempt("at-upper-" + suffix, workspace, "dev-user", lastIncluded.plus(1, ChronoUnit.MICROS).toString());
        insertAttempt("before-from-" + suffix, workspace, "dev-user", from.minus(1, ChronoUnit.MICROS).toString());
        insertAttempt("prepared-only-" + suffix, workspace, "dev-user", null);
        String cursor = report.path("attempts").path("pageInfo").path("endCursor").asText();
        JsonNode fixed = graph(reportQuery.formatted(",after:\"" + cursor + "\""));
        assertFalse(fixed.has("errors"), fixed.toString());
        JsonNode fixedReport = fixed.path("data").path("myUsageReport");
        assertEquals(report.path("from"), fixedReport.path("from"));
        assertEquals(report.path("to"), fixedReport.path("to"));
        assertEquals("2", fixedReport.path("summary").path("recordedAttemptCount").asText());
    }

    @Test
    void cursorPagesAttemptsButSummaryRemainsFullWindowAndCursorKeepsWindow() throws Exception {
        String workspace=createWorkspace();
        for(int i=0;i<25;i++) insertAttempt("report-page-"+UUID.randomUUID(),workspace,"dev-user",recentStart);
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
        assertFalse(Instant.parse(page2.path("asOf").asText()).isBefore(Instant.parse(page1.path("asOf").asText())));
    }

    @Test
    void cursorTamperingFilterChangesAndMembershipRevocationAreRejected() throws Exception {
        String workspace=createWorkspace();
        insertAttempt("report-cursor-a-"+UUID.randomUUID(),workspace,"dev-user",recentStart);
        insertAttempt("report-cursor-b-"+UUID.randomUUID(),workspace,"dev-user",recentStart);
        JsonNode first=graph("{ myUsageReport(workspaceId:\""+workspace+"\",periodDays:7,first:1){attempts{pageInfo{endCursor}}} }");
        assertFalse(first.has("errors"),first.toString());
        String cursor=first.path("data").path("myUsageReport").path("attempts").path("pageInfo").path("endCursor").asText();
        String altered = (cursor.startsWith("A") ? "B" : "A") + cursor.substring(1);
        assertCode(graph("{ myUsageReport(workspaceId:\""+workspace+"\",periodDays:7,first:1,after:\""+altered+"\"){workspaceId} }"),"INVALID_CURSOR");
        assertCode(graph("{ myUsageReport(workspaceId:\""+workspace+"\",periodDays:7,provider:\"OTHER\",first:1,after:\""+cursor+"\"){workspaceId} }"),"INVALID_CURSOR");
        jdbc.update("update workspace_member set active=false where workspace_id=? and user_id='dev-user'", workspace);
        assertCode(graph("{ myUsageReport(workspaceId:\""+workspace+"\",periodDays:7,first:1,after:\""+cursor+"\"){workspaceId} }"),"WORKSPACE_ACCESS_DENIED");
    }

    @Test
    void nativeMetricsPreserveZeroNullMissingLargeIntegersAndSplitSchemaGroups() throws Exception {
        String workspace=createWorkspace();
        insertRaw("native-schema-a-" + UUID.randomUUID(),workspace,"dev-user","GOOGLE","schema-a","{\"promptTokenCount\":0,\"candidatesTokenCount\":null,\"cachedContentTokenCount\":9007199254740993}",false);
        insertRaw("native-schema-b-" + UUID.randomUUID(),workspace,"dev-user","GOOGLE","schema-b","{\"promptTokenCount\":7}",false);
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
        String workspace=createWorkspace();
        insertAttempt("real-unknown-"+UUID.randomUUID(),workspace,"dev-user",recentStart);
        insertAttempt("sim-unknown-"+UUID.randomUUID(),workspace,"dev-user",recentStart,true);
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
        jdbc.update("insert into provider_attempt(id,workspace_id,actor_id,provider,endpoint,model,channel,adapter_version,simulation,attempt_coverage,prepared_at,started_at,execution_outcome,usage_presence,capture_completeness,projection_state) values (?,?,?,?,'generateContent','test-model','test','test',?,'UNKNOWN',now(),?::timestamptz,'SUCCEEDED','PRESENT','COMPLETE','VALID')",id,workspace,actor,provider,simulation,recentStart);
        String snapshot="snap-"+id;
        jdbc.update("insert into usage_snapshot(id,attempt_id,event_id,payload_hash,ordinal,semantic,source_layer,schema_version,adapter_version,capture_version,capture_completeness,raw_state,raw_usage,captured_at) values (?,?,?, ?,1,'FINAL','SDK_NATIVE',?,'1','1','COMPLETE','JSONB',?::jsonb,now())",snapshot,id,"event-"+id,"hash-"+id,schema,raw);
        jdbc.update("update provider_attempt set projection_snapshot_id=? where id=?",snapshot,id);
    }

    private void assertSubtotal(BigDecimal expected, JsonNode response) {
        String actual = response.path("knownSubtotal").asText();
        assertFalse(actual.isBlank(), response.toString());
        assertEquals(0, expected.compareTo(new BigDecimal(actual)), () -> "Expected " + expected + " but was " + actual);
    }

    private void assertCode(JsonNode response,String code) {
        assertEquals(code,response.path("errors").get(0).path("extensions").path("code").asText(),response.toString());
    }

    private void insertAttempt(String id, String workspace, String actor, String startedAt) {
        insertAttempt(id, workspace, actor, startedAt, false);
    }

    private JsonNode graph(String query) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/graphql"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("query", query))))
                .build();
        try (var client = HttpClient.newHttpClient()) {
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode(), response.body());
            return json.readTree(response.body());
        }
    }
}
