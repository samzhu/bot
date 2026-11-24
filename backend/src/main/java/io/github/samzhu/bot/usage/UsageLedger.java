package io.github.samzhu.bot.usage;

import java.time.Instant;

/** Public boundary between execution, usage capture and future pricing. */
public interface UsageLedger {
    record Dispatch(
            String runId,
            String workspaceId,
            String actorId,
            String provider,
            String endpoint,
            String model,
            String channel,
            String adapterVersion,
            boolean simulation,
            String attemptCoverage) {}

    record Snapshot(
            String eventId,
            long ordinal,
            String semantic,
            String sourceLayer,
            String schemaVersion,
            String adapterVersion,
            String captureVersion,
            String completeness,
            String rawJson) {}

    record EffectiveSnapshotView(
            String rawJson,
            String rawState,
            String semantic,
            long ordinal,
            String sourceLayer,
            String schemaVersion,
            String adapterVersion,
            String captureVersion,
            String captureCompleteness,
            String captureReason) {}

    record PricingInputView(
            String attemptId,
            String runId,
            String workspaceId,
            String actorId,
            String purpose,
            String provider,
            String endpoint,
            String model,
            String channel,
            Instant preparedAt,
            Instant startedAt,
            boolean simulation,
            String attemptCoverage,
            String executionOutcome,
            String executionReason,
            long projectionVersion,
            String projectionState,
            String projectionReason,
            String usagePresence,
            String effectiveSnapshotId,
            EffectiveSnapshotView effectiveSnapshot) {}

    String begin(Dispatch dispatch);

    String begin(Dispatch dispatch, io.github.samzhu.bot.usage.RunOwnershipGuard.Fence owner);

    boolean markDispatched(String attemptId, java.util.function.Function<java.time.Clock, java.time.Instant> decision);

    boolean markDispatched(String attemptId, java.util.function.Function<java.time.Clock, java.time.Instant> decision, RunOwnershipGuard.Fence owner);

    void append(String attemptId, Snapshot snapshot);

    void append(String attemptId, Snapshot snapshot, io.github.samzhu.bot.usage.RunOwnershipGuard.Fence owner);

    void finish(String attemptId, String outcome);

    void finish(String attemptId, String outcome, RunOwnershipGuard.Fence owner);

    void notDispatched(String attemptId, String reason);

    void notDispatched(String attemptId, String reason, RunOwnershipGuard.Fence owner);

    void recordResponseId(String attemptId, String responseId);

    void recordResponseId(String attemptId, String responseId, io.github.samzhu.bot.usage.RunOwnershipGuard.Fence owner);

    PricingInputView readPricingInput(String attemptId);
}
