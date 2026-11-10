package io.github.samzhu.bot.execution.internal.web;

import io.github.samzhu.bot.conversation.ConversationView;
import io.github.samzhu.bot.execution.ExecutionOperations;
import io.github.samzhu.bot.execution.RunView;
import io.github.samzhu.bot.identity.CurrentUserProvider;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.stereotype.Controller;

import java.util.List;

@Controller
public class ConversationRunsGraphQLController {

    private final ExecutionOperations executionOperations;
    private final CurrentUserProvider currentUserProvider;

    public ConversationRunsGraphQLController(
            ExecutionOperations executionOperations,
            CurrentUserProvider currentUserProvider) {
        this.executionOperations = executionOperations;
        this.currentUserProvider = currentUserProvider;
    }

    @QueryMapping
    public RunView run(@Argument(name = "id") String id) {
        String userId = currentUserProvider.getCurrentUserId();
        return executionOperations.findRunById(id, userId).orElse(null);
    }

    @SchemaMapping(typeName = "Conversation", field = "runs")
    public List<RunView> runs(ConversationView conversation, @Argument(name = "limit") Integer limit, @Argument(name = "offset") Integer offset) {
        int safeLimit = (limit == null || limit <= 0) ? 20 : limit;
        int safeOffset = (offset == null || offset < 0) ? 0 : offset;
        return executionOperations.listRunsByConversation(conversation.id(), safeLimit, safeOffset);
    }
}
