package io.github.samzhu.bot.execution.internal;

import io.github.samzhu.bot.conversation.ConversationOperations;
import io.github.samzhu.bot.execution.internal.capture.UsageCaptureAdapter;
import io.github.samzhu.bot.usage.UsageLedger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Flux;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class UsageInvocation {
    private static final Logger log = LoggerFactory.getLogger(UsageInvocation.class);
    private final UsageLedger ledger;
    private final UsageCaptureAdapter adapter;
    private final ConversationOperations conversations;
    private final DispatchCommitObserver dispatchCommitObserver;
    private final reactor.core.scheduler.Scheduler finalizationScheduler;

    public UsageInvocation(
            UsageLedger ledger,
            UsageCaptureAdapter adapter,
            ConversationOperations conversations,
            DispatchCommitObserver dispatchCommitObserver,
            @org.springframework.beans.factory.annotation.Qualifier("usageFinalizationScheduler")
                    reactor.core.scheduler.Scheduler finalizationScheduler) {
        this.ledger = ledger;
        this.adapter = adapter;
        this.conversations = conversations;
        this.dispatchCommitObserver = dispatchCommitObserver;
        this.finalizationScheduler = finalizationScheduler;
    }

    public record Attempt(String id, RunHandle.DispatchGate gate) {}

    public Attempt begin(String run, String workspace, String actor, RunHandle handle) {
        RunHandle.DispatchGate gate = handle.newDispatchGate();
        return new Attempt(ledger.begin(adapter.dispatch(run, workspace, actor)), gate);
    }

    public Flux<ChatResponse> stream(
            ChatModel model,
            Prompt prompt,
            Attempt attempt,
            String conversationId,
            String workspaceId,
            String actorId,
            RunHandle handle) {
        AtomicLong ordinal = new AtomicLong();
        return Flux.defer(
                        () -> {
                            // Scope is checked at the invocation boundary; a queued Run may have
                            // lost membership.
                            var conversation =
                                    conversations.findConversationById(conversationId, actorId);
                            if (conversation.isEmpty()
                                    || !workspaceId.equals(conversation.get().workspaceId())) {
                                ledger.notDispatched(attempt.id(), "WORKSPACE_ACCESS_DENIED");
                                return Flux.error(
                                        new IllegalStateException("Workspace access denied"));
                            }
                            if (!prepareDispatch(attempt))
                                return Flux.error(
                                        new IllegalStateException("Run ended before dispatch"));
                            return Flux.defer(() -> model.stream(prompt))
                                    .doOnError(
                                            error ->
                                                    finishSafely(
                                                            attempt.id(),
                                                            handle.cancellationRequested()
                                                                    ? "CANCELLED"
                                                                    : "FAILED"))
                                    .doOnNext(response -> capture(response, attempt, ordinal.incrementAndGet()))
                                    .doOnComplete(() -> finishSuccess(attempt.id()));
                        })
                .doOnCancel(() -> finalizeCancellationAsync(attempt, handle));
    }

    public reactor.core.publisher.Mono<ChatResponse> call(
            ChatModel model,
            Prompt prompt,
            Attempt attempt,
            String conversationId,
            String workspaceId,
            String actorId,
            RunHandle handle) {
        return reactor.core.publisher.Mono.defer(
                        () -> {
                            var conversation =
                                    conversations.findConversationById(conversationId, actorId);
                            if (conversation.isEmpty()
                                    || !workspaceId.equals(conversation.get().workspaceId())) {
                                ledger.notDispatched(attempt.id(), "WORKSPACE_ACCESS_DENIED");
                                return reactor.core.publisher.Mono.error(
                                        new IllegalStateException("Workspace access denied"));
                            }
                            if (!prepareDispatch(attempt))
                                return reactor.core.publisher.Mono.error(
                                        new IllegalStateException("Run ended before dispatch"));
                            return reactor.core.publisher.Mono.fromCallable(
                                            () ->
                                                    java.util.Objects.requireNonNull(
                                                            model.call(prompt),
                                                            "Model response missing"))
                                    .doOnError(
                                            error ->
                                                    finishSafely(
                                                            attempt.id(),
                                                            handle.cancellationRequested()
                                                                    ? "CANCELLED"
                                                                    : "FAILED"))
                                    .doOnNext(response -> capture(response, attempt, 1))
                                    .doOnSuccess(response -> finishSuccess(attempt.id()));
                        })
                .doOnCancel(() -> finalizeCancellationAsync(attempt, handle));
    }

    private void capture(ChatResponse response, Attempt attempt, long ordinal) {
        try {
            adapter.responseId(response).ifPresent(id -> ledger.recordResponseId(attempt.id(), id));
            adapter.capture(response, UUID.randomUUID().toString(), ordinal)
                    .ifPresent(snapshot -> ledger.append(attempt.id(), snapshot));
        } catch (RuntimeException ex) {
            // Capture exceptions may contain provider payloads or SQL values; neither may escape.
            finishSafely(attempt.id(), "UNKNOWN");
            throw new IllegalStateException("Usage capture failed");
        }
    }

    private void finishSuccess(String attempt) {
        try {
            ledger.finish(attempt, "SUCCEEDED");
        } catch (RuntimeException ex) {
            // Leave the durable attempt for restart recovery without exposing storage details.
            throw new IllegalStateException("Usage finalization failed");
        }
    }

    private boolean prepareDispatch(Attempt attempt) {
        if (!attempt.gate().prepare()) {
            notDispatchedSafely(attempt.id(), "RUN_TERMINATED_BEFORE_DISPATCH");
            return false;
        }
        try {
            if (!ledger.markDispatched(attempt.id(), attempt.gate()::authorize)) {
                notDispatchedSafely(attempt.id(), "DISPATCH_TIME_NOT_RECORDED");
                return false;
            }
        } catch (RuntimeException ex) {
            notDispatchedSafely(attempt.id(), "DISPATCH_TIME_WRITE_FAILED");
            return false;
        }
        try {
            dispatchCommitObserver.afterCommit(attempt.id());
        } catch (RuntimeException ex) {
            notDispatchedSafely(attempt.id(), "DISPATCH_BOUNDARY_FAILED");
            return false;
        }
        if (!attempt.gate().confirm()) {
            notDispatchedSafely(attempt.id(), "RUN_TERMINATED_BEFORE_DISPATCH");
            return false;
        }
        return true;
    }

    public void finalizeCancellationAsync(Attempt attempt, RunHandle handle) {
        // Cancellation can dispose the subscription while holding the RunHandle monitor.
        // Never perform the ledger write in that callback; a failed write leaves durable recovery
        // evidence.
        try {
            finalizationScheduler.schedule(
                    () -> {
                        if (attempt.gate().dispatched()) finishSafely(attempt.id(), "CANCELLED");
                        else notDispatchedSafely(attempt.id(), "RUN_TERMINATED_BEFORE_DISPATCH");
                    });
        } catch (RuntimeException ex) {
            log.error("Unable to schedule usage cancellation recovery for attempt {}", attempt.id());
        }
    }

    public void notDispatchedSafely(String attempt, String reason) {
        try {
            ledger.notDispatched(attempt, reason);
        } catch (RuntimeException ex) {
            log.error(
                    "Unable to finalize undispatched usage attempt {}; recovery is required",
                    attempt);
        }
    }

    public void finishSafely(String attempt, String outcome) {
        try {
            ledger.finish(attempt, outcome);
        } catch (RuntimeException ex) {
            // The durable IN_PROGRESS attempt is retained for recovery when storage is unavailable.
            log.error("Unable to finalize usage attempt {}; recovery is required", attempt);
        }
    }
}
