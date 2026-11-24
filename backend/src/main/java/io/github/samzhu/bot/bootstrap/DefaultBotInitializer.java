package io.github.samzhu.bot.bootstrap;

import org.springframework.boot.ApplicationArguments;
import org.springframework.jdbc.core.JdbcTemplate;

/** Provisions the canonical local assistant without overwriting an existing Bot. */
public class DefaultBotInitializer {
    private final JdbcTemplate jdbc;

    public DefaultBotInitializer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void run(ApplicationArguments args) {
        jdbc.update("""
            INSERT INTO bot (id, name, target, system_prompt, created_at, updated_at)
            VALUES ('bot-general', 'General Assistant', 'LOCAL', 'You are a helpful, accurate, and concise AI coding assistant.', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            ON CONFLICT (id) DO NOTHING;
        """);
        if (!Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from bot where id='bot-general' and target='LOCAL')", Boolean.class))) {
            throw new IllegalStateException("Existing default Bot is not LOCAL");
        }
    }
}
