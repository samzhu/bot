package io.github.samzhu.bot.conversation;

import java.time.Instant;

public record MessageView(
    String id,
    String conversationId,
    String runId,
    String role,
    String content,
    boolean interrupted,
    Instant createdAt
) {}
