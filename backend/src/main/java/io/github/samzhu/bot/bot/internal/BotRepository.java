package io.github.samzhu.bot.bot.internal;

import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface BotRepository extends CrudRepository<Bot, String> {
    @Query("SELECT * FROM bot ORDER BY created_at ASC, id ASC LIMIT :limit OFFSET :offset")
    List<Bot> findAllWithPaging(int limit, int offset);
}
