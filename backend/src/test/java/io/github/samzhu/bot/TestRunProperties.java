package io.github.samzhu.bot;

import io.github.samzhu.bot.execution.internal.RunProperties;

import java.time.Duration;

public class TestRunProperties extends RunProperties {

    private static final TestRunProperties INSTANCE = new TestRunProperties();

    public static TestRunProperties getInstance() {
        return INSTANCE;
    }

    private volatile Duration overrideTimeout;
    private volatile Duration overrideHeartbeatInterval;

    public TestRunProperties() {
        super(Duration.ofSeconds(180), Duration.ofSeconds(15));
    }

    public void setTimeout(Duration timeout) {
        this.overrideTimeout = timeout;
    }

    public void setHeartbeatInterval(Duration interval) {
        this.overrideHeartbeatInterval = interval;
    }

    public void reset() {
        this.overrideTimeout = null;
        this.overrideHeartbeatInterval = null;
    }

    @Override
    public Duration getTimeout() {
        return overrideTimeout != null ? overrideTimeout : super.getTimeout();
    }

    @Override
    public Duration getHeartbeatInterval() {
        return overrideHeartbeatInterval != null ? overrideHeartbeatInterval : super.getHeartbeatInterval();
    }
}
