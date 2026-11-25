package io.github.samzhu.bot.bootstrap;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.docker.compose.core.DockerComposeOrigin;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.boot.origin.OriginProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

import javax.sql.DataSource;
import java.util.Objects;

@Configuration(proxyBeanMethods = false)
@Profile("dev & !test & !prod")
@ConditionalOnProperty(name = "spring.docker.compose.enabled", havingValue = "true")
@Import({ReleaseMigration.class, ReleaseProvisioner.class, DefaultBotInitializer.class})
class DevProvisioningConfiguration {
    @Bean
    SmartInitializingSingleton provisionComposeDatabase(ReleaseProvisioner provisioner, Environment environment,
            ObjectProvider<JdbcConnectionDetails> connectionDetails, DataSource dataSource) {
        return () -> {
            try {
                var details = connectionDetails.getIfUnique();
                if (environment.containsProperty("spring.datasource.url")
                        || environment.containsProperty("spring.datasource.hikari.jdbc-url")
                        || !(details instanceof OriginProvider origin && origin.getOrigin() instanceof DockerComposeOrigin)
                        || !(dataSource instanceof HikariDataSource hikari)
                        || hikari.getDataSource() != null || hikari.getDataSourceClassName() != null
                        || hikari.getConnectionInitSql() != null
                        || !Objects.equals(hikari.getJdbcUrl(), details.getJdbcUrl())
                        || !Objects.equals(hikari.getUsername(), details.getUsername())) {
                    throw new IllegalStateException("Dev provisioning requires the Compose PostgreSQL datasource");
                }
                try (var connection = dataSource.getConnection()) {
                    var metadata = connection.getMetaData();
                    if (!Objects.equals(details.getJdbcUrl(), metadata.getURL())
                            || !Objects.equals(details.getUsername(), metadata.getUserName())
                            || !"PostgreSQL".equals(metadata.getDatabaseProductName())) {
                        throw new IllegalStateException("Dev datasource does not match the Compose connection");
                    }
                }
                // Finish both transactions before the context refresh starts scheduled work.
                provisioner.migrateSchema();
                provisioner.initializeDev();
            } catch (Exception ex) {
                throw new IllegalStateException("Dev provisioning failed", ex);
            }
        };
    }
}
