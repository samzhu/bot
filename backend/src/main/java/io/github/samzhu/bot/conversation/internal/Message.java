package io.github.samzhu.bot.conversation.internal;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

@Table("message")
public record Message(
    @Id String id,
    String conversationId,
    String runId,
    String role,
    String content,
    boolean interrupted,
    Instant createdAt
) {}
