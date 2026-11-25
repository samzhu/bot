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

class EndpointGuardIntegrationTests {
    @TempDir Path output;

    private JdbcTemplate db(PostgreSQLContainer pg) {
        return new JdbcTemplate(new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword()));
    }

    private int admin(PostgreSQLContainer pg, String name, String... args) throws Exception {
        var command = new ArrayList<String>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-cp"); command.add(System.getProperty("release.test.classpath"));
        command.add(ReleaseProvisioningApplication.class.getName());
        command.add("--spring.datasource.url=" + pg.getJdbcUrl());
        command.add("--spring.datasource.username=" + pg.getUsername());
        command.add("--spring.datasource.password=" + pg.getPassword());
        command.add("--management.otlp.metrics.export.enabled=false");
        command.addAll(List.of(args));
        var process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.resolve(name).toFile()).start();
        if (!process.waitFor(90, TimeUnit.SECONDS)) { process.destroyForcibly(); fail("admin timed out"); }
        return process.exitValue();
    }

    private String referenceSnapshot(JdbcTemplate jdbc) {
        var result = new StringBuilder();
        for (String table : List.of("bot", "pricing_catalog_revision", "pricing_catalog_entry", "workspace", "workspace_member", "conversation")) {
            result.append(table).append(jdbc.queryForObject("select coalesce(jsonb_agg(to_jsonb(t) order by to_jsonb(t)::text), '[]'::jsonb)::text from " + table + " t", String.class));
        }
        result.append(jdbc.queryForList("select id || '|' || coalesce(md5sum,'') from databasechangelog order by id", String.class));
        return result.toString();
    }

    @Test void sameDatabaseNameOnAnotherServerIsRejectedWithoutWrites() throws Exception {
        try (var expected = PostgresTestImage.container(); var actual = PostgresTestImage.container()) {
            expected.withDatabaseName("endpoint_guard");
            actual.withDatabaseName("endpoint_guard");
            expected.start(); actual.start();
            assertEquals(expected.getDatabaseName(), actual.getDatabaseName());
            assertNotEquals(expected.getJdbcUrl(), actual.getJdbcUrl());
            assertEquals(0, admin(expected, "expected-schema.log", "--bot.release.action=schema-only"));
            assertEquals(0, admin(actual, "actual-schema.log", "--bot.release.action=schema-only"));
            var expectedDb = db(expected); var actualDb = db(actual);
            String beforeExpected = referenceSnapshot(expectedDb), beforeActual = referenceSnapshot(actualDb);
            try (var blocker = java.sql.DriverManager.getConnection(actual.getJdbcUrl(), actual.getUsername(), actual.getPassword())) {
                blocker.setAutoCommit(false);
                try (var statement = blocker.createStatement()) { statement.execute("select pg_advisory_xact_lock(7620261124)"); }
                long start = System.nanoTime();
                int result = admin(actual, "mismatch.log", "--bot.release.action=initialize-prod",
                        "--bot.release.expected-jdbc-url=" + expected.getJdbcUrl());
                assertNotEquals(0, result, "different PostgreSQL endpoint must be refused");
                assertTrue(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start) < 30,
                        "mismatch must be rejected before waiting for the release lock");
                blocker.rollback();
            }
            String log = Files.readString(output.resolve("mismatch.log"));
            assertTrue(log.contains("Initialization PostgreSQL endpoint mismatch"));
            assertFalse(log.contains(expected.getJdbcUrl()));
            assertFalse(log.contains(actual.getJdbcUrl()));
            assertEquals(beforeExpected, referenceSnapshot(expectedDb));
            assertEquals(beforeActual, referenceSnapshot(actualDb));
            assertEquals(0, admin(actual, "match.log", "--bot.release.action=initialize-prod",
                    "--bot.release.expected-jdbc-url=" + actual.getJdbcUrl()));
            assertEquals(1, actualDb.queryForObject("select count(*) from pricing_catalog_revision", Integer.class));
            assertEquals(beforeExpected, referenceSnapshot(expectedDb));
        }
    }
    @Test void missingRepeatedAndRetiredEndpointOptionsAreRejectedBeforeWrites() throws Exception {
        try (var pg = PostgresTestImage.container()) {
            pg.start(); var jdbc = db(pg);
            var cases = List.of(
                    List.of("--bot.release.action=initialize-prod"),
                    List.of("--bot.release.action=initialize-prod", "--bot.release.expected-jdbc-url="),
                    List.of("--bot.release.action=initialize-prod", "--bot.release.expected-jdbc-url=" + pg.getJdbcUrl(), "--bot.release.expected-jdbc-url=" + pg.getJdbcUrl()),
                    List.of("--bot.release.action=initialize-prod", "--bot.release.expected-database=" + pg.getDatabaseName()),
                    List.of("--bot.release.action=schema-only", "--bot.release.expected-jdbc-url=" + pg.getJdbcUrl()));
            for (int i = 0; i < cases.size(); i++) {
                String log = "invalid-" + i + ".log";
                assertNotEquals(0, admin(pg, log, cases.get(i).toArray(String[]::new)));
                assertFalse(Files.readString(output.resolve(log)).contains(pg.getJdbcUrl()));
                assertNull(jdbc.queryForObject("select to_regclass('databasechangelog')::text", String.class));
            }
        }
    }

    @Test void secondCatalogInsertFailureRollsBackAndPreservesPriorData() throws Exception {
        try (var pg = PostgresTestImage.container()) {
            pg.start();
            assertEquals(0, admin(pg, "rollback-schema.log", "--bot.release.action=schema-only"));
            var jdbc = db(pg);
            jdbc.update("insert into bot(id,name,target,system_prompt) values ('bot-general','Customized','LOCAL','keep')");
            jdbc.update("insert into workspace(id,name,personal_owner_id) values ('prior-workspace','Prior','prior-user')");
            jdbc.update("insert into workspace_member(workspace_id,user_id,role) values ('prior-workspace','prior-user','MANAGER')");
            jdbc.update("insert into conversation(id,bot_id,title,user_id,workspace_id) values ('prior-conversation','bot-general','Prior','prior-user','prior-workspace')");
            jdbc.update("insert into pricing_catalog_revision(id,source_reference,source_revision,content_hash) values ('prior-revision','prior','prior','prior-hash')");
            jdbc.update("insert into pricing_catalog_entry(id,revision_id,provider,endpoint,model,channel,price_revision,effective_from,effective_until,rates) values ('prior-entry','prior-revision','FAKE','prior','prior','FAKE','prior','2020-01-01','2030-01-01','{\"input\":\"1\"}'::jsonb)");
            jdbc.execute("create sequence fixture_entry_attempt");
            jdbc.execute("""
                create function fixture_fail_second_catalog_entry() returns trigger language plpgsql as $$
                begin
                    perform nextval('fixture_entry_attempt');
                    if new.price_revision = 'gemini-3.8-flash-standard-2027' then
                        raise exception 'fixture-second-catalog-row-failure';
                    end if;
                    return new;
                end $$
                """);
            jdbc.execute("create trigger fixture_fail_second before insert on pricing_catalog_entry for each row execute function fixture_fail_second_catalog_entry()");
            String before = referenceSnapshot(jdbc);
            assertNotEquals(0, admin(pg, "rollback-initialize.log", "--bot.release.action=initialize-prod",
                    "--bot.release.expected-jdbc-url=" + pg.getJdbcUrl()));
            assertTrue(Files.readString(output.resolve("rollback-initialize.log")).contains("fixture-second-catalog-row-failure"));
            assertEquals(2L, jdbc.queryForObject("select last_value from fixture_entry_attempt", Long.class),
                    "sequence proves first and second insert reached the trigger");
            assertEquals(before, referenceSnapshot(jdbc));
        }
    }

}
