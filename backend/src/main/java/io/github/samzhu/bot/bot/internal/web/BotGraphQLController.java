package io.github.samzhu.bot.bot.internal.web;

import io.github.samzhu.bot.bot.BotOperations;
import io.github.samzhu.bot.bot.BotView;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;

import java.util.List;
import java.util.Optional;

@Controller
public class BotGraphQLController {

    private final BotOperations botOperations;

    public BotGraphQLController(BotOperations botOperations) {
        this.botOperations = botOperations;
    }

    @QueryMapping
    public List<BotView> bots(@Argument(name = "limit") Integer limit, @Argument(name = "offset") Integer offset) {
        int safeLimit = (limit == null || limit <= 0) ? 20 : limit;
        int safeOffset = (offset == null || offset < 0) ? 0 : offset;
        return botOperations.listBots(safeLimit, safeOffset);
    }

    @QueryMapping
    public Optional<BotView> bot(@Argument(name = "id") String id) {
        return botOperations.findById(id);
    }
}
