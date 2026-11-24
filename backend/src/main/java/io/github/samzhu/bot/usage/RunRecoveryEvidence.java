package io.github.samzhu.bot.usage;

/** Validates immutable usage evidence while holding the caller's existing transaction locks. */
public interface RunRecoveryEvidence {
    /** Locks registration then Run and verifies the exact settled prompt-failure fact. */
    boolean lockPromptFailure(WorkspaceAdmission.PromptFailureFact fact);
    /** Locks registration then Run and verifies the exact settled supersession fact. */
    boolean lockSuperseded(WorkspaceAdmission.SupersededNotDispatched fact);
}
