package io.github.samzhu.bot.usage.internal.admission;

import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@ConfigurationProperties("bot.budget.monthly")
public record MonthlyBudgetProperties(String defaultCapUsd) {
    public MonthlyBudgetProperties {
        if (defaultCapUsd == null || !defaultCapUsd.matches("[0-9]+(?:\\.[0-9]+)?")) {
            throw new IllegalArgumentException("Invalid default monthly budget");
        }
        BigDecimal value = new BigDecimal(defaultCapUsd);
        if (value.scale() > 12 || value.compareTo(new BigDecimal("999999999999999999.999999999999")) > 0) {
            throw new IllegalArgumentException("Invalid default monthly budget");
        }
    }

    BigDecimal cap() { return new BigDecimal(defaultCapUsd); }
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MonthlyBudgetProperties.class)
class MonthlyBudgetConfiguration {}
