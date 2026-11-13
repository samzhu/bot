package io.github.samzhu.bot.execution.internal;

import io.github.samzhu.bot.usage.WorkspaceAdmission;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Full-cycle recovery of usage-verified superseded facts; uncertainty never advances the cursor. */
@Component
class SupersededBudgetCompletionScanner {
    private static final int PAGE_SIZE=100;
    private final WorkspaceAdmission admission;
    private final RunExecutionServiceImpl runs;
    private final AtomicBoolean running=new AtomicBoolean();
    private volatile boolean enabled;
    private String upper;
    private String after;

    SupersededBudgetCompletionScanner(WorkspaceAdmission admission,RunExecutionServiceImpl runs) {
        this.admission=admission; this.runs=runs;
    }
    void enable() { enabled=true; }

    @Scheduled(initialDelayString="${bot.execution.completion-sweep.initial-delay-ms:1000}",
            fixedDelayString="${bot.execution.completion-sweep.delay-ms:1000}")
    public void scan() {
        if (!enabled || !running.compareAndSet(false,true)) return;
        try {
            var result=admission.scanSupersededNotDispatched(after,upper,PAGE_SIZE).toCompletableFuture().get(2,TimeUnit.SECONDS);
            if (!(result instanceof WorkspaceAdmission.SupersededPage.Page page)) return;
            if (upper==null) upper=page.upperToken();
            if (upper==null) { after=null; return; }
            long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(250);
            int completed=0;
            for (var fact:page.facts()) {
                if (System.nanoTime()>=deadline || !runs.completeSupersededNotDispatched(fact)) return;
                after=fact.registration().token(); completed++;
            }
            if (completed==page.facts().size() && completed<PAGE_SIZE) { upper=null; after=null; }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (Exception unknown) {
            // The durable fact remains eligible on the next bounded scan.
        } finally { running.set(false); }
    }
}
