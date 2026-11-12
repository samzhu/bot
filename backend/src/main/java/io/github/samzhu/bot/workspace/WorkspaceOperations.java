package io.github.samzhu.bot.workspace;

import java.util.List;

public interface WorkspaceOperations {
    record WorkspaceView(String id, String name, String role, boolean personal) {}

    WorkspaceView personal(String userId);

    List<WorkspaceView> list(String userId);

    String requireMember(String workspaceId, String userId);

    boolean isMember(String workspaceId, String userId);

    boolean isManager(String workspaceId, String userId);
}
