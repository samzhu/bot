package io.github.samzhu.release;

import io.github.samzhu.bot.bootstrap.DatabaseInitializer;
import io.github.samzhu.bot.bootstrap.DefaultBotInitializer;
import io.github.samzhu.bot.usage.PricingCatalogReleaseConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration(proxyBeanMethods = false)
@EnableAutoConfiguration
@Import({DatabaseInitializer.class, DefaultBotInitializer.class, PricingCatalogReleaseConfiguration.class})
public class ReleaseConfiguration {}
