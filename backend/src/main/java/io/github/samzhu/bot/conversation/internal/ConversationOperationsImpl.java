package io.github.samzhu.bot.conversation.internal;

import io.github.samzhu.bot.bot.BotOperations;
import io.github.samzhu.bot.bot.BotView;
import io.github.samzhu.bot.bot.GkeAgentNotConnectedException;
import io.github.samzhu.bot.conversation.ConversationAccessDeniedException;
import io.github.samzhu.bot.conversation.ConversationOperations;
import io.github.samzhu.bot.conversation.ConversationView;
import io.github.samzhu.bot.conversation.MessageView;
import org.springframework.data.jdbc.core.JdbcAggregateTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
public class ConversationOperationsImpl implements ConversationOperations {

    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;
    private final JdbcAggregateTemplate jdbcAggregateTemplate;
    private final BotOperations botOperations;

    public ConversationOperationsImpl(
            ConversationRepository conversationRepository,
            MessageRepository messageRepository,
            JdbcAggregateTemplate jdbcAggregateTemplate,
            BotOperations botOperations) {
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.jdbcAggregateTemplate = jdbcAggregateTemplate;
        this.botOperations = Objects.requireNonNull(botOperations, "botOperations must not be null");
    }

    @Override
    public ConversationView createConversation(String botId, String title, String userId) {
        Optional<BotView> botOpt = botOperations.findById(botId, userId);
        if (botOpt.isEmpty()) {
            throw new IllegalArgumentException("Bot not found: " + botId);
        }
        BotView bot = botOpt.get();
        if (!bot.canChat()) {
            throw new GkeAgentNotConnectedException("GKE Agent is not connected and cannot start conversations");
        }

        String convId = UUID.randomUUID().toString();
        String safeTitle = (title == null || title.isBlank()) ? "New Conversation" : title.trim();
        Instant now = Instant.now();
        Conversation conv = new Conversation(convId, botId, safeTitle, userId, false, now, now);
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
        return listConversations(userId, null, limit, offset);
    }

    @Override
    public List<ConversationView> listConversations(String userId, Boolean pinned, int limit, int offset) {
        int safeLimit = Math.max(1, Math.min(limit <= 0 ? 20 : limit, 100));
        int safeOffset = Math.max(0, offset);

        List<Conversation> list;
        if (pinned == null) {
            list = conversationRepository.findByUserIdWithPaging(userId, safeLimit, safeOffset);
        } else {
            list = conversationRepository.findByUserIdAndPinnedWithPaging(userId, pinned, safeLimit, safeOffset);
        }

        return list.stream()
                .map(this::toView)
                .toList();
    }

    @Override
    @Transactional
    public ConversationView setConversationPinned(String id, boolean pinned, String userId) {
        Conversation conv = conversationRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Conversation not found: " + id));

        if (!conv.userId().equals(userId)) {
            throw new ConversationAccessDeniedException("Unauthorized: Conversation " + id + " is not owned by user " + userId);
        }

        // Fast-path idempotency: if already in requested state, return immediately without DB update
        if (conv.pinned() == pinned) {
            return toView(conv);
        }

        conversationRepository.updatePinned(id, userId, pinned);

        // Reload to verify final state (handles concurrent update or successful update without misjudging owner)
        Conversation current = conversationRepository.findById(id)
                .orElseThrow(() -> new IllegalStateException("Failed to reload conversation after pin update: " + id));

        if (!current.userId().equals(userId)) {
            throw new ConversationAccessDeniedException("Unauthorized: Conversation " + id + " is not owned by user " + userId);
        }

        return toView(current);
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
        return new ConversationView(c.id(), c.botId(), c.title(), c.userId(), c.pinned(), c.createdAt(), c.updatedAt());
    }

    private MessageView toView(Message m) {
        return new MessageView(m.id(), m.conversationId(), m.runId(), m.role(), m.content(), m.interrupted(), m.createdAt());
    }
}
