package io.github.samzhu.bot.conversation.internal.web;

import graphql.GraphQLError;
import graphql.GraphqlErrorBuilder;
import io.github.samzhu.bot.bot.GkeAgentNotConnectedException;
import io.github.samzhu.bot.conversation.ConversationAccessDeniedException;
import io.github.samzhu.bot.conversation.ConversationOperations;
import io.github.samzhu.bot.conversation.ConversationSearchValidationException;
import io.github.samzhu.bot.conversation.ConversationView;
import io.github.samzhu.bot.conversation.MessageView;
import io.github.samzhu.bot.identity.CurrentUserProvider;
import io.github.samzhu.bot.workspace.WorkspaceAccessDeniedException;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.GraphQlExceptionHandler;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.graphql.execution.ErrorType;
import org.springframework.stereotype.Controller;

import java.util.List;
import java.util.Map;

@Controller
public class ConversationGraphQLController {

    private final ConversationOperations conversationOperations;
    private final CurrentUserProvider currentUserProvider;

    public ConversationGraphQLController(
            ConversationOperations conversationOperations,
            CurrentUserProvider currentUserProvider) {
        this.conversationOperations = conversationOperations;
        this.currentUserProvider = currentUserProvider;
    }

    @QueryMapping
    public List<ConversationView> conversations(
            @Argument(name = "workspaceId") String workspaceId,
            @Argument(name = "search") String search,
            @Argument(name = "pinned") Boolean pinned,
            @Argument(name = "limit") Integer limit,
            @Argument(name = "offset") Integer offset) {
        int safeLimit = (limit == null || limit <= 0) ? 20 : limit;
        int safeOffset = (offset == null || offset < 0) ? 0 : offset;
        String userId = currentUserProvider.getCurrentUserId();
        return conversationOperations.listConversations(userId, search, pinned, safeLimit, safeOffset, workspaceId);
    }

    @QueryMapping
    public ConversationView conversation(@Argument(name = "id") String id) {
        String userId = currentUserProvider.getCurrentUserId();
        return conversationOperations.findConversationById(id, userId).orElse(null);
    }

    @SchemaMapping(typeName = "Conversation", field = "messages")
    public List<MessageView> messages(ConversationView conversation, @Argument(name = "limit") Integer limit, @Argument(name = "offset") Integer offset) {
        int safeLimit = (limit == null || limit <= 0) ? 20 : limit;
        int safeOffset = (offset == null || offset < 0) ? 0 : offset;
        if (conversationOperations.findConversationById(conversation.id(), currentUserProvider.getCurrentUserId()).isEmpty()) {
            throw new ConversationAccessDeniedException("Conversation access denied");
        }
        return conversationOperations.listMessages(conversation.id(), safeLimit, safeOffset);
    }

    @MutationMapping
    public ConversationView createConversation(@Argument(name = "botId") String botId, @Argument(name = "title") String title, @Argument(name = "workspaceId") String workspaceId) {
        String userId = currentUserProvider.getCurrentUserId();
        return conversationOperations.createConversation(botId, title, userId, workspaceId);
    }

    @MutationMapping
    public ConversationView setConversationPinned(
            @Argument(name = "id") String id,
            @Argument(name = "pinned") boolean pinned) {
        String userId = currentUserProvider.getCurrentUserId();
        return conversationOperations.setConversationPinned(id, pinned, userId);
    }

    @GraphQlExceptionHandler
    public GraphQLError handleConversationAccessDenied(ConversationAccessDeniedException ex) {
        return GraphqlErrorBuilder.newError()
                .errorType(ErrorType.FORBIDDEN)
                .message("Conversation access denied")
                .extensions(Map.of("code", "CONVERSATION_ACCESS_DENIED"))
                .build();
    }

    @GraphQlExceptionHandler
    public GraphQLError handleWorkspaceAccessDenied(WorkspaceAccessDeniedException ex) {
        return GraphqlErrorBuilder.newError().errorType(ErrorType.FORBIDDEN).message("Workspace access denied").extensions(Map.of("code", "WORKSPACE_ACCESS_DENIED")).build();
    }

    @GraphQlExceptionHandler
    public GraphQLError handleInvalidSearch(ConversationSearchValidationException ex) {
        return GraphqlErrorBuilder.newError()
                .errorType(ErrorType.BAD_REQUEST)
                .message(ex.getMessage())
                .build();
    }

    @GraphQlExceptionHandler
    public GraphQLError handleGkeAgentNotConnected(GkeAgentNotConnectedException ex) {
        return GraphqlErrorBuilder.newError()
                .errorType(ErrorType.BAD_REQUEST)
                .message(ex.getMessage())
                .extensions(Map.of("code", "GKE_AGENT_NOT_CONNECTED"))
                .build();
    }
}
