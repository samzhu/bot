package io.github.samzhu.bot.bootstrap;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Ensures a usable local assistant even when demonstration data is disabled. */
@Component
@Order(15)
public class DefaultBotInitializer implements ApplicationRunner {
    private final JdbcTemplate jdbc;

    public DefaultBotInitializer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        jdbc.update("""
            INSERT INTO bot (id, name, target, system_prompt, created_at, updated_at)
            VALUES ('bot-general', 'General Assistant', 'LOCAL', 'You are a helpful, accurate, and concise AI coding assistant.', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            ON CONFLICT (id) DO NOTHING;
        """);
    }
}
