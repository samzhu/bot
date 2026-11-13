package io.github.samzhu.bot.usage;

/** Execution-owned Run state read inside the caller's existing transaction. */
public interface RunAdmissionStateReader {
    enum Status { ACTIVE, COMPLETED, TERMINAL, NOT_FOUND }
    record State(Status status, String conversationId) {}

    /** Lock the Run after its registration and before its attempts; never start a new transaction. */
    State readForUpdate(String runId);
}
