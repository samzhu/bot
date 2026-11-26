package io.github.samzhu.bot;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/** Common real HTTP and PostgreSQL configuration for backend integration tests. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@ActiveProfiles("test")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "server.address=127.0.0.1",
            "management.endpoint.health.probes.enabled=true",
            "spring.config.location=classpath:/"
        })
@Import({TestcontainersConfiguration.class, BackendIntegrationTestConfiguration.class})
public @interface BackendIntegrationTest {
}
