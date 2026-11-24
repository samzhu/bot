package io.github.samzhu.bot;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReleaseProvisioningIntegrationTests {
    @Test
    void releaseCommandPreparesAnEmptyDatabase() throws Exception {
        try (var postgres = PostgresTestImage.container()) {
            postgres.start();
            var launcher = Class.forName("io.github.samzhu.bot.bootstrap.ReleaseProvisioningApplication");
            launcher.getMethod("main", String[].class).invoke(null, (Object) new String[] {
                    "--spring.datasource.url=" + postgres.getJdbcUrl(),
                    "--spring.datasource.username=" + postgres.getUsername(),
                    "--spring.datasource.password=" + postgres.getPassword()
            });
            var jdbc = new JdbcTemplate(new DriverManagerDataSource(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
            assertEquals(1, jdbc.queryForObject("select count(*) from bot where id='bot-general'", Integer.class));
            assertEquals(1, jdbc.queryForObject("select count(*) from pricing_catalog_revision where source_revision='gemini-standard-2026-11-16'", Integer.class));
            launcher.getMethod("main", String[].class).invoke(null, (Object) new String[] {
                    "--spring.datasource.url=" + postgres.getJdbcUrl(),
                    "--spring.datasource.username=" + postgres.getUsername(),
                    "--spring.datasource.password=" + postgres.getPassword()
            });
            assertEquals(1, jdbc.queryForObject("select count(*) from bot where id='bot-general'", Integer.class));
            assertEquals(1, jdbc.queryForObject("select count(*) from pricing_catalog_revision where source_revision='gemini-standard-2026-11-16'", Integer.class));
            var conflict = assertThrows(java.lang.reflect.InvocationTargetException.class, () ->
                    launcher.getMethod("main", String[].class).invoke(null, (Object) new String[] {
                            "--spring.datasource.url=" + postgres.getJdbcUrl(),
                            "--spring.datasource.username=" + postgres.getUsername(),
                            "--spring.datasource.password=" + postgres.getPassword(),
                            "--bot.pricing.catalog.source-reference=conflicting-test-source"
                    }));
            assertTrue(conflict.getCause().getMessage().contains("Catalog revision payload conflict"));
            assertEquals(1, jdbc.queryForObject("select count(*) from pricing_catalog_revision where source_revision='gemini-standard-2026-11-16'", Integer.class));
        }
    }
}
