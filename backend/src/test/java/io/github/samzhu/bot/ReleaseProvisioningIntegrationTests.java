package io.github.samzhu.bot;

import io.github.samzhu.bot.bootstrap.ReleaseProvisioningApplication;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.junit.jupiter.api.Assertions.*;

class ReleaseProvisioningIntegrationTests {
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
            ReleaseProvisioningApplication.main(schema);
            ReleaseProvisioningApplication.main(initialize);
            assertEquals(1, jdbc.queryForObject("select count(*) from bot where id='bot-general'", Integer.class));
            assertEquals(id, jdbc.queryForObject("select id from pricing_catalog_revision where source_revision='gemini-standard-2026-11-16'", String.class));
        }
    }
}
