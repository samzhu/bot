package io.github.samzhu.bot.conversation;

import java.util.Optional;

/** Trusted server-side recovery port; it does not authorize user-facing reads. */
public interface ConversationRecoveryReader {
    Optional<ConversationRecoveryIdentity> readIdentity(String conversationId);
}
