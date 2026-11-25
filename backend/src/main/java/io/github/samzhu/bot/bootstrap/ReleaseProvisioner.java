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
    public void initializeProd(String expectedJdbcUrl) throws Exception {
        // Use the transaction-bound connection, before acquiring the release lock or writing.
        boolean matches = expectedJdbcUrl != null && !expectedJdbcUrl.isBlank()
                && Boolean.TRUE.equals(jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Boolean>) connection -> {
                    var metadata = connection.getMetaData();
                    return "PostgreSQL".equals(metadata.getDatabaseProductName())
                            && expectedJdbcUrl.equals(metadata.getURL());
                }));
        if (!matches) throw new IllegalStateException("Initialization PostgreSQL endpoint mismatch");
        lockRelease();
        if (ReleaseSchema.verifiedPrefix(jdbc) != 0)
            throw new IllegalStateException("Release schema is not ready");
        initialize("prod");
    }

    @Transactional(rollbackFor = Exception.class)
    public void initializeDev() throws Exception {
        lockRelease();
        if (ReleaseSchema.verifiedPrefix(jdbc) != 0) throw new IllegalStateException("Release schema is not ready");
        initialize("dev");
    }

    private void initialize(String environment) throws Exception {
        var data = new InitializationSql(environment).execute(jdbc);
        bot.run(data.bot());
        catalog.publishRelease(data.revision(), data.source(), data.entries());
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
