package io.github.samzhu.bot.execution.internal.capture;

import io.github.samzhu.bot.usage.UsageLedger;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import static org.assertj.core.api.Assertions.assertThat;

class OllamaUsageCaptureAdapterTests {
    private final OllamaUsageCaptureAdapter adapter = new OllamaUsageCaptureAdapter("fixture-model");

    @Test
    void dispatchKeepsLocalOllamaIdentityAndRunScope() {
        assertThat(adapter.dispatch("run-id", "workspace-id", "actor-id"))
                .isEqualTo(new UsageLedger.Dispatch("run-id", "workspace-id", "actor-id",
                        "OLLAMA", "chat", "fixture-model", "LOCAL", "2.1.0", false, "UNKNOWN"));
    }

    @Test
    void absentCountersAndDerivedZeroesRemainUnknownWithoutRawUsage() {
        var reply = List.of(new Generation(new AssistantMessage("Fixture reply")));
        var withoutCounters = new ChatResponse(reply);
        // Spring AI 2.1 substitutes zero for an absent Ollama counter before this adapter sees it.
        var derivedZeroes = new ChatResponse(reply, ChatResponseMetadata.builder()
                .usage(new DefaultUsage(0, 0))
                .build());
        var unknown = new UsageLedger.Snapshot("first-response", 1, "UNKNOWN", "SPRING_AI",
                "ollama-usage-unavailable-v1", "2.1.0", "1", "INCOMPLETE", null);

        assertThat(adapter.capture(withoutCounters, "first-response", 1)).contains(unknown);
        assertThat(adapter.capture(derivedZeroes, "first-response", 1)).contains(unknown);
        assertThat(adapter.capture(derivedZeroes, "later-response", 2)).isEmpty();
    }

    @Test
    void noResponseDoesNotCreateASnapshot() {
        assertThat(adapter.capture(null, "first-response", 1)).isEmpty();
    }
}
