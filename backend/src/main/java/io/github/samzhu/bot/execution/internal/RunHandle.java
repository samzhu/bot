package io.github.samzhu.bot.execution.internal;

import com.agui.community.core.event.Event;
import com.agui.community.core.event.RunErrorEvent;
import com.agui.community.core.event.RunFinishedEvent;
import com.agui.community.core.event.RunStartedEvent;
import com.agui.community.core.event.TextMessageContentEvent;
import com.agui.community.core.event.TextMessageEndEvent;
import com.agui.community.core.event.TextMessageStartEvent;
import com.agui.community.core.message.Role;
import com.agui.community.spring.server.core.JacksonSerializer;
import io.github.samzhu.bot.execution.RunStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;
import reactor.core.Disposables;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;

public class RunHandle {

    private static final Logger log = LoggerFactory.getLogger(RunHandle.class);
    private static final JacksonSerializer AGUI_SERIALIZER = new JacksonSerializer();

    // Why single lock monitor: guarantees that delta appending, sequence increment,
    // event emission, and terminal CAS transition happen atomically without reordering or concurrency races.
    private final Object lock = new Object();
    private final String runId;
    private final String conversationId;
    private final String ownerId;
    private final SseEmitter emitter;
    private final RunRegistry registry;
    private final String userMessageId;
    private final String assistantId;
    private boolean textStarted;
    private final TerminalCallback callback;
    // Why Disposables.swap(): prevents race conditions where a stream reaches terminal
    // (or is cancelled) before the reactive subscription is attached; disposing swapDisposable
    // ensures any subsequent late subscription attached via setSubscription is immediately disposed.
    private final Disposable.Swap swapDisposable = Disposables.swap();
    private final StringBuilder fullReply = new StringBuilder();

    private volatile io.github.samzhu.bot.usage.WorkspaceAdmission.Registration admissionRegistration;
    private volatile boolean admissionCancelledBeforeClaim;
    private final java.util.concurrent.atomic.AtomicReference<io.github.samzhu.bot.usage.WorkspaceAdmission.ClaimFence> admissionClaim = new java.util.concurrent.atomic.AtomicReference<>();
    public void setAdmissionRegistration(io.github.samzhu.bot.usage.WorkspaceAdmission.Registration registration) {
        this.admissionRegistration = registration;
    }
    public io.github.samzhu.bot.usage.WorkspaceAdmission.Registration admissionRegistration() {
        return admissionRegistration;
    }
    public void markAdmissionCancelledBeforeClaim() { admissionCancelledBeforeClaim = true; }
    public boolean admissionCancelledBeforeClaim() { return admissionCancelledBeforeClaim; }
    public void setAdmissionClaim(io.github.samzhu.bot.usage.WorkspaceAdmission.ClaimFence claim) {
        synchronized (dispatchLock) { admissionClaim.set(claim); }
    }
    public io.github.samzhu.bot.usage.WorkspaceAdmission.ClaimFence admissionClaim() {
        return admissionClaim.get();
    }
    public boolean requestCancellationForClaim(io.github.samzhu.bot.usage.WorkspaceAdmission.ClaimFence claim) {
        synchronized (dispatchLock) {
            if (!java.util.Objects.equals(admissionClaim.get(), claim)) return false;
            requestCancellation();
            return true;
        }
    }

    private boolean terminal = false;
    private boolean promptFailureAwaitingSettlement;
    private boolean budgetCancellationPending;

    /** Protect a verified durable budget cancellation from generic failure and timeout paths. */
    public boolean protectBudgetCancellation(io.github.samzhu.bot.usage.BudgetCancellationSink.Effect effect) {
        synchronized (lock) {
            var registration=admissionRegistration;
            if (registration==null || effect==null || effect.effectId()==null || effect.effectId().isBlank()
                    || effect.intentReceipt()==null || effect.intentReceipt().isBlank()
                    || !(effect.target() instanceof io.github.samzhu.bot.usage.BudgetCancellationSink.Target.Pending pending)) return false;
            var fence=pending.fence();
            if (fence==null || fence.pendingId()==null || fence.pendingId().isBlank() || fence.epoch()<0
                    || fence.periodStart()==null || fence.periodEnd()==null || !fence.periodStart().isBefore(fence.periodEnd())
                    || !java.util.Objects.equals(registration.token(),effect.token())
                    || !java.util.Objects.equals(registration.workspaceId(),effect.workspaceId())
                    || registration.partition()!=effect.partition() || !java.util.Objects.equals(registration.runId(),effect.runId())
                    || !java.util.Objects.equals(fence.token(),effect.token())
                    || !java.util.Objects.equals(fence.workspaceId(),effect.workspaceId()) || fence.partition()!=effect.partition()) return false;
            budgetCancellationPending=true;
            return true;
        }
    }

    public void markPromptFailureAwaitingSettlement() {
        synchronized (lock) { promptFailureAwaitingSettlement = true; }
    }
    private final java.util.concurrent.CompletableFuture<Void> durableTerminal = new java.util.concurrent.CompletableFuture<>();
    public java.util.concurrent.CompletionStage<Void> durableTerminal() { return durableTerminal.minimalCompletionStage(); }
    private enum DispatchClaim { PENDING, PREPARING, AUTHORIZED, DISPATCHED, CANCELLED }
    private final Object dispatchLock = new Object();
    private final java.util.List<DispatchGate> dispatchGates = new java.util.ArrayList<>();
    private boolean dispatchStopped;

    public final class DispatchGate {
        private DispatchClaim state = DispatchClaim.PENDING;
        public boolean prepare() {
            synchronized (dispatchLock) {
                if (dispatchStopped || state != DispatchClaim.PENDING) return false;
                state = DispatchClaim.PREPARING;
                return true;
            }
        }
        public java.time.Instant authorize(java.time.Clock clock) {
            synchronized (dispatchLock) {
                if (dispatchStopped || state != DispatchClaim.PREPARING) return null;
                // The dispatch decision and its clock sample share one short in-memory boundary.
                java.time.Instant time = clock.instant();
                state = DispatchClaim.AUTHORIZED;
                return time;
            }
        }
        public boolean confirm() {
            synchronized (dispatchLock) {
                if (dispatchStopped || state != DispatchClaim.AUTHORIZED) return false;
                state = DispatchClaim.DISPATCHED;
                return true;
            }
        }
        public boolean dispatched() {
            synchronized (dispatchLock) { return state == DispatchClaim.DISPATCHED; }
        }
    }
    public DispatchGate newDispatchGate() {
        synchronized (dispatchLock) {
            DispatchGate gate = new DispatchGate();
            if (dispatchStopped) gate.state = DispatchClaim.CANCELLED;
            dispatchGates.add(gate);
            return gate;
        }
    }
    private enum TerminalClaim { ACTIVE, CANCEL_REQUESTED, FINALIZING }
    private final java.util.concurrent.atomic.AtomicReference<TerminalClaim> terminalClaim =
            new java.util.concurrent.atomic.AtomicReference<>(TerminalClaim.ACTIVE);
    private int seq = 1; // run.started is seq 1, deltas start at 2

    private ScheduledFuture<?> deadlineFuture;
    private ScheduledFuture<?> heartbeatFuture;

    public record TerminalResult(
        boolean casSuccess,
        RunStatus finalStatus,
        String messageId,
        String errorMessage
    ) {
        public TerminalResult(boolean casSuccess, RunStatus finalStatus, String messageId) {
            this(casSuccess, finalStatus, messageId, null);
        }
    }

    public interface TerminalCallback {
        TerminalResult commitTerminal(String runId, String conversationId, RunStatus targetStatus, String errorMessage, String assistantContent, boolean interrupted, String userPrompt);
        RunStatus queryCurrentStatus(String runId);
        default TerminalResult commitBudgetCancellation(String runId, String conversationId, String partial,
                io.github.samzhu.bot.usage.BudgetCancellationSink.Effect effect) {
            throw new UnsupportedOperationException("Budget cancellation is unavailable");
        }
    }

    public RunHandle(
            String runId,
            String conversationId,
            String ownerId,
            SseEmitter emitter,
            RunRegistry registry,
            String userMessageId,
            String assistantId,
            TerminalCallback callback) {
        this.runId = runId;
        this.conversationId = conversationId;
        this.ownerId = ownerId;
        this.emitter = emitter;
        this.registry = registry;
        this.userMessageId = userMessageId;
        this.assistantId = assistantId;
        this.callback = callback;

        this.emitter.onCompletion(() -> {
            synchronized (lock) {
                // If the run reached a clean terminal state (COMPLETED, FAILED, CANCELLED),
                // onCompletion is the normal servlet request end and must NOT overwrite the final status.
                if (terminal) return;
                handleWriteFailure("Emitter onCompletion before terminal");
            }
        });
        this.emitter.onError(err -> {
            synchronized (lock) {
                if (terminal) return;
                handleWriteFailure("Emitter onError: " + (err != null ? err.getMessage() : "unknown"));
            }
        });
        this.emitter.onTimeout(() -> {
            synchronized (lock) {
                if (promptFailureAwaitingSettlement || budgetCancellationPending) return;
                stopUndispatched();
                if (terminal || !terminalClaim.compareAndSet(TerminalClaim.ACTIVE, TerminalClaim.FINALIZING)) return;
                /*
                 * RATIONALE: Servlet container timeout callback is the container's error notification/cleanup mechanism.
                 * Transitioning to FAILED with timeout error reason reflects true outcome rather than CANCELLED,
                 * without calling emitter.complete() as the container has already initiated error processing.
                 */
                terminal = true;
                clearScheduledTasks();
                swapDisposable.dispose();
                registry.remove(runId);
                String partial = fullReply.toString();
                TerminalResult result = callback.commitTerminal(runId, conversationId, RunStatus.FAILED, "Execution timeout", partial, true, null);
                if (result != null && result.finalStatus() != null) durableTerminal.complete(null);
            }
        });
    }

    public RunHandle(
            String runId,
            String conversationId,
            String ownerId,
            SseEmitter emitter,
            RunRegistry registry,
            TerminalCallback callback) {
        this(runId, conversationId, ownerId, emitter, registry, null, null, callback);
    }

    public void startScheduledTasks(TaskScheduler scheduler, Duration deadline, Duration heartbeatInterval) {
        synchronized (lock) {
            if (terminal || scheduler == null) return;
            if (deadline != null && !deadline.isZero() && !deadline.isNegative()) {
                this.deadlineFuture = scheduler.schedule(this::appTimeout, Instant.now().plus(deadline));
            }
            if (heartbeatInterval != null && !heartbeatInterval.isZero() && !heartbeatInterval.isNegative()) {
                this.heartbeatFuture = scheduler.scheduleWithFixedDelay(
                        this::sendHeartbeat,
                        Instant.now().plus(heartbeatInterval),
                        heartbeatInterval
                );
            }
        }
    }

    private void clearScheduledTasks() {
        if (deadlineFuture != null) {
            deadlineFuture.cancel(false);
            deadlineFuture = null;
        }
        if (heartbeatFuture != null) {
            heartbeatFuture.cancel(false);
            heartbeatFuture = null;
        }
    }

    public void sendStarted() {
        synchronized (lock) {
            try {
                if (assistantId != null) {
                    sendAgUi(new RunStartedEvent(conversationId, runId));
                    return;
                }
                Map<String, Object> data = new HashMap<>();
                data.put("runId", runId);
                data.put("seq", 1);
                if (userMessageId != null) {
                    data.put("userMessageId", userMessageId);
                }
                emitter.send(SseEmitter.event()
                        .name("run.started")
                        .data(data));
            } catch (Exception ex) {
                handleWriteFailure("Failed to send initial run.started: " + ex.getMessage());
            }
        }
    }

    // The CAS orders cancellation against terminal callbacks without waiting for an
    // emitter write while the caller holds the shared lifecycle stripe.
    public void requestCancellation() {
        stopUndispatched();
        terminalClaim.compareAndSet(TerminalClaim.ACTIVE, TerminalClaim.CANCEL_REQUESTED);
    }

    public boolean cancellationRequested() {
        return terminalClaim.get() == TerminalClaim.CANCEL_REQUESTED;
    }

    // Claim and cancellation hold this lock only for in-memory state; no I/O occurs here.
    private void stopUndispatched() {
        synchronized (dispatchLock) {
            dispatchStopped = true;
            for (DispatchGate gate : dispatchGates) {
                if (gate.state == DispatchClaim.PENDING || gate.state == DispatchClaim.PREPARING || gate.state == DispatchClaim.AUTHORIZED) gate.state = DispatchClaim.CANCELLED;
            }
        }
    }

    // The caller also holds the lifecycle stripe, ordering this check with cancellation.
    public boolean startSubscription(java.util.function.Supplier<Disposable> subscribe) {
        synchronized (lock) {
            if (terminal || terminalClaim.get() != TerminalClaim.ACTIVE) return false;
            swapDisposable.update(subscribe.get());
            return true;
        }
    }

    public void setSubscription(Disposable d) {
        if (d == null) return;
        synchronized (lock) {
            if (terminal) {
                d.dispose();
                return;
            }
            swapDisposable.update(d);
        }
    }

    public void delta(String chunk) {
        if (chunk == null || chunk.isEmpty()) return;
        synchronized (lock) {
            if (terminal || terminalClaim.get() != TerminalClaim.ACTIVE) return;
            fullReply.append(chunk);
            int currentSeq = ++seq;
            try {
                if (assistantId != null) {
                    if (!textStarted) {
                        sendAgUi(new TextMessageStartEvent(assistantId, Role.ASSISTANT));
                        textStarted = true;
                    }
                    sendAgUi(new TextMessageContentEvent(assistantId, chunk));
                    return;
                }
                emitter.send(SseEmitter.event()
                        .name("message.delta")
                        .data(Map.of("runId", runId, "seq", currentSeq, "delta", chunk)));
            } catch (IOException | IllegalStateException ex) {
                handleWriteFailure("SSE delta send error: " + ex.getMessage());
            } catch (Exception ex) {
                handleWriteFailure("SSE delta unexpected error: " + ex.getMessage());
            }
        }
    }

    public void sendHeartbeat() {
        synchronized (lock) {
            if (terminal) return;
            try {
                /*
                 * RATIONALE: SSE Comment Heartbeat frame (: heartbeat\n\n).
                 * Standard SSE ping that tests socket connectivity without incrementing
                 * business sequence or triggering frontend onmessage handlers.
                 */
                emitter.send(SseEmitter.event().comment("heartbeat"));
            } catch (IOException | IllegalStateException ex) {
                handleWriteFailure("Heartbeat write failure: " + ex.getMessage());
            } catch (Exception ex) {
                handleWriteFailure("Heartbeat unexpected error: " + ex.getMessage());
            }
        }
    }

    public void complete(String userPrompt) {
        synchronized (lock) {
            if (terminal || terminalClaim.get() != TerminalClaim.ACTIVE) return;
            String answer = fullReply.toString();
            if (answer.isBlank()) {
                fail("Empty model response");
                return;
            }
            // The CAS, not the later DB commit, decides whether completion or cancellation won.
            if (!terminalClaim.compareAndSet(TerminalClaim.ACTIVE, TerminalClaim.FINALIZING)) return;
            clearScheduledTasks();
            TerminalResult result = commitOrAbandon(RunStatus.COMPLETED, null, answer, false, userPrompt);
            if (result == null) return;
            terminal = true;
            swapDisposable.dispose();
            registry.remove(runId);

            dispatchTerminalEvent(result);
        }
    }

    public void fail(String errorMessage) {
        stopUndispatched();
        synchronized (lock) {
            if (budgetCancellationPending) return;
            if (terminal || !terminalClaim.compareAndSet(TerminalClaim.ACTIVE, TerminalClaim.FINALIZING)) return;
            clearScheduledTasks();
            String partial = fullReply.toString();
            TerminalResult result = commitOrAbandon(RunStatus.FAILED, errorMessage, partial, true, null);
            if (result == null) return;
            terminal = true;
            swapDisposable.dispose();
            registry.remove(runId);

            dispatchTerminalEvent(result);
        }
    }

    /** Durable recovery claims locally, performs bounded DB work without the monitor, then publishes the result. */
    public boolean failSettledRecovery(java.util.function.Supplier<TerminalResult> commit) {
        synchronized (lock) {
            if (terminal) return true;
            if (!terminalClaim.compareAndSet(TerminalClaim.ACTIVE,TerminalClaim.FINALIZING)) return false;
        }
        TerminalResult result;
        try { result=commit.get(); }
        catch (RuntimeException unknown) { result=null; }
        synchronized (lock) {
            if (terminal) return true;
            if (result==null || result.finalStatus()==null) {
                terminalClaim.compareAndSet(TerminalClaim.FINALIZING,TerminalClaim.ACTIVE);
                return false;
            }
            clearScheduledTasks();
            terminal=true;
            swapDisposable.dispose();
            registry.remove(runId);
            dispatchTerminalEvent(result);
            return true;
        }
    }

    /** A verified terminal Run only needs local cleanup; it must not issue another terminal write. */
    public void reconcileObservedTerminal(RunStatus status) {
        if (status!=RunStatus.COMPLETED && status!=RunStatus.FAILED && status!=RunStatus.CANCELLED)
            throw new IllegalArgumentException("Run is not terminal");
        synchronized (lock) {
            if (terminal) {
                registry.remove(runId,this);
                durableTerminal.complete(null);
                return;
            }
            terminal=true;
            clearScheduledTasks();
            registry.remove(runId,this);
            swapDisposable.dispose();
            durableTerminal.complete(null);
            // The committing path may already have sent its event. Closing avoids a duplicate.
            try { emitter.complete(); } catch (RuntimeException ignored) { }
        }
    }

    public RunStatus cancel(String requestingUserId) {
        synchronized (lock) {
            if (terminal) {
                return callback.queryCurrentStatus(runId);
            }
            requestCancellation();
            clearScheduledTasks();
            String partial = fullReply.toString();
            TerminalResult result = commitOrAbandon(RunStatus.CANCELLED, "Run cancelled by user", partial, true, null);
            if (result == null) throw new IllegalStateException("Unable to determine run terminal status");
            terminal = true;
            swapDisposable.dispose();
            registry.remove(runId);

            dispatchTerminalEvent(result);
            return result.finalStatus();
        }
    }

    public TerminalResult cancelForBudget(io.github.samzhu.bot.usage.BudgetCancellationSink.Effect effect) {
        synchronized (lock) {
            if (terminal) return new TerminalResult(false, callback.queryCurrentStatus(runId), null);
            requestCancellation();
            clearScheduledTasks();
            TerminalResult result = callback.commitBudgetCancellation(runId, conversationId, fullReply.toString(), effect);
            if (result == null) throw new IllegalStateException("Budget cancellation outcome unknown");
            terminal = true;
            swapDisposable.dispose();
            registry.remove(runId);
            dispatchTerminalEvent(result);
            return result;
        }
    }

    // If terminal writes and the fallback lookup fail, the DB status is unknown.
    // A still-RUNNING row blocks new runs until explicit cancellation after recovery
    // or startup orphan reconciliation; a transport error must not claim DB failure.
    private TerminalResult commitOrAbandon(RunStatus status, String error, String content,
                                           boolean interrupted, String userPrompt) {
        try {
            return callback.commitTerminal(runId, conversationId, status, error, content, interrupted, userPrompt);
        } catch (RuntimeException ex) {
            terminal = true;
            clearScheduledTasks();
            swapDisposable.dispose();
            registry.remove(runId);
            try { emitter.completeWithError(ex); } catch (RuntimeException ignored) { }
            return null;
        }
    }

    private void dispatchTerminalEvent(TerminalResult result) {
        if (result.finalStatus() != null) durableTerminal.complete(null);
        if (!result.casSuccess()) {
            /*
             * RATIONALE: CAS loser does not forge duplicate terminal SSE events (the winning committer owns terminal emission).
             * However, if the emitter is still open, we close the live emitter cleanly so the HTTP connection reaches EOF
             * rather than hanging indefinitely with clearScheduledTasks() already called.
             */
            try {
                emitter.complete();
            } catch (Exception ignored) {}
            return;
        }

        if (assistantId != null) {
            dispatchAgUiTerminal(result);
            return;
        }
        int currentSeq = ++seq;
        RunStatus status = result.finalStatus();

        if (status == RunStatus.COMPLETED) {
            sendTerminalEventAndClose("run.completed", Map.of(
                    "runId", runId,
                    "seq", currentSeq,
                    "status", "COMPLETED",
                    "messageId", result.messageId() != null ? result.messageId() : ""
            ));
        } else if (status == RunStatus.CANCELLED) {
            Map<String, Object> cancelData = new HashMap<>();
            cancelData.put("runId", runId);
            cancelData.put("seq", currentSeq);
            cancelData.put("status", "CANCELLED");
            cancelData.put("reason", result.errorMessage() != null ? result.errorMessage() : "Run cancelled by user");
            if (result.messageId() != null) {
                cancelData.put("messageId", result.messageId());
            }
            sendTerminalEventAndClose("run.cancelled", cancelData);
        } else {
            // FAILED (including fallback from complete or cancel)
            Map<String, Object> failData = new HashMap<>();
            failData.put("runId", runId);
            failData.put("seq", currentSeq);
            failData.put("status", "FAILED");
            failData.put("reason", result.errorMessage() != null ? result.errorMessage() : "Execution failed");
            if (result.messageId() != null) {
                failData.put("messageId", result.messageId());
            }
            sendTerminalEventAndClose("run.failed", failData);
        }
    }

    private void sendAgUi(Event event) throws IOException {
        emitter.send(SseEmitter.event().data(AGUI_SERIALIZER.serialize(event)));
    }

    private void dispatchAgUiTerminal(TerminalResult result) {
        try {
            if (textStarted) {
                sendAgUi(new TextMessageEndEvent(assistantId));
            }
            if (result.finalStatus() == RunStatus.COMPLETED) {
                sendAgUi(new RunFinishedEvent(conversationId, runId));
            } else if (result.finalStatus() == RunStatus.CANCELLED) {
                // Java core 0.1.1 cannot represent the standard 1.0.4 cancelled outcome.
                // Serialize its wire shape until the published Java sealed union supports it.
                Map<String, Object> cancelled = Map.of(
                        "type", "RUN_FINISHED",
                        "threadId", conversationId,
                        "runId", runId,
                        "outcome", Map.of("type", "cancelled"));
                emitter.send(SseEmitter.event().data(AGUI_SERIALIZER.serialize(cancelled)));
            } else {
                sendAgUi(new RunErrorEvent("Execution failed"));
            }
            emitter.complete();
        } catch (Exception ex) {
            log.info("AG-UI terminal delivery failed for run {}: {}", runId, ex.getMessage());
        }
    }

    private void sendTerminalEventAndClose(String eventName, Map<String, Object> data) {
        try {
            emitter.send(SseEmitter.event().name(eventName).data(data));
            // Only complete if send succeeded without socket exception
            try {
                emitter.complete();
            } catch (Exception ignored) {}
        } catch (IOException | IllegalStateException ex) {
            log.info("Terminal event send failed on closed socket for run {}: {}", runId, ex.getMessage());
            /*
             * RATIONALE: Spring Framework 7 / Servlet container cleanup contract.
             * When an IOException occurs while writing to the socket, the servlet container (Tomcat)
             * manages channel cleanup and aborts the async request context.
             * The application must not attempt further emitter writes or complete invocations.
             */
        } catch (Exception ex) {
            log.warn("Terminal event unexpected error for run {}: {}", runId, ex.getMessage());
        }
    }

    public void appTimeout() {
        synchronized (lock) {
            if (terminal) return;
            // The durable prompt-failure intent owns the outcome; timeout must not overtake settlement.
            if (promptFailureAwaitingSettlement || budgetCancellationPending) return;
            log.warn("Application deadline exceeded for run {}", runId);
            fail("Execution timeout");
        }
    }

    private void handleWriteFailure(String reason) {
        if (budgetCancellationPending) return;
        stopUndispatched();
        if (terminal || !terminalClaim.compareAndSet(TerminalClaim.ACTIVE, TerminalClaim.FINALIZING)) return;
        terminal = true;
        clearScheduledTasks();
        registry.remove(runId);
        log.info("Client write failed or disconnected for run {}: {}", runId, reason);
        String partial = fullReply.toString();
        try {
            TerminalResult result = callback.commitTerminal(runId, conversationId, RunStatus.CANCELLED, reason, partial, true, null);
            if (result != null && result.finalStatus() != null) durableTerminal.complete(null);
        } finally {
            swapDisposable.dispose();
        }
        /*
         * RATIONALE: Spring Framework 7 / Servlet container cleanup contract.
         * When an IOException occurs while writing to the HTTP socket, the servlet container (Tomcat)
         * takes ownership of channel cleanup and closes the underlying transport.
         * The application relies on container error notification rather than manually calling emitter.complete().
         */
    }

    public void disconnect(String reason) {
        synchronized (lock) {
            if (terminal) return;
            handleWriteFailure("Client disconnected: " + reason);
        }
    }

    public String getRunId() {
        return runId;
    }

    public String getConversationId() {
        return conversationId;
    }

    public String getOwnerId() {
        return ownerId;
    }
}
