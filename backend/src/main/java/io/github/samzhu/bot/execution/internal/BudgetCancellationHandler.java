package io.github.samzhu.bot.execution.internal;

import io.github.samzhu.bot.execution.RunStatus;
import io.github.samzhu.bot.usage.BudgetCancellationSink;
import io.github.samzhu.bot.usage.WorkspaceAdmission;
import org.springframework.stereotype.Component;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Delivers only exact, durably recorded budget cancellation intents. */
@Component
public class BudgetCancellationHandler implements BudgetCancellationSink {
    private final WorkspaceAdmission admission;
    private final RunRegistry registry;
    private final BudgetCancellationReceipts receipts;
    private final reactor.core.scheduler.Scheduler deliveryScheduler;

    public BudgetCancellationHandler(WorkspaceAdmission admission, RunRegistry registry,
                                     BudgetCancellationReceipts receipts,
                                     @org.springframework.beans.factory.annotation.Qualifier("usageFinalizationScheduler")
                                     reactor.core.scheduler.Scheduler deliveryScheduler) {
        this.admission=admission; this.registry=registry; this.receipts=receipts;
        this.deliveryScheduler=deliveryScheduler;
    }

    @Override
    public CompletionStage<ReceiptInspection> inspectAcceptance(Effect effect, String acceptanceReceipt) {
        if (effect == null || acceptanceReceipt == null || acceptanceReceipt.isBlank())
            return CompletableFuture.completedFuture(new ReceiptInspection.Unknown());
        return reactor.core.publisher.Mono.<ReceiptInspection>fromCallable(() -> {
            try {
                String stored=receipts.findMatching(effect);
                if (stored == null) return new ReceiptInspection.Unknown();
                if (!Objects.equals(stored,acceptanceReceipt)) return new ReceiptInspection.Mismatch();
                return new ReceiptInspection.Matched(effect,stored);
            } catch (BudgetCancellationReceipts.IdentityMismatch ex) {
                return new ReceiptInspection.Mismatch();
            } catch (RuntimeException ex) {
                return new ReceiptInspection.Unknown();
            }
        }).subscribeOn(deliveryScheduler).onErrorReturn(new ReceiptInspection.Unknown()).toFuture();
    }

    @Override
    public CompletionStage<Result> cancel(Effect effect) {
        if (effect == null || effect.effectId() == null || effect.token() == null
                || effect.target() == null || effect.intentReceipt() == null || effect.partition() == null)
            return CompletableFuture.completedFuture(retry(effect));
        try {
            String receipt=receipts.findMatching(effect);
            if (receipt != null) return CompletableFuture.completedFuture(accepted(effect,receipt));
        } catch (RuntimeException ex) { return CompletableFuture.completedFuture(retry(effect)); }
        return admission.inspectCancellationEffect(effect.effectId()).thenCompose(inspection -> {
            if (!(inspection instanceof WorkspaceAdmission.EffectInspection.Found found))
                return CompletableFuture.completedFuture(retry(effect));
            var stored=found.effect();
            if (!Objects.equals(stored.effectId(),effect.effectId()) || !Objects.equals(stored.token(),effect.token())
                    || !Objects.equals(stored.workspaceId(),effect.workspaceId()) || stored.partition()!=effect.partition()
                    || !Objects.equals(stored.target(),effect.target()) || !Objects.equals(stored.intentReceipt(),effect.intentReceipt()))
                return CompletableFuture.completedFuture(retry(effect));
            return admission.prepareCancellationEffect(effect).thenApplyAsync(prepared -> {
                if (prepared instanceof WorkspaceAdmission.EffectAcceptance.Stale)
                    return new Result.Stale(effect.effectId(),effect.token(),effect.target());
                if (!(prepared instanceof WorkspaceAdmission.EffectAcceptance.Prepared p) || !effect.equals(p.effect())) return retry(effect);
                return applyPrepared(effect);
            }, task -> deliveryScheduler.schedule(task));
        }).exceptionally(error -> retry(effect));
    }

    private Result applyPrepared(Effect effect) {
        try {
            var gate=registry.lifecycleGate(effect.runId());
            RunHandle handle;
            gate.lock();
            try {
                handle=registry.get(effect.runId());
                if (handle != null) {
                var registration=handle.admissionRegistration();
                if (registration==null || !Objects.equals(registration.token(),effect.token())
                        || !Objects.equals(registration.workspaceId(),effect.workspaceId())
                        || registration.partition()!=effect.partition()) return retry(effect);
                if (effect.target() instanceof Target.Claimed claimed) {
                    if (!handle.requestCancellationForClaim(claimed.fence())) return retry(effect);
                } else if (handle.admissionClaim() == null) {
                    handle.requestCancellation();
                } else return retry(effect);
                }
            } finally { gate.unlock(); }
            // A durable run row does not prove publication completed. Keep the
            // intent until publication or recovery supplies authoritative evidence.
            if (handle == null) return retry(effect);
            var result=handle.cancelForBudget(effect);
            if (!result.casSuccess()) return result.finalStatus()==RunStatus.COMPLETED
                    ? new Result.Stale(effect.effectId(),effect.token(),effect.target()) : retry(effect);
            String receipt=receipts.findMatching(effect);
            return receipt==null ? retry(effect) : accepted(effect,receipt);
        } catch (RuntimeException ex) { return retry(effect); }
    }

    private static Result.Accepted accepted(Effect e,String receipt) {
        return new Result.Accepted(e.effectId(),e.token(),e.target(),receipt);
    }
    private static Result.Retry retry(Effect e) {
        return e==null ? new Result.Retry(null,null,null) : new Result.Retry(e.effectId(),e.token(),e.target());
    }
}
