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
    @Test void rejectsConflictingSelectorsBeforeAnyBeanCreation() {
        clients.set(0);
        assertThrows(IllegalStateException.class, () -> new SpringApplicationBuilder(SyntheticClientConfiguration.class)
            .web(WebApplicationType.NONE).listeners(new ProfileGuard())
            .run("--spring.config.location=optional:classpath:/empty-provider-test/", "--spring.main.banner-mode=off",
                "--spring.profiles.active=local", "--bot-ai-provider=gemini", "--spring.ai.model.chat=other"));
        assertThat(clients.get()).isZero();
    }
    @Test void rejectsMissingInvalidAndForbiddenStandardSelectorsBeforeBeanCreation() {
        String[][] cases = {
            {"dev", ""}, {"local", "--spring.ai.model.chat="}, {"local", "--spring.ai.model.chat=invalid"},
            {"test", "--spring.ai.model.chat=google-genai"}, {"local,mock-ai", "--spring.ai.model.chat=google-genai"},
            {"local,test", "--spring.ai.model.chat=google-genai"}, {"local,prod", "--spring.ai.model.chat=google-genai"}
        };
        for (String[] entry : cases) {
            clients.set(0);
            var builder = new SpringApplicationBuilder(SyntheticClientConfiguration.class)
                .web(WebApplicationType.NONE).listeners(new ProfileGuard());
            var args = new java.util.ArrayList<>(java.util.List.of("--spring.config.location=optional:classpath:/empty-provider-test/",
                "--spring.main.banner-mode=off", "--spring.profiles.active=" + entry[0]));
            if (!entry[1].isEmpty()) args.add(entry[1]);
            assertThrows(IllegalStateException.class, () -> builder.run(args.toArray(String[]::new)));
            assertThat(clients.get()).isZero();
        }
    }
}
