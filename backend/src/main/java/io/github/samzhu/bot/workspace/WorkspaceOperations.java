package io.github.samzhu.bot.workspace;

import java.util.List;

public interface WorkspaceOperations {
    record WorkspaceView(String id, String name, String role, boolean personal) {}
    record MemberView(String userId, String role) {}
    record PageInfo(boolean hasNextPage, String endCursor) {}
    record MemberPage(List<MemberView> nodes, PageInfo pageInfo) {}
    MemberPage activeMembers(String workspaceId, String actorId, Integer first, String after);

    WorkspaceView personal(String userId);

    List<WorkspaceView> list(String userId);

    String requireMember(String workspaceId, String userId);

    boolean isMember(String workspaceId, String userId);

    boolean isManager(String workspaceId, String userId);
}
