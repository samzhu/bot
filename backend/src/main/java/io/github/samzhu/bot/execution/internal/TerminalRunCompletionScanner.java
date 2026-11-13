package io.github.samzhu.bot.execution.internal;

import io.github.samzhu.bot.usage.WorkspaceAdmission;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Reconciles durable terminal Runs without retaining a Run lock while calling usage. */
@Component
public class TerminalRunCompletionScanner {
    private static final Logger log = LoggerFactory.getLogger(TerminalRunCompletionScanner.class);
    private static final int PAGE_SIZE = 100;
    private static final Duration BATCH_BUDGET = Duration.ofMillis(250);
    private final TerminalRunScanRepository runs;
    private final WorkspaceAdmission admission;
    private final PromptFailureCompletionScanner promptFailures;
    private final SupersededBudgetCompletionScanner supersededBudgets;
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile boolean enabled;
    private String upper;
    private String after = "";
    private boolean unresolved;

    public TerminalRunCompletionScanner(TerminalRunScanRepository runs, WorkspaceAdmission admission, PromptFailureCompletionScanner promptFailures,
                                        SupersededBudgetCompletionScanner supersededBudgets) {
        this.runs = runs;
        this.admission = admission;
        this.promptFailures = promptFailures;
        this.supersededBudgets = supersededBudgets;
    }

    /** Startup coordinator may enable periodic work only after its readiness barrier succeeds. */
    public void enableAfterStartupBarrier() { promptFailures.enable(); supersededBudgets.enable(); enabled = true; }

    /**
     * Run one bounded page. A cycle pins its upper ID and resets after the final page;
     * old Runs that become terminal after their ID was passed are visited next cycle.
     * This method holds no DB transaction, lifecycle stripe, or monitor across usage calls.
     */
    public Batch scanBatch() {
        if (!running.compareAndSet(false, true)) return new Batch(false, true);
        try {
            long deadline = System.nanoTime() + BATCH_BUDGET.toNanos();
            if (upper == null) {
                upper = runs.highestRunId().orElse(null);
                after = "";
                unresolved = false;
                if (upper == null) return new Batch(true, false);
            }
            List<String> ids = runs.terminalRunIds(after, upper, PAGE_SIZE);
            int processed = 0;
            for (String id : ids) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) break;
                try {
                    var result = admission.completeTerminalRun(id).toCompletableFuture()
                            .get(remaining, TimeUnit.NANOSECONDS);
                    if (result == null || result == WorkspaceAdmission.TerminalRunCompletion.RETRY
                            || result == WorkspaceAdmission.TerminalRunCompletion.UNKNOWN) unresolved = true;
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    unresolved = true;
                    break;
                } catch (Exception ex) {
                    // Unknown/timeout remains eligible when the next complete cycle begins.
                    unresolved = true;
                }
                after = id;
                processed++;
            }
            if (processed == ids.size() && ids.size() < PAGE_SIZE) {
                boolean cycleUnresolved = unresolved;
                upper = null;
                after = "";
                unresolved = false;
                return new Batch(true, cycleUnresolved);
            }
            return new Batch(false, unresolved);
        } finally {
            running.set(false);
        }
    }

    @Scheduled(initialDelayString = "${bot.execution.completion-sweep.initial-delay-ms:1000}",
            fixedDelayString = "${bot.execution.completion-sweep.delay-ms:1000}")
    public void scheduledScan() {
        if (!enabled) return;
        try { scanBatch(); }
        catch (RuntimeException ex) { log.warn("Terminal Run completion scan requires retry"); }
    }

    public record Batch(boolean cycleComplete, boolean unresolved) {}
}
