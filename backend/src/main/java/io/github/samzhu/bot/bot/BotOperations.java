package io.github.samzhu.bot.bot;

import java.util.List;
import java.util.Optional;

public interface BotOperations {
    Optional<BotView> findById(String id);
    List<BotView> listBots(int limit, int offset);
    Optional<String> findSystemPromptById(String id);
}
