package io.github.samzhu.bot.usage.internal.admission;

import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import static org.junit.jupiter.api.Assertions.*;

class MonthlyBudgetConfigDataTests {
    @Configuration(proxyBeanMethods = false)
    @Import(MonthlyBudgetConfiguration.class)
    static class App {}

    @Test void yamlDefaultAndCommandLineOverrideAreExact() {
        try (var context = new SpringApplicationBuilder(App.class).web(WebApplicationType.NONE).run()) {
            assertEquals("10", context.getBean(MonthlyBudgetProperties.class).defaultCapUsd());
        }
        try (var context = new SpringApplicationBuilder(App.class).web(WebApplicationType.NONE)
                .run("--bot.budget.monthly.default-cap-usd=23.125")) {
            assertEquals("23.125", context.getBean(MonthlyBudgetProperties.class).defaultCapUsd());
        }
    }

    @Test void invalidDefaultsFailStartup() {
        for (String invalid : new String[]{"-1", "0.0000000000001", "1000000000000000000", "garbage"}) {
            assertThrows(Exception.class, () -> new SpringApplicationBuilder(App.class).web(WebApplicationType.NONE)
                    .run("--bot.budget.monthly.default-cap-usd=" + invalid));
        }
    }
}
