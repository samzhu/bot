package io.github.samzhu.bot.execution;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

/**
 * Public view DTO for run lifecycle information.
 *
 * WHY / ARCHITECTURAL CONTRACT:
 * {@code promptText} is deliberately preserved as a public field to maintain 100% backward compatibility
 * with existing GraphQL and REST contracts, internally mapping from the redesigned database column {@code chat_run.input_text}.
 * Do not rename or remove {@code promptText} to prevent breaking public API consumers.
 */
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
