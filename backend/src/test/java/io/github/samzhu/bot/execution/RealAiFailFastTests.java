package io.github.samzhu.bot.execution;

import com.google.genai.Client;
import io.github.samzhu.bot.execution.internal.model.GoogleGenAiManualConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class RealAiFailFastTests {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(GoogleGenAiManualConfig.class);

    @Test
    @DisplayName("Fail-fast: Starting with real-ai profile but missing API key must abort with clear diagnostic")
    void missingApiKey_abortsStartupWithClearDiagnostic() {
        runner.withPropertyValues("spring.profiles.active=real-ai")
                .withPropertyValues("spring.ai.google.genai.api-key=")
                .withPropertyValues("spring.ai.google.genai.chat.model=gemini-3.8-flash")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalStateException.class)
                            .hasRootCauseMessage("Fail-fast: 'real-ai' profile requires spring.ai.google.genai.api-key");
                });
    }

    @Test
    @DisplayName("Fail-fast: Starting with real-ai profile and blank model must abort with clear diagnostic")
    void missingModel_abortsStartupWithClearDiagnostic() {
        runner.withPropertyValues("spring.profiles.active=real-ai")
                .withPropertyValues("spring.ai.google.genai.api-key=dummy-test-key-not-real")
                .withPropertyValues("spring.ai.google.genai.chat.model=")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalStateException.class)
                            .hasRootCauseMessage("Fail-fast: 'real-ai' profile requires explicit spring.ai.google.genai.chat.model in YAML configuration.");
                });
    }

    @Test
    @DisplayName("Success: Starting with real-ai profile, dummy key and explicit model successfully registers genuine ChatModel bean")
    void validDummyConfig_registersGenuineChatModelBean() {
        runner.withPropertyValues("spring.profiles.active=real-ai")
                .withPropertyValues("spring.ai.google.genai.api-key=dummy-test-key-not-real")
                .withPropertyValues("spring.ai.google.genai.chat.model=gemini-3.8-flash")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(Client.class);
                    assertThat(context).hasSingleBean(ChatModel.class);
                    assertThat(context.getBean(ChatModel.class)).isInstanceOf(GoogleGenAiChatModel.class);
                });
    }

    @Test
    @DisplayName("Default / Test profile: Real AI beans are NOT loaded when real-ai profile is inactive")
    void nonRealAiProfile_doesNotLoadGoogleGenAiBeans() {
        runner.withPropertyValues("spring.profiles.active=test")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(Client.class);
                    assertThat(context).doesNotHaveBean(ChatModel.class);
                });
    }
}
