package io.github.samzhu.bot.bot.internal;

import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface BotRepository extends CrudRepository<Bot, String> {

    String DEFAULT_LOCAL_BOT_ID = "bot-general";

    @Query("""
        SELECT * FROM bot
        WHERE target = 'LOCAL' OR (target = 'GKE_REMOTE' AND owner_id = :userId)
        ORDER BY updated_at DESC, id DESC
        LIMIT :limit OFFSET :offset
    """)
    List<Bot> findVisibleBotsWithPaging(@Param("userId") String userId, @Param("limit") int limit, @Param("offset") int offset);

    /*
     * WHY / ARCHITECTURAL RATIONALE:
     * 1. Target isolation & ownership security:
     *    - When target is NULL: returns all LOCAL bots and only GKE_REMOTE bots owned by current user.
     *    - When target is 'LOCAL': returns all LOCAL bots (shared across system).
     *    - When target is 'GKE_REMOTE': returns only GKE_REMOTE bots owned by current user. Foreign bots are never matched.
     * 2. Search pattern matching:
     *    - Database-side case-insensitive matching (ILIKE) on name and description.
     *    - Instructions column is explicitly excluded to prevent matching long system prompts.
     *    - ESCAPE '\\' ensures escaped literal '%' and '_' characters are treated as literal search targets.
     *    - CAST(:param AS text) IS NULL guards against PostgreSQL untyped NULL parameter ambiguity in JDBC PreparedStatement.
     * 3. Deterministic pagination:
     *    - ORDER BY updated_at DESC, id DESC ensures stable pagination without page drift or duplicate items.
     *
     * OFFICIAL REFERENCE:
     * - PostgreSQL 16 Docs Chapter 9.7 "Pattern Matching": https://www.postgresql.org/docs/16/functions-matching.html
     * - Checked: 2026-11-12
     */
    @Query("""
        SELECT * FROM bot
        WHERE (
            (CAST(:target AS text) IS NULL AND (target = 'LOCAL' OR (target = 'GKE_REMOTE' AND owner_id = :userId)))
            OR (:target = 'LOCAL' AND target = 'LOCAL')
            OR (:target = 'GKE_REMOTE' AND target = 'GKE_REMOTE' AND owner_id = :userId)
        )
        AND (
            CAST(:searchPattern AS text) IS NULL
            OR name ILIKE :searchPattern ESCAPE '\\'
            OR (description IS NOT NULL AND description ILIKE :searchPattern ESCAPE '\\')
        )
        ORDER BY updated_at DESC, id DESC
        LIMIT :limit OFFSET :offset
    """)
    List<Bot> findVisibleBotsWithFilters(
            @Param("userId") String userId,
            @Param("target") String target,
            @Param("searchPattern") String searchPattern,
            @Param("limit") int limit,
            @Param("offset") int offset);

    @Query("""
        SELECT * FROM bot
        WHERE id = 'bot-general' AND target = 'LOCAL'
        LIMIT 1
    """)
    Optional<Bot> findDefaultLocalBot();
}
