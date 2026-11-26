package io.github.samzhu.bot.conversation;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Owns workspace fixtures for HTTP tests whose transactions commit independently. */
final class ConversationTestData implements AutoCloseable {
    static final String INSERT_CONVERSATION = """
            INSERT INTO conversation (id, bot_id, title, user_id, pinned, created_at, updated_at, workspace_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private final JdbcTemplate jdbc;
    private final List<String> workspaceIds = new ArrayList<>();

    ConversationTestData(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    String workspace(String caller) {
        String id = "conversation-test-" + UUID.randomUUID();
        jdbc.update("INSERT INTO workspace (id, name) VALUES (?, 'Conversation test')", id);
        workspaceIds.add(id);
        addMember(id, caller);
        return id;
    }

    void addMember(String workspaceId, String userId) {
        jdbc.update("INSERT INTO workspace_member (workspace_id, user_id, role) VALUES (?, ?, 'MEMBER')",
                workspaceId, userId);
    }

    void insertConversation(String id, String botId, String title, String owner, boolean pinned,
            Instant time, String workspaceId) {
        Timestamp timestamp = Timestamp.from(time);
        jdbc.update(INSERT_CONVERSATION, id, botId, title, owner, pinned, timestamp, timestamp, workspaceId);
    }

    @Override
    public void close() {
        for (String workspaceId : workspaceIds) {
            jdbc.update("DELETE FROM ai_session WHERE id IN (SELECT id FROM conversation WHERE workspace_id = ?)",
                    workspaceId);
            jdbc.update("DELETE FROM conversation WHERE workspace_id = ?", workspaceId);
            jdbc.update("DELETE FROM workspace_member WHERE workspace_id = ?", workspaceId);
            jdbc.update("DELETE FROM workspace WHERE id = ?", workspaceId);
        }
    }
}
