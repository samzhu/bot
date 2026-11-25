package io.github.samzhu.bot.usage;

/** Release-owned catalog publication and read-only runtime restoration. */
public interface PricingCatalogRelease {
    void publishRelease(String revision, String source, java.util.List<io.github.samzhu.bot.pricing.PriceCatalog.Entry> entries);
    void restoreExpected(String revision, String contentHash);
}
