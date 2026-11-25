package io.github.samzhu.bot;

import io.github.samzhu.bot.bootstrap.ReleaseProvisioningApplication;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.charset.StandardCharsets;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class LegacyBaselineIntegrationTests {
    // Independent frozen #76 fixture: execute the checked-in historical SQL, with old filename receipts.
    private static final List<String> HISTORY = List.of("V1__initial_schema.sql", "V3__session_schema.sql",
            "V4__add_conversation_pinned.sql", "V5__gke_bot_and_run_mode.sql", "V6__workspace.sql",
            "V7__usage_ledger.sql", "V8__usage_pricing.sql", "V9__usage_cost_revision.sql",
            "V10__workspace_monthly_budget.sql", "V11__member_weekly_budget.sql", "V12__pricing_catalog_entry_metadata.sql",
            "V13__conversation_title_work.sql", "V14__conversation_title_recovery.sql",
            "V15__conversation_title_reservation.sql", "V16__run_ownership.sql");
    private static String[] args(PostgreSQLContainer pg) {
        return new String[]{"--spring.datasource.url=" + pg.getJdbcUrl(), "--spring.datasource.username=" + pg.getUsername(),
                "--spring.datasource.password=" + pg.getPassword(), "--management.otlp.metrics.export.enabled=false", "--bot.release.action=schema-only"};
    }
    private static JdbcTemplate db(PostgreSQLContainer pg) {
        return new JdbcTemplate(new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword()));
    }
    private static void legacy(JdbcTemplate db) throws Exception {
        for (String name : HISTORY) {
            try (var in = LegacyBaselineIntegrationTests.class.getResourceAsStream("/db/migration/" + name)) {
                assertNotNull(in, name);
                db.execute(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
            db.update("insert into schema_version(script_name) values (?)", name);
        }
        db.update("insert into bot(id,name,target,system_prompt) values ('bot-general','General','LOCAL','test')");
        db.update("insert into workspace(id,name,personal_owner_id) values ('w79','Existing','u79')");
        db.update("insert into workspace_member(workspace_id,user_id,role) values ('w79','u79','MANAGER')");
        db.update("insert into conversation(id,bot_id,title,user_id,workspace_id) values ('c79','bot-general','Existing','u79','w79')");
        db.execute("comment on table workspace is 'legacy fixture comment'");
    }
    private static String snapshot(JdbcTemplate db) {
        var result = new StringBuilder(db.queryForList("""
            select c.relname || ':' || c.relkind::text || ':' || coalesce(obj_description(c.oid), '')
              from pg_class c join pg_namespace n on n.oid=c.relnamespace where n.nspname=current_schema() order by 1
            """, String.class).toString());
        result.append(db.queryForList("""
            select c.conname || ':' || pg_get_constraintdef(c.oid) from pg_constraint c
              join pg_namespace n on n.oid=c.connamespace where n.nspname=current_schema() order by 1
            """, String.class));
        for (String table : db.queryForList("select tablename from pg_tables where schemaname=current_schema() order by 1", String.class))
            result.append(table).append(db.queryForObject("select coalesce(jsonb_agg(to_jsonb(t) order by to_jsonb(t)::text),'[]'::jsonb)::text from \"" + table.replace("\"", "\"\"") + "\" t", String.class));
        return result.toString();
    }
    private static void rejectedWithoutWrites(PostgreSQLContainer pg, JdbcTemplate db, String reason) {
        String before = snapshot(db);
        Exception ex = assertThrows(Exception.class, () -> ReleaseProvisioningApplication.main(args(pg)));
        assertTrue(ex.toString().contains(reason), ex.toString());
        assertEquals(before, snapshot(db));
    }
    private static void markOne(PostgreSQLContainer pg) throws Exception {
        try (var conn = java.sql.DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
             var resources = new ClassLoaderResourceAccessor();
             var lb = new Liquibase("db/changelog/db.changelog-master.yaml", resources, new JdbcConnection(conn))) {
            lb.markNextChangeSetRan(new Contexts(), new LabelExpression());
        }
    }

    @Test void rejectsHistoryAndSchemaDriftWithoutWriting() throws Exception {
        try (var pg = PostgresTestImage.container()) {
            pg.start(); var db = db(pg); legacy(db);
            db.update("delete from schema_version where script_name=?", HISTORY.get(7));
            rejectedWithoutWrites(pg, db, "legacy migration history");
            db.update("insert into schema_version(script_name) values (?)", HISTORY.get(7));
            db.update("insert into schema_version(script_name) values ('V99__unknown.sql')");
            rejectedWithoutWrites(pg, db, "legacy migration history");
            db.update("delete from schema_version where script_name='V99__unknown.sql'");
            db.execute("alter table conversation drop constraint fk_conversation_workspace");
            rejectedWithoutWrites(pg, db, "foreign key conversation.workspace_id");
            db.execute("alter table conversation add constraint fk_conversation_workspace foreign key (workspace_id) references workspace(id)");
            db.execute("alter table run_ownership drop column lease_until");
            rejectedWithoutWrites(pg, db, "column run_ownership.lease_until");
        }
    }

    @Test void resumesActualVendorPartialAndServiceStartsWithoutFurtherWrites() throws Exception {
        try (var pg = PostgresTestImage.container()) {
            pg.start(); var db = db(pg); legacy(db); markOne(pg);
            assertEquals(1, db.queryForObject("select count(*) from databasechangelog", Integer.class));
            assertEquals("EXECUTED", db.queryForObject("select exectype from databasechangelog", String.class));
            ReleaseProvisioningApplication.main(args(pg));
            PostgresCommentsIntegrationTests.assertReleaseReceipts(db);
            assertEquals("legacy fixture comment", db.queryForObject("select obj_description('workspace'::regclass)", String.class));
            assertEquals("Existing", db.queryForObject("select title from conversation where id='c79'", String.class));
            ReleaseProvisioningApplication.main(new String[]{"--spring.datasource.url=" + pg.getJdbcUrl(),
                    "--spring.datasource.username=" + pg.getUsername(), "--spring.datasource.password=" + pg.getPassword(),
                    "--bot.release.action=initialize-prod", "--bot.release.expected-jdbc-url=" + pg.getJdbcUrl()});
            var stable = snapshot(db);
            ReleaseProvisioningApplication.main(args(pg));
            assertEquals(stable, snapshot(db));
            try (var context = new SpringApplicationBuilder(BotApplication.class, TestChatModelConfiguration.class,
                    TestUsageCaptureConfiguration.class).profiles("test").web(WebApplicationType.NONE).run(args(pg))) {
                assertEquals(org.springframework.boot.availability.ReadinessState.ACCEPTING_TRAFFIC,
                        context.getBean(org.springframework.boot.availability.ApplicationAvailability.class).getReadinessState());
            }
            assertEquals(stable, snapshot(db));
        }
    }

    @Test void newerChangeExecutesAfterOnlyHistoricalPrefixIsSynced() throws Exception {
        try (var pg = PostgresTestImage.container()) {
            pg.start(); var db = db(pg); legacy(db);
            var root = java.nio.file.Files.createTempDirectory("future-changelog-");
            try {
                // Merge service and admin resources into one child fixture, keeping one changelog URL.
                var resourcesRoot = java.nio.file.Path.of(getClass().getResource("/application.yaml").toURI()).getParent();
                var adminRoot = java.nio.file.Path.of(getClass().getResource("/db/changelog/db.changelog-master.yaml").toURI());
                for (int i = 0; i < 3; i++) adminRoot = adminRoot.getParent();
                for (var sourceRoot : List.of(resourcesRoot, adminRoot)) {
                    try (var files = java.nio.file.Files.walk(sourceRoot)) {
                        for (var source : files.toList()) {
                            var destination = root.resolve(sourceRoot.relativize(source));
                            if (java.nio.file.Files.isDirectory(source)) java.nio.file.Files.createDirectories(destination);
                            else java.nio.file.Files.copy(source, destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        }
                    }
                }
                var path = root.resolve("db/changelog/db.changelog-master.yaml");
                try (var in = getClass().getResourceAsStream("/db/changelog/db.changelog-master.yaml")) {
                    var master = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    java.nio.file.Files.writeString(path, master + """
                          - changeSet:
                              id: V18__future_test.sql
                              author: bot
                              changes:
                                - sql:
                                    sql: CREATE TABLE future_test_marker (id INTEGER PRIMARY KEY)
                        """);
                }
                var command = new java.util.ArrayList<String>();
                command.add(java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java").toString());
                var fixtureAdminRoot = adminRoot;
                var classpath = java.util.Arrays.stream(System.getProperty("release.test.classpath").split(java.util.regex.Pattern.quote(java.io.File.pathSeparator)))
                        .filter(entry -> !java.nio.file.Path.of(entry).equals(resourcesRoot) && !java.nio.file.Path.of(entry).equals(fixtureAdminRoot)).toList();
                command.add("-cp"); command.add(root + java.io.File.pathSeparator + String.join(java.io.File.pathSeparator, classpath));
                command.add(ReleaseProvisioningApplication.class.getName()); command.addAll(List.of(args(pg)));
                var log = root.resolve("admin.log");
                var process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
                assertTrue(process.waitFor(90, java.util.concurrent.TimeUnit.SECONDS));
                assertEquals(0, process.exitValue(), () -> {
                    try { return java.nio.file.Files.readString(log); } catch (java.io.IOException e) { return e.toString(); }
                });
                var expected = new java.util.ArrayList<>(HISTORY);
                expected.add("V17__postgres_comments.sql");
                expected.add("V18__title_denial_reason.sql");
                assertEquals(expected.stream().sorted().toList(), db.queryForList(
                        "select id from databasechangelog where id <> 'V18__future_test.sql'", String.class).stream().sorted().toList());
                assertEquals(1, db.queryForObject("select count(*) from databasechangelog where id='V18__future_test.sql'", Integer.class));
                assertEquals("future_test_marker", db.queryForObject("select to_regclass('future_test_marker')::text", String.class));
                assertEquals("legacy fixture comment", db.queryForObject("select obj_description('workspace'::regclass)", String.class));
            } finally {
                try (var files = java.nio.file.Files.walk(root)) {
                    for (var path : files.sorted(java.util.Comparator.reverseOrder()).toList()) java.nio.file.Files.deleteIfExists(path);
                }
            }
        }
    }

    @Test void rejectsUnknownAndNonPrefixLiquibaseReceipts() throws Exception {
        try (var pg = PostgresTestImage.container()) {
            pg.start(); var db = db(pg); legacy(db); markOne(pg);
            db.update("update databasechangelog set id='V3__session_schema.sql'");
            rejectedWithoutWrites(pg, db, "unrecognized Liquibase history");
            db.update("update databasechangelog set id='unrecognized'");
            rejectedWithoutWrites(pg, db, "unrecognized Liquibase history");
        }
    }
}
