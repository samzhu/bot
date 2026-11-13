package io.github.samzhu.bot.execution.internal;

import io.github.samzhu.bot.usage.BudgetCancellationSink;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Execution-owned receipt storage; writes participate in the run terminal transaction. */
@Repository
class BudgetCancellationReceipts {
    static final class IdentityMismatch extends RuntimeException {
        IdentityMismatch(String message) { super(message); }
    }
    private final JdbcTemplate jdbc;
    BudgetCancellationReceipts(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    private record Target(String kind, String id, String attemptId, Instant start, Instant end, long generation) {}
    private Target target(BudgetCancellationSink.Target target) {
        if (target instanceof BudgetCancellationSink.Target.Pending p) {
            var f=p.fence(); return new Target("PENDING",f.pendingId(),null,f.periodStart(),f.periodEnd(),f.epoch());
        }
        var f=((BudgetCancellationSink.Target.Claimed)target).fence();
        return new Target("CLAIMED",f.claimId(),f.attemptId(),f.periodStart(),f.periodEnd(),f.policyGeneration());
    }
    private void validateScope(BudgetCancellationSink.Effect e) {
        if (e.target() instanceof BudgetCancellationSink.Target.Pending p) {
            var f=p.fence();
            if (!Objects.equals(e.token(),f.token()) || !Objects.equals(e.workspaceId(),f.workspaceId()) || e.partition()!=f.partition())
                throw new IdentityMismatch("Effect fence scope mismatch");
        } else if (e.target() instanceof BudgetCancellationSink.Target.Claimed c) {
            var f=c.fence();
            if (!Objects.equals(e.token(),f.token()) || !Objects.equals(e.workspaceId(),f.workspaceId()) || e.partition()!=f.partition())
                throw new IdentityMismatch("Effect fence scope mismatch");
        } else throw new IdentityMismatch("Unknown effect target");
    }
    String findMatching(BudgetCancellationSink.Effect e) {
        validateScope(e);
        var t=target(e.target());
        var rows=jdbc.query("""
            select registration_token,workspace_id,partition,run_id,target_kind,target_id,target_attempt_id,
                   target_period_start,target_period_end,target_epoch_or_generation,intent_receipt,acceptance_receipt
              from execution_budget_cancellation_receipt where effect_id=?
            """, (rs,n)->new Object[]{rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),
                rs.getString(5),rs.getString(6),rs.getString(7),rs.getTimestamp(8).toInstant(),rs.getTimestamp(9).toInstant(),
                rs.getLong(10),rs.getString(11),rs.getString(12)},e.effectId());
        if (rows.isEmpty()) return null;
        var r=rows.getFirst();
        if (!Objects.equals(r[0],e.token()) || !Objects.equals(r[1],e.workspaceId()) || !Objects.equals(r[2],e.partition().name())
                || !Objects.equals(r[3],e.runId()) || !Objects.equals(r[4],t.kind()) || !Objects.equals(r[5],t.id())
                || !Objects.equals(r[6],t.attemptId()) || !Objects.equals(r[7],t.start()) || !Objects.equals(r[8],t.end())
                || !Objects.equals(r[9],t.generation()) || !Objects.equals(r[10],e.intentReceipt()))
            throw new IdentityMismatch("Effect identity mismatch");
        return (String)r[11];
    }
    void insert(BudgetCancellationSink.Effect e) {
        validateScope(e);
        var t=target(e.target());
        jdbc.update("""
            insert into execution_budget_cancellation_receipt(effect_id,registration_token,workspace_id,partition,run_id,
              target_kind,target_id,target_attempt_id,target_period_start,target_period_end,target_epoch_or_generation,intent_receipt,acceptance_receipt)
            values (?,?,?,?,?,?,?,?,?,?,?,?,?)
            """,e.effectId(),e.token(),e.workspaceId(),e.partition().name(),e.runId(),t.kind(),t.id(),t.attemptId(),
                Timestamp.from(t.start()),Timestamp.from(t.end()),t.generation(),e.intentReceipt(),UUID.randomUUID().toString());
    }
}
