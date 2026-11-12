package io.github.samzhu.bot.usage.internal;

import java.util.List;

/** Shared read-side values; managerScope is established only by server-side authorization. */
final class UsageReadModel {
    public record Attempt(
            String id,
            String workspaceId,
            String runId,
            String providerRequestId,
            String providerResponseId,
            String preparedAt,
            String startedAt,
            String finishedAt,
            String provider,
            String endpoint,
            String model,
            String channel,
            String executionOutcome,
            String usagePresence,
            String captureCompleteness,
            String projectionError,
            String projectionState,
            String projectionVersion,
            String effectiveSnapshotId,
            boolean simulation,
            String attemptCoverage,
            boolean managerScope, boolean reportRead,
            io.github.samzhu.bot.usage.UsageCostOperations.Cost reportCost) {
        Attempt withReportCost(io.github.samzhu.bot.usage.UsageCostOperations.Cost cost) {
            return new Attempt(id,workspaceId,runId,providerRequestId,providerResponseId,preparedAt,startedAt,finishedAt,
                    provider,endpoint,model,channel,executionOutcome,usagePresence,captureCompleteness,projectionError,
                    projectionState,projectionVersion,effectiveSnapshotId,simulation,attemptCoverage,managerScope,true,cost);
        }
    }

    public record Snapshot(
            String id,
            String eventId,
            String ordinal,
            String semantic,
            String sourceLayer,
            String schemaVersion,
            String adapterVersion,
            String captureVersion,
            String captureCompleteness,
            String rawState,
            String rawUsage,
            String captureReason,
            String capturedAt) {}

    public record PageInfo(boolean hasNextPage, String endCursor) {}

    public record Connection<T>(List<T> nodes, PageInfo pageInfo) {}

    static Attempt attempt(java.sql.ResultSet rs) throws java.sql.SQLException { return attempt(rs, false); }

    static Attempt attempt(java.sql.ResultSet rs, boolean managerScope) throws java.sql.SQLException {
        return new Attempt(
                rs.getString("id"),
                rs.getString("workspace_id"),
                rs.getString("run_id"),
                rs.getString("provider_request_id"),
                rs.getString("provider_response_id"),
                rs.getTimestamp("prepared_at").toInstant().toString(),
                rs.getTimestamp("started_at") == null
                        ? null
                        : rs.getTimestamp("started_at").toInstant().toString(),
                rs.getTimestamp("finished_at") == null
                        ? null
                        : rs.getTimestamp("finished_at").toInstant().toString(),
                rs.getString("provider"),
                rs.getString("endpoint"),
                rs.getString("model"),
                rs.getString("channel"),
                rs.getString("execution_outcome"),
                rs.getString("usage_presence"),
                rs.getString("capture_completeness"),
                rs.getString("projection_error"),
                rs.getString("projection_state"),
                Long.toString(rs.getLong("projection_version")),
                "VALID".equals(rs.getString("projection_state"))
                        ? rs.getString("projection_snapshot_id")
                        : null,
                rs.getBoolean("simulation"),
                rs.getString("attempt_coverage"), managerScope, false, null);
    }

    private UsageReadModel() {}
}
