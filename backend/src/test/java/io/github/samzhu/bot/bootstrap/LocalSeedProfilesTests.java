package io.github.samzhu.bot.bootstrap;

import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

class LocalSeedProfilesTests {
    @Test void defaultBotRunsAfterMigration() {
        assertThat(DatabaseInitializer.class.getAnnotation(Order.class).value())
                .isLessThan(DefaultBotInitializer.class.getAnnotation(Order.class).value());
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        new DefaultBotInitializer(jdbc).run(null);
        verify(jdbc).update(contains("'bot-general'"));
        verifyNoMoreInteractions(jdbc);
    }
}
