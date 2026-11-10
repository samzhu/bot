package io.github.samzhu.bot.conversation;

import java.time.Instant;

public record ConversationView(
    String id,
    String botId,
    String title,
    String userId,
    Instant createdAt,
    Instant updatedAt
) {}
