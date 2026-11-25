package io.github.samzhu.bot.bootstrap;

import org.springframework.jdbc.core.JdbcTemplate;

/** Provisions the canonical local assistant without overwriting an existing Bot. */
public class DefaultBotInitializer {
    private final JdbcTemplate jdbc;

    public DefaultBotInitializer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void run(InitializationSql.Bot bot) {
        jdbc.update("""
            INSERT INTO bot (id, name, target, system_prompt, created_at, updated_at)
            VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            ON CONFLICT (id) DO NOTHING
        """, bot.id(), bot.name(), bot.target(), bot.systemPrompt());
        if (!Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from bot where id=? and target=?)", Boolean.class, bot.id(), bot.target()))) {
            throw new IllegalStateException("Existing default Bot has an incompatible target");
        }
    }
}
