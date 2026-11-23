package io.github.samzhu.bot;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import javax.sql.DataSource;
import java.util.Map;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** Test-only entry point. Its readiness line is consumed solely from the owned child process. */
public final class BrowserFixtureApplication {
    public static void main(String[] args) {
        System.out.println("BROWSER_FIXTURE_PROCESS " + ProcessHandle.current().pid());
        System.out.flush();
        if (args.length != 0) throw new IllegalArgumentException("Fixture accepts no arguments");
        var environment = System.getenv();
        if (environment.keySet().stream().anyMatch(key -> key.startsWith("SPRING_") || key.equals("BOT_AI_PROVIDER"))) {
            throw new IllegalStateException("Unsafe fixture selector or Spring configuration");
        }
        if (environment.keySet().stream().anyMatch(key -> key.startsWith("GOOGLE_") || key.startsWith("GEMINI_") || key.equals("BOT_GOOGLE_API_KEY"))) {
            throw new IllegalStateException("Unsafe fixture provider credentials or endpoint");
        }
        // Fault injection never reaches Spring or a generation-capable context.
        if ("1".equals(environment.get("BOT72_TEST_HANG_BEFORE_READY"))) {
            try { Thread.sleep(120_000); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            throw new IllegalStateException("Fixture startup fault did not terminate");
        }
        SpringApplication app = new SpringApplication(BotApplication.class, Fixture.class);
        app.setAdditionalProfiles("test", "browser-fixture");
        var context = app.run("--server.address=127.0.0.1", "--server.port=0",
                "--spring.docker.compose.enabled=false", "--spring.config.location=classpath:/");
        try {
            var postgres = context.getBean(PostgreSQLContainer.class);
            var dataSource = context.getBean(DataSource.class);
            try (var connection = dataSource.getConnection()) {
                if (!connection.getMetaData().getURL().equals(postgres.getJdbcUrl())) {
                    throw new IllegalStateException("Fixture datasource is not the owned container");
                }
            } catch (java.sql.SQLException e) {
                throw new IllegalStateException("Fixture datasource verification failed", e);
            }
            if (!(context.getBean(ChatModel.class) instanceof ControlledChatModel)
                    || !context.getBeansOfType(com.google.genai.Client.class).isEmpty()
                    || !context.getEnvironment().matchesProfiles("test & !local & !prod")
                    || context.getEnvironment().getProperty("spring.docker.compose.enabled", Boolean.class, true)) {
                throw new IllegalStateException("Unsafe fixture model configuration");
            }
            int port = ((WebServerApplicationContext) context).getWebServer().getPort();
            System.out.println("BROWSER_FIXTURE_READY " + port + " " + ProcessHandle.current().pid());
            System.out.flush();
        } catch (RuntimeException e) {
            context.close();
            throw e;
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Fixture {
        @Bean @Primary
        ChatModel browserFixtureModel() { return new ControlledChatModel(); }

        @Bean @ServiceConnection
        PostgreSQLContainer browserFixturePostgres() {
            return new PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg16"));
        }
    }

    @RestController
    @Profile("browser-fixture")
    static class Evidence {
        private final JdbcTemplate jdbc;
        Evidence(JdbcTemplate jdbc) { this.jdbc = jdbc; }

        @GetMapping("/api/__fixture/evidence")
        Map<String, Object> evidence(@RequestParam String conversation, @RequestParam String run) {
            return Map.of(
                "conversation", jdbc.queryForObject("select count(*) from conversation where id = ?", Integer.class, conversation),
                "run", jdbc.queryForObject("select count(*) from chat_run where id = ? and conversation_id = ? and status = 'COMPLETED'", Integer.class, run, conversation),
                "messages", jdbc.queryForObject("select count(*) from message where run_id = ? and conversation_id = ?", Integer.class, run, conversation));
        }
    }
}
