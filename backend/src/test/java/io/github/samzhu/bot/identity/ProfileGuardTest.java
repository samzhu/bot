package io.github.samzhu.bot.identity;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProfileGuardTest {

    @Test
    void shouldFailFastWhenNoPermittedProfileIsActive() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("production");
        ProfileGuard guard = new ProfileGuard(env);

        assertThrows(IllegalStateException.class, guard::validateActiveProfiles);
    }

    @Test
    void shouldPassWhenLocalProfileIsActive() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("local");
        env.setProperty("spring.docker.compose.enabled", "false");
        ProfileGuard guard = new ProfileGuard(env);

        assertDoesNotThrow(guard::validateActiveProfiles);
    }

    @Test
    void shouldPassWhenTestProfileIsActive() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("test");
        ProfileGuard guard = new ProfileGuard(env);

        assertDoesNotThrow(guard::validateActiveProfiles);
    }
    @Test
    void explicitGeminiCannotRunAlongsideMock() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("local", "mock-ai");
        env.setProperty("spring.ai.model.chat", "google-genai");
        assertThrows(IllegalStateException.class, () -> new ProfileGuard(env).validateActiveProfiles());
    }
    @Test
    void rejectsInvalidProviderAndGeminiOutsideLocal() {
        for (String[] profiles : new String[][]{{"prod"}, {"local", "prod"}, {"test"}, {"local", "test"}, {"local", "mock-ai"}}) {
            MockEnvironment env = new MockEnvironment();
            env.setActiveProfiles(profiles);
            env.setProperty("spring.ai.model.chat", "google-genai");
            assertThrows(IllegalStateException.class, () -> new ProfileGuard(env).validateActiveProfiles());
        }
        for (String value : new String[]{"", "other", "GEMINI"}) {
            MockEnvironment env = new MockEnvironment(); env.setActiveProfiles("local");
            env.setProperty("bot-ai-provider", value);
            assertThrows(IllegalStateException.class, () -> new ProfileGuard(env).validateActiveProfiles());
        }
        MockEnvironment valid = new MockEnvironment(); valid.setActiveProfiles("local");
        valid.setProperty("spring.ai.model.chat", "google-genai");
        valid.setProperty("spring.docker.compose.enabled", "false");
        assertDoesNotThrow(() -> new ProfileGuard(valid).validateActiveProfiles());
    }
}
