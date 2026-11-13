package io.github.samzhu.bot.usage.internal.admission;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** Bounded single-JVM serialization by workspace; callers must never block a lane on external futures. */
@Component
@org.springframework.context.annotation.DependsOn("dataSource")
public final class WorkspaceLane {
    private static final int STRIPES = 32;
    private static final int QUEUE_CAPACITY = 256;
    private static final int CRITICAL_RESERVE = 64;
    private final ThreadPoolExecutor[] lanes = new ThreadPoolExecutor[STRIPES];
    private final Semaphore completionSlots = new Semaphore(260);
    private final Semaphore criticalCompletionSlots = new Semaphore(68);
    private final Semaphore[] admissionQueueSlots = new Semaphore[STRIPES];
    // Each accepted task holds one of at most 328 delivery permits until notification completes.
    // The fallback therefore has enough bounded space even if a primary executor rejects every task.
    private final ThreadPoolExecutor fallbackCompletions = new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(328),r -> {
                var thread=new Thread(r,"workspace-completion-fallback"); thread.setDaemon(true); return thread;
            },new ThreadPoolExecutor.AbortPolicy());
    private final ThreadPoolExecutor criticalCompletions = new ThreadPoolExecutor(4, 4, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(64), r -> {
                var thread = new Thread(r, "workspace-critical-completion");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    private final ThreadPoolExecutor completions = new ThreadPoolExecutor(4, 4, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(256), r -> {
                var thread = new Thread(r, "workspace-admission-completion");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());

    public WorkspaceLane() {
        for (int i = 0; i < lanes.length; i++) {
            int number = i;
            ThreadFactory threads = r -> {
                var thread = new Thread(r, "workspace-admission-" + number);
                thread.setDaemon(true);
                return thread;
            };
            admissionQueueSlots[i] = new Semaphore(QUEUE_CAPACITY);
            lanes[i] = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(QUEUE_CAPACITY + CRITICAL_RESERVE), threads, new ThreadPoolExecutor.AbortPolicy());
        }
    }

    public <T> CompletionStage<T> submit(String workspaceId, Supplier<T> operation) {
        return enqueue(workspaceId,operation,false);
    }

    /** Reserved capacity for work already accepted by admission; finite reserve still requires durable retry. */
    public <T> CompletionStage<T> submitCritical(String workspaceId, Supplier<T> operation) {
        return enqueue(workspaceId,operation,true);
    }

    private <T> CompletionStage<T> enqueue(String workspaceId, Supplier<T> operation, boolean critical) {
        var result = new CompletableFuture<T>();
        var deliverySlots=critical ? criticalCompletionSlots : completionSlots;
        var executor=critical ? criticalCompletions : completions;
        int stripe=Math.floorMod(workspaceId.hashCode(), lanes.length);
        if (!deliverySlots.tryAcquire()) {
            result.completeExceptionally(new RejectedExecutionException("completion capacity exhausted"));
            return result.minimalCompletionStage();
        }
        if (!critical && !admissionQueueSlots[stripe].tryAcquire()) {
            deliverySlots.release();
            result.completeExceptionally(new RejectedExecutionException("workspace queue full"));
            return result.minimalCompletionStage();
        }
        try {
            lanes[stripe].execute(() -> {
                if (!critical) admissionQueueSlots[stripe].release();
                T value = null;
                Throwable failure = null;
                try { value = operation.get(); }
                catch (Throwable caught) { failure = caught; }
                T completedValue = value;
                Throwable completedFailure = failure;
                Runnable delivery=() -> {
                    try {
                        if (completedFailure == null) result.complete(completedValue);
                        else result.completeExceptionally(completedFailure);
                    } finally { deliverySlots.release(); }
                };
                try { executor.execute(delivery); }
                catch (RejectedExecutionException rejected) { fallbackCompletions.execute(delivery); }
            });
        } catch (RejectedExecutionException rejected) {
            if (!critical) admissionQueueSlots[stripe].release();
            deliverySlots.release();
            result.completeExceptionally(rejected);
        }
        return result.minimalCompletionStage();
    }

    @PreDestroy
    void shutdown() {
        for (var lane : lanes) lane.shutdown();
        // Drain accepted lane work while the datasource remains available; never hold a lane lock while waiting.
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        boolean drained=true;
        try {
            for (var lane:lanes) {
                long remaining=deadline-System.nanoTime();
                if (remaining<=0 || !lane.awaitTermination(remaining,TimeUnit.NANOSECONDS)) { drained=false; break; }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); drained=false;
        }
        if (drained) {
            completions.shutdown();
            criticalCompletions.shutdown();
            fallbackCompletions.shutdown();
            // Dependent callbacks can still be using the datasource after the lane operation returns.
            // Share the original deadline so shutdown remains bounded even when a callback stalls.
            try {
                for (var executor : new ThreadPoolExecutor[]{completions, criticalCompletions, fallbackCompletions}) {
                    long remaining=deadline-System.nanoTime();
                    if (remaining<=0 || !executor.awaitTermination(remaining,TimeUnit.NANOSECONDS)) break;
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return;
        }
        // Durable intents remain queryable after the bounded drain; keep delivery alive until late lane jobs end.
        Thread.startVirtualThread(() -> {
            try {
                for (var lane : lanes) {
                    while (!lane.awaitTermination(1, TimeUnit.DAYS)) { /* drain accepted work */ }
                }
                completions.shutdown();
                criticalCompletions.shutdown();
                fallbackCompletions.shutdown();
            } catch (InterruptedException e) {
                // Keep completion delivery alive if shutdown is interrupted while lanes still drain.
                Thread.currentThread().interrupt();
            }
        });
    }
}
