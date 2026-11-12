package io.github.samzhu.bot.execution;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Optional;

public interface ExecutionOperations {
    Optional<RunView> findRunById(String runId, String userId);
    List<RunView> listRunsByConversation(String conversationId, int limit, int offset);
    StartRunOutcome startRun(String conversationId, String clientRequestId, String message, String userId);
    StartRunOutcome startAgUiRun(String conversationId, String runId, String message, String userId);
    CancelRunOutcome cancelRun(String runId, String userId);

    record StartRunOutcome(
        Type type,
        RunView existingRun,
        SseEmitter emitter,
        String conflictMessage
    ) {
        public enum Type { STARTED, EXISTING, CONFLICT, SERVICE_UNAVAILABLE, ACCESS_DENIED }
    }

    record CancelRunOutcome(
        String runId,
        RunStatus finalStatus,
        boolean cancelled,
        boolean accessDenied
    ) {}
}
