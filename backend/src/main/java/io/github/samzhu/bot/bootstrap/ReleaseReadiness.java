package io.github.samzhu.bot.bootstrap;

import io.github.samzhu.bot.usage.PricingCatalogRelease;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Checks the release-owned prerequisites before domain startup recovery runs. */
@Component
@Order(10)
public class ReleaseReadiness implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    private final PricingCatalogRelease catalog;

    public ReleaseReadiness(JdbcTemplate jdbc, PricingCatalogRelease catalog) {
        this.jdbc = jdbc;
        this.catalog = catalog;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        try {
            var applied = new java.util.HashSet<>(jdbc.queryForList("select script_name from schema_version", String.class));
            for (var resource : new PathMatchingResourcePatternResolver().getResources("classpath:db/migration/*.sql")) {
                if (!applied.contains(resource.getFilename())) {
                    throw new IllegalStateException("Release provisioning required: unapplied migration " + resource.getFilename());
                }
            }
            if (!Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from bot where id='bot-general' and target='LOCAL')", Boolean.class))) {
                throw new IllegalStateException("Release provisioning required: LOCAL default Bot is missing");
            }
            catalog.restorePublished();
        } catch (org.springframework.dao.DataAccessException ex) {
            throw new IllegalStateException("Release provisioning required: database schema or catalog is not ready", ex);
        }
    }
}
