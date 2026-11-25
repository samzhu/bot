package io.github.samzhu.bot;

import io.github.samzhu.bot.bootstrap.ReleaseMigration;
import io.github.samzhu.bot.bootstrap.ReleaseProvisioner;
import io.github.samzhu.bot.bootstrap.DefaultBotInitializer;
import io.github.samzhu.bot.usage.PricingCatalogRelease;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import javax.sql.DataSource;

/** Explicit test-owned provisioning before the service readiness gate. */
@TestConfiguration(proxyBeanMethods = false)
public class TestProvisioningConfiguration {
    @Bean ReleaseMigration testReleaseMigration(JdbcTemplate jdbc, DataSource dataSource) {
        return new ReleaseMigration(jdbc, dataSource);
    }
    @Bean ReleaseProvisioner testReleaseProvisioner(JdbcTemplate jdbc, ReleaseMigration migration,
            PricingCatalogRelease catalog) {
        return new ReleaseProvisioner(jdbc, migration, new DefaultBotInitializer(jdbc), catalog);
    }
    @Bean @Order(0)
    ApplicationRunner provisionTestDatabase(ReleaseProvisioner provisioner, JdbcTemplate jdbc) {
        return args -> {
            provisioner.migrateSchema();
            provisioner.initializeProd(jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<String>) c -> c.getMetaData().getURL()));
        };
    }
}
