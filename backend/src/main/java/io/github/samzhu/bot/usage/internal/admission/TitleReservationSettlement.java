package io.github.samzhu.bot.usage.internal.admission;

import org.springframework.jdbc.core.JdbcTemplate;

/** Operations run in the caller's publication transaction and workspace lane. */
public final class TitleReservationSettlement {
    private TitleReservationSettlement() {}

    public static void lockBudget(JdbcTemplate jdbc, String attemptId) {
        // Title capture and expiry recovery lock work before the attempt and budget rows.
        jdbc.query("""
                select w.conversation_id from conversation_title_work w
                join provider_attempt a on a.title_conversation_id=w.conversation_id
                where a.id=? and a.purpose='CONVERSATION_TITLE' for update of w
                """, (rs,n)->rs.getString(1), attemptId);
        jdbc.query("""
                select b.workspace_id from workspace_monthly_budget b
                join workspace_title_reservation h on h.workspace_id=b.workspace_id and h.partition=b.partition
                where h.attempt_id=? for update of b
                """, (rs,n)->rs.getString(1), attemptId);
    }

    private static final String REMAINING = """
            select coalesce(sum(case when e.status='COMPLETE' and e.source_version=a.projection_version
                    and e.complete_amount is not null and audit.kind is distinct from 'MANUAL_ESTIMATE'
                    and a.purpose='CONVERSATION_TITLE' and a.title_conversation_id=h.conversation_id
                    and a.workspace_id=h.workspace_id and a.actor_id=h.actor_id
                    and a.simulation=(h.partition='SIMULATION') and a.started_at=h.reserved_at
                then greatest(h.amount_usd-e.complete_amount,0) else h.amount_usd end),0)
              from workspace_title_reservation h
              left join provider_attempt a on a.id=h.attempt_id
              left join usage_cost_current c on c.attempt_id=a.id
              left join usage_cost_estimate e on e.id=c.estimate_id and e.attempt_id=a.id
              left join usage_cost_revision_audit audit on audit.attempt_id=a.id and audit.estimate_id=e.id
             where h.workspace_id=? and h.partition=? and h.state in ('HELD','UNKNOWN')
            """;

    public static java.math.BigDecimal monthRemaining(JdbcTemplate jdbc, String workspace, String partition, java.time.LocalDate month) {
        return jdbc.queryForObject(REMAINING + " and h.month_start=?", java.math.BigDecimal.class, workspace, partition, month);
    }

    public static java.math.BigDecimal weekRemaining(JdbcTemplate jdbc, String workspace, String actor, String partition,
            java.time.Instant start, java.time.Instant end) {
        return jdbc.queryForObject(REMAINING + " and h.actor_id=? and h.reserved_at>=? and h.reserved_at<?",
                java.math.BigDecimal.class, workspace, partition, actor, java.sql.Timestamp.from(start), java.sql.Timestamp.from(end));
    }

    public static void settleCurrent(JdbcTemplate jdbc, String attemptId) {
        jdbc.update("""
                update workspace_title_reservation h set state='SETTLED'
                 where h.attempt_id=? and h.state in ('HELD','UNKNOWN') and exists (
                    select 1 from provider_attempt a join usage_cost_current c on c.attempt_id=a.id
                    join usage_cost_estimate e on e.id=c.estimate_id and e.attempt_id=a.id
                    where a.id=h.attempt_id and a.workspace_id=h.workspace_id and a.actor_id=h.actor_id
                      and a.purpose='CONVERSATION_TITLE' and a.title_conversation_id=h.conversation_id
                      and a.started_at=h.reserved_at and a.execution_outcome='SUCCEEDED'
                      and a.simulation=(h.partition='SIMULATION') and e.source_version=a.projection_version
                      and e.status='COMPLETE' and e.complete_amount<=h.amount_usd
                      and (not exists (select 1 from usage_cost_revision_audit audit
                                       where audit.attempt_id=a.id and audit.estimate_id=e.id)
                           or exists (select 1 from usage_cost_revision_audit audit
                               join usage_price_snapshot p on p.id=e.price_snapshot_id and p.attempt_id=a.id
                               where audit.attempt_id=a.id and audit.estimate_id=e.id
                                 and audit.kind='RECONCILIATION' and audit.source_type='SERVER_RAW_CATALOG'
                                 and p.origin='AUDITED_RECONCILIATION' and p.selection_state='MATCHED'
                                 and e.source_snapshot_id=a.projection_snapshot_id)))
                """, attemptId);
    }
}
