package io.github.samzhu.bot;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

final class BudgetBoundaryTestClock extends Clock {
    final AtomicReference<Instant> instant = new AtomicReference<>(Instant.parse("2026-11-30T15:59:00Z"));

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return instant.get();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ClockConfiguration {
        @Bean
        @Primary
        BudgetBoundaryTestClock startupClock() {
            return new BudgetBoundaryTestClock();
        }
    }
}
