package io.github.samzhu.bot.execution.internal.capture;

import io.github.samzhu.bot.usage.UsageLedger;
import java.util.Optional;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("local & !mock-ai & !test & !prod")
@ConditionalOnProperty(name = "spring.ai.model.chat", havingValue = "ollama")
public class OllamaUsageCaptureAdapter implements UsageCaptureAdapter {
    private final String model;

    public OllamaUsageCaptureAdapter(@Value("${spring.ai.ollama.chat.model:}") String model) {
        this.model = model;
    }

    @Override public UsageLedger.Dispatch dispatch(String run, String workspace, String actor) {
        return new UsageLedger.Dispatch(run, workspace, actor, "OLLAMA", "chat", model, "LOCAL", "2.1.0", false, "UNKNOWN");
    }

    @Override public Optional<UsageLedger.Snapshot> capture(ChatResponse response, String eventId, long ordinal) {
        // Spring AI 2.1 replaces missing counters with zero and does not retain the raw response.
        // Record that evidence once per invocation, without treating derived zeroes as observed usage.
        if (response == null || ordinal != 1) return Optional.empty();
        return Optional.of(new UsageLedger.Snapshot(eventId, ordinal, "UNKNOWN", "SPRING_AI",
                "ollama-usage-unavailable-v1", "2.1.0", "1", "INCOMPLETE", null));
    }
}
