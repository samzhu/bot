package io.github.samzhu.bot.usage.pricing;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** Owns the committed rows for one usage scenario that calls the application over HTTP. */
public final class UsageCaseData {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final List<String> catalogRevisions = new ArrayList<>();
    private String workspace;

    public UsageCaseData(JdbcTemplate jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    public void createWorkspace(String id, String manager) {
        transactions.executeWithoutResult(status -> {
            jdbc.update("insert into workspace(id,name) values (?,'Usage test')", id);
            jdbc.update("""
                    insert into workspace_member(workspace_id,user_id,role,active) values (?,?,'MANAGER',true)
                    """, id, manager);
        });
        workspace = id;
    }

    public void createCatalogRevision(String id, String sourceRevision) {
        jdbc.update("""
                insert into pricing_catalog_revision(id,source_reference,source_revision,content_hash)
                values (?,'fixture',?,'hash')
                """, id, sourceRevision);
        catalogRevisions.add(id);
    }

    public void cleanUp() throws InterruptedException {
        if (workspace == null) {
            return;
        }
        awaitTerminalWork();
        var sql = new NamedParameterJdbcTemplate(jdbc);
        var scope = Map.of("workspace", workspace);
        transactions.executeWithoutResult(status -> {
            // Cost, admission, and projection rows hold non-cascading references to attempts.
            for (String table : List.of("usage_cost_revision_audit", "usage_cost_manual_source_checkpoint",
                    "usage_cost_current", "usage_cost_estimate", "usage_price_snapshot",
                    "workspace_admission_finalization", "workspace_admission_dispatch")) {
                sql.update("""
                        delete from %s
                        where attempt_id in (select id from provider_attempt where workspace_id = :workspace)
                        """.formatted(table), scope);
            }
            sql.update("update provider_attempt set projection_snapshot_id = null where workspace_id = :workspace", scope);
            sql.update("""
                    delete from usage_snapshot where attempt_id in
                        (select id from provider_attempt where workspace_id = :workspace)
                    """, scope);
            sql.update("delete from provider_attempt where workspace_id = :workspace", scope);
            sql.update("""
                    delete from workspace_admission_claim where registration_token in
                        (select token from workspace_admission_registration where workspace_id = :workspace)
                    """, scope);
            sql.update("delete from workspace_admission_registration where workspace_id = :workspace", scope);
            sql.update("""
                    delete from ai_session where id in (select id from conversation where workspace_id = :workspace)
                    """, scope);
            sql.update("delete from conversation where workspace_id = :workspace", scope);
            sql.update("delete from workspace_monthly_budget_period where workspace_id = :workspace", scope);
            sql.update("delete from workspace_monthly_budget where workspace_id = :workspace", scope);
            sql.update("delete from workspace_member where workspace_id = :workspace", scope);
            sql.update("delete from workspace where id = :workspace", scope);
            for (String revision : catalogRevisions) {
                jdbc.update("delete from pricing_catalog_entry where revision_id = ?", revision);
                jdbc.update("delete from pricing_catalog_revision where id = ?", revision);
            }
        });
    }

    private void awaitTerminalWork() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from chat_run r join conversation c on c.id = r.conversation_id
                    where c.workspace_id = ? and r.status in ('PENDING', 'RUNNING'))
                    or exists(select 1 from workspace_admission_registration where workspace_id = ? and status <> 'TERMINAL')
                """, Boolean.class, workspace, workspace))) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("Usage fixture has unfinished Run or admission work: " + workspace);
            }
            Thread.sleep(20);
        }
    }
}
