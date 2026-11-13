package io.github.samzhu.bot.execution.internal;

import io.github.samzhu.bot.usage.RunAdmissionStateReader;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Reads execution-owned Run state in the caller's registration-first transaction. */
@Component
public class JdbcRunAdmissionStateReader implements RunAdmissionStateReader {
    private final ChatRunRepository runs;

    public JdbcRunAdmissionStateReader(ChatRunRepository runs) { this.runs = runs; }

    @Override
    public State readForUpdate(String runId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Run admission lookup requires the caller's transaction");
        return runs.findForAdmission(runId).map(run -> new State(switch (run.status()) {
            case "PENDING", "RUNNING" -> Status.ACTIVE;
            case "COMPLETED" -> Status.COMPLETED;
            case "FAILED", "CANCELLED" -> Status.TERMINAL;
            default -> throw new IllegalStateException("Unknown Run state");
        }, run.conversationId())).orElseGet(() -> new State(Status.NOT_FOUND, null));
    }
}
