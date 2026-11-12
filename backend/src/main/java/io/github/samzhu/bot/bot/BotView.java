package io.github.samzhu.bot.bot;

import java.time.Instant;

public record BotView(
    String id,
    String name,
    String description,
    String instructions,
    BotTarget target,
    ConnectionStatus connectionStatus,
    BotExecutionMode executionMode,
    String statusReason,
    boolean canChat,
    Instant createdAt,
    Instant updatedAt,
    String ownerId
) {}
