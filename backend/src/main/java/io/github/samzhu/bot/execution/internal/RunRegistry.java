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
