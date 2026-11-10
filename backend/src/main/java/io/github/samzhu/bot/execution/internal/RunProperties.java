package io.github.samzhu.bot.execution.internal;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class RunProperties {

    private final Duration timeout;
    private final Duration heartbeatInterval;

    public RunProperties(
            @Value("${bot.run.timeout:180s}") Duration timeout,
            @Value("${bot.run.heartbeat-interval:15s}") Duration heartbeatInterval) {
        this.timeout = timeout;
        this.heartbeatInterval = heartbeatInterval;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public Duration getHeartbeatInterval() {
        return heartbeatInterval;
    }
}
