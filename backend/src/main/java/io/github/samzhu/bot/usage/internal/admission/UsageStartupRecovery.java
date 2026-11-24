package io.github.samzhu.bot.usage.internal.admission;

import io.github.samzhu.bot.usage.WorkspaceAdmission;
import io.github.samzhu.bot.usage.RunAdmissionStateReader;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** Replays durable terminal evidence before treating unproven in-flight attempts as unknown. */
@Component
@Order(20)
final class UsageStartupRecovery implements ApplicationRunner, io.github.samzhu.bot.usage.RunRecoverySweep {
    private final JdbcTemplate jdbc;
    private final JdbcTemplate startupJdbc;
    private final WorkspaceAdmission admission;
    private final DefaultWorkspaceAdmission supersededAuthority;
    private final WorkspaceLane lane;
    private final BudgetProjection projection;
    private final TransactionTemplate transactions;
    private final RunAdmissionStateReader runStateReader;
    private final io.github.samzhu.bot.usage.RunOwnershipGuard ownership;
    private final StartupRecoveryState startupState;
    private final io.github.samzhu.bot.usage.ConversationTitleAdmission titleAdmission;
    private String registrationCursor="", runCursor="", startCursor="";

    UsageStartupRecovery(JdbcTemplate jdbc, WorkspaceAdmission admission, DefaultWorkspaceAdmission supersededAuthority, WorkspaceLane lane,
            BudgetProjection projection, org.springframework.transaction.PlatformTransactionManager manager, RunAdmissionStateReader runStateReader,
            io.github.samzhu.bot.usage.RunOwnershipGuard ownership,
            io.github.samzhu.bot.usage.ConversationTitleAdmission titleAdmission, StartupRecoveryState startupState) {
        this.jdbc=jdbc; this.admission=admission; this.supersededAuthority=supersededAuthority; this.lane=lane; this.projection=projection;
        this.startupJdbc=new JdbcTemplate(java.util.Objects.requireNonNull(jdbc.getDataSource()));
        this.startupJdbc.setQueryTimeout(5);
        this.transactions=new TransactionTemplate(manager);
        this.runStateReader=runStateReader;
        this.ownership=ownership;
        this.titleAdmission=titleAdmission;
        this.startupState=startupState;
    }

    @Override public void run(ApplicationArguments args) {
        long titleDeadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(25);
        while (System.nanoTime()<titleDeadline && titleAdmission.recoverExpiredBatch()>0) { /* Continue bounded pages. */ }
        String cursor="";
        for (;;) {
            var rows=startupJdbc.query("""
                    select f.attempt_id,r.token,r.workspace_id,r.run_id,r.actor_id,r.conversation_id,r.partition
                      from workspace_admission_finalization f join workspace_admission_registration r on r.token=f.registration_token
                     where f.state='PENDING' and f.attempt_id>? order by f.attempt_id limit 100
                    """,(rs,n)->new Object[]{rs.getString(1),new WorkspaceAdmission.Registration(rs.getString(2),rs.getString(3),
                        rs.getString(4),rs.getString(5),rs.getString(6),WorkspaceAdmission.Partition.valueOf(rs.getString(7)))},cursor);
            if (rows.isEmpty()) break;
            for (var row:rows) {
                cursor=(String)row[0];
                if (await(admission.recoverFinalization((WorkspaceAdmission.Registration)row[1],cursor))
                        !=WorkspaceAdmission.FinalizationResult.SETTLED)
                    throw new IllegalStateException("Terminal intake recovery is unresolved");
            }
        }
        recoverExpired(1000,java.time.Duration.ofSeconds(25));
        startupState.markRebuilt();
    }

    @Override public void recoverExpiredBatch() {
        titleAdmission.recoverExpiredBatch();
        recoverExpired(1,java.time.Duration.ofMillis(250));
    }

    private synchronized void recoverExpired(int maxPages,java.time.Duration budget) {
        long deadline=System.nanoTime()+budget.toNanos();
        // Only a known, expired ownership lease can authorize recovery. Registration,
        // Run and ownership are locked in that order in the same transaction.
        for (int page=0;page<maxPages && System.nanoTime()<deadline;page++) {
            var candidates=startupJdbc.query("""
                    select r.token,r.run_id,r.workspace_id,r.conversation_id,r.partition,r.actor_id
                      from workspace_admission_registration r
                      join chat_run cr on cr.id=r.run_id
                      join run_ownership o on o.run_id=cr.id
                     where r.token>? and r.status<>'TERMINAL' and cr.status in ('PENDING','RUNNING')
                       and o.lease_until<=clock_timestamp()
                     order by r.token limit 100
                    """, (rs,n)->new String[]{rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getString(6)},registrationCursor);
            if (candidates.isEmpty()) { registrationCursor=""; break; }
            for (var candidate:candidates) {
                if (System.nanoTime()>=deadline) break;
                registrationCursor=candidate[0];
                await(lane.submitCritical(candidate[2], () -> transactions.execute(status -> {
                    jdbc.execute("set local lock_timeout = '2s'");
                    jdbc.execute("set local statement_timeout = '5s'");
                    var registrations=jdbc.query("select status from workspace_admission_registration where token=? and run_id=? and workspace_id=? for update",
                            (rs,n)->rs.getString(1),candidate[0],candidate[1],candidate[2]);
                    if (registrations.size()!=1 || "TERMINAL".equals(registrations.getFirst())) return null;
                    var run=runStateReader.readForUpdate(candidate[1]);
                    if (run==null || run.status()!=RunAdmissionStateReader.Status.ACTIVE || !candidate[3].equals(run.conversationId())) return null;
                    // Pending intents require their own evidence-based recovery; don't overwrite them.
                    Boolean unresolved=jdbc.queryForObject("""
                            select exists(select 1 from workspace_admission_dispatch where registration_token=? and state='PENDING')
                                or exists(select 1 from workspace_admission_finalization where registration_token=? and state='PENDING')
                            """,Boolean.class,candidate[0],candidate[0]);
                    if (!Boolean.FALSE.equals(unresolved)) return null;
                    var registration=new WorkspaceAdmission.Registration(candidate[0],candidate[2],candidate[1],candidate[5],candidate[3],WorkspaceAdmission.Partition.valueOf(candidate[4]));
                    // Trusted superseded work has precedence over generic orphan recovery.
                    if (supersededAuthority.preserveSupersededInTransaction(registration)) return null;
                    var fence=ownership.takeExpired(candidate[1]);
                    if (fence==null) return null;
                    var attempts=jdbc.query("select id,workspace_id,simulation from provider_attempt where run_id=? order by id for update",
                            (rs,n)->new Object[]{rs.getString(1),rs.getString(2),rs.getBoolean(3)},candidate[1]);
                    for (var attempt:attempts) {
                        if (!candidate[2].equals(attempt[1]) || !candidate[4].equals((boolean)attempt[2]?"SIMULATION":"REAL"))
                            throw new IllegalStateException("Run attempt scope requires recovery");
                        jdbc.update("""
                                update provider_attempt set execution_outcome='UNKNOWN',usage_presence='UNKNOWN',
                                    projection_state='UNKNOWN',projection_error='PROCESS_RESTART',projection_version=projection_version+1
                                 where id=? and execution_outcome='IN_PROGRESS'
                                """,attempt[0]);
                    }
                    var claims=jdbc.query("select period_start,period_end from workspace_admission_claim where registration_token=? and status in ('CLAIMED','CANCEL_REQUESTED') for update",
                            (rs,n)->new java.time.LocalDate[]{rs.getDate(1).toLocalDate(),rs.getDate(2).toLocalDate()},candidate[0]);
                    jdbc.update("update workspace_admission_claim set status='UNKNOWN',updated_at=current_timestamp where registration_token=? and status in ('CLAIMED','CANCEL_REQUESTED')",candidate[0]);
                    var zone=java.time.ZoneId.of("Asia/Taipei");
                    var partition=WorkspaceAdmission.Partition.valueOf(candidate[4]);
                    for (var claim:claims) projection.recompute(candidate[2],partition,claim[0].atStartOfDay(zone).toInstant(),claim[1].atStartOfDay(zone).toInstant());
                    if (!ownership.failTakenRun(fence,candidate[3],"SYSTEM_RESTART_ORPHAN")) throw new IllegalStateException("Run ownership changed during recovery");
                    return null;
                })));
            }
        }
        // A known expired Run can be recovered without a registration only after
        // locking the Run and rechecking both registration and usage absence.
        for (int page=0;page<maxPages && System.nanoTime()<deadline;page++) {
            var runs=startupJdbc.query("""
                    select cr.id,cr.conversation_id from chat_run cr join run_ownership o on o.run_id=cr.id
                     where cr.id>? and cr.status in ('PENDING','RUNNING') and o.lease_until<=clock_timestamp()
                       and not exists(select 1 from workspace_admission_registration r where r.run_id=cr.id)
                     order by cr.id limit 100
                    """,(rs,n)->new String[]{rs.getString(1),rs.getString(2)},runCursor);
            if (runs.isEmpty()) { runCursor=""; break; }
            for (var candidate:runs) {
                if (System.nanoTime()>=deadline) break;
                runCursor=candidate[0];
                transactions.executeWithoutResult(status -> {
                    jdbc.execute("set local lock_timeout = '2s'");
                    jdbc.execute("set local statement_timeout = '5s'");
                    var run=runStateReader.readForUpdate(candidate[0]);
                    if (run==null || run.status()!=RunAdmissionStateReader.Status.ACTIVE || !candidate[1].equals(run.conversationId())) return;
                    Boolean facts=jdbc.queryForObject("""
                            select exists(select 1 from workspace_admission_registration where run_id=?)
                                or exists(select 1 from provider_attempt where run_id=?)
                            """,Boolean.class,candidate[0],candidate[0]);
                    if (!Boolean.FALSE.equals(facts)) return;
                    var fence=ownership.takeExpired(candidate[0]);
                    if (fence!=null && !ownership.failTakenRun(fence,candidate[1],"SYSTEM_RESTART_ORPHAN"))
                        throw new IllegalStateException("Run ownership changed during recovery");
                });
            }
        }
        // A known expired start capability can be reclaimed only under the same
        // registration lock used by the Run creation transaction.
        for (int page=0;page<maxPages && System.nanoTime()<deadline;page++) {
            var starts=startupJdbc.query("""
                    select token,run_id,workspace_id from workspace_admission_registration
                     where token>? and status='REGISTERED' and start_lease_until<=clock_timestamp()
                     order by token limit 100
                    """,(rs,n)->new String[]{rs.getString(1),rs.getString(2),rs.getString(3)},startCursor);
            if (starts.isEmpty()) { startCursor=""; break; }
            for (var start:starts) {
                if (System.nanoTime()>=deadline) break;
                startCursor=start[0];
                await(lane.submitCritical(start[2], () -> transactions.execute(status -> {
                    jdbc.execute("set local lock_timeout = '2s'");
                    jdbc.execute("set local statement_timeout = '5s'");
                    jdbc.query("select token from workspace_admission_registration where token=? for update",(rs,n)->rs.getString(1),start[0]);
                    Boolean expired=jdbc.queryForObject("""
                            select exists(select 1 from workspace_admission_registration
                             where token=? and run_id=? and workspace_id=? and status='REGISTERED' and start_lease_until<=clock_timestamp())
                            """,Boolean.class,start[0],start[1],start[2]);
                    if (!Boolean.TRUE.equals(expired)) return null;
                    var run=runStateReader.readForUpdate(start[1]);
                    if (run==null || run.status()!=RunAdmissionStateReader.Status.NOT_FOUND) return null;
                    Boolean facts=jdbc.queryForObject("""
                            select exists(select 1 from provider_attempt where run_id=?)
                                or exists(select 1 from workspace_admission_claim where registration_token=?)
                                or exists(select 1 from workspace_admission_dispatch where registration_token=?)
                                or exists(select 1 from workspace_admission_finalization where registration_token=?)
                                or exists(select 1 from workspace_admission_effect_outbox where registration_token=?)
                            """,Boolean.class,start[1],start[0],start[0],start[0],start[0]);
                    if (!Boolean.FALSE.equals(facts)) return null;
                    jdbc.update("""
                            update workspace_admission_registration set status='TERMINAL',updated_at=clock_timestamp()
                             where token=? and status='REGISTERED' and start_lease_until<=clock_timestamp()
                            """,start[0]);
                    return null;
                })));
            }
        }
    }

    private static <T> T await(java.util.concurrent.CompletionStage<T> stage) {
        try { return stage.toCompletableFuture().get(10,java.util.concurrent.TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Startup recovery interrupted",interrupted);
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException failure) {
            throw new IllegalStateException("Startup recovery outcome is unknown",failure);
        }
    }
}
