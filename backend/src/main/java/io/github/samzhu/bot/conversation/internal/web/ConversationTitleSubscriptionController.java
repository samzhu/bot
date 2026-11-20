package io.github.samzhu.bot.conversation.internal.web;

import io.github.samzhu.bot.identity.CurrentUserProvider;
import io.github.samzhu.bot.conversation.ConversationOperations;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.SubscriptionMapping;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Controller;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;

@Controller
class ConversationTitleSubscriptionController {
    record ConversationTitleChange(String id, String version, String state) {}
    private final JdbcTemplate jdbc;
    private final ConversationOperations conversations;
    private final CurrentUserProvider currentUser;

    ConversationTitleSubscriptionController(JdbcTemplate jdbc, ConversationOperations conversations, CurrentUserProvider currentUser) {
        this.jdbc = jdbc;
        this.conversations = conversations;
        this.currentUser = currentUser;
    }

    record WorkState(String state, String version) {}

    @SubscriptionMapping
    Flux<ConversationTitleChange> conversationTitleChanged(@Argument String conversationId) {
        String user = currentUser.getCurrentUserId();
        if (conversations.findConversationById(conversationId, user).isEmpty()) {
            throw new io.github.samzhu.bot.conversation.ConversationAccessDeniedException("Conversation access denied");
        }
        // Poll only this authorized work item; a short lease bounds disconnected idle streams.
        return Flux.interval(Duration.ZERO, Duration.ofMillis(500))
                .take(6)
                .concatMap(ignored -> Mono.fromCallable(() -> jdbc.query("""
                    select case when w.state='WAITING'
                        and (exists (select 1 from chat_run r where r.conversation_id=c.id)
                             or c.created_at < clock_timestamp() - interval '30 seconds')
                        and not exists (select 1 from chat_run r where r.conversation_id=c.id and r.status in ('PENDING','RUNNING'))
                        and not exists (select 1 from message msg where msg.conversation_id=c.id and msg.role='ASSISTANT' and msg.content ~ '[^[:space:]]')
                        then 'UNAVAILABLE' else w.state end, w.version from conversation_title_work w
                    join conversation c on c.id=w.conversation_id
                    join workspace_member m on m.workspace_id=c.workspace_id and m.user_id=? and m.active=true
                    where c.id=? and c.user_id=?
                    """, (rs, row) -> new WorkState(rs.getString(1), Long.toString(rs.getLong(2))), user, conversationId, user))
                        .subscribeOn(Schedulers.boundedElastic()))
                .map(rows -> rows.isEmpty() ? new WorkState("UNAVAILABLE", "0") : rows.getFirst())
                .takeUntil(work -> !work.state().equals("WAITING") && !work.state().equals("READY") && !work.state().equals("CLAIMED"))
                .last()
                .map(work -> new ConversationTitleChange(conversationId, work.version(), work.state()))
                .flux();
    }
}
