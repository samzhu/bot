package io.github.samzhu.bot.usage.internal.admission;

import io.github.samzhu.bot.usage.WorkspaceAdmission;
import io.github.samzhu.bot.usage.WorkspaceMonthlyBudgetOperations;
import io.github.samzhu.bot.workspace.WorkspaceOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

@Repository
public class JdbcWorkspaceMonthlyBudgetOperations implements WorkspaceMonthlyBudgetOperations {
    private static final BigDecimal MAX_CAP = new BigDecimal("999999999999999999.999999999999");
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final WorkspaceOperations workspaces;
    private final WorkspaceLane lane;
    private final BudgetCancellationOutbox outbox;
    private final DefaultWorkspaceAdmission admission;
    private final java.time.Clock clock;

    public JdbcWorkspaceMonthlyBudgetOperations(JdbcTemplate jdbc,
                                                org.springframework.transaction.PlatformTransactionManager manager,
                                                WorkspaceOperations workspaces, WorkspaceLane lane, BudgetCancellationOutbox outbox,
                                                DefaultWorkspaceAdmission admission,java.time.Clock clock) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(manager);
        this.workspaces = workspaces;
        this.lane = lane;
        this.outbox = outbox;
        this.admission = admission;
        this.clock = clock;
    }

    @Override
    public CompletionStage<Budget> read(ReadCommand command) {
        if (command == null || command.scope() == null || command.scope().workspaceId() == null)
            return CompletableFuture.failedFuture(new BudgetAccessDeniedException());
        return lane.submit(command.scope().workspaceId(), () -> {
            Scope scope = authorizeRead(command);
            return tx.execute(status -> {
                ensureRow(scope);
                return select(scope);
            });
        });
    }

    @Override
    public CompletionStage<Budget> readCurrent(CurrentReadCommand command) {
        if (command==null || command.workspaceId()==null) return CompletableFuture.failedFuture(new BudgetAccessDeniedException());
        return lane.submit(command.workspaceId(),()-> {
            var scope=new Scope(command.workspaceId(),command.partition(),java.time.YearMonth.from(clock.instant().atZone(java.time.ZoneId.of("Asia/Taipei"))));
            authorizeRead(new ReadCommand(scope,command.actorId()));
            return tx.execute(status -> { ensureRow(scope); return select(scope); });
        });
    }

    @Override
    public CompletionStage<Budget> configure(ConfigureCommand command) {
        if (command == null || command.scope() == null || command.scope().workspaceId() == null)
            return CompletableFuture.failedFuture(new BudgetAccessDeniedException());
        return lane.submit(command.scope().workspaceId(), () -> {
            Scope scope = authorizeConfigure(command);
            validateInput(command);
            return tx.execute(status -> {
                ensureRow(scope);
                int updated = jdbc.update("""
                        UPDATE workspace_monthly_budget
                           SET enabled=?, cap_usd=?, config_version=config_version+1
                         WHERE workspace_id=? AND partition=? AND config_version=?
                        """, command.enabled(), command.capUsd(), scope.workspaceId(),
                        scope.partition().name(), command.expectedConfigVersion());
                if (updated != 1) throw new StaleBudgetConfigurationException();
                jdbc.update("update workspace_monthly_budget_period set gate_generation=gate_generation+1 where workspace_id=? and partition=? and period_start=?",
                        scope.workspaceId(), scope.partition().name(), scope.period().atDay(1));
                outbox.recordIfCapReached(scope);
                return select(scope);
            });
        });
    }

    @Override
    public CompletionStage<ConfigureResult> configureIdempotent(IdempotentConfigureCommand command) {
        if (command==null || command.scope()==null || command.scope().workspaceId()==null)
            return CompletableFuture.failedFuture(new BudgetAccessDeniedException());
        return lane.submit(command.scope().workspaceId(),()-> {
            var requested=command.scope();
            if (requested.partition()==null || command.actorId()==null || command.actorId().isBlank()
                    || !workspaces.isMember(requested.workspaceId(),command.actorId())) throw new BudgetAccessDeniedException();
            if (!workspaces.isManager(requested.workspaceId(),command.actorId())) throw new BudgetManagerRequiredException();
            long expected;
            BigDecimal cap;
            try {
                if (command.expectedConfigVersion()==null || !command.expectedConfigVersion().matches("[0-9]+")
                        || command.idempotencyKey()==null || command.idempotencyKey().isBlank() || command.idempotencyKey().length()>128)
                    throw new IllegalArgumentException();
                expected=Long.parseLong(command.expectedConfigVersion());
                if (command.capUsd()!=null && !command.capUsd().matches("[0-9]+(?:\\.[0-9]+)?")) throw new IllegalArgumentException();
                cap=command.capUsd()==null?null:new BigDecimal(command.capUsd()).stripTrailingZeros();
            } catch (RuntimeException invalid) { throw new InvalidBudgetConfigurationException(); }
            var scope=new Scope(requested.workspaceId(),requested.partition(),java.time.YearMonth.from(clock.instant().atZone(java.time.ZoneId.of("Asia/Taipei"))));
            var canonical=new ConfigureCommand(scope,command.actorId(),command.enabled(),cap,expected);
            validateInput(canonical);
            return tx.execute(status -> {
                ensureRow(scope);
                var existing=jdbc.query("""
                        select expected_config_version,enabled,cap_usd from workspace_monthly_budget_command
                         where workspace_id=? and partition=? and actor_id=? and idempotency_key=?
                        """,(rs,n)->new Object[]{rs.getLong(1),rs.getBoolean(2),rs.getBigDecimal(3)},
                        scope.workspaceId(),scope.partition().name(),command.actorId(),command.idempotencyKey());
                if (!existing.isEmpty()) {
                    var prior=existing.getFirst(); var previousCap=(BigDecimal)prior[2];
                    boolean sameCap=previousCap==null?cap==null:cap!=null && previousCap.compareTo(cap)==0;
                    if (((long)prior[0])!=expected || ((boolean)prior[1])!=command.enabled() || !sameCap)
                        throw new StaleBudgetConfigurationException();
                    return new ConfigureResult(ConfigureOutcome.REPLAYED,select(scope));
                }
                int updated=jdbc.update("""
                        update workspace_monthly_budget set enabled=?,cap_usd=?,config_version=config_version+1
                         where workspace_id=? and partition=? and config_version=?
                        """,command.enabled(),cap,scope.workspaceId(),scope.partition().name(),expected);
                if (updated!=1) throw new StaleBudgetConfigurationException();
                jdbc.update("update workspace_monthly_budget_period set gate_generation=gate_generation+1 where workspace_id=? and partition=? and period_start=?",
                        scope.workspaceId(),scope.partition().name(),scope.period().atDay(1));
                outbox.recordIfCapReached(scope);
                jdbc.update("""
                        insert into workspace_monthly_budget_command(workspace_id,partition,actor_id,idempotency_key,expected_config_version,enabled,cap_usd)
                        values (?,?,?,?,?,?,?)
                        """,scope.workspaceId(),scope.partition().name(),command.actorId(),command.idempotencyKey(),expected,command.enabled(),cap);
                return new ConfigureResult(ConfigureOutcome.APPLIED,select(scope));
            });
        });
    }

    private Scope authorizeRead(ReadCommand command) {
        if (command == null || command.scope() == null || command.scope().workspaceId() == null
                || command.scope().partition() == null || command.scope().period() == null
                || command.actorId() == null || command.actorId().isBlank()
                || !workspaces.isMember(command.scope().workspaceId(), command.actorId())) {
            throw new BudgetAccessDeniedException();
        }
        return command.scope();
    }

    private Scope authorizeConfigure(ConfigureCommand command) {
        if (command == null || command.scope() == null || command.scope().workspaceId() == null
                || command.scope().partition() == null || command.scope().period() == null
                || command.actorId() == null || command.actorId().isBlank()
                || !workspaces.isManager(command.scope().workspaceId(), command.actorId())) {
            throw new BudgetAccessDeniedException();
        }
        return command.scope();
    }

    private void validateInput(ConfigureCommand command) {
        BigDecimal cap = command.capUsd();
        if (command.expectedConfigVersion() < 0 || (command.enabled() && cap == null)
                || (cap != null && (cap.signum() < 0 || cap.compareTo(MAX_CAP) > 0
                || cap.scale() > 12))) {
            throw new InvalidBudgetConfigurationException();
        }
    }

    private void ensureRow(Scope scope) {
        jdbc.update("""
                INSERT INTO workspace_monthly_budget(workspace_id, partition)
                VALUES (?, ?) ON CONFLICT DO NOTHING
                """, scope.workspaceId(), scope.partition().name());
        jdbc.update("""
                INSERT INTO workspace_monthly_budget_period(workspace_id, partition, period_start)
                VALUES (?, ?, ?) ON CONFLICT DO NOTHING
                """, scope.workspaceId(), scope.partition().name(), scope.period().atDay(1));
    }

    private Budget select(Scope scope) {
        var zone=java.time.ZoneId.of("Asia/Taipei");
        long unknown=jdbc.queryForObject("""
                select count(*) from provider_attempt a
                left join usage_cost_current c on c.attempt_id=a.id
                left join usage_cost_estimate e on e.id=c.estimate_id and e.attempt_id=a.id
                left join usage_cost_revision_audit audit on audit.estimate_id=e.id and audit.attempt_id=a.id
                where a.workspace_id=? and a.simulation=? and a.started_at>=? and a.started_at<?
                  and a.execution_outcome not in ('IN_PROGRESS','NOT_DISPATCHED')
                  and (e.id is null or e.source_version<>a.projection_version or audit.kind='MANUAL_ESTIMATE' or e.status<>'COMPLETE')
                """,Long.class,scope.workspaceId(),scope.partition()==WorkspaceAdmission.Partition.SIMULATION,
                java.sql.Timestamp.from(scope.period().atDay(1).atStartOfDay(zone).toInstant()),
                java.sql.Timestamp.from(scope.period().plusMonths(1).atDay(1).atStartOfDay(zone).toInstant()));
        boolean recovery=admission.scopeRequiresRecovery(scope.workspaceId(),scope.partition());
        return jdbc.queryForObject("""
                SELECT b.enabled, b.cap_usd, p.known_subtotal_usd, p.terminal_effective_unknown,
                       b.config_version, p.gate_generation
                  FROM workspace_monthly_budget b JOIN workspace_monthly_budget_period p
                    ON p.workspace_id=b.workspace_id AND p.partition=b.partition
                 WHERE b.workspace_id=? AND b.partition=? AND p.period_start=?
                """, (rs, n) -> new Budget(scope, rs.getBoolean(1), rs.getBigDecimal(2),
                rs.getBigDecimal(3), rs.getBoolean(4), rs.getLong(5), rs.getLong(6),unknown,recovery),
                scope.workspaceId(), scope.partition().name(), scope.period().atDay(1));
    }

    public static final class BudgetAccessDeniedException extends RuntimeException {
        public BudgetAccessDeniedException() { super("WORKSPACE_ACCESS_DENIED"); }
    }

    public static final class InvalidBudgetConfigurationException extends RuntimeException {
        public InvalidBudgetConfigurationException() { super("INVALID_BUDGET_CONFIGURATION"); }
    }

    public static final class BudgetManagerRequiredException extends RuntimeException {
        public BudgetManagerRequiredException() { super("WORKSPACE_MANAGER_REQUIRED"); }
    }

    public static final class StaleBudgetConfigurationException extends RuntimeException {
        public StaleBudgetConfigurationException() { super("BUDGET_CONFIGURATION_CONFLICT"); }
    }
}
