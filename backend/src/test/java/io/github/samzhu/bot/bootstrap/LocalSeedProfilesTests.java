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
        when(jdbc.queryForObject(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.eq(Boolean.class), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenReturn(true);
        new DefaultBotInitializer(jdbc).run(new InitializationSql.Bot("bot-general", "General Assistant", "LOCAL", "prompt"));
        verify(jdbc).update(contains("INSERT INTO bot"), org.mockito.ArgumentMatchers.eq("bot-general"), org.mockito.ArgumentMatchers.eq("General Assistant"), org.mockito.ArgumentMatchers.eq("LOCAL"), org.mockito.ArgumentMatchers.eq("prompt"));
        verify(jdbc).queryForObject(contains("target=?"), org.mockito.ArgumentMatchers.eq(Boolean.class), org.mockito.ArgumentMatchers.eq("bot-general"), org.mockito.ArgumentMatchers.eq("LOCAL"));
        verifyNoMoreInteractions(jdbc);
    }
}
