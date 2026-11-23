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

        String provider = env.getProperty("bot-ai-provider");
        String standard = env.getProperty("spring.ai.model.chat");
        if (standard != null && !"google-genai".equals(standard)) {
            throw new IllegalStateException("Unsupported spring.ai.model.chat; select google-genai explicitly.");
        }
        if (env.matchesProfiles("dev & !mock-ai & !test & !prod") && standard == null && provider == null) {
            throw new IllegalStateException("Development requires an explicit chat provider selector.");
        }
        if (standard != null && provider != null && !"gemini".equals(provider)) {
            throw new IllegalStateException("Conflicting chat provider selectors.");
        }
        if (standard != null && !env.matchesProfiles("local & !mock-ai & !test & !prod")) {
            throw new IllegalStateException("Gemini requires local without mock-ai, test or prod.");
        }
        if (provider != null && !"gemini".equals(provider)) {
            throw new IllegalStateException("Unsupported bot-ai-provider; select gemini explicitly or leave it unset.");
        }
        if ("gemini".equals(provider) && !env.matchesProfiles("local & !mock-ai & !test & !prod")) {
            throw new IllegalStateException("Gemini requires local without mock-ai, test or prod.");
        }

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
