package io.github.samzhu.bot.usage.internal.admission;

import io.github.samzhu.bot.usage.MemberWeeklyBudgetOperations;
import io.github.samzhu.bot.usage.WorkspaceAdmission;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.time.*;
import java.time.temporal.TemporalAdjusters;

@Component
public class WeeklyBudgetQuery {
    private static final ZoneId ZONE=ZoneId.of("Asia/Taipei");
    private final JdbcTemplate jdbc;
    public WeeklyBudgetQuery(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public MemberWeeklyBudgetOperations.Budget select(String workspace,String member,WorkspaceAdmission.Partition partition,Instant at) {
        var monday=at.atZone(ZONE).toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        var start=monday.atStartOfDay(ZONE).toInstant(); var end=monday.plusWeeks(1).atStartOfDay(ZONE).toInstant();
        var totals=jdbc.queryForMap("""
          select count(*) as attempts,
            sum(case when audit.kind is distinct from 'MANUAL_ESTIMATE' and e.source_version=a.projection_version then e.known_subtotal end) as subtotal,
            count(*) filter (where a.execution_outcome<>'IN_PROGRESS' and (e.id is null or e.source_version<>a.projection_version or audit.kind='MANUAL_ESTIMATE' or e.status<>'COMPLETE')) as unknowns
          from provider_attempt a left join usage_cost_current c on c.attempt_id=a.id
          left join usage_cost_estimate e on e.id=c.estimate_id and e.attempt_id=a.id
          left join usage_cost_revision_audit audit on audit.estimate_id=e.id and audit.attempt_id=a.id
          where a.workspace_id=? and a.actor_id=? and a.simulation=? and a.started_at>=? and a.started_at<? and a.execution_outcome<>'NOT_DISPATCHED'
          """,workspace,member,partition==WorkspaceAdmission.Partition.SIMULATION,java.sql.Timestamp.from(start),java.sql.Timestamp.from(end));
        var rows=jdbc.query("select enabled,cap_usd,config_version from member_weekly_budget where workspace_id=? and member_user_id=? and partition=?",(rs,n)->new Object[]{rs.getBoolean(1),rs.getBigDecimal(2),rs.getLong(3)},workspace,member,partition.name());
        var row=rows.isEmpty()?new Object[]{false,null,0L}:rows.getFirst();
        BigDecimal subtotal=((Number)totals.get("attempts")).longValue()==0?BigDecimal.ZERO:(BigDecimal)totals.get("subtotal");
        return new MemberWeeklyBudgetOperations.Budget(workspace,member,partition,start,end,(boolean)row[0],(BigDecimal)row[1],subtotal,((Number)totals.get("unknowns")).longValue(),(long)row[2],false);
    }
}
