package io.github.samzhu.bot.conversation;

public class ConversationAccessDeniedException extends RuntimeException {
    public ConversationAccessDeniedException(String message) {
        super(message);
    }
}
