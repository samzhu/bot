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

class EnvironmentSqlIntegrationTests {
    @TempDir Path output;

    private void admin(PostgreSQLContainer pg, String log, String... args) throws Exception {
        var command = new ArrayList<String>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-cp"); command.add(System.getProperty("release.test.classpath"));
        command.add(ReleaseProvisioningApplication.class.getName());
        command.add("--spring.datasource.url=" + pg.getJdbcUrl());
        command.add("--spring.datasource.username=" + pg.getUsername());
        command.add("--spring.datasource.password=" + pg.getPassword());
        command.add("--management.otlp.metrics.export.enabled=false");
        command.addAll(List.of(args));
        var process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.resolve(log).toFile()).start();
        if (!process.waitFor(90, TimeUnit.SECONDS)) { process.destroyForcibly(); fail("admin timed out: " + log); }
        assertEquals(0, process.exitValue(), () -> {
            try { return Files.readString(output.resolve(log)); } catch (java.io.IOException e) { return e.toString(); }
        });
    }

    @Test void productionInitializationIsExplicitAndFollowsSchemaMigration() throws Exception {
        try (var pg = PostgresTestImage.container()) {
            pg.start();
            var jdbc = new JdbcTemplate(new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword()));
            admin(pg, "schema.log", "--bot.release.action=schema-only");
            PostgresCommentsIntegrationTests.assertReleaseReceipts(jdbc);
            assertEquals(0, jdbc.queryForObject("select count(*) from bot", Integer.class));
            assertEquals(0, jdbc.queryForObject("select count(*) from pricing_catalog_revision", Integer.class));
            admin(pg, "initialize-prod.log", "--bot.release.action=initialize-prod",
                    "--bot.release.expected-jdbc-url=" + pg.getJdbcUrl());
            assertEquals(1, jdbc.queryForObject("select count(*) from bot where id='bot-general' and target='LOCAL'", Integer.class));
            assertEquals(1, jdbc.queryForObject("select count(*) from pricing_catalog_revision where source_revision='gemini-standard-2026-11-16'", Integer.class));
            assertEquals(0, jdbc.queryForObject("select count(*) from pricing_catalog_entry where provider='OPENAI' and model='gpt-5.4-mini'", Integer.class));
            PostgresCommentsIntegrationTests.assertReleaseReceipts(jdbc);
            String revision = jdbc.queryForObject("select id from pricing_catalog_revision", String.class);
            jdbc.update("update bot set name='Customized' where id='bot-general'");
            admin(pg, "repeat.log", "--bot.release.action=initialize-prod",
                    "--bot.release.expected-jdbc-url=" + pg.getJdbcUrl());
            assertEquals("Customized", jdbc.queryForObject("select name from bot where id='bot-general'", String.class));
            assertEquals(revision, jdbc.queryForObject("select id from pricing_catalog_revision", String.class));
            assertEquals(1, jdbc.queryForObject("select count(*) from pricing_catalog_revision", Integer.class));
        }
    }
    @Test void rejectsWrongTargetAndRollsBackRevisionConflict() throws Exception {
        try (var pg = PostgresTestImage.container()) {
            pg.start();
            var jdbc = new JdbcTemplate(new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword()));
            rejected(pg, "missing-schema.log", "--bot.release.action=initialize-prod", "--bot.release.expected-jdbc-url=" + pg.getJdbcUrl());
            assertNull(jdbc.queryForObject("select to_regclass('databasechangelog')::text", String.class));
            admin(pg, "schema.log", "--bot.release.action=schema-only");
            rejected(pg, "wrong-profile.log", "--spring.profiles.active=dev", "--bot.release.action=initialize-prod", "--bot.release.expected-jdbc-url=" + pg.getJdbcUrl());
            rejected(pg, "wrong-target.log", "--bot.release.action=initialize-prod", "--bot.release.expected-jdbc-url=not-the-target");
            assertEquals(0, jdbc.queryForObject("select count(*) from bot", Integer.class));
            assertEquals(0, jdbc.queryForObject("select count(*) from pricing_catalog_revision", Integer.class));
            jdbc.update("insert into pricing_catalog_revision(id,source_reference,source_revision,content_hash) values ('conflict','fixture','gemini-standard-2026-11-16','bad')");
            rejected(pg, "conflict.log", "--bot.release.action=initialize-prod", "--bot.release.expected-jdbc-url=" + pg.getJdbcUrl());
            assertEquals(0, jdbc.queryForObject("select count(*) from bot", Integer.class));
            assertEquals(1, jdbc.queryForObject("select count(*) from pricing_catalog_revision", Integer.class));
            assertEquals(0, jdbc.queryForObject("select count(*) from pricing_catalog_entry", Integer.class));
        }
    }

    @Test void sqlFailureDoesNotPublishReferenceData() throws Exception {
        try (var pg = PostgresTestImage.container()) {
            pg.start();
            var jdbc = new JdbcTemplate(new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword()));
            admin(pg, "schema.log", "--bot.release.action=schema-only");
            var resource = output.resolve("override/db/initialization/prod.sql");
            Files.createDirectories(resource.getParent());
            try (var input = getClass().getResourceAsStream("/db/initialization/prod.sql")) {
                assertNotNull(input);
                String sql = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                // Valid JSON for Jackson, but PostgreSQL jsonb rejects U+0000 during INSERT.
                Files.writeString(resource, sql.replace("General Assistant", "General \\u0000 Assistant"));
            }
            var command = new ArrayList<String>();
            command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
            command.add("-cp"); command.add(output.resolve("override") + java.io.File.pathSeparator + System.getProperty("release.test.classpath"));
            command.add(ReleaseProvisioningApplication.class.getName());
            command.add("--spring.datasource.url=" + pg.getJdbcUrl());
            command.add("--spring.datasource.username=" + pg.getUsername());
            command.add("--spring.datasource.password=" + pg.getPassword());
            command.add("--bot.release.action=initialize-prod");
            command.add("--bot.release.expected-jdbc-url=" + pg.getJdbcUrl());
            var process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.resolve("sql-failure.log").toFile()).start();
            if (!process.waitFor(90, TimeUnit.SECONDS)) { process.destroyForcibly(); fail("admin timed out"); }
            assertNotEquals(0, process.exitValue());
            assertTrue(Files.readString(output.resolve("sql-failure.log")).contains("unsupported Unicode escape sequence"));
            assertEquals(0, jdbc.queryForObject("select count(*) from bot", Integer.class));
            assertEquals(0, jdbc.queryForObject("select count(*) from pricing_catalog_revision", Integer.class));
            assertEquals(0, jdbc.queryForObject("select count(*) from pricing_catalog_entry", Integer.class));
            PostgresCommentsIntegrationTests.assertReleaseReceipts(jdbc);
        }
    }

    @Test void initializationEnvironmentMismatchDoesNotPublish() throws Exception {
        try (var pg = PostgresTestImage.container()) {
            pg.start();
            admin(pg, "mismatch-schema.log", "--bot.release.action=schema-only");
            var resource = output.resolve("mismatch/db/initialization/prod.sql");
            Files.createDirectories(resource.getParent());
            try (var input = getClass().getResourceAsStream("/db/initialization/prod.sql")) {
                assertNotNull(input);
                Files.writeString(resource, new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
                        .replace("\"environment\": \"prod\"", "\"environment\": \"dev\""));
            }
            var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-cp", output.resolve("mismatch") + java.io.File.pathSeparator + System.getProperty("release.test.classpath"),
                    ReleaseProvisioningApplication.class.getName(), "--spring.datasource.url=" + pg.getJdbcUrl(),
                    "--spring.datasource.username=" + pg.getUsername(), "--spring.datasource.password=" + pg.getPassword(),
                    "--bot.release.action=initialize-prod", "--bot.release.expected-jdbc-url=" + pg.getJdbcUrl())
                    .redirectErrorStream(true).redirectOutput(output.resolve("mismatch.log").toFile()).start();
            if (!process.waitFor(90, TimeUnit.SECONDS)) { process.destroyForcibly(); fail("admin timed out"); }
            assertNotEquals(0, process.exitValue());
            assertTrue(Files.readString(output.resolve("mismatch.log")).contains("Initialization environment mismatch"));
            var jdbc = new JdbcTemplate(new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword()));
            assertEquals(0, jdbc.queryForObject("select count(*) from bot", Integer.class));
            assertEquals(0, jdbc.queryForObject("select count(*) from pricing_catalog_revision", Integer.class));
        }
    }

    @Test void invalidCatalogPayloadsFailBeforePublishing() throws Exception {
        try (var pg = PostgresTestImage.container()) {
            pg.start();
            var jdbc = new JdbcTemplate(new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword()));
            admin(pg, "validation-schema.log", "--bot.release.action=schema-only");
            for (String failure : List.of("negative-rate", "missing-metadata", "overlap", "missing-rate",
                    "numeric-rate", "unknown-metadata", "empty-selection", "duplicate-selection", "unknown-environment")) {
                var payload = productionPayload();
                var entries = (com.fasterxml.jackson.databind.node.ObjectNode) payload.path("entries");
                var first = (com.fasterxml.jackson.databind.node.ObjectNode) entries.path("gemini-2026");
                switch (failure) {
                    case "negative-rate" -> ((com.fasterxml.jackson.databind.node.ObjectNode) first.path("rates")).put("input", "-1");
                    case "missing-metadata" -> first.remove("metadata");
                    case "overlap" -> ((com.fasterxml.jackson.databind.node.ObjectNode) entries.path("gemini-2027")).put("fromInclusive", "2026-12-01T00:00:00Z");
                    case "missing-rate" -> ((com.fasterxml.jackson.databind.node.ObjectNode) first.path("rates")).remove("output");
                    case "numeric-rate" -> ((com.fasterxml.jackson.databind.node.ObjectNode) first.path("rates")).put("input", 0.75);
                    case "unknown-metadata" -> ((com.fasterxml.jackson.databind.node.ObjectNode) first.path("metadata")).put("servicetier", "STANDARD");
                    case "empty-selection" -> ((com.fasterxml.jackson.databind.node.ObjectNode) payload.path("environments").path("prod")).putArray("entries");
                    case "duplicate-selection" -> ((com.fasterxml.jackson.databind.node.ArrayNode) payload.path("environments").path("prod").path("entries")).add("gemini-2026");
                    case "unknown-environment" -> ((com.fasterxml.jackson.databind.node.ObjectNode) payload.path("environments")).putObject("production");
                    default -> throw new AssertionError(failure);
                }
                String expected = switch (failure) {
                    case "negative-rate" -> "Invalid catalog rate";
                    case "missing-metadata" -> "Invalid catalog metadata";
                    case "overlap" -> "Overlapping catalog entries";
                    case "missing-rate" -> "Missing required catalog rate";
                    case "numeric-rate" -> "Invalid catalog rate";
                    case "unknown-metadata", "unknown-environment" -> "Invalid catalog fields";
                    default -> "Invalid catalog selection";
                };
                assertTrue(adminWithPayload(pg, failure, payload).contains(expected), failure);
                assertEquals(0, jdbc.queryForObject("select count(*) from bot", Integer.class));
                assertEquals(0, jdbc.queryForObject("select count(*) from pricing_catalog_revision", Integer.class));
                assertEquals(0, jdbc.queryForObject("select count(*) from pricing_catalog_entry", Integer.class));
            }
        }
    }

    @Test void changedRateOrMetadataCannotReplacePublishedRevision() throws Exception {
        try (var pg = PostgresTestImage.container()) {
            pg.start();
            var jdbc = new JdbcTemplate(new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword()));
            admin(pg, "conflict-schema.log", "--bot.release.action=schema-only");
            admin(pg, "conflict-initial.log", "--bot.release.action=initialize-prod", "--bot.release.expected-jdbc-url=" + pg.getJdbcUrl());
            String id = jdbc.queryForObject("select id from pricing_catalog_revision", String.class);
            String before = jdbc.queryForObject("select jsonb_agg(to_jsonb(e) order by e.price_revision)::text from pricing_catalog_entry e", String.class);
            for (String change : List.of("rate", "metadata")) {
                var payload = productionPayload();
                var first = payload.path("entries").path("gemini-2026");
                if (change.equals("rate")) ((com.fasterxml.jackson.databind.node.ObjectNode) first.path("rates")).put("input", "9");
                else ((com.fasterxml.jackson.databind.node.ObjectNode) first.path("metadata")).put("storageRate", "0.51");
                assertTrue(adminWithPayload(pg, "changed-" + change, payload).contains("Catalog revision payload conflict"));
                assertEquals(id, jdbc.queryForObject("select id from pricing_catalog_revision", String.class));
                assertEquals(before, jdbc.queryForObject("select jsonb_agg(to_jsonb(e) order by e.price_revision)::text from pricing_catalog_entry e", String.class));
            }
        }
    }

    @Test void duplicateYamlKeysAreRejectedBeforeWrites() throws Exception {
        try (var pg = PostgresTestImage.container()) {
            pg.start();
            var jdbc = new JdbcTemplate(new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword()));
            admin(pg, "duplicate-schema.log", "--bot.release.action=schema-only");
            try (var input = getClass().getResourceAsStream("/pricing/catalog.yaml")) {
                assertNotNull(input);
                String yaml = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                assertTrue(adminWithYaml(pg, "duplicate", yaml.replace("  prod:\n", "  prod:\n    revision: duplicate\n")).contains("duplicate key"));
            }
            assertEquals(0, jdbc.queryForObject("select count(*) from bot", Integer.class));
            assertEquals(0, jdbc.queryForObject("select count(*) from pricing_catalog_revision", Integer.class));
        }
    }

    @Test void reorderingYamlObjectsKeepsPublishedCatalogUnchanged() throws Exception {
        try (var pg = PostgresTestImage.container()) {
            pg.start();
            admin(pg, "reorder-schema.log", "--bot.release.action=schema-only");
            admin(pg, "reorder-original.log", "--bot.release.action=initialize-prod", "--bot.release.expected-jdbc-url=" + pg.getJdbcUrl());
            var jdbc = new JdbcTemplate(new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword()));
            String before = jdbc.queryForObject("select jsonb_agg(to_jsonb(e) order by e.price_revision)::text from pricing_catalog_entry e", String.class);
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            mapper.enable(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
            Object reordered = mapper.convertValue(productionPayload(), Object.class);
            adminWithYaml(pg, "reordered", mapper.writeValueAsString(reordered), true);
            assertEquals(1, jdbc.queryForObject("select count(*) from pricing_catalog_revision", Integer.class));
            assertEquals(before, jdbc.queryForObject("select jsonb_agg(to_jsonb(e) order by e.price_revision)::text from pricing_catalog_entry e", String.class));
        }
    }

    private com.fasterxml.jackson.databind.node.ObjectNode productionPayload() throws Exception {
        try (var input = getClass().getResourceAsStream("/pricing/catalog.yaml")) {
            assertNotNull(input);
            var options = new org.yaml.snakeyaml.LoaderOptions();
            Object data = new org.yaml.snakeyaml.Yaml(new org.yaml.snakeyaml.constructor.SafeConstructor(options)).load(input);
            return new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(data);
        }
    }

    private String adminWithPayload(PostgreSQLContainer pg, String name, com.fasterxml.jackson.databind.node.ObjectNode payload) throws Exception {
        return adminWithYaml(pg, name, new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(payload));
    }

    private String adminWithYaml(PostgreSQLContainer pg, String name, String yaml) throws Exception {
        return adminWithYaml(pg, name, yaml, false);
    }

    private String adminWithYaml(PostgreSQLContainer pg, String name, String yaml, boolean succeeds) throws Exception {
        var directory = output.resolve(name);
        var resource = directory.resolve("pricing/catalog.yaml");
        Files.createDirectories(resource.getParent());
        Files.writeString(resource, yaml);
        var command = new ArrayList<String>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-cp"); command.add(directory + java.io.File.pathSeparator + System.getProperty("release.test.classpath"));
        command.add(ReleaseProvisioningApplication.class.getName());
        command.add("--spring.datasource.url=" + pg.getJdbcUrl());
        command.add("--spring.datasource.username=" + pg.getUsername());
        command.add("--spring.datasource.password=" + pg.getPassword());
        command.add("--bot.release.action=initialize-prod");
        command.add("--bot.release.expected-jdbc-url=" + pg.getJdbcUrl());
        Path log = output.resolve(name + ".log");
        var process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        if (!process.waitFor(90, TimeUnit.SECONDS)) { process.destroyForcibly(); fail("admin timed out: " + name); }
        String text = Files.readString(log);
        if (succeeds) assertEquals(0, process.exitValue(), text);
        else assertNotEquals(0, process.exitValue(), text);
        return text;
    }

    @Test void retiredCatalogConfigurationIsRejectedBeforeAdminWrites() throws Exception {
        try (var pg = PostgresTestImage.container()) {
            pg.start();
            var jdbc = new JdbcTemplate(new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword()));
            String log = rejected(pg, "retired.log", "--bot.release.action=schema-only", "--bot.pricing.catalog.unknown=value");
            assertTrue(log.contains("bot.pricing.catalog configuration is retired"), log);
            assertNull(jdbc.queryForObject("select to_regclass('databasechangelog')::text", String.class));
        }
    }

    private String rejected(PostgreSQLContainer pg, String log, String... args) throws Exception {
        var command = new ArrayList<String>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-cp"); command.add(System.getProperty("release.test.classpath"));
        command.add(ReleaseProvisioningApplication.class.getName());
        command.add("--spring.datasource.url=" + pg.getJdbcUrl());
        command.add("--spring.datasource.username=" + pg.getUsername());
        command.add("--spring.datasource.password=" + pg.getPassword());
        command.addAll(List.of(args));
        var process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.resolve(log).toFile()).start();
        if (!process.waitFor(90, TimeUnit.SECONDS)) { process.destroyForcibly(); fail("admin timed out: " + log); }
        assertNotEquals(0, process.exitValue());
        return Files.readString(output.resolve(log));
    }

}
