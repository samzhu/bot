package io.github.samzhu.bot.conversation;

import java.util.List;
import java.util.Optional;

public interface ConversationOperations {
    ConversationView createConversation(String botId, String title, String userId);
    Optional<ConversationView> findConversationById(String id, String userId);
    List<ConversationView> listConversations(String userId, int limit, int offset);
    List<ConversationView> listConversations(String userId, Boolean pinned, int limit, int offset);
    List<ConversationView> listConversations(String userId, String search, Boolean pinned, int limit, int offset);
    ConversationView setConversationPinned(String id, boolean pinned, String userId);
    MessageView appendMessage(String conversationId, String role, String content, String runId, boolean interrupted);
    MessageView appendMessageWithId(String messageId, String conversationId, String role, String content, String runId, boolean interrupted);
    List<MessageView> listMessages(String conversationId, int limit, int offset);
}
