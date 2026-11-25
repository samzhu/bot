package io.github.samzhu.bot.bootstrap;

import io.github.samzhu.release.ReleaseConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;

/** One-off release process using the same configuration as the service. */
public final class ReleaseProvisioningApplication {
    private ReleaseProvisioningApplication() {}

    public static void main(String[] args) throws Exception {
        boolean schemaOnly = schemaOnly(args);
        var app = new SpringApplication(ReleaseConfiguration.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        try (var context = app.run(args)) {
            var provisioner = context.getBean(ReleaseProvisioner.class);
            if (schemaOnly) provisioner.migrateSchema();
            else provisioner.run();
        }
    }

    private static boolean schemaOnly(String[] args) {
        String prefix = "--bot.release.action";
        String action = null;
        for (String arg : args) {
            if (!arg.equals(prefix) && !arg.startsWith(prefix + "=")) continue;
            if (action != null || !arg.equals(prefix + "=schema-only")) {
                throw new IllegalArgumentException("Invalid or repeated bot.release.action; expected schema-only");
            }
            action = "schema-only";
        }
        return action != null;
    }
}
