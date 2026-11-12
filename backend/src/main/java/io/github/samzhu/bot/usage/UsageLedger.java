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

    boolean markDispatched(String attemptId, java.util.function.Function<java.time.Clock, java.time.Instant> decision);

    void append(String attemptId, Snapshot snapshot);

    void finish(String attemptId, String outcome);

    void notDispatched(String attemptId, String reason);

    void recordResponseId(String attemptId, String responseId);

    PricingInputView readPricingInput(String attemptId);
}
