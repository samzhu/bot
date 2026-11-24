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
            ReleaseProvisioningApplication.main(database);
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

    private static String causes(Throwable failure) {
        var messages = new StringBuilder();
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) messages.append(cause.getMessage()).append('\n');
        return messages.toString();
    }
}
