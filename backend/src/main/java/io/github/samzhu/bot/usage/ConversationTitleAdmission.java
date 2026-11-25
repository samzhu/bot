package io.github.samzhu.bot.usage;

/** Trusted background-work admission; callers never supply a workspace or actor. */
public interface ConversationTitleAdmission {
    enum Decision {
        ALLOWED(null), DENIED(null), DEFERRED(null), DENIED_MONTH("MONTH_CAP"), DENIED_WEEK("WEEK_CAP"),
        DENIED_UNKNOWN("UNRESOLVED_SPENDING"), DENIED_RATE("RATE_UNAVAILABLE");
        private final String reason;
        Decision(String reason) { this.reason = reason; }
        public String reason() { return reason; }
    }
    Decision allow(String conversationId);
    boolean capture(String conversationId, io.github.samzhu.bot.conversation.ConversationTitleGenerator.Result result);
    void unknown(String conversationId);
    /** Process one bounded page of expired, durable title claims without redispatch. */
    int recoverExpiredBatch();
}
