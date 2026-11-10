package io.github.samzhu.bot.execution.internal;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * Spring configuration for execution scheduling.
 *
 * RATIONALE: TaskScheduler is managed as a singleton Spring bean to provide
 * deterministic application-level deadlines and periodic SSE heartbeat pings
 * without leaking unbounded platform threads or creating ad-hoc executor pools.
 */
@Configuration
public class ExecutionConfiguration {

    @Bean
    public ThreadPoolTaskScheduler botTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(4);
        scheduler.setThreadNamePrefix("bot-exec-scheduler-");
        scheduler.setDaemon(true);
        // Do not call scheduler.initialize() manually; Spring's afterPropertiesSet() manages bean lifecycle.
        return scheduler;
    }
}
