package io.github.samzhu.bot;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import(Postgres18CompatibilityIntegrationTests.Pg18Config.class)
class Postgres18CompatibilityIntegrationTests {

    @TestConfiguration(proxyBeanMethods = false)
    static class Pg18Config {
        @Bean
        @ServiceConnection
        PostgreSQLContainer postgresContainer() {
            return PostgresTestImage.container();
        }
    }

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void postgres18ProvidesVectorAndJsonbThroughServiceConnection() {
        assertThat(jdbc.queryForObject("show server_version_num", Integer.class)).isBetween(180000, 189999);
        jdbc.execute("CREATE EXTENSION IF NOT EXISTS vector");
        assertThat(jdbc.queryForObject("SELECT '[1,2,3]'::vector::text", String.class)).isEqualTo("[1,2,3]");
        assertThat(jdbc.queryForObject("SELECT '{\"n\":9007199254740993}'::jsonb->>'n'", String.class))
                .isEqualTo("9007199254740993");
    }
}
