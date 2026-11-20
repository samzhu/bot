package io.github.samzhu.bot.conversation;

/** Schedules title work after the first visible reply is handed to the stream. */
public interface ConversationTitleDispatch {
    void afterFirstVisibleReply(String conversationId);
}
