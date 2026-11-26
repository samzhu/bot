package io.github.samzhu.bot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.samzhu.bot.usage.UsageLedger;
import io.github.samzhu.bot.usage.pricing.UsageCaseData;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
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
class UsageLedgerIntegrationTests {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate transactions;
    @Autowired UsageLedger ledger;

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();
    private final String workspace = "usage-ledger-" + UUID.randomUUID();
    private UsageCaseData data;
    private UsageCaseData otherWorkspace;

    @BeforeEach
    void createWorkspace() {
        data = new UsageCaseData(jdbc, transactions);
        data.createWorkspace(workspace, "dev-user");
    }

    @AfterEach
    void cleanUp() throws InterruptedException {
        try {
            if (otherWorkspace != null) otherWorkspace.cleanUp();
            data.cleanUp();
        } finally {
            http.close();
        }
    }

    @Test
    void duplicateFinalConflictAndJsonbFallbackPreserveRaw() throws Exception {
        String workspace = this.workspace;
        String id =
                ledger.begin(
                        new UsageLedger.Dispatch(
                                null,
                                workspace,
                                "dev-user",
                                "FAKE",
                                "test",
                                "m",
                                "FAKE",
                                "1",
                                true,
                                "VERIFIED"));
        var first =
                new UsageLedger.Snapshot(
                        "event-1", 1, "FINAL", "FAKE_RAW", "1", "1", "1", "COMPLETE", "{\"n\":1}");
        ledger.append(id, first);
        ledger.append(id, first);
        assertEquals(
                1,
                jdbc.queryForObject(
                        "select count(*) from usage_snapshot where attempt_id=?",
                        Integer.class,
                        id));
        assertThrows(
                RuntimeException.class,
                () ->
                        ledger.append(
                                id,
                                new UsageLedger.Snapshot(
                                        "event-1",
                                        1,
                                        "FINAL",
                                        "FAKE_RAW",
                                        "1",
                                        "1",
                                        "1",
                                        "COMPLETE",
                                        "{\"n\":2}")));
        ledger.append(
                id,
                new UsageLedger.Snapshot(
                        "event-2", 1, "FINAL", "FAKE_RAW", "1", "1", "1", "COMPLETE", "{\"n\":2}"));
        var conflict = ledger.readPricingInput(id);
        assertEquals("UNKNOWN", conflict.projectionState());
        assertEquals("CONFLICTING_FINAL", conflict.projectionReason());
        assertNull(conflict.effectiveSnapshotId());
        String fallback =
                ledger.begin(
                        new UsageLedger.Dispatch(
                                null,
                                workspace,
                                "dev-user",
                                "FAKE",
                                "test",
                                "m",
                                "FAKE",
                                "1",
                                true,
                                "VERIFIED"));
        ledger.append(
                fallback,
                new UsageLedger.Snapshot(
                        "event-3",
                        1,
                        "FINAL",
                        "FAKE_RAW",
                        "1",
                        "1",
                        "1",
                        "COMPLETE",
                        "{\"n\":1e131072}"));
        assertEquals(
                "{\"n\":1e131072}",
                jdbc.queryForObject(
                        "select raw_usage_text from usage_snapshot where attempt_id=?",
                        String.class,
                        fallback));
        assertEquals("UNKNOWN", ledger.readPricingInput(fallback).projectionState());
        var fallbackView =
                graph(
                        "{ myUsageAttempt(workspaceId: \""
                                + workspace
                                + "\",id: \""
                                + fallback
                                + "\") { projectionState projectionError effectiveSnapshotId"
                                + " snapshots{nodes{rawUsage rawState captureCompleteness"
                                + " captureReason}} } }");
        assertFalse(fallbackView.has("errors"), fallbackView.toString());
        var item = fallbackView.path("data").path("myUsageAttempt");
        assertEquals("JSONB_UNSUPPORTED", item.path("projectionError").asText());
        assertTrue(item.path("effectiveSnapshotId").isNull());
        var fallbackRaw = item.path("snapshots").path("nodes").get(0);
        assertEquals("TEXT_FALLBACK", fallbackRaw.path("rawState").asText());
        assertEquals("COMPLETE", fallbackRaw.path("captureCompleteness").asText());
        assertEquals("{\"n\":1e131072}", fallbackRaw.path("rawUsage").asText());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        ledger.append(
                                fallback,
                                new UsageLedger.Snapshot(
                                        "bad",
                                        2,
                                        "FINAL",
                                        "FAKE_RAW",
                                        "1",
                                        "1",
                                        "1",
                                        "COMPLETE",
                                        "{oops}")));
        assertEquals(
                1,
                jdbc.queryForObject(
                        "select count(*) from usage_snapshot where attempt_id=?",
                        Integer.class,
                        fallback));
    }

    @Test
    void cursorPaginationAndInvalidArguments() throws Exception {
        String workspace = this.workspace;
        String owner = "page-" + UUID.randomUUID();
        jdbc.update(
                "insert into workspace_member(workspace_id,user_id,role) values (?,?,'MEMBER')",
                workspace,
                owner);
        var dispatch =
                new UsageLedger.Dispatch(
                        null,
                        workspace,
                        "dev-user",
                        "FAKE",
                        "test",
                        "m",
                        "FAKE",
                        "1",
                        true,
                        "VERIFIED");
        String a = ledger.begin(dispatch);
        String b = ledger.begin(dispatch);
        var first =
                graph(
                        "{ myUsageAttempts(workspaceId: \""
                                + workspace
                                + "\", first: 1) { nodes { id } pageInfo { hasNextPage endCursor }"
                                + " } }");
        assertFalse(first.has("errors"), first.toString());
        var page = first.path("data").path("myUsageAttempts");
        assertTrue(page.path("pageInfo").path("hasNextPage").asBoolean());
        String item = page.path("nodes").get(0).path("id").asText();
        String cursor = page.path("pageInfo").path("endCursor").asText();
        ledger.begin(dispatch);
        var second =
                graph(
                        "{ myUsageAttempts(workspaceId: \""
                                + workspace
                                + "\", first: 1, after: \""
                                + cursor
                                + "\") { nodes { id } } }");
        assertFalse(second.has("errors"), second.toString());
        String secondId =
                second.path("data")
                        .path("myUsageAttempts")
                        .path("nodes")
                        .get(0)
                        .path("id")
                        .asText();
        assertNotEquals(item, secondId);
        assertTrue(List.of(a, b).contains(secondId));
        assertEquals(
                "INVALID_PAGE_SIZE",
                graph(
                                "{ myUsageAttempts(workspaceId: \""
                                        + workspace
                                        + "\", first: 0) { nodes { id } } }")
                        .path("errors")
                        .get(0)
                        .path("extensions")
                        .path("code")
                        .asText());
        assertEquals(
                "INVALID_PAGE_SIZE",
                graph(
                                "{ myUsageAttempts(workspaceId: \""
                                        + workspace
                                        + "\", first: 101) { nodes { id } } }")
                        .path("errors")
                        .get(0)
                        .path("extensions")
                        .path("code")
                        .asText());
        otherWorkspace = new UsageCaseData(jdbc, transactions);
        String foreign = "usage-cursor-" + UUID.randomUUID();
        otherWorkspace.createWorkspace(foreign, "dev-user");
        var wrongScope =
                graph(
                        "{ myUsageAttempts(workspaceId: \"" + foreign + "\",after: \""
                                + cursor
                                + "\") { nodes { id } } }");
        assertEquals(
                "INVALID_CURSOR",
                wrongScope.path("errors").get(0).path("extensions").path("code").asText());
        assertEquals(
                "INVALID_CURSOR",
                graph(
                                "{ myUsageAttempts(workspaceId: \""
                                        + workspace
                                        + "\", after: \"invalid\") { nodes { id } } }")
                        .path("errors")
                        .get(0)
                        .path("extensions")
                        .path("code")
                        .asText());
        var encoder = java.util.Base64.getUrlEncoder().withoutPadding();
        String forged =
                java.util.stream.Stream.of(
                                "v1",
                                "attempt",
                                workspace,
                                "",
                                "2026-01-01T00:00:00Z",
                                "not-a-uuid")
                        .map(
                                v ->
                                        encoder.encodeToString(
                                                v.getBytes(
                                                        java.nio.charset.StandardCharsets.UTF_8)))
                        .collect(java.util.stream.Collectors.joining("."));
        var invalid =
                graph(
                        "{ myUsageAttempts(workspaceId: \""
                                + workspace
                                + "\", after: \""
                                + forged
                                + "\") { nodes { id } } }");
        assertTrue(invalid.path("errors").isArray(), invalid.toString());
        assertEquals(
                "INVALID_CURSOR",
                invalid.path("errors").get(0).path("extensions").path("code").asText());
        jdbc.update(
                """
insert into provider_attempt(id,workspace_id,actor_id,provider,endpoint,model,channel,adapter_version,simulation,attempt_coverage,prepared_at,execution_outcome,usage_presence,capture_completeness,projection_state)
values ('non-uuid-anchor',?,'dev-user','FAKE','test','m','FAKE','1',true,'VERIFIED','2026-01-01T00:00:00Z','IN_PROGRESS','PENDING','UNKNOWN','MISSING'),
       ('non-uuid-next',?,'dev-user','FAKE','test','m','FAKE','1',true,'VERIFIED','2026-01-01T00:00:00Z','IN_PROGRESS','PENDING','UNKNOWN','MISSING')
""",
                workspace,
                workspace);
        String legal =
                java.util.stream.Stream.of(
                                "v1",
                                "attempt",
                                workspace,
                                "",
                                "2026-01-01T00:00:00Z",
                                "non-uuid-next")
                        .map(
                                v ->
                                        encoder.encodeToString(
                                                v.getBytes(
                                                        java.nio.charset.StandardCharsets.UTF_8)))
                        .collect(java.util.stream.Collectors.joining("."));
        var valid =
                graph(
                        "{ myUsageAttempts(workspaceId: \""
                                + workspace
                                + "\", first: 1, after: \""
                                + legal
                                + "\") { nodes { id } } }");
        assertFalse(valid.has("errors"), valid.toString());
        assertEquals(
                "non-uuid-anchor",
                valid.path("data")
                        .path("myUsageAttempts")
                        .path("nodes")
                        .get(0)
                        .path("id")
                        .asText());
        String nanos =
                java.util.stream.Stream.of(
                                "v1",
                                "attempt",
                                workspace,
                                "",
                                "2026-01-01T00:00:00.000000001Z",
                                "non-uuid-next")
                        .map(
                                v ->
                                        encoder.encodeToString(
                                                v.getBytes(
                                                        java.nio.charset.StandardCharsets.UTF_8)))
                        .collect(java.util.stream.Collectors.joining("."));
        var tooPrecise =
                graph(
                        "{ myUsageAttempts(workspaceId: \""
                                + workspace
                                + "\", after: \""
                                + nanos
                                + "\") { nodes { id } } }");
        assertEquals(
                "INVALID_CURSOR",
                tooPrecise.path("errors").get(0).path("extensions").path("code").asText());
    }

    @Test
    void nativeValuesAndRawNullAreDistinguished() throws Exception {
        String workspace = this.workspace;
        var dispatch =
                new UsageLedger.Dispatch(
                        null,
                        workspace,
                        "dev-user",
                        "FAKE",
                        "test",
                        "m",
                        "FAKE",
                        "1",
                        true,
                        "VERIFIED");
        String id = ledger.begin(dispatch);
        ledger.append(
                id,
                new UsageLedger.Snapshot(
                        "event",
                        1,
                        "FINAL",
                        "FAKE_RAW",
                        "1",
                        "1",
                        "1",
                        "COMPLETE",
                        "{\"input\":0,\"output\":null,\"cacheRead\":9007199254740993}"));
        var result =
                graph(
                        "{ myUsageAttempt(workspaceId: \""
                                + workspace
                                + "\", id: \""
                                + id
                                + "\") { input{value presence} output{value presence}"
                                + " cacheRead{value presence} cacheWrite{value presence} snapshots"
                                + " {nodes{rawUsage rawState}} } }");
        assertFalse(result.has("errors"), result.toString());
        var attempt = result.path("data").path("myUsageAttempt");
        assertEquals("0", attempt.path("input").path("value").asText());
        assertEquals("PRESENT", attempt.path("input").path("presence").asText());
        assertTrue(attempt.path("output").path("value").isNull());
        assertEquals("NULL_VALUE", attempt.path("output").path("presence").asText());
        assertEquals("9007199254740993", attempt.path("cacheRead").path("value").asText());
        assertEquals("MISSING", attempt.path("cacheWrite").path("presence").asText());
        String nullId = ledger.begin(dispatch);
        ledger.append(
                nullId,
                new UsageLedger.Snapshot(
                        "event", 1, "FINAL", "FAKE_RAW", "1", "1", "1", "COMPLETE", "null"));
        var nullResult =
                graph(
                        "{ myUsageAttempt(workspaceId: \""
                                + workspace
                                + "\", id: \""
                                + nullId
                                + "\") { snapshots { nodes {rawUsage rawState} } } }");
        assertEquals(
                "null",
                nullResult
                        .path("data")
                        .path("myUsageAttempt")
                        .path("snapshots")
                        .path("nodes")
                        .get(0)
                        .path("rawUsage")
                        .asText());
        String unknown = ledger.begin(dispatch);
        ledger.append(
                unknown,
                new UsageLedger.Snapshot(
                        "event", 1, "FINAL", "SDK_NATIVE", "1", "1", "1", "INCOMPLETE", "{}"));
        var missing =
                graph(
                        "{ myUsageAttempt(workspaceId: \""
                                + workspace
                                + "\", id: \""
                                + unknown
                                + "\") { input {value presence} } }");
        assertEquals(
                "UNKNOWN",
                missing.path("data")
                        .path("myUsageAttempt")
                        .path("input")
                        .path("presence")
                        .asText());
        String unavailable = ledger.begin(dispatch);
        ledger.append(
                unavailable,
                new UsageLedger.Snapshot(
                        "unavailable", 1, "UNKNOWN", "FAKE_RAW", "1", "1", "1", "UNKNOWN", null));
        var unavailableResult =
                graph(
                        "{ myUsageAttempt(workspaceId: \""
                                + workspace
                                + "\", id: \""
                                + unavailable
                                + "\") { snapshots { nodes {rawUsage rawState captureReason} } }"
                                + " }");
        var raw =
                unavailableResult
                        .path("data")
                        .path("myUsageAttempt")
                        .path("snapshots")
                        .path("nodes")
                        .get(0);
        assertTrue(raw.path("rawUsage").isNull());
        assertEquals("UNAVAILABLE", raw.path("rawState").asText());
    }

    @Test
    void unknownCoverageRemainsIndependentFromCompleteCapture() throws Exception {
        String workspace = this.workspace;
        String id =
                ledger.begin(
                        new UsageLedger.Dispatch(
                                null,
                                workspace,
                                "dev-user",
                                "GOOGLE",
                                "generateContent",
                                "m",
                                "GEMINI_API",
                                "1",
                                false,
                                "UNKNOWN"));
        ledger.append(
                id,
                new UsageLedger.Snapshot(
                        "e",
                        1,
                        "FINAL",
                        "FAKE_RAW",
                        "1",
                        "1",
                        "1",
                        "COMPLETE",
                        "{\"promptTokenCount\":0}"));
        var input = ledger.readPricingInput(id);
        assertEquals("UNKNOWN", input.attemptCoverage());
        assertEquals("COMPLETE", input.effectiveSnapshot().captureCompleteness());
        var result =
                graph(
                        "{ myUsageAttempt(workspaceId: \""
                                + workspace
                                + "\",id: \""
                                + id
                                + "\") { attemptCoverage captureCompleteness simulation cost{status"
                                + " amount currency} } }");
        var attempt = result.path("data").path("myUsageAttempt");
        assertEquals("UNKNOWN", attempt.path("attemptCoverage").asText());
        assertFalse(attempt.path("simulation").asBoolean());
        assertEquals("UNPRICED", attempt.path("cost").path("status").asText());
        assertTrue(attempt.path("cost").path("amount").isNull());
    }

    @Test
    void preparedAttemptHasNoDispatchTimestamp() throws Exception {
        String workspace = this.workspace;
        String id =
                ledger.begin(
                        new UsageLedger.Dispatch(
                                null,
                                workspace,
                                "dev-user",
                                "FAKE",
                                "test",
                                "m",
                                "FAKE",
                                "1",
                                true,
                                "VERIFIED"));
        var result =
                graph(
                        "{ myUsageAttempt(workspaceId: \""
                                + workspace
                                + "\",id: \""
                                + id
                                + "\") { preparedAt startedAt } }");
        assertFalse(result.has("errors"), result.toString());
        var attempt = result.path("data").path("myUsageAttempt");
        assertFalse(attempt.path("preparedAt").isNull());
        assertTrue(attempt.path("startedAt").isNull());
    }

    @Test
    void conflictingFinalsRemainUnknownRegardlessOfProjectionOrder() throws Exception {
        String workspace = this.workspace;
        String id =
                ledger.begin(
                        new UsageLedger.Dispatch(
                                null,
                                workspace,
                                "dev-user",
                                "FAKE",
                                "test",
                                "m",
                                "FAKE",
                                "1",
                                true,
                                "VERIFIED"));
        ledger.append(
                id,
                new UsageLedger.Snapshot(
                        "cumulative",
                        10,
                        "CUMULATIVE",
                        "FAKE_RAW",
                        "1",
                        "1",
                        "1",
                        "COMPLETE",
                        "{\"input\":10}"));
        ledger.append(
                id,
                new UsageLedger.Snapshot(
                        "final-older",
                        9,
                        "FINAL",
                        "FAKE_RAW",
                        "1",
                        "1",
                        "1",
                        "COMPLETE",
                        "{\"input\":9}"));
        ledger.append(
                id,
                new UsageLedger.Snapshot(
                        "final-newer",
                        11,
                        "FINAL",
                        "FAKE_RAW",
                        "1",
                        "1",
                        "1",
                        "COMPLETE",
                        "{\"input\":11}"));
        var result = ledger.readPricingInput(id);
        assertEquals("UNKNOWN", result.projectionState());
        assertEquals("CONFLICTING_FINAL", result.projectionReason());
        assertNull(result.effectiveSnapshotId());
        assertEquals(
                3,
                jdbc.queryForObject(
                        "select count(*) from usage_snapshot where attempt_id=?",
                        Integer.class,
                        id));
    }

    @Test
    void captureFailureKeepsEarlierRawForAuditButRemovesEffectiveProjection() throws Exception {
        String workspace = this.workspace;
        String id = ledger.begin(new UsageLedger.Dispatch(
                null, workspace, "dev-user", "FAKE", "test", "m", "FAKE", "1", true, "VERIFIED"));
        ledger.append(id, new UsageLedger.Snapshot(
                "earlier-raw", 1, "CUMULATIVE", "FAKE_RAW", "1", "1", "1", "COMPLETE", "{\"input\":12}"));
        ledger.finish(id, "UNKNOWN");
        var view = ledger.readPricingInput(id);
        assertEquals("UNKNOWN", view.projectionState());
        assertEquals("CAPTURE_FAILED", view.projectionReason());
        assertNull(view.effectiveSnapshotId());
        assertEquals("{\"input\": 12}", jdbc.queryForObject(
                "select raw_usage::text from usage_snapshot where attempt_id=? and event_id='earlier-raw'", String.class, id));
    }

    @Test
    void lateUsagePreservesTerminalOutcomeAndFinalBeatsPartial() {
        String id = ledger.begin(new UsageLedger.Dispatch(null, workspace, "dev-user",
                "FAKE", "test", "m", "FAKE", "1", true, "VERIFIED"));
        assertTrue(ledger.markDispatched(id, java.time.Clock::instant));
        ledger.append(id, new UsageLedger.Snapshot("before", 1, "CUMULATIVE", "FAKE_RAW",
                "1", "1", "1", "COMPLETE", "{\"input\":1}"));
        ledger.finish(id, "CANCELLED");
        ledger.append(id, new UsageLedger.Snapshot("final", 2, "FINAL", "FAKE_RAW",
                "1", "1", "1", "COMPLETE", "{\"input\":12}"));
        var finalView = ledger.readPricingInput(id);
        assertEquals("CANCELLED", finalView.executionOutcome());
        assertEquals("FINAL", finalView.effectiveSnapshot().semantic());

        ledger.append(id, new UsageLedger.Snapshot("after", 3, "CUMULATIVE", "FAKE_RAW",
                "1", "1", "1", "COMPLETE", "{\"input\":13}"));
        var lateView = ledger.readPricingInput(id);
        assertEquals("CANCELLED", lateView.executionOutcome());
        assertEquals(finalView.effectiveSnapshotId(), lateView.effectiveSnapshotId());
        assertEquals(3, jdbc.queryForObject("select count(*) from usage_snapshot where attempt_id=?",
                Integer.class, id));
    }

    private JsonNode graph(String query) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/graphql"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("query",query))))
                .build();
        var response = http.send(request,HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        return json.readTree(response.body());
    }
}
