package io.github.samzhu.bot.identity;

import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.Environment;

import java.util.Arrays;
import java.util.Set;

public class ProfileGuard implements ApplicationListener<ApplicationEnvironmentPreparedEvent> {

    private static final Set<String> ALLOWED_PROFILES = Set.of("local", "test");

    private final Environment environment;

    public ProfileGuard() {
        this.environment = null;
    }

    public ProfileGuard(Environment environment) {
        this.environment = environment;
    }

    public void validateActiveProfiles() {
        if (environment != null) {
            validate(environment);
        }
    }

    @Override
    public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
        validate(event.getEnvironment());
    }

    private void validate(Environment env) {
        String[] activeProfiles = env.getActiveProfiles();

        boolean allowed = Arrays.stream(activeProfiles)
                .anyMatch(ALLOWED_PROFILES::contains);

        if (!allowed) {
            throw new IllegalStateException(
                    "Production guard: Local Chat MVP only permits 'local' or 'test' profile. Active: "
                            + Arrays.toString(activeProfiles)
                            + ". Refusing to start without loopback guard.");
        }
    }
}
