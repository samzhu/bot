package io.github.samzhu.bot.execution.internal;

import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface AgentRunRepository extends CrudRepository<AgentRun, String> {

    Optional<AgentRun> findByConversationIdAndClientRequestId(String conversationId, String clientRequestId);

    @Query("SELECT * FROM agent_run WHERE conversation_id = :conversationId AND status IN ('PENDING', 'RUNNING') LIMIT 1")
    Optional<AgentRun> findActiveRunByConversationId(String conversationId);

    @Query("SELECT * FROM agent_run WHERE conversation_id = :conversationId ORDER BY created_at DESC, id DESC LIMIT :limit OFFSET :offset")
    List<AgentRun> findByConversationIdWithPaging(String conversationId, int limit, int offset);

    @Modifying
    @Query("""
        UPDATE agent_run
        SET status = :newStatus, error_message = :errorMessage, updated_at = :updatedAt
        WHERE id = :id AND status IN ('PENDING', 'RUNNING')
    """)
    int updateTerminalStatus(String id, String newStatus, String errorMessage, Instant updatedAt);
}
