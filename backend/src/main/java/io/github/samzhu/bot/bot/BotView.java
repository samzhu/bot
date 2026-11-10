package io.github.samzhu.bot.bot;

import java.time.Instant;

public record BotView(
    String id,
    String name,
    Instant createdAt
) {}
