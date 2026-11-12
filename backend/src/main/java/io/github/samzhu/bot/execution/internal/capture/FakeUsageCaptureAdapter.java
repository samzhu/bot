package io.github.samzhu.bot.execution.internal.capture;

import io.github.samzhu.bot.usage.UsageLedger;

import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
@Profile("(local | test) & !real-ai")
public class FakeUsageCaptureAdapter implements UsageCaptureAdapter {
    @Override
    public UsageLedger.Dispatch dispatch(String run, String workspace, String actor) {
        return new UsageLedger.Dispatch(
                run,
                workspace,
                actor,
                "FAKE",
                "generateContent",
                "fake",
                "FAKE",
                "1",
                true,
                "VERIFIED");
    }

    @Override
    public Optional<String> responseId(ChatResponse response) {
        if (response == null || response.getMetadata() == null) return Optional.empty();
        String id = response.getMetadata().getId();
        return id == null || id.isBlank() ? Optional.empty() : Optional.of(id);
    }

    @Override
    public Optional<UsageLedger.Snapshot> capture(
            ChatResponse response, String eventId, long ordinal) {
        if (response == null
                || response.getMetadata() == null
                || response.getMetadata().getUsage() == null) return Optional.empty();
        Object nativeUsage = response.getMetadata().getUsage().getNativeUsage();
        if (!(nativeUsage instanceof String raw)) return Optional.empty();
        return Optional.of(
                new UsageLedger.Snapshot(
                        eventId,
                        ordinal,
                        "CUMULATIVE",
                        "FAKE_RAW",
                        "1",
                        "1",
                        "1",
                        "COMPLETE",
                        raw));
    }
}
