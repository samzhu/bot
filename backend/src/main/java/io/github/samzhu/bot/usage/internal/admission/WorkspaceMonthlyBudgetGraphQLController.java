package io.github.samzhu.bot.usage.internal.admission;

import graphql.GraphQLError;
import graphql.GraphqlErrorBuilder;
import io.github.samzhu.bot.identity.CurrentUserProvider;
import io.github.samzhu.bot.usage.WorkspaceAdmission;
import io.github.samzhu.bot.usage.WorkspaceMonthlyBudgetOperations;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.GraphQlExceptionHandler;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.execution.ErrorType;
import org.springframework.stereotype.Controller;

import java.time.Clock;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.Map;

@Controller
public class WorkspaceMonthlyBudgetGraphQLController {
    private static final ZoneId ZONE=ZoneId.of("Asia/Taipei");
    private final WorkspaceMonthlyBudgetOperations budgets;
    private final CurrentUserProvider users;
    private final Clock clock;

    public WorkspaceMonthlyBudgetGraphQLController(WorkspaceMonthlyBudgetOperations budgets,CurrentUserProvider users,Clock clock) {
        this.budgets=budgets; this.users=users; this.clock=clock;
    }

    public record View(String workspaceId,boolean simulation,String period,String periodStart,String periodEnd,
                       boolean enabled,String capUsd,String knownSubtotalUsd,String terminalUnknownCount,
                       String state,String reason,String configVersion,String generation) {}
    public record ConfigureInput(String workspaceId,boolean simulation,boolean enabled,String capUsd,
                                 String expectedConfigVersion,String idempotencyKey) {}
    public record ConfigureView(WorkspaceMonthlyBudgetOperations.ConfigureOutcome outcome,View budget) {}

    @QueryMapping
    public View workspaceMonthlyBudget(@Argument String workspaceId,@Argument boolean simulation) {
        final WorkspaceMonthlyBudgetOperations.Budget budget;
        try { budget=budgets.readCurrent(new WorkspaceMonthlyBudgetOperations.CurrentReadCommand(workspaceId,
                simulation?WorkspaceAdmission.Partition.SIMULATION:WorkspaceAdmission.Partition.REAL,users.getCurrentUserId())).toCompletableFuture().join(); }
        catch (java.util.concurrent.CompletionException ex) {
            if (ex.getCause() instanceof RuntimeException cause) throw cause;
            throw ex;
        }
        return view(budget);
    }

    @MutationMapping
    public ConfigureView configureWorkspaceMonthlyBudget(@Argument ConfigureInput input) {
        var scope=new WorkspaceMonthlyBudgetOperations.Scope(input.workspaceId(),
                input.simulation()?WorkspaceAdmission.Partition.SIMULATION:WorkspaceAdmission.Partition.REAL,
                YearMonth.from(clock.instant().atZone(ZONE)));
        try {
            var result=budgets.configureIdempotent(new WorkspaceMonthlyBudgetOperations.IdempotentConfigureCommand(scope,
                    users.getCurrentUserId(),input.enabled(),input.capUsd(),input.expectedConfigVersion(),input.idempotencyKey())).toCompletableFuture().join();
            return new ConfigureView(result.outcome(),view(result.budget()));
        } catch (java.util.concurrent.CompletionException ex) {
            if (ex.getCause() instanceof RuntimeException cause) throw cause;
            throw ex;
        }
    }

    private View view(WorkspaceMonthlyBudgetOperations.Budget budget) {
        var scope=budget.scope(); var period=scope.period();
        boolean simulation=scope.partition()==WorkspaceAdmission.Partition.SIMULATION;
        String state,reason;
        if (budget.recoveryRequired()) { state="BLOCKED_RECOVERY"; reason="RECOVERY_REQUIRED"; }
        else if (!budget.enabled()) { state="DISABLED"; reason="DISABLED"; }
        else if (budget.capUsd().signum()==0 || (budget.knownSubtotalUsd()!=null && budget.knownSubtotalUsd().compareTo(budget.capUsd())>=0)) {
            state="BLOCKED_CAP"; reason="CAP_REACHED";
        } else if (budget.terminalEffectiveUnknown()) { state="BLOCKED_UNKNOWN"; reason="TERMINAL_COST_UNKNOWN"; }
        else { state="OPEN"; reason="NONE"; }
        return new View(scope.workspaceId(),simulation,period.toString(),period.atDay(1).atStartOfDay(ZONE).toInstant().toString(),
                period.plusMonths(1).atDay(1).atStartOfDay(ZONE).toInstant().toString(),budget.enabled(),
                budget.capUsd()==null?null:budget.capUsd().toPlainString(),
                budget.knownSubtotalUsd()==null?null:budget.knownSubtotalUsd().toPlainString(),Long.toString(budget.terminalUnknownCount()),
                state,reason,Long.toString(budget.configVersion()),Long.toString(budget.gateGeneration()));
    }

    @GraphQlExceptionHandler
    public GraphQLError denied(JdbcWorkspaceMonthlyBudgetOperations.BudgetAccessDeniedException ex) {
        return GraphqlErrorBuilder.newError().errorType(ErrorType.FORBIDDEN).message("WORKSPACE_ACCESS_DENIED")
                .extensions(Map.of("code","WORKSPACE_ACCESS_DENIED")).build();
    }

    @GraphQlExceptionHandler
    public GraphQLError manager(JdbcWorkspaceMonthlyBudgetOperations.BudgetManagerRequiredException ex) {
        return error("WORKSPACE_MANAGER_REQUIRED",ErrorType.FORBIDDEN);
    }

    @GraphQlExceptionHandler
    public GraphQLError invalid(JdbcWorkspaceMonthlyBudgetOperations.InvalidBudgetConfigurationException ex) {
        return error("INVALID_BUDGET_CONFIGURATION",ErrorType.BAD_REQUEST);
    }

    @GraphQlExceptionHandler
    public GraphQLError conflict(JdbcWorkspaceMonthlyBudgetOperations.StaleBudgetConfigurationException ex) {
        return error("BUDGET_CONFIGURATION_CONFLICT",ErrorType.BAD_REQUEST);
    }

    private GraphQLError error(String code,ErrorType type) {
        return GraphqlErrorBuilder.newError().errorType(type).message(code).extensions(Map.of("code",code)).build();
    }
}
