package io.github.samzhu.bot.execution.internal;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

@Component
public class RunRegistry {

    private final ConcurrentHashMap<String, RunHandle> activeHandles = new ConcurrentHashMap<>();

    public void register(String runId, RunHandle handle) {
        activeHandles.put(runId, handle);
    }

    public RunHandle get(String runId) {
        return activeHandles.get(runId);
    }

    public RunHandle remove(String runId) {
        return activeHandles.remove(runId);
    }
}
