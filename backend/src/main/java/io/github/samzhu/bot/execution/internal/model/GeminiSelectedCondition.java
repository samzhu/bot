package io.github.samzhu.bot.execution.internal.model;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/** Supports the legacy launch only until its removal in the follow-up migration. */
public class GeminiSelectedCondition implements Condition {
    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        var env = context.getEnvironment();
        return "google-genai".equals(env.getProperty("spring.ai.model.chat"))
                || "gemini".equals(env.getProperty("bot-ai-provider"));
    }
}
