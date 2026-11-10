package io.github.samzhu.bot.conversation.internal;

import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MessageRepository extends CrudRepository<Message, String> {

    @Query("SELECT * FROM message WHERE conversation_id = :conversationId ORDER BY created_at DESC, id DESC LIMIT :limit OFFSET :offset")
    List<Message> findByConversationIdWithPaging(String conversationId, int limit, int offset);

    @Query("SELECT * FROM message WHERE run_id = :runId ORDER BY created_at ASC")
    List<Message> findByRunId(String runId);
}
