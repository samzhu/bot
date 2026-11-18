package io.github.samzhu.bot.usage.internal.admission;

import graphql.GraphQLError;
import graphql.GraphqlErrorBuilder;
import io.github.samzhu.bot.identity.CurrentUserProvider;
import io.github.samzhu.bot.usage.MemberWeeklyBudgetOperations;
import io.github.samzhu.bot.usage.WorkspaceAdmission;
import org.springframework.graphql.data.method.annotation.*;
import org.springframework.graphql.execution.ErrorType;
import org.springframework.stereotype.Controller;
import java.util.Map;
import java.util.concurrent.CompletionException;

@Controller
public class MemberWeeklyBudgetGraphQLController {
    private final MemberWeeklyBudgetOperations budgets; private final CurrentUserProvider users;
    public MemberWeeklyBudgetGraphQLController(MemberWeeklyBudgetOperations budgets,CurrentUserProvider users) { this.budgets=budgets; this.users=users; }
    record Input(String workspaceId,String memberUserId,boolean simulation,boolean enabled,String capUsd,String expectedConfigVersion,String idempotencyKey) {}
    record View(String workspaceId,String memberUserId,boolean simulation,String periodStart,String periodEnd,String timezone,
                boolean enabled,String capUsd,String knownSubtotalUsd,String terminalUnknownCount,String state,String reason,String configVersion) {}
    record Result(String outcome,View budget) {}
    private WorkspaceAdmission.Partition partition(boolean simulation) { return simulation?WorkspaceAdmission.Partition.SIMULATION:WorkspaceAdmission.Partition.REAL; }
    @QueryMapping public View memberWeeklyBudget(@Argument String workspaceId,@Argument String memberUserId,@Argument boolean simulation) {
        return view(await(budgets.read(new MemberWeeklyBudgetOperations.Read(workspaceId,memberUserId,partition(simulation),users.getCurrentUserId(),false))));
    }
    @QueryMapping public View myMemberWeeklyBudget(@Argument String workspaceId,@Argument boolean simulation) {
        String actor=users.getCurrentUserId(); return view(await(budgets.read(new MemberWeeklyBudgetOperations.Read(workspaceId,actor,partition(simulation),actor,true))));
    }
    @MutationMapping public Result configureMemberWeeklyBudget(@Argument Input input) {
        var result=await(budgets.configure(new MemberWeeklyBudgetOperations.Configure(input.workspaceId(),input.memberUserId(),partition(input.simulation()),users.getCurrentUserId(),input.enabled(),input.capUsd(),input.expectedConfigVersion(),input.idempotencyKey())));
        return new Result(result.outcome(),view(result.budget()));
    }
    private <T> T await(java.util.concurrent.CompletionStage<T> stage) { try { return stage.toCompletableFuture().join(); } catch(CompletionException e) { if(e.getCause() instanceof RuntimeException r) throw r; throw e; } }
    private View view(MemberWeeklyBudgetOperations.Budget b) {
        String state,reason;
        if(b.recoveryRequired()) { state="BLOCKED_RECOVERY"; reason="RECOVERY_REQUIRED"; }
        else if(!b.enabled()) { state="DISABLED"; reason="DISABLED"; }
        else if(b.capUsd().signum()==0 || (b.knownSubtotalUsd()!=null && b.knownSubtotalUsd().compareTo(b.capUsd())>=0)) { state="BLOCKED_CAP"; reason="CAP_REACHED"; }
        else if(b.terminalUnknownCount()>0) { state="BLOCKED_UNKNOWN"; reason="TERMINAL_COST_UNKNOWN"; }
        else { state="OPEN"; reason="NONE"; }
        return new View(b.workspaceId(),b.memberUserId(),b.partition()==WorkspaceAdmission.Partition.SIMULATION,b.start().toString(),b.end().toString(),"Asia/Taipei",b.enabled(),b.capUsd()==null?null:b.capUsd().toPlainString(),b.knownSubtotalUsd()==null?null:b.knownSubtotalUsd().toPlainString(),Long.toString(b.terminalUnknownCount()),state,reason,Long.toString(b.configVersion()));
    }
    @GraphQlExceptionHandler public GraphQLError failure(JdbcMemberWeeklyBudgetOperations.Failure ex) {
        String code=ex.getMessage(); return GraphqlErrorBuilder.newError().message(code).errorType(code.endsWith("DENIED") || code.equals("WORKSPACE_MANAGER_REQUIRED")?ErrorType.FORBIDDEN:ErrorType.BAD_REQUEST).extensions(Map.of("code",code)).build();
    }
}
