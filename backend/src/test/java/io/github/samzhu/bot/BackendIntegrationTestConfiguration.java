package io.github.samzhu.bot;

import io.github.samzhu.bot.execution.internal.capture.GoogleUsageCaptureAdapter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

@TestConfiguration(proxyBeanMethods = false)
class BackendIntegrationTestConfiguration {
    @Bean
    BackendTestChatModel backendTestChatModel() {
        return new BackendTestChatModel();
    }

    @Bean
    GoogleUsageCaptureAdapter googleUsageCaptureAdapter(
            @Value("${spring.ai.google.genai.chat.model:unknown}") String model) {
        // The test profile disables provider configuration, so wire the real adapter here.
        return new GoogleUsageCaptureAdapter(model);
    }
}
