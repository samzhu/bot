package io.github.samzhu.bot.bot;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Tracks the Bot rows owned by one case for exact-ID cleanup. */
final class BotCaseRows {
    private final String prefix = "bot-case-" + UUID.randomUUID() + "-";
    private final Set<String> ids = new LinkedHashSet<>();

    String id(String suffix) {
        return prefix + suffix;
    }

    String ownedId(String suffix) {
        String id = id(suffix);
        ids.add(id);
        return id;
    }

    void deleteFrom(JdbcTemplate jdbc) {
        for (String id : ids) {
            jdbc.update("DELETE FROM bot WHERE id = ?", id);
        }
    }
}
