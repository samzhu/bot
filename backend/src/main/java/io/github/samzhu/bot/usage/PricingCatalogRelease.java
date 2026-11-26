package io.github.samzhu.bot.usage;

/** Release-owned catalog publication and read-only runtime restoration. */
public interface PricingCatalogRelease {
    void restoreRevision(String revision, String contentHash);
}
