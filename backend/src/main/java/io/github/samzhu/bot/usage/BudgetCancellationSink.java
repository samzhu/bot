package io.github.samzhu.bot.usage;

import java.util.concurrent.CompletionStage;

/** Execution-owned sink for durable usage cancellation effects. */
public interface BudgetCancellationSink {
    sealed interface Target {
        record Pending(WorkspaceAdmission.PendingFence fence) implements Target {}
        record Claimed(WorkspaceAdmission.ClaimFence fence) implements Target {}
    }

    record Effect(String effectId, String token, String workspaceId,
                  WorkspaceAdmission.Partition partition, String runId, Target target, String intentReceipt) {}

    sealed interface Result {
        record Accepted(String effectId, String token, Target target, String receiptId) implements Result {}
        record Stale(String effectId, String token, Target target) implements Result {}
        record Retry(String effectId, String token, Target target) implements Result {}
    }

    sealed interface ReceiptInspection {
        record Matched(Effect effect,String acceptanceReceipt) implements ReceiptInspection {}
        record Mismatch() implements ReceiptInspection {}
        record Unknown() implements ReceiptInspection {}
    }

    CompletionStage<Result> cancel(Effect effect);
    /** Inspect execution-owned durable acceptance; absence or unavailable storage is unknown. */
    CompletionStage<ReceiptInspection> inspectAcceptance(Effect effect,String acceptanceReceipt);
}
