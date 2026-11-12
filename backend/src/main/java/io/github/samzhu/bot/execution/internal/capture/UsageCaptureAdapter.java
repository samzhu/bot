package io.github.samzhu.bot.execution.internal.capture;

import io.github.samzhu.bot.usage.UsageLedger;

import org.springframework.ai.chat.model.ChatResponse;

import java.util.Optional;

public interface UsageCaptureAdapter {
    UsageLedger.Dispatch dispatch(String runId, String workspaceId, String actorId);

    default Optional<String> responseId(ChatResponse response) {
        return Optional.empty();
    }

    Optional<UsageLedger.Snapshot> capture(ChatResponse response, String eventId, long ordinal);
}
