package io.github.samzhu.bot.execution.internal;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

@Table("chat_run")
public record ChatRun(
    @Id String id,
    String conversationId,
    String clientRequestId,
    String inputText,
    String status,
    String executionMode,
    String errorMessage,
    Instant createdAt,
    Instant updatedAt
) {}
