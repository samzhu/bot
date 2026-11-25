package io.github.samzhu.bot.bootstrap;

import liquibase.changelog.ChangeSet;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Set;

/** The #76 release receipts establish filenames, not historical SQL checksums. */
final class LegacyBaseline {
    static final List<String> SCRIPTS = List.of(
            "V1__initial_schema.sql", "V3__session_schema.sql", "V4__add_conversation_pinned.sql",
            "V5__gke_bot_and_run_mode.sql", "V6__workspace.sql", "V7__usage_ledger.sql",
            "V8__usage_pricing.sql", "V9__usage_cost_revision.sql", "V10__workspace_monthly_budget.sql",
            "V11__member_weekly_budget.sql", "V12__pricing_catalog_entry_metadata.sql",
            "V13__conversation_title_work.sql", "V14__conversation_title_recovery.sql",
            "V15__conversation_title_reservation.sql", "V16__run_ownership.sql");

    private LegacyBaseline() {}

    static void verify(JdbcTemplate jdbc, List<ChangeSet> changes) {
        if (changes.size() < SCRIPTS.size()) throw mismatch("changelog history");
        for (int i = 0; i < SCRIPTS.size(); i++) {
            var c = changes.get(i);
            if (!SCRIPTS.get(i).equals(c.getId()) || !"bot".equals(c.getAuthor())
                    || !ReleaseSchema.PATH.equals(c.getFilePath())) throw mismatch("changelog identity");
        }
        if (!Boolean.TRUE.equals(jdbc.queryForObject("select to_regclass('schema_version') is not null", Boolean.class)))
            throw mismatch("missing schema_version");
        List<String> receipts = jdbc.queryForList("select script_name from schema_version", String.class);
        if (receipts.size() != SCRIPTS.size() || !Set.copyOf(receipts).equals(Set.copyOf(SCRIPTS)))
            throw mismatch("legacy migration history");
        for (String table : List.of("bot", "workspace", "workspace_member", "conversation", "chat_run", "message",
                "provider_attempt", "usage_snapshot", "pricing_catalog_revision", "workspace_admission_registration",
                "run_ownership")) {
            if (!Boolean.TRUE.equals(jdbc.queryForObject("select to_regclass(?) is not null", Boolean.class, table)))
                throw mismatch("missing table " + table);
        }
        for (String[] column : new String[][]{
                {"workspace", "id", "character varying", "NO"},
                {"workspace_member", "workspace_id", "character varying", "NO"},
                {"conversation", "workspace_id", "character varying", "NO"},
                {"chat_run", "execution_mode", "character varying", "NO"},
                {"run_ownership", "owner_token", "character varying", "NO"},
                {"run_ownership", "generation", "bigint", "NO"},
                {"run_ownership", "lease_until", "timestamp with time zone", "NO"},
                {"workspace_admission_registration", "start_lease_until", "timestamp with time zone", "YES"}}) {
            if (!Boolean.TRUE.equals(jdbc.queryForObject("""
                    select exists(select 1 from information_schema.columns where table_schema=current_schema()
                      and table_name=? and column_name=? and data_type=? and is_nullable=?)
                    """, Boolean.class, (Object[]) column))) throw mismatch("column " + column[0] + "." + column[1]);
        }
        for (String[] fk : new String[][]{{"workspace_member", "workspace_id", "workspace", "id"},
                {"conversation", "workspace_id", "workspace", "id"}, {"run_ownership", "run_id", "chat_run", "id"}}) {
            if (!Boolean.TRUE.equals(jdbc.queryForObject("""
                    select exists(select 1 from pg_constraint c
                      join pg_class src on src.oid=c.conrelid join pg_namespace ns on ns.oid=src.relnamespace
                      join pg_class dst on dst.oid=c.confrelid
                      join pg_attribute a on a.attrelid=src.oid and a.attnum=c.conkey[1]
                      join pg_attribute b on b.attrelid=dst.oid and b.attnum=c.confkey[1]
                      where ns.nspname=current_schema() and c.contype='f' and c.convalidated
                        and cardinality(c.conkey)=1 and src.relname=? and a.attname=? and dst.relname=? and b.attname=?)
                    """, Boolean.class, (Object[]) fk))) throw mismatch("foreign key " + fk[0] + "." + fk[1]);
        }
        if (!Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from pg_constraint c join pg_class t on t.oid=c.conrelid
                  join pg_namespace n on n.oid=t.relnamespace where n.nspname=current_schema()
                  and t.relname='run_ownership' and c.contype='c' and c.convalidated
                  and pg_get_constraintdef(c.oid) like '%generation > 0%')
                """, Boolean.class))) throw mismatch("run ownership generation constraint");
    }
    private static IllegalStateException mismatch(String detail) {
        return new IllegalStateException("Release provisioning refused: legacy baseline mismatch: " + detail);
    }
}
