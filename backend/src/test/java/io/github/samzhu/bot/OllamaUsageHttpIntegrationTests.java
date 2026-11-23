package io.github.samzhu.bot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.docker.compose.enabled=false", "spring.ai.model.chat=ollama", "spring.ai.ollama.chat.model=fixture-model",
    "bot-google-api-key=synthetic-key-only", "bot.pricing.reconcile.initial-delay-ms=3600000"})
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("local")
class OllamaUsageHttpIntegrationTests {
    static final AtomicInteger chats = new AtomicInteger();
    static final AtomicInteger pulls = new AtomicInteger();
    static final java.util.concurrent.atomic.AtomicBoolean unavailable = new java.util.concurrent.atomic.AtomicBoolean();
    static final HttpServer server = startServer();
    static HttpServer startServer() {
        try {
            var fixture = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            fixture.createContext("/api/chat", exchange -> {
                chats.incrementAndGet();
                if (unavailable.get()) { exchange.sendResponseHeaders(404, -1); exchange.close(); return; }
                byte[] response = ("{\"model\":\"fixture-model\",\"message\":{\"role\":\"assistant\",\"content\":\"fixture \"},\"done\":false}\n" +
                    "{\"model\":\"fixture-model\",\"message\":{\"role\":\"assistant\",\"content\":\"reply\"},\"done\":true}\n").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/x-ndjson");
                exchange.sendResponseHeaders(200, response.length);
                try (var out = exchange.getResponseBody()) { out.write(response); }
            });
            fixture.createContext("/api/pull", exchange -> { pulls.incrementAndGet(); exchange.sendResponseHeaders(500, -1); exchange.close(); });
            fixture.start(); return fixture;
        } catch (Exception ex) { throw new IllegalStateException(ex); }
    }
    @DynamicPropertySource static void endpoint(DynamicPropertyRegistry properties) {
        properties.add("spring.ai.ollama.base-url", () -> "http://127.0.0.1:" + server.getAddress().getPort());
    }
    @AfterAll static void stopFixture() { server.stop(0); }
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    final ObjectMapper json = new ObjectMapper();
    final HttpClient http = HttpClient.newHttpClient();
    String post(String path, Object body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
        var result = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(result.statusCode()).isEqualTo(200);
        return result.body();
    }
    @Test void absentRawCountersRemainUnknownInDurableAndPublicUsage() throws Exception {
        jdbc.update("insert into bot(id,name,system_prompt,created_at) values ('bot-general','General','fixture',current_timestamp) on conflict(id) do nothing");
        var created = json.readTree(post("/graphql", Map.of("query", "mutation { createConversation(botId:\"bot-general\",title:\"Fixture\") { id } }")));
        assertThat(created.path("errors").isMissingNode()).as(created.toString()).isTrue();
        String conversation = created.path("data").path("createConversation").path("id").asText();
        String workspace = jdbc.queryForObject("select workspace_id from conversation where id=?", String.class, conversation);
        String run = UUID.randomUUID().toString();
        var input = Map.of("threadId",conversation,"runId",run,"state",Map.of(),"messages",List.of(Map.of("id",UUID.randomUUID().toString(),"role","user","content","fixture")),"tools",List.of(),"context",List.of(),"forwardedProps",Map.of());
        int beforeChats = chats.get();
        String stream = post("/api/ag-ui", input);
        assertThat(stream).contains("RUN_FINISHED");
        StringBuilder text = new StringBuilder();
        for (String line : stream.split("\\R")) {
            if (line.startsWith("data:")) {
                var event = json.readTree(line.substring(5).trim());
                if ("TEXT_MESSAGE_CONTENT".equals(event.path("type").asText())) text.append(event.path("delta").asText());
            }
        }
        assertThat(text.toString()).isEqualTo("fixture reply");
        assertThat(chats.get()).isEqualTo(beforeChats + 1); assertThat(pulls.get()).isZero();
        var rows = jdbc.queryForList("select id,provider,endpoint,model,channel,simulation,usage_presence,capture_completeness,projection_state,execution_outcome from provider_attempt where run_id=?",run);
        assertThat(rows).hasSize(1);
        var row = rows.getFirst();
        assertThat(row).containsEntry("provider", "OLLAMA").containsEntry("endpoint", "chat").containsEntry("model", "fixture-model")
            .containsEntry("channel", "LOCAL").containsEntry("simulation", false).containsEntry("execution_outcome", "SUCCEEDED");
        String id = row.get("id").toString();
        var snapshots = jdbc.queryForList("select semantic,raw_state,capture_completeness,capture_reason from usage_snapshot where attempt_id=?", id);
        assertThat(snapshots).hasSize(1);
        assertThat(snapshots.getLast()).containsEntry("semantic", "UNKNOWN").containsEntry("raw_state", "UNAVAILABLE")
            .containsEntry("capture_completeness", "INCOMPLETE").containsEntry("capture_reason", "USAGE_UNAVAILABLE");
        String query = "{ myUsageAttempt(workspaceId:\""+workspace+"\",id:\""+id+"\") { provider endpoint model channel simulation usagePresence captureCompleteness projectionState effectiveSnapshotId snapshots { nodes { id semantic rawState captureCompleteness captureReason rawUsage } } cost { status knownSubtotal completeAmount priceRevision reasons } } }";
        var publicResult = json.readTree(post("/graphql", Map.of("query", query)));
        assertThat(publicResult.path("errors").isMissingNode()).as(publicResult.toString()).isTrue();
        var attempt = publicResult.path("data").path("myUsageAttempt");
        System.out.println("OLLAMA_PUBLIC_USAGE=" + attempt);
        assertThat(attempt.path("provider").asText()).isEqualTo("OLLAMA");
        assertThat(attempt.path("usagePresence").asText()).isEqualTo("UNKNOWN");
        assertThat(attempt.path("effectiveSnapshotId").isNull()).isTrue();
        var snapshot = attempt.path("snapshots").path("nodes").get(0);
        assertThat(snapshot.path("semantic").asText()).isEqualTo("UNKNOWN");
        assertThat(snapshot.path("rawState").asText()).isEqualTo("UNAVAILABLE");
        assertThat(snapshot.path("captureCompleteness").asText()).isEqualTo("INCOMPLETE");
        assertThat(snapshot.path("captureReason").asText()).isEqualTo("USAGE_UNAVAILABLE");
        assertThat(snapshot.path("rawUsage").isNull()).isTrue();
        assertThat(attempt.path("cost").path("completeAmount").isNull()).isTrue();
        assertThat(attempt.path("cost").path("priceRevision").isNull()).isTrue();
        assertThat(attempt.path("cost").path("knownSubtotal").isNull()).isTrue();
        assertThat(attempt.path("cost").path("status").asText()).isEqualTo("UNPRICED");
        assertThat(attempt.path("cost").path("reasons")).isEmpty();
    }
    @Test void missingModelFailsWithoutCreatingUsageSnapshot() throws Exception {
        jdbc.update("insert into bot(id,name,system_prompt,created_at) values ('bot-general','General','fixture',current_timestamp) on conflict(id) do nothing");
        var created = json.readTree(post("/graphql", Map.of("query", "mutation { createConversation(botId:\"bot-general\",title:\"Missing\") { id } }")));
        String conversation = created.path("data").path("createConversation").path("id").asText();
        String run = UUID.randomUUID().toString();
        var input = Map.of("threadId",conversation,"runId",run,"state",Map.of(),"messages",List.of(Map.of("id",UUID.randomUUID().toString(),"role","user","content","fixture")),"tools",List.of(),"context",List.of(),"forwardedProps",Map.of());
        unavailable.set(true);
        try {
            String stream = post("/api/ag-ui", input);
            assertThat(stream).contains("RUN_ERROR").doesNotContain("RUN_FINISHED");
            assertThat(jdbc.queryForObject("select count(*) from usage_snapshot s join provider_attempt a on a.id=s.attempt_id where a.run_id=?", Integer.class, run)).isZero();
            assertThat(pulls.get()).isZero();
        } finally { unavailable.set(false); }
    }
}
