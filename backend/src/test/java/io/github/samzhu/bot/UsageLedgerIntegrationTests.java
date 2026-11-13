package io.github.samzhu.bot;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.convention.TestBean;

import reactor.core.publisher.Flux;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class UsageLedgerIntegrationTests {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired io.github.samzhu.bot.usage.internal.pricing.PricingPublisher pricingPublisher;
    @Autowired io.github.samzhu.bot.identity.CurrentUserProvider currentUser;
    @Autowired io.github.samzhu.bot.usage.internal.pricing.CatalogActivation catalogActivation;
    @Autowired io.github.samzhu.bot.execution.internal.UsageInvocation invocation;
    @Autowired io.github.samzhu.bot.usage.WorkspaceAdmission admission;
    @Autowired io.github.samzhu.bot.execution.internal.RunRegistry runRegistry;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean io.github.samzhu.bot.usage.UsageLedger ledger;
    @Autowired org.springframework.transaction.support.TransactionTemplate transaction;
    @Autowired io.github.samzhu.bot.usage.internal.admission.WorkspaceLane workspaceLane;
    @Autowired io.github.samzhu.bot.usage.internal.admission.BudgetProjection budgetProjection;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
    @Autowired io.github.samzhu.bot.usage.RunAdmissionStateReader runAdmissionStateReader;

    @TestBean(name = "mockChatModel", methodName = "fakeModel", enforceOverride = true)
    ChatModel model;

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();

    static ChatModel fakeModel() {
        return new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                throw new UnsupportedOperationException();
            }

            @Override
            public ChatOptions getDefaultOptions() {
                return null;
            }

            @Override
            public Flux<ChatResponse> stream(Prompt prompt) {
                var text = new ChatResponse(List.of(new Generation(new AssistantMessage("hello"))));
                var usage =
                        new DefaultUsage(
                                0,
                                0,
                                0,
                                "{\"input\":0,\"extra\":{\"n\":9007199254740993,\"decimal\":0.12345678901234567890123456789}}");
                var metadataOnly =
                        new ChatResponse(
                                List.of(),
                                ChatResponseMetadata.builder()
                                        .id("fake-response-01")
                                        .usage(usage)
                                        .build());
                return Flux.just(text, metadataOnly);
            }
        };
    }

    private JsonNode graph(String query) throws Exception {
        var request =
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/graphql"))
                        .header("Content-Type", "application/json")
                        .POST(
                                HttpRequest.BodyPublishers.ofString(
                                        json.writeValueAsString(Map.of("query", query))))
                        .build();
        return json.readTree(http.send(request, HttpResponse.BodyHandlers.ofString()).body());
    }

    @Test
    void metadataOnlyFrameCreatesAttemptAndRawSnapshotVisibleThroughGraphql() throws Exception {
        jdbc.update(
                "insert into bot(id,name,target) values ('usage-test-bot','Fake','LOCAL') on"
                    + " conflict do nothing");
        JsonNode workspace = graph("{ personalWorkspace { id } }");
        String workspaceId = workspace.path("data").path("personalWorkspace").path("id").asText();
        String conversation =
                graph("mutation { createConversation(botId: \"usage-test-bot\") { id } }")
                        .path("data")
                        .path("createConversation")
                        .path("id")
                        .asText();
        String run = UUID.randomUUID().toString();
        var body =
                Map.of(
                        "threadId",
                        conversation,
                        "runId",
                        run,
                        "messages",
                        List.of(Map.of("id", "u", "role", "user", "content", "hi")));
        var request =
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/ag-ui"))
                        .header("Content-Type", "application/json")
                        .header("Accept", "text/event-stream")
                        .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                        .build();
        assertEquals(200, http.send(request, HttpResponse.BodyHandlers.ofString()).statusCode());
        JsonNode result =
                graph(
                        "{ myUsageAttempts(workspaceId: \""
                                + workspaceId
                                + "\") { nodes { runId attemptCoverage providerRequestId"
                                + " providerResponseId input {value presence meaning} output {value"
                                + " presence} cacheRead {value presence} cost { status amount"
                                + " currency } snapshots { nodes { rawUsage } } } } }");
        assertFalse(result.has("errors"), result.toString());
        JsonNode attempt = result.path("data").path("myUsageAttempts").path("nodes").get(0);
        assertEquals(run, attempt.path("runId").asText());
        assertEquals("VERIFIED", attempt.path("attemptCoverage").asText());
        assertTrue(attempt.path("providerRequestId").isNull());
        assertEquals("fake-response-01", attempt.path("providerResponseId").asText());
        assertEquals("0", attempt.path("input").path("value").asText());
        assertEquals("PRESENT", attempt.path("input").path("presence").asText());
        assertEquals("MISSING", attempt.path("output").path("presence").asText());
        assertEquals("UNPRICED", attempt.path("cost").path("status").asText());
        assertTrue(attempt.path("cost").path("amount").isNull());
        assertEquals("USD", attempt.path("cost").path("currency").asText());
        assertTrue(
                attempt.path("snapshots")
                        .path("nodes")
                        .get(0)
                        .path("rawUsage")
                        .asText()
                        .contains("9007199254740993"));
        assertTrue(
                attempt.path("snapshots")
                        .path("nodes")
                        .get(0)
                        .path("rawUsage")
                        .asText()
                        .contains("0.12345678901234567890123456789"));
    }

    @Test
    void duplicateFinalConflictAndJsonbFallbackPreserveRaw() throws Exception {
        String workspace =
                graph("{ personalWorkspace { id } }")
                        .path("data")
                        .path("personalWorkspace")
                        .path("id")
                        .asText();
        String id =
                ledger.begin(
                        new io.github.samzhu.bot.usage.UsageLedger.Dispatch(
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
                new io.github.samzhu.bot.usage.UsageLedger.Snapshot(
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
                                new io.github.samzhu.bot.usage.UsageLedger.Snapshot(
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
                new io.github.samzhu.bot.usage.UsageLedger.Snapshot(
                        "event-2", 1, "FINAL", "FAKE_RAW", "1", "1", "1", "COMPLETE", "{\"n\":2}"));
        var conflict = ledger.readPricingInput(id);
        assertEquals("UNKNOWN", conflict.projectionState());
        assertEquals("CONFLICTING_FINAL", conflict.projectionReason());
        assertNull(conflict.effectiveSnapshotId());
        String fallback =
                ledger.begin(
                        new io.github.samzhu.bot.usage.UsageLedger.Dispatch(
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
                new io.github.samzhu.bot.usage.UsageLedger.Snapshot(
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
                                new io.github.samzhu.bot.usage.UsageLedger.Snapshot(
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
        String workspace =
                graph("{ personalWorkspace { id } }")
                        .path("data")
                        .path("personalWorkspace")
                        .path("id")
                        .asText();
        String owner = "page-" + UUID.randomUUID();
        jdbc.update(
                "insert into workspace_member(workspace_id,user_id,role) values (?,?,'MEMBER')",
                workspace,
                owner);
        var dispatch =
                new io.github.samzhu.bot.usage.UsageLedger.Dispatch(
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
        jdbc.update(
                "insert into workspace(id,name) values ('cursor-other-scope','Other') on conflict"
                    + " do nothing");
        jdbc.update(
                "insert into workspace_member(workspace_id,user_id,role) values"
                    + " ('cursor-other-scope','dev-user','MEMBER') on conflict do nothing");
        var wrongScope =
                graph(
                        "{ myUsageAttempts(workspaceId: \"cursor-other-scope\",after: \""
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
        String workspace =
                graph("{ personalWorkspace { id } }")
                        .path("data")
                        .path("personalWorkspace")
                        .path("id")
                        .asText();
        var dispatch =
                new io.github.samzhu.bot.usage.UsageLedger.Dispatch(
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
                new io.github.samzhu.bot.usage.UsageLedger.Snapshot(
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
                new io.github.samzhu.bot.usage.UsageLedger.Snapshot(
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
                new io.github.samzhu.bot.usage.UsageLedger.Snapshot(
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
                new io.github.samzhu.bot.usage.UsageLedger.Snapshot(
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
    void ownerScopeAndMembershipAreRecheckedForEveryPage() throws Exception {
        String workspace =
                graph("{ personalWorkspace { id } }")
                        .path("data")
                        .path("personalWorkspace")
                        .path("id")
                        .asText();
        var own =
                new io.github.samzhu.bot.usage.UsageLedger.Dispatch(
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
        var foreign =
                new io.github.samzhu.bot.usage.UsageLedger.Dispatch(
                        null,
                        workspace,
                        "other-user",
                        "FAKE",
                        "test",
                        "m",
                        "FAKE",
                        "1",
                        true,
                        "VERIFIED");
        jdbc.update(
                "insert into workspace_member(workspace_id,user_id,role) values"
                    + " (?,'other-user','MEMBER') on conflict do nothing",
                workspace);
        String id = ledger.begin(own);
        String other = ledger.begin(foreign);
        ledger.append(
                id,
                new io.github.samzhu.bot.usage.UsageLedger.Snapshot(
                        "event1", 1, "CUMULATIVE", "FAKE_RAW", "1", "1", "1", "COMPLETE", "{}"));
        ledger.append(
                id,
                new io.github.samzhu.bot.usage.UsageLedger.Snapshot(
                        "event2", 2, "CUMULATIVE", "FAKE_RAW", "1", "1", "1", "COMPLETE", "{}"));
        var hidden =
                graph(
                        "{ myUsageAttempt(workspaceId: \""
                                + workspace
                                + "\",id: \""
                                + other
                                + "\") {id} }");
        assertTrue(hidden.path("data").path("myUsageAttempt").isNull());
        var first =
                graph(
                        "{ myUsageAttempt(workspaceId: \""
                                + workspace
                                + "\",id: \""
                                + id
                                + "\") { snapshots(first:1){nodes{id} pageInfo{hasNextPage"
                                + " endCursor}} } }");
        assertFalse(first.has("errors"), first.toString());
        var page = first.path("data").path("myUsageAttempt").path("snapshots");
        assertTrue(page.path("pageInfo").path("hasNextPage").asBoolean());
        String cursor = page.path("pageInfo").path("endCursor").asText();
        String otherOwn = ledger.begin(own);
        var wrongAttempt =
                graph(
                        "{ myUsageAttempt(workspaceId: \""
                                + workspace
                                + "\",id: \""
                                + otherOwn
                                + "\") { snapshots(first:1,after: \""
                                + cursor
                                + "\"){nodes{id}} } }");
        assertEquals(
                "INVALID_CURSOR",
                wrongAttempt.path("errors").get(0).path("extensions").path("code").asText());
        var second =
                graph(
                        "{ myUsageAttempt(workspaceId: \""
                                + workspace
                                + "\",id: \""
                                + id
                                + "\") { snapshots(first:1,after: \""
                                + cursor
                                + "\"){nodes{id}} } }");
        assertFalse(second.has("errors"), second.toString());
        assertNotEquals(
                page.path("nodes").get(0).path("id").asText(),
                second.path("data")
                        .path("myUsageAttempt")
                        .path("snapshots")
                        .path("nodes")
                        .get(0)
                        .path("id")
                        .asText());
        try {
            jdbc.update(
                    "update workspace_member set active=false where workspace_id=? and"
                        + " user_id='dev-user'",
                    workspace);
            var denied =
                    graph("{ myUsageAttempts(workspaceId: \"" + workspace + "\") {nodes{id}} }");
            assertEquals(
                    "WORKSPACE_ACCESS_DENIED",
                    denied.path("errors").get(0).path("extensions").path("code").asText());
        } finally {
            jdbc.update(
                    "update workspace_member set active=true where workspace_id=? and"
                        + " user_id='dev-user'",
                    workspace);
        }
    }

    @Test
    void googleAdapterMarksCoverageUnknownAndSdkCaptureIncomplete() {
        var adapter =
                new io.github.samzhu.bot.execution.internal.capture.GoogleUsageCaptureAdapter(
                        "test-model");
        assertEquals("UNKNOWN", adapter.dispatch("r", "w", "u").attemptCoverage());
        var nativeUsage =
                com.google.genai.types.GenerateContentResponseUsageMetadata.fromJson(
                        "{\"promptTokenCount\":0}");
        var metadata =
                ChatResponseMetadata.builder()
                        .id("provider-response")
                        .usage(new DefaultUsage(0, 0, 0, nativeUsage))
                        .build();
        var response = new ChatResponse(List.of(), metadata);
        var snapshot = adapter.capture(response, "event", 1).orElseThrow();
        assertEquals("SDK_NATIVE", snapshot.sourceLayer());
        assertEquals("INCOMPLETE", snapshot.completeness());
        assertEquals("provider-response", adapter.responseId(response).orElseThrow());
    }

    @Test
    void lateUsagePreservesTerminalOutcomeAndFinalBeatsPartial() throws Exception {
        String workspace =
                graph("{ personalWorkspace { id } }")
                        .path("data")
                        .path("personalWorkspace")
                        .path("id")
                        .asText();
        var fixed =
                java.time.Clock.fixed(
                        java.time.Instant.parse("2026-01-31T16:00:00Z"), java.time.ZoneOffset.UTC);
        var controlled = new io.github.samzhu.bot.usage.internal.JdbcUsageLedger(
                jdbc, fixed, catalogActivation, workspaceLane, budgetProjection, transactionManager, runAdmissionStateReader);
        String id =
                controlled.begin(
                        new io.github.samzhu.bot.usage.UsageLedger.Dispatch(
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
        transaction.executeWithoutResult(
                status ->
                        controlled.append(
                                id,
                                new io.github.samzhu.bot.usage.UsageLedger.Snapshot(
                                        "old",
                                        1,
                                        "CUMULATIVE",
                                        "FAKE_RAW",
                                        "1",
                                        "1",
                                        "1",
                                        "COMPLETE",
                                        "{\"input\":1}")));
        assertTrue(controlled.markDispatched(id, java.time.Clock::instant));
        controlled.finish(id, "CANCELLED");
        assertEquals(
                fixed.instant(),
                jdbc.queryForObject(
                                "select captured_at from usage_snapshot where attempt_id=?",
                                java.sql.Timestamp.class,
                                id)
                        .toInstant());
        assertEquals(
                fixed.instant(),
                jdbc.queryForObject(
                                "select finished_at from provider_attempt where id=?",
                                java.sql.Timestamp.class,
                                id)
                        .toInstant());
        ledger.append(
                id,
                new io.github.samzhu.bot.usage.UsageLedger.Snapshot(
                        "late",
                        2,
                        "FINAL",
                        "FAKE_RAW",
                        "1",
                        "1",
                        "1",
                        "COMPLETE",
                        "{\"input\":2}"));
        var before = ledger.readPricingInput(id);
        assertEquals("CANCELLED", before.executionOutcome());
        assertEquals(fixed.instant(), before.preparedAt());
        assertEquals(fixed.instant(), before.startedAt());
        assertEquals("FINAL", before.effectiveSnapshot().semantic());
        ledger.append(
                id,
                new io.github.samzhu.bot.usage.UsageLedger.Snapshot(
                        "partial",
                        3,
                        "CUMULATIVE",
                        "FAKE_RAW",
                        "1",
                        "1",
                        "1",
                        "COMPLETE",
                        "{\"input\":3}"));
        var after = ledger.readPricingInput(id);
        assertEquals(before.effectiveSnapshotId(), after.effectiveSnapshotId());
        assertEquals("CANCELLED", after.executionOutcome());
        assertEquals(
                3,
                jdbc.queryForObject(
                        "select count(*) from usage_snapshot where attempt_id=?",
                        Integer.class,
                        id));
    }

    @Test
    void unknownCoverageRemainsIndependentFromCompleteCapture() throws Exception {
        String workspace =
                graph("{ personalWorkspace { id } }")
                        .path("data")
                        .path("personalWorkspace")
                        .path("id")
                        .asText();
        String id =
                ledger.begin(
                        new io.github.samzhu.bot.usage.UsageLedger.Dispatch(
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
                new io.github.samzhu.bot.usage.UsageLedger.Snapshot(
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
    void localUsageSeedIsSyntheticIdempotentAndPreservesExistingHistory() throws Exception {
        jdbc.update(
                "insert into bot(id,name,target) values ('usage-test-bot','Fake','LOCAL') on"
                    + " conflict do nothing");
        String workspace =
                graph("{ personalWorkspace { id } }")
                        .path("data")
                        .path("personalWorkspace")
                        .path("id")
                        .asText();
        jdbc.update(
                "insert into conversation(id,bot_id,title,user_id,workspace_id) values"
                    + " ('seed-conv-completed-01','usage-test-bot','Seed','dev-user',?) on conflict"
                    + " do nothing",
                workspace);
        jdbc.update(
                "insert into chat_run(id,conversation_id,client_request_id,input_text,status)"
                    + " values"
                    + " ('seed-run-completed-01','seed-conv-completed-01','usage-seed-request','synthetic','COMPLETED')"
                    + " on conflict do nothing");
        int messages =
                jdbc.queryForObject(
                        "select count(*) from message where"
                            + " conversation_id='seed-conv-completed-01'",
                        Integer.class);
        int sessions =
                jdbc.queryForObject(
                        "select count(*) from ai_session where id='seed-conv-completed-01'",
                        Integer.class);
        var seed = new io.github.samzhu.bot.bootstrap.LocalUsageSeedInitializer(jdbc, transaction);
        seed.run(null);
        seed.run(null);
        assertEquals(
                1,
                jdbc.queryForObject(
                        "select count(*) from provider_attempt where id='seed-usage-attempt-01' and"
                            + " simulation and provider='FAKE'",
                        Integer.class));
        assertEquals(
                1,
                jdbc.queryForObject(
                        "select count(*) from usage_snapshot where id='seed-usage-snapshot-01' and"
                            + " source_layer='FAKE_RAW' and raw_usage -> 'example' ->> 'kind' ="
                            + " 'synthetic'",
                        Integer.class));
        assertEquals(
                messages,
                jdbc.queryForObject(
                        "select count(*) from message where"
                            + " conversation_id='seed-conv-completed-01'",
                        Integer.class));
        assertEquals(
                sessions,
                jdbc.queryForObject(
                        "select count(*) from ai_session where id='seed-conv-completed-01'",
                        Integer.class));
    }

    @Test
    void twoFakeInvocationsOnOneRunEachPersistBeforeDispatch() throws Exception {
        jdbc.update(
                "insert into bot(id,name,target) values ('usage-test-bot','Fake','LOCAL') on"
                    + " conflict do nothing");
        String workspace =
                graph("{ personalWorkspace { id } }")
                        .path("data")
                        .path("personalWorkspace")
                        .path("id")
                        .asText();
        String conversation =
                graph("mutation { createConversation(botId: \"usage-test-bot\") {id} }")
                        .path("data")
                        .path("createConversation")
                        .path("id")
                        .asText();
        String run = UUID.randomUUID().toString();
        jdbc.update(
                "insert into chat_run(id,conversation_id,client_request_id,input_text,status)"
                    + " values (?,?,?,'synthetic','RUNNING')",
                run,
                conversation,
                run);
        var handle =
                new io.github.samzhu.bot.execution.internal.RunHandle(
                        run,
                        conversation,
                        "dev-user",
                        new org.springframework.web.servlet.mvc.method.annotation.SseEmitter(),
                        runRegistry,
                        org.mockito.Mockito.mock(
                                io.github.samzhu.bot.execution.internal.RunHandle.TerminalCallback
                                        .class));
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        ChatModel fake =
                new ChatModel() {
                    @Override
                    public ChatResponse call(Prompt p) {
                        throw new AssertionError("unexpected sync call");
                    }

                    @Override
                    public Flux<ChatResponse> stream(Prompt p) {
                        int count = calls.incrementAndGet();
                        assertEquals(
                                count,
                                jdbc.queryForObject(
                                        "select count(*) from provider_attempt where run_id=?",
                                        Integer.class,
                                        run));
                        var usage = new DefaultUsage(0, 0, 0, "{\"input\":" + count + "}");
                        return Flux.just(
                                new ChatResponse(
                                        List.of(new Generation(new AssistantMessage("ok"))),
                                        ChatResponseMetadata.builder().usage(usage).build()));
                    }
                };
        var metadata = invocation.dispatchMetadata(run, workspace, "dev-user");
            var partition = metadata.simulation() ? io.github.samzhu.bot.usage.WorkspaceAdmission.Partition.SIMULATION : io.github.samzhu.bot.usage.WorkspaceAdmission.Partition.REAL;
            var registered = admission.register(new io.github.samzhu.bot.usage.WorkspaceAdmission.Register(workspace, run, "dev-user", conversation, partition)).toCompletableFuture().join();
            assertInstanceOf(io.github.samzhu.bot.usage.WorkspaceAdmission.RegisterResult.Registered.class, registered);
            var registration = ((io.github.samzhu.bot.usage.WorkspaceAdmission.RegisterResult.Registered)registered).registration();
            assertInstanceOf(io.github.samzhu.bot.usage.WorkspaceAdmission.PublicationResult.Ready.class, admission.publish(registration).toCompletableFuture().join());
            handle.setAdmissionRegistration(registration);
            var first = invocation.begin(metadata, registration);
        assertEquals(
                1,
                invocation.stream(
                                fake,
                                new Prompt("one"),
                                first,
                                conversation,
                                workspace,
                                "dev-user",
                                handle)
                        .collectList()
                        .block()
                        .size());
        var second = invocation.begin(metadata, registration);
        assertEquals(
                1,
                invocation.stream(
                                fake,
                                new Prompt("two"),
                                second,
                                conversation,
                                workspace,
                                "dev-user",
                                handle)
                        .collectList()
                        .block()
                        .size());
        assertNotEquals(first.id(), second.id());
        assertEquals(2, calls.get());
        assertEquals(
                2,
                jdbc.queryForObject(
                        "select count(*) from provider_attempt where run_id=? and"
                            + " execution_outcome='SUCCEEDED'",
                        Integer.class,
                        run));
        var firstRaw = ledger.readPricingInput(first.id()).effectiveSnapshot().rawJson();
        var secondRaw = ledger.readPricingInput(second.id()).effectiveSnapshot().rawJson();
        assertEquals(1, json.readTree(firstRaw).path("input").asInt());
        assertEquals(2, json.readTree(secondRaw).path("input").asInt());
        var exposed =
                graph(
                        "{ myUsageAttempts(workspaceId: \""
                                + workspace
                                + "\",first:100) { nodes {id runId} } }");
        var matches = exposed.path("data").path("myUsageAttempts").path("nodes");
        assertEquals(
                2,
                java.util.stream.StreamSupport.stream(matches.spliterator(), false)
                        .filter(node -> run.equals(node.path("runId").asText()))
                        .count());
    }

    @Test
    void secondInvocationCannotDispatchAfterRevocationOrCancellation() throws Exception {
        jdbc.update(
                "insert into bot(id,name,target) values ('usage-test-bot','Fake','LOCAL') on"
                    + " conflict do nothing");
        String workspace =
                graph("{ personalWorkspace { id } }")
                        .path("data")
                        .path("personalWorkspace")
                        .path("id")
                        .asText();
        for (boolean revoke : List.of(true, false)) {
            String conversation =
                    graph("mutation { createConversation(botId: \"usage-test-bot\") {id} }")
                            .path("data")
                            .path("createConversation")
                            .path("id")
                            .asText();
            String run = UUID.randomUUID().toString();
            jdbc.update(
                    "insert into chat_run(id,conversation_id,client_request_id,input_text,status)"
                        + " values (?,?,?,'synthetic','RUNNING')",
                    run,
                    conversation,
                    run);
            var handle =
                    new io.github.samzhu.bot.execution.internal.RunHandle(
                            run,
                            conversation,
                            "dev-user",
                            new org.springframework.web.servlet.mvc.method.annotation.SseEmitter(),
                            runRegistry,
                            org.mockito.Mockito.mock(
                                    io.github.samzhu.bot.execution.internal.RunHandle
                                            .TerminalCallback.class));
            var calls = new java.util.concurrent.atomic.AtomicInteger();
            ChatModel fake =
                    new ChatModel() {
                        @Override
                        public ChatResponse call(Prompt p) {
                            throw new AssertionError();
                        }

                        @Override
                        public Flux<ChatResponse> stream(Prompt p) {
                            calls.incrementAndGet();
                            return Flux.just(
                                    new ChatResponse(
                                            List.of(new Generation(new AssistantMessage("ok")))));
                        }
                    };
            var metadata = invocation.dispatchMetadata(run, workspace, "dev-user");
            var partition = metadata.simulation() ? io.github.samzhu.bot.usage.WorkspaceAdmission.Partition.SIMULATION : io.github.samzhu.bot.usage.WorkspaceAdmission.Partition.REAL;
            var registered = admission.register(new io.github.samzhu.bot.usage.WorkspaceAdmission.Register(workspace, run, "dev-user", conversation, partition)).toCompletableFuture().join();
            assertInstanceOf(io.github.samzhu.bot.usage.WorkspaceAdmission.RegisterResult.Registered.class, registered);
            var registration = ((io.github.samzhu.bot.usage.WorkspaceAdmission.RegisterResult.Registered)registered).registration();
            assertInstanceOf(io.github.samzhu.bot.usage.WorkspaceAdmission.PublicationResult.Ready.class, admission.publish(registration).toCompletableFuture().join());
            handle.setAdmissionRegistration(registration);
            var first = invocation.begin(metadata, registration);
            invocation.stream(
                            fake,
                            new Prompt("one"),
                            first,
                            conversation,
                            workspace,
                            "dev-user",
                            handle)
                    .collectList()
                    .block();
            var second = invocation.begin(metadata, registration);
            try {
                if (revoke)
                    jdbc.update(
                            "update workspace_member set active=false where workspace_id=? and"
                                + " user_id='dev-user'",
                            workspace);
                else handle.requestCancellation();
                assertThrows(
                        RuntimeException.class,
                        () ->
                                invocation.stream(
                                                fake,
                                                new Prompt("two"),
                                                second,
                                                conversation,
                                                workspace,
                                                "dev-user",
                                                handle)
                                        .collectList()
                                        .block());
                assertEquals(1, calls.get());
                assertEquals("SUCCEEDED", ledger.readPricingInput(first.id()).executionOutcome());
                assertEquals(
                        "NOT_DISPATCHED", ledger.readPricingInput(second.id()).executionOutcome());
            } finally {
                if (revoke)
                    jdbc.update(
                            "update workspace_member set active=true where workspace_id=? and"
                                + " user_id='dev-user'",
                            workspace);
            }
        }
    }

    @Test
    void preparedAttemptHasNoDispatchTimestamp() throws Exception {
        String workspace =
                graph("{ personalWorkspace { id } }")
                        .path("data")
                        .path("personalWorkspace")
                        .path("id")
                        .asText();
        String id =
                ledger.begin(
                        new io.github.samzhu.bot.usage.UsageLedger.Dispatch(
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
        String workspace =
                graph("{ personalWorkspace { id } }")
                        .path("data")
                        .path("personalWorkspace")
                        .path("id")
                        .asText();
        String id =
                ledger.begin(
                        new io.github.samzhu.bot.usage.UsageLedger.Dispatch(
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
                new io.github.samzhu.bot.usage.UsageLedger.Snapshot(
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
                new io.github.samzhu.bot.usage.UsageLedger.Snapshot(
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
                new io.github.samzhu.bot.usage.UsageLedger.Snapshot(
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
        String workspace = graph("{ personalWorkspace { id } }")
                .path("data").path("personalWorkspace").path("id").asText();
        String id = ledger.begin(new io.github.samzhu.bot.usage.UsageLedger.Dispatch(
                null, workspace, "dev-user", "FAKE", "test", "m", "FAKE", "1", true, "VERIFIED"));
        ledger.append(id, new io.github.samzhu.bot.usage.UsageLedger.Snapshot(
                "earlier-raw", 1, "CUMULATIVE", "FAKE_RAW", "1", "1", "1", "COMPLETE", "{\"input\":12}"));
        ledger.finish(id, "UNKNOWN");
        var view = ledger.readPricingInput(id);
        assertEquals("UNKNOWN", view.projectionState());
        assertEquals("CAPTURE_FAILED", view.projectionReason());
        assertNull(view.effectiveSnapshotId());
        assertEquals("{\"input\": 12}", jdbc.queryForObject(
                "select raw_usage::text from usage_snapshot where attempt_id=? and event_id='earlier-raw'", String.class, id));
    }

    @Test void captureWriteFailureMarksAttemptUnknownWithoutLeakingException() throws Exception {
        jdbc.update("insert into bot(id,name,target) values ('usage-test-bot','Fake','LOCAL') on conflict do nothing");
        String conversation=graph("mutation { createConversation(botId: \"usage-test-bot\") {id} }").path("data").path("createConversation").path("id").asText();
        String run=UUID.randomUUID().toString();
        org.mockito.Mockito.doThrow(new IllegalStateException("private-error-sentinel")).doCallRealMethod().when(ledger).append(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any());
        try {
            var body=Map.of("threadId",conversation,"runId",run,"messages",List.of(Map.of("id","u","role","user","content","hello")));
            var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/ag-ui")).header("Content-Type","application/json").header("Accept","text/event-stream").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
            var response=http.send(request,HttpResponse.BodyHandlers.ofString());
            assertEquals("UNKNOWN",jdbc.queryForObject("select execution_outcome from provider_attempt where run_id=?",String.class,run));
            assertEquals("UNKNOWN",jdbc.queryForObject("select f.terminal_kind from workspace_admission_finalization f join provider_attempt a on a.id=f.attempt_id where a.run_id=?",String.class,run));
            assertEquals("SETTLED",jdbc.queryForObject("select f.state from workspace_admission_finalization f join provider_attempt a on a.id=f.attempt_id where a.run_id=?",String.class,run));
            assertFalse(response.body().contains("private-error-sentinel"));
            var next=Map.of("threadId",conversation,"runId",UUID.randomUUID().toString(),"messages",List.of(Map.of("id","u2","role","user","content","next")));
            var retry=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/ag-ui")).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(next))).build();
            var nextResponse=http.send(retry,HttpResponse.BodyHandlers.ofString());
            assertEquals(200,nextResponse.statusCode(),nextResponse.body());
        } finally { org.mockito.Mockito.reset(ledger); }
    }
    @Test
    void currentCostGraphqlIsAuthorizedAndStaleAmountsAreSuppressed() throws Exception {
        String workspace=graph("{ personalWorkspace { id } }").path("data").path("personalWorkspace").path("id").asText();
        String id=ledger.begin(new io.github.samzhu.bot.usage.UsageLedger.Dispatch(null,workspace,currentUser.getCurrentUserId(),"GOOGLE","generateContent","unknown-model","GEMINI_API","1",false,"UNKNOWN"));
        assertTrue(ledger.markDispatched(id,java.time.Clock::instant));
        ledger.finish(id,"SUCCEEDED");
        assertTrue(pricingPublisher.recompute(id));
        String query="{ myUsageAttempt(workspaceId: \""+workspace+"\", id: \""+id+"\") { cost { status amount knownSubtotal completeAmount currency simulation needsReprice sourceProjectionVersion estimateSourceVersion currentRevision priceRevision priceSnapshotId priceSourceReference priceEffectiveFrom priceEffectiveUntil calculatorVersion sourceSnapshotId sourceLayer schemaVersion adapterVersion captureVersion captureCompleteness semantic ordinal reasons lineItems { category nativeQuantity unit meaning rate rateBasis exactAmount storedAmount reasons } } } }";
        var response=graph(query);
        assertFalse(response.has("errors"),response.toString());
        var cost=response.path("data").path("myUsageAttempt").path("cost");
        assertEquals("UNKNOWN",cost.path("status").asText()); assertTrue(cost.path("completeAmount").isNull());
        assertFalse(cost.path("needsReprice").asBoolean());
        assertEquals(cost.path("sourceProjectionVersion").asText(),cost.path("estimateSourceVersion").asText());
        ledger.append(id,new io.github.samzhu.bot.usage.UsageLedger.Snapshot("cost-late",1,"CUMULATIVE","FAKE_RAW","1","1","1","COMPLETE","{\"promptTokenCount\":7}"));
        var stale=graph(query).path("data").path("myUsageAttempt").path("cost");
        assertEquals("UNKNOWN",stale.path("status").asText()); assertTrue(stale.path("needsReprice").asBoolean());
        assertTrue(stale.path("knownSubtotal").isNull()); assertTrue(stale.path("completeAmount").isNull());
        assertTrue(stale.path("reasons").toString().contains("SOURCE_CHANGED"));
        String other="pricing-private-"+UUID.randomUUID();
        jdbc.update("insert into workspace(id,name) values (?,?)",other,"private");
        var denied=graph("{ myUsageAttempt(workspaceId: \""+other+"\", id: \""+id+"\") { cost { status } } }");
        assertTrue(denied.has("errors"),denied.toString());
    }
    @Test
    void pricedCostGraphqlShowsImmutablePriceProvenanceAndExactAmounts() throws Exception {
        String workspace=graph("{ personalWorkspace { id } }").path("data").path("personalWorkspace").path("id").asText();
        String rev="graphql-pricing-"+UUID.randomUUID();
        var rates=Map.of("input","1","cache_read","0.1","output","2","thoughts","2","tool_input","1");
        var fixture=new io.github.samzhu.bot.pricing.PriceSnapshot("GOOGLE","generateContent","fixture-graphql","GEMINI_API","fixture-price",rates);
        catalogActivation.publish(rev,"fixture-only",List.of(new io.github.samzhu.bot.pricing.PriceCatalog.Entry(fixture,java.time.Instant.parse("2000-01-01T00:00:00Z"),java.time.Instant.parse("2100-01-01T00:00:00Z"))));
        String id=ledger.begin(new io.github.samzhu.bot.usage.UsageLedger.Dispatch(null,workspace,currentUser.getCurrentUserId(),"GOOGLE","generateContent","fixture-graphql","GEMINI_API","1",true,"VERIFIED"));
        assertTrue(ledger.markDispatched(id,java.time.Clock::instant));
        ledger.append(id,new io.github.samzhu.bot.usage.UsageLedger.Snapshot("priced",1,"FINAL","FAKE_RAW","1","1","1","COMPLETE","{\"promptTokenCount\":1000,\"cachedContentTokenCount\":200,\"candidatesTokenCount\":300,\"thoughtsTokenCount\":100,\"toolUsePromptTokenCount\":0}"));
        ledger.finish(id,"SUCCEEDED");
        assertTrue(pricingPublisher.recompute(id));
        String q="{ myUsageAttempt(workspaceId: \""+workspace+"\",id: \""+id+"\") { cost { status knownSubtotal completeAmount priceRevision priceSnapshotId priceSourceReference priceEffectiveFrom priceEffectiveUntil reasons lineItems { category nativeQuantity rate exactAmount storedAmount reasons } } } }";
        var result=graph(q); assertFalse(result.has("errors"),result.toString());
        var cost=result.path("data").path("myUsageAttempt").path("cost");
        assertEquals("PARTIAL",cost.path("status").asText()); assertEquals("0.001620000000",cost.path("knownSubtotal").asText());
        assertTrue(cost.path("completeAmount").isNull()); assertEquals("fixture-price",cost.path("priceRevision").asText());
        assertEquals("fixture-only",cost.path("priceSourceReference").asText()); assertFalse(cost.path("priceSnapshotId").asText().isEmpty());
        assertEquals("2000-01-01T00:00:00Z",cost.path("priceEffectiveFrom").asText());
        assertEquals("2100-01-01T00:00:00Z",cost.path("priceEffectiveUntil").asText());
        assertTrue(cost.path("reasons").toString().contains("EXTRA_COST_UNKNOWN"));
        assertEquals("800",cost.path("lineItems").get(0).path("nativeQuantity").asText());
    }
    @Test
    void threeTrustedNativeFixtureEstimatesReachPublicGraphqlWithoutRealRuntime() throws Exception {
        String workspace=graph("{ personalWorkspace { id } }").path("data").path("personalWorkspace").path("id").asText();
        var start=java.time.Instant.parse("2000-01-01T00:00:00Z"); var end=java.time.Instant.parse("2100-01-01T00:00:00Z");
        var entries=List.of(
            new io.github.samzhu.bot.pricing.PriceCatalog.Entry(new io.github.samzhu.bot.pricing.PriceSnapshot("FAKE_OPENAI","responses-fixture-ttl","fixture-openai","FAKE","openai-test",Map.of("input","1","cache_read","0.1","cache_write_30m","1.25","cache_write_12h","2.5","output","2")),start,end),
            new io.github.samzhu.bot.pricing.PriceCatalog.Entry(new io.github.samzhu.bot.pricing.PriceSnapshot("FAKE_ANTHROPIC","messages","fixture-anthropic","FAKE","anthropic-test",Map.of("input","1","cache_read","0.1","cache_write_5m","1.25","cache_write_1h","2","output","2")),start,end),
            new io.github.samzhu.bot.pricing.PriceCatalog.Entry(new io.github.samzhu.bot.pricing.PriceSnapshot("FAKE_GEMINI","generateContent","fixture-gemini","FAKE","gemini-test",Map.of("input","1","cache_read","0.1","output","2","thoughts","2","tool_input","1")),start,end));
        catalogActivation.publish("pricing-fixtures-"+UUID.randomUUID(),"synthetic-only",entries);
        String[][] fixtures={
          {"FAKE_OPENAI","responses-fixture-ttl","fixture-openai","{\"input_tokens\":1000,\"input_tokens_details\":{\"cached_tokens\":200,\"cache_write_tokens\":300,\"cache_write\":{\"30m\":100,\"12h\":200}},\"output_tokens\":400,\"output_tokens_details\":{\"reasoning_tokens\":100}}","0.001945000000"},
          {"FAKE_ANTHROPIC","messages","fixture-anthropic","{\"input_tokens\":500,\"cache_read_input_tokens\":200,\"cache_creation_input_tokens\":300,\"cache_creation\":{\"ephemeral_5m_input_tokens\":100,\"ephemeral_1h_input_tokens\":200},\"output_tokens\":400}","0.001845000000"},
          {"FAKE_GEMINI","generateContent","fixture-gemini","{\"promptTokenCount\":1000,\"cachedContentTokenCount\":200,\"candidatesTokenCount\":300,\"thoughtsTokenCount\":100,\"toolUsePromptTokenCount\":0,\"totalTokenCount\":1400}","0.001620000000"}};
        for(var f:fixtures) {
            String id=ledger.begin(new io.github.samzhu.bot.usage.UsageLedger.Dispatch(null,workspace,currentUser.getCurrentUserId(),f[0],f[1],f[2],"FAKE","1",true,"VERIFIED"));
            assertTrue(ledger.markDispatched(id,java.time.Clock::instant));
            ledger.append(id,new io.github.samzhu.bot.usage.UsageLedger.Snapshot("fixture",1,"FINAL","FAKE_RAW","pricing-fixture-v1","1","1","COMPLETE",f[3]));
            ledger.finish(id,"SUCCEEDED"); assertTrue(pricingPublisher.recompute(id)); assertFalse(pricingPublisher.recompute(id));
            var response=graph("{ myUsageAttempt(workspaceId: \""+workspace+"\", id: \""+id+"\") { cost { status simulation completeAmount knownSubtotal currency reasons } } }");
            assertFalse(response.has("errors"),response.toString()); var cost=response.path("data").path("myUsageAttempt").path("cost");
            assertEquals("COMPLETE",cost.path("status").asText()); assertTrue(cost.path("simulation").asBoolean());
            assertEquals(f[4],cost.path("completeAmount").asText()); assertEquals(f[4],cost.path("knownSubtotal").asText()); assertEquals("USD",cost.path("currency").asText());
            if ("FAKE_OPENAI".equals(f[0])) {
                // Test-only fault injection: a changed rate alters money under the same identity.
                jdbc.update("update usage_price_snapshot set rates=jsonb_set(rates,'{input}',to_jsonb('2'::text)) where attempt_id=?",id);
                try {
                    assertThrows(io.github.samzhu.bot.usage.internal.pricing.PricingPublisher.Conflict.class,() -> pricingPublisher.recompute(id));
                    assertEquals(1,jdbc.queryForObject("select count(*) from usage_cost_estimate where attempt_id=?",Integer.class,id));
                    assertEquals(f[4],graph("{ myUsageAttempt(workspaceId: \""+workspace+"\",id: \""+id+"\") { cost { completeAmount } } }").path("data").path("myUsageAttempt").path("cost").path("completeAmount").asText());
                } finally {
                    jdbc.update("update usage_price_snapshot set rates=jsonb_set(rates,'{input}',to_jsonb('1'::text)) where attempt_id=?",id);
                }
            }
        }
        String unsupported=ledger.begin(new io.github.samzhu.bot.usage.UsageLedger.Dispatch(null,workspace,currentUser.getCurrentUserId(),"FAKE_GEMINI","generateContent","fixture-gemini","FAKE","1",true,"VERIFIED"));
        assertTrue(ledger.markDispatched(unsupported,java.time.Clock::instant));
        String unsupportedRaw=fixtures[2][3].replace("{","{\"unknown_modality\":1,");
        ledger.append(unsupported,new io.github.samzhu.bot.usage.UsageLedger.Snapshot("unsupported",1,"FINAL","FAKE_RAW","pricing-fixture-v1","1","1","COMPLETE",unsupportedRaw));
        ledger.finish(unsupported,"SUCCEEDED"); assertTrue(pricingPublisher.recompute(unsupported));
        var unsafe=graph("{ myUsageAttempt(workspaceId: \""+workspace+"\", id: \""+unsupported+"\") { cost { status completeAmount reasons } } }");
        var cost=unsafe.path("data").path("myUsageAttempt").path("cost");
        assertNotEquals("COMPLETE",cost.path("status").asText()); assertTrue(cost.path("completeAmount").isNull());
        assertTrue(cost.path("reasons").toString().contains("EXTRA_COST_UNKNOWN"));
    }
}
