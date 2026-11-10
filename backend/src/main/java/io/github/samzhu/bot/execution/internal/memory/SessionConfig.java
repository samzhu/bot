package io.github.samzhu.bot.execution.internal.memory;

import org.springframework.ai.session.DefaultSessionService;
import org.springframework.ai.session.SessionRepository;
import org.springframework.ai.session.SessionService;
import org.springframework.ai.session.jdbc.JdbcSessionRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

@Configuration
public class SessionConfig {

    @Bean
    public SessionRepository sessionRepository(DataSource dataSource, PlatformTransactionManager transactionManager) {
        return JdbcSessionRepository.builder()
                .dataSource(dataSource)
                .transactionManager(transactionManager)
                .build();
    }

    @Bean
    public SessionService sessionService(SessionRepository sessionRepository) {
        return DefaultSessionService.builder()
                .sessionRepository(sessionRepository)
                .build();
    }
}
