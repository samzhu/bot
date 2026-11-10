package io.github.samzhu.bot.execution.internal;

import io.github.samzhu.bot.execution.RunStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;
import reactor.core.Disposables;

import java.io.IOException;
import java.util.Map;

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

    public record TerminalResult(
        boolean casSuccess,
        RunStatus finalStatus,
        String messageId
    ) {}

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

        this.emitter.onCompletion(() -> disconnect("Emitter onCompletion"));
        this.emitter.onError(err -> disconnect("Emitter onError: " + (err != null ? err.getMessage() : "unknown")));
        this.emitter.onTimeout(this::timeout);
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

    public void sendStarted() {
        synchronized (lock) {
            try {
                Map<String, Object> data = new java.util.HashMap<>();
                data.put("runId", runId);
                data.put("seq", 1);
                if (userMessageId != null) {
                    data.put("userMessageId", userMessageId);
                }
                emitter.send(SseEmitter.event()
                        .name("run.started")
                        .data(data));
            } catch (Exception ex) {
                disconnect("Failed to send initial run.started: " + ex.getMessage());
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
                disconnect("SSE send error: " + ex.getMessage());
            } catch (Exception ex) {
                disconnect("SSE unexpected error: " + ex.getMessage());
            }
        }
    }

    public void complete(String userPrompt) {
        synchronized (lock) {
            if (terminal) return;
            String answer = fullReply.toString();
            if (answer.isBlank()) {
                fail("Empty model response");
                return;
            }

            TerminalResult result = callback.commitTerminal(runId, conversationId, RunStatus.COMPLETED, null, answer, false, userPrompt);
            terminal = true;
            swapDisposable.dispose();
            registry.remove(runId);

            if (result.casSuccess()) {
                int currentSeq = ++seq;
                try {
                    emitter.send(SseEmitter.event()
                            .name("run.completed")
                            .data(Map.of(
                                    "runId", runId,
                                    "seq", currentSeq,
                                    "status", "COMPLETED",
                                    "messageId", result.messageId() != null ? result.messageId() : ""
                            )));
                } catch (Exception ignored) {}
            }
            try {
                emitter.complete();
            } catch (Exception ignored) {}
        }
    }

    public void fail(String errorMessage) {
        synchronized (lock) {
            if (terminal) return;
            String partial = fullReply.toString();
            TerminalResult result = callback.commitTerminal(runId, conversationId, RunStatus.FAILED, errorMessage, partial, true, null);
            terminal = true;
            swapDisposable.dispose();
            registry.remove(runId);

            if (result.casSuccess()) {
                int currentSeq = ++seq;
                try {
                    emitter.send(SseEmitter.event()
                            .name("run.failed")
                            .data(Map.of(
                                    "runId", runId,
                                    "seq", currentSeq,
                                    "status", "FAILED",
                                    "reason", errorMessage != null ? errorMessage : "Unknown error"
                            )));
                } catch (Exception ignored) {}
            }
            try {
                emitter.complete();
            } catch (Exception ignored) {}
        }
    }

    public RunStatus cancel(String requestingUserId) {
        synchronized (lock) {
            if (terminal) {
                return callback.queryCurrentStatus(runId);
            }
            String partial = fullReply.toString();
            TerminalResult result = callback.commitTerminal(runId, conversationId, RunStatus.CANCELLED, "Run cancelled by user", partial, true, null);
            terminal = true;
            swapDisposable.dispose();
            registry.remove(runId);

            if (result.casSuccess()) {
                int currentSeq = ++seq;
                try {
                    Map<String, Object> cancelData = new java.util.HashMap<>();
                    cancelData.put("runId", runId);
                    cancelData.put("seq", currentSeq);
                    cancelData.put("status", "CANCELLED");
                    cancelData.put("reason", "Run cancelled by user");
                    if (result.messageId() != null) {
                        cancelData.put("messageId", result.messageId());
                    }
                    emitter.send(SseEmitter.event()
                            .name("run.cancelled")
                            .data(cancelData));
                } catch (Exception ignored) {}
            }
            try {
                emitter.complete();
            } catch (Exception ignored) {}
            return result.finalStatus();
        }
    }

    public void disconnect(String reason) {
        synchronized (lock) {
            if (terminal) return;
            log.info("Client disconnected for run {}: {}", runId, reason);
            String partial = fullReply.toString();
            callback.commitTerminal(runId, conversationId, RunStatus.CANCELLED, "Client disconnected: " + reason, partial, true, null);
            terminal = true;
            swapDisposable.dispose();
            registry.remove(runId);
            try {
                emitter.complete();
            } catch (Exception ignored) {}
        }
    }

    public void timeout() {
        synchronized (lock) {
            if (terminal) return;
            fail("Execution timeout");
        }
    }

    public String getOwnerId() {
        return ownerId;
    }
}
