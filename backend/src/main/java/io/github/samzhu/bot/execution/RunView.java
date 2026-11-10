package io.github.samzhu.bot.execution;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

public record RunView(
    String id,
    String conversationId,
    String clientRequestId,
    String promptText,
    String status,
    String errorMessage,
    Instant createdAt,
    Instant updatedAt
) {
    @JsonProperty("runId")
    public String runId() {
        return id;
    }
}
