package io.github.samzhu.bot.execution.internal;

import io.github.samzhu.bot.execution.RunStatus;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class RunHandleSchedulingTests {
    @Test
    void cancellationDisposesSubscriptionBeforeDelayedWorkerStarts() throws Exception {
        var scheduler = Schedulers.newSingle("run-test");
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var drained = new CountDownLatch(1);
        var subscriptions = new AtomicInteger();
        try {
            scheduler.schedule(() -> {
                entered.countDown();
                try { release.await(5, TimeUnit.SECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var callback = new RunHandle.TerminalCallback() {
                public RunHandle.TerminalResult commitTerminal(String runId, String conversationId, RunStatus status,
                        String error, String content, boolean interrupted, String prompt) {
                    return new RunHandle.TerminalResult(true, status, null);
                }
                public RunStatus queryCurrentStatus(String runId) { return RunStatus.CANCELLED; }
            };
            var handle = new RunHandle("run", "conversation", "owner", new SseEmitter(0L),
                    new RunRegistry(), callback);
            handle.sendStarted();
            handle.startSubscription(() -> Flux.defer(() -> {
                subscriptions.incrementAndGet();
                return Flux.just("model result");
            }).subscribeOn(scheduler).subscribe());
            assertEquals(RunStatus.CANCELLED, handle.cancel("owner"));
            release.countDown();
            scheduler.schedule(drained::countDown);
            assertTrue(drained.await(5, TimeUnit.SECONDS));
            assertEquals(0, subscriptions.get());
        } finally {
            release.countDown();
            scheduler.dispose();
        }
    }
    @Test
    void claimedCancellationCannotBeOvertakenByModelCompletionOrFailure() {
        var commits = new java.util.ArrayList<RunStatus>();
        var callback = new RunHandle.TerminalCallback() {
            public RunHandle.TerminalResult commitTerminal(String runId, String conversationId, RunStatus status,
                    String error, String content, boolean interrupted, String prompt) {
                commits.add(status);
                return new RunHandle.TerminalResult(true, status, null);
            }
            public RunStatus queryCurrentStatus(String runId) { return RunStatus.CANCELLED; }
        };
        var handle = new RunHandle("run", "conversation", "owner", new SseEmitter(0L),
                new RunRegistry(), callback);
        handle.sendStarted();
        handle.delta("partial");
        handle.requestCancellation();
        handle.complete("prompt");
        handle.fail("model failed");
        assertEquals(RunStatus.CANCELLED, handle.cancel("owner"));
        assertEquals(java.util.List.of(RunStatus.CANCELLED), commits);
    }
    @Test
    void synchronousScheduleRejectionIsDeferredUntilGateReleased() {
        var gate = new java.util.concurrent.locks.ReentrantLock();
        var deferred = new java.util.concurrent.atomic.AtomicReference<String>();
        var emitted = new AtomicInteger();
        var scheduler = Schedulers.newSingle("rejected-worker");
        scheduler.dispose();
        gate.lock();
        try {
            Flux.just("never").subscribeOn(scheduler).subscribe(value -> fail("unexpected value"), error ->
                RunExecutionServiceImpl.failAfterGate(gate, deferred, ignored -> emitted.incrementAndGet(), "rejected"));
            assertEquals("rejected", deferred.get());
            assertEquals(0, emitted.get());
        } finally { gate.unlock(); }
        if (deferred.get() != null) emitted.incrementAndGet();
        assertEquals(1, emitted.get());
    }

    @Test
    void completionThatClaimsTerminalFirstWinsBeforeDatabaseCommit() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var commits = new java.util.concurrent.CopyOnWriteArrayList<RunStatus>();
        var callback = new RunHandle.TerminalCallback() {
            public RunHandle.TerminalResult commitTerminal(String r, String c, RunStatus status, String e, String content, boolean interrupted, String prompt) {
                entered.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("commit gate timeout"); }
                catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new AssertionError(ex); }
                commits.add(status);
                return new RunHandle.TerminalResult(true, status, null);
            }
            public RunStatus queryCurrentStatus(String runId) { return RunStatus.COMPLETED; }
        };
        var handle = new RunHandle("run", "conversation", "owner", new SseEmitter(0L), new RunRegistry(), callback);
        handle.sendStarted();
        handle.delta("reply");
        var completing = java.util.concurrent.CompletableFuture.runAsync(() -> handle.complete("prompt"));
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            handle.requestCancellation();
        } finally { release.countDown(); }
        completing.get(5, TimeUnit.SECONDS);
        assertEquals(RunStatus.COMPLETED, handle.cancel("owner"));
        assertEquals(java.util.List.of(RunStatus.COMPLETED), commits);
    }

    @Test
    void containerTimeoutCannotOvertakeCancellationClaim() {
        var emitter = org.mockito.Mockito.mock(SseEmitter.class);
        var timeout = new java.util.concurrent.atomic.AtomicReference<Runnable>();
        org.mockito.Mockito.doAnswer(call -> { timeout.set(call.getArgument(0)); return null; })
                .when(emitter).onTimeout(org.mockito.ArgumentMatchers.any());
        var commits = new java.util.ArrayList<RunStatus>();
        var callback = new RunHandle.TerminalCallback() {
            public RunHandle.TerminalResult commitTerminal(String r, String c, RunStatus status, String e, String content, boolean interrupted, String prompt) {
                commits.add(status); return new RunHandle.TerminalResult(true, status, null);
            }
            public RunStatus queryCurrentStatus(String runId) { return RunStatus.CANCELLED; }
        };
        var handle = new RunHandle("run", "conversation", "owner", emitter, new RunRegistry(), callback);
        handle.requestCancellation();
        timeout.get().run();
        assertEquals(RunStatus.CANCELLED, handle.cancel("owner"));
        assertEquals(java.util.List.of(RunStatus.CANCELLED), commits);
    }
    @Test
    void workerFailureWaitsForStarterToReleaseGate() throws Exception {
        var gate = new java.util.concurrent.locks.ReentrantLock();
        var entered = new CountDownLatch(1);
        var emitted = new CountDownLatch(1);
        var deferred = new java.util.concurrent.atomic.AtomicReference<String>();
        gate.lock();
        java.util.concurrent.CompletableFuture<Void> worker;
        try {
            worker = java.util.concurrent.CompletableFuture.runAsync(() -> {
                entered.countDown();
                RunExecutionServiceImpl.failAfterGate(gate, deferred, ignored -> emitted.countDown(), "failed");
            });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertFalse(emitted.await(100, TimeUnit.MILLISECONDS), "failure ran while starter held the gate");
        } finally { gate.unlock(); }
        worker.get(5, TimeUnit.SECONDS);
        assertTrue(emitted.await(5, TimeUnit.SECONDS));
    }

    @Test
    void unavailableDatabaseDuringTerminalCommitDoesNotLeaveAnActiveHandle() {
        var registry = new RunRegistry();
        var emitter = org.mockito.Mockito.mock(SseEmitter.class);
        var callback = new RunHandle.TerminalCallback() {
            public RunHandle.TerminalResult commitTerminal(String r, String c, RunStatus status, String e, String content, boolean interrupted, String prompt) {
                throw new org.springframework.dao.DataAccessResourceFailureException("synthetic DB outage");
            }
            public RunStatus queryCurrentStatus(String runId) { throw new org.springframework.dao.DataAccessResourceFailureException("synthetic DB outage"); }
        };
        var handle = new RunHandle("run", "conversation", "owner", emitter, registry, callback);
        registry.register("run", handle);
        handle.delta("partial");
        assertDoesNotThrow(() -> handle.complete("prompt"));
        assertNull(registry.get("run"));
        org.mockito.Mockito.verify(emitter).completeWithError(org.mockito.ArgumentMatchers.any());
    }
}
