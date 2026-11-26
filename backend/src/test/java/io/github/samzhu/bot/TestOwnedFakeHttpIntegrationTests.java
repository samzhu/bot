package io.github.samzhu.bot;

import com.google.genai.Client;
import com.google.genai.types.GenerateContentResponseUsageMetadata;
import io.github.samzhu.bot.execution.internal.capture.UsageCaptureAdapter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

@BackendIntegrationTest
class TestOwnedFakeHttpIntegrationTests {
    @LocalServerPort int port;
    @Autowired BackendTestChatModel model;
    @Autowired UsageCaptureAdapter capture;
    @Autowired ApplicationContext context;

    @AfterEach
    void resetModel() {
        model.reset();
    }

    @Test
    void explicitTestModelStartsAndServesHealthWithoutAProvider() throws Exception {
        assertThat(context.getBeansOfType(ChatModel.class).values()).containsExactly(model);
        assertThat(context.getBeanNamesForType(Client.class, true, false)).isEmpty();
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/actuator/health"))
                .timeout(Duration.ofSeconds(5)).GET().build();
        try (var client = HttpClient.newHttpClient()) {
            assertThat(client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
        }
        assertThat(model.invocations()).as("startup and health never call the model").isZero();

        var nativeUsage = GenerateContentResponseUsageMetadata.builder()
                .promptTokenCount(3).candidatesTokenCount(2).totalTokenCount(5).build();
        var metadata = ChatResponseMetadata.builder().usage(new DefaultUsage(3, 2, 5, nativeUsage)).build();
        model.respondWith(new ChatResponse(List.of(new Generation(new AssistantMessage("Fixture reply"))), metadata),
                List.of(new ChatResponse(List.of(new Generation(new AssistantMessage("Fixture ")))),
                        new ChatResponse(List.of(new Generation(new AssistantMessage("stream"))), metadata)));
        var prompt = new Prompt("Check the test model");
        assertThat(model.call(prompt).getResult().getOutput().getText()).isEqualTo("Fixture reply");
        var chunks = model.stream(prompt).collectList().block(Duration.ofSeconds(5));
        assertThat(chunks).extracting(chunk -> chunk.getResult().getOutput().getText())
                .containsExactly("Fixture ", "stream");
        var snapshot = capture.capture(chunks.getLast(), "test-usage", 2).orElseThrow();
        var usage = GenerateContentResponseUsageMetadata.fromJson(snapshot.rawJson());
        assertThat(snapshot.sourceLayer()).isEqualTo("SDK_NATIVE");
        assertThat(usage.promptTokenCount()).contains(3);
        assertThat(usage.candidatesTokenCount()).contains(2);
        assertThat(usage.totalTokenCount()).contains(5);
    }
}
