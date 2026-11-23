package io.github.samzhu.bot;

import io.github.samzhu.bot.execution.internal.capture.UsageCaptureAdapter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.assertThat;

class TestUsageCaptureConfigurationTests {
    @Test void explicitTestConfigurationProvidesSimulationUsageWithoutAProvider() {
        new ApplicationContextRunner().withUserConfiguration(TestUsageCaptureConfiguration.class)
            .run(context -> {
                assertThat(context).hasNotFailed().hasSingleBean(UsageCaptureAdapter.class);
                var dispatch = context.getBean(UsageCaptureAdapter.class).dispatch("run", "workspace", "actor");
                assertThat(dispatch.provider()).isEqualTo("FAKE");
                assertThat(dispatch.channel()).isEqualTo("FAKE");
                assertThat(dispatch.simulation()).isTrue();
                assertThat(dispatch.attemptCoverage()).isEqualTo("VERIFIED");
            });
    }
}
