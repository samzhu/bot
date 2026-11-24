package io.github.samzhu.bot.usage;

import io.github.samzhu.bot.usage.internal.pricing.CatalogActivation;
import io.github.samzhu.bot.usage.internal.pricing.CatalogImportConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** Makes the usage release contract available to the one-off admin context. */
@Configuration(proxyBeanMethods = false)
@Import({CatalogActivation.class, CatalogImportConfiguration.class})
public class PricingCatalogReleaseConfiguration {}
