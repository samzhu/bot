package io.github.samzhu.bot;

import io.github.samzhu.bot.execution.internal.model.MockChatModel;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

@TestConfiguration(proxyBeanMethods = false)
public class TestChatModelConfiguration {
    @Bean
    MockChatModel mockChatModel() {
        return new MockChatModel();
    }
}
