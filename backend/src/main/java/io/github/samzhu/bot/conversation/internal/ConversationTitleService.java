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
    private final io.github.samzhu.bot.usage.ConversationTitleAdmission admission;
    private final TransactionTemplate transactions;
    private final ObjectProvider<ConversationTitleGenerator> generator;
    private final SimpleAsyncTaskExecutor executor;
    private final ThreadPoolTaskScheduler deadlines;
    private final ThreadPoolTaskScheduler recovery;
    private final Semaphore concurrent = new Semaphore(2);
    private final Duration queueTimeout;
    private final Duration executionTimeout;
    private final Duration recoveryWindow;

    ConversationTitleService(JdbcTemplate jdbc, TransactionTemplate transactions, io.github.samzhu.bot.usage.ConversationTitleAdmission admission,
            ObjectProvider<ConversationTitleGenerator> generator,
            @Qualifier("conversationTitleExecutor") SimpleAsyncTaskExecutor executor,
            @Qualifier("conversationTitleDeadlines") ThreadPoolTaskScheduler deadlines,
            @Qualifier("conversationTitleRecovery") ThreadPoolTaskScheduler recovery,
            @Value("${bot.conversation.title.queue-timeout:5s}") Duration queueTimeout,
            @Value("${bot.conversation.title.execution-timeout:30s}") Duration executionTimeout,
            @Value("${bot.conversation.title.recovery-window:5m}") Duration recoveryWindow) {
        this.jdbc = jdbc;
        this.admission = admission;
        this.transactions = transactions;
        this.generator = generator;
        this.executor = executor;
        this.deadlines = deadlines;
        this.recovery = recovery;
        this.queueTimeout = queueTimeout;
        this.executionTimeout = executionTimeout;
        this.recoveryWindow = recoveryWindow;
    }

    @Override public void afterFirstVisibleReply(String conversationId) {
        try {
            executor.execute(() -> {
                jdbc.update("update conversation_title_work set state='READY', ready_until=clock_timestamp() + (? * interval '1 millisecond') where conversation_id=? and state='WAITING'", recoveryWindow.toMillis(), conversationId);
                var available = generator.getIfAvailable();
                if (available != null) runBounded(conversationId, available);
            });
        } catch (RuntimeException ex) {
            log.warn("Unable to schedule conversation title work");
        }
    }

    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    void startRecovery() {
        recovery.scheduleWithFixedDelay(this::recover, Duration.ofSeconds(1));
    }

    private void recover() {
        try {
            jdbc.update("update conversation_title_work set state='FAILED' where state='READY' and ready_until <= clock_timestamp()");
            jdbc.update("update conversation_title_work set state='FAILED' where state='CLAIMED' and claimed_until <= clock_timestamp()");
            var available = generator.getIfAvailable();
            if (available == null) return;
            // Take a finite snapshot; an ineligible READY must not monopolize recovery.
            var ids = jdbc.query("""
                select w.conversation_id from conversation_title_work w
                join conversation c on c.id=w.conversation_id
                where w.state='READY' and w.ready_until > clock_timestamp()
                and (w.next_attempt_at is null or w.next_attempt_at <= clock_timestamp())
                and c.title='New Conversation'
                and exists (select 1 from message m where m.conversation_id=w.conversation_id and m.role='USER')
                order by w.ready_until, w.conversation_id limit 2
                """, (rs, row) -> rs.getString(1));
            for (String id : ids) {
                if (!concurrent.tryAcquire()) break;
                try {
                    executor.execute(() -> {
                        try { runAcquired(id, available); }
                        finally { concurrent.release(); }
                    });
                } catch (RuntimeException ex) { concurrent.release(); throw ex; }
            }
        } catch (RuntimeException ex) { log.warn("Unable to recover conversation title work"); }
    }

    private void runBounded(String conversationId, ConversationTitleGenerator available) {
        boolean acquired = false;
        try {
            acquired = concurrent.tryAcquire(queueTimeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!acquired) {
                jdbc.update("update conversation_title_work set state='FAILED' where conversation_id=? and state='READY'", conversationId);
                return;
            }
            runAcquired(conversationId, available);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } finally {
            if (acquired) concurrent.release();
        }
    }

    private void runAcquired(String conversationId, ConversationTitleGenerator available) {
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
    }

    private void generate(String conversationId, ConversationTitleGenerator available, AtomicBoolean expired, Instant expiresAt) {
        if (expired.get()) return;
        var input = transactions.execute(status -> {
            int claimed = jdbc.update("""
                update conversation_title_work w set state='CLAIMED', claimed_until=?
                where w.conversation_id=? and w.state='READY' and clock_timestamp() < ? and clock_timestamp() < w.ready_until
                and exists (select 1 from conversation c where c.id=w.conversation_id and c.title='New Conversation')
                and exists (select 1 from message m where m.conversation_id=w.conversation_id and m.role='USER')
                """, java.sql.Timestamp.from(expiresAt), conversationId, java.sql.Timestamp.from(expiresAt));
            if (claimed != 1) return null;
            return jdbc.queryForObject("select content from message where conversation_id=? and role='USER' order by created_at, id limit 1", String.class, conversationId);
        });
        if (input == null) return;
        if (expired.get()) {
            jdbc.update("update conversation_title_work set state='FAILED' where conversation_id=? and state='CLAIMED'", conversationId);
            return;
        }
        try {
            var decision = admission.allow(conversationId);
            if (decision == io.github.samzhu.bot.usage.ConversationTitleAdmission.Decision.DEFERRED) {
                // Deferral is permitted only before an attempt or hold exists; retain the original expiry.
                jdbc.update("""
                    update conversation_title_work set state='READY',claimed_until=null,next_attempt_at=clock_timestamp()+interval '1 second'
                    where conversation_id=? and state='CLAIMED' and ready_until>clock_timestamp()
                    and not exists (select 1 from workspace_title_reservation h where h.conversation_id=conversation_title_work.conversation_id)
                    and not exists (select 1 from provider_attempt a where a.title_conversation_id=conversation_title_work.conversation_id and a.purpose='CONVERSATION_TITLE')
                    """, conversationId);
                return;
            }
            if (decision != io.github.samzhu.bot.usage.ConversationTitleAdmission.Decision.ALLOWED) {
                jdbc.update("update conversation_title_work set state='FAILED', denial_reason=? where conversation_id=? and state='CLAIMED' and claimed_until>clock_timestamp()", decision.reason(), conversationId);
                return;
            }
            if (expired.get() || Thread.currentThread().isInterrupted()) {
                admission.unknown(conversationId);
                return;
            }
            var result = available.generateResult(input.substring(0, Math.min(input.length(), 10000)));
            if (result == null || !admission.capture(conversationId, result)) {
                jdbc.update("update conversation_title_work set state='FAILED' where conversation_id=? and state='CLAIMED'", conversationId);
                return;
            }
            String generated = result.title();
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
            try { admission.unknown(conversationId); } catch (RuntimeException failure) { log.warn("Unable to record title usage uncertainty"); }
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

    // A blocked recovery query must not occupy the deadline scheduler.
    @Bean(name = "conversationTitleRecovery", defaultCandidate = false)
    ThreadPoolTaskScheduler conversationTitleRecovery() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("conversation-title-recovery-");
        return scheduler;
    }

    @Bean(name = "conversationTitleExecutor", defaultCandidate = false)
    SimpleAsyncTaskExecutor conversationTitleExecutor() {
        var executor = new SimpleAsyncTaskExecutor("conversation-title-");
        executor.setVirtualThreads(true);
        return executor;
    }
}
