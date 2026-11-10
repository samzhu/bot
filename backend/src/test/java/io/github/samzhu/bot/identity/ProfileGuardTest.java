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
}
