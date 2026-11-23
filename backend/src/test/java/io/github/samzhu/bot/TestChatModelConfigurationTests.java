package io.github.samzhu.bot;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.assertThat;

class TestChatModelConfigurationTests {
    @Test void explicitTestConfigurationProvidesDeterministicChatModel() {
        new ApplicationContextRunner().withUserConfiguration(TestChatModelConfiguration.class)
            .run(context -> {
                assertThat(context).hasNotFailed().hasSingleBean(ChatModel.class);
                assertThat(context.getBean(ChatModel.class).call("synthetic question"))
                    .isEqualTo("Mock deterministic reply to: synthetic question");
            });
    }
}
