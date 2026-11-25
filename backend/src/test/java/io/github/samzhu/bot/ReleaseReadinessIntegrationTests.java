package io.github.samzhu.bot;

import io.github.samzhu.bot.bootstrap.ReleaseProvisioningApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.junit.jupiter.api.Assertions.*;

class ReleaseReadinessIntegrationTests {
    @Test
    void serviceRejectsUnpublishedConfiguredCatalogWithoutWritingIt() throws Exception {
        try (var postgres = PostgresTestImage.container()) {
            postgres.start();
            String[] database = {"--spring.datasource.url=" + postgres.getJdbcUrl(),
                    "--spring.datasource.username=" + postgres.getUsername(),
                    "--spring.datasource.password=" + postgres.getPassword()};
            var schema = new java.util.ArrayList<>(java.util.List.of(database));
            schema.add("--bot.release.action=schema-only");
            ReleaseProvisioningApplication.main(schema.toArray(String[]::new));
            var initialize = new java.util.ArrayList<>(java.util.List.of(database));
            initialize.add("--bot.release.action=initialize-prod");
            initialize.add("--bot.release.expected-jdbc-url=" + postgres.getJdbcUrl());
            ReleaseProvisioningApplication.main(initialize.toArray(String[]::new));
            var jdbc = new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
            jdbc.update("delete from pricing_catalog_entry");
            jdbc.update("delete from pricing_catalog_revision");
            var failure = assertThrows(Exception.class, () -> new SpringApplicationBuilder(
                    BotApplication.class, TestChatModelConfiguration.class, TestUsageCaptureConfiguration.class)
                    .profiles("test").web(WebApplicationType.NONE).run(database));
            assertTrue(causes(failure).contains("configured pricing catalog is missing"), causes(failure));
            assertEquals(0, jdbc.queryForObject("select count(*) from pricing_catalog_revision", Integer.class));
        }
    }

    @Test
    void serviceRestoresConfiguredRevisionEvenWhenAnotherRevisionIsNewer() throws Exception {
        try (var postgres = PostgresTestImage.container()) {
            postgres.start();
            String[] database = {"--spring.datasource.url=" + postgres.getJdbcUrl(),
                    "--spring.datasource.username=" + postgres.getUsername(), "--spring.datasource.password=" + postgres.getPassword()};
            var schema = new java.util.ArrayList<>(java.util.List.of(database));
            schema.add("--bot.release.action=schema-only");
            ReleaseProvisioningApplication.main(schema.toArray(String[]::new));
            var initialize = new java.util.ArrayList<>(java.util.List.of(database));
            initialize.add("--bot.release.action=initialize-prod");
            initialize.add("--bot.release.expected-jdbc-url=" + postgres.getJdbcUrl());
            ReleaseProvisioningApplication.main(initialize.toArray(String[]::new));
            var jdbc = new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
            var expected = jdbc.queryForObject("select id from pricing_catalog_revision where source_revision='gemini-standard-2026-11-16'", String.class);
            jdbc.update("insert into pricing_catalog_revision(id,source_reference,source_revision,content_hash) values ('newer','fixture','other-environment','unused')");
            try (var context = new SpringApplicationBuilder(BotApplication.class, TestChatModelConfiguration.class,
                    TestUsageCaptureConfiguration.class).profiles("test").web(WebApplicationType.NONE).run(database)) {
                var catalog = context.getBean(io.github.samzhu.bot.usage.internal.pricing.CatalogActivation.class);
                assertEquals(expected, catalog.sample(java.time.Clock.systemUTC(), java.time.Clock::instant).revisionId());
            }
        }
    }

    @Test
    void schemaAloneRejectsReadinessUntilProductionInitialization() throws Exception {
        try (var postgres = PostgresTestImage.container()) {
            postgres.start();
            String[] database = {"--spring.datasource.url=" + postgres.getJdbcUrl(),
                    "--spring.datasource.username=" + postgres.getUsername(), "--spring.datasource.password=" + postgres.getPassword()};
            var schema = new java.util.ArrayList<>(java.util.List.of(database));
            schema.add("--bot.release.action=schema-only");
            ReleaseProvisioningApplication.main(schema.toArray(String[]::new));
            var jdbc = new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
            var failure = assertThrows(Exception.class, () -> new SpringApplicationBuilder(BotApplication.class,
                    TestChatModelConfiguration.class, TestUsageCaptureConfiguration.class)
                    .profiles("test").web(WebApplicationType.NONE).run(database));
            assertTrue(causes(failure).contains("default Bot is missing"), causes(failure));
            assertEquals(0, jdbc.queryForObject("select count(*) from bot", Integer.class));
            assertEquals(0, jdbc.queryForObject("select count(*) from pricing_catalog_revision", Integer.class));
            var initialize = new java.util.ArrayList<>(java.util.List.of(database));
            initialize.add("--bot.release.action=initialize-prod");
            initialize.add("--bot.release.expected-jdbc-url=" + postgres.getJdbcUrl());
            ReleaseProvisioningApplication.main(initialize.toArray(String[]::new));
            try (var context = new SpringApplicationBuilder(BotApplication.class, TestChatModelConfiguration.class,
                    TestUsageCaptureConfiguration.class).profiles("test").web(WebApplicationType.NONE).run(database)) {
                assertEquals(org.springframework.boot.availability.ReadinessState.ACCEPTING_TRAFFIC,
                        context.getBean(org.springframework.boot.availability.ApplicationAvailability.class).getReadinessState());
            }
        }
    }

    @Test
    void retiredCatalogConfigurationIsRejectedByService() throws Exception {
        try (var postgres = PostgresTestImage.container()) {
            postgres.start();
            String[] database = {"--spring.datasource.url=" + postgres.getJdbcUrl(),
                    "--spring.datasource.username=" + postgres.getUsername(), "--spring.datasource.password=" + postgres.getPassword(),
                    "--bot.pricing.catalog.revision=obsolete"};
            var failure = assertThrows(Exception.class, () -> new SpringApplicationBuilder(BotApplication.class,
                    TestChatModelConfiguration.class, TestUsageCaptureConfiguration.class)
                    .profiles("test").web(WebApplicationType.NONE).run(database));
            assertTrue(causes(failure).contains("bot.pricing.catalog configuration is retired"), causes(failure));
            var jdbc = new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
            assertNull(jdbc.queryForObject("select to_regclass('databasechangelog')::text", String.class));
        }
    }

    private static String causes(Throwable failure) {
        var messages = new StringBuilder();
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) messages.append(cause.getMessage()).append('\n');
        return messages.toString();
    }
}
