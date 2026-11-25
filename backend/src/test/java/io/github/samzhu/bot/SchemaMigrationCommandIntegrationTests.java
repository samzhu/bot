package io.github.samzhu.bot;

import io.github.samzhu.bot.bootstrap.ReleaseProvisioningApplication;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class SchemaMigrationCommandIntegrationTests {
    @TempDir Path output;

    private JdbcTemplate jdbc(PostgreSQLContainer pg) {
        return new JdbcTemplate(new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword()));
    }

    private Process admin(PostgreSQLContainer pg, String log, String... actions) throws Exception {
        var command = new ArrayList<String>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-cp"); command.add(System.getProperty("release.test.classpath"));
        command.add(ReleaseProvisioningApplication.class.getName());
        command.add("--spring.datasource.url=" + pg.getJdbcUrl());
        command.add("--spring.datasource.username=" + pg.getUsername());
        command.add("--spring.datasource.password=" + pg.getPassword());
        command.add("--management.otlp.metrics.export.enabled=false");
        command.addAll(List.of(actions));
        return new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.resolve(log).toFile()).start();
    }

    private String finish(Process process, String log, boolean succeeds) throws Exception {
        if (!process.waitFor(90, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            fail("admin process timed out: " + log);
        }
        String text = Files.readString(output.resolve(log));
        if (succeeds) assertEquals(0, process.exitValue(), text);
        else assertNotEquals(0, process.exitValue(), text);
        return text;
    }

    private void assertEmptyReferenceData(JdbcTemplate db) {
        for (String table : List.of("bot", "pricing_catalog_revision", "pricing_catalog_entry"))
            assertEquals(0, db.queryForObject("select count(*) from " + table, Integer.class), table);
    }

    private String snapshot(JdbcTemplate db) {
        var result = new StringBuilder(db.queryForList("""
                select c.relname || ':' || c.relkind::text || ':' || coalesce(obj_description(c.oid), '')
                  from pg_class c join pg_namespace n on n.oid=c.relnamespace
                 where n.nspname=current_schema() order by 1
                """, String.class).toString());
        result.append(db.queryForList("""
                select c.conname || ':' || pg_get_constraintdef(c.oid) from pg_constraint c
                  join pg_namespace n on n.oid=c.connamespace where n.nspname=current_schema() order by 1
                """, String.class));
        for (String table : db.queryForList("select tablename from pg_tables where schemaname=current_schema() order by 1", String.class)) {
            String quoted = "\"" + table.replace("\"", "\"\"") + "\"";
            result.append(table).append(db.queryForObject("select coalesce(jsonb_agg(to_jsonb(t) order by to_jsonb(t)::text),'[]'::jsonb)::text from " + quoted + " t", String.class));
        }
        return result.toString();
    }

    @Test
    void schemaOnlyCommandMigratesWithoutPublishingReferenceData() throws Exception {
        try (var pg = PostgresTestImage.container()) {
            pg.start();
            finish(admin(pg, "schema-admin.log", "--bot.release.action=schema-only"), "schema-admin.log", true);
            var db = jdbc(pg);
            PostgresCommentsIntegrationTests.assertReleaseReceipts(db);
            assertEmptyReferenceData(db);
            assertEquals("一次使用者請求從發起至完成、失敗或取消的完整 Run 生命週期；可包含多筆 ProviderAttempt。",
                    db.queryForObject("select obj_description('chat_run'::regclass)", String.class));
            var before = snapshot(db);
            finish(admin(pg, "schema-repeat.log", "--bot.release.action=schema-only"), "schema-repeat.log", true);
            assertEquals(before, snapshot(db));
            PostgresCommentsIntegrationTests.assertReleaseReceipts(db);
            assertEmptyReferenceData(db);
        }
    }

    @Test
    void rejectedActionNeverWritesEvenOnEmptyDatabase() throws Exception {
        try (var pg = PostgresTestImage.container()) {
            pg.start(); var db = jdbc(pg); String before = snapshot(db);
            List<List<String>> invalid = List.of(List.of("--bot.release.action=unknown"),
                    List.of("--bot.release.action="), List.of("--bot.release.action"),
                    List.of("--bot.release.action=schema-only", "--bot.release.action=schema-only"),
                    List.of("--bot.release.action", "--bot.release.action=schema-only"));
            for (int i = 0; i < invalid.size(); i++) {
                String log = "bad-action-" + i + ".log";
                String text = finish(admin(pg, log, invalid.get(i).toArray(String[]::new)), log, false);
                assertTrue(text.contains("bot.release.action"), text);
                assertEquals(before, snapshot(db));
            }
        }
    }

    @Test
    void resumesVerifiedPrefixWithoutPublishingReferenceData() throws Exception {
        try (var pg = PostgresTestImage.container()) {
            pg.start();
            try (var connection = java.sql.DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
                 var resources = new liquibase.resource.ClassLoaderResourceAccessor();
                 var lb = new liquibase.Liquibase("db/changelog/db.changelog-master.yaml", resources,
                         new liquibase.database.jvm.JdbcConnection(connection))) {
                lb.update(14, new liquibase.Contexts(), new liquibase.LabelExpression());
            }
            finish(admin(pg, "prefix.log", "--bot.release.action=schema-only"), "prefix.log", true);
            var db = jdbc(pg);
            PostgresCommentsIntegrationTests.assertReleaseReceipts(db);
            assertEmptyReferenceData(db);
        }
    }

    @Test
    void rejectsUnknownAndCorruptHistoryWithoutWrites() throws Exception {
        try (var pg = PostgresTestImage.container()) {
            pg.start(); var db = jdbc(pg);
            finish(admin(pg, "first.log", "--bot.release.action=schema-only"), "first.log", true);
            db.update("update databasechangelog set md5sum='9:00000000000000000000000000000000' where id='V1__initial_schema.sql'");
            String before = snapshot(db);
            String text = finish(admin(pg, "bad-checksum.log", "--bot.release.action=schema-only"), "bad-checksum.log", false);
            assertTrue(text.contains("unrecognized Liquibase history"), text);
            assertEquals(before, snapshot(db));
            db.update("update databasechangelog set id='unexpected' where id='V1__initial_schema.sql'");
            before = snapshot(db);
            text = finish(admin(pg, "bad-identity.log", "--bot.release.action=schema-only"), "bad-identity.log", false);
            assertTrue(text.contains("unrecognized Liquibase history"), text);
            assertEquals(before, snapshot(db));
        }
    }

    @Test
    void schemaCommandWaitsForSharedReleaseAdvisoryLock() throws Exception {
        try (var pg = PostgresTestImage.container()) {
            pg.start(); var db = jdbc(pg);
            try (var blocker = java.sql.DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())) {
                blocker.setAutoCommit(false);
                int pid;
                try (var stmt = blocker.createStatement(); var rs = stmt.executeQuery("select pg_backend_pid()")) { rs.next(); pid = rs.getInt(1); }
                try (var stmt = blocker.createStatement()) { stmt.execute("select pg_advisory_xact_lock(7620261124)"); }
                var process = admin(pg, "blocked.log", "--bot.release.action=schema-only");
                try {
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
                    boolean waiting = false;
                    while (System.nanoTime() < deadline && !waiting && process.isAlive()) {
                        waiting = Boolean.TRUE.equals(db.queryForObject(
                                "select exists(select 1 from pg_stat_activity where ?=any(pg_blocking_pids(pid)))", Boolean.class, pid));
                        if (!waiting) Thread.sleep(50);
                    }
                    assertTrue(waiting, "schema command must wait on the release lock");
                    assertNull(db.queryForObject("select to_regclass('databasechangelog')::text", String.class));
                    blocker.rollback();
                    finish(process, "blocked.log", true);
                    PostgresCommentsIntegrationTests.assertReleaseReceipts(db);
                    assertEmptyReferenceData(db);
                } finally { if (process.isAlive()) process.destroyForcibly(); }
            }
        }
    }
}
