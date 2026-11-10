package io.github.samzhu.bot.conversation.internal;

import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ConversationRepository extends CrudRepository<Conversation, String> {

    @Query("SELECT * FROM conversation WHERE user_id = :userId ORDER BY updated_at DESC, id DESC LIMIT :limit OFFSET :offset")
    List<Conversation> findByUserIdWithPaging(String userId, int limit, int offset);
}
