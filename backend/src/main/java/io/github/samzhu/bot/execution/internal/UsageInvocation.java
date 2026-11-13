package io.github.samzhu.bot.execution.internal;

import io.github.samzhu.bot.conversation.ConversationOperations;
import io.github.samzhu.bot.execution.internal.capture.UsageCaptureAdapter;
import io.github.samzhu.bot.usage.UsageLedger;
import io.github.samzhu.bot.usage.WorkspaceAdmission;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class UsageInvocation {
    private static final Logger log = LoggerFactory.getLogger(UsageInvocation.class);
    private final UsageLedger ledger;
    private final WorkspaceAdmission admission;
    private final UsageCaptureAdapter adapter;
    private final ConversationOperations conversations;
    private final DispatchCommitObserver dispatchCommitObserver;
    private final reactor.core.scheduler.Scheduler finalizationScheduler;
    private final reactor.core.scheduler.Scheduler providerScheduler;

    public UsageInvocation(
            UsageLedger ledger,
            WorkspaceAdmission admission,
            UsageCaptureAdapter adapter,
            ConversationOperations conversations,
            DispatchCommitObserver dispatchCommitObserver,
            @org.springframework.beans.factory.annotation.Qualifier("usageFinalizationScheduler")
                    reactor.core.scheduler.Scheduler finalizationScheduler,
            @org.springframework.beans.factory.annotation.Qualifier("providerDispatchScheduler")
                    reactor.core.scheduler.Scheduler providerScheduler) {
        this.ledger = ledger;
        this.admission = admission;
        this.adapter = adapter;
        this.conversations = conversations;
        this.dispatchCommitObserver = dispatchCommitObserver;
        this.finalizationScheduler = finalizationScheduler;
        this.providerScheduler = providerScheduler;
    }

    public record Attempt(String id, WorkspaceAdmission.Registration registration,
                          java.util.concurrent.atomic.AtomicReference<ClaimState> claim,
                          java.util.concurrent.atomic.AtomicReference<WorkspaceAdmission.ClaimFence> fence,
                          java.util.concurrent.atomic.AtomicReference<WorkspaceAdmission.TerminalKind> terminalIntent,
                          java.util.concurrent.CompletableFuture<Boolean> settled) {}
    enum ClaimState { PENDING, SUBMITTED, CLAIMED, DENIED, UNKNOWN }

    public UsageLedger.Dispatch dispatchMetadata(String run, String workspace, String actor) {
        return adapter.dispatch(run, workspace, actor);
    }

    public Attempt begin(UsageLedger.Dispatch dispatch, WorkspaceAdmission.Registration registration) {
        return new Attempt(ledger.begin(dispatch), registration, new java.util.concurrent.atomic.AtomicReference<>(ClaimState.PENDING),
                new java.util.concurrent.atomic.AtomicReference<>(), new java.util.concurrent.atomic.AtomicReference<>(),
                new java.util.concurrent.CompletableFuture<>());
    }

    public void bindCompletion(Attempt attempt, RunHandle handle) {
        attempt.settled().thenCombine(handle.durableTerminal(), (settled, ignored) -> settled)
                .thenAcceptAsync(settled -> {
                    if (settled) admission.complete(attempt.registration());
                }, task -> finalizationScheduler.schedule(task));
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
                                finalizeAttempt(attempt, WorkspaceAdmission.TerminalKind.NOT_DISPATCHED, WorkspaceAdmission.TerminalReason.WORKSPACE_ACCESS_DENIED);
                                return settlement(attempt).thenMany(Flux.error(
                                        new IllegalStateException("Workspace access denied")));
                            }
                            return prepareDispatch(attempt, handle).flatMapMany(decision -> {
                                if (decision==DispatchResolution.BUDGET_PENDING) return awaitDurableTerminal(handle).thenMany(Flux.empty());
                                if (decision!=DispatchResolution.READY) return settlement(attempt).thenMany(Flux.error(new IllegalStateException("Run ended before dispatch")));
                                return Flux.defer(() -> {
                                    if (handle.cancellationRequested()) return Flux.error(new IllegalStateException("Run cancelled before provider invocation"));
                                    return model.stream(prompt);
                                })
                                    // Admission completes asynchronously; isolate the later blocking subscription
                                    // so cancellation can reach its worker even after the outer subscribe returned.
                                    .subscribeOn(providerScheduler)
                                    .doOnNext(response -> capture(response, attempt, ordinal.incrementAndGet()))
                                    .onErrorResume(error -> {
                                        if (!(error instanceof CaptureFailure))
                                            finishSafely(attempt, handle.cancellationRequested() ? "CANCELLED" : "FAILED");
                                        return settlement(attempt).thenMany(Flux.error(error));
                                    })
                                    .concatWith(Flux.defer(() -> {
                                        finishSuccess(attempt);
                                        return settlement(attempt).thenMany(Flux.empty());
                                    }));
                            });
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
                                finalizeAttempt(attempt, WorkspaceAdmission.TerminalKind.NOT_DISPATCHED, WorkspaceAdmission.TerminalReason.WORKSPACE_ACCESS_DENIED);
                                return settlement(attempt).then(Mono.error(
                                        new IllegalStateException("Workspace access denied")));
                            }
                            return prepareDispatch(attempt, handle).flatMap(decision -> {
                                if (decision==DispatchResolution.BUDGET_PENDING) return awaitDurableTerminal(handle).then(Mono.empty());
                                if (decision!=DispatchResolution.READY) return settlement(attempt).then(Mono.error(new IllegalStateException("Run ended before dispatch")));
                                return reactor.core.publisher.Mono.fromCallable(
                                            () -> {
                                                    if (handle.cancellationRequested()) throw new IllegalStateException("Run cancelled before provider invocation");
                                                    return java.util.Objects.requireNonNull(
                                                            model.call(prompt),
                                                            "Model response missing");
                                            })
                                    .doOnNext(response -> capture(response, attempt, 1))
                                    .onErrorResume(error -> {
                                        if (!(error instanceof CaptureFailure))
                                            finishSafely(attempt, handle.cancellationRequested() ? "CANCELLED" : "FAILED");
                                        return settlement(attempt).then(Mono.error(error));
                                    })
                                    .flatMap(response -> {
                                        finishSuccess(attempt);
                                        return settlement(attempt).thenReturn(response);
                                    });
                            });
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
            finalizeAttempt(attempt, WorkspaceAdmission.TerminalKind.UNKNOWN, WorkspaceAdmission.TerminalReason.CAPTURE_FAILED);
            throw new CaptureFailure();
        }
    }

    private static final class CaptureFailure extends IllegalStateException {
        private CaptureFailure() { super("Usage capture failed"); }
    }

    private void finishSuccess(Attempt attempt) {
        finalizeAttempt(attempt, WorkspaceAdmission.TerminalKind.SUCCEEDED);
    }

    private Mono<Void> settlement(Attempt attempt) {
        // Subscriber cancellation must not cancel the shared durable finalization future.
        return Mono.fromFuture(attempt.settled(), true)
                .flatMap(settled -> Boolean.TRUE.equals(settled)
                        ? Mono.empty() : Mono.error(new IllegalStateException("Usage finalization requires recovery")));
    }

    public void finalizeAttempt(Attempt attempt, WorkspaceAdmission.TerminalKind kind) {
        finalizeAttempt(attempt, kind, null);
    }

    public void afterSettled(Attempt attempt, Runnable action) {
        // Settlement completes outside the workspace lane; never wait for it while holding a Run lock.
        attempt.settled().whenCompleteAsync((settled, error) -> {
            if (error == null && Boolean.TRUE.equals(settled)) action.run();
            else log.error("Attempt finalization requires recovery for {}", attempt.id());
        }, task -> finalizationScheduler.schedule(task)).exceptionally(error -> {
            log.error("Unable to schedule settled Run continuation for {}", attempt.id());
            return null;
        });
    }

    public void finalizeAttempt(Attempt attempt, WorkspaceAdmission.TerminalKind kind, WorkspaceAdmission.TerminalReason reason) {
        // The first terminal evidence owns this attempt; a downstream error or cancellation
        // must not issue a conflicting command after capture has selected UNKNOWN.
        if (!attempt.terminalIntent().compareAndSet(null, kind)) return;
        try {
            admission.finalizeAttempt(new WorkspaceAdmission.FinalizeAttempt(
                    attempt.registration(), attempt.id(), attempt.fence().get(), kind, reason))
                .whenComplete((result, error) -> {
                    attempt.settled().complete(error == null && result == WorkspaceAdmission.FinalizationResult.SETTLED);
                });
        } catch (RuntimeException ex) {
            attempt.settled().complete(false);
            log.error("Unable to enqueue attempt finalization; recovery is required for {}", attempt.id());
        }
    }

    private enum DispatchResolution { READY, STOPPED, BUDGET_PENDING }

    private Mono<Void> awaitDurableTerminal(RunHandle handle) {
        // Cancellation of this subscriber must not cancel the shared terminal observation.
        return Mono.create(sink -> handle.durableTerminal().whenComplete((ignored,error) -> {
            if (error==null) sink.success(); else sink.error(error);
        }));
    }

    private reactor.core.publisher.Mono<DispatchResolution> prepareDispatch(Attempt attempt, RunHandle handle) {
        // The stage is not cancelled with the subscriber: a late claim must still be accounted for.
        return reactor.core.publisher.Mono.create(sink -> {
            var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
            sink.onCancel(() -> cancelled.set(true));
            if (handle.cancellationRequested()) {
                attempt.claim().set(ClaimState.DENIED);
                finalizeAttempt(attempt, WorkspaceAdmission.TerminalKind.NOT_DISPATCHED, WorkspaceAdmission.TerminalReason.RUN_TERMINATED_BEFORE_DISPATCH);
                sink.success(DispatchResolution.STOPPED);
                return;
            }
            if (!attempt.claim().compareAndSet(ClaimState.PENDING, ClaimState.SUBMITTED)) {
                sink.success(DispatchResolution.STOPPED);
                return;
            }
            final java.util.concurrent.CompletionStage<WorkspaceAdmission.Decision> dispatch;
            try {
                dispatch = admission.dispatch(new WorkspaceAdmission.Dispatch(attempt.registration(), attempt.id()));
                if (dispatch == null) throw new IllegalStateException("Admission returned no decision stage");
            } catch (RuntimeException ex) {
                // A synchronous failure may occur after the lane received work; only
                // recoverClaim can distinguish a committed claim from no dispatch.
                attempt.claim().set(ClaimState.UNKNOWN);
                recoverDispatch(attempt);
                if (!cancelled.get()) sink.error(new IllegalStateException("Admission outcome unknown"));
                return;
            }
            dispatch.whenComplete((decision,failure) -> {
                // Install the barrier before scheduling the continuation: rejection must not
                // let a known durable budget cancellation fall into generic Run failure.
                if (failure==null && decision instanceof WorkspaceAdmission.Decision.BudgetCancellationPending budget
                        && !handle.protectBudgetCancellation(budget.effect()))
                    throw new IllegalStateException("Budget cancellation identity requires recovery");
            })
                .whenCompleteAsync((decision, failure) -> {
                    if (failure != null || decision == null) {
                        attempt.claim().set(ClaimState.UNKNOWN);
                        recoverDispatch(attempt);
                        if (!cancelled.get()) sink.error(new IllegalStateException("Admission outcome unknown"));
                        return;
                    }
                    if (decision instanceof WorkspaceAdmission.Decision.BudgetCancellationPending budget) {
                        if (!handle.protectBudgetCancellation(budget.effect())) {
                            attempt.claim().set(ClaimState.UNKNOWN);
                            recoverDispatch(attempt);
                            if (!cancelled.get()) sink.error(new IllegalStateException("Budget cancellation identity requires recovery"));
                            return;
                        }
                        attempt.claim().set(ClaimState.DENIED);
                        finalizeAttempt(attempt, WorkspaceAdmission.TerminalKind.NOT_DISPATCHED);
                        if (!cancelled.get()) sink.success(DispatchResolution.BUDGET_PENDING);
                        return;
                    }
                    if (decision instanceof WorkspaceAdmission.Decision.Denied denied) {
                        if (denied.reason() == WorkspaceAdmission.Reason.RECOVERY_REQUIRED
                                || denied.reason() == WorkspaceAdmission.Reason.OVERLOADED) {
                            attempt.claim().set(ClaimState.UNKNOWN);
                            recoverDispatch(attempt);
                            if (!cancelled.get()) sink.error(new IllegalStateException("Admission outcome unknown"));
                            return;
                        }
                        attempt.claim().set(ClaimState.DENIED);
                        finalizeAttempt(attempt, WorkspaceAdmission.TerminalKind.NOT_DISPATCHED,
                                denied.reason() == WorkspaceAdmission.Reason.UNAUTHORIZED ? WorkspaceAdmission.TerminalReason.WORKSPACE_ACCESS_DENIED
                                        : denied.reason() == WorkspaceAdmission.Reason.CANCELLED ? WorkspaceAdmission.TerminalReason.RUN_TERMINATED_BEFORE_DISPATCH : null);
                        if (!cancelled.get()) sink.success(DispatchResolution.STOPPED);
                        return;
                    }
                    var allowed = (WorkspaceAdmission.Decision.Allowed) decision;
                    attempt.claim().set(ClaimState.CLAIMED);
                    attempt.fence().set(allowed.claim());
                    handle.setAdmissionClaim(allowed.claim());
                    try {
                        dispatchCommitObserver.afterCommit(attempt.id());
                    } catch (RuntimeException ex) {
                        finishSafely(attempt, "UNKNOWN");
                        if (!cancelled.get()) sink.error(new IllegalStateException("Dispatch boundary requires recovery"));
                        return;
                    }
                    if (cancelled.get() || handle.cancellationRequested()) {
                        finishSafely(attempt, "CANCELLED");
                        if (!cancelled.get()) sink.success(DispatchResolution.STOPPED);
                        return;
                    }
                    admission.authorizeProviderStart(allowed.claim()).whenCompleteAsync((start, startFailure) -> {
                        if (startFailure != null || start == null) {
                            finishSafely(attempt, "UNKNOWN");
                            if (!cancelled.get()) sink.error(new IllegalStateException("Provider start outcome unknown"));
                        } else if (!(start instanceof WorkspaceAdmission.ProviderStartResult.Ready)
                                || cancelled.get() || handle.cancellationRequested()) {
                            finishSafely(attempt, "CANCELLED");
                            if (!cancelled.get()) sink.success(DispatchResolution.STOPPED);
                        } else {
                            sink.success(DispatchResolution.READY);
                        }
                    }, task -> finalizationScheduler.schedule(task)).exceptionally(error -> {
                        attempt.claim().set(ClaimState.UNKNOWN);
                        finishSafely(attempt, "UNKNOWN");
                        if (!cancelled.get()) sink.error(new IllegalStateException("Provider start continuation requires recovery"));
                        return null;
                    });
                }, task -> finalizationScheduler.schedule(task))
                .exceptionally(error -> {
                    attempt.claim().set(ClaimState.UNKNOWN);
                    recoverDispatch(attempt);
                    if (!cancelled.get()) sink.error(new IllegalStateException("Admission continuation requires recovery"));
                    return null;
                });
        });
    }

    private void recoverDispatch(Attempt attempt) {
        try {
            // Recovery is a separate lane command; a lost dispatch is never resubmitted.
            admission.recoverClaim(attempt.registration(), attempt.id())
                    .whenCompleteAsync((inspection, failure) -> {
                        if (failure != null) return;
                        if (inspection instanceof WorkspaceAdmission.ClaimInspection.Found found) {
                            attempt.fence().set(found.claim());
                            attempt.claim().set(ClaimState.CLAIMED);
                            finishSafely(attempt, "UNKNOWN");
                        } else if (inspection instanceof WorkspaceAdmission.ClaimInspection.Absent) {
                            attempt.claim().set(ClaimState.DENIED);
                            finalizeAttempt(attempt, WorkspaceAdmission.TerminalKind.NOT_DISPATCHED);
                        }
                    }, task -> finalizationScheduler.schedule(task));
        } catch (RuntimeException ex) {
            log.error("Unable to request claim recovery for attempt {}", attempt.id());
        }
    }

    public void finalizeCancellationAsync(Attempt attempt, RunHandle handle) {
        try {
            finalizationScheduler.schedule(() -> {
                // PENDING or UNKNOWN may already have committed a claim. Its completion or
                // recovery owns finalization; never erase a possibly committed startedAt.
                if (attempt.claim().get() == ClaimState.CLAIMED) finishSafely(attempt, "CANCELLED");
                else if (attempt.claim().compareAndSet(ClaimState.PENDING, ClaimState.DENIED))
                    finalizeAttempt(attempt, WorkspaceAdmission.TerminalKind.NOT_DISPATCHED, WorkspaceAdmission.TerminalReason.RUN_TERMINATED_BEFORE_DISPATCH);
                else if (handle.admissionCancelledBeforeClaim()
                        && attempt.claim().compareAndSet(ClaimState.SUBMITTED, ClaimState.UNKNOWN))
                    // Cancellation is committed in the same lane. A not-yet-returned
                    // dispatch may have reached it; only its authoritative result is safe.
                    recoverDispatch(attempt);
            });
        } catch (RuntimeException ex) {
            log.error("Unable to schedule usage cancellation recovery for attempt {}", attempt.id());
        }
    }

    public void finishSafely(Attempt attempt, String outcome) {
        var kind = WorkspaceAdmission.TerminalKind.valueOf(outcome);
        finalizeAttempt(attempt, kind, kind == WorkspaceAdmission.TerminalKind.FAILED
                ? WorkspaceAdmission.TerminalReason.PROVIDER_STREAM_ERROR
                : kind == WorkspaceAdmission.TerminalKind.UNKNOWN ? WorkspaceAdmission.TerminalReason.ADMISSION_RECOVERY : null);
    }
}
