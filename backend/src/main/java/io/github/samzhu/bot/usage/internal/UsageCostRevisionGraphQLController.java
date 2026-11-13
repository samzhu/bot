package io.github.samzhu.bot.usage.internal;

import graphql.GraphQLError;
import graphql.GraphqlErrorBuilder;
import io.github.samzhu.bot.usage.UsageCostRevisionOperations;
import io.github.samzhu.bot.usage.internal.pricing.JdbcUsageCostRevisionOperations;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.GraphQlExceptionHandler;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.execution.ErrorType;
import org.springframework.stereotype.Controller;

import java.util.Map;

@Controller
public class UsageCostRevisionGraphQLController {
    private final UsageCostRevisionOperations operations;
    private final UsageCostRevisionQueryService queries;
    public UsageCostRevisionGraphQLController(UsageCostRevisionOperations operations,UsageCostRevisionQueryService queries) { this.operations=operations; this.queries=queries; }
    @MutationMapping
    public UsageCostRevisionOperations.Result manualCostRevision(@Argument UsageCostRevisionOperations.ManualCommand input) {
        return operations.manual(input);
    }
    @MutationMapping
    public UsageCostRevisionOperations.Result reconcileCost(@Argument UsageCostRevisionOperations.ReconcileCommand input) {
        return operations.reconcile(input);
    }
    @org.springframework.graphql.data.method.annotation.SchemaMapping(typeName="UsageCost",field="amount")
    public String amount(Object cost) {
        if (cost instanceof io.github.samzhu.bot.usage.UsageCostOperations.Cost value) return value.completeAmount();
        if (cost instanceof Map<?,?> value) return (String)value.get("completeAmount");
        return null;
    }
    @org.springframework.graphql.data.method.annotation.QueryMapping
    public UsageCostRevisionQueryService.HistoryConnection costRevisions(@Argument String workspaceId,@Argument String attemptId,@Argument Integer first,@Argument String after) {
        return queries.history(workspaceId,attemptId,first,after);
    }
    @org.springframework.graphql.data.method.annotation.QueryMapping
    public UsageCostRevisionQueryService.CatalogConnection publishedPricingCatalogs(@Argument String workspaceId,@Argument Integer first,@Argument String after) {
        return queries.catalog(workspaceId,first,after);
    }
    @org.springframework.graphql.data.method.annotation.QueryMapping
    public UsageReadModel.Connection<UsageReadModel.Attempt> costReviewAttempts(@Argument String workspaceId,@Argument Boolean simulation,@Argument Integer first,@Argument String after) {
        return queries.reviewAttempts(workspaceId,simulation,first,after);
    }
    @GraphQlExceptionHandler
    public GraphQLError failure(JdbcUsageCostRevisionOperations.Failure failure) {
        String code=failure.getMessage();
        boolean forbidden=code.equals("WORKSPACE_ACCESS_DENIED")||code.equals("WORKSPACE_MANAGER_REQUIRED")||code.equals("USAGE_ATTEMPT_ACCESS_DENIED");
        return GraphqlErrorBuilder.newError().errorType(forbidden?ErrorType.FORBIDDEN:ErrorType.BAD_REQUEST)
                .message(code).extensions(Map.of("code",code)).build();
    }
}
