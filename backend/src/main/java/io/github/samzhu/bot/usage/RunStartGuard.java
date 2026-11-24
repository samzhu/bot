package io.github.samzhu.bot.usage;

/** Locks and verifies a durable registration start capability in the caller's transaction. */
public interface RunStartGuard {
    boolean lockAndCheck(WorkspaceAdmission.Registration registration);
}
