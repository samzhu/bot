package io.github.samzhu.bot.bootstrap;

import io.github.samzhu.bot.usage.PricingCatalogRelease;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/** Serializes the complete release, including the preflight and reference data. */
public class ReleaseProvisioner {
    private final JdbcTemplate jdbc;
    private final ReleaseMigration migration;
    private final DefaultBotInitializer bot;
    private final PricingCatalogRelease catalog;

    public ReleaseProvisioner(JdbcTemplate jdbc, ReleaseMigration migration,
            DefaultBotInitializer bot, PricingCatalogRelease catalog) {
        this.jdbc = jdbc;
        this.migration = migration;
        this.bot = bot;
        this.catalog = catalog;
    }

    @Transactional(rollbackFor = Exception.class)
    public void run() throws Exception {
        lockRelease();
        migration.run();
        bot.run(null);
        catalog.publishConfigured();
    }

    @Transactional(rollbackFor = Exception.class)
    public void migrateSchema() throws Exception {
        lockRelease();
        migration.run();
    }

    private void lockRelease() {
        // The same transaction-scoped lock protects preflight and Liquibase in both admin modes.
        jdbc.execute("select pg_advisory_xact_lock(7620261124)");
    }
}
