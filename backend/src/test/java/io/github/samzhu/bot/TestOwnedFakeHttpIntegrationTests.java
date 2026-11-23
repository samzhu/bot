package io.github.samzhu.bot;

import io.github.samzhu.bot.execution.internal.capture.UsageCaptureAdapter;
import io.github.samzhu.bot.execution.internal.model.MockChatModel;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({TestcontainersConfiguration.class, TestChatModelConfiguration.class, TestUsageCaptureConfiguration.class})
@ActiveProfiles("test")
class TestOwnedFakeHttpIntegrationTests {
    @LocalServerPort int port;
    @Autowired ChatModel model;
    @Autowired UsageCaptureAdapter capture;

    @Test void explicitTestFakesStartAndServeHealthWithoutAProvider() throws Exception {
        assertThat(model).isInstanceOf(MockChatModel.class);
        assertThat(capture.dispatch("run", "workspace", "actor").simulation()).isTrue();
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/actuator/health")).GET().build();
        assertThat(HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
    }
}
