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
    private static final String NONCE_ENV = "BOT75_FIXTURE_NONCE";
    private static final String NONCE_LABEL = "io.github.samzhu.bot.browser-fixture.nonce";

    public static void main(String[] args) {
        System.out.println("BROWSER_FIXTURE_PROCESS " + ProcessHandle.current().pid());
        System.out.flush();
        if (args.length != 0) throw new IllegalArgumentException("Fixture accepts no arguments");
        var environment = System.getenv();
        String nonce = environment.get(NONCE_ENV);
        if (nonce != null && !nonce.matches("[0-9a-f]{32}")) {
            throw new IllegalStateException("Invalid fixture nonce");
        }
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
        boolean titleFixture = "1".equals(environment.get("BOT85_TITLE_FIXTURE"));
        if (titleFixture && nonce == null) throw new IllegalStateException("Title fixture requires owned nonce");
        SpringApplication app = titleFixture
                ? new SpringApplication(BotApplication.class, Fixture.class, TitleFixture.class, TestUsageCaptureConfiguration.class)
                : new SpringApplication(BotApplication.class, Fixture.class, TestUsageCaptureConfiguration.class);
        if (titleFixture) app.setAdditionalProfiles("test", "browser-fixture", "browser-title-fixture");
        else app.setAdditionalProfiles("test", "browser-fixture");
        var context = app.run("--server.address=127.0.0.1", "--server.port=0",
                "--spring.docker.compose.enabled=false", "--spring.config.location=classpath:/");
        try {
            var postgres = context.getBean(PostgreSQLContainer.class);
            var dataSource = context.getBean(DataSource.class);
            String database = postgres.getDatabaseName();
            String username = postgres.getUsername();
            try (var connection = dataSource.getConnection()) {
                if (!connection.getMetaData().getURL().equals(postgres.getJdbcUrl())
                        || (nonce != null && (!java.util.Objects.equals(database, connection.getCatalog())
                        || !java.util.Objects.equals(username, connection.getMetaData().getUserName())))) {
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
            if (nonce != null) {
                String id = postgres.getContainerId();
                var info = postgres.getCurrentContainerInfo();
                if (id == null || !id.matches("[0-9a-f]{64}")
                        || database == null || !database.matches("[a-z_][a-z0-9_]{0,62}")
                        || username == null || !username.matches("[a-z_][a-z0-9_]{0,62}")
                        || !id.equals(info.getId()) || info.getState() == null
                        || !Boolean.TRUE.equals(info.getState().getRunning())
                        || info.getConfig() == null || info.getConfig().getLabels() == null
                        || !nonce.equals(info.getConfig().getLabels().get(NONCE_LABEL))) {
                    throw new IllegalStateException("Fixture PostgreSQL identity verification failed");
                }
                // The private marker lets the owning test runner recheck this container before each mutation.
                System.out.println("BROWSER_FIXTURE_POSTGRES " + id + " " + database + " " + username + " "
                        + ProcessHandle.current().pid() + " " + nonce);
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
            var postgres = new PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg16"));
            String nonce = System.getenv(NONCE_ENV);
            if (nonce != null) postgres.withLabel(NONCE_LABEL, nonce);
            return postgres;
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TitleFixture {
        @Bean ControlledTitle browserFixtureTitle() { return new ControlledTitle(); }
    }

    static final class ControlledTitle implements io.github.samzhu.bot.conversation.ConversationTitleGenerator {
        final java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        @Override public String generate(String input) throws Exception {
            calls.incrementAndGet();
            if (!release.await(20, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("Synthetic title timed out");
            return "Synthetic browser title";
        }
    }

    @RestController
    @Profile("browser-title-fixture")
    static class TitleControl {
        private final ControlledTitle title;
        TitleControl(ControlledTitle title) { this.title = title; }
        @GetMapping("/api/__fixture/title-status")
        Map<String, Object> status(@org.springframework.web.bind.annotation.RequestHeader("X-Fixture-Nonce") String nonce) {
            if (!java.util.Objects.equals(System.getenv(NONCE_ENV), nonce)) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN);
            return Map.of("calls", title.calls.get());
        }
        @org.springframework.web.bind.annotation.PostMapping("/api/__fixture/title-release")
        Map<String, Object> release(@org.springframework.web.bind.annotation.RequestHeader("X-Fixture-Nonce") String nonce) {
            if (!java.util.Objects.equals(System.getenv(NONCE_ENV), nonce)) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN);
            title.release.countDown();
            return Map.of("calls", title.calls.get());
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
