package io.github.samzhu.bot;

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

class AdminArchiveIntegrationTests {
    @TempDir Path temporary;
    private void admin(PostgreSQLContainer pg, String name, String... args) throws Exception {
        var command = new ArrayList<String>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-jar"); command.add(System.getProperty("release.admin.archive"));
        command.add("--spring.datasource.url=" + pg.getJdbcUrl());
        command.add("--spring.datasource.username=" + pg.getUsername());
        command.add("--spring.datasource.password=" + pg.getPassword());
        command.add("--management.otlp.metrics.export.enabled=false"); command.addAll(List.of(args));
        var log = temporary.resolve(name);
        var process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        if (!process.waitFor(90, TimeUnit.SECONDS)) { process.destroyForcibly(); fail("admin archive timed out"); }
        assertEquals(0, process.exitValue(), () -> {
            try { return Files.readString(log); } catch (java.io.IOException e) { return e.toString(); }
        });
    }
    @Test void standaloneAdminArchiveMigratesThenInitializes() throws Exception {
        try (var pg = PostgresTestImage.container()) {
            pg.start();
            var db = new JdbcTemplate(new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword()));
            admin(pg, "schema.log", "--bot.release.action=schema-only");
            PostgresCommentsIntegrationTests.assertReleaseReceipts(db);
            assertEquals(0, db.queryForObject("select count(*) from bot", Integer.class));
            assertEquals(0, db.queryForObject("select count(*) from pricing_catalog_revision", Integer.class));
            admin(pg, "init.log", "--bot.release.action=initialize-prod", "--bot.release.expected-jdbc-url=" + pg.getJdbcUrl());
            assertEquals(1, db.queryForObject("select count(*) from bot where id='bot-general'", Integer.class));
            assertEquals(1, db.queryForObject("select count(*) from pricing_catalog_revision where source_revision='gemini-standard-2026-11-16'", Integer.class));
            assertEquals(0, db.queryForObject("select count(*) from pricing_catalog_entry where model='gpt-5.4-mini'", Integer.class));
        }
    }
}
