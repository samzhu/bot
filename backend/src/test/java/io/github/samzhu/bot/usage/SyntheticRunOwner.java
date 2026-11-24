package io.github.samzhu.bot.usage;

import org.springframework.jdbc.core.JdbcTemplate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Test-only fixture helper to attach a valid generation-1 DB owner lease
 * for synthetic test Runs, matching Run state and credentials.
 */
public final class SyntheticRunOwner {

    private SyntheticRunOwner() {}

    /**
     * Attaches a live DB-clock owner generation 1 with an independent random owner token,
     * validating that the synthetic Run exists and matches the registration's conversation and active status.
     * Asserts exactly one row inserted via atomic INSERT...SELECT to reject collisions and out-of-scope runs.
     *
     * @param jdbc the database template
     * @param registration the verified admission registration containing runId and conversationId
     * @return immutable Fence for passing to owner-aware ledger and admission operations
     */
    public static RunOwnershipGuard.Fence attach(JdbcTemplate jdbc, WorkspaceAdmission.Registration registration) {
        String ownerToken = UUID.randomUUID().toString();
        int inserted = jdbc.update("""
                INSERT INTO run_ownership (run_id, owner_token, generation, lease_until)
                SELECT r.id, ?, 1, clock_timestamp() + interval '60 seconds'
                FROM chat_run r
                WHERE r.id = ?
                  AND r.conversation_id = ?
                  AND r.status IN ('PENDING', 'RUNNING')
                """, ownerToken, registration.runId(), registration.conversationId());

        assertEquals(1, inserted, "Synthetic Run must exist and match registration conversation/status before attaching owner fence");
        return new RunOwnershipGuard.Fence(registration.runId(), ownerToken, 1);
    }

    /**
     * Attaches a live DB-clock owner generation 1 with an independent random owner token
     * directly by runId, asserting the Run exists and is in active status ('PENDING' or 'RUNNING').
     *
     * @param jdbc the database template
     * @param runId the run ID to attach ownership to
     * @return immutable Fence for passing to owner-aware operations
     */
    public static RunOwnershipGuard.Fence attach(JdbcTemplate jdbc, String runId) {
        String ownerToken = UUID.randomUUID().toString();
        int inserted = jdbc.update("""
                INSERT INTO run_ownership (run_id, owner_token, generation, lease_until)
                SELECT r.id, ?, 1, clock_timestamp() + interval '60 seconds'
                FROM chat_run r
                WHERE r.id = ?
                  AND r.status IN ('PENDING', 'RUNNING')
                """, ownerToken, runId);

        assertEquals(1, inserted, "Synthetic Run must exist with PENDING/RUNNING status before attaching owner fence");
        return new RunOwnershipGuard.Fence(runId, ownerToken, 1);
    }
}
