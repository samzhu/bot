package io.github.samzhu.bot.execution.internal.capture;

import com.google.genai.types.GenerateContentResponseUsageMetadata;

import io.github.samzhu.bot.usage.UsageLedger;

import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
@Profile("real-ai")
public class GoogleUsageCaptureAdapter implements UsageCaptureAdapter {
    private final String model;

    public GoogleUsageCaptureAdapter(
            @Value("${spring.ai.google.genai.chat.model:unknown}") String model) {
        this.model = model;
    }

    @Override
    public UsageLedger.Dispatch dispatch(String run, String workspace, String actor) {
        // attempts(1) was verified only for the initial 503 fixture; other hidden retry paths
        // remain unknown.
        return new UsageLedger.Dispatch(
                run,
                workspace,
                actor,
                "GOOGLE",
                "generateContent",
                model,
                "GEMINI_API",
                "2.1.0-RC2",
                false,
                "UNKNOWN");
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
        if (!(nativeUsage instanceof GenerateContentResponseUsageMetadata nativeObject))
            return Optional.empty();
        // SDK typed objects may already have discarded wire fields; never claim complete wire
        // capture.
        return Optional.of(
                new UsageLedger.Snapshot(
                        eventId,
                        ordinal,
                        "CUMULATIVE",
                        "SDK_NATIVE",
                        "1",
                        "2.1.0-RC2",
                        "1",
                        "INCOMPLETE",
                        nativeObject.toJson()));
    }
}
