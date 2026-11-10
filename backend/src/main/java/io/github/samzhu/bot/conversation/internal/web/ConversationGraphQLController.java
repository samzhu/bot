package io.github.samzhu.bot.conversation.internal.web;

import io.github.samzhu.bot.conversation.ConversationOperations;
import io.github.samzhu.bot.conversation.ConversationView;
import io.github.samzhu.bot.conversation.MessageView;
import io.github.samzhu.bot.identity.CurrentUserProvider;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.stereotype.Controller;

import java.util.List;

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
    public List<ConversationView> conversations(@Argument(name = "limit") Integer limit, @Argument(name = "offset") Integer offset) {
        int safeLimit = (limit == null || limit <= 0) ? 20 : limit;
        int safeOffset = (offset == null || offset < 0) ? 0 : offset;
        String userId = currentUserProvider.getCurrentUserId();
        return conversationOperations.listConversations(userId, safeLimit, safeOffset);
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
        return conversationOperations.listMessages(conversation.id(), safeLimit, safeOffset);
    }

    @MutationMapping
    public ConversationView createConversation(@Argument(name = "botId") String botId, @Argument(name = "title") String title) {
        String userId = currentUserProvider.getCurrentUserId();
        return conversationOperations.createConversation(botId, title, userId);
    }
}
