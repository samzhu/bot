package io.github.samzhu.bot.usage.internal;

import graphql.GraphQLError;
import graphql.GraphqlErrorBuilder;

import io.github.samzhu.bot.identity.CurrentUserProvider;
import io.github.samzhu.bot.workspace.WorkspaceAccessDeniedException;
import io.github.samzhu.bot.workspace.WorkspaceOperations;

import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.GraphQlExceptionHandler;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.graphql.execution.ErrorType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Controller;

import java.util.List;
import java.util.Map;

@Controller
public class UsageGraphQLController {
    private final JdbcTemplate jdbc;
    private final CurrentUserProvider user;
    private final WorkspaceOperations workspace;
    private final io.github.samzhu.bot.usage.UsageCostOperations costs;

    public UsageGraphQLController(
            JdbcTemplate jdbc, CurrentUserProvider user, WorkspaceOperations workspace,
            io.github.samzhu.bot.usage.UsageCostOperations costs) {
        this.jdbc = jdbc;
        this.user = user;
        this.workspace = workspace;
        this.costs = costs;
    }

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
            String attemptCoverage) {}

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

    @QueryMapping
    public Connection<Attempt> myUsageAttempts(
            @Argument String workspaceId, @Argument Integer first, @Argument String after) {
        String actor = user.getCurrentUserId();
        workspace.requireMember(workspaceId, actor);
        int limit = pageSize(first);
        String[] cursor = after == null ? null : decode(after, "attempt", workspaceId, "");
        java.time.Instant time = null;
        if (cursor != null) {
            java.time.Instant requested;
            try {
                requested = java.time.Instant.parse(cursor[3]);
            } catch (java.time.DateTimeException ex) {
                throw new UsageQueryException("INVALID_CURSOR");
            }
            // Read the anchor by authorized scope before using its trusted timestamp in SQL.
            var anchors =
                    jdbc.query(
                            "SELECT prepared_at FROM provider_attempt WHERE workspace_id=? AND"
                                + " actor_id=? AND id=?",
                            (rs, n) -> rs.getTimestamp(1).toInstant(),
                            workspaceId,
                            actor,
                            cursor[4]);
            if (anchors.isEmpty() || !anchors.getFirst().equals(requested))
                throw new UsageQueryException("INVALID_CURSOR");
            time = anchors.getFirst();
        }
        var list =
                cursor == null
                        ? jdbc.query(
                                "SELECT * FROM provider_attempt WHERE workspace_id=? AND actor_id=?"
                                    + " ORDER BY prepared_at DESC,id DESC LIMIT ?",
                                (rs, n) -> attempt(rs),
                                workspaceId,
                                actor,
                                limit + 1)
                        : jdbc.query(
                                "SELECT * FROM provider_attempt WHERE workspace_id=? AND actor_id=?"
                                    + " AND (prepared_at,id)<(?,?) ORDER BY prepared_at DESC,id"
                                    + " DESC LIMIT ?",
                                (rs, n) -> attempt(rs),
                                workspaceId,
                                actor,
                                java.sql.Timestamp.from(time),
                                cursor[4],
                                limit + 1);
        boolean more = list.size() > limit;
        var nodes = List.copyOf(list.subList(0, Math.min(limit, list.size())));
        String next =
                nodes.isEmpty()
                        ? null
                        : encode(
                                "attempt",
                                workspaceId,
                                "",
                                nodes.getLast().preparedAt(),
                                nodes.getLast().id());
        return new Connection<>(nodes, new PageInfo(more, next));
    }

    @QueryMapping
    public Attempt myUsageAttempt(@Argument String workspaceId, @Argument String id) {
        String actor = user.getCurrentUserId();
        workspace.requireMember(workspaceId, actor);
        return jdbc
                .query(
                        "SELECT * FROM provider_attempt WHERE workspace_id=? AND actor_id=? AND"
                            + " id=?",
                        (rs, n) -> attempt(rs),
                        workspaceId,
                        actor,
                        id)
                .stream()
                .findFirst()
                .orElse(null);
    }

    private Attempt attempt(java.sql.ResultSet rs) throws java.sql.SQLException {
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
                rs.getString("attempt_coverage"));
    }

    public record NativeValue(String value, String presence, String meaning) {}

    @SchemaMapping(typeName = "UsageAttempt", field = "input")
    public NativeValue input(Attempt attempt) {
        return nativeValue(attempt, "input");
    }

    @SchemaMapping(typeName = "UsageAttempt", field = "output")
    public NativeValue output(Attempt attempt) {
        return nativeValue(attempt, "output");
    }

    @SchemaMapping(typeName = "UsageAttempt", field = "cacheRead")
    public NativeValue cacheRead(Attempt attempt) {
        return nativeValue(attempt, "cacheRead");
    }

    @SchemaMapping(typeName = "UsageAttempt", field = "cacheWrite")
    public NativeValue cacheWrite(Attempt attempt) {
        return nativeValue(attempt, "cacheWrite");
    }

    private NativeValue nativeValue(Attempt attempt, String field) {
        if (myUsageAttempt(attempt.workspaceId(), attempt.id()) == null)
            throw new WorkspaceAccessDeniedException();
        String key =
                switch (attempt.provider()) {
                    case "FAKE" -> field;
                    case "GOOGLE" ->
                            switch (field) {
                                case "input" -> "promptTokenCount";
                                case "output" -> "candidatesTokenCount";
                                case "cacheRead" -> "cachedContentTokenCount";
                                default -> "";
                            };
                    default -> "";
                };
        String meaning;
        if (key.isEmpty()) meaning = "Provider field not available";
        else if ("GOOGLE".equals(attempt.provider()))
            meaning =
                    switch (field) {
                        case "input" -> "Gemini promptTokenCount，包含已快取的輸入";
                        case "output" -> "Gemini candidatesTokenCount，不包含獨立的 thoughtsTokenCount";
                        case "cacheRead" -> "Gemini cachedContentTokenCount，為 prompt 的子集";
                        default -> "Provider field not available";
                    };
        else meaning = attempt.provider() + " native " + key;
        if (attempt.effectiveSnapshotId() == null || key.isEmpty())
            return new NativeValue(null, "UNKNOWN", meaning);
        var rows =
                jdbc.query(
                        "SELECT raw_usage::text, capture_completeness FROM usage_snapshot WHERE"
                            + " attempt_id=? AND id=?",
                        (rs, n) -> new String[] {rs.getString(1), rs.getString(2)},
                        attempt.id(),
                        attempt.effectiveSnapshotId());
        if (rows.isEmpty() || rows.getFirst()[0] == null)
            return new NativeValue(null, "UNKNOWN", meaning);
        try {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            mapper.enable(
                    com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_INTEGER_FOR_INTS);
            var root = mapper.readTree(rows.getFirst()[0]);
            var node = root == null ? null : root.get(key);
            if (node == null)
                return new NativeValue(
                        null,
                        "COMPLETE".equals(rows.getFirst()[1]) ? "MISSING" : "UNKNOWN",
                        meaning);
            if (node.isNull()) return new NativeValue(null, "NULL_VALUE", meaning);
            if (!node.isIntegralNumber() || node.bigIntegerValue().signum() < 0)
                return new NativeValue(null, "UNKNOWN", meaning);
            return new NativeValue(node.bigIntegerValue().toString(), "PRESENT", meaning);
        } catch (java.io.IOException ex) {
            return new NativeValue(null, "UNKNOWN", meaning);
        }
    }

    @SchemaMapping(typeName = "UsageAttempt", field = "cost")
    public java.util.Map<String, Object> cost(Attempt attempt) {
        if (myUsageAttempt(attempt.workspaceId(), attempt.id()) == null)
            throw new WorkspaceAccessDeniedException();
        var c = costs.readCosts(java.util.List.of(attempt.id())).get(attempt.id());
        if (c == null) throw new WorkspaceAccessDeniedException();
        var result = new java.util.HashMap<String, Object>();
        result.put("status", c.status()); result.put("amount", c.completeAmount());
        result.put("knownSubtotal", c.knownSubtotal()); result.put("completeAmount", c.completeAmount());
        result.put("currency", c.currency()); result.put("simulation", c.simulation());
        result.put("needsReprice", c.needsReprice()); result.put("sourceProjectionVersion", c.sourceProjectionVersion());
        result.put("estimateSourceVersion", c.estimateSourceVersion()); result.put("currentRevision", c.currentRevision());
        result.put("priceRevision", c.priceRevision()); result.put("priceSnapshotId", c.priceSnapshotId());
        result.put("priceSourceReference", c.priceSourceReference()); result.put("priceEffectiveFrom", c.priceEffectiveFrom());
        result.put("priceEffectiveUntil", c.priceEffectiveUntil()); result.put("calculatorVersion", c.calculatorVersion());
        result.put("sourceSnapshotId", c.sourceSnapshotId()); result.put("sourceLayer", c.sourceLayer());
        result.put("schemaVersion", c.schemaVersion()); result.put("adapterVersion", c.adapterVersion());
        result.put("captureVersion", c.captureVersion()); result.put("captureCompleteness", c.captureCompleteness());
        result.put("semantic", c.semantic()); result.put("ordinal", c.ordinal());
        result.put("reasons", c.reasons()); result.put("lineItems", c.lineItems());
        return result;
    }

    @SchemaMapping(typeName = "UsageAttempt", field = "snapshots")
    public Connection<Snapshot> snapshots(
            Attempt attempt, @Argument Integer first, @Argument String after) {
        if (myUsageAttempt(attempt.workspaceId(), attempt.id()) == null)
            throw new WorkspaceAccessDeniedException();
        int limit = pageSize(first);
        String[] cursor =
                after == null
                        ? null
                        : decode(after, "snapshot", attempt.workspaceId(), attempt.id());
        long ordinal = 0;
        if (cursor != null) {
            long requested;
            try {
                requested = Long.parseLong(cursor[3]);
            } catch (NumberFormatException ex) {
                throw new UsageQueryException("INVALID_CURSOR");
            }
            var anchors =
                    jdbc.query(
                            "SELECT ordinal FROM usage_snapshot WHERE attempt_id=? AND id=?",
                            (rs, n) -> rs.getLong(1),
                            attempt.id(),
                            cursor[4]);
            if (requested < 0 || anchors.isEmpty() || anchors.getFirst() != requested)
                throw new UsageQueryException("INVALID_CURSOR");
            ordinal = anchors.getFirst();
        }
        String select =
                "SELECT *,COALESCE(raw_usage::text,raw_usage_text) AS raw_json FROM usage_snapshot"
                    + " WHERE attempt_id=? ";
        org.springframework.jdbc.core.RowMapper<Snapshot> mapper =
                (rs, n) ->
                        new Snapshot(
                                rs.getString("id"),
                                rs.getString("event_id"),
                                Long.toString(rs.getLong("ordinal")),
                                rs.getString("semantic"),
                                rs.getString("source_layer"),
                                rs.getString("schema_version"),
                                rs.getString("adapter_version"),
                                rs.getString("capture_version"),
                                rs.getString("capture_completeness"),
                                rs.getString("raw_state"),
                                rs.getString("raw_json"),
                                rs.getString("capture_reason"),
                                rs.getTimestamp("captured_at").toInstant().toString());
        var list =
                cursor == null
                        ? jdbc.query(
                                select + "ORDER BY ordinal DESC,id DESC LIMIT ?",
                                mapper,
                                attempt.id(),
                                limit + 1)
                        : jdbc.query(
                                select
                                        + "AND (ordinal,id)<(?,?) ORDER BY ordinal DESC,id DESC"
                                        + " LIMIT ?",
                                mapper,
                                attempt.id(),
                                ordinal,
                                cursor[4],
                                limit + 1);
        boolean more = list.size() > limit;
        var nodes = List.copyOf(list.subList(0, Math.min(limit, list.size())));
        String next =
                nodes.isEmpty()
                        ? null
                        : encode(
                                "snapshot",
                                attempt.workspaceId(),
                                attempt.id(),
                                nodes.getLast().ordinal(),
                                nodes.getLast().id());
        return new Connection<>(nodes, new PageInfo(more, next));
    }

    private static int pageSize(Integer first) {
        if (first == null) return 20;
        if (first < 1 || first > 100) throw new UsageQueryException("INVALID_PAGE_SIZE");
        return first;
    }

    private static String encode(String kind, String scope, String parent, String key, String id) {
        var encoder = java.util.Base64.getUrlEncoder().withoutPadding();
        return java.util.stream.Stream.of("v1", kind, scope, parent, key, id)
                .map(
                        part ->
                                encoder.encodeToString(
                                        part.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .collect(java.util.stream.Collectors.joining("."));
    }

    private static String[] decode(String cursor, String kind, String scope, String parent) {
        try {
            if (cursor.length() > 2048) throw new IllegalArgumentException();
            String[] parts = cursor.split("\\.", -1);
            if (parts.length != 6) throw new IllegalArgumentException();
            var decoder = java.util.Base64.getUrlDecoder();
            String[] values = new String[6];
            for (int i = 0; i < parts.length; i++)
                values[i] =
                        new String(
                                decoder.decode(parts[i]), java.nio.charset.StandardCharsets.UTF_8);
            if (!encode(values[1], values[2], values[3], values[4], values[5]).equals(cursor)
                    || !"v1".equals(values[0])
                    || !kind.equals(values[1])
                    || !scope.equals(values[2])
                    || !parent.equals(values[3])
                    || values[4].isEmpty()
                    || values[5].isEmpty()) throw new IllegalArgumentException();
            return new String[] {values[0], values[1], values[2], values[4], values[5]};
        } catch (RuntimeException ex) {
            throw new UsageQueryException("INVALID_CURSOR");
        }
    }

    private static class UsageQueryException extends RuntimeException {
        UsageQueryException(String code) {
            super(code);
        }
    }

    @GraphQlExceptionHandler
    public GraphQLError invalidQuery(UsageQueryException ex) {
        return GraphqlErrorBuilder.newError()
                .errorType(ErrorType.BAD_REQUEST)
                .message(ex.getMessage())
                .extensions(Map.of("code", ex.getMessage()))
                .build();
    }

    @GraphQlExceptionHandler
    public GraphQLError denied(WorkspaceAccessDeniedException ex) {
        return GraphqlErrorBuilder.newError()
                .errorType(ErrorType.FORBIDDEN)
                .message("Workspace access denied")
                .extensions(Map.of("code", "WORKSPACE_ACCESS_DENIED"))
                .build();
    }
}
