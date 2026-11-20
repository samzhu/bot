package io.github.samzhu.bot.execution.internal.model;

import io.github.samzhu.bot.conversation.ConversationTitleGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Profile;
import static org.junit.jupiter.api.Assertions.*;

class GeminiConversationTitleConfigurationTests {
    @Test void testAndMockProfilesNeverCreateProviderClient() {
        for (String[] profiles : new String[][]{{"test"}, {"local", "mock-ai"}, {"test", "real-ai"}}) {
            new ApplicationContextRunner().withUserConfiguration(GeminiConversationTitleConfiguration.class)
                .withInitializer(context -> context.getEnvironment().setActiveProfiles(profiles))
                .run(context -> {
                    assertFalse(context.containsBean("conversationTitleClient"));
                    assertTrue(context.getBeansOfType(ConversationTitleGenerator.class).isEmpty());
                });
        }
        var rule=GeminiConversationTitleConfiguration.class.getAnnotation(Profile.class);
        assertNotNull(rule);
        assertArrayEquals(new String[]{"local & real-ai & !mock-ai & !test"},rule.value());
    }
}
