package io.github.samzhu.bot.usage.internal.pricing;

import org.springframework.context.annotation.Configuration;

/** Loads the configured catalog revision for this release. */
@Configuration
public class CatalogImportConfiguration implements io.github.samzhu.bot.usage.PricingCatalogRelease {
    private final CatalogActivation activation;
    CatalogImportConfiguration(CatalogActivation activation, org.springframework.core.env.Environment environment) {
        this.activation = activation;
        var prefix = org.springframework.boot.context.properties.source.ConfigurationPropertyName.of("bot.pricing.catalog");
        for (var source : org.springframework.boot.context.properties.source.ConfigurationPropertySources.get(environment)) {
            if (source.getConfigurationProperty(prefix) != null
                    || source.containsDescendantOf(prefix) == org.springframework.boot.context.properties.source.ConfigurationPropertyState.PRESENT) {
                throw new IllegalStateException("bot.pricing.catalog configuration is retired; use Liquibase changesets");
            }
        }
    }
    @Override
    public void restoreRevision(String revision, String contentHash) {
        activation.restoreRevision(revision, contentHash);
    }
}
