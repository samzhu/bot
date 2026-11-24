package io.github.samzhu.bot.usage.internal.pricing;

import io.github.samzhu.bot.pricing.PriceCatalog;
import io.github.samzhu.bot.pricing.PriceSnapshot;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Optional controlled deployment input. No bundled production or synthetic prices. */
@Configuration
@EnableConfigurationProperties(CatalogImportConfiguration.CatalogProperties.class)
public class CatalogImportConfiguration implements io.github.samzhu.bot.usage.PricingCatalogRelease {
    @ConfigurationProperties("bot.pricing.catalog")
    record CatalogProperties(String revision, String sourceReference, List<Item> entries) {
        record Item(String provider, String endpoint, String model, String channel, String priceRevision,
                    Instant fromInclusive, Instant untilExclusive, Map<String,String> rates, PriceCatalog.Metadata metadata) {}
    }
    private final CatalogProperties properties;
    private final CatalogActivation activation;
    CatalogImportConfiguration(CatalogProperties properties, CatalogActivation activation) { this.properties=properties; this.activation=activation; }
    public void importIfConfigured() {
        var entries = configuredEntries();
        if (entries != null) activation.publish(properties.revision(),properties.sourceReference(),entries);
    }
    @Override
    public void publishConfigured() { importIfConfigured(); }

    @Override
    public void restorePublished() { activation.restore(verifyPublishedIfConfigured()); }
    public boolean verifyPublishedIfConfigured() {
        var entries = configuredEntries();
        if (entries == null) return false;
        activation.verifyPublished(properties.revision(),properties.sourceReference(),entries);
        return true;
    }
    private List<PriceCatalog.Entry> configuredEntries() {
        if (properties.revision()==null && properties.sourceReference()==null && properties.entries()==null) return null;
        if (properties.revision()==null || properties.revision().isBlank() || properties.sourceReference()==null
                || properties.sourceReference().isBlank() || properties.entries()==null)
            throw new IllegalStateException("Incomplete controlled pricing catalog configuration");
        for (var item : properties.entries()) {
            if ("GOOGLE".equals(item.provider()) && "generateContent".equals(item.endpoint())
                    && "gemini-3.8-flash".equals(item.model()) && "GEMINI_API".equals(item.channel())) {
                var metadata=item.metadata();
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
        return properties.entries().stream().map(e -> new PriceCatalog.Entry(
            new PriceSnapshot(e.provider(),e.endpoint(),e.model(),e.channel(),e.priceRevision(),e.rates()),
            e.fromInclusive(),e.untilExclusive(),e.metadata())).toList();
    }
}
