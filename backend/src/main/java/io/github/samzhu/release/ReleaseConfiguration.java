package io.github.samzhu.release;

import io.github.samzhu.bot.bootstrap.ReleaseMigration;
import io.github.samzhu.bot.bootstrap.ReleaseProvisioner;
import io.github.samzhu.bot.bootstrap.DefaultBotInitializer;
import io.github.samzhu.bot.usage.PricingCatalogReleaseConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration(proxyBeanMethods = false)
// The release invokes Liquibase only after its serialized preflight.
@EnableAutoConfiguration(exclude = org.springframework.boot.liquibase.autoconfigure.LiquibaseAutoConfiguration.class)
@Import({ReleaseMigration.class, ReleaseProvisioner.class, DefaultBotInitializer.class, PricingCatalogReleaseConfiguration.class})
public class ReleaseConfiguration {}
