package io.github.samzhu.bot.usage.internal.admission;

import io.github.samzhu.bot.usage.WorkspaceAdmission;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.time.YearMonth;
import java.time.ZoneId;

/** Recomputes the committed cost projection inside the caller's workspace lane and transaction. */
@Component
public final class BudgetProjection {
    private static final ZoneId ZONE=ZoneId.of("Asia/Taipei");
    private final JdbcTemplate jdbc;
    private final BudgetCancellationOutbox outbox;
    BudgetProjection(JdbcTemplate jdbc,BudgetCancellationOutbox outbox) { this.jdbc=jdbc; this.outbox=outbox; }
    public void recompute(String workspaceId, WorkspaceAdmission.Partition partition, java.time.Instant start, java.time.Instant end) {
        var period=start.atZone(ZONE).toLocalDate();
        jdbc.update("insert into workspace_monthly_budget(workspace_id,partition) values (?,?) on conflict do nothing",workspaceId,partition.name());
        jdbc.update("insert into workspace_monthly_budget_period(workspace_id,partition,period_start) values (?,?,?) on conflict do nothing",workspaceId,partition.name(),period);
        var totals=jdbc.queryForMap("""
                select count(*) as attempt_count,
                       sum(case when audit.kind is distinct from 'MANUAL_ESTIMATE' and e.source_version=a.projection_version
                                then e.known_subtotal end) as subtotal,
                       bool_or(a.execution_outcome<>'IN_PROGRESS' and
                         (e.id is null or e.source_version<>a.projection_version or audit.kind='MANUAL_ESTIMATE'
                          or e.status<>'COMPLETE')) as has_unknown
                  from provider_attempt a
                  left join usage_cost_current c on c.attempt_id=a.id
                  left join usage_cost_estimate e on e.id=c.estimate_id and e.attempt_id=a.id
                  left join usage_cost_revision_audit audit on audit.estimate_id=e.id and audit.attempt_id=a.id
                 where a.workspace_id=? and a.simulation=? and a.started_at>=? and a.started_at<?
                   and a.execution_outcome<>'NOT_DISPATCHED'
                """,workspaceId,partition==WorkspaceAdmission.Partition.SIMULATION,
                java.sql.Timestamp.from(start),java.sql.Timestamp.from(end));
        long count=((Number)totals.get("attempt_count")).longValue();
        var subtotal=(java.math.BigDecimal)totals.get("subtotal");
        if (count==0) subtotal=java.math.BigDecimal.ZERO;
        boolean unknown=Boolean.TRUE.equals(totals.get("has_unknown"));
        jdbc.update("""
                update workspace_monthly_budget_period
                   set known_subtotal_usd=?,terminal_effective_unknown=?,
                       gate_generation=gate_generation+case when known_subtotal_usd is distinct from ?
                           or terminal_effective_unknown is distinct from ? then 1 else 0 end
                 where workspace_id=? and partition=? and period_start=?
                """,subtotal,unknown,subtotal,unknown,workspaceId,partition.name(),period);
        outbox.recordIfCapReached(new io.github.samzhu.bot.usage.WorkspaceMonthlyBudgetOperations.Scope(workspaceId,partition,YearMonth.from(period)));
    }

 }
