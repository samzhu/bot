package io.github.samzhu.bot.usage.internal.admission;

import io.github.samzhu.bot.usage.WorkspaceAdmission;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Retry committed terminal intake without holding a workspace lane while waiting. */
@Component
final class FinalizationRecovery {
    private final JdbcTemplate jdbc;
    private final WorkspaceAdmission admission;
    private final Set<String> inFlight=ConcurrentHashMap.newKeySet();
    private String cursor="";

    FinalizationRecovery(JdbcTemplate jdbc, WorkspaceAdmission admission) {
        this.jdbc=jdbc;
        this.admission=admission;
    }

    @Scheduled(initialDelayString="${bot.budget.finalization.initial-delay-ms:1000}",fixedDelayString="${bot.budget.finalization.delay-ms:1000}")
    synchronized void scan() {
        var rows=jdbc.query("""
                select f.attempt_id,r.token,r.workspace_id,r.run_id,r.actor_id,r.conversation_id,r.partition
                  from workspace_admission_finalization f
                  join workspace_admission_registration r on r.token=f.registration_token
                 where f.state='PENDING' and f.attempt_id>? order by f.attempt_id limit 100
                """,(rs,n)->new Object[]{rs.getString(1),new WorkspaceAdmission.Registration(rs.getString(2),rs.getString(3),
                    rs.getString(4),rs.getString(5),rs.getString(6),WorkspaceAdmission.Partition.valueOf(rs.getString(7)))},cursor);
        if (rows.isEmpty()) { cursor=""; return; }
        for (var row:rows) {
            String attempt=(String)row[0]; cursor=attempt;
            if (!inFlight.add(attempt)) continue;
            try {
                admission.recoverFinalization((WorkspaceAdmission.Registration)row[1],attempt)
                        .whenComplete((result,error)->inFlight.remove(attempt));
            } catch (RuntimeException failure) {
                inFlight.remove(attempt);
            }
        }
    }
}
