package io.github.samzhu.bot.execution.internal;

import io.github.samzhu.bot.usage.WorkspaceAdmission;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.time.Duration;

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
    @ConditionalOnProperty(name = "bot.execution.startup-coordinator.enabled", havingValue = "true", matchIfMissing = true)
    public TerminalRunStartupCoordinator terminalRunStartupCoordinator(
            TerminalRunCompletionScanner scanner, WorkspaceAdmission admission,
            @Value("${bot.execution.startup-coordinator.budget:30s}") Duration startupBudget) {
        return new TerminalRunStartupCoordinator(scanner, admission, startupBudget);
    }

    // The injectable scheduler makes a queued dispatch cancellable and deterministically testable.
    @Bean(destroyMethod = "")
    public reactor.core.scheduler.Scheduler providerDispatchScheduler() {
        return reactor.core.scheduler.Schedulers.boundedElastic();
    }

    // Separate from provider streams so blocked SDK calls cannot starve cancellation accounting.
    @Bean(destroyMethod = "dispose")
    public reactor.core.scheduler.Scheduler usageFinalizationScheduler() {
        return reactor.core.scheduler.Schedulers.newBoundedElastic(2, 1000, "usage-finalization");
    }

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
