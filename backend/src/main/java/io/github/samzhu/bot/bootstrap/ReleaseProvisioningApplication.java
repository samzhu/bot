package io.github.samzhu.bot.bootstrap;

import io.github.samzhu.release.ReleaseConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;

/** One-off release process using the same configuration as the service. */
public final class ReleaseProvisioningApplication {
    private ReleaseProvisioningApplication() {}

    public static void main(String[] args) throws Exception {
        var app = new SpringApplication(ReleaseConfiguration.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        try (var context = app.run(args)) {
            context.getBean(ReleaseProvisioner.class).run();
        }
    }
}
