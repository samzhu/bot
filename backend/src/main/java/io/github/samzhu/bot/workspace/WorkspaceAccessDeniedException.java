package io.github.samzhu.bot.workspace;

public class WorkspaceAccessDeniedException extends RuntimeException {
    public WorkspaceAccessDeniedException() {
        super("Workspace access denied");
    }
}
