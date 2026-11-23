package io.github.samzhu.bot.bootstrap;

import io.github.samzhu.bot.execution.internal.capture.FakeUsageCaptureAdapter;
import io.github.samzhu.bot.execution.internal.model.MockChatModel;
import io.github.samzhu.bot.execution.internal.web.MockVerificationController;
import io.github.samzhu.bot.identity.ProfileGuard;
import io.github.samzhu.bot.workspace.WorkspaceOperations;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.session.SessionService;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.core.annotation.Order;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.ArgumentMatchers.contains;

class LocalSeedProfilesTests {
    @Configuration(proxyBeanMethods = false)
    @ComponentScan(basePackageClasses = LocalDataSeedInitializer.class)
    static class Seeds { }

    @Test
    void publicConfigSelectsOnlyExplicitMockAndRetainsDefaultBot(@TempDir Path config) throws Exception {
        // Exercise the public config with an isolated missing secrets path, never the developer's file.
        Files.copy(Path.of("src/main/resources/application.yaml"), config.resolve("application.yaml"));
        String localConfig = Files.readString(Path.of("src/main/resources/application-local.yaml"));
        Files.writeString(config.resolve("application-local.yaml"), localConfig);
        for (String name : new String[]{"application-dev.yaml", "application-prod.yaml", "application-mock-ai.yaml"}) {
            Path source = Path.of("config", name);
            if (Files.exists(source)) {
                String contents = Files.readString(source).replace("file:./config/application-secrets.properties", "file:" + config.resolve("absent-secrets.properties"));
                Files.writeString(config.resolve(name), contents);
            }
        }
        for (String profiles : new String[]{"local", "local,dev,mock-ai", "local,dev", "prod"}) {
            boolean mockMode = profiles.contains("mock-ai");
            boolean local = profiles.contains("local");
            new ApplicationContextRunner()
                    .withInitializer(new ConfigDataApplicationContextInitializer())
                    .withPropertyValues("spring.config.location=" + config.toUri(), "spring.profiles.active=" + profiles)
                    .withUserConfiguration(Seeds.class, MockChatModel.class, FakeUsageCaptureAdapter.class, MockVerificationController.class)
                    .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
                    .withBean(SessionService.class, () -> mock(SessionService.class))
                    .withBean(WorkspaceOperations.class, () -> mock(WorkspaceOperations.class))
                    .withBean(TransactionTemplate.class, () -> mock(TransactionTemplate.class))
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(context.getBeansOfType(LocalDataSeedInitializer.class)).hasSize(mockMode ? 1 : 0);
                        assertThat(context.getBeansOfType(LocalUsageSeedInitializer.class)).hasSize(mockMode ? 1 : 0);
                        assertThat(context.getBeansOfType(MockChatModel.class)).hasSize(mockMode ? 1 : 0);
                        assertThat(context.getBeansOfType(FakeUsageCaptureAdapter.class)).hasSize(mockMode ? 1 : 0);
                        assertThat(context.getBeansOfType(MockVerificationController.class)).hasSize(mockMode ? 1 : 0);
                        assertThat(context.containsBean("defaultBotInitializer")).isTrue();
                        assertThat(context.getEnvironment().getProperty("bot.demo-data.enabled")).isEqualTo(String.valueOf(mockMode));
                        assertThat(context.getEnvironment().getProperty("spring.docker.compose.enabled")).isEqualTo(String.valueOf(local));
                        assertThat(context.getEnvironment().getProperty("bot.gke.execution-mode")).isEqualTo(mockMode ? "SIMULATED" : "OFF");
                    });
        }
    }

    @Test
    void conflictingModelProfilesAreRejected() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("local", "mock-ai");
        env.setProperty("bot-ai-provider", "gemini");
        assertThatThrownBy(() -> new ProfileGuard(env).validateActiveProfiles())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("mock-ai");
    }

    @Test
    void defaultBotRunsAfterMigrationAndBeforeDemoSeeds() {
        assertThat(DatabaseInitializer.class.getAnnotation(Order.class).value()).isLessThan(DefaultBotInitializer.class.getAnnotation(Order.class).value());
        assertThat(DefaultBotInitializer.class.getAnnotation(Order.class).value()).isLessThan(LocalDataSeedInitializer.class.getAnnotation(Order.class).value());
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        new DefaultBotInitializer(jdbc).run(null);
        verify(jdbc).update(contains("'bot-general'"));
        verifyNoMoreInteractions(jdbc);
    }

    @Test
    void testProfileEnablesFakeButNotDemo() {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withUserConfiguration(Seeds.class, MockChatModel.class, FakeUsageCaptureAdapter.class)
                .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
                .withBean(SessionService.class, () -> mock(SessionService.class))
                .withBean(WorkspaceOperations.class, () -> mock(WorkspaceOperations.class))
                .withBean(TransactionTemplate.class, () -> mock(TransactionTemplate.class));
        runner.withPropertyValues("spring.profiles.active=test")
                .run(context -> {
                    assertThat(context).hasSingleBean(MockChatModel.class).hasSingleBean(FakeUsageCaptureAdapter.class);
                    assertThat(context).doesNotHaveBean(LocalDataSeedInitializer.class).doesNotHaveBean(LocalUsageSeedInitializer.class);
                });

    }
}
