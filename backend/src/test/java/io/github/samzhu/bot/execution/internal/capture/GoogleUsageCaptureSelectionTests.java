package io.github.samzhu.bot.execution.internal.capture;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.assertThat;

class GoogleUsageCaptureSelectionTests {
    @Test void captureAdapterRequiresExplicitLocalGemini() {
        var runner = new ApplicationContextRunner().withUserConfiguration(GoogleUsageCaptureAdapter.class);
        runner.withPropertyValues("spring.profiles.active=local", "bot-google-api-key=synthetic-key-only")
                .run(context -> assertThat(context).doesNotHaveBean(GoogleUsageCaptureAdapter.class));
        runner.withPropertyValues("spring.profiles.active=local", "bot-ai-provider=gemini")
                .run(context -> assertThat(context).hasSingleBean(GoogleUsageCaptureAdapter.class));
        for (String profiles : new String[]{"test", "local,test", "local,mock-ai", "local,prod", "prod"}) {
            runner.withPropertyValues("spring.profiles.active=" + profiles, "bot-ai-provider=gemini")
                    .run(context -> assertThat(context).doesNotHaveBean(GoogleUsageCaptureAdapter.class));
        }
    }
    @Test void standardGeminiSelectorCreatesCaptureAdapter() {
        new ApplicationContextRunner().withUserConfiguration(GoogleUsageCaptureAdapter.class)
            .withPropertyValues("spring.profiles.active=local", "spring.ai.model.chat=google-genai")
            .run(context -> assertThat(context).hasSingleBean(GoogleUsageCaptureAdapter.class));
    }
}
