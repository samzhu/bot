package io.github.samzhu.bot.workspace.internal;

import io.github.samzhu.bot.workspace.WorkspaceAccessDeniedException;
import io.github.samzhu.bot.workspace.WorkspaceOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class WorkspaceOperationsImpl implements WorkspaceOperations {
    private final JdbcTemplate jdbc;

    public WorkspaceOperationsImpl(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public WorkspaceView personal(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new WorkspaceAccessDeniedException();
        }
        // Conflict handling keeps concurrent first requests on the same personal workspace.
        jdbc.update("""
                INSERT INTO workspace(id, name, personal_owner_id)
                VALUES (?, 'Personal Workspace', ?)
                ON CONFLICT (personal_owner_id) DO NOTHING
                """, UUID.randomUUID().toString(), userId);
        String id = jdbc.queryForObject(
                "SELECT id FROM workspace WHERE personal_owner_id = ?", String.class, userId);
        jdbc.update("""
                INSERT INTO workspace_member(workspace_id, user_id, role)
                VALUES (?, ?, 'MANAGER')
                ON CONFLICT (workspace_id, user_id) DO NOTHING
                """, id, userId);
        return jdbc.query("""
                SELECT w.id, w.name, m.role, w.personal_owner_id = ? AS personal
                FROM workspace w JOIN workspace_member m ON m.workspace_id = w.id
                WHERE w.id = ? AND m.user_id = ? AND m.active
                """, (rs, row) -> new WorkspaceView(rs.getString(1), rs.getString(2),
                rs.getString(3), rs.getBoolean(4)), userId, id, userId)
                .stream().findFirst().orElseThrow(WorkspaceAccessDeniedException::new);
    }

    @Override
    @Transactional
    public List<WorkspaceView> list(String userId) {
        personal(userId);
        return jdbc.query("""
                SELECT w.id, w.name, m.role, COALESCE(w.personal_owner_id = ?, false)
                FROM workspace w JOIN workspace_member m ON m.workspace_id = w.id
                WHERE m.user_id = ? AND m.active
                ORDER BY w.created_at, w.id
                """, (rs, row) -> new WorkspaceView(rs.getString(1), rs.getString(2),
                rs.getString(3), rs.getBoolean(4)), userId, userId);
    }

    @Override
    @Transactional
    public String requireMember(String workspaceId, String userId) {
        if (workspaceId == null) {
            return personal(userId).id();
        }
        if (!isMember(workspaceId, userId)) {
            throw new WorkspaceAccessDeniedException();
        }
        return workspaceId;
    }

    @Override
    public boolean isMember(String workspaceId, String userId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM workspace_member
                              WHERE workspace_id = ? AND user_id = ? AND active)
                """, Boolean.class, workspaceId, userId));
    }

    @Override
    public boolean isManager(String workspaceId, String userId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM workspace_member
                              WHERE workspace_id = ? AND user_id = ? AND active AND role = 'MANAGER')
                """, Boolean.class, workspaceId, userId));
    }
}
