package io.github.samzhu.bot.conversation.internal;

import io.github.samzhu.bot.conversation.ConversationOperations;
import io.github.samzhu.bot.conversation.ConversationView;
import io.github.samzhu.bot.conversation.MessageView;
import org.springframework.data.jdbc.core.JdbcAggregateTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class ConversationOperationsImpl implements ConversationOperations {

    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;
    private final JdbcAggregateTemplate jdbcAggregateTemplate;

    public ConversationOperationsImpl(
            ConversationRepository conversationRepository,
            MessageRepository messageRepository,
            JdbcAggregateTemplate jdbcAggregateTemplate) {
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.jdbcAggregateTemplate = jdbcAggregateTemplate;
    }

    @Override
    public ConversationView createConversation(String botId, String title, String userId) {
        String convId = UUID.randomUUID().toString();
        String safeTitle = (title == null || title.isBlank()) ? "New Conversation" : title.trim();
        Instant now = Instant.now();
        Conversation conv = new Conversation(convId, botId, safeTitle, userId, now, now);
        Conversation saved = jdbcAggregateTemplate.insert(conv);
        return toView(saved);
    }

    @Override
    public Optional<ConversationView> findConversationById(String id, String userId) {
        return conversationRepository.findById(id)
                .filter(conv -> userId != null && userId.equals(conv.userId()))
                .map(this::toView);
    }

    @Override
    public List<ConversationView> listConversations(String userId, int limit, int offset) {
        int safeLimit = Math.max(1, Math.min(limit <= 0 ? 20 : limit, 100));
        int safeOffset = Math.max(0, offset);
        return conversationRepository.findByUserIdWithPaging(userId, safeLimit, safeOffset).stream()
                .map(this::toView)
                .toList();
    }

    @Override
    @Transactional
    public MessageView appendMessage(String conversationId, String role, String content, String runId, boolean interrupted) {
        String msgId = UUID.randomUUID().toString();
        Instant now = Instant.now();
        Message msg = new Message(msgId, conversationId, runId, role, content, interrupted, now);
        Message saved = jdbcAggregateTemplate.insert(msg);
        conversationRepository.touchUpdatedAt(conversationId, now);
        return toView(saved);
    }

    @Override
    public List<MessageView> listMessages(String conversationId, int limit, int offset) {
        int safeLimit = Math.max(1, Math.min(limit <= 0 ? 20 : limit, 100));
        int safeOffset = Math.max(0, offset);
        return messageRepository.findByConversationIdWithPaging(conversationId, safeLimit, safeOffset).stream()
                .map(this::toView)
                .toList();
    }

    private ConversationView toView(Conversation c) {
        return new ConversationView(c.id(), c.botId(), c.title(), c.userId(), c.createdAt(), c.updatedAt());
    }

    private MessageView toView(Message m) {
        return new MessageView(m.id(), m.conversationId(), m.runId(), m.role(), m.content(), m.interrupted(), m.createdAt());
    }
}
