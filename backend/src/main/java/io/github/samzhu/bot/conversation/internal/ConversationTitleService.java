package io.github.samzhu.bot.conversation.internal;

import io.github.samzhu.bot.conversation.ConversationTitleDispatch;
import io.github.samzhu.bot.conversation.ConversationTitleGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.time.Instant;
import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.transaction.support.TransactionTemplate;

@Service
class ConversationTitleService implements ConversationTitleDispatch {
    private static final Logger log = LoggerFactory.getLogger(ConversationTitleService.class);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectProvider<ConversationTitleGenerator> generator;
    private final SimpleAsyncTaskExecutor executor;
    private final ThreadPoolTaskScheduler deadlines;
    private final Semaphore concurrent = new Semaphore(2);
    private final Duration queueTimeout;
    private final Duration executionTimeout;

    ConversationTitleService(JdbcTemplate jdbc, TransactionTemplate transactions,
            ObjectProvider<ConversationTitleGenerator> generator,
            @Qualifier("conversationTitleExecutor") SimpleAsyncTaskExecutor executor,
            @Qualifier("conversationTitleDeadlines") ThreadPoolTaskScheduler deadlines,
            @Value("${bot.conversation.title.queue-timeout:5s}") Duration queueTimeout,
            @Value("${bot.conversation.title.execution-timeout:30s}") Duration executionTimeout) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.generator = generator;
        this.executor = executor;
        this.deadlines = deadlines;
        this.queueTimeout = queueTimeout;
        this.executionTimeout = executionTimeout;
    }

    @Override public void afterFirstVisibleReply(String conversationId) {
        var available = generator.getIfAvailable();
        if (available == null) return;
        try {
            executor.execute(() -> runBounded(conversationId, available));
        } catch (RuntimeException ex) {
            log.warn("Unable to schedule conversation title work");
        }
    }

    private void runBounded(String conversationId, ConversationTitleGenerator available) {
        boolean acquired = false;
        try {
            acquired = concurrent.tryAcquire(queueTimeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!acquired) {
                jdbc.update("update conversation_title_work set state='FAILED' where conversation_id=? and state='WAITING'", conversationId);
                return;
            }
            Thread worker = Thread.currentThread();
            AtomicBoolean expired = new AtomicBoolean();
            Instant expiresAt = Instant.now().plus(executionTimeout);
            var deadline = deadlines.schedule(() -> {
                expired.set(true);
                worker.interrupt();
                try { jdbc.update("update conversation_title_work set state='FAILED' where conversation_id=? and state='CLAIMED'", conversationId); }
                catch (RuntimeException ex) { log.warn("Unable to record conversation title timeout"); }
            }, expiresAt);
            try { generate(conversationId, available, expired, expiresAt); }
            catch (RuntimeException ex) { log.warn("Conversation title work could not complete"); }
            finally { if (deadline != null) deadline.cancel(false); }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } finally {
            if (acquired) concurrent.release();
        }
    }

    private void generate(String conversationId, ConversationTitleGenerator available, AtomicBoolean expired, Instant expiresAt) {
        if (expired.get()) return;
        var input = transactions.execute(status -> {
            int claimed = jdbc.update("""
                update conversation_title_work w set state='CLAIMED'
                where w.conversation_id=? and w.state='WAITING' and clock_timestamp() < ?
                and exists (select 1 from conversation c where c.id=w.conversation_id and c.title='New Conversation')
                and exists (select 1 from message m where m.conversation_id=w.conversation_id and m.role='USER')
                """, conversationId, java.sql.Timestamp.from(expiresAt));
            if (claimed != 1) return null;
            return jdbc.queryForObject("select content from message where conversation_id=? and role='USER' order by created_at, id limit 1", String.class, conversationId);
        });
        if (input == null) return;
        if (expired.get()) {
            jdbc.update("update conversation_title_work set state='FAILED' where conversation_id=? and state='CLAIMED'", conversationId);
            return;
        }
        try {
            String generated = available.generate(input.substring(0, Math.min(input.length(), 10000)));
            String value = generated == null ? "" : generated.strip();
            if (expired.get() || value.isBlank() || value.length() > 255 || value.contains("\n") || value.contains("\r")) {
                jdbc.update("update conversation_title_work set state='FAILED' where conversation_id=? and state='CLAIMED'", conversationId);
                return;
            }
            transactions.executeWithoutResult(status -> {
                // Match Conversation DELETE's parent-then-cascade order; timeout locks only the work row.
                var parent = jdbc.query("select id from conversation where id=? for update", (rs, row) -> rs.getString(1), conversationId);
                if (parent.isEmpty()) return;
                var work = jdbc.query("select state from conversation_title_work where conversation_id=? for update", (rs, row) -> rs.getString(1), conversationId);
                if (work.isEmpty() || !"CLAIMED".equals(work.getFirst())) return;
                if (expired.get()) {
                    jdbc.update("update conversation_title_work set state='FAILED' where conversation_id=? and state='CLAIMED'", conversationId);
                    return;
                }
                int updated = jdbc.update("update conversation set title=? where id=? and title='New Conversation' and clock_timestamp() < ?", value, conversationId, java.sql.Timestamp.from(expiresAt));
                // A timeout that fires while the title write waits must roll back both changes.
                if (expired.get()) { status.setRollbackOnly(); return; }
                jdbc.update("update conversation_title_work set state=?, version=version+? where conversation_id=? and state='CLAIMED'",
                        updated == 1 ? "DONE" : "FAILED", updated, conversationId);
            });
        } catch (Exception ex) {
            boolean interrupted = Thread.interrupted() || ex instanceof InterruptedException;
            try { jdbc.update("update conversation_title_work set state='FAILED' where conversation_id=? and state='CLAIMED'", conversationId); }
            catch (RuntimeException failure) { log.warn("Unable to record conversation title failure"); }
            if (interrupted) Thread.currentThread().interrupt();
            log.warn("Conversation title work failed");
        }
    }
}

@Configuration(proxyBeanMethods = false)
class ConversationTitleExecutorConfiguration {
    @Bean(name = "conversationTitleDeadlines", defaultCandidate = false)
    ThreadPoolTaskScheduler conversationTitleDeadlines() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("conversation-title-deadline-");
        return scheduler;
    }

    @Bean(name = "conversationTitleExecutor", defaultCandidate = false)
    SimpleAsyncTaskExecutor conversationTitleExecutor() {
        var executor = new SimpleAsyncTaskExecutor("conversation-title-");
        executor.setVirtualThreads(true);
        return executor;
    }
}
