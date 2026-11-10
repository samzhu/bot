package io.github.samzhu.bot.bot.internal;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

@Table("bot")
public record Bot(
    @Id String id,
    String name,
    String systemPrompt,
    Instant createdAt
) {}
