package io.github.samzhu.bot.identity;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ComposeEmptyProfileStartupTests {
    static final AtomicInteger beans = new AtomicInteger();
    @Configuration(proxyBeanMethods = false)
    static class Fixture {
        @Bean Object marker() { beans.incrementAndGet(); return new Object(); }
    }

    @Test void defaultAndExplicitEmptyHaveDifferentStartupOutcomes(@TempDir Path directory) throws Exception {
        Files.copy(Path.of("src/main/resources/application.yaml"), directory.resolve("application.yaml"));
        Files.copy(Path.of("src/main/resources/application-local.yaml"), directory.resolve("application-local.yaml"));
        String[] base = {"--spring.config.location=" + directory.toUri(), "--spring.main.banner-mode=off",
                "--spring.profiles.active=dev", "--spring.ai.model.chat=google-genai"};
        beans.set(0);
        try (var context = new SpringApplicationBuilder(Fixture.class).web(WebApplicationType.NONE)
                .listeners(new ProfileGuard()).run(base)) {
            assertThat(Binder.get(context.getEnvironment()).bind("spring.docker.compose.profiles.active", Bindable.setOf(String.class)).orElse(Set.of()))
                .containsExactly("google-genai");
            assertThat(beans.get()).isEqualTo(1);
        }
        try (var unguarded = new SpringApplicationBuilder(Fixture.class).web(WebApplicationType.NONE)
                .run(base[0], base[1], base[2], base[3], "--spring.docker.compose.profiles.active=")) {
            assertThat(Binder.get(unguarded.getEnvironment()).bind("spring.docker.compose.profiles.active", Bindable.setOf(String.class)).orElse(Set.of()))
                .isEmpty();
        }
        beans.set(0);
        assertThrows(IllegalStateException.class, () -> new SpringApplicationBuilder(Fixture.class).web(WebApplicationType.NONE)
            .listeners(new ProfileGuard()).run(base[0], base[1], base[2], base[3], "--spring.docker.compose.profiles.active="));
        assertThat(beans.get()).isZero();
    }
    @Test void composeDisabledAcceptsExplicitEmpty(@TempDir Path directory) throws Exception {
        Files.copy(Path.of("src/main/resources/application.yaml"), directory.resolve("application.yaml"));
        Files.copy(Path.of("src/main/resources/application-local.yaml"), directory.resolve("application-local.yaml"));
        try (var context = new SpringApplicationBuilder(Fixture.class).web(WebApplicationType.NONE).listeners(new ProfileGuard())
                .run("--spring.config.location=" + directory.toUri(), "--spring.main.banner-mode=off", "--spring.profiles.active=dev",
                     "--spring.ai.model.chat=google-genai", "--spring.docker.compose.enabled=false", "--spring.docker.compose.profiles.active=")) {
            assertThat(context.isActive()).isTrue();
        }
    }
    @Test void explicitExternalConfigurationAndNonlocalTestRemainValid() {
        try (var external = new SpringApplicationBuilder(Fixture.class).web(WebApplicationType.NONE).listeners(new ProfileGuard())
                .run("--spring.config.location=optional:classpath:/empty-provider-test/", "--spring.main.banner-mode=off", "--spring.profiles.active=local",
                     "--spring.ai.model.chat=ollama", "--spring.docker.compose.profiles.active=ollama")) {
            assertThat(external.isActive()).isTrue();
        }
        try (var test = new SpringApplicationBuilder(Fixture.class).web(WebApplicationType.NONE).listeners(new ProfileGuard())
                .run("--spring.config.location=optional:classpath:/empty-provider-test/", "--spring.main.banner-mode=off", "--spring.profiles.active=test",
                     "--spring.docker.compose.enabled=true", "--spring.docker.compose.profiles.active=")) {
            assertThat(test.isActive()).isTrue();
        }
    }
}
