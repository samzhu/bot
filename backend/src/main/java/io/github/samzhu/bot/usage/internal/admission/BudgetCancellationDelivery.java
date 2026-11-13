package io.github.samzhu.bot.usage.internal.admission;

import io.github.samzhu.bot.usage.BudgetCancellationSink;
import io.github.samzhu.bot.usage.WorkspaceAdmission;
import io.github.samzhu.bot.usage.RunAdmissionStateReader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Delivers durable intents outside workspace lanes; failed delivery remains pending for retry. */
@Component
final class BudgetCancellationDelivery {
    private final JdbcTemplate jdbc;
    private final JdbcTemplate leaseJdbc;
    private final WorkspaceAdmission admission;
    private final BudgetCancellationSink sink;
    private final WorkspaceLane lane;
    private final DefaultWorkspaceAdmission authority;
    private final RunAdmissionStateReader runStateReader;
    private final org.springframework.transaction.support.TransactionTemplate transactions;
    private final Set<String> inFlight=ConcurrentHashMap.newKeySet();
    private static final int PAGE_SIZE=100;
    private static final int MAX_IN_FLIGHT=128;
    private java.sql.Timestamp cursorCreated;
    private String cursorId;
    BudgetCancellationDelivery(JdbcTemplate jdbc,WorkspaceAdmission admission,BudgetCancellationSink sink,
            WorkspaceLane lane, org.springframework.transaction.PlatformTransactionManager manager,
            DefaultWorkspaceAdmission authority,RunAdmissionStateReader runStateReader) {
        this.jdbc=jdbc; this.leaseJdbc=new JdbcTemplate(java.util.Objects.requireNonNull(jdbc.getDataSource()));
        this.leaseJdbc.setQueryTimeout(2);
        this.admission=admission; this.sink=sink; this.lane=lane;
        this.authority=authority; this.runStateReader=runStateReader;
        this.transactions=new org.springframework.transaction.support.TransactionTemplate(manager);
    }
    @Scheduled(initialDelayString="${bot.budget.effects.initial-delay-ms:1000}",fixedDelayString="${bot.budget.effects.delay-ms:1000}")
    void scan() {
        var pending=cursorCreated==null
                ? jdbc.query("select effect_id,target_run_id,created_at from workspace_admission_effect_outbox where delivery_status='PENDING' order by created_at,effect_id limit ?",
                        (rs,n)->new Object[]{rs.getString(1),rs.getString(2),rs.getTimestamp(3)},PAGE_SIZE)
                : jdbc.query("select effect_id,target_run_id,created_at from workspace_admission_effect_outbox where delivery_status='PENDING' and (created_at,effect_id)>(?,?) order by created_at,effect_id limit ?",
                        (rs,n)->new Object[]{rs.getString(1),rs.getString(2),rs.getTimestamp(3)},cursorCreated,cursorId,PAGE_SIZE);
        if (pending.isEmpty()) { cursorCreated=null; cursorId=null; return; }
        for (var row:pending) {
            String id=(String)row[0]; cursorId=id; cursorCreated=(java.sql.Timestamp)row[2];
            if (inFlight.size()>=MAX_IN_FLIGHT) continue;
            if (!inFlight.add(id)) continue;
            try {
                String lease=java.util.UUID.randomUUID().toString();
                int acquired=leaseJdbc.update("""
                        with candidate as (
                            select effect_id from workspace_admission_effect_outbox
                             where effect_id=? and delivery_status='PENDING'
                               and (delivery_lease_until is null or delivery_lease_until<=clock_timestamp())
                             for update skip locked
                        )
                        update workspace_admission_effect_outbox o
                           set delivery_lease_id=?,delivery_lease_until=clock_timestamp()+interval '2 seconds'
                          from candidate c where o.effect_id=c.effect_id
                        """,id,lease);
                if (acquired!=1) { inFlight.remove(id); continue; }
                var active=new java.util.concurrent.atomic.AtomicBoolean(true);
                var delivery=admission.inspectCancellationEffect(id).thenCompose(inspection -> {
                    if (!active.get()) return java.util.concurrent.CompletableFuture.<BudgetCancellationSink.Result>completedFuture(null);
                    if (!(inspection instanceof WorkspaceAdmission.EffectInspection.Found found))
                        return java.util.concurrent.CompletableFuture.<BudgetCancellationSink.Result>completedFuture(null);
                    var view=found.effect();
                    var effect=new BudgetCancellationSink.Effect(id,view.token(),view.workspaceId(),view.partition(),(String)row[1],view.target(),view.intentReceipt());
                    return sink.cancel(effect).thenCompose(result -> active.get()
                            ? acknowledge(effect,result,lease)
                            : java.util.concurrent.CompletableFuture.completedFuture(result));
                });
                var bounded=new java.util.concurrent.CompletableFuture<BudgetCancellationSink.Result>();
                delivery.whenComplete((result,error)-> {
                    // A timed-out external chain still consumes capacity until it really finishes.
                    inFlight.remove(id);
                    if(error==null) bounded.complete(result); else bounded.completeExceptionally(error);
                });
                // Timeout does not cancel shared execution work; DB lease fencing rejects its late ack.
                bounded.orTimeout(2,java.util.concurrent.TimeUnit.SECONDS).whenComplete((result,error)-> {
                    active.set(false);
                });
            } catch (RuntimeException ex) { inFlight.remove(id); }
        }
    }
    private java.util.concurrent.CompletionStage<BudgetCancellationSink.Result> acknowledge(
            BudgetCancellationSink.Effect effect,BudgetCancellationSink.Result result,String lease) {
        if (result instanceof BudgetCancellationSink.Result.Stale stale) {
            if (!java.util.Objects.equals(effect.effectId(),stale.effectId()) || !java.util.Objects.equals(effect.token(),stale.token())
                    || !java.util.Objects.equals(effect.target(),stale.target()))
                return java.util.concurrent.CompletableFuture.completedFuture(result);
            return lane.submit(effect.workspaceId(), () -> transactions.execute(status -> {
                var rows=jdbc.query("""
                        select conversation_id from workspace_admission_registration
                         where token=? and workspace_id=? and partition=? and run_id=? for update
                        """,(rs,n)->rs.getString(1),effect.token(),effect.workspaceId(),effect.partition().name(),effect.runId());
                if (rows.size()!=1) return result;
                var run=runStateReader.readForUpdate(effect.runId());
                var checked=authority.prepareEffectInTransaction(effect);
                boolean completed=run!=null && run.status()==RunAdmissionStateReader.Status.COMPLETED
                        && rows.getFirst().equals(run.conversationId());
                if ((checked instanceof WorkspaceAdmission.EffectAcceptance.Stale || completed) && matchesOutbox(effect)) {
                    jdbc.update("""
                            update workspace_admission_effect_outbox set delivery_status='STALE',ack_receipt=?,acknowledged_at=current_timestamp,
                                delivery_lease_id=null,delivery_lease_until=null
                             where effect_id=? and delivery_status='PENDING' and delivery_lease_id=? and delivery_lease_until>clock_timestamp()
                            """,java.util.UUID.randomUUID().toString(),effect.effectId(),lease);
                }
                return result;
            }));
        }
        if (!(result instanceof BudgetCancellationSink.Result.Accepted accepted)
                || !java.util.Objects.equals(effect.effectId(),accepted.effectId())
                || !java.util.Objects.equals(effect.token(),accepted.token())
                || !java.util.Objects.equals(effect.target(),accepted.target())
                || accepted.receiptId()==null || accepted.receiptId().isBlank()
                || accepted.receiptId().equals(effect.intentReceipt()))
            return java.util.concurrent.CompletableFuture.completedFuture(result);
        return sink.inspectAcceptance(effect,accepted.receiptId()).thenCompose(inspection -> {
            if (!(inspection instanceof BudgetCancellationSink.ReceiptInspection.Matched matched)
                    || !effect.equals(matched.effect()) || !accepted.receiptId().equals(matched.acceptanceReceipt()))
                return java.util.concurrent.CompletableFuture.completedFuture(result);
            return lane.submit(effect.workspaceId(), () -> transactions.execute(status -> {
                if (!matchesOutbox(effect)) return result;
                jdbc.update("""
                    update workspace_admission_effect_outbox set delivery_status='ACKNOWLEDGED',ack_receipt=?,acknowledged_at=current_timestamp,
                        delivery_lease_id=null,delivery_lease_until=null
                     where effect_id=? and registration_token=? and intent_receipt=? and delivery_status='PENDING'
                       and delivery_lease_id=? and delivery_lease_until>clock_timestamp()
                    """,accepted.receiptId(),effect.effectId(),effect.token(),effect.intentReceipt(),lease);
                return result;
            }));
        });
    }
    private boolean matchesOutbox(BudgetCancellationSink.Effect effect) {
        var rows=jdbc.query("""
                select registration_token,target_workspace_id,target_partition,target_run_id,pending_id,pending_epoch,
                       claim_id,target_attempt_id,period_start,period_end,generation,intent_receipt
                  from workspace_admission_effect_outbox where effect_id=? and delivery_status='PENDING' for update
                """,(rs,n)->new Object[]{rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getObject(6),
                    rs.getString(7),rs.getString(8),rs.getDate(9).toLocalDate().atStartOfDay(java.time.ZoneId.of("Asia/Taipei")).toInstant(),
                    rs.getDate(10).toLocalDate().atStartOfDay(java.time.ZoneId.of("Asia/Taipei")).toInstant(),rs.getLong(11),rs.getString(12)},effect.effectId());
        if (rows.size()!=1) return false;
        var r=rows.getFirst();
        if (!effect.token().equals(r[0]) || !effect.workspaceId().equals(r[1]) || !effect.partition().name().equals(r[2])
                || !effect.runId().equals(r[3]) || !effect.intentReceipt().equals(r[11])) return false;
        if (effect.target() instanceof BudgetCancellationSink.Target.Pending p) {
            var f=p.fence();
            return effect.token().equals(f.token()) && effect.workspaceId().equals(f.workspaceId()) && effect.partition()==f.partition()
                    && f.pendingId().equals(r[4]) && r[5]!=null && f.epoch()==((Number)r[5]).longValue() && r[6]==null
                    && f.periodStart().equals(r[8]) && f.periodEnd().equals(r[9]);
        }
        if (effect.target() instanceof BudgetCancellationSink.Target.Claimed c) {
            var f=c.fence();
            return effect.token().equals(f.token()) && effect.workspaceId().equals(f.workspaceId()) && effect.partition()==f.partition()
                    && r[4]==null && f.claimId().equals(r[6]) && f.attemptId().equals(r[7])
                    && f.periodStart().equals(r[8]) && f.periodEnd().equals(r[9]) && f.policyGeneration()==(long)r[10];
        }
        return false;
    }
}
