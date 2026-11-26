package io.github.samzhu.bot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.samzhu.bot.conversation.ConversationTitleGenerator;
import io.github.samzhu.bot.usage.pricing.UsageCaseData;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import static org.assertj.core.api.Assertions.assertThat;

@BackendIntegrationTest
class SharedConversationTitleHttpIntegrationTests {
    private static final String DEFAULT_TITLE = "New Conversation";
    private static final String CREATED_TITLE = "Fixture-generated title";

    @LocalServerPort int port;
    @Autowired ApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate transactions;
    @Autowired BackendTestChatModel model;

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final String workspace = "title-fixture-" + UUID.randomUUID();
    private final String prompt = "Title fixture " + UUID.randomUUID();
    private final String run = UUID.randomUUID().toString();
    private UsageCaseData fixture;

    @BeforeEach
    void createWorkspace() {
        model.reset();
        fixture = new UsageCaseData(jdbc, transactions);
        fixture.createWorkspace(workspace, "dev-user");
    }

    @AfterEach
    void cleanWorkspace() throws InterruptedException {
        try {
            jdbc.update("delete from workspace_title_reservation where workspace_id = ?", workspace);
            fixture.cleanUp();
        } finally {
            http.shutdownNow();
            model.reset();
        }
    }

    @Test
    void firstVisibleReplyGeneratesAndPublishesATitle() throws Exception {
        assertThat(context.getBeansOfType(ChatModel.class).values()).containsExactly(model);
        assertThat(context.getBeansOfType(ConversationTitleGenerator.class).values()).containsExactly(model);
        model.titleFor(prompt, CREATED_TITLE);
        String conversation = createConversation(null, DEFAULT_TITLE);
        assertTitleWork(conversation, DEFAULT_TITLE, "WAITING", 0L);

        assertThat(assertRunFinished(post("/api/ag-ui", runInput(conversation), "text/event-stream")))
                .extracting(event -> event.path("type").asText()).contains("TEXT_MESSAGE_CONTENT");
        assertTitleNotification(conversation, "DONE", "1");
        assertThat(readTitle(conversation)).isEqualTo(CREATED_TITLE);
        assertTitleWork(conversation, CREATED_TITLE, "DONE", 1L);
        assertThat(runStatus()).isEqualTo("COMPLETED");
        assertThat(model.titleInputs()).containsExactly(prompt);
        assertNoTitleLedger();
    }

    @Test
    void namedConversationCompletesRunWithoutTitleWork() throws Exception {
        String named = "Explicit fixture title";
        String conversation = createConversation(named, named);
        model.titleFor(prompt, CREATED_TITLE);
        assertThat(assertRunFinished(post("/api/ag-ui", runInput(conversation), "text/event-stream")))
                .extracting(event -> event.path("type").asText()).contains("TEXT_MESSAGE_CONTENT");

        assertThat(readTitle(conversation)).isEqualTo(named);
        assertThat(jdbc.queryForObject("select count(*) from conversation_title_work where conversation_id = ?",
                Integer.class, conversation)).isZero();
        assertThat(model.titleInputs()).isEmpty();
        assertNoTitleLedger();
    }

    @Test
    void failureBeforeVisibleReplyKeepsTheDefaultTitle() throws Exception {
        String conversation = createConversation(null, DEFAULT_TITLE);
        model.titleFor(prompt, CREATED_TITLE);
        model.streamWith(Flux.error(new IllegalStateException("Synthetic model failure")));

        var events = events(post("/api/ag-ui", runInput(conversation), "text/event-stream"));
        assertThat(events).extracting(event -> event.path("type").asText())
                .doesNotContain("TEXT_MESSAGE_CONTENT").endsWith("RUN_ERROR");
        assertThat(runStatus()).isEqualTo("FAILED");
        assertUnchangedDefaultTitle(conversation);
    }

    @Test
    void cancellationBeforeVisibleReplyKeepsTheDefaultTitle() throws Exception {
        String conversation = createConversation(null, DEFAULT_TITLE);
        model.titleFor(prompt, CREATED_TITLE);
        var pending = Sinks.<ChatResponse>one();
        var subscribed = new CountDownLatch(1);
        model.streamWith(pending.asMono().doOnSubscribe(subscription -> subscribed.countDown()).flux());

        var stream = http.sendAsync(request("/api/ag-ui", runInput(conversation), "text/event-stream"),
                HttpResponse.BodyHandlers.ofString());
        try {
            assertThat(subscribed.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(runStatus()).isEqualTo("RUNNING");
            var cancel = post("/api/runs/" + run + "/cancel", Map.of(), "application/json");
            assertThat(cancel.statusCode()).isEqualTo(200);
            var cancelled = json.readTree(cancel.body());
            assertThat(cancelled.path("status").asText()).isEqualTo("CANCELLED");
            assertThat(cancelled.path("cancelled").asBoolean()).isTrue();
            var events = assertRunFinished(stream.get(8, TimeUnit.SECONDS));
            assertThat(events.getLast().path("outcome").path("type").asText()).isEqualTo("cancelled");
            assertThat(events).extracting(event -> event.path("type").asText()).doesNotContain("TEXT_MESSAGE_CONTENT");
            assertThat(runStatus()).isEqualTo("CANCELLED");
            assertUnchangedDefaultTitle(conversation);
        } finally {
            pending.tryEmitEmpty();
        }
    }

    private void assertUnchangedDefaultTitle(String conversation) throws Exception {
        assertTitleNotification(conversation, "UNAVAILABLE", "0");
        assertThat(readTitle(conversation)).isEqualTo(DEFAULT_TITLE);
        assertTitleWork(conversation, DEFAULT_TITLE, "WAITING", 0L);
        assertThat(model.titleInputs()).isEmpty();
        assertNoTitleLedger();
    }

    private void assertTitleWork(String conversation, String title, String state, long version) {
        assertThat(jdbc.queryForMap("""
                select c.title, w.state, w.version
                from conversation c join conversation_title_work w on w.conversation_id = c.id
                where c.id = ? and c.workspace_id = ?
                """, conversation, workspace)).containsExactlyInAnyOrderEntriesOf(
                        Map.of("title", title, "state", state, "version", version));
    }

    private void assertNoTitleLedger() {
        assertThat(jdbc.queryForObject("""
                select (select count(*) from workspace_title_reservation where workspace_id = ?)
                     + (select count(*) from provider_attempt where workspace_id = ? and purpose = 'CONVERSATION_TITLE')
                """, Long.class, workspace, workspace)).isZero();
    }

    private void assertTitleNotification(String conversation, String state, String version) throws Exception {
        var response = post("/graphql", Map.of(
                "query", """
                        subscription Title($id: ID!) {
                            conversationTitleChanged(conversationId: $id) { id state version }
                        }
                        """, "variables", Map.of("id", conversation)), "text/event-stream");
        var updates = events(response);
        assertThat(updates).hasSize(1);
        var change = updates.getFirst().path("data").path("conversationTitleChanged");
        assertThat(change.path("id").asText()).isEqualTo(conversation);
        assertThat(change.path("state").asText()).isEqualTo(state);
        assertThat(change.path("version").asText()).isEqualTo(version);
    }

    private List<JsonNode> assertRunFinished(HttpResponse<String> response) throws Exception {
        var events = events(response);
        var terminal = events.getLast();
        assertThat(terminal.path("type").asText()).isEqualTo("RUN_FINISHED");
        assertThat(terminal.path("runId").asText()).isEqualTo(run);
        return events;
    }

    private List<JsonNode> events(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("text/event-stream");
        var events = new ArrayList<JsonNode>();
        for (String line : response.body().split("\\R")) {
            if (line.startsWith("data:") && !line.substring(5).isBlank()) {
                events.add(json.readTree(line.substring(5)));
            }
        }
        return events;
    }

    private String createConversation(String title, String expected) throws Exception {
        var variables = new HashMap<String, String>();
        variables.put("workspace", workspace);
        if (title != null) {
            variables.put("title", title);
        }
        var conversation = graphql("""
                mutation Conversation($workspace: ID!, $title: String) {
                    createConversation(botId: "bot-general", workspaceId: $workspace, title: $title) {
                        id workspaceId title
                    }
                }
                """, variables).path("createConversation");
        assertThat(conversation.path("workspaceId").asText()).isEqualTo(workspace);
        assertThat(conversation.path("title").asText()).isEqualTo(expected);
        String id = conversation.path("id").asText();
        assertThat(id).isNotBlank();
        return id;
    }

    private String readTitle(String conversation) throws Exception {
        return graphql("query Title($id: ID!) { conversation(id: $id) { title } }", Map.of("id", conversation))
                .path("conversation").path("title").asText();
    }

    private JsonNode graphql(String query, Map<String, String> variables) throws Exception {
        var response = post("/graphql", Map.of("query", query, "variables", variables), "application/json");
        assertThat(response.statusCode()).isEqualTo(200);
        var body = json.readTree(response.body());
        assertThat(body.has("errors")).as("GraphQL payload: %s", body).isFalse();
        return body.path("data");
    }

    private String runStatus() {
        return jdbc.queryForObject("select status from chat_run where id = ?", String.class, run);
    }

    private Map<String, Object> runInput(String conversation) {
        return Map.of("threadId", conversation, "runId", run, "messages", List.of(
                Map.of("id", UUID.randomUUID().toString(), "role", "user", "content", prompt)));
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
