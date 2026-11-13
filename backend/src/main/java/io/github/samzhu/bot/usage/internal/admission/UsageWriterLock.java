package io.github.samzhu.bot.usage.internal.admission;

import org.springframework.jdbc.core.JdbcTemplate;

/** Establishes registration-before-attempt ordering for usage writers in their existing transaction. */
public final class UsageWriterLock {
    private UsageWriterLock() {}

    public static void registrationForAttempt(JdbcTemplate jdbc,String attemptId) {
        // Terminal intake takes this row first; reversing the order can deadlock outbox creation.
        jdbc.query("""
                select r.token from workspace_admission_registration r
                  join provider_attempt a on a.run_id=r.run_id and a.workspace_id=r.workspace_id
                 where a.id=? for update of r
                """,(rs,n)->rs.getString(1),attemptId);
    }
}
