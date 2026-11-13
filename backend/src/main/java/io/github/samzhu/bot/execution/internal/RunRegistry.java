package io.github.samzhu.bot.execution.internal;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

@Component
public class RunRegistry {

    private final ConcurrentHashMap<String, RunHandle> activeHandles = new ConcurrentHashMap<>();
    // Bounded fair stripes serialize publication and cancellation of the same run without
    // retaining a lock per historical ID. Model execution is outside these gates.
    // These in-memory stripes coordinate only one application process, not multiple nodes.
    private final java.util.concurrent.locks.ReentrantLock[] lifecycleGates = java.util.stream.IntStream.range(0, 256)
            .mapToObj(i -> new java.util.concurrent.locks.ReentrantLock(true))
            .toArray(java.util.concurrent.locks.ReentrantLock[]::new);

    public java.util.concurrent.locks.ReentrantLock lifecycleGate(String runId) {
        return lifecycleGates[Math.floorMod(runId.hashCode(), lifecycleGates.length)];
    }

    public void register(String runId, RunHandle handle) {
        activeHandles.put(runId, handle);
    }

    public RunHandle get(String runId) {
        return activeHandles.get(runId);
    }

    public RunHandle remove(String runId) {
        return activeHandles.remove(runId);
    }

    public boolean remove(String runId, RunHandle expected) {
        return activeHandles.remove(runId, expected);
    }

    public boolean hasActiveRunForConversation(String conversationId) {
        if (conversationId == null) return false;
        return activeHandles.values().stream().anyMatch(h -> conversationId.equals(h.getConversationId()));
    }

    public RunHandle getActiveHandleForConversation(String conversationId) {
        if (conversationId == null) return null;
        return activeHandles.values().stream()
                .filter(h -> conversationId.equals(h.getConversationId()))
                .findFirst()
                .orElse(null);
    }
}
