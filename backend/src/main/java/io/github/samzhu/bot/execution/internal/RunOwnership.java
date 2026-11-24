package io.github.samzhu.bot.execution.internal;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.UUID;

/** Database-fenced authority for a single Run; all mutations use the caller's transaction. */
@Component
final class RunOwnership implements io.github.samzhu.bot.usage.RunOwnershipGuard {
    
    private final JdbcTemplate jdbc;
    private final org.springframework.transaction.support.TransactionTemplate transactions;
    private final org.springframework.scheduling.TaskScheduler scheduler;
    private final RunRegistry registry;
    // Only receipt recovery may consult these original local capabilities; async callbacks keep their own fence.
    private final java.util.concurrent.ConcurrentMap<String,Fence> localRecovery=new java.util.concurrent.ConcurrentHashMap<>();


    RunOwnership(JdbcTemplate jdbc, org.springframework.transaction.support.TransactionTemplate transactions, RunRegistry registry,
            @org.springframework.beans.factory.annotation.Qualifier("botTaskScheduler") org.springframework.scheduling.TaskScheduler scheduler) {
        this.jdbc = jdbc; this.transactions = transactions; this.registry=registry; this.scheduler = scheduler;
    }

    void maintain(Fence fence) {
        localRecovery.put(fence.runId(),fence);
        final java.util.concurrent.atomic.AtomicReference<java.util.concurrent.ScheduledFuture<?>> future = new java.util.concurrent.atomic.AtomicReference<>();
        try { future.set(scheduler.scheduleWithFixedDelay(() -> {
            try {
                if (fence.equals(localRecovery.get(fence.runId()))) {
                    // After a registry miss, retain the original capability until its existing
                    // lease expires, but do not extend lost local work indefinitely.
                    boolean current=Boolean.TRUE.equals(transactions.execute(status ->
                            registry.get(fence.runId())==null ? lockOwner(fence) : renew(fence)));
                    if (current) return;
                }
            } catch (RuntimeException failure) {
                // Uncertain renewal cannot grant permission to keep writing.
            }
            localRecovery.remove(fence.runId(),fence);
            var scheduled = future.get();
            if (scheduled != null) scheduled.cancel(false);
        }, java.time.Instant.now().plusSeconds(10), java.time.Duration.ofSeconds(10))); }
        catch (RuntimeException failure) { localRecovery.remove(fence.runId(),fence); throw failure; }
    }

    Fence localForReceipt(String runId, String registrationToken) {
        var fence=localRecovery.get(runId);
        return fence!=null && fence.token().equals(registrationToken) ? fence : null;
    }

    void forget(Fence fence) { if (fence!=null) localRecovery.remove(fence.runId(),fence); }

    boolean mayDispatch(Fence fence) {
        try {
            return Boolean.TRUE.equals(transactions.execute(status -> {
                jdbc.query("select id from chat_run where id=? for update", (rs,n) -> rs.getString(1), fence.runId());
                return lockOwner(fence);
            }));
        } catch (RuntimeException failure) { return false; }
    }

    Fence create(String runId, String token) {
        requireTransaction();
        jdbc.update("""
                insert into run_ownership(run_id,owner_token,generation,lease_until)
                values (?,?,1,clock_timestamp()+interval '60 seconds')
                """, runId, token);
        return new Fence(runId, token, 1);
    }

    boolean renew(Fence fence) {
        requireTransaction();
        lockRow(fence.runId());
        return jdbc.update("""
                update run_ownership set lease_until=clock_timestamp()+interval '60 seconds'
                 where run_id=? and owner_token=? and generation=? and lease_until>clock_timestamp()
                   and exists (select 1 from chat_run r where r.id=run_ownership.run_id and r.status in ('PENDING','RUNNING'))
                """, fence.runId(), fence.token(), fence.generation()) == 1;
    }

    @Override public boolean lockOwner(Fence fence) {
        requireTransaction();
        lockRow(fence.runId());
        return !jdbc.query("""
                select run_id from run_ownership
                 where run_id=? and owner_token=? and generation=? and lease_until>clock_timestamp()
                """, (rs,n) -> rs.getString(1), fence.runId(), fence.token(), fence.generation()).isEmpty();
    }

    // The caller must hold registration then Run row locks before taking the ownership row.
    @Override public Fence takeExpired(String runId) {
        requireTransaction();
        lockRow(runId);
        String token = UUID.randomUUID().toString();
        var generations = jdbc.query("""
                update run_ownership set owner_token=?,generation=generation+1,
                       lease_until=clock_timestamp()+interval '60 seconds'
                 where run_id=? and lease_until<=clock_timestamp()
                   and exists (select 1 from chat_run r where r.id=run_ownership.run_id and r.status in ('PENDING','RUNNING'))
                 returning generation
                """, (rs,n) -> rs.getLong(1), token, runId);
        return generations.isEmpty() ? null : new Fence(runId, token, generations.getFirst());
    }

    @Override public boolean failTakenRun(Fence fence, String conversationId, String reason) {
        requireTransaction();
        if (!lockOwner(fence)) return false;
        return jdbc.update("""
                update chat_run set status='FAILED', error_message=?, updated_at=clock_timestamp()
                where id=? and conversation_id=? and status in ('PENDING','RUNNING')
                """, reason, fence.runId(), conversationId)==1;
    }

    private void lockRow(String runId) {
        jdbc.query("select run_id from run_ownership where run_id=? for update", (rs,n) -> rs.getString(1), runId);
    }

    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Run ownership requires the caller's transaction");
    }
}
