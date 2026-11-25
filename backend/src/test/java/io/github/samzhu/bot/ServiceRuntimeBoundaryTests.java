package io.github.samzhu.bot;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ServiceRuntimeBoundaryTests {
    @TempDir Path temporary;

    @Test void productionRuntimeDoesNotExposeTheReleaseAdminEntrypoint() throws Exception {
        Path source = temporary.resolve("ServiceRuntimeProbe.java");
        Files.writeString(source, """
                public class ServiceRuntimeProbe {
                    public static void main(String[] args) throws Exception {
                        try {
                            Class.forName("io.github.samzhu.bot.bootstrap.ReleaseProvisioningApplication", false,
                                    Thread.currentThread().getContextClassLoader());
                            throw new AssertionError("release admin entrypoint is present in service production runtime");
                        } catch (ClassNotFoundException expected) {
                            // The production runtime must not expose the release entrypoint.
                        }
                    }
                }
                """);
        Path log = temporary.resolve("probe.log");
        var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "--class-path", System.getProperty("service.production.classpath"), source.toString())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        if (!process.waitFor(60, TimeUnit.SECONDS)) { process.destroyForcibly(); fail("service runtime probe timed out"); }
        assertEquals(0, process.exitValue(), () -> {
            try { return Files.readString(log); } catch (java.io.IOException ex) { return ex.toString(); }
        });
    }
    @Test void productionRuntimeContainsOnlyServiceReleaseMetadata() throws Exception {
        Path source = temporary.resolve("ServiceContentProbe.java");
        Files.writeString(source, """
                public class ServiceContentProbe {
                    public static void main(String[] args) throws Exception {
                        var loader = Thread.currentThread().getContextClassLoader();
                        for (String name : new String[]{"liquibase.Liquibase", "io.github.samzhu.bot.bootstrap.ReleaseMigration",
                                "io.github.samzhu.bot.bootstrap.DevProvisioningConfiguration"}) {
                            try { Class.forName(name, false, loader); throw new AssertionError("unexpected class: " + name); }
                            catch (ClassNotFoundException expected) { }
                        }
                        for (String name : new String[]{"db/changelog/db.changelog-master.yaml", "db/migration/V1__initial_schema.sql",
                                "db/initialization/prod.sql", "db/initialization/dev.sql", "pricing/catalog.yaml", "release/dev.json"}) {
                            if (loader.getResource(name) != null) throw new AssertionError("unexpected resource: " + name);
                        }
                        if (loader.getResource("release/prod.json") == null) throw new AssertionError("production metadata missing");
                    }
                }
                """);
        Path log = temporary.resolve("content.log");
        var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "--class-path", System.getProperty("service.production.classpath"), source.toString())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        if (!process.waitFor(60, TimeUnit.SECONDS)) { process.destroyForcibly(); fail("service content probe timed out"); }
        assertEquals(0, process.exitValue(), () -> {
            try { return Files.readString(log); } catch (java.io.IOException ex) { return ex.toString(); }
        });
    }

}
