package io.github.samzhu.bot.usage.internal;

import graphql.GraphQLError;
import graphql.GraphqlErrorBuilder;
import io.github.samzhu.bot.workspace.WorkspaceAccessDeniedException;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.GraphQlExceptionHandler;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.execution.ErrorType;
import org.springframework.stereotype.Controller;
import java.util.Map;

@Controller
public class UsageReportGraphQLController {
    private final UsageReportService reports;
    public UsageReportGraphQLController(UsageReportService reports) { this.reports=reports; }
    @QueryMapping
    public UsageReportService.Report myUsageReport(@Argument String workspaceId,@Argument Integer periodDays,
            @Argument String provider,@Argument String model,@Argument Boolean simulation,@Argument Integer first,@Argument String after) {
        return reports.report(workspaceId,false,periodDays,provider,model,simulation,first,after,null,false);
    }
    @QueryMapping
    public UsageReportService.Report workspaceUsageReport(@Argument String workspaceId,@Argument Integer periodDays,
            @Argument String provider,@Argument String model,@Argument Boolean simulation,@Argument Integer first,@Argument String after,
            @Argument String memberId,@Argument Boolean unassignedOnly) {
        return reports.report(workspaceId,true,periodDays,provider,model,simulation,first,after,memberId,unassignedOnly);
    }
    @QueryMapping
    public UsageReadModel.Attempt workspaceUsageAttempt(@Argument String workspaceId,@Argument String id) {
        return reports.managerAttempt(workspaceId,id);
    }
    @GraphQlExceptionHandler
    public GraphQLError invalid(UsageReportService.Invalid ex) {return error(ErrorType.BAD_REQUEST,ex.getMessage());}
    @GraphQlExceptionHandler
    public GraphQLError manager(UsageReportService.ManagerRequired ex) {return error(ErrorType.FORBIDDEN,"WORKSPACE_MANAGER_REQUIRED");}
    @GraphQlExceptionHandler
    public GraphQLError denied(WorkspaceAccessDeniedException ex) {return error(ErrorType.FORBIDDEN,"WORKSPACE_ACCESS_DENIED");}
    private static GraphQLError error(ErrorType type,String code) {return GraphqlErrorBuilder.newError().errorType(type).message(code).extensions(Map.of("code",code)).build();}
}
