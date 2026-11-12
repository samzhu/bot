package io.github.samzhu.bot.bot;

import java.util.List;
import java.util.Optional;

/**
 * Public Facade contract for Bot management and querying.
 *
 * <p>OFFICIAL REFERENCE:
 * <ul>
 *   <li>Spring Framework 6.2 / Spring Boot 4.1 Service Facade patterns: https://docs.spring.io/spring-framework/reference/core/beans.html</li>
 *   <li>Checked: 2026-11-12</li>
 * </ul>
 *
 * <p>WHY / ARCHITECTURAL RATIONALE:
 * Preserves clean domain module boundaries (aligned with Spring Modulith principles).
 * External caller modules (e.g. Conversation, Execution, Web) access Bot capabilities exclusively
 * through this public interface, never directly importing internal repository or aggregate entities.
 */
public interface BotOperations {
    Optional<BotView> findById(String id, String userId);

    default List<BotView> listBots(String userId, int limit, int offset) {
        return listBots(userId, null, null, limit, offset);
    }

    List<BotView> listBots(String userId, String search, BotTarget target, int limit, int offset);

    Optional<BotView> findDefaultLocalBot(String userId);

    BotView createBot(CreateBotInput input, String userId);

    BotView updateBot(UpdateBotInput input, String userId);

    boolean canChat(String botId, String userId);

    Optional<String> getEffectiveSystemPrompt(String botId, String userId);
}
