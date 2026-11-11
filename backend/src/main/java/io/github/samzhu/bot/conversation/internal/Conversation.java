package io.github.samzhu.bot.conversation.internal;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

@Table("conversation")
public record Conversation(
    @Id String id,
    String botId,
    String title,
    String userId,
    boolean pinned,
    Instant createdAt,
    Instant updatedAt
) {}
