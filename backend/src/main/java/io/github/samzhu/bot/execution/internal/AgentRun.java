package io.github.samzhu.bot.execution.internal;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

@Table("agent_run")
public record AgentRun(
    @Id String id,
    String conversationId,
    String clientRequestId,
    String promptText,
    String status,
    String errorMessage,
    Instant createdAt,
    Instant updatedAt
) {}
