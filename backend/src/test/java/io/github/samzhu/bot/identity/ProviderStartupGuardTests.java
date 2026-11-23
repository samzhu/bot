package io.github.samzhu.bot.identity;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProviderStartupGuardTests {
    static final AtomicInteger clients = new AtomicInteger();
    @Configuration(proxyBeanMethods = false)
    static class SyntheticClientConfiguration {
        @Bean Object syntheticClient() { clients.incrementAndGet(); return new Object(); }
    }
    @Test void rejectsForbiddenGeminiBeforeAnyBeanCreation() {
        for (String profile : new String[]{"test", "prod", "local,test", "local,prod", "local,mock-ai"}) {
            clients.set(0);
            assertThrows(IllegalStateException.class, () -> new SpringApplicationBuilder(SyntheticClientConfiguration.class)
                .web(WebApplicationType.NONE).listeners(new ProfileGuard())
                .run("--spring.config.location=optional:classpath:/empty-provider-test/", "--spring.main.banner-mode=off", "--spring.profiles.active=" + profile, "--bot-ai-provider=gemini"));
            assertThat(clients.get()).isZero();
        }
    }
}
