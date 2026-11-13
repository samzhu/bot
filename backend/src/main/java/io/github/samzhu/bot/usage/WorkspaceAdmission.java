package io.github.samzhu.bot.usage;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletionStage;

/** Typed usage boundary for durable registration, publication, per-attempt claims and cancellation. */
public interface WorkspaceAdmission {
    enum Partition { REAL, SIMULATION }
    enum Reason { MONTH_CAP, MONTH_UNKNOWN, UNAUTHORIZED, CANCELLED, OVERLOADED, RECOVERY_REQUIRED }
    enum RegistrationState { REGISTERED, PUBLISHED, CANCEL_REQUESTED, TERMINAL }

    record Register(String workspaceId, String runId, String actorId, String conversationId,
                    Partition partition) {}
    record Registration(String token, String workspaceId, String runId, String actorId,
                        String conversationId, Partition partition) {}

    sealed interface RegisterResult {
        record Registered(Registration registration) implements RegisterResult {}
        record Collision() implements RegisterResult {}
        record Denied(Reason reason) implements RegisterResult {}
    }

    record PendingFence(String token, String pendingId, String workspaceId, Partition partition,
                        Instant periodStart, Instant periodEnd, long epoch) {}
    record ClaimFence(String token, String claimId, String attemptId, String workspaceId,
                      Partition partition, Instant periodStart, Instant periodEnd,
                      long policyGeneration) {}
    record Dispatch(Registration registration, String attemptId) {}

    sealed interface Decision {
        record Allowed(Instant startedAt, ClaimFence claim) implements Decision {}
        record BudgetCancellationPending(BudgetCancellationSink.Effect effect) implements Decision {}
        record Denied(Reason reason) implements Decision {}
    }

    sealed interface PublicationResult {
        record Ready() implements PublicationResult {}
        record CancelPending() implements PublicationResult {}
        record Denied(Reason reason) implements PublicationResult {}
    }

    enum CancelStatus { BEFORE_CLAIM, AFTER_CLAIM, ALREADY_TERMINAL, UNKNOWN }
    record CancelResult(CancelStatus status, List<ClaimFence> claimedTargets,
                        List<PendingFence> pendingTargets) {}
    enum TerminalKind { SUCCEEDED, FAILED, CANCELLED, UNKNOWN, NOT_DISPATCHED }
    enum TerminalReason { CAPTURE_FAILED, ADMISSION_RECOVERY, PROMPT_CONTEXT_UNAVAILABLE, WORKSPACE_ACCESS_DENIED, RUN_TERMINATED_BEFORE_DISPATCH, PROVIDER_STREAM_ERROR, BUDGET_CANCELLATION_SUPERSEDED }
    enum FinalizationResult { SETTLED, RECOVERY_REQUIRED }
    enum TerminalRunCompletion { COMPLETED, ALREADY_COMPLETED, NOT_FOUND, RETRY, UNKNOWN }
    enum RecoveryReadiness { READY, RETRY, FAILED }
    record PromptFailureFact(String attemptId, Registration registration) {}
    sealed interface PromptFailurePage {
        record Page(List<PromptFailureFact> facts, String upperAttemptId) implements PromptFailurePage {}
        record Unknown() implements PromptFailurePage {}
    }
    /** Trusted recovery scan; callers must retry unknown pages without advancing their cursor. */
    CompletionStage<PromptFailurePage> scanSettledPromptFailures(String afterExclusive, String upperInclusive, int limit);
    default CompletionStage<PromptFailurePage> scanSettledPromptFailures(String afterExclusive, String upperInclusive) {
        return scanSettledPromptFailures(afterExclusive, upperInclusive, 100);
    }
    record SupersededNotDispatched(Registration registration, BudgetCancellationSink.Effect oldEffect) {}
    sealed interface SupersededPage {
        record Page(List<SupersededNotDispatched> facts, String upperToken) implements SupersededPage {}
        record Unknown() implements SupersededPage {}
    }
    /** Trusted full-cycle scan of dispositions whose attempts are all durably settled; Unknown never advances the cursor. */
    CompletionStage<SupersededPage> scanSupersededNotDispatched(String afterExclusive, String upperInclusive, int limit);
    record FinalizeAttempt(Registration registration, String attemptId, ClaimFence claim, TerminalKind kind, TerminalReason reason) {
        public FinalizeAttempt(Registration registration, String attemptId, ClaimFence claim, TerminalKind kind) {
            this(registration, attemptId, claim, kind, null);
        }
    }
    enum CancellationState { NONE, REQUESTED, TERMINAL, UNKNOWN }
    record CancellationView(CancellationState state, List<ClaimFence> claimedTargets,
                            List<PendingFence> pendingTargets) {}
    enum EffectStatus { PENDING, ACKNOWLEDGED, STALE, UNKNOWN }
    record EffectView(String effectId, String token, String workspaceId, Partition partition,
                      BudgetCancellationSink.Target target, EffectStatus status,
                      String intentReceipt, String ackReceipt) {}
    sealed interface EffectInspection {
        record Found(EffectView effect) implements EffectInspection {}
        record Absent() implements EffectInspection {}
        record Unknown() implements EffectInspection {}
    }
    sealed interface EffectAcceptance {
        record Prepared(BudgetCancellationSink.Effect effect) implements EffectAcceptance {}
        record Stale() implements EffectAcceptance {}
        record Retry() implements EffectAcceptance {}
    }
    sealed interface ClaimInspection {
        record Found(ClaimFence claim) implements ClaimInspection {}
        record Absent() implements ClaimInspection {}
        record Unknown() implements ClaimInspection {}
    }
    sealed interface ProviderStartResult {
        record Ready() implements ProviderStartResult {}
        record CancelPending(CancelResult cancellation) implements ProviderStartResult {}
        record Denied(Reason reason) implements ProviderStartResult {}
    }

    CompletionStage<RegisterResult> register(Register command);
    CompletionStage<PublicationResult> publish(Registration registration);
    /** Re-reads durable cancellation intent after publication and immediately before provider invocation. */
    CompletionStage<ProviderStartResult> authorizeProviderStart(ClaimFence claim);
    CompletionStage<Decision> dispatch(Dispatch command);
    /** Reconcile a lost dispatch response without issuing another claim. */
    CompletionStage<ClaimInspection> inspectClaim(Registration registration, String attemptId);
    /** Resolve an ambiguous dispatch after the workspace lane has finished its durable decision. */
    CompletionStage<ClaimInspection> recoverClaim(Registration registration, String attemptId);
    /** Settles durable attempt evidence through the same workspace lane; uncertain results remain recoverable. */
    CompletionStage<FinalizationResult> finalizeAttempt(FinalizeAttempt command);
    /** Retry the previously committed terminal intent; never issues a new provider claim. */
    CompletionStage<FinalizationResult> recoverFinalization(Registration registration, String attemptId);
    CompletionStage<CancelResult> cancelRun(Registration registration);
    /** NONE means the durable query found no current intent; it says nothing about future commands. */
    CompletionStage<CancellationView> cancellationState(Registration registration);
    /** Narrow durable lookup used to validate the exact effect delivered to the execution sink. */
    CompletionStage<EffectInspection> inspectCancellationEffect(String effectId);
    /** Atomically validates the exact fence and commits durable cancellation before execution delivery. */
    CompletionStage<EffectAcceptance> prepareCancellationEffect(BudgetCancellationSink.Effect effect);
    CompletionStage<Void> abandon(Registration registration);
    CompletionStage<Void> complete(Registration registration);
    /** Recheck execution's terminal Run and durable attempts before releasing its registration. */
    CompletionStage<TerminalRunCompletion> completeTerminalRun(String runId);
    /** Final startup verification; READY is the only result that permits admission. */
    CompletionStage<RecoveryReadiness> verifyAndOpen();
}
