package io.github.samzhu.bot.usage.internal.pricing;

import io.github.samzhu.bot.pricing.PriceCatalog;
import org.springframework.context.annotation.Configuration;
import java.util.List;

/** Release initialization shares the catalog's publication and validation rules. */
@Configuration
public class CatalogImportConfiguration implements io.github.samzhu.bot.usage.PricingCatalogRelease {
    private final CatalogActivation activation;
    CatalogImportConfiguration(CatalogActivation activation, org.springframework.core.env.Environment environment) {
        this.activation = activation;
        var prefix = org.springframework.boot.context.properties.source.ConfigurationPropertyName.of("bot.pricing.catalog");
        for (var source : org.springframework.boot.context.properties.source.ConfigurationPropertySources.get(environment)) {
            if (source.getConfigurationProperty(prefix) != null
                    || source.containsDescendantOf(prefix) == org.springframework.boot.context.properties.source.ConfigurationPropertyState.PRESENT) {
                throw new IllegalStateException("bot.pricing.catalog configuration is retired; use environment initialization SQL");
            }
        }
    }
    @Override
    public void publishRelease(String revision, String source, List<PriceCatalog.Entry> entries) {
        validateMetadata(entries);
        activation.publishRelease(revision, source, entries);
    }
    @Override
    public void restorePublished(String revision, String source, List<PriceCatalog.Entry> entries) {
        validateMetadata(entries);
        activation.verifyPublished(revision, source, entries);
        activation.restoreRevision(revision);
    }
    private void validateMetadata(List<PriceCatalog.Entry> entries) {
        for (var entry : entries) {
            if ("GOOGLE".equals(entry.snapshot().provider()) && "generateContent".equals(entry.snapshot().endpoint())
                    && "gemini-3.8-flash".equals(entry.snapshot().model()) && "GEMINI_API".equals(entry.snapshot().channel())) {
                var metadata=entry.metadata();
                try {
                    if (metadata==null || !"STANDARD".equals(metadata.serviceTier())
                            || !"USD_PER_1000000_TOKENS".equals(metadata.tokenUnit())
                            || !"USD_PER_1000000_TOKEN_HOURS".equals(metadata.storageUnit())
                            || metadata.storageRate()==null || !metadata.storageRate().matches("[0-9]+(?:\\.[0-9]+)?")
                            || metadata.sources()==null || metadata.sources().isEmpty()) throw new IllegalArgumentException();
                    java.time.LocalDate.parse(metadata.verifiedDate());
                    for (String source : metadata.sources()) {
                        var uri=java.net.URI.create(source);
                        if (!"https".equals(uri.getScheme()) || uri.getHost()==null) throw new IllegalArgumentException();
                    }
                } catch (RuntimeException ex) { throw new IllegalArgumentException("Invalid catalog metadata"); }
            }
        }
    }
}
