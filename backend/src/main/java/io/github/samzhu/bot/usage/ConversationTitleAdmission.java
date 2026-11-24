package io.github.samzhu.bot.usage;

/** Trusted background-work admission; callers never supply a workspace or actor. */
public interface ConversationTitleAdmission {
    enum Decision { ALLOWED, DENIED, DEFERRED }
    Decision allow(String conversationId);
    boolean capture(String conversationId, io.github.samzhu.bot.conversation.ConversationTitleGenerator.Result result);
    void unknown(String conversationId);
    /** Process one bounded page of expired, durable title claims without redispatch. */
    int recoverExpiredBatch();
}
