package io.github.samzhu.bot.bootstrap;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class LocalSeedProfilesTests {
    @Test void defaultBotRunsAfterMigration() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.eq(Boolean.class))).thenReturn(true);
        new DefaultBotInitializer(jdbc).run(null);
        verify(jdbc).update(contains("'bot-general'"));
        verify(jdbc).queryForObject(contains("target='LOCAL'"), org.mockito.ArgumentMatchers.eq(Boolean.class));
        verifyNoMoreInteractions(jdbc);
    }
}
