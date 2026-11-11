package io.github.samzhu.bot.conversation.internal;

import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface ConversationRepository extends CrudRepository<Conversation, String> {

    @Query("SELECT * FROM conversation WHERE user_id = :userId ORDER BY updated_at DESC, id DESC LIMIT :limit OFFSET :offset")
    List<Conversation> findByUserIdWithPaging(String userId, int limit, int offset);

    @Query("SELECT * FROM conversation WHERE user_id = :userId AND pinned = :pinned ORDER BY updated_at DESC, id DESC LIMIT :limit OFFSET :offset")
    List<Conversation> findByUserIdAndPinnedWithPaging(String userId, boolean pinned, int limit, int offset);

    @Modifying
    @Query("UPDATE conversation SET pinned = :pinned WHERE id = :id AND user_id = :userId AND pinned <> :pinned")
    int updatePinned(String id, String userId, boolean pinned);

    @Modifying
    @Query("UPDATE conversation SET updated_at = :now WHERE id = :id")
    int touchUpdatedAt(String id, Instant now);
}
