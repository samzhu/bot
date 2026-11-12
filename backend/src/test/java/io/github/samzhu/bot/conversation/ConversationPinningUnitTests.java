package io.github.samzhu.bot.conversation;

import graphql.GraphQLError;
import io.github.samzhu.bot.bot.BotOperations;
import io.github.samzhu.bot.conversation.internal.Conversation;
import io.github.samzhu.bot.conversation.internal.ConversationOperationsImpl;
import io.github.samzhu.bot.conversation.internal.ConversationRepository;
import io.github.samzhu.bot.conversation.internal.MessageRepository;
import io.github.samzhu.bot.conversation.internal.web.ConversationGraphQLController;
import io.github.samzhu.bot.identity.CurrentUserProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.jdbc.core.JdbcAggregateTemplate;
import org.springframework.graphql.execution.ErrorType;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConversationPinningUnitTests {

    @Test
    @DisplayName("Unit: Non-owner update in ConversationOperationsImpl throws ConversationAccessDeniedException")
    void testOperationsThrowsConversationAccessDeniedExceptionForNonOwner() {
        ConversationRepository convRepo = mock(ConversationRepository.class);
        MessageRepository msgRepo = mock(MessageRepository.class);
        JdbcAggregateTemplate jdbcTemp = mock(JdbcAggregateTemplate.class);
        BotOperations botOps = mock(BotOperations.class);

        ConversationOperationsImpl ops = new ConversationOperationsImpl(convRepo, msgRepo, jdbcTemp, botOps);

        Conversation conv = new Conversation("c-1", "b-1", "Title", "foreign-user", false, Instant.now(), Instant.now());
        when(convRepo.findById("c-1")).thenReturn(Optional.of(conv));

        ConversationAccessDeniedException ex = assertThrows(
                ConversationAccessDeniedException.class,
                () -> ops.setConversationPinned("c-1", true, "caller-user")
        );
        assertTrue(ex.getMessage().contains("caller-user"));
        assertTrue(ex.getMessage().contains("Unauthorized"));

        verify(convRepo, never()).updatePinned(anyString(), anyString(), anyBoolean());
    }

    @Test
    @DisplayName("Unit: Reload failure in ConversationOperationsImpl throws IllegalStateException, not AccessDenied")
    void testOperationsThrowsIllegalStateExceptionOnReloadFailure() {
        ConversationRepository convRepo = mock(ConversationRepository.class);
        MessageRepository msgRepo = mock(MessageRepository.class);
        JdbcAggregateTemplate jdbcTemp = mock(JdbcAggregateTemplate.class);

        ConversationOperationsImpl ops = new ConversationOperationsImpl(convRepo, msgRepo, jdbcTemp, mock(BotOperations.class));

        Conversation initial = new Conversation("c-1", "b-1", "Title", "owner-user", false, Instant.now(), Instant.now());
        when(convRepo.findById("c-1"))
                .thenReturn(Optional.of(initial)) // first lookup succeeds
                .thenReturn(Optional.empty());     // reload fails (unexpected internal error)

        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> ops.setConversationPinned("c-1", true, "owner-user")
        );
        assertTrue(ex.getMessage().contains("Failed to reload conversation after pin update"));
    }

    @Test
    @DisplayName("Unit: ConversationGraphQLController handles ConversationAccessDeniedException with FORBIDDEN classification")
    void testControllerExceptionHandlerProducesForbiddenClassification() {
        ConversationOperations ops = mock(ConversationOperations.class);
        CurrentUserProvider userProvider = mock(CurrentUserProvider.class);

        ConversationGraphQLController controller = new ConversationGraphQLController(ops, userProvider);

        ConversationAccessDeniedException ex = new ConversationAccessDeniedException("Unauthorized: Conversation c-1 is not owned by user caller-1");
        GraphQLError error = controller.handleConversationAccessDenied(ex);

        assertNotNull(error);
        assertEquals(ErrorType.FORBIDDEN, error.getErrorType());
        assertEquals("FORBIDDEN", error.getErrorType().toString());
        assertEquals("Unauthorized: Conversation c-1 is not owned by user caller-1", error.getMessage());
    }
}
