package io.github.samzhu.bot.execution.internal;

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

    // Why single lock monitor: guarantees that delta appending, sequence increment,
    // event emission, and terminal CAS transition happen atomically without reordering or concurrency races.
    private final Object lock = new Object();
    private final String runId;
    private final String conversationId;
    private final String ownerId;
    private final SseEmitter emitter;
    private final RunRegistry registry;
    private final String userMessageId;
    private final TerminalCallback callback;
    // Why Disposables.swap(): prevents race conditions where a stream reaches terminal
    // (or is cancelled) before the reactive subscription is attached; disposing swapDisposable
    // ensures any subsequent late subscription attached via setSubscription is immediately disposed.
    private final Disposable.Swap swapDisposable = Disposables.swap();
    private final StringBuilder fullReply = new StringBuilder();

    private boolean terminal = false;
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
    }

    public RunHandle(
            String runId,
            String conversationId,
            String ownerId,
            SseEmitter emitter,
            RunRegistry registry,
            String userMessageId,
            TerminalCallback callback) {
        this.runId = runId;
        this.conversationId = conversationId;
        this.ownerId = ownerId;
        this.emitter = emitter;
        this.registry = registry;
        this.userMessageId = userMessageId;
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
                if (terminal) return;
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
                callback.commitTerminal(runId, conversationId, RunStatus.FAILED, "Execution timeout", partial, true, null);
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
        this(runId, conversationId, ownerId, emitter, registry, null, callback);
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
            if (terminal) return;
            fullReply.append(chunk);
            int currentSeq = ++seq;
            try {
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
            if (terminal) return;
            clearScheduledTasks();
            String answer = fullReply.toString();
            if (answer.isBlank()) {
                fail("Empty model response");
                return;
            }

            TerminalResult result = callback.commitTerminal(runId, conversationId, RunStatus.COMPLETED, null, answer, false, userPrompt);
            terminal = true;
            swapDisposable.dispose();
            registry.remove(runId);

            dispatchTerminalEvent(result);
        }
    }

    public void fail(String errorMessage) {
        synchronized (lock) {
            if (terminal) return;
            clearScheduledTasks();
            String partial = fullReply.toString();
            TerminalResult result = callback.commitTerminal(runId, conversationId, RunStatus.FAILED, errorMessage, partial, true, null);
            terminal = true;
            swapDisposable.dispose();
            registry.remove(runId);

            dispatchTerminalEvent(result);
        }
    }

    public RunStatus cancel(String requestingUserId) {
        synchronized (lock) {
            if (terminal) {
                return callback.queryCurrentStatus(runId);
            }
            clearScheduledTasks();
            String partial = fullReply.toString();
            TerminalResult result = callback.commitTerminal(runId, conversationId, RunStatus.CANCELLED, "Run cancelled by user", partial, true, null);
            terminal = true;
            swapDisposable.dispose();
            registry.remove(runId);

            dispatchTerminalEvent(result);
            return result.finalStatus();
        }
    }

    private void dispatchTerminalEvent(TerminalResult result) {
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
            log.warn("Application deadline exceeded for run {}", runId);
            fail("Execution timeout");
        }
    }

    private void handleWriteFailure(String reason) {
        if (terminal) return;
        terminal = true;
        clearScheduledTasks();
        registry.remove(runId);
        log.info("Client write failed or disconnected for run {}: {}", runId, reason);
        String partial = fullReply.toString();
        callback.commitTerminal(runId, conversationId, RunStatus.CANCELLED, reason, partial, true, null);
        swapDisposable.dispose();
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
