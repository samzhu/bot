package io.github.samzhu.bot.bootstrap;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** Local-only synthetic usage; the values are examples, not tokenizer or provider measurements. */
@Component
@Profile("local")
@Order(30)
public class LocalUsageSeedInitializer implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public LocalUsageSeedInitializer(JdbcTemplate jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    @Override
    public void run(ApplicationArguments args) {
        transactions.executeWithoutResult(
                status -> {
                    // Seed only against the existing local demonstration Run and its real workspace
                    // association.
                    jdbc.update(
                            """
INSERT INTO provider_attempt(id,run_id,workspace_id,actor_id,provider,endpoint,model,channel,adapter_version,
    simulation,attempt_coverage,prepared_at,started_at,finished_at,execution_outcome,usage_presence,capture_completeness,projection_state)
SELECT 'seed-usage-attempt-01',r.id,c.workspace_id,c.user_id,'FAKE','local-demo','synthetic-demo','FAKE','seed-1',
    TRUE,'VERIFIED',r.created_at,r.created_at,r.updated_at,'SUCCEEDED','PRESENT','COMPLETE','MISSING'
FROM chat_run r JOIN conversation c ON c.id=r.conversation_id
JOIN workspace w ON w.id=c.workspace_id AND w.personal_owner_id=c.user_id
WHERE r.id='seed-run-completed-01' AND c.id='seed-conv-completed-01' AND c.user_id='dev-user'
ON CONFLICT (id) DO NOTHING
""");
                    jdbc.update(
                            """
INSERT INTO usage_snapshot(id,attempt_id,event_id,payload_hash,ordinal,semantic,source_layer,schema_version,
    adapter_version,capture_version,capture_completeness,raw_state,raw_usage,captured_at)
SELECT 'seed-usage-snapshot-01',id,'seed-event-01',?,1,'FINAL','FAKE_RAW','seed-1','seed-1','seed-1',
    'COMPLETE','JSONB',?::jsonb,started_at
FROM provider_attempt WHERE id='seed-usage-attempt-01' AND run_id='seed-run-completed-01'
    AND actor_id='dev-user' AND provider='FAKE' AND simulation
ON CONFLICT (id) DO NOTHING
""",
                            "cd98319cf41c7a65a21da0dab338237c63966f33d63bbacc30ffbff31ffef929",
                            "{\"input\":12,\"output\":4,\"cacheRead\":0,\"example\":{\"kind\":\"synthetic\",\"note\":\"not"
                                + " tokenizer counts\"}}");
                    jdbc.update(
                            """
UPDATE provider_attempt SET projection_snapshot_id='seed-usage-snapshot-01',projection_state='VALID',projection_version=1
WHERE id='seed-usage-attempt-01' AND projection_snapshot_id IS NULL AND run_id='seed-run-completed-01'
    AND EXISTS (SELECT 1 FROM usage_snapshot WHERE id='seed-usage-snapshot-01' AND attempt_id='seed-usage-attempt-01')
""");
                });
    }
}
