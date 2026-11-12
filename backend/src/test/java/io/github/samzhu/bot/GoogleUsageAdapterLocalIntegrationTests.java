package io.github.samzhu.bot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genai.Client;
import com.sun.net.httpserver.HttpServer;
import io.github.samzhu.bot.execution.internal.capture.GoogleUsageCaptureAdapter;
import io.github.samzhu.bot.execution.internal.model.GoogleGenAiManualConfig;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Real SDK/adapter against a loopback fixture only; no live model or credentials. */
class GoogleUsageAdapterLocalIntegrationTests {
    private static final String MODEL = "local-fixture-model";
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void metadataOnlyFrameExposesSdkNativeIncompleteUsageAndResponseId() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        AtomicReference<String> path = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            path.set(exchange.getRequestURI().getPath());
            exchange.getRequestBody().readAllBytes();
            byte[] body = ("data: {\"responseId\":\"issue33-local-response\","
                    + "\"usageMetadata\":{\"promptTokenCount\":7,\"candidatesTokenCount\":2,\"totalTokenCount\":9}}\n\n")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        try (Client client = localClient(server)) {
            List<ChatResponse> responses = model(client).stream(new Prompt("fixture prompt"))
                    .collectList().block(Duration.ofSeconds(10));
            assertNotNull(responses);
            assertEquals(1, requests.get());
            assertTrue(path.get().contains("streamGenerateContent"), String.valueOf(path.get()));
            var capture = new GoogleUsageCaptureAdapter(MODEL);
            var candidate = responses.stream().filter(response -> capture.capture(response, "event-1", 1).isPresent())
                    .findFirst().orElseThrow(() -> new AssertionError("metadata-only frame lost native usage"));
            assertTrue(candidate.getResults().isEmpty(), "fixture contains metadata only, no generation");
            assertEquals("issue33-local-response", capture.responseId(candidate).orElseThrow());
            var snapshot = capture.capture(candidate, "event-1", 1).orElseThrow();
            assertEquals("SDK_NATIVE", snapshot.sourceLayer());
            assertEquals("INCOMPLETE", snapshot.completeness());
            var nativeUsage = json.readTree(snapshot.rawJson());
            assertEquals(7, nativeUsage.path("promptTokenCount").asInt());
            assertEquals(2, nativeUsage.path("candidatesTokenCount").asInt());
            assertEquals(9, nativeUsage.path("totalTokenCount").asInt());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void productRetryOptionsLimitInitial503FixtureToOneHttpAttempt() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            byte[] body = "{\"error\":{\"code\":503,\"message\":\"synthetic unavailable\",\"status\":\"UNAVAILABLE\"}}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(503, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        try (Client client = localClient(server)) {
            assertEquals(1, GoogleGenAiManualConfig.providerHttpOptions().retryOptions().orElseThrow().attempts().orElseThrow());
            assertThrows(RuntimeException.class, () -> model(client).stream(new Prompt("fixture prompt"))
                    .collectList().block(Duration.ofSeconds(10)));
            // This covers only an initial HTTP 503, not other transport-level retry paths.
            assertEquals(1, requests.get());
        } finally {
            server.stop(0);
        }
    }

    private Client localClient(HttpServer server) {
        var options = GoogleGenAiManualConfig.providerHttpOptions().toBuilder()
                .baseUrl("http://127.0.0.1:" + server.getAddress().getPort()).build();
        return Client.builder().apiKey("local-fixture-dummy-key").enterprise(false).httpOptions(options).build();
    }

    private GoogleGenAiChatModel model(Client client) {
        return GoogleGenAiChatModel.builder().genAiClient(client)
                .options(GoogleGenAiChatOptions.builder().model(MODEL).build()).build();
    }
}
