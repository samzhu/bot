package io.github.samzhu.bot;

import io.github.samzhu.bot.bootstrap.ReleaseProvisioningApplication;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.junit.jupiter.api.Assertions.*;

class ReleaseProvisioningIntegrationTests {
    @Test
    void administrationArchiveContainsTheSharedYamlCatalog() throws Exception {
        try (var archive = new java.util.jar.JarFile(System.getProperty("release.admin.archive"))) {
            assertNotNull(archive.getEntry("BOOT-INF/classes/pricing/catalog.yaml"));
        }
    }

    @Test
    void initializationSqlContainsOnlyBotData() throws Exception {
        for (String environment : java.util.List.of("dev", "prod")) {
            try (var input = getClass().getResourceAsStream("/db/initialization/" + environment + ".sql")) {
                assertNotNull(input);
                String sql = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                assertFalse(sql.contains("\"catalog\""), environment + " must use the shared YAML catalog");
            }
        }
    }

    @Test
    void migratedCatalogMetadataPreservesBothPublishedHashes() throws Exception {
        var expected = java.util.Map.of(
                "prod", "0a0e4a13103ea27bffdee2a02623f42764a442f90953b45be40cceffd04db9ec",
                "dev", "1135ff8e3c4dcdfc95249eb785eefc8fc0fabc0f7e4bc91e01cb56dd164cad03");
        for (var environment : expected.keySet()) {
            try (var input = getClass().getResourceAsStream("/release/" + environment + ".json")) {
                assertNotNull(input);
                assertEquals(expected.get(environment), new com.fasterxml.jackson.databind.ObjectMapper().readTree(input).path("contentHash").asText());
            }
        }
    }

    @Test
    void productionReleasePublishesTheSelectedYamlCatalog(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        try (var postgres = PostgresTestImage.container()) {
            postgres.start();
            String[] database = {"--spring.datasource.url=" + postgres.getJdbcUrl(),
                    "--spring.datasource.username=" + postgres.getUsername(),
                    "--spring.datasource.password=" + postgres.getPassword()};
            ReleaseProvisioningApplication.main(new String[]{database[0], database[1], database[2], "--bot.release.action=schema-only"});
            var yaml = directory.resolve("pricing/catalog.yaml");
            java.nio.file.Files.createDirectories(yaml.getParent());
            try (var input = getClass().getResourceAsStream("/pricing/catalog.yaml")) {
                assertNotNull(input);
                String original = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                java.nio.file.Files.writeString(yaml, original.replace("gemini-standard-2026-11-16", "fixture-yaml-revision")
                        .replace("input: '0.75'", "input: '0.76'"));
            }
            var log = directory.resolve("admin.log");
            var process = new ProcessBuilder(java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-cp", directory + java.io.File.pathSeparator + System.getProperty("release.test.classpath"),
                    ReleaseProvisioningApplication.class.getName(), database[0], database[1], database[2],
                    "--bot.release.action=initialize-prod", "--bot.release.expected-jdbc-url=" + postgres.getJdbcUrl())
                    .redirectErrorStream(true).redirectOutput(log.toFile()).start();
            assertTrue(process.waitFor(90, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(0, process.exitValue(), () -> "Admin release failed: " + log);
            var jdbc = new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
            assertEquals(1, jdbc.queryForObject("select count(*) from pricing_catalog_revision where source_revision='fixture-yaml-revision'", Integer.class));
            assertEquals("0.76", jdbc.queryForObject("select rates->>'input' from pricing_catalog_entry e join pricing_catalog_revision r on r.id=e.revision_id where r.source_revision='fixture-yaml-revision' and e.price_revision='gemini-3.8-flash-standard-2026'", String.class));
        }
    }

    @Test
    void productionReleaseRejectsUnknownYamlFieldsBeforeWriting(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        try (var postgres = PostgresTestImage.container()) {
            postgres.start();
            String[] database = {"--spring.datasource.url=" + postgres.getJdbcUrl(),
                    "--spring.datasource.username=" + postgres.getUsername(),
                    "--spring.datasource.password=" + postgres.getPassword()};
            ReleaseProvisioningApplication.main(new String[]{database[0], database[1], database[2], "--bot.release.action=schema-only"});
            var yaml = directory.resolve("pricing/catalog.yaml");
            java.nio.file.Files.createDirectories(yaml.getParent());
            try (var input = getClass().getResourceAsStream("/pricing/catalog.yaml")) {
                assertNotNull(input);
                java.nio.file.Files.writeString(yaml, "unexpected: true\n" + new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            }
            var process = new ProcessBuilder(java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-cp", directory + java.io.File.pathSeparator + System.getProperty("release.test.classpath"),
                    ReleaseProvisioningApplication.class.getName(), database[0], database[1], database[2],
                    "--bot.release.action=initialize-prod", "--bot.release.expected-jdbc-url=" + postgres.getJdbcUrl())
                    .redirectErrorStream(true).redirectOutput(directory.resolve("invalid-admin.log").toFile()).start();
            assertTrue(process.waitFor(90, java.util.concurrent.TimeUnit.SECONDS));
            assertNotEquals(0, process.exitValue(), "Unknown YAML fields must reject the release");
            var jdbc = new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
            assertEquals(0, jdbc.queryForObject("select count(*) from bot", Integer.class));
            assertEquals(0, jdbc.queryForObject("select count(*) from pricing_catalog_revision", Integer.class));
        }
    }

    @Test
    void productionReleaseRejectsAnyPriceWithoutRequiredMetadata(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        rejectPriceWithoutMetadata(directory, "serviceTier");
    }

    @Test
    void productionReleaseRejectsAnyPriceWithoutMetadata(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        rejectPriceWithoutMetadata(directory, "metadata");
    }

    @Test
    void productionReleaseRejectsPriceWithoutUnit(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        rejectPriceWithoutMetadata(directory, "tokenUnit");
    }

    @Test
    void productionReleaseRejectsPriceWithoutSources(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        rejectPriceWithoutMetadata(directory, "sources");
    }

    @Test
    void productionReleaseRejectsPriceWithoutVerificationDate(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        rejectPriceWithoutMetadata(directory, "verifiedDate");
    }

    private void rejectPriceWithoutMetadata(java.nio.file.Path directory, String missing) throws Exception {
        try (var postgres = PostgresTestImage.container()) {
            postgres.start();
            String[] database = {"--spring.datasource.url=" + postgres.getJdbcUrl(),
                    "--spring.datasource.username=" + postgres.getUsername(),
                    "--spring.datasource.password=" + postgres.getPassword()};
            ReleaseProvisioningApplication.main(new String[]{database[0], database[1], database[2], "--bot.release.action=schema-only"});
            var yaml = directory.resolve("pricing/catalog.yaml");
            java.nio.file.Files.createDirectories(yaml.getParent());
            try (var input = getClass().getResourceAsStream("/pricing/catalog.yaml")) {
                assertNotNull(input);
                Object data = new org.yaml.snakeyaml.Yaml(new org.yaml.snakeyaml.constructor.SafeConstructor(new org.yaml.snakeyaml.LoaderOptions())).load(input);
                var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                var catalog = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.valueToTree(data);
                var price = (com.fasterxml.jackson.databind.node.ObjectNode) catalog.path("entries").path("openai-2026");
                if (missing.equals("metadata")) price.remove("metadata");
                else ((com.fasterxml.jackson.databind.node.ObjectNode) price.path("metadata")).remove(missing);
                ((com.fasterxml.jackson.databind.node.ObjectNode) catalog.path("environments").path("prod")).putArray("entries").add("openai-2026");
                java.nio.file.Files.writeString(yaml, mapper.writeValueAsString(catalog));
            }
            var process = new ProcessBuilder(java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-cp", directory + java.io.File.pathSeparator + System.getProperty("release.test.classpath"),
                    ReleaseProvisioningApplication.class.getName(), database[0], database[1], database[2],
                    "--bot.release.action=initialize-prod", "--bot.release.expected-jdbc-url=" + postgres.getJdbcUrl())
                    .redirectErrorStream(true).redirectOutput(directory.resolve("metadata-admin.log").toFile()).start();
            assertTrue(process.waitFor(90, java.util.concurrent.TimeUnit.SECONDS));
            assertNotEquals(0, process.exitValue(), "Every price requires metadata");
            var jdbc = new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
            assertEquals(0, jdbc.queryForObject("select count(*) from bot", Integer.class));
            assertEquals(0, jdbc.queryForObject("select count(*) from pricing_catalog_revision", Integer.class));
        }
    }

    @Test
    void releaseCommandsPrepareAnEmptyDatabaseAndRepeatWithoutDuplicates() throws Exception {
        try (var postgres = PostgresTestImage.container()) {
            postgres.start();
            String[] schema = {"--spring.datasource.url=" + postgres.getJdbcUrl(),
                    "--spring.datasource.username=" + postgres.getUsername(),
                    "--spring.datasource.password=" + postgres.getPassword(), "--bot.release.action=schema-only"};
            String[] initialize = {"--spring.datasource.url=" + postgres.getJdbcUrl(),
                    "--spring.datasource.username=" + postgres.getUsername(),
                    "--spring.datasource.password=" + postgres.getPassword(), "--bot.release.action=initialize-prod",
                    "--bot.release.expected-jdbc-url=" + postgres.getJdbcUrl()};
            ReleaseProvisioningApplication.main(schema);
            ReleaseProvisioningApplication.main(initialize);
            var jdbc = new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
            PostgresCommentsIntegrationTests.assertReleaseReceipts(jdbc);
            String id = jdbc.queryForObject("select id from pricing_catalog_revision where source_revision='gemini-standard-2026-11-16'", String.class);
            assertEquals("0a0e4a13103ea27bffdee2a02623f42764a442f90953b45be40cceffd04db9ec",
                    jdbc.queryForObject("select content_hash from pricing_catalog_revision where id=?", String.class, id));
            ReleaseProvisioningApplication.main(schema);
            ReleaseProvisioningApplication.main(initialize);
            assertEquals(1, jdbc.queryForObject("select count(*) from bot where id='bot-general'", Integer.class));
            assertEquals(id, jdbc.queryForObject("select id from pricing_catalog_revision where source_revision='gemini-standard-2026-11-16'", String.class));
        }
    }

    @Test
    void developmentReleasePreservesLegacyCatalogAndCanBeRepeated() throws Exception {
        try (var postgres = PostgresTestImage.container()) {
            postgres.start();
            String[] database = {"--spring.datasource.url=" + postgres.getJdbcUrl(),
                    "--spring.datasource.username=" + postgres.getUsername(),
                    "--spring.datasource.password=" + postgres.getPassword()};
            ReleaseProvisioningApplication.main(new String[]{database[0], database[1], database[2], "--bot.release.action=schema-only"});
            var app = new org.springframework.boot.SpringApplication(io.github.samzhu.release.ReleaseConfiguration.class);
            app.setWebApplicationType(org.springframework.boot.WebApplicationType.NONE);
            try (var context = app.run(database)) {
                var provisioner = context.getBean(io.github.samzhu.bot.bootstrap.ReleaseProvisioner.class);
                var jdbc = new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
                provisioner.initializeDev();
                assertEquals("1135ff8e3c4dcdfc95249eb785eefc8fc0fabc0f7e4bc91e01cb56dd164cad03",
                        jdbc.queryForObject("select content_hash from pricing_catalog_revision where source_revision='dev-gemini-openai-2026-11-25'", String.class));
                var before = jdbc.queryForObject("select jsonb_agg(to_jsonb(e) order by e.price_revision)::text from pricing_catalog_entry e", String.class);
                assertEquals(3, jdbc.queryForObject("select count(*) from pricing_catalog_entry", Integer.class));
                assertEquals(1, jdbc.queryForObject("select count(*) from pricing_catalog_entry where provider='OPENAI' and model='gpt-5.4-mini'", Integer.class));
                provisioner.initializeDev();
                assertEquals(1, jdbc.queryForObject("select count(*) from pricing_catalog_revision", Integer.class));
                assertEquals(before, jdbc.queryForObject("select jsonb_agg(to_jsonb(e) order by e.price_revision)::text from pricing_catalog_entry e", String.class));
            }
        }
    }
}
