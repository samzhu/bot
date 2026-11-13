package io.github.samzhu.bot.execution.internal;

import io.github.samzhu.bot.usage.WorkspaceAdmission;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

/**
 * Startup candidate, to be registered only with the usage rebuild and final readiness gate.
 * It never holds a Run transaction or monitor while waiting for a usage lane.
 */
@Order(30)
public class TerminalRunStartupCoordinator implements ApplicationRunner {
    private final TerminalRunCompletionScanner scanner;
    private final WorkspaceAdmission admission;
    private final Duration startupBudget;

    public TerminalRunStartupCoordinator(TerminalRunCompletionScanner scanner,
                                         WorkspaceAdmission admission, Duration startupBudget) {
        if (startupBudget == null || startupBudget.isZero() || startupBudget.isNegative()
                || startupBudget.compareTo(Duration.ofMinutes(5)) > 0)
            throw new IllegalArgumentException("Invalid terminal Run startup recovery budget");
        this.scanner = scanner;
        this.admission = admission;
        this.startupBudget = startupBudget;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        long deadline = System.nanoTime() + startupBudget.toNanos();
        while (System.nanoTime() < deadline) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Startup recovery interrupted");
            var batch = scanner.scanBatch();
            if (!batch.cycleComplete()) continue;
            if (!batch.unresolved()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) break;
                var result = admission.verifyAndOpen().toCompletableFuture().get(remaining, TimeUnit.NANOSECONDS);
                if (result == WorkspaceAdmission.RecoveryReadiness.READY) {
                    scanner.enableAfterStartupBarrier();
                    return;
                }
                if (result != WorkspaceAdmission.RecoveryReadiness.RETRY)
                    throw new IllegalStateException("Startup admission verification failed");
            }
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Startup recovery interrupted");
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(50));
        }
        throw new IllegalStateException("Startup admission recovery did not reach READY within its budget");
    }
}
