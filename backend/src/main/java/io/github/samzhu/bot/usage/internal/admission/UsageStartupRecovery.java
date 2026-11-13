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
final class UsageStartupRecovery implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    private final JdbcTemplate startupJdbc;
    private final WorkspaceAdmission admission;
    private final WorkspaceLane lane;
    private final BudgetProjection projection;
    private final TransactionTemplate transactions;
    private final RunAdmissionStateReader runStateReader;
    private final StartupRecoveryState startupState;

    UsageStartupRecovery(JdbcTemplate jdbc, WorkspaceAdmission admission, WorkspaceLane lane,
            BudgetProjection projection, org.springframework.transaction.PlatformTransactionManager manager, RunAdmissionStateReader runStateReader,
            StartupRecoveryState startupState) {
        this.jdbc=jdbc; this.admission=admission; this.lane=lane; this.projection=projection;
        this.startupJdbc=new JdbcTemplate(java.util.Objects.requireNonNull(jdbc.getDataSource()));
        this.startupJdbc.setQueryTimeout(5);
        this.transactions=new TransactionTemplate(manager);
        this.runStateReader=runStateReader;
        this.startupState=startupState;
    }

    @Override public void run(ApplicationArguments args) {
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
        cursor="";
        for (;;) {
            var rows=startupJdbc.query("select id,workspace_id,simulation from provider_attempt where execution_outcome='IN_PROGRESS' and id>? order by id limit 100",
                    (rs,n)->new Object[]{rs.getString(1),rs.getString(2),rs.getBoolean(3)},cursor);
            if (rows.isEmpty()) break;
            for (var row:rows) {
                String attempt=(String)row[0],workspace=(String)row[1]; cursor=attempt;
                var partition=(boolean)row[2]?WorkspaceAdmission.Partition.SIMULATION:WorkspaceAdmission.Partition.REAL;
                await(lane.submitCritical(workspace,()->transactions.execute(status -> {
                    jdbc.execute("set local lock_timeout = '2s'");
                    jdbc.execute("set local statement_timeout = '5s'");
                    UsageWriterLock.registrationForAttempt(jdbc,attempt);
                    jdbc.update("""
                            update provider_attempt set execution_outcome='UNKNOWN',usage_presence='UNKNOWN',
                                projection_state='UNKNOWN',projection_error='PROCESS_RESTART',projection_version=projection_version+1
                             where id=? and execution_outcome='IN_PROGRESS'
                               and not exists(select 1 from workspace_admission_finalization where attempt_id=? and state='PENDING')
                            """,attempt,attempt);
                    var claims=jdbc.query("select period_start,period_end from workspace_admission_claim where attempt_id=?",
                            (rs,n)->new java.time.LocalDate[]{rs.getDate(1).toLocalDate(),rs.getDate(2).toLocalDate()},attempt);
                    if (!claims.isEmpty()) {
                        jdbc.update("update workspace_admission_claim set status='UNKNOWN',updated_at=current_timestamp where attempt_id=? and status in ('CLAIMED','CANCEL_REQUESTED')",attempt);
                        var zone=java.time.ZoneId.of("Asia/Taipei");
                        projection.recompute(workspace,partition,claims.getFirst()[0].atStartOfDay(zone).toInstant(),claims.getFirst()[1].atStartOfDay(zone).toInstant());
                    }
                    return null;
                })));
            }
        }
        cursor="";
        for (;;) {
            var registrations=startupJdbc.query("select token,run_id,workspace_id from workspace_admission_registration where status='REGISTERED' and token>? order by token limit 100",
                    (rs,n)->new String[]{rs.getString(1),rs.getString(2),rs.getString(3)},cursor);
            if (registrations.isEmpty()) break;
            for (var registration:registrations) {
                cursor=registration[0];
                await(lane.submitCritical(registration[2],()->transactions.execute(status -> {
                    jdbc.execute("set local lock_timeout = '2s'");
                    jdbc.execute("set local statement_timeout = '5s'");
                    var states=jdbc.query("select status from workspace_admission_registration where token=? and run_id=? and workspace_id=? for update",
                            (rs,n)->rs.getString(1),registration[0],registration[1],registration[2]);
                    if (states.size()!=1 || !"REGISTERED".equals(states.getFirst())) return null;
                    var run=runStateReader.readForUpdate(registration[1]);
                    if (run==null || run.status()==null) throw new IllegalStateException("Run state is unknown");
                    if (run.status()!=RunAdmissionStateReader.Status.NOT_FOUND) return null;
                    Boolean hasFacts=jdbc.queryForObject("""
                            select exists(select 1 from provider_attempt where run_id=?)
                                or exists(select 1 from workspace_admission_claim where registration_token=?)
                                or exists(select 1 from workspace_admission_dispatch where registration_token=?)
                                or exists(select 1 from workspace_admission_finalization where registration_token=?)
                                or exists(select 1 from workspace_admission_effect_outbox where registration_token=?)
                            """,Boolean.class,registration[1],registration[0],registration[0],registration[0],registration[0]);
                    if (!Boolean.FALSE.equals(hasFacts)) throw new IllegalStateException("Absent Run has unresolved admission facts");
                    jdbc.update("update workspace_admission_registration set status='TERMINAL',updated_at=current_timestamp where token=? and status='REGISTERED'",registration[0]);
                    return null;
                })));
            }
        }
        startupState.markRebuilt();
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
