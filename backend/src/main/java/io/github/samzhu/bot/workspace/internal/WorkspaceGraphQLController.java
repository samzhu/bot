package io.github.samzhu.bot.workspace.internal;

import io.github.samzhu.bot.identity.CurrentUserProvider;
import io.github.samzhu.bot.workspace.WorkspaceOperations;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.GraphQlExceptionHandler;
import org.springframework.stereotype.Controller;

import java.util.List;

@Controller
public class WorkspaceGraphQLController {
    private final WorkspaceOperations workspace;
    private final CurrentUserProvider user;

    public WorkspaceGraphQLController(WorkspaceOperations workspace, CurrentUserProvider user) {
        this.workspace = workspace;
        this.user = user;
    }

    @QueryMapping
    public List<WorkspaceOperations.WorkspaceView> workspaces() {
        return workspace.list(user.getCurrentUserId());
    }

    @QueryMapping
    public WorkspaceOperations.WorkspaceView personalWorkspace() {
        return workspace.personal(user.getCurrentUserId());
    }
    @QueryMapping
    public WorkspaceOperations.MemberPage workspaceActiveMembers(@Argument String workspaceId,@Argument Integer first,@Argument String after) {
        return workspace.activeMembers(workspaceId,user.getCurrentUserId(),first,after);
    }
    @GraphQlExceptionHandler
    public graphql.GraphQLError memberPageError(WorkspaceOperationsImpl.MemberPageFailure ex) {
        String code=ex.getMessage();
        return graphql.GraphqlErrorBuilder.newError().message(code).errorType(code.startsWith("WORKSPACE_")?org.springframework.graphql.execution.ErrorType.FORBIDDEN:org.springframework.graphql.execution.ErrorType.BAD_REQUEST).extensions(java.util.Map.of("code",code)).build();
    }
}
