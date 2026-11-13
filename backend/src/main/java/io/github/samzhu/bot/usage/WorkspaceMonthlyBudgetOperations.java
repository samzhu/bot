package io.github.samzhu.bot.usage;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.concurrent.CompletionStage;

/** Server-side monthly budget and contribution contract used by workspace admission. */
public interface WorkspaceMonthlyBudgetOperations {
    record Scope(String workspaceId, WorkspaceAdmission.Partition partition, YearMonth period) {}
    record Budget(Scope scope, boolean enabled, BigDecimal capUsd, BigDecimal knownSubtotalUsd,
                  boolean terminalEffectiveUnknown, long configVersion, long gateGeneration,
                  long terminalUnknownCount, boolean recoveryRequired) {}
    record ReadCommand(Scope scope, String actorId) {}
    record CurrentReadCommand(String workspaceId,WorkspaceAdmission.Partition partition,String actorId) {}
    record ConfigureCommand(Scope scope, String actorId, boolean enabled, BigDecimal capUsd,
                            long expectedConfigVersion) {}
    record IdempotentConfigureCommand(Scope scope,String actorId,boolean enabled,String capUsd,
                                      String expectedConfigVersion,String idempotencyKey) {}
    enum ConfigureOutcome { APPLIED, REPLAYED }
    record ConfigureResult(ConfigureOutcome outcome,Budget budget) {}
    CompletionStage<Budget> read(ReadCommand command);
    CompletionStage<Budget> readCurrent(CurrentReadCommand command);
    CompletionStage<Budget> configure(ConfigureCommand command);
    CompletionStage<ConfigureResult> configureIdempotent(IdempotentConfigureCommand command);
}
