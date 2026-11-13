package io.github.samzhu.bot.usage.internal.admission;

import io.github.samzhu.bot.usage.WorkspaceMonthlyBudgetOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.util.UUID;

/** Called inside the workspace lane and the same transaction as the cap transition. */
@Component
final class BudgetCancellationOutbox {
    private final JdbcTemplate jdbc;
    BudgetCancellationOutbox(JdbcTemplate jdbc) { this.jdbc=jdbc; }

    void recordIfCapReached(WorkspaceMonthlyBudgetOperations.Scope scope) {
        var policies=jdbc.query("""
                select b.enabled,b.cap_usd,p.known_subtotal_usd,p.gate_generation
                  from workspace_monthly_budget b join workspace_monthly_budget_period p using(workspace_id,partition)
                 where b.workspace_id=? and b.partition=? and p.period_start=?
                """,(rs,n)->new Object[]{rs.getBoolean(1),rs.getBigDecimal(2),rs.getBigDecimal(3),rs.getLong(4)},
                scope.workspaceId(),scope.partition().name(),scope.period().atDay(1));
        if (policies.isEmpty()) return;
        var policy=policies.getFirst();
        if (!(boolean)policy[0]) return;
        var cap=(java.math.BigDecimal)policy[1]; var subtotal=(java.math.BigDecimal)policy[2];
        if (cap==null || (cap.signum()!=0 && (subtotal==null || subtotal.compareTo(cap)<0))) return;
        long generation=(long)policy[3];
        var pending=jdbc.query("""
                select token,run_id,pending_id,pending_epoch,pending_period_start,pending_period_end
                  from workspace_admission_registration where workspace_id=? and partition=?
                   and status in ('REGISTERED','PUBLISHED','CANCEL_REQUESTED') and pending_period_start=?
                """,(rs,n)->new Object[]{rs.getString(1),rs.getString(2),rs.getString(3),rs.getLong(4),rs.getDate(5),rs.getDate(6)},
                scope.workspaceId(),scope.partition().name(),scope.period().atDay(1));
        for (var row:pending) {
            jdbc.update("""
                    insert into workspace_admission_effect_outbox(effect_id,registration_token,target_workspace_id,target_partition,target_run_id,
                      pending_id,pending_epoch,period_start,period_end,generation,delivery_status,intent_receipt)
                    values (?,?,?,?,?,?,?,?,?,?,'PENDING',?) on conflict do nothing
                    """,UUID.randomUUID().toString(),row[0],scope.workspaceId(),scope.partition().name(),row[1],row[2],row[3],row[4],row[5],generation,UUID.randomUUID().toString());
            // The intent and publication decision share this transaction and workspace lane.
            jdbc.update("update workspace_admission_registration set status='CANCEL_REQUESTED',updated_at=current_timestamp where token=? and status in ('REGISTERED','PUBLISHED')",row[0]);
        }
        var claims=jdbc.query("""
                select c.registration_token,r.run_id,c.claim_id,c.attempt_id,c.period_start,c.period_end,c.generation
                  from workspace_admission_claim c join workspace_admission_registration r on r.token=c.registration_token
                 where r.workspace_id=? and r.partition=? and c.period_start=? and c.status in ('CLAIMED','CANCEL_REQUESTED','UNKNOWN')
                """,(rs,n)->new Object[]{rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getDate(5),rs.getDate(6),rs.getLong(7)},
                scope.workspaceId(),scope.partition().name(),scope.period().atDay(1));
        for (var row:claims) jdbc.update("""
                insert into workspace_admission_effect_outbox(effect_id,registration_token,target_workspace_id,target_partition,target_run_id,
                  claim_id,target_attempt_id,period_start,period_end,generation,delivery_status,intent_receipt)
                values (?,?,?,?,?,?,?,?,?,?,'PENDING',?) on conflict do nothing
                """,UUID.randomUUID().toString(),row[0],scope.workspaceId(),scope.partition().name(),row[1],row[2],row[3],row[4],row[5],row[6],UUID.randomUUID().toString());
    }
}
