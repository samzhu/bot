package io.github.samzhu.bot.bot.internal;

import io.github.samzhu.bot.bot.*;
import org.springframework.data.jdbc.core.JdbcAggregateTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class BotOperationsImpl implements BotOperations {

    private final BotRepository botRepository;
    private final GkeProperties gkeProperties;
    private final JdbcAggregateTemplate jdbcAggregateTemplate;

    public BotOperationsImpl(
            BotRepository botRepository,
            GkeProperties gkeProperties,
            JdbcAggregateTemplate jdbcAggregateTemplate) {
        this.botRepository = botRepository;
        this.gkeProperties = gkeProperties;
        this.jdbcAggregateTemplate = jdbcAggregateTemplate;
    }

    @Override
    public Optional<BotView> findById(String id, String userId) {
        return botRepository.findById(id)
                .filter(b -> isVisibleToUser(b, userId))
                .map(this::toView);
    }

    @Override
    public List<BotView> listBots(String userId, int limit, int offset) {
        return listBots(userId, null, null, limit, offset);
    }

    @Override
    public List<BotView> listBots(String userId, String search, BotTarget target, int limit, int offset) {
        int safeLimit = Math.max(1, Math.min(limit <= 0 ? 20 : limit, 100));
        int safeOffset = Math.max(0, offset);

        String trimmedSearch = (search != null) ? search.trim() : null;
        if (trimmedSearch != null && trimmedSearch.isEmpty()) {
            trimmedSearch = null;
        }

        String searchPattern = null;
        if (trimmedSearch != null) {
            String escaped = escapeLikePattern(trimmedSearch);
            searchPattern = "%" + escaped + "%";
        }

        String targetStr = (target != null) ? target.name() : null;

        return botRepository.findVisibleBotsWithFilters(userId, targetStr, searchPattern, safeLimit, safeOffset)
                .stream()
                .map(this::toView)
                .toList();
    }

    /*
     * WHY / ARCHITECTURAL RATIONALE:
     * SQL LIKE and ILIKE special wildcards '%' (matching any sequence) and '_' (matching any single character),
     * as well as the escape character '\', must be escaped when user searches for exact literal characters.
     * Escaping '\' first avoids double-escaping subsequent replaced escape characters.
     * Official PostgreSQL 16 Docs Chapter 9.7 "Pattern Matching":
     * "The default escape character is the backslash, but a different one can be selected by using the ESCAPE clause.
     *  To match the escape character itself, write two escape characters."
     * Checked: 2026-11-12.
     */
    static String escapeLikePattern(String input) {
        if (input == null) {
            return null;
        }
        return input.replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }

    @Override
    public Optional<BotView> findDefaultLocalBot(String userId) {
        return botRepository.findDefaultLocalBot()
                .filter(b -> isVisibleToUser(b, userId))
                .map(this::toView);
    }

    @Override
    @Transactional
    public BotView createBot(CreateBotInput input, String userId) {
        if (input == null || input.name() == null || input.name().trim().isBlank()) {
            throw new IllegalArgumentException("Bot name is required and cannot be blank");
        }
        String trimmedName = input.name().trim();
        if (trimmedName.length() > 100) {
            throw new IllegalArgumentException("Bot name length cannot exceed 100 characters");
        }
        if (input.description() != null && input.description().length() > 2000) {
            throw new IllegalArgumentException("Description length cannot exceed 2000 characters");
        }
        if (input.instructions() != null && input.instructions().length() > 10000) {
            throw new IllegalArgumentException("Instructions length cannot exceed 10000 characters");
        }

        String botId = "bot-gke-" + UUID.randomUUID();
        Instant now = Instant.now();
        Bot bot = new Bot(
                botId,
                trimmedName,
                BotTarget.GKE_REMOTE.name(),
                input.description(),
                input.instructions(),
                null,
                userId,
                now,
                now
        );

        Bot saved = jdbcAggregateTemplate.insert(bot);
        return toView(saved);
    }

    @Override
    @Transactional
    public BotView updateBot(UpdateBotInput input, String userId) {
        if (input == null || input.id() == null || input.id().isBlank()) {
            throw new IllegalArgumentException("Bot id is required and cannot be blank");
        }
        if (input.name() == null || input.name().trim().isBlank()) {
            throw new IllegalArgumentException("Bot name is required and cannot be blank");
        }
        String trimmedName = input.name().trim();
        if (trimmedName.length() > 100) {
            throw new IllegalArgumentException("Bot name length cannot exceed 100 characters");
        }
        if (input.description() != null && input.description().length() > 2000) {
            throw new IllegalArgumentException("Description length cannot exceed 2000 characters");
        }
        if (input.instructions() != null && input.instructions().length() > 10000) {
            throw new IllegalArgumentException("Instructions length cannot exceed 10000 characters");
        }

        Optional<Bot> existingOpt = botRepository.findById(input.id());
        if (existingOpt.isEmpty()) {
            // 未知不洩漏
            throw new IllegalArgumentException("Bot not found: " + input.id());
        }

        Bot existing = existingOpt.get();

        /*
         * WHY / ARCHITECTURAL RATIONALE:
         * 1. LOCAL bots are shared system baselines and strictly read-only: any attempt to modify them
         *    is explicitly rejected.
         * 2. Non-leakage authorization constraint: foreign GKE bots owned by other users or mismatched
         *    identities must return a generic 'not found' message rather than leaking the existence
         *    or ownership details of other users' bots.
         * 3. Immutability constraints: id, target, ownerId, and createdAt must never be altered during update.
         *    Foreign keys (e.g. conversation.bot_id) remain permanently valid.
         * 4. Refresh updated_at: updated_at is refreshed to current timestamp upon every successful mutation.
         */
        if (BotTarget.LOCAL.name().equalsIgnoreCase(existing.target())) {
            throw new IllegalArgumentException("LOCAL bot is read-only and cannot be updated");
        }

        if (!BotTarget.GKE_REMOTE.name().equalsIgnoreCase(existing.target())
                || existing.ownerId() == null
                || !existing.ownerId().equals(userId)) {
            // 他人不洩漏
            throw new IllegalArgumentException("Bot not found: " + input.id());
        }

        Instant now = Instant.now();
        Bot updated = new Bot(
                existing.id(),
                trimmedName,
                existing.target(),
                input.description(),
                input.instructions(),
                existing.systemPrompt(),
                existing.ownerId(),
                existing.createdAt(),
                now
        );

        Bot saved = jdbcAggregateTemplate.update(updated);
        return toView(saved);
    }

    @Override
    public boolean canChat(String botId, String userId) {
        return findById(botId, userId).map(BotView::canChat).orElse(false);
    }

    @Override
    public Optional<String> getEffectiveSystemPrompt(String botId, String userId) {
        Optional<Bot> botOpt = botRepository.findById(botId).filter(b -> isVisibleToUser(b, userId));
        if (botOpt.isEmpty()) {
            return Optional.empty();
        }
        Bot b = botOpt.get();
        if (BotTarget.LOCAL.name().equalsIgnoreCase(b.target())) {
            return Optional.ofNullable(b.systemPrompt());
        }
        if (BotTarget.GKE_REMOTE.name().equalsIgnoreCase(b.target())) {
            if (gkeProperties.isSimulated()) {
                return Optional.ofNullable(b.instructions());
            }
        }
        return Optional.empty();
    }

    private boolean isVisibleToUser(Bot b, String userId) {
        if (b == null) {
            return false;
        }
        if (BotTarget.LOCAL.name().equalsIgnoreCase(b.target()) || b.target() == null) {
            return true;
        }
        return b.ownerId() != null && b.ownerId().equals(userId);
    }

    private BotView toView(Bot b) {
        BotTarget target = BotTarget.LOCAL;
        if (b.target() != null) {
            try {
                target = BotTarget.valueOf(b.target());
            } catch (IllegalArgumentException ignored) {}
        }

        ConnectionStatus connStatus;
        BotExecutionMode execMode;
        boolean canChat;
        String reason;

        if (target == BotTarget.LOCAL) {
            connStatus = ConnectionStatus.NOT_APPLICABLE;
            execMode = BotExecutionMode.LOCAL;
            canChat = true;
            reason = null;
        } else {
            connStatus = ConnectionStatus.NOT_CONNECTED;
            if (gkeProperties.isSimulated()) {
                execMode = BotExecutionMode.SPRING_AI_SIMULATION;
                canChat = true;
                reason = "本機模擬，GKE尚未串接";
            } else {
                execMode = BotExecutionMode.UNAVAILABLE;
                canChat = false;
                reason = "GKE 專案與叢集尚未配置";
            }
        }

        return new BotView(
                b.id(),
                b.name(),
                b.description(),
                b.instructions(),
                target,
                connStatus,
                execMode,
                reason,
                canChat,
                b.createdAt(),
                b.updatedAt() != null ? b.updatedAt() : b.createdAt(),
                b.ownerId()
        );
    }
}
