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
    @Transactional(readOnly=true)
    public MemberPage activeMembers(String workspaceId,String actorId,Integer first,String after) {
        if (actorId==null || !isMember(workspaceId,actorId)) throw new MemberPageFailure("WORKSPACE_ACCESS_DENIED");
        if (!isManager(workspaceId,actorId)) throw new MemberPageFailure("WORKSPACE_MANAGER_REQUIRED");
        int limit=first==null?20:first;
        if(limit<1 || limit>100) throw new MemberPageFailure("INVALID_PAGE_SIZE");
        String anchor=null;
        if(after!=null) {
            try {
                var decoded=java.util.Base64.getUrlDecoder().decode(after);
                var in=new java.io.DataInputStream(new java.io.ByteArrayInputStream(decoded));
                String scope=in.readUTF(); anchor=in.readUTF();
                if(!scope.equals(workspaceId) || anchor.isBlank() || in.available()!=0 || !isMember(workspaceId,anchor)) throw new IllegalArgumentException();
            } catch(Exception ex) { throw new MemberPageFailure("INVALID_CURSOR"); }
        }
        var rows=jdbc.query("select user_id,role from workspace_member where workspace_id=? and active and (cast(? as varchar) is null or user_id>?) order by user_id limit ?",
                (rs,n)->new MemberView(rs.getString(1),rs.getString(2)),workspaceId,anchor,anchor,limit+1);
        boolean more=rows.size()>limit; var nodes=List.copyOf(rows.subList(0,Math.min(rows.size(),limit)));
        String cursor=null;
        if(!nodes.isEmpty()) try {
            var bytes=new java.io.ByteArrayOutputStream(); var out=new java.io.DataOutputStream(bytes);
            out.writeUTF(workspaceId); out.writeUTF(nodes.getLast().userId());
            cursor=java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.toByteArray());
        } catch(java.io.IOException impossible) { throw new IllegalStateException(impossible); }
        return new MemberPage(nodes,new PageInfo(more,cursor));
    }
    public static class MemberPageFailure extends RuntimeException { public MemberPageFailure(String code) { super(code); } }

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
