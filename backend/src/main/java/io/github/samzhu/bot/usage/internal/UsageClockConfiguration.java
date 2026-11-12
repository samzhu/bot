package io.github.samzhu.bot.usage.internal;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class UsageClockConfiguration {
    @Bean
    @ConditionalOnMissingBean(Clock.class)
    Clock usageClock() {
        return Clock.systemUTC();
    }
}
