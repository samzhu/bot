package io.github.samzhu.bot.bot.internal;

import io.github.samzhu.bot.bot.BotOperations;
import io.github.samzhu.bot.bot.BotView;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class BotOperationsImpl implements BotOperations {

    private final BotRepository botRepository;

    public BotOperationsImpl(BotRepository botRepository) {
        this.botRepository = botRepository;
    }

    @Override
    public Optional<BotView> findById(String id) {
        return botRepository.findById(id).map(this::toView);
    }

    @Override
    public List<BotView> listBots(int limit, int offset) {
        int safeLimit = Math.max(1, Math.min(limit <= 0 ? 20 : limit, 100));
        int safeOffset = Math.max(0, offset);
        return botRepository.findAllWithPaging(safeLimit, safeOffset).stream()
                .map(this::toView)
                .toList();
    }

    @Override
    public Optional<String> findSystemPromptById(String id) {
        return botRepository.findById(id).map(Bot::systemPrompt);
    }

    private BotView toView(Bot b) {
        return new BotView(b.id(), b.name(), b.createdAt());
    }
}
