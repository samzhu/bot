package io.github.samzhu.bot.usage;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.concurrent.CompletionStage;

public interface MemberWeeklyBudgetOperations {
    record Budget(String workspaceId, String memberUserId, WorkspaceAdmission.Partition partition,
                  Instant start, Instant end, boolean enabled, BigDecimal capUsd, BigDecimal knownSubtotalUsd,
                  long terminalUnknownCount, long configVersion, boolean recoveryRequired) {}
    record Read(String workspaceId, String memberUserId, WorkspaceAdmission.Partition partition, String actorId, boolean self) {}
    record Configure(String workspaceId, String memberUserId, WorkspaceAdmission.Partition partition, String actorId,
                     boolean enabled, String capUsd, String expectedConfigVersion, String idempotencyKey) {}
    record Result(String outcome, Budget budget) {}
    CompletionStage<Budget> read(Read command);
    CompletionStage<Result> configure(Configure command);
}
