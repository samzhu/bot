package io.github.samzhu.bot;

import io.github.samzhu.bot.execution.internal.capture.FakeUsageCaptureAdapter;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

@TestConfiguration(proxyBeanMethods = false)
public class TestUsageCaptureConfiguration {
    @Bean
    FakeUsageCaptureAdapter fakeUsageCaptureAdapter() {
        return new FakeUsageCaptureAdapter();
    }
}
