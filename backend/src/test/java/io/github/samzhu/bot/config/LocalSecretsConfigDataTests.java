package io.github.samzhu.bot.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.assertThat;

class LocalSecretsConfigDataTests {
    @Test void devProfileActivatesLocalInfrastructure(@TempDir Path directory) throws Exception {
        Files.copy(Path.of("src/main/resources/application.yaml"), directory.resolve("application.yaml"));
        Files.copy(Path.of("src/main/resources/application-local.yaml"), directory.resolve("application-local.yaml"));
        new ApplicationContextRunner().withInitializer(new ConfigDataApplicationContextInitializer())
            .withPropertyValues("spring.config.location=" + directory.toUri(), "spring.profiles.active=dev")
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getEnvironment().getActiveProfiles()).contains("local");
                assertThat(context.getEnvironment().getProperty("spring.docker.compose.enabled")).isEqualTo("true");
            });
    }

    @Test void devLocalImportsOnlyOutsideMockTestAndProd(@TempDir Path directory) throws Exception {
        Path secret = directory.resolve("synthetic.properties");
        Files.writeString(secret, "bot-google-api-key=synthetic-value-only\nbot-openai-api-key=synthetic-openai-key\n");
        Files.copy(Path.of("src/main/resources/application.yaml"), directory.resolve("application.yaml"));
        Files.copy(Path.of("src/main/resources/application-local.yaml"), directory.resolve("application-local.yaml"));
        String dev = Files.readString(Path.of("config/application-dev.yaml"));
        assertThat(dev).contains("file:./config/application-secrets.properties");
        dev = dev.replace("file:./config/application-secrets.properties", secret.toUri().toString());
        Files.writeString(directory.resolve("application-dev.yaml"), dev);
        for (String profiles : new String[]{"local,dev", "dev", "local", "local,dev,mock-ai", "local,dev,test", "local,dev,prod", "test", "prod"}) {
            new ApplicationContextRunner().withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues("spring.config.location=" + directory.toUri(), "spring.profiles.active=" + profiles)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    if (profiles.equals("local,dev") || profiles.equals("dev")) {
                        assertThat(context.getEnvironment().getProperty("bot-google-api-key")).isEqualTo("synthetic-value-only");
                        assertThat(context.getEnvironment().getProperty("spring.ai.openai.api-key")).isEqualTo("synthetic-openai-key");
                        assertThat(context.getEnvironment().getProperty("bot-ai-provider")).isNull();
                    } else {
                        assertThat(context.getEnvironment().getProperty("bot-google-api-key")).isNull();
                        assertThat(context.getEnvironment().getProperty("bot-openai-api-key")).isNull();
                    }
                    if (profiles.startsWith("local") || profiles.equals("dev")) {
                        assertThat(context.getEnvironment().getProperty("spring.ai.google.genai.tts.model")).isEqualTo("gemini-3.8-flash-tts");
                        assertThat(context.getEnvironment().getProperty("spring.ai.google.genai.transcription.model")).isEqualTo("gemini-3.5-transcribe");
                        assertThat(context.getEnvironment().getProperty("spring.ai.openai.chat.model")).isEqualTo("gpt-5-mini");
                    }
                });
        }
        new ApplicationContextRunner().withInitializer(new ConfigDataApplicationContextInitializer())
            .withPropertyValues("spring.config.location=" + directory.toUri(), "spring.profiles.active=local,dev", "bot-google-api-key=synthetic-override")
            .run(context -> assertThat(context.getEnvironment().getProperty("bot-google-api-key")).isEqualTo("synthetic-override"));
        Files.delete(secret);
        new ApplicationContextRunner().withInitializer(new ConfigDataApplicationContextInitializer())
            .withPropertyValues("spring.config.location=" + directory.toUri(), "spring.profiles.active=local,dev")
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getEnvironment().getProperty("bot-google-api-key")).isNull();
            });
    }
}
