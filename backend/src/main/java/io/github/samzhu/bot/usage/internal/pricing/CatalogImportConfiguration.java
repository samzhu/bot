package io.github.samzhu.bot.usage.internal.pricing;

import io.github.samzhu.bot.pricing.PriceCatalog;
import io.github.samzhu.bot.pricing.PriceSnapshot;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Optional controlled deployment input. No bundled production or synthetic prices. */
@Configuration
@EnableConfigurationProperties(CatalogImportConfiguration.CatalogProperties.class)
class CatalogImportConfiguration {
    @ConfigurationProperties("bot.pricing.catalog")
    record CatalogProperties(String revision, String sourceReference, List<Item> entries) {
        record Item(String provider, String endpoint, String model, String channel, String priceRevision,
                    Instant fromInclusive, Instant untilExclusive, Map<String,String> rates) {}
    }
    private final CatalogProperties properties;
    private final CatalogActivation activation;
    CatalogImportConfiguration(CatalogProperties properties, CatalogActivation activation) { this.properties=properties; this.activation=activation; }
    @EventListener(ApplicationReadyEvent.class)
    @Order(org.springframework.core.Ordered.LOWEST_PRECEDENCE)
    public void importIfConfigured() {
        if (properties.revision()==null && properties.sourceReference()==null && properties.entries()==null) return;
        if (properties.revision()==null || properties.revision().isBlank() || properties.sourceReference()==null
                || properties.sourceReference().isBlank() || properties.entries()==null)
            throw new IllegalStateException("Incomplete controlled pricing catalog configuration");
        var entries=properties.entries().stream().map(e -> new PriceCatalog.Entry(
            new PriceSnapshot(e.provider(),e.endpoint(),e.model(),e.channel(),e.priceRevision(),e.rates()),
            e.fromInclusive(),e.untilExclusive())).toList();
        activation.publish(properties.revision(),properties.sourceReference(),entries);
    }
}
