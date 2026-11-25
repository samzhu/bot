package io.github.samzhu.bot;

import io.github.samzhu.bot.bootstrap.ReleaseProvisioningApplication;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** BOT_76_ADMIN_JAR is built from the unmodified e64a536 release admin. */
@EnabledIfEnvironmentVariable(named = "BOT_76_ADMIN_JAR", matches = ".+")
class LegacyLiquibaseIntegrationTests {
    @Test void previouslyProvisionedDatabaseIsAdoptedWithoutReplayingHistory() throws Exception {
        try (var pg = PostgresTestImage.container(); var fresh = PostgresTestImage.container()) {
            pg.start(); fresh.start();
            var args = new String[]{"--spring.datasource.url=" + pg.getJdbcUrl(),
                    "--spring.datasource.username=" + pg.getUsername(),
                    "--spring.datasource.password=" + pg.getPassword(), "--management.otlp.metrics.export.enabled=false", "--bot.release.action=schema-only"};
            var command = new java.util.ArrayList<String>();
            command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
            command.add("-jar"); command.add(System.getenv("BOT_76_ADMIN_JAR"));
            command.addAll(java.util.List.of(args));
            var log = java.nio.file.Files.createTempFile("legacy76-admin-", ".log");
            try {
                var process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
                assertTrue(process.waitFor(90, TimeUnit.SECONDS));
                assertEquals(0, process.exitValue(), () -> {
                    try { return java.nio.file.Files.readString(log); } catch (java.io.IOException e) { return e.toString(); }
                });
            } finally { java.nio.file.Files.deleteIfExists(log); }
            var jdbc = new JdbcTemplate(new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword()));
            var expectedLegacy = java.util.List.of("V1__initial_schema.sql", "V3__session_schema.sql",
                    "V4__add_conversation_pinned.sql", "V5__gke_bot_and_run_mode.sql", "V6__workspace.sql",
                    "V7__usage_ledger.sql", "V8__usage_pricing.sql", "V9__usage_cost_revision.sql",
                    "V10__workspace_monthly_budget.sql", "V11__member_weekly_budget.sql", "V12__pricing_catalog_entry_metadata.sql",
                    "V13__conversation_title_work.sql", "V14__conversation_title_recovery.sql",
                    "V15__conversation_title_reservation.sql", "V16__run_ownership.sql").stream().sorted().toList();
            assertEquals(expectedLegacy, jdbc.queryForList("select script_name from schema_version order by script_name", String.class));
            jdbc.update("insert into workspace(id,name,personal_owner_id) values ('w79','Existing','u79')");
            jdbc.update("insert into workspace_member(workspace_id,user_id,role) values ('w79','u79','MANAGER')");
            jdbc.update("insert into conversation(id,bot_id,title,user_id,workspace_id) values ('c79','bot-general','Existing','u79','w79')");
            jdbc.update("insert into chat_run(id,conversation_id,client_request_id,input_text,status) values ('r79','c79','req79','test','COMPLETED')");
            jdbc.update("insert into message(id,conversation_id,run_id,role,content) values ('m79','c79','r79','USER','preserved')");
            var before = domainData(jdbc);
            var oldComment = jdbc.queryForObject("select obj_description('chat_run'::regclass, 'pg_class')", String.class);
            ReleaseProvisioningApplication.main(args);
            assertNotEquals(oldComment, jdbc.queryForObject("select obj_description('chat_run'::regclass, 'pg_class')", String.class));
            assertEquals("一次使用者請求從發起至完成、失敗或取消的完整 Run 生命週期；可包含多筆 ProviderAttempt。",
                    jdbc.queryForObject("select obj_description('chat_run'::regclass, 'pg_class')", String.class));
            assertEquals(before, domainData(jdbc));
            assertEquals(expectedLegacy, jdbc.queryForList("select script_name from schema_version order by script_name", String.class));
            PostgresCommentsIntegrationTests.assertReleaseReceipts(jdbc);

            var freshArgs = new String[]{"--spring.datasource.url=" + fresh.getJdbcUrl(),
                    "--spring.datasource.username=" + fresh.getUsername(),
                    "--spring.datasource.password=" + fresh.getPassword(), "--management.otlp.metrics.export.enabled=false", "--bot.release.action=schema-only"};
            ReleaseProvisioningApplication.main(freshArgs);
            var freshDb = new JdbcTemplate(new DriverManagerDataSource(fresh.getJdbcUrl(), fresh.getUsername(), fresh.getPassword()));
            assertEquals(comments(freshDb), comments(jdbc));
            var commentsAfter = comments(jdbc);
            ReleaseProvisioningApplication.main(args);
            assertEquals(before, domainData(jdbc));
            assertEquals(commentsAfter, comments(jdbc));
            PostgresCommentsIntegrationTests.assertReleaseReceipts(jdbc);
            try (var context = new org.springframework.boot.builder.SpringApplicationBuilder(BotApplication.class,
                    TestChatModelConfiguration.class, TestUsageCaptureConfiguration.class)
                    .profiles("test").web(org.springframework.boot.WebApplicationType.NONE).run(args)) {
                assertEquals(org.springframework.boot.availability.ReadinessState.ACCEPTING_TRAFFIC,
                        context.getBean(org.springframework.boot.availability.ApplicationAvailability.class).getReadinessState());
            }
            assertEquals(before, domainData(jdbc));
            assertEquals(commentsAfter, comments(jdbc));
        }
    }

    private static java.util.List<String> domainData(JdbcTemplate db) {
        var rows = new java.util.ArrayList<String>();
        for (String table : java.util.List.of("schema_version", "bot", "workspace", "workspace_member", "conversation", "chat_run", "message"))
            rows.add(table + db.queryForObject("select coalesce(jsonb_agg(to_jsonb(t) order by to_jsonb(t)::text),'[]'::jsonb)::text from " + table + " t", String.class));
        return rows;
    }

    private static java.util.List<String> comments(JdbcTemplate db) {
        return db.queryForList("""
            select c.relname || ':<table>:' || coalesce(obj_description(c.oid, 'pg_class'), '<none>')
              from pg_class c join pg_namespace n on n.oid=c.relnamespace
             where n.nspname=current_schema() and c.relkind in ('r','p')
               and c.relname not in ('databasechangelog','databasechangeloglock')
            union all
            select c.relname || ':' || a.attname || ':' || coalesce(col_description(c.oid,a.attnum), '<none>')
              from pg_class c join pg_namespace n on n.oid=c.relnamespace
              join pg_attribute a on a.attrelid=c.oid and a.attnum>0 and not a.attisdropped
             where n.nspname=current_schema() and c.relkind in ('r','p')
               and c.relname not in ('databasechangelog','databasechangeloglock')
             order by 1
            """, String.class);
    }
}
