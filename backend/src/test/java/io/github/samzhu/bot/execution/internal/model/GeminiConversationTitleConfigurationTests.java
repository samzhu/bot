package io.github.samzhu.bot.execution.internal.model;

import io.github.samzhu.bot.conversation.ConversationTitleGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.junit.jupiter.api.Assertions.*;

class GeminiConversationTitleConfigurationTests {
    @Test void missingSelectorAndForbiddenProfilesNeverCreateProviderClient() {
        for (String[] profiles : new String[][]{{"local"}, {"test"}, {"local", "mock-ai"}, {"local", "test"}, {"local", "prod"}}) {
            new ApplicationContextRunner().withUserConfiguration(GeminiConversationTitleConfiguration.class)
                .withPropertyValues("bot-google-api-key=synthetic-key-only")
                .withInitializer(context -> context.getEnvironment().setActiveProfiles(profiles))
                .run(context -> assertTrue(context.getBeansOfType(ConversationTitleGenerator.class).isEmpty()));
        }
        for (String[] profiles : new String[][]{{"test"}, {"local", "mock-ai"}, {"local", "test"}, {"local", "prod"}}) {
            new ApplicationContextRunner().withUserConfiguration(GeminiConversationTitleConfiguration.class)
                .withPropertyValues("bot-ai-provider=gemini", "bot-google-api-key=synthetic-key-only")
                .withInitializer(context -> context.getEnvironment().setActiveProfiles(profiles))
                .run(context -> assertTrue(context.getBeansOfType(ConversationTitleGenerator.class).isEmpty()));
        }
    }
    @Test void geminiTitleClientUsesCanonicalKeyWithoutProviderCall() {
        new ApplicationContextRunner().withUserConfiguration(GeminiConversationTitleConfiguration.class)
            .withPropertyValues("bot-ai-provider=gemini", "bot-google-api-key=synthetic-key-only")
            .withInitializer(context -> context.getEnvironment().setActiveProfiles("local"))
            .run(context -> {
                assertNull(context.getStartupFailure());
                assertTrue(context.containsBean("conversationTitleClient"));
                assertEquals(1, context.getBeansOfType(ConversationTitleGenerator.class).size());
            });
    }
    @Test void geminiTitleClientRejectsMissingCanonicalKey() {
        new ApplicationContextRunner().withUserConfiguration(GeminiConversationTitleConfiguration.class)
            .withPropertyValues("bot-ai-provider=gemini")
            .withInitializer(context -> context.getEnvironment().setActiveProfiles("local"))
            .run(context -> {
                assertNotNull(context.getStartupFailure());
            });
    }
}
