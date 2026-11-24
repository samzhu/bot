package io.github.samzhu.bot.usage;

/** Execution-owned fence checked inside a caller's existing transaction. */
public interface RunOwnershipGuard {
    record Fence(String runId, String token, long generation) {}
    /** Caller holds the registration and Run row locks before this call. */
    boolean lockOwner(Fence fence);
    /** Caller holds registration and Run row locks; returns null unless a known lease is expired. */
    Fence takeExpired(String runId);
    /** Caller retains the same locks and ownership fence in its transaction. */
    boolean failTakenRun(Fence fence, String conversationId, String reason);
}
