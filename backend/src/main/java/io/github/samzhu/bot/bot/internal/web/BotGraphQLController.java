package io.github.samzhu.bot.bot.internal.web;

import graphql.GraphQLError;
import graphql.GraphqlErrorBuilder;
import io.github.samzhu.bot.bot.BotOperations;
import io.github.samzhu.bot.bot.BotTarget;
import io.github.samzhu.bot.bot.BotView;
import io.github.samzhu.bot.bot.CreateBotInput;
import io.github.samzhu.bot.bot.UpdateBotInput;
import io.github.samzhu.bot.identity.CurrentUserProvider;
import jakarta.validation.Valid;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.GraphQlExceptionHandler;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.execution.ErrorType;
import org.springframework.stereotype.Controller;

import java.util.List;
import java.util.Optional;

@Controller
public class BotGraphQLController {

    private final BotOperations botOperations;
    private final CurrentUserProvider currentUserProvider;

    public BotGraphQLController(BotOperations botOperations, CurrentUserProvider currentUserProvider) {
        this.botOperations = botOperations;
        this.currentUserProvider = currentUserProvider;
    }

    @QueryMapping
    public List<BotView> bots(
            @Argument(name = "search") String search,
            @Argument(name = "target") BotTarget target,
            @Argument(name = "limit") Integer limit,
            @Argument(name = "offset") Integer offset) {
        int safeLimit = (limit == null || limit <= 0) ? 20 : limit;
        int safeOffset = (offset == null || offset < 0) ? 0 : offset;
        String userId = currentUserProvider.getCurrentUserId();
        return botOperations.listBots(userId, search, target, safeLimit, safeOffset);
    }

    @QueryMapping
    public Optional<BotView> bot(@Argument(name = "id") String id) {
        String userId = currentUserProvider.getCurrentUserId();
        return botOperations.findById(id, userId);
    }

    @QueryMapping
    public Optional<BotView> defaultLocalBot() {
        String userId = currentUserProvider.getCurrentUserId();
        return botOperations.findDefaultLocalBot(userId);
    }

    @MutationMapping
    public BotView createBot(@Valid @Argument(name = "input") CreateBotInput input) {
        String userId = currentUserProvider.getCurrentUserId();
        return botOperations.createBot(input, userId);
    }

    @MutationMapping
    public BotView updateBot(@Valid @Argument(name = "input") UpdateBotInput input) {
        String userId = currentUserProvider.getCurrentUserId();
        return botOperations.updateBot(input, userId);
    }

    @GraphQlExceptionHandler
    public GraphQLError handleIllegalArgument(IllegalArgumentException ex) {
        return GraphqlErrorBuilder.newError()
                .errorType(ErrorType.BAD_REQUEST)
                .message(ex.getMessage())
                .build();
    }
}
