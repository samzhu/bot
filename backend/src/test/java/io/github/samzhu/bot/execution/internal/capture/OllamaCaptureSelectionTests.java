package io.github.samzhu.bot.execution.internal.capture;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class OllamaCaptureSelectionTests {
    @Configuration(proxyBeanMethods = false)
    @ComponentScan(basePackageClasses = UsageCaptureAdapter.class)
    static class CaptureConfiguration { }

    @Test void ollamaDispatchUsesRealLocalProviderIdentity() {
        new ApplicationContextRunner().withUserConfiguration(CaptureConfiguration.class)
            .withPropertyValues("spring.profiles.active=local", "spring.ai.model.chat=ollama",
                "spring.ai.ollama.chat.model=fixture-model", "bot-google-api-key=synthetic-key-only")
            .run(context -> {
                assertThat(context).hasNotFailed().hasSingleBean(UsageCaptureAdapter.class);
                var dispatch = context.getBean(UsageCaptureAdapter.class).dispatch("run", "workspace", "actor");
                assertThat(dispatch.provider()).isEqualTo("OLLAMA");
                assertThat(dispatch.endpoint()).isEqualTo("chat");
                assertThat(dispatch.model()).isEqualTo("fixture-model");
                assertThat(dispatch.channel()).isEqualTo("LOCAL");
                assertThat(dispatch.simulation()).isFalse();
                assertThat(dispatch.attemptCoverage()).isEqualTo("UNKNOWN");
            });
    }
}
