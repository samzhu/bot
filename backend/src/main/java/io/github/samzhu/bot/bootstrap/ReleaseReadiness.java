package io.github.samzhu.bot.bootstrap;

import io.github.samzhu.bot.usage.PricingCatalogRelease;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Checks the release-owned prerequisites before domain startup recovery runs. */
@Component
@Order(10)
public class ReleaseReadiness implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    private final PricingCatalogRelease catalog;
    private final String catalogRevision;
    private final String catalogContentHash;

    public ReleaseReadiness(JdbcTemplate jdbc, PricingCatalogRelease catalog,
            @Value("${bot.pricing.release-revision}") String catalogRevision,
            @Value("${bot.pricing.release-content-hash}") String catalogContentHash) {
        this.jdbc = jdbc;
        this.catalog = catalog;
        this.catalogRevision = catalogRevision;
        this.catalogContentHash = catalogContentHash;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        try {
            if (!Boolean.TRUE.equals(jdbc.queryForObject("""
                    select exists(select 1 from bot where id = 'bot-general' and target = 'LOCAL')
                    """, Boolean.class))) {
                throw new IllegalStateException("Release provisioning required: LOCAL default Bot is missing");
            }
            catalog.restoreRevision(catalogRevision, catalogContentHash);
        } catch (org.springframework.dao.DataAccessException ex) {
            throw new IllegalStateException("Release provisioning required: database schema or catalog is not ready", ex);
        }
    }
}
