package io.github.samzhu.bot.usage;

/** Manager-declared estimates and audited recalculations; neither is a provider invoice. */
public interface UsageCostRevisionOperations {
    record ManualCommand(String workspaceId, String attemptId, String expectedRevision,
                         String expectedSourceProjectionVersion, String idempotencyKey,
                         String amount, String sourceType, String evidenceReference, String reason) {}
    record ReconcileCommand(String workspaceId, String attemptId, String expectedRevision,
                            String expectedSourceProjectionVersion, String idempotencyKey,
                            String catalogRevisionId, String evidenceReference, String reason) {}
    record Revision(String id, String attemptId, String kind, String status, String knownSubtotal,
                    String completeAmount, String sourceProjectionVersion, String sourceType,
                    String evidenceReference, String reason, String actorId, String recordedAt,
                    String sourceSnapshotId, String priceSnapshotId, String priceRevision,
                    String catalogRevisionId, String calculatorVersion, java.util.List<String> reasons, String currency) {}
    record Result(String outcome, Revision revision, UsageCostOperations.Cost effectiveCost) {}

    Result manual(ManualCommand command);
    Result reconcile(ReconcileCommand command);
}
