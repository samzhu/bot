package io.github.samzhu.bot.bootstrap;

import io.github.samzhu.bot.usage.PricingCatalogRelease;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Checks the release-owned prerequisites before domain startup recovery runs. */
@Component
@Order(10)
public class ReleaseReadiness implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    private final PricingCatalogRelease catalog;
    private final org.springframework.core.env.Environment environment;

    public ReleaseReadiness(JdbcTemplate jdbc, PricingCatalogRelease catalog, org.springframework.core.env.Environment environment) {
        this.jdbc = jdbc;
        this.catalog = catalog;
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        try {
            int missing = ReleaseSchema.verifiedPrefix(jdbc);
            if (missing != 0) {
                throw new IllegalStateException("Release provisioning required: " + missing + " unapplied migration(s)");
            }
            String selected = environment.acceptsProfiles(org.springframework.core.env.Profiles.of("dev & !test & !prod")) ? "dev" : "prod";
            var expected = new InitializationSql(selected).read();
            if (!Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from bot where id=? and target=?)", Boolean.class, expected.bot().id(), expected.bot().target()))) {
                throw new IllegalStateException("Release provisioning required: LOCAL default Bot is missing");
            }
            catalog.restorePublished(expected.revision(), expected.source(), expected.entries());
        } catch (org.springframework.dao.DataAccessException ex) {
            throw new IllegalStateException("Release provisioning required: database schema or catalog is not ready", ex);
        }
    }
}
