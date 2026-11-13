package io.github.samzhu.bot.usage.internal;

import io.github.samzhu.bot.usage.UsageLedger;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

@Service
public class JdbcUsageLedger implements UsageLedger {
    private final JdbcTemplate jdbc;
    private final java.time.Clock clock;
    private final io.github.samzhu.bot.usage.internal.admission.WorkspaceLane lane;
    private final io.github.samzhu.bot.usage.internal.admission.BudgetProjection budgetProjection;
    private final org.springframework.transaction.support.TransactionTemplate transactions;
    private final io.github.samzhu.bot.usage.internal.pricing.CatalogActivation catalog;
    private final io.github.samzhu.bot.usage.RunAdmissionStateReader runStateReader;

    @org.springframework.beans.factory.annotation.Autowired
    public JdbcUsageLedger(JdbcTemplate jdbc, java.time.Clock clock, io.github.samzhu.bot.usage.internal.pricing.CatalogActivation catalog,
            io.github.samzhu.bot.usage.internal.admission.WorkspaceLane lane,
            io.github.samzhu.bot.usage.internal.admission.BudgetProjection budgetProjection,
            org.springframework.transaction.PlatformTransactionManager manager,
            io.github.samzhu.bot.usage.RunAdmissionStateReader runStateReader) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.catalog = catalog;
        this.lane = lane;
        this.budgetProjection = budgetProjection;
        this.transactions = new org.springframework.transaction.support.TransactionTemplate(manager);
        this.runStateReader = runStateReader;
    }

    @Override
    public String begin(Dispatch d) {
        try {
            return lane.submit(d.workspaceId(), () -> transactions.execute(status -> beginInTransaction(d))).toCompletableFuture().join();
        } catch (java.util.concurrent.CompletionException ex) {
            if (ex.getCause() instanceof RuntimeException cause) throw cause;
            throw ex;
        }
    }

    private String beginInTransaction(Dispatch d) {
        if (d.runId()!=null) {
            var registrations=jdbc.query("select workspace_id,actor_id,conversation_id,partition,status from workspace_admission_registration where run_id=? for update",
                    (rs,n)->new String[]{rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5)},d.runId());
            if (registrations.size()!=1 || runStateReader==null) throw new IllegalStateException("Admission registration unavailable");
            var registration=registrations.getFirst();
            if (!d.workspaceId().equals(registration[0]) || !d.actorId().equals(registration[1])
                    || !(d.simulation()?"SIMULATION":"REAL").equals(registration[3]) || !"PUBLISHED".equals(registration[4]))
                throw new IllegalStateException("Admission registration is not active");
            // The registration lock serializes begin with a durable whole-Run supersession.
            if (Boolean.TRUE.equals(jdbc.queryForObject("""
                    select exists(select 1 from workspace_admission_superseded s
                        join workspace_admission_registration r on r.token=s.registration_token where r.run_id=?)
                    """,Boolean.class,d.runId()))) throw new IllegalStateException("Admission registration is superseded");
            var run=runStateReader.readForUpdate(d.runId());
            if (run==null || run.status()!=io.github.samzhu.bot.usage.RunAdmissionStateReader.Status.ACTIVE
                    || !registration[2].equals(run.conversationId())) throw new IllegalStateException("Run is not active");
        }
        String id = UUID.randomUUID().toString();
        jdbc.update(
                """
INSERT INTO provider_attempt(id,run_id,workspace_id,actor_id,provider,endpoint,model,channel,adapter_version,
    simulation,attempt_coverage,prepared_at,execution_outcome,usage_presence,capture_completeness,projection_state)
VALUES (?,?,?,?,?,?,?,?,?,?,?,?,'IN_PROGRESS','PENDING','UNKNOWN','MISSING')
""",
                id,
                d.runId(),
                d.workspaceId(),
                d.actorId(),
                d.provider(),
                d.endpoint(),
                d.model(),
                d.channel(),
                d.adapterVersion(),
                d.simulation(),
                d.attemptCoverage(),
                Timestamp.from(clock.instant()));
        return id;
    }

    @Override
    public boolean markDispatched(String attemptId, java.util.function.Function<java.time.Clock, Instant> decision) {
        var scopes=jdbc.query("select workspace_id from provider_attempt where id=?",(rs,n)->rs.getString(1),attemptId);
        if (scopes.isEmpty()) return false;
        return Boolean.TRUE.equals(lane.submitCritical(scopes.getFirst(),()->transactions.execute(status ->
                markDispatchedInTransaction(attemptId,decision))).toCompletableFuture().join());
    }

    private boolean markDispatchedInTransaction(String attemptId, java.util.function.Function<java.time.Clock, Instant> decision) {
        io.github.samzhu.bot.usage.internal.admission.UsageWriterLock.registrationForAttempt(jdbc,attemptId);
        // Wait for the database row before sampling the clock, so lock contention cannot move a
        // dispatch into the wrong period.
        var rows =
                jdbc.query(
                        "SELECT execution_outcome, started_at FROM provider_attempt WHERE id=? FOR"
                            + " UPDATE",
                        (rs, n) -> new Object[] {rs.getString(1), rs.getTimestamp(2)},
                        attemptId);
        if (rows.isEmpty()
                || !"IN_PROGRESS".equals(rows.getFirst()[0])
                || rows.getFirst()[1] != null) return false;
        var sampled = catalog.sample(clock, decision);
        if (sampled == null) return false;
        return jdbc.update(
                        """
UPDATE provider_attempt SET started_at=?,catalog_marker=?,catalog_revision_id=?,projection_version=projection_version+1
WHERE id=? AND execution_outcome='IN_PROGRESS' AND started_at IS NULL
""",
                        Timestamp.from(sampled.startedAt()),
                        sampled.revisionId() == null ? "NO_CATALOG" : "CAPTURED",
                        sampled.revisionId(),
                        attemptId)
                == 1;
    }

    @Override
    public void append(String attemptId, Snapshot snapshot) {
        var scopes=jdbc.query("select workspace_id from provider_attempt where id=?",(rs,n)->rs.getString(1),attemptId);
        if (scopes.isEmpty()) throw new IllegalArgumentException("Unknown usage attempt");
        String workspace=scopes.getFirst();
        try {
            lane.submitCritical(workspace, () -> transactions.execute(status -> {
                io.github.samzhu.bot.usage.internal.admission.UsageWriterLock.registrationForAttempt(jdbc,attemptId);
                appendInTransaction(attemptId,snapshot);
                refreshAttemptPeriod(attemptId,workspace);
                return null;
            })).toCompletableFuture().join();
        } catch (java.util.concurrent.CompletionException ex) {
            if (ex.getCause() instanceof RuntimeException cause) throw cause;
            throw ex;
        }
    }

    private void appendInTransaction(String attemptId, Snapshot snapshot) {
        if (snapshot.ordinal() < 0
                || (snapshot.rawJson() == null && !"UNKNOWN".equals(snapshot.semantic())))
            throw new IllegalArgumentException("Invalid snapshot");
        if (snapshot.rawJson() != null) validateJson(snapshot.rawJson());
        String hash = hash(snapshot);
        jdbc.queryForObject(
                "SELECT id FROM provider_attempt WHERE id=? FOR UPDATE", String.class, attemptId);
        var existing =
                jdbc.query(
                        "SELECT payload_hash FROM usage_snapshot WHERE attempt_id=? AND event_id=?",
                        (rs, row) -> rs.getString(1),
                        attemptId,
                        snapshot.eventId());
        if (!existing.isEmpty()) {
            if (!existing.getFirst().equals(hash))
                throw new IllegalStateException("Snapshot identity conflict");
            return;
        }
        String rawState =
                snapshot.rawJson() == null ? "UNAVAILABLE" : jsonbState(snapshot.rawJson());
        String id = UUID.randomUUID().toString();
        jdbc.update(
                """
INSERT INTO usage_snapshot(id,attempt_id,event_id,payload_hash,ordinal,semantic,source_layer,schema_version,
    adapter_version,capture_version,capture_completeness,raw_state,raw_usage,raw_usage_text,capture_reason,captured_at)
VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?,?,?)
""",
                id,
                attemptId,
                snapshot.eventId(),
                hash,
                snapshot.ordinal(),
                snapshot.semantic(),
                snapshot.sourceLayer(),
                snapshot.schemaVersion(),
                snapshot.adapterVersion(),
                snapshot.captureVersion(),
                snapshot.completeness(),
                rawState,
                "JSONB".equals(rawState) ? snapshot.rawJson() : null,
                "TEXT_FALLBACK".equals(rawState) ? snapshot.rawJson() : null,
                "TEXT_FALLBACK".equals(rawState)
                        ? "JSONB_UNSUPPORTED"
                        : "UNAVAILABLE".equals(rawState) ? "USAGE_UNAVAILABLE" : null,
                Timestamp.from(clock.instant()));
        var current =
                jdbc.queryForMap(
                        """
SELECT a.projection_state,a.projection_error,s.ordinal,s.semantic,s.raw_usage::text AS raw
FROM provider_attempt a LEFT JOIN usage_snapshot s ON s.attempt_id=a.id AND s.id=a.projection_snapshot_id
WHERE a.id=?
""",
                        attemptId);
        if ("CONFLICTING_FINAL".equals(current.get("projection_error"))) return;
        Object currentOrdinal = current.get("ordinal");
        String oldSemantic = (String) current.get("semantic");
        if ("FINAL".equals(snapshot.semantic())
                && Boolean.TRUE.equals(
                        jdbc.queryForObject(
                                """
SELECT EXISTS(SELECT 1 FROM usage_snapshot WHERE attempt_id=? AND semantic='FINAL' AND id<>?)
""",
                                Boolean.class,
                                attemptId,
                                id))) {
            // A prior final may not be the current projection; all distinct finals require
            // reconciliation.
            jdbc.update(
                    "UPDATE provider_attempt SET"
                        + " usage_presence='UNKNOWN',projection_state='UNKNOWN',projection_error='CONFLICTING_FINAL',projection_version=projection_version+1"
                        + " WHERE id=?",
                    attemptId);
            return;
        }
        if ("FINAL".equals(oldSemantic)
                || (currentOrdinal != null
                        && ((Number) currentOrdinal).longValue() >= snapshot.ordinal())) return;
        String state =
                "JSONB".equals(rawState)
                                && !"DELTA".equals(snapshot.semantic())
                                && !"UNKNOWN".equals(snapshot.semantic())
                        ? "VALID"
                        : "UNKNOWN";
        String reason =
                "TEXT_FALLBACK".equals(rawState)
                        ? "JSONB_UNSUPPORTED"
                        : "UNAVAILABLE".equals(rawState)
                                ? "USAGE_UNAVAILABLE"
                                : "VALID".equals(state) ? null : "UNSUPPORTED_SEMANTIC";
        jdbc.update(
                """
UPDATE provider_attempt SET usage_presence=?,capture_completeness=?,projection_state=?,projection_error=?,
   projection_snapshot_id=?,projection_version=projection_version+1 WHERE id=?
""",
                "VALID".equals(state) ? "PRESENT" : "UNKNOWN",
                snapshot.completeness(),
                state,
                reason,
                id,
                attemptId);
    }

    private void refreshAttemptPeriod(String attemptId,String workspace) {
        var rows=jdbc.query("select simulation,started_at from provider_attempt where id=? and workspace_id=?",
                (rs,n)->new Object[]{rs.getBoolean(1),rs.getTimestamp(2)},attemptId,workspace);
        if (rows.isEmpty() || rows.getFirst()[1]==null) return;
        var row=rows.getFirst(); var zone=java.time.ZoneId.of("Asia/Taipei");
        var period=java.time.YearMonth.from(((Timestamp)row[1]).toInstant().atZone(zone));
        budgetProjection.recompute(workspace,(boolean)row[0] ? io.github.samzhu.bot.usage.WorkspaceAdmission.Partition.SIMULATION : io.github.samzhu.bot.usage.WorkspaceAdmission.Partition.REAL,
                period.atDay(1).atStartOfDay(zone).toInstant(),period.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant());
    }

    private void validateJson(String raw) {
        try (var parser = new com.fasterxml.jackson.core.JsonFactory().createParser(raw)) {
            if (parser.nextToken() == null) throw new IllegalArgumentException("Empty usage JSON");
            parser.skipChildren();
            if (parser.nextToken() != null)
                throw new IllegalArgumentException("Multiple usage JSON values");
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("Invalid usage JSON", e);
        }
    }

    private String jsonbState(String raw) {
        return jdbc.execute(
                (org.springframework.jdbc.core.ConnectionCallback<String>)
                        connection -> {
                            var savepoint = connection.setSavepoint();
                            try (var statement = connection.prepareStatement("SELECT ?::jsonb")) {
                                statement.setString(1, raw);
                                statement.execute();
                                connection.releaseSavepoint(savepoint);
                                return "JSONB";
                            } catch (java.sql.SQLException e) {
                                connection.rollback(savepoint);
                                if ("22003".equals(e.getSQLState())
                                        || "22P05".equals(e.getSQLState())) return "TEXT_FALLBACK";
                                throw e;
                            }
                        });
    }

    private String hash(Snapshot s) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            for (String value :
                    new String[] {
                        Long.toString(s.ordinal()),
                        s.semantic(),
                        s.sourceLayer(),
                        s.schemaVersion(),
                        s.adapterVersion(),
                        s.captureVersion(),
                        s.completeness(),
                        s.rawJson()
                    }) {
                byte[] bytes =
                        (value == null ? "<null>" : value)
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array());
                digest.update(bytes);
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void notDispatched(String attemptId, String reason) {
        var scopes=jdbc.query("select workspace_id from provider_attempt where id=?",(rs,n)->rs.getString(1),attemptId);
        if (scopes.isEmpty()) throw new IllegalArgumentException("Unknown usage attempt");
        try {
            lane.submitCritical(scopes.getFirst(), () -> transactions.execute(status -> {
                io.github.samzhu.bot.usage.internal.admission.UsageWriterLock.registrationForAttempt(jdbc,attemptId);
                var rows=jdbc.query("select started_at,execution_outcome from provider_attempt where id=? for update",
                        (rs,n)->new Object[]{rs.getTimestamp(1),rs.getString(2)},attemptId);
                if (rows.isEmpty()) throw new IllegalStateException("RECOVERY_REQUIRED");
                var row=rows.getFirst();
                if ("NOT_DISPATCHED".equals(row[1])) return null;
                Boolean uncertain=jdbc.queryForObject("""
                        select exists(select 1 from workspace_admission_dispatch where attempt_id=? and state='PENDING')
                            or exists(select 1 from workspace_admission_claim where attempt_id=?)
                        """,Boolean.class,attemptId,attemptId);
                if (row[0]!=null || !"IN_PROGRESS".equals(row[1]) || Boolean.TRUE.equals(uncertain))
                    throw new IllegalStateException("RECOVERY_REQUIRED");
                jdbc.update("""
                        update provider_attempt set execution_outcome='NOT_DISPATCHED',execution_reason=?,finished_at=?,
                            projection_version=projection_version+1,usage_presence='UNKNOWN'
                         where id=? and execution_outcome='IN_PROGRESS' and started_at is null
                        """,reason,Timestamp.from(clock.instant()),attemptId);
                return null;
            })).toCompletableFuture().join();
        } catch (java.util.concurrent.CompletionException ex) {
            if (ex.getCause() instanceof RuntimeException cause) throw cause;
            throw ex;
        }
    }

    @Override
    public void recordResponseId(String attemptId, String responseId) {
        if (responseId == null || responseId.isBlank()) return;
        var scopes=jdbc.query("select workspace_id from provider_attempt where id=?",(rs,n)->rs.getString(1),attemptId);
        if (scopes.isEmpty()) throw new IllegalArgumentException("Unknown usage attempt");
        lane.submitCritical(scopes.getFirst(),()->transactions.execute(status -> {
            io.github.samzhu.bot.usage.internal.admission.UsageWriterLock.registrationForAttempt(jdbc,attemptId);
            int updated=jdbc.update(
                        """
                        UPDATE provider_attempt SET provider_response_id=?
                        WHERE id=? AND (provider_response_id IS NULL OR provider_response_id=?)
                        """,
                        responseId,
                        attemptId,
                        responseId);
            if (updated != 1) throw new IllegalStateException("Provider response identity conflict");
            return null;
        })).toCompletableFuture().join();
    }

    @Override
    public void finish(String id, String outcome) {
        var scopes=jdbc.query("select workspace_id from provider_attempt where id=?",(rs,n)->rs.getString(1),id);
        if (scopes.isEmpty()) return;
        String workspace=scopes.getFirst();
        lane.submitCritical(workspace, () -> transactions.execute(status -> {
            io.github.samzhu.bot.usage.internal.admission.UsageWriterLock.registrationForAttempt(jdbc,id);
            var rows=jdbc.query("select simulation,started_at from provider_attempt where id=? and workspace_id=? for update",
                    (rs,n)->new Object[]{rs.getBoolean(1),rs.getTimestamp(2)},id,workspace);
            if (rows.isEmpty()) return null;
        jdbc.update(
                """
UPDATE provider_attempt SET execution_outcome=?,finished_at=?,projection_version=projection_version+1,
    execution_reason=CASE WHEN ?='UNKNOWN' THEN 'CAPTURE_FAILED' WHEN ?='FAILED' THEN 'PROVIDER_STREAM_ERROR' ELSE execution_reason END,
    usage_presence=CASE WHEN ?='UNKNOWN' OR usage_presence='PENDING' THEN 'UNKNOWN' ELSE usage_presence END,
    projection_state=CASE WHEN ?='UNKNOWN' THEN 'UNKNOWN' ELSE projection_state END,
    projection_error=CASE WHEN ?='UNKNOWN' THEN 'CAPTURE_FAILED' ELSE projection_error END
WHERE id=? AND execution_outcome='IN_PROGRESS'
""",
                outcome,
                Timestamp.from(clock.instant()),
                outcome,
                outcome,
                outcome,
                outcome,
                outcome,
                id);

            var row=rows.getFirst();
            if (row[1]!=null) {
                var period=java.time.YearMonth.from(((Timestamp)row[1]).toInstant().atZone(java.time.ZoneId.of("Asia/Taipei")));
                budgetProjection.recompute(workspace, (boolean)row[0] ? io.github.samzhu.bot.usage.WorkspaceAdmission.Partition.SIMULATION : io.github.samzhu.bot.usage.WorkspaceAdmission.Partition.REAL,
                        period.atDay(1).atStartOfDay(java.time.ZoneId.of("Asia/Taipei")).toInstant(),
                        period.plusMonths(1).atDay(1).atStartOfDay(java.time.ZoneId.of("Asia/Taipei")).toInstant());
            }
            return null;
        })).toCompletableFuture().join();
    }

    @Override
    public PricingInputView readPricingInput(String id) {
        return jdbc
                .query(
                        """
SELECT a.*,s.raw_usage::text AS raw_json,s.raw_state,s.semantic,s.ordinal,s.source_layer,s.schema_version,s.adapter_version AS snap_adapter,
       s.capture_version,s.capture_completeness AS snap_completeness,s.capture_reason
FROM provider_attempt a LEFT JOIN usage_snapshot s ON s.attempt_id=a.id AND s.id=a.projection_snapshot_id
  AND a.projection_state='VALID' WHERE a.id=?
""",
                        (rs, n) -> {
                            String snapshotId =
                                    rs.getString("raw_state") == null
                                            ? null
                                            : rs.getString("projection_snapshot_id");
                            EffectiveSnapshotView snapshot =
                                    snapshotId == null
                                            ? null
                                            : new EffectiveSnapshotView(
                                                    rs.getString("raw_json"),
                                                    rs.getString("raw_state"),
                                                    rs.getString("semantic"),
                                                    rs.getLong("ordinal"),
                                                    rs.getString("source_layer"),
                                                    rs.getString("schema_version"),
                                                    rs.getString("snap_adapter"),
                                                    rs.getString("capture_version"),
                                                    rs.getString("snap_completeness"),
                                                    rs.getString("capture_reason"));
                            return new PricingInputView(
                                    rs.getString("id"),
                                    rs.getString("run_id"),
                                    rs.getString("workspace_id"),
                                    rs.getString("actor_id"),
                                    rs.getString("provider"),
                                    rs.getString("endpoint"),
                                    rs.getString("model"),
                                    rs.getString("channel"),
                                    rs.getTimestamp("prepared_at").toInstant(),
                                    rs.getTimestamp("started_at") == null
                                            ? null
                                            : rs.getTimestamp("started_at").toInstant(),
                                    rs.getBoolean("simulation"),
                                    rs.getString("attempt_coverage"),
                                    rs.getString("execution_outcome"),
                                    rs.getString("execution_reason"),
                                    rs.getLong("projection_version"),
                                    rs.getString("projection_state"),
                                    rs.getString("projection_error"),
                                    rs.getString("usage_presence"),
                                    snapshotId,
                                    snapshot);
                        },
                        id)
                .stream()
                .findFirst()
                .orElse(null);
    }
}
