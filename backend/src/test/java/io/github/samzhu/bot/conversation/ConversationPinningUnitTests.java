package io.github.samzhu.bot.conversation;

import graphql.GraphQLError;
import io.github.samzhu.bot.bot.BotOperations;
import io.github.samzhu.bot.conversation.internal.Conversation;
import io.github.samzhu.bot.conversation.internal.ConversationOperationsImpl;
import io.github.samzhu.bot.conversation.internal.ConversationRepository;
import io.github.samzhu.bot.conversation.internal.MessageRepository;
import io.github.samzhu.bot.conversation.internal.web.ConversationGraphQLController;
import io.github.samzhu.bot.identity.CurrentUserProvider;
import io.github.samzhu.bot.workspace.WorkspaceOperations;
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

        ConversationOperationsImpl ops = new ConversationOperationsImpl(convRepo, msgRepo, jdbcTemp, botOps, mock(WorkspaceOperations.class));

        Conversation conv = new Conversation("c-1", "b-1", "Title", "foreign-user", "workspace-1", false, Instant.now(), Instant.now());
        when(convRepo.findById("c-1")).thenReturn(Optional.of(conv));

        ConversationAccessDeniedException ex = assertThrows(
                ConversationAccessDeniedException.class,
                () -> ops.setConversationPinned("c-1", true, "caller-user")
        );
        assertEquals("Conversation access denied", ex.getMessage());
        assertFalse(ex.getMessage().contains("caller-user"));

        verify(convRepo, never()).updatePinned(anyString(), anyString(), anyString(), anyBoolean());
    }

    @Test
    @DisplayName("Unit: Reload failure in ConversationOperationsImpl throws IllegalStateException, not AccessDenied")
    void testOperationsThrowsIllegalStateExceptionOnReloadFailure() {
        ConversationRepository convRepo = mock(ConversationRepository.class);
        MessageRepository msgRepo = mock(MessageRepository.class);
        JdbcAggregateTemplate jdbcTemp = mock(JdbcAggregateTemplate.class);

        WorkspaceOperations workspaces = mock(WorkspaceOperations.class);
        when(workspaces.isMember("workspace-1", "owner-user")).thenReturn(true);
        ConversationOperationsImpl ops = new ConversationOperationsImpl(convRepo, msgRepo, jdbcTemp, mock(BotOperations.class), workspaces);

        Conversation initial = new Conversation("c-1", "b-1", "Title", "owner-user", "workspace-1", false, Instant.now(), Instant.now());
        when(convRepo.findById("c-1"))
                .thenReturn(Optional.of(initial)) // access check and update target
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
        assertEquals("Conversation access denied", error.getMessage());
        assertEquals("CONVERSATION_ACCESS_DENIED", error.getExtensions().get("code"));
        assertFalse(error.getMessage().contains("caller-1"));
    }
}
