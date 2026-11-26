package io.github.samzhu.bot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.samzhu.bot.execution.ExecutionOperations;
import io.github.samzhu.bot.usage.pricing.UsageCaseData;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Sinks;

import static org.junit.jupiter.api.Assertions.*;

@BackendIntegrationTest
class WorkspaceAuthorizationIntegrationTests {
    private static final String CALLER = "dev-user";

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate transactions;
    @Autowired BackendTestChatModel model;
    @Autowired ExecutionOperations executions;

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();
    private final String scope = "workspace-auth-" + UUID.randomUUID();
    private final String localBot = UUID.randomUUID().toString();
    private final String remoteBot = UUID.randomUUID().toString();
    private final String messageId = UUID.randomUUID().toString();
    private UsageCaseData caseData;

    @BeforeEach
    void prepareCase() {
        model.reset();
        caseData = new UsageCaseData(jdbc, transactions);
    }

    @AfterEach
    void cleanCase() throws InterruptedException {
        try {
            caseData.cleanUp();
            transactions.executeWithoutResult(status -> {
                jdbc.update("delete from conversation where user_id=? and bot_id=?", CALLER, localBot);
                jdbc.update("delete from bot where id in (?,?) and owner_id=?", localBot, remoteBot, CALLER);
            });
        } finally {
            http.close();
            model.reset();
        }
    }

    @Test
    void personalAndInvalidScopeAreDistinct() throws Exception {
        var data = graphData(graph("{ personalWorkspace { id } workspaces { id name role personal } }"));
        String personal = data.path("personalWorkspace").path("id").asText();
        assertFalse(personal.isBlank(), data.toString());
        var workspaces = data.path("workspaces");
        assertTrue(workspaces.isArray(), data.toString());
        boolean foundPersonal = false;
        for (var workspace : workspaces) {
            if (personal.equals(workspace.path("id").asText())) {
                assertTrue(workspace.path("personal").asBoolean(), workspace.toString());
                foundPersonal = true;
            }
        }
        assertTrue(foundPersonal, data.toString());
        assertGraphCode("WORKSPACE_ACCESS_DENIED",
                graph("{ conversations(workspaceId: \"%s\") { id } }".formatted(scope)));
    }

    @Test
    void deniedStartHasNoSideEffects() throws Exception {
        String missing = UUID.randomUUID().toString();
        assertJsonError(post("/api/ag-ui", runInput(missing, UUID.randomUUID().toString(), "hello")),
                404, "CONVERSATION_ACCESS_DENIED");
        assertNoRunEffects(missing);
    }

    @Test
    void scopeOwnerAndRevocationProtectAllPaths() throws Exception {
        prepareWorkspace();
        String owner = UUID.randomUUID().toString();
        assertEquals(1, jdbc.update("update workspace_member set role='MEMBER' where workspace_id=? and user_id=?",
                scope, CALLER));
        var created = createConversation("workspaceId: \"%s\"".formatted(scope), scope);
        String owned = created.path("id").asText();
        assertEquals(scope, created.path("workspaceId").asText());

        assertEquals(Set.of(), conversationIds("search: \"%s\"".formatted(scope)));
        assertEquals(Set.of(), conversationIds("workspaceId: null, search: \"%s\"".formatted(scope)));
        assertEquals(Set.of(owned), conversationIds("workspaceId: \"%s\"".formatted(scope)));

        // Preserve the legacy negative check: even a workspace manager cannot access a member's conversation.
        assertEquals(1, jdbc.update("update workspace_member set role='MANAGER' where workspace_id=? and user_id=?",
                scope, CALLER));
        jdbc.update("insert into workspace_member(workspace_id,user_id,role,active) values (?,?,'MEMBER',true)",
                scope, owner);
        String foreign = UUID.randomUUID().toString();
        jdbc.update("insert into conversation(id,bot_id,title,user_id,workspace_id) values (?,?,?,?,?)",
                foreign, localBot, "Foreign conversation", owner, scope);
        assertTrue(graphData(graph("{ conversation(id: \"%s\") { id messages { id } runs { id } } }"
                .formatted(foreign))).path("conversation").isNull());
        assertGraphCode("CONVERSATION_ACCESS_DENIED",
                graph("mutation { setConversationPinned(id: \"%s\", pinned: true) { id } }".formatted(foreign)));
        var denied = post("/api/ag-ui", runInput(foreign, UUID.randomUUID().toString(), "hello"));
        assertJsonError(denied, 404, "CONVERSATION_ACCESS_DENIED");
        assertFalse(denied.body().contains(foreign));
        assertNoRunEffects(foreign);

        assertEquals(1, jdbc.update("update workspace_member set role='MEMBER' where workspace_id=? and user_id=?",
                scope, CALLER));
        revokeCaller();
        assertGraphCode("WORKSPACE_ACCESS_DENIED",
                graph("{ conversations(workspaceId: \"%s\") { id } }".formatted(scope)));
        assertTrue(graphData(graph("{ conversation(id: \"%s\") { id } }".formatted(owned)))
                .path("conversation").isNull());
    }

    @Test
    void explicitNullAndOmittedScopeResolvePersonal() throws Exception {
        insertBot(localBot, "LOCAL");
        String personal = graphData(graph("{ personalWorkspace { id } }")).path("personalWorkspace").path("id").asText();
        assertFalse(personal.isBlank());
        var omitted = createConversation("", scope + " omitted");
        var explicit = createConversation("workspaceId: null", scope + " explicit");

        assertEquals(personal, omitted.path("workspaceId").asText());
        assertEquals(personal, explicit.path("workspaceId").asText());
        assertEquals(Set.of(omitted.path("id").asText(), explicit.path("id").asText()),
                conversationIds("workspaceId: null, search: \"%s\"".formatted(scope)));
    }

    @Test
    void legacyStartAndCancelMissingAreNonLeaking() throws Exception {
        String missingConversation = UUID.randomUUID().toString();
        String missingRun = UUID.randomUUID().toString();
        var start = post("/api/conversations/" + missingConversation + "/runs",
                Map.of("clientRequestId", UUID.randomUUID().toString(), "message", "hello"));
        assertJsonError(start, 404, "CONVERSATION_ACCESS_DENIED");
        assertFalse(start.body().contains(missingConversation));
        var cancel = post("/api/runs/" + missingRun + "/cancel", Map.of());
        assertJsonError(cancel, 404, "RUN_ACCESS_DENIED");
        assertFalse(cancel.body().contains(missingRun));
        assertNoRunEffects(missingConversation);
    }

    @Test
    void revokedConversationRejectsSseAcceptWithoutSideEffects() throws Exception {
        prepareWorkspace();
        String conversation = createConversation("workspaceId: \"%s\"".formatted(scope), "Revoked fixture")
                .path("id").asText();
        revokeCaller();
        var denied = postSse(runInput(conversation, UUID.randomUUID().toString(), "hello"));
        assertJsonError(denied, 404, "CONVERSATION_ACCESS_DENIED");
        assertNoRunEffects(conversation);
    }

    @Test
    void httpAgentAcceptPreservesSuccessAndErrors() throws Exception {
        assertJsonError(postSse(Map.of()), 400, "INVALID_AG_UI_INPUT");
        assertJsonError(postSse(runInput(UUID.randomUUID().toString(), UUID.randomUUID().toString(), "hi")),
                404, "CONVERSATION_ACCESS_DENIED");

        prepareWorkspace();
        String conversation = createConversation("workspaceId: \"%s\"".formatted(scope), "Agent fixture")
                .path("id").asText();
        String run = UUID.randomUUID().toString();
        var request = runInput(conversation, run, "hi");
        var success = postSse(request);
        assertEquals(200, success.statusCode(), success.body());
        assertTrue(contentType(success).startsWith("text/event-stream"));
        assertTrue(success.body().contains("RUN_FINISHED"), success.body());
        assertJsonError(postSse(request), 409, "DUPLICATE_RUN");
        assertJsonError(postSse(runInput(conversation, run, "changed")), 409, "RUN_CONFLICT");

        insertBot(remoteBot, "GKE_REMOTE");
        // OFF prevents GraphQL creation for this Bot. Seed an existing conversation to exercise the Run path.
        String remoteConversation = UUID.randomUUID().toString();
        jdbc.update("insert into conversation(id,bot_id,title,user_id,workspace_id) values (?,?,?,?,?)",
                remoteConversation, remoteBot, "Remote fixture", CALLER, scope);
        assertJsonError(postSse(runInput(remoteConversation, UUID.randomUUID().toString(), "hi")),
                503, "GKE_AGENT_NOT_CONNECTED");
        assertEquals(1, model.invocations());
        assertEquals(0, jdbc.queryForObject("select count(*) from chat_run where conversation_id=?",
                Integer.class, remoteConversation));
    }

    @Test
    void activeCancellationRechecksMembership() throws Exception {
        prepareWorkspace();
        String foreignActor = UUID.randomUUID().toString();
        jdbc.update("insert into workspace_member(workspace_id,user_id,role) values (?,?,'MEMBER')",
                scope, foreignActor);
        String conversation = createConversation("workspaceId: \"%s\"".formatted(scope), "Cancellation fixture")
                .path("id").asText();
        String run = UUID.randomUUID().toString();
        String missingRun = UUID.randomUUID().toString();
        String cancelPath = "/api/runs/" + run + "/cancel";
        String message = "hello";
        var input = runInput(conversation, run, message);
        var pending = Sinks.<ChatResponse>one();
        var subscribed = new CountDownLatch(1);
        model.streamWith(pending.asMono().doOnSubscribe(subscription -> subscribed.countDown()).flux());

        try {
            var stream = http.send(request("/api/ag-ui", input, "text/event-stream"),
                    HttpResponse.BodyHandlers.ofInputStream());
            try (var streamBody = stream.body()) {
                assertEquals(200, stream.statusCode());
                assertTrue(contentType(stream).startsWith("text/event-stream"));
                assertTrue(subscribed.await(5, TimeUnit.SECONDS), "The original Run never subscribed to the model");
                assertRunStatus(run, "RUNNING");

                // HTTP has one configured identity. The public Execution facade accepts a second actor explicitly.
                assertTrue(executions.findRunById(run, foreignActor).isEmpty());
                var foreign = executions.cancelRun(run, foreignActor);
                assertEquals(new ExecutionOperations.CancelRunOutcome(null, null, false, true), foreign);
                assertEquals(executions.cancelRun(missingRun, foreignActor), foreign);
                var duplicate = executions.startAgUiRun(conversation, run, message, foreignActor);
                assertEquals(new ExecutionOperations.StartRunOutcome(
                        ExecutionOperations.StartRunOutcome.Type.ACCESS_DENIED, null, null, null), duplicate);
                assertRunStatus(run, "RUNNING");
                assertEquals(1, model.invocations());

                revokeCaller();
                assertTrue(graphData(graph("{ run(id: \"%s\") { id } }".formatted(run))).path("run").isNull());
                var denied = post(cancelPath, Map.of());
                assertJsonError(denied, 404, "RUN_ACCESS_DENIED");
                assertEquals(post("/api/runs/" + missingRun + "/cancel", Map.of()).body(), denied.body());
                assertFalse(denied.body().contains(run));
                assertJsonError(postSse(input), 404, "CONVERSATION_ACCESS_DENIED");
                assertRunStatus(run, "RUNNING");
                assertEquals(1, model.invocations());

                assertEquals(1, jdbc.update(
                        "update workspace_member set active=true where workspace_id=? and user_id=?", scope, CALLER));
                var cancelled = post(cancelPath, Map.of());
                assertEquals(200, cancelled.statusCode(), cancelled.body());
                var result = json.readTree(cancelled.body());
                assertEquals(run, result.path("runId").asText());
                assertEquals("CANCELLED", result.path("status").asText());
                assertTrue(result.path("cancelled").asBoolean());
                assertRunStatus(run, "CANCELLED");
                assertEquals(1, model.invocations());
            }
        } finally {
            pending.tryEmitEmpty();
        }
    }

    private void assertRunStatus(String run, String expected) {
        assertEquals(expected, jdbc.queryForObject("select status from chat_run where id=?", String.class, run));
    }

    private void prepareWorkspace() {
        caseData.createWorkspace(scope, CALLER);
        insertBot(localBot, "LOCAL");
    }

    private void insertBot(String bot, String target) {
        jdbc.update("insert into bot(id,name,target,owner_id) values (?,'Workspace authorization fixture',?,?)",
                bot, target, CALLER);
    }

    private void revokeCaller() {
        assertEquals(1, jdbc.update("update workspace_member set active=false where workspace_id=? and user_id=?",
                scope, CALLER));
    }

    private JsonNode createConversation(String scopeArgument, String title) throws Exception {
        String query = """
                mutation { createConversation(botId: "%s", title: "%s"%s) { id workspaceId } }
                """.formatted(localBot, title, scopeArgument.isEmpty() ? "" : ", " + scopeArgument);
        var created = graphData(graph(query)).path("createConversation");
        assertFalse(created.path("id").asText().isBlank(), created.toString());
        return created;
    }

    private Set<String> conversationIds(String arguments) throws Exception {
        var conversations = graphData(graph("{ conversations(%s) { id } }".formatted(arguments)))
                .path("conversations");
        assertTrue(conversations.isArray(), conversations.toString());
        Set<String> ids = new HashSet<>();
        conversations.forEach(conversation -> assertTrue(ids.add(conversation.path("id").asText())));
        return ids;
    }

    private void assertNoRunEffects(String conversation) {
        assertEquals(0, model.invocations());
        assertEquals(0, jdbc.queryForObject("select count(*) from chat_run where conversation_id=?",
                Integer.class, conversation));
        assertEquals(0, jdbc.queryForObject("select count(*) from message where conversation_id=?",
                Integer.class, conversation));
        assertEquals(0, jdbc.queryForObject("select count(*) from ai_session where id=?",
                Integer.class, conversation));
    }

    private Map<String, Object> runInput(String thread, String run, String message) {
        return Map.of("threadId", thread, "runId", run, "messages",
                List.of(Map.of("id", messageId, "role", "user", "content", message)));
    }

    private JsonNode graph(String query) throws Exception {
        var response = post("/graphql", Map.of("query", query));
        assertEquals(200, response.statusCode(), response.body());
        return json.readTree(response.body());
    }

    private JsonNode graphData(JsonNode response) {
        assertFalse(response.has("errors"), response.toString());
        return response.path("data");
    }

    private void assertGraphCode(String code, JsonNode response) {
        assertEquals(code, response.path("errors").path(0).path("extensions").path("code").asText(),
                response.toString());
    }

    private void assertJsonError(HttpResponse<String> response, int status, String code) throws Exception {
        assertEquals(status, response.statusCode(), response.body());
        assertTrue(contentType(response).startsWith("application/json"));
        assertEquals(code, json.readTree(response.body()).path("code").asText());
    }

    private String contentType(HttpResponse<?> response) {
        return response.headers().firstValue("content-type").orElse("");
    }

    private HttpResponse<String> post(String path, Object body) throws Exception {
        return post(path, body, "*/*");
    }

    private HttpResponse<String> postSse(Object body) throws Exception {
        return post("/api/ag-ui", body, "text/event-stream");
    }

    private HttpResponse<String> post(String path, Object body, String accept) throws Exception {
        return http.send(request(path, body, accept), HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest request(String path, Object body, String accept) throws Exception {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("Accept", accept)
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                .build();
    }
}
