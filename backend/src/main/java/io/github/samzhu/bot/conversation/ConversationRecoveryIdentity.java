package io.github.samzhu.bot.conversation;

/** Minimal server-side identity used to reconcile durable execution facts. */
public record ConversationRecoveryIdentity(String conversationId, String workspaceId, String ownerId) {}
