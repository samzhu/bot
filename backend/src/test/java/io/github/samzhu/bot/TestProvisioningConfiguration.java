package io.github.samzhu.bot;

import io.github.samzhu.bot.bootstrap.DatabaseInitializer;
import io.github.samzhu.bot.bootstrap.DefaultBotInitializer;
import io.github.samzhu.bot.usage.PricingCatalogRelease;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;

/** Explicit test-owned provisioning before the service readiness gate. */
@TestConfiguration(proxyBeanMethods = false)
public class TestProvisioningConfiguration {
    @Bean DatabaseInitializer testDatabaseInitializer(JdbcTemplate jdbc) { return new DatabaseInitializer(jdbc); }

    @Bean @Order(0)
    ApplicationRunner provisionTestDatabase(DatabaseInitializer migrations, JdbcTemplate jdbc,
            PricingCatalogRelease catalog) {
        return args -> {
            migrations.run(args);
            new DefaultBotInitializer(jdbc).run(args);
            catalog.publishConfigured();
        };
    }
}
