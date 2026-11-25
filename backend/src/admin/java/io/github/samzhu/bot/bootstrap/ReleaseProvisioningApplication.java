package io.github.samzhu.bot.bootstrap;

import io.github.samzhu.release.ReleaseConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.core.env.Profiles;

/** One-off release process using the same configuration as the service. */
public final class ReleaseProvisioningApplication {
    private ReleaseProvisioningApplication() {}

    public static void main(String[] args) throws Exception {
        String action = option(args, "bot.release.action");
        String legacyDatabase = option(args, "bot.release.expected-database");
        if (legacyDatabase != null) throw new IllegalArgumentException("bot.release.expected-database is no longer supported");
        String expectedJdbcUrl = option(args, "bot.release.expected-jdbc-url");
        if (!"schema-only".equals(action) && !"initialize-prod".equals(action))
            throw new IllegalArgumentException("Expected bot.release.action=schema-only or initialize-prod");
        if (("initialize-prod".equals(action) && (expectedJdbcUrl == null || expectedJdbcUrl.isBlank()))
                || ("schema-only".equals(action) && expectedJdbcUrl != null))
            throw new IllegalArgumentException("initialize-prod requires bot.release.expected-jdbc-url");
        var app = new SpringApplication(ReleaseConfiguration.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        try (var context = app.run(args)) {
            var provisioner = context.getBean(ReleaseProvisioner.class);
            if ("schema-only".equals(action)) provisioner.migrateSchema();
            else {
                if (context.getEnvironment().acceptsProfiles(Profiles.of("dev | local | test")))
                    throw new IllegalStateException("Production initialization is unavailable in development or test profiles");
                provisioner.initializeProd(expectedJdbcUrl);
            }
        }
    }

    private static String option(String[] args, String name) {
        String prefix = "--" + name;
        String value = null;
        for (String arg : args) {
            if (!arg.equals(prefix) && !arg.startsWith(prefix + "=")) continue;
            if (value != null || !arg.startsWith(prefix + "=") || arg.length() == prefix.length() + 1)
                throw new IllegalArgumentException("Invalid or repeated " + name);
            value = arg.substring(prefix.length() + 1);
        }
        return value;
    }
}
