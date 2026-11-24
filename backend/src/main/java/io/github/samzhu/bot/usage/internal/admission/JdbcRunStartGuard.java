package io.github.samzhu.bot.usage.internal.admission;

import io.github.samzhu.bot.usage.RunStartGuard;
import io.github.samzhu.bot.usage.WorkspaceAdmission;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
final class JdbcRunStartGuard implements RunStartGuard {
    private final JdbcTemplate jdbc;
    JdbcRunStartGuard(JdbcTemplate jdbc) { this.jdbc=jdbc; }

    @Override public boolean lockAndCheck(WorkspaceAdmission.Registration r) {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Run start requires the caller's transaction");
        jdbc.query("select token from workspace_admission_registration where token=? for update",(rs,n)->rs.getString(1),r.token());
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from workspace_admission_registration
                 where token=? and run_id=? and workspace_id=? and actor_id=? and conversation_id=? and partition=?
                   and status='REGISTERED' and start_lease_until>clock_timestamp())
                """,Boolean.class,r.token(),r.runId(),r.workspaceId(),r.actorId(),r.conversationId(),r.partition().name()));
    }
}
