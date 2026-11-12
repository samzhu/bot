package io.github.samzhu.bot.workspace.internal;

import io.github.samzhu.bot.identity.CurrentUserProvider;
import io.github.samzhu.bot.workspace.WorkspaceOperations;
import org.springframework.graphql.data.method.annotation.QueryMapping;
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
}
