package io.github.samzhu.bot.usage.internal.admission;

import io.github.samzhu.bot.conversation.ConversationOperations;
import io.github.samzhu.bot.usage.BudgetCancellationSink;
import io.github.samzhu.bot.usage.WorkspaceAdmission;
import io.github.samzhu.bot.usage.RunAdmissionStateReader;
import io.github.samzhu.bot.workspace.WorkspaceOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;

/** Durable admission state. A workspace lane serializes decisions until the transaction commits. */
@Service
public class DefaultWorkspaceAdmission implements WorkspaceAdmission {
    private static final ZoneId ZONE = ZoneId.of("Asia/Taipei");
    private final ConversationOperations conversations;
    private final WorkspaceOperations workspaces;
    private final JdbcTemplate jdbc;
    private final JdbcTemplate startupJdbc;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final WorkspaceLane lane;
    private final BudgetProjection projection;
    private final RunAdmissionStateReader runStateReader;
    private final StartupRecoveryState startupState;
    private final java.util.concurrent.ThreadPoolExecutor startupVerifier = new java.util.concurrent.ThreadPoolExecutor(1,1,0,
            java.util.concurrent.TimeUnit.MILLISECONDS,new java.util.concurrent.ArrayBlockingQueue<>(1),r -> {
                var thread=new Thread(r,"workspace-startup-verifier"); thread.setDaemon(true); return thread;
            },new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
    // A failed/ambiguous write is fail-closed for this process until recovery establishes durable state.
    private record RecoveryScope(String workspaceId, Partition partition) {}
    private final java.util.Set<RecoveryScope> recoveryScopes = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final java.util.concurrent.atomic.AtomicBoolean startupReady = new java.util.concurrent.atomic.AtomicBoolean(false);
    private final java.util.Set<String> supersededInFlight = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private String supersededCursor = "";
    private final io.github.samzhu.bot.usage.internal.pricing.CatalogActivation catalog;

    public DefaultWorkspaceAdmission(ConversationOperations conversations, WorkspaceOperations workspaces,
            JdbcTemplate jdbc, org.springframework.transaction.PlatformTransactionManager manager,
            Clock clock, WorkspaceLane lane, io.github.samzhu.bot.usage.internal.pricing.CatalogActivation catalog, BudgetProjection projection,
            RunAdmissionStateReader runStateReader, StartupRecoveryState startupState) {
        this.conversations = conversations;
        this.workspaces = workspaces;
        this.jdbc = jdbc;
        this.startupJdbc = new JdbcTemplate(java.util.Objects.requireNonNull(jdbc.getDataSource()));
        this.startupJdbc.setQueryTimeout(5);
        this.transactions = new TransactionTemplate(manager);
        this.clock = clock;
        this.lane = lane;
        this.projection = projection;
        this.catalog = catalog;
        this.runStateReader = runStateReader;
        this.startupState = startupState;
    }

    @Override public CompletionStage<PromptFailurePage> scanSettledPromptFailures(String afterExclusive, String upperInclusive, int limit) {
        if (limit<1 || limit>100 || (afterExclusive!=null && afterExclusive.isBlank())
                || (upperInclusive!=null && upperInclusive.isBlank()) || (afterExclusive!=null && upperInclusive==null))
            throw new IllegalArgumentException("Invalid prompt failure scan cursor or limit");
        try {
            return CompletableFuture.<PromptFailurePage>supplyAsync(() -> {
                try {
                    return transactions.execute(status -> {
                        jdbc.execute("set local lock_timeout = '2s'");
                        jdbc.execute("set local statement_timeout = '5s'");
                        String upper=upperInclusive;
                        if (upper==null) upper=startupJdbc.queryForObject("""
                            select max(attempt_id) from workspace_admission_finalization
                            where state='SETTLED' and terminal_reason='PROMPT_CONTEXT_UNAVAILABLE'
                            """,String.class);
                        if (upper==null) return new PromptFailurePage.Page(List.of(),null);
                        var facts=startupJdbc.query("""
                            select f.attempt_id,r.token,r.workspace_id,r.run_id,r.actor_id,r.conversation_id,r.partition
                              from workspace_admission_finalization f join workspace_admission_registration r on r.token=f.registration_token
                             where f.state='SETTLED' and f.terminal_reason='PROMPT_CONTEXT_UNAVAILABLE'
                               and f.attempt_id>? and f.attempt_id<=?
                             order by f.attempt_id limit ?
                            """,(rs,n) -> new PromptFailureFact(rs.getString(1),new Registration(rs.getString(2),rs.getString(3),
                                rs.getString(4),rs.getString(5),rs.getString(6),Partition.valueOf(rs.getString(7)))),
                                afterExclusive==null?"":afterExclusive,upper,limit);
                        return new PromptFailurePage.Page(List.copyOf(facts),upper);
                    });
                } catch (RuntimeException unknown) { return new PromptFailurePage.Unknown(); }
            },startupVerifier).minimalCompletionStage();
        } catch (RejectedExecutionException overloaded) {
            return CompletableFuture.completedFuture(new PromptFailurePage.Unknown());
        }
    }

    @Override public CompletionStage<SupersededPage> scanSupersededNotDispatched(String afterExclusive, String upperInclusive, int limit) {
        if (limit<1 || limit>100 || (afterExclusive!=null && afterExclusive.isBlank())
                || (upperInclusive!=null && upperInclusive.isBlank()) || (afterExclusive!=null && upperInclusive==null))
            throw new IllegalArgumentException("Invalid superseded scan cursor or limit");
        try {
            return CompletableFuture.<SupersededPage>supplyAsync(() -> {
                try {
                    String upper=upperInclusive;
                    if (upper==null) upper=startupJdbc.queryForObject("select max(registration_token) from workspace_admission_superseded",String.class);
                    if (upper==null) return new SupersededPage.Page(List.of(),null);
                    var facts=startupJdbc.query("""
                            select r.token,r.workspace_id,r.run_id,r.actor_id,r.conversation_id,r.partition,
                                   o.effect_id,o.pending_id,o.pending_epoch,o.period_start,o.period_end,o.intent_receipt
                              from workspace_admission_superseded s
                              join workspace_admission_registration r on r.token=s.registration_token
                              join workspace_admission_effect_outbox o on o.effect_id=s.old_effect_id
                             where s.registration_token>? and s.registration_token<=?
                               and o.registration_token=r.token and o.target_workspace_id=r.workspace_id
                               and o.target_partition=r.partition and o.target_run_id=r.run_id
                               and o.delivery_status='STALE' and o.claim_id is null and o.pending_id is not null
                               and not exists (select 1 from provider_attempt a
                                    left join workspace_admission_finalization f on f.attempt_id=a.id and f.registration_token=r.token
                                    where a.run_id=r.run_id and (a.execution_outcome='IN_PROGRESS' or f.state is distinct from 'SETTLED'
                                        or f.terminal_kind is distinct from a.execution_outcome))
                             order by s.registration_token limit ?
                            """,(rs,n) -> {
                        var registration=new Registration(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),Partition.valueOf(rs.getString(6)));
                        var fence=new PendingFence(registration.token(),rs.getString(8),registration.workspaceId(),registration.partition(),
                                rs.getDate(10).toLocalDate().atStartOfDay(ZONE).toInstant(),rs.getDate(11).toLocalDate().atStartOfDay(ZONE).toInstant(),rs.getLong(9));
                        var effect=new BudgetCancellationSink.Effect(rs.getString(7),registration.token(),registration.workspaceId(),registration.partition(),registration.runId(),new BudgetCancellationSink.Target.Pending(fence),rs.getString(12));
                        return new SupersededNotDispatched(registration,effect);
                    },afterExclusive==null?"":afterExclusive,upper,limit);
                    return new SupersededPage.Page(List.copyOf(facts),upper);
                } catch (RuntimeException unknown) { return new SupersededPage.Unknown(); }
            },startupVerifier).minimalCompletionStage();
        } catch (RejectedExecutionException overloaded) {
            return CompletableFuture.completedFuture(new SupersededPage.Unknown());
        }
    }

    /** Stale effects are durable work, so discovery repeats a bounded full cycle after failures and restarts. */
    @org.springframework.scheduling.annotation.Scheduled(initialDelayString="${bot.budget.finalization.initial-delay-ms:1000}",fixedDelayString="${bot.budget.finalization.delay-ms:1000}")
    synchronized void recoverSupersededEffects() {
        final List<String[]> rows;
        try {
            rows=startupJdbc.query("""
                    select o.effect_id,r.token,r.workspace_id,r.run_id,r.actor_id,r.conversation_id,r.partition
                      from workspace_admission_effect_outbox o join workspace_admission_registration r on r.token=o.registration_token
                     where o.delivery_status='STALE' and o.claim_id is null and o.effect_id>?
                       and not exists(select 1 from workspace_admission_superseded s where s.registration_token=r.token)
                     order by o.effect_id limit 100
                    """,(rs,n)->new String[]{rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getString(6),rs.getString(7)},supersededCursor);
        } catch (RuntimeException unavailable) { return; }
        if (rows.isEmpty()) { supersededCursor=""; return; }
        for (var row:rows) {
            if (supersededInFlight.size()>=100) break;
            supersededCursor=row[0];
            if (!supersededInFlight.add(row[0])) continue;
            try {
                var r=new Registration(row[1],row[2],row[3],row[4],row[5],Partition.valueOf(row[6]));
                executeCritical(r.workspaceId(),()->transactions.execute(status->createSupersededDisposition(r,row[0])),false)
                        .whenComplete((ignored,error)->supersededInFlight.remove(row[0]));
            } catch (RuntimeException failure) { supersededInFlight.remove(row[0]); }
        }
    }

    private boolean createSupersededDisposition(Registration r, String effectId) {
        jdbc.execute("set local lock_timeout = '2s'");
        jdbc.execute("set local statement_timeout = '5s'");
        String registrationState=state(r,true);
        if (registrationState==null || "TERMINAL".equals(registrationState) || superseded(r.token())) return false;
        // Lock every attempt in deterministic order after the registration, before deciding the whole-Run fence.
        var attempts=jdbc.query("""
                select id,workspace_id,actor_id,simulation,started_at,execution_outcome
                  from provider_attempt where run_id=? order by id for update
                """,(rs,n)->new Object[]{rs.getString(1),rs.getString(2),rs.getString(3),rs.getBoolean(4),rs.getTimestamp(5),rs.getString(6)},r.runId());
        if (Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from workspace_admission_claim where registration_token=?)",Boolean.class,r.token()))
                || Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from workspace_admission_dispatch where registration_token=? and state='PENDING')",Boolean.class,r.token()))) return false;
        Boolean exact=jdbc.queryForObject("""
                select exists(select 1 from workspace_admission_effect_outbox where effect_id=? and registration_token=?
                    and target_workspace_id=? and target_partition=? and target_run_id=? and delivery_status='STALE'
                    and claim_id is null and target_attempt_id is null and pending_id is not null and pending_epoch is not null
                    and intent_receipt is not null and period_end>period_start)
                """,Boolean.class,effectId,r.token(),r.workspaceId(),r.partition().name(),r.runId());
        if (!Boolean.TRUE.equals(exact)) return false;
        for (var attempt:attempts) {
            if (!r.workspaceId().equals(attempt[1]) || !r.actorId().equals(attempt[2])
                    || ((boolean)attempt[3])!=(r.partition()==Partition.SIMULATION) || attempt[4]!=null) return false;
            var intents=jdbc.query("select terminal_kind,state from workspace_admission_finalization where attempt_id=? and registration_token=?",
                    (rs,n)->new String[]{rs.getString(1),rs.getString(2)},attempt[0],r.token());
            if (intents.isEmpty()) {
                if (!"IN_PROGRESS".equals(attempt[5])) return false;
            } else if (!"NOT_DISPATCHED".equals(intents.getFirst()[0])
                    || (!"IN_PROGRESS".equals(attempt[5]) && (!"NOT_DISPATCHED".equals(attempt[5]) || !"SETTLED".equals(intents.getFirst()[1])))) return false;
        }
        jdbc.update("insert into workspace_admission_superseded(registration_token,old_effect_id) values (?,?)",r.token(),effectId);
        for (var attempt:attempts) {
            if ("IN_PROGRESS".equals(attempt[5])) jdbc.update("""
                    insert into workspace_admission_finalization(attempt_id,registration_token,terminal_kind,terminal_reason,state)
                    values (?,?,'NOT_DISPATCHED','BUDGET_CANCELLATION_SUPERSEDED','PENDING') on conflict (attempt_id) do nothing
                    """,attempt[0],r.token());
        }
        return true;
    }

    @Override public CompletionStage<RegisterResult> register(Register command) {
        if (command == null || blank(command.workspaceId()) || blank(command.actorId()) || blank(command.runId())
                || blank(command.conversationId()) || command.partition() == null) {
            return CompletableFuture.completedFuture(new RegisterResult.Denied(Reason.UNAUTHORIZED));
        }
        if (!startupReady.get()) return CompletableFuture.completedFuture(new RegisterResult.Denied(Reason.RECOVERY_REQUIRED));
        return execute(command.workspaceId(), () -> {
            if (!authorized(command.workspaceId(), command.conversationId(), command.actorId()))
                return new RegisterResult.Denied(Reason.UNAUTHORIZED);
            if (recoveryScopes.contains(scopeKey(command.workspaceId(),command.partition())) || hasPendingRecovery(command.workspaceId(),command.partition()))
                return new RegisterResult.Denied(Reason.RECOVERY_REQUIRED);
            RegisterResult result = transactions.execute(status -> {
                var period = YearMonth.from(clock.instant().atZone(ZONE));
                jdbc.update("insert into workspace_monthly_budget(workspace_id,partition) values (?,?) on conflict do nothing",command.workspaceId(),command.partition().name());
                jdbc.update("insert into workspace_monthly_budget_period(workspace_id,partition,period_start) values (?,?,?) on conflict do nothing",command.workspaceId(),command.partition().name(),period.atDay(1));
                var policy=jdbc.queryForMap("""
                        select b.enabled,b.cap_usd,p.known_subtotal_usd,p.terminal_effective_unknown
                          from workspace_monthly_budget b join workspace_monthly_budget_period p using(workspace_id,partition)
                         where b.workspace_id=? and b.partition=? and p.period_start=? for update of b,p
                        """,command.workspaceId(),command.partition().name(),period.atDay(1));
                if ((boolean)policy.get("enabled")) {
                    var cap=(java.math.BigDecimal)policy.get("cap_usd");
                    var known=(java.math.BigDecimal)policy.get("known_subtotal_usd");
                    if (cap.signum()==0 || (known!=null && known.compareTo(cap)>=0)) return new RegisterResult.Denied(Reason.MONTH_CAP);
                    if ((boolean)policy.get("terminal_effective_unknown")) return new RegisterResult.Denied(Reason.MONTH_UNKNOWN);
                }
                var registration = new Registration(UUID.randomUUID().toString(), command.workspaceId(),
                        command.runId(), command.actorId(), command.conversationId(), command.partition());
                int inserted = jdbc.update("""
                    insert into workspace_admission_registration(token,run_id,workspace_id,actor_id,conversation_id,
                        partition,status,pending_id,pending_period_start,pending_period_end)
                    values (?,?,?,?,?,?,'REGISTERED',?,?,?) on conflict (run_id) do nothing
                    """, registration.token(), registration.runId(), registration.workspaceId(), registration.actorId(),
                        registration.conversationId(), registration.partition().name(), UUID.randomUUID().toString(),
                        period.atDay(1), period.plusMonths(1).atDay(1));
                return inserted == 1 ? new RegisterResult.Registered(registration)
                        : new RegisterResult.Collision();
            });
            if (!(result instanceof RegisterResult.Collision)) return result;
            // Recheck under registration -> Run locks in a fresh transaction; missing or inconsistent
            // evidence can be a still-in-flight start and must not be reported as a proven collision.
            return transactions.execute(status -> {
                var conversations = jdbc.query("select conversation_id from workspace_admission_registration where run_id=? for update",
                        (rs,n) -> rs.getString(1), command.runId());
                if (conversations.size()!=1) return new RegisterResult.Denied(Reason.RECOVERY_REQUIRED);
                var run=runStateReader.readForUpdate(command.runId());
                if (run==null || run.status()==null || run.status()==RunAdmissionStateReader.Status.NOT_FOUND
                        || !conversations.getFirst().equals(run.conversationId()))
                    return new RegisterResult.Denied(Reason.RECOVERY_REQUIRED);
                return new RegisterResult.Collision();
            });
        }, new RegisterResult.Denied(Reason.OVERLOADED));
    }

    @Override public CompletionStage<PublicationResult> publish(Registration registration) {
        if (!valid(registration)) return CompletableFuture.completedFuture(new PublicationResult.Denied(Reason.UNAUTHORIZED));
        return execute(registration.workspaceId(), () -> transactions.execute(status -> {
            String state = state(registration, true);
            if (state == null || !authorized(registration.workspaceId(), registration.conversationId(), registration.actorId()))
                return new PublicationResult.Denied(Reason.UNAUTHORIZED);
            if ("CANCEL_REQUESTED".equals(state)) return new PublicationResult.CancelPending();
            if (!"REGISTERED".equals(state) && !"PUBLISHED".equals(state))
                return new PublicationResult.Denied(Reason.RECOVERY_REQUIRED);
            var run=runStateReader.readForUpdate(registration.runId());
            if (run==null || run.status()!=RunAdmissionStateReader.Status.ACTIVE
                    || !registration.conversationId().equals(run.conversationId()))
                return new PublicationResult.Denied(Reason.RECOVERY_REQUIRED);
            jdbc.update("update workspace_admission_registration set status='PUBLISHED',updated_at=current_timestamp where token=?", registration.token());
            return new PublicationResult.Ready();
        }), new PublicationResult.Denied(Reason.OVERLOADED));
    }

    @Override public CompletionStage<CancellationView> cancellationState(Registration registration) {
        if (!valid(registration)) return CompletableFuture.completedFuture(new CancellationView(CancellationState.UNKNOWN,List.of(),List.of()));
        return execute(registration.workspaceId(), () -> transactions.execute(status -> {
            String value = state(registration, false);
            if (value == null || recoveryScopes.contains(scopeKey(registration.workspaceId(),registration.partition())) || hasPendingRecovery(registration.workspaceId(),registration.partition())) return new CancellationView(CancellationState.UNKNOWN,List.of(),List.of());
            if ("TERMINAL".equals(value)) return new CancellationView(CancellationState.TERMINAL,List.of(),List.of());
            if ("CANCEL_REQUESTED".equals(value)) return new CancellationView(CancellationState.REQUESTED,claims(registration),pending(registration));
            return new CancellationView(CancellationState.NONE,List.of(),List.of());
        }), new CancellationView(CancellationState.UNKNOWN,List.of(),List.of()));
    }

    @Override public CompletionStage<CancelResult> cancelRun(Registration registration) {
        if (!valid(registration)) return CompletableFuture.completedFuture(new CancelResult(CancelStatus.UNKNOWN,List.of(),List.of()));
        return execute(registration.workspaceId(), () -> transactions.execute(status -> {
            String value = state(registration, true);
            if (value == null || !authorized(registration.workspaceId(),registration.conversationId(),registration.actorId()))
                return new CancelResult(CancelStatus.UNKNOWN,List.of(),List.of());
            if ("TERMINAL".equals(value)) return new CancelResult(CancelStatus.ALREADY_TERMINAL,List.of(),List.of());
            var claims = claims(registration); var pending = pending(registration);
            jdbc.update("update workspace_admission_registration set status='CANCEL_REQUESTED',user_cancel_requested_at=coalesce(user_cancel_requested_at,current_timestamp),updated_at=current_timestamp where token=?", registration.token());
            jdbc.update("update workspace_admission_claim set status='CANCEL_REQUESTED',updated_at=current_timestamp where registration_token=? and status='CLAIMED'", registration.token());
            return new CancelResult(claims.isEmpty() ? CancelStatus.BEFORE_CLAIM : CancelStatus.AFTER_CLAIM,claims,pending);
        }), new CancelResult(CancelStatus.UNKNOWN,List.of(),List.of()));
    }

    private String state(Registration r, boolean lock) {
        var rows = jdbc.query("select status from workspace_admission_registration where token=? and workspace_id=? and run_id=? and actor_id=? and conversation_id=? and partition=?" + (lock ? " for update" : ""),
                (rs,n) -> rs.getString(1), r.token(),r.workspaceId(),r.runId(),r.actorId(),r.conversationId(),r.partition().name());
        return rows.isEmpty() ? null : rows.getFirst();
    }
    private List<ClaimFence> claims(Registration r) {
        return jdbc.query("select claim_id,attempt_id,period_start,period_end,generation from workspace_admission_claim where registration_token=? and status in ('CLAIMED','CANCEL_REQUESTED','UNKNOWN') order by claim_id",
                (rs,n) -> new ClaimFence(r.token(),rs.getString(1),rs.getString(2),r.workspaceId(),r.partition(),
                        rs.getDate(3).toLocalDate().atStartOfDay(ZONE).toInstant(),rs.getDate(4).toLocalDate().atStartOfDay(ZONE).toInstant(),rs.getLong(5)),r.token());
    }
    private List<PendingFence> pending(Registration r) {
        return jdbc.query("select pending_id,pending_period_start,pending_period_end,pending_epoch from workspace_admission_registration where token=?",
                (rs,n) -> new PendingFence(r.token(),rs.getString(1),r.workspaceId(),r.partition(),
                        rs.getDate(2).toLocalDate().atStartOfDay(ZONE).toInstant(),rs.getDate(3).toLocalDate().atStartOfDay(ZONE).toInstant(),rs.getLong(4)),r.token());
    }
    private boolean authorized(String workspace, String conversation, String actor) {
        return workspaces.isMember(workspace,actor) && conversations.findConversationById(conversation,actor)
                .map(c -> workspace.equals(c.workspaceId())).orElse(false);
    }
    private static boolean blank(String s) { return s == null || s.isBlank(); }
    private static boolean valid(Registration r) {
        return r != null && !blank(r.token()) && !blank(r.workspaceId()) && !blank(r.runId())
                && !blank(r.actorId()) && !blank(r.conversationId()) && r.partition()!=null;
    }
    private <T> CompletionStage<T> executeCritical(String workspace, Supplier<T> task, T overloaded) {
        return lane.submitCritical(workspace,task).exceptionally(ex -> {
            if ((ex instanceof RejectedExecutionException || ex.getCause() instanceof RejectedExecutionException) && overloaded != null) return overloaded;
            throw new java.util.concurrent.CompletionException(ex);
        });
    }
    private <T> CompletionStage<T> execute(String workspace, Supplier<T> task, T overloaded) {
        return lane.submit(workspace,task).exceptionally(ex -> {
            if ((ex instanceof RejectedExecutionException || ex.getCause() instanceof RejectedExecutionException) && overloaded != null) return overloaded;
            throw new java.util.concurrent.CompletionException(ex);
        });
    }

    // Remaining operations are deliberately fail-closed until their durable claim and outbox paths are implemented.
    @Override public CompletionStage<Decision> dispatch(Dispatch command) {
        if (command == null || !valid(command.registration()) || blank(command.attemptId()))
            return CompletableFuture.completedFuture(new Decision.Denied(Reason.UNAUTHORIZED));
        if (!startupReady.get()) return CompletableFuture.completedFuture(new Decision.Denied(Reason.RECOVERY_REQUIRED));
        var r = command.registration();
        return this.<Decision>execute(r.workspaceId(), () -> {
            if (recoveryScopes.contains(scopeKey(r.workspaceId(),r.partition())) || hasPendingRecovery(r.workspaceId(),r.partition()))
                return new Decision.Denied(Reason.RECOVERY_REQUIRED);
            Reason preflight=transactions.execute(status -> persistDispatchIntent(command));
            if (preflight==Reason.CANCELLED)
                return transactions.execute(status -> pendingBudgetCancellation(command,false));
            if (preflight!=null) return new Decision.Denied(preflight);
            return transactions.execute(status -> {
            String registrationState = state(r, true);
            if (registrationState == null || !authorized(r.workspaceId(),r.conversationId(),r.actorId()))
                return resolveDenied(command,Reason.UNAUTHORIZED);
            if ("CANCEL_REQUESTED".equals(registrationState)) return pendingBudgetCancellation(command,true);
            if (!"PUBLISHED".equals(registrationState)) return new Decision.Denied(Reason.RECOVERY_REQUIRED);
            var run=runStateReader.readForUpdate(r.runId());
            if (run==null || run.status()!=RunAdmissionStateReader.Status.ACTIVE || !r.conversationId().equals(run.conversationId()))
                return new Decision.Denied(Reason.RECOVERY_REQUIRED);
            var attempts = jdbc.query("""
                    select run_id,workspace_id,actor_id,simulation,execution_outcome,started_at
                      from provider_attempt where id=? for update
                    """, (rs,n) -> new Object[]{rs.getString(1),rs.getString(2),rs.getString(3),rs.getBoolean(4),
                            rs.getString(5),rs.getTimestamp(6)},command.attemptId());
            if (attempts.isEmpty()) return resolveDenied(command,Reason.UNAUTHORIZED);
            var attempt=attempts.getFirst();
            if (!r.runId().equals(attempt[0]) || !r.workspaceId().equals(attempt[1]) || !r.actorId().equals(attempt[2])
                    || ((boolean)attempt[3]) != (r.partition()==Partition.SIMULATION))
                return resolveDenied(command,Reason.UNAUTHORIZED);
            if (!"IN_PROGRESS".equals(attempt[4]) || attempt[5]!=null)
                return new Decision.Denied(Reason.RECOVERY_REQUIRED);
            var sampled=catalog.sample(clock, Clock::instant);
            var period=YearMonth.from(sampled.startedAt().atZone(ZONE));
            // A pending fence from the previous Taipei month must not target the new cohort.
            jdbc.update("""
                    update workspace_admission_registration
                       set pending_id=?, pending_epoch=pending_epoch+1, pending_period_start=?, pending_period_end=?
                     where token=? and pending_period_start<>?
                    """,UUID.randomUUID().toString(),period.atDay(1),period.plusMonths(1).atDay(1),r.token(),period.atDay(1));
            jdbc.update("insert into workspace_monthly_budget(workspace_id,partition) values (?,?) on conflict do nothing",r.workspaceId(),r.partition().name());
            jdbc.update("insert into workspace_monthly_budget_period(workspace_id,partition,period_start) values (?,?,?) on conflict do nothing",r.workspaceId(),r.partition().name(),period.atDay(1));
            var budget=jdbc.queryForMap("""
                    select b.enabled,b.cap_usd,p.known_subtotal_usd,p.terminal_effective_unknown,p.gate_generation
                    from workspace_monthly_budget b join workspace_monthly_budget_period p using(workspace_id,partition)
                    where b.workspace_id=? and b.partition=? and p.period_start=? for update of b,p
                    """,r.workspaceId(),r.partition().name(),period.atDay(1));
            if ((boolean)budget.get("enabled")) {
                var cap=(java.math.BigDecimal)budget.get("cap_usd");
                var known=(java.math.BigDecimal)budget.get("known_subtotal_usd");
                if (cap.signum()==0 || (known!=null && known.compareTo(cap)>=0)) return resolveDenied(command,Reason.MONTH_CAP);
                if ((boolean)budget.get("terminal_effective_unknown")) return resolveDenied(command,Reason.MONTH_UNKNOWN);
            }
            int changed=jdbc.update("update provider_attempt set started_at=?,catalog_marker=?,catalog_revision_id=?,projection_version=projection_version+1 where id=? and started_at is null and execution_outcome='IN_PROGRESS'",
                    java.sql.Timestamp.from(sampled.startedAt()),sampled.revisionId()==null?"NO_CATALOG":"CAPTURED",sampled.revisionId(),command.attemptId());
            if (changed!=1) throw new IllegalStateException("attempt claim changed");
            long generation=((Number)budget.get("gate_generation")).longValue();
            String claimId=UUID.randomUUID().toString();
            jdbc.update("insert into workspace_admission_claim(claim_id,registration_token,attempt_id,period_start,period_end,generation,status) values (?,?,?,?,?,?,'CLAIMED')",
                    claimId,r.token(),command.attemptId(),period.atDay(1),period.plusMonths(1).atDay(1),generation);
            // A claimed attempt can never be cancelled using the earlier pending capability.
            jdbc.update("update workspace_admission_registration set pending_id=?,pending_epoch=pending_epoch+1 where token=?",
                    UUID.randomUUID().toString(),r.token());
            jdbc.update("update workspace_admission_dispatch set state='CLAIMED',resolved_at=current_timestamp where attempt_id=? and registration_token=? and state='PENDING'",command.attemptId(),r.token());
            return new Decision.Allowed(sampled.startedAt(),new ClaimFence(r.token(),claimId,command.attemptId(),r.workspaceId(),r.partition(),
                    period.atDay(1).atStartOfDay(ZONE).toInstant(),period.plusMonths(1).atDay(1).atStartOfDay(ZONE).toInstant(),generation));
            });
        },new Decision.Denied(Reason.OVERLOADED)).exceptionally(ex -> {
            recoveryScopes.add(scopeKey(r.workspaceId(),r.partition()));
            return new Decision.Denied(Reason.RECOVERY_REQUIRED);
        });
    }
    @Override public CompletionStage<ClaimInspection> inspectClaim(Registration registration,String attemptId) {
        if (!valid(registration) || blank(attemptId)) return CompletableFuture.completedFuture(new ClaimInspection.Unknown());
        return execute(registration.workspaceId(), () -> transactions.execute(status -> {
            if (state(registration,false)==null || recoveryScopes.contains(scopeKey(registration.workspaceId(),registration.partition())) || hasPendingRecovery(registration.workspaceId(),registration.partition())) return new ClaimInspection.Unknown();
            var rows=jdbc.query("select claim_id,period_start,period_end,generation from workspace_admission_claim where registration_token=? and attempt_id=?",
                    (rs,n) -> new ClaimFence(registration.token(),rs.getString(1),attemptId,registration.workspaceId(),registration.partition(),
                            rs.getDate(2).toLocalDate().atStartOfDay(ZONE).toInstant(),rs.getDate(3).toLocalDate().atStartOfDay(ZONE).toInstant(),rs.getLong(4)),registration.token(),attemptId);
            return rows.isEmpty() ? new ClaimInspection.Absent() : new ClaimInspection.Found(rows.getFirst());
        }),new ClaimInspection.Unknown());
    }
    @Override public CompletionStage<ClaimInspection> recoverClaim(Registration registration,String attemptId) {
        if (!valid(registration) || blank(attemptId)) return CompletableFuture.completedFuture(new ClaimInspection.Unknown());
        return executeCritical(registration.workspaceId(), () -> transactions.execute(status -> {
            if (state(registration,true)==null || hasPendingFinalization(registration.workspaceId(),registration.partition()))
                return (ClaimInspection)new ClaimInspection.Unknown();
            var attempts=jdbc.query("select run_id,workspace_id,actor_id,simulation,started_at,execution_outcome from provider_attempt where id=? for update",
                    (rs,n)->new Object[]{rs.getString(1),rs.getString(2),rs.getString(3),rs.getBoolean(4),rs.getTimestamp(5),rs.getString(6)},attemptId);
            if (attempts.isEmpty()) return (ClaimInspection)new ClaimInspection.Unknown();
            var a=attempts.getFirst();
            if (!registration.runId().equals(a[0]) || !registration.workspaceId().equals(a[1]) || !registration.actorId().equals(a[2])
                    || ((boolean)a[3])!=(registration.partition()==Partition.SIMULATION)) return (ClaimInspection)new ClaimInspection.Unknown();
            var claims=jdbc.query("select claim_id,period_start,period_end,generation from workspace_admission_claim where registration_token=? and attempt_id=?",
                    (rs,n)->new ClaimFence(registration.token(),rs.getString(1),attemptId,registration.workspaceId(),registration.partition(),
                            rs.getDate(2).toLocalDate().atStartOfDay(ZONE).toInstant(),rs.getDate(3).toLocalDate().atStartOfDay(ZONE).toInstant(),rs.getLong(4)),registration.token(),attemptId);
            if (claims.size()==1 && a[4]!=null) {
                jdbc.update("update workspace_admission_dispatch set state='CLAIMED',resolved_at=current_timestamp where attempt_id=? and registration_token=? and state='PENDING'",attemptId,registration.token());
                return (ClaimInspection)new ClaimInspection.Found(claims.getFirst());
            }
            if (claims.isEmpty() && a[4]==null && "IN_PROGRESS".equals(a[5])) {
                jdbc.update("update workspace_admission_dispatch set state='DENIED',resolved_at=current_timestamp where attempt_id=? and registration_token=? and state='PENDING'",attemptId,registration.token());
                return (ClaimInspection)new ClaimInspection.Absent();
            }
            return (ClaimInspection)new ClaimInspection.Unknown();
        }),new ClaimInspection.Unknown()).thenApply(result -> {
            if (!(result instanceof ClaimInspection.Unknown)) recoveryScopes.remove(scopeKey(registration.workspaceId(),registration.partition()));
            return result;
        }).exceptionally(ex -> new ClaimInspection.Unknown());
    }
    @Override public CompletionStage<FinalizationResult> finalizeAttempt(FinalizeAttempt command) {
        if (command==null || !valid(command.registration()) || blank(command.attemptId()) || command.kind()==null || !validTerminalReason(command))
            return CompletableFuture.completedFuture(FinalizationResult.RECOVERY_REQUIRED);
        var r=command.registration();
        // Intake is committed before reserving bounded completion delivery: a saturated
        // callback pool must not discard the only evidence of an accepted terminal result.
        try {
            if (!Boolean.TRUE.equals(transactions.execute(status -> persistFinalizationIntent(command))))
                return CompletableFuture.completedFuture(FinalizationResult.RECOVERY_REQUIRED);
        } catch (RuntimeException failure) {
            recoveryScopes.add(scopeKey(r.workspaceId(),r.partition()));
            return CompletableFuture.completedFuture(FinalizationResult.RECOVERY_REQUIRED);
        }
        return executeCritical(r.workspaceId(), () -> {
            return transactions.execute(status -> {
            if (state(r,true)==null) return FinalizationResult.RECOVERY_REQUIRED;
            var attempts=jdbc.query("select run_id,workspace_id,actor_id,simulation,started_at,execution_outcome from provider_attempt where id=? for update",
                    (rs,n)->new Object[]{rs.getString(1),rs.getString(2),rs.getString(3),rs.getBoolean(4),rs.getTimestamp(5),rs.getString(6)},command.attemptId());
            if (attempts.isEmpty()) return FinalizationResult.RECOVERY_REQUIRED;
            var attempt=attempts.getFirst();
            if (!r.runId().equals(attempt[0]) || !r.workspaceId().equals(attempt[1]) || !r.actorId().equals(attempt[2])
                    || ((boolean)attempt[3])!=(r.partition()==Partition.SIMULATION)) return FinalizationResult.RECOVERY_REQUIRED;
            var claimRows=jdbc.query("select claim_id,period_start,period_end,generation from workspace_admission_claim where registration_token=? and attempt_id=? for update",
                    (rs,n)->new ClaimFence(r.token(),rs.getString(1),command.attemptId(),r.workspaceId(),r.partition(),
                            rs.getDate(2).toLocalDate().atStartOfDay(ZONE).toInstant(),rs.getDate(3).toLocalDate().atStartOfDay(ZONE).toInstant(),rs.getLong(4)),r.token(),command.attemptId());
            if (command.kind()==TerminalKind.NOT_DISPATCHED) {
                if (!claimRows.isEmpty() || attempt[4]!=null || command.claim()!=null || dispatchPending(command.attemptId(),r.token())) return FinalizationResult.RECOVERY_REQUIRED;
            } else {
                if (claimRows.size()!=1 || attempt[4]==null || command.claim()==null || !claimRows.getFirst().equals(command.claim()))
                    return FinalizationResult.RECOVERY_REQUIRED;
            }
            String current=(String)attempt[5], desired=command.kind().name();
            if (!"IN_PROGRESS".equals(current)) {
                if (!current.equals(desired)) return FinalizationResult.RECOVERY_REQUIRED;
                if (!claimRows.isEmpty()) projection.recompute(r.workspaceId(),r.partition(),claimRows.getFirst().periodStart(),claimRows.getFirst().periodEnd());
                markFinalizationSettled(command.attemptId());
                return FinalizationResult.SETTLED;
            }
            int updated=jdbc.update("""
                    update provider_attempt set execution_outcome=?,finished_at=?,projection_version=projection_version+1,
                        execution_reason=coalesce(?,case when ?='UNKNOWN' then 'ADMISSION_RECOVERY' else execution_reason end),
                        usage_presence=case when ?='UNKNOWN' or usage_presence='PENDING' then 'UNKNOWN' else usage_presence end,
                        projection_state=case when ?='UNKNOWN' then 'UNKNOWN' else projection_state end,
                        projection_error=case when ?='UNKNOWN' then coalesce(?,'ADMISSION_RECOVERY') else projection_error end
                    where id=? and execution_outcome='IN_PROGRESS'
                    """,desired,java.sql.Timestamp.from(clock.instant()),command.reason()==null?null:command.reason().name(),desired,desired,desired,desired,command.reason()==null?null:command.reason().name(),command.attemptId());
            if (updated!=1) throw new IllegalStateException("attempt finalization conflict");
            if (!claimRows.isEmpty()) {
                jdbc.update("update workspace_admission_claim set status=?,updated_at=current_timestamp where claim_id=?",
                        command.kind()==TerminalKind.UNKNOWN?"UNKNOWN":"TERMINAL",claimRows.getFirst().claimId());
                projection.recompute(r.workspaceId(),r.partition(),claimRows.getFirst().periodStart(),claimRows.getFirst().periodEnd());
            }
            markFinalizationSettled(command.attemptId());
            return FinalizationResult.SETTLED;
            });
        },FinalizationResult.RECOVERY_REQUIRED).handle((result, ex) -> {
            if (ex != null || result == FinalizationResult.RECOVERY_REQUIRED) {
                // A forged token must not put another workspace into process-local recovery.
                // If the database is unavailable, preserve fail-closed behavior for uncertainty.
                boolean known;
                try { known=Boolean.TRUE.equals(jdbc.queryForObject("""
                        select exists(select 1 from workspace_admission_registration where token=? and workspace_id=?
                          and run_id=? and actor_id=? and conversation_id=? and partition=?)
                        """,Boolean.class,r.token(),r.workspaceId(),r.runId(),r.actorId(),r.conversationId(),r.partition().name())); }
                catch (RuntimeException unavailable) { known=true; }
                if (known) recoveryScopes.add(scopeKey(r.workspaceId(),r.partition()));
                return FinalizationResult.RECOVERY_REQUIRED;
            }
            return result;
        });
    }

    @Override public CompletionStage<FinalizationResult> recoverFinalization(Registration registration, String attemptId) {
        if (!valid(registration) || blank(attemptId)) return CompletableFuture.completedFuture(FinalizationResult.RECOVERY_REQUIRED);
        return execute(registration.workspaceId(), () -> transactions.execute(status -> {
            if (state(registration,true)==null) return (FinalizeAttempt)null;
            var rows=jdbc.query("select claim_id,terminal_kind,terminal_reason from workspace_admission_finalization where registration_token=? and attempt_id=?",
                    (rs,n)->new String[]{rs.getString(1),rs.getString(2),rs.getString(3)},registration.token(),attemptId);
            if (rows.isEmpty()) return (FinalizeAttempt)null;
            String claimId=rows.getFirst()[0]; ClaimFence claim=null;
            if (claimId!=null) {
                var found=jdbc.query("select period_start,period_end,generation from workspace_admission_claim where claim_id=? and registration_token=? and attempt_id=?",
                        (rs,n)->new ClaimFence(registration.token(),claimId,attemptId,registration.workspaceId(),registration.partition(),
                                rs.getDate(1).toLocalDate().atStartOfDay(ZONE).toInstant(),rs.getDate(2).toLocalDate().atStartOfDay(ZONE).toInstant(),rs.getLong(3)),
                        claimId,registration.token(),attemptId);
                if (found.isEmpty()) return (FinalizeAttempt)null;
                claim=found.getFirst();
            }
            return new FinalizeAttempt(registration,attemptId,claim,TerminalKind.valueOf(rows.getFirst()[1]),rows.getFirst()[2]==null?null:TerminalReason.valueOf(rows.getFirst()[2]));
        }),null).thenCompose(command -> command==null ? CompletableFuture.completedFuture(FinalizationResult.RECOVERY_REQUIRED)
                : finalizeAttempt(command)).thenApply(result -> {
            if (result==FinalizationResult.SETTLED) {
                Boolean pending=jdbc.queryForObject("""
                        select exists(select 1 from workspace_admission_finalization f
                            join workspace_admission_registration r on r.token=f.registration_token
                           where r.workspace_id=? and r.partition=? and f.state='PENDING')
                        """,Boolean.class,registration.workspaceId(),registration.partition().name());
                if (!Boolean.TRUE.equals(pending)) recoveryScopes.remove(scopeKey(registration.workspaceId(),registration.partition()));
            }
            return result;
        }).exceptionally(ex -> FinalizationResult.RECOVERY_REQUIRED);
    }

    private boolean persistFinalizationIntent(FinalizeAttempt command) {
        var r=command.registration();
        if (state(r,true)==null) return false;
        var rows=jdbc.query("select run_id,workspace_id,actor_id,simulation,started_at from provider_attempt where id=? for update",
                (rs,n)->new Object[]{rs.getString(1),rs.getString(2),rs.getString(3),rs.getBoolean(4),rs.getTimestamp(5)},command.attemptId());
        if (rows.isEmpty()) return false;
        var attempt=rows.getFirst();
        if (!r.runId().equals(attempt[0]) || !r.workspaceId().equals(attempt[1]) || !r.actorId().equals(attempt[2])
                || ((boolean)attempt[3])!=(r.partition()==Partition.SIMULATION)) return false;
        var claimRows=jdbc.query("select claim_id,period_start,period_end,generation from workspace_admission_claim where registration_token=? and attempt_id=? for update",
                (rs,n)->new ClaimFence(r.token(),rs.getString(1),command.attemptId(),r.workspaceId(),r.partition(),
                        rs.getDate(2).toLocalDate().atStartOfDay(ZONE).toInstant(),rs.getDate(3).toLocalDate().atStartOfDay(ZONE).toInstant(),rs.getLong(4)),r.token(),command.attemptId());
        if (command.kind()==TerminalKind.NOT_DISPATCHED) {
            if (!claimRows.isEmpty() || attempt[4]!=null || command.claim()!=null || dispatchPending(command.attemptId(),r.token())) return false;
        } else if (claimRows.size()!=1 || attempt[4]==null || !claimRows.getFirst().equals(command.claim())) return false;
        jdbc.update("""
                insert into workspace_admission_finalization(attempt_id,registration_token,claim_id,terminal_kind,terminal_reason,state)
                values (?,?,?,?,?,'PENDING') on conflict (attempt_id) do nothing
                """,command.attemptId(),r.token(),command.claim()==null?null:command.claim().claimId(),command.kind().name(),command.reason()==null?null:command.reason().name());
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from workspace_admission_finalization where attempt_id=? and registration_token=?
                 and claim_id is not distinct from ? and terminal_kind=? and terminal_reason is not distinct from ?)
                """,Boolean.class,command.attemptId(),r.token(),command.claim()==null?null:command.claim().claimId(),command.kind().name(),command.reason()==null?null:command.reason().name()));
    }

    private boolean validTerminalReason(FinalizeAttempt command) {
        if (command.reason()==null) return true;
        return switch (command.reason()) {
            case CAPTURE_FAILED, ADMISSION_RECOVERY -> command.kind()==TerminalKind.UNKNOWN;
            case PROMPT_CONTEXT_UNAVAILABLE, WORKSPACE_ACCESS_DENIED, RUN_TERMINATED_BEFORE_DISPATCH, BUDGET_CANCELLATION_SUPERSEDED -> command.kind()==TerminalKind.NOT_DISPATCHED;
            case PROVIDER_STREAM_ERROR -> command.kind()==TerminalKind.FAILED;
        };
    }
    private static RecoveryScope scopeKey(String workspaceId, Partition partition) { return new RecoveryScope(workspaceId, partition); }
    boolean scopeRequiresRecovery(String workspaceId, Partition partition) {
        return !startupReady.get() || recoveryScopes.contains(scopeKey(workspaceId,partition)) || hasPendingRecovery(workspaceId,partition);
    }
    private boolean dispatchPending(String attemptId,String token) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from workspace_admission_dispatch where attempt_id=? and registration_token=? and state='PENDING')",Boolean.class,attemptId,token));
    }
    private boolean hasPendingRecovery(String workspaceId, Partition partition) {
        if (hasPendingFinalization(workspaceId,partition)) return true;
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from workspace_admission_dispatch d join workspace_admission_registration r
                    on r.token=d.registration_token where r.workspace_id=? and r.partition=? and d.state='PENDING')
                """,Boolean.class,workspaceId,partition.name()));
    }
    /** Called only on the workspace lane within a transaction, after the registration lock. */
    private Decision pendingBudgetCancellation(Dispatch command, boolean ownDispatchPending) {
        var r=command.registration();
        if (!"CANCEL_REQUESTED".equals(state(r,true)) || !authorized(r.workspaceId(),r.conversationId(),r.actorId()))
            return new Decision.Denied(Reason.RECOVERY_REQUIRED);
        var attempts=jdbc.query("select run_id,workspace_id,actor_id,simulation,started_at,execution_outcome from provider_attempt where id=? for update",
                (rs,n)->new Object[]{rs.getString(1),rs.getString(2),rs.getString(3),rs.getBoolean(4),rs.getTimestamp(5),rs.getString(6)},command.attemptId());
        if (attempts.size()!=1) return new Decision.Denied(Reason.RECOVERY_REQUIRED);
        var a=attempts.getFirst();
        if (!r.runId().equals(a[0]) || !r.workspaceId().equals(a[1]) || !r.actorId().equals(a[2])
                || ((boolean)a[3])!=(r.partition()==Partition.SIMULATION) || a[4]!=null || !"IN_PROGRESS".equals(a[5]))
            return new Decision.Denied(Reason.RECOVERY_REQUIRED);
        Boolean claimExists=jdbc.queryForObject("select exists(select 1 from workspace_admission_claim where registration_token=? and attempt_id=?)",Boolean.class,r.token(),command.attemptId());
        var dispatches=jdbc.query("select attempt_id,state from workspace_admission_dispatch where registration_token=? and (state='PENDING' or attempt_id=?)",
                (rs,n)->new String[]{rs.getString(1),rs.getString(2)},r.token(),command.attemptId());
        if (!Boolean.FALSE.equals(claimExists) || (ownDispatchPending
                ? dispatches.size()!=1 || !command.attemptId().equals(dispatches.getFirst()[0]) || !"PENDING".equals(dispatches.getFirst()[1])
                : !dispatches.isEmpty())) return new Decision.Denied(Reason.RECOVERY_REQUIRED);
        var fences=pending(r);
        if (fences.size()!=1) return new Decision.Denied(Reason.RECOVERY_REQUIRED);
        var fence=fences.getFirst();
        var effects=jdbc.query("""
                select effect_id,target_run_id,target_workspace_id,target_partition,pending_id,pending_epoch,period_start,period_end,intent_receipt,delivery_status
                  from workspace_admission_effect_outbox where registration_token=? and delivery_status='PENDING'
                """,(rs,n)->new Object[]{rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getLong(6),rs.getDate(7),rs.getDate(8),rs.getString(9),rs.getString(10)},r.token());
        // Absence of a pending budget effect alone does not prove a user cancellation.
        if (effects.isEmpty()) {
            Boolean userCancelled=jdbc.queryForObject("select user_cancel_requested_at is not null from workspace_admission_registration where token=?",Boolean.class,r.token());
            if (!Boolean.TRUE.equals(userCancelled)) return new Decision.Denied(Reason.RECOVERY_REQUIRED);
            return ownDispatchPending ? resolveDenied(command,Reason.CANCELLED) : new Decision.Denied(Reason.CANCELLED);
        }
        if (effects.size()!=1) return new Decision.Denied(Reason.RECOVERY_REQUIRED);
        var e=effects.getFirst();
        if (!r.runId().equals(e[1]) || !r.workspaceId().equals(e[2]) || !r.partition().name().equals(e[3])
                || !fence.pendingId().equals(e[4]) || fence.epoch()!=((Number)e[5]).longValue()
                || !fence.periodStart().equals(((java.sql.Date)e[6]).toLocalDate().atStartOfDay(ZONE).toInstant())
                || !fence.periodEnd().equals(((java.sql.Date)e[7]).toLocalDate().atStartOfDay(ZONE).toInstant())
                || blank((String)e[0]) || blank((String)e[8])) return new Decision.Denied(Reason.RECOVERY_REQUIRED);
        if (ownDispatchPending && jdbc.update("update workspace_admission_dispatch set state='DENIED',resolved_at=current_timestamp where attempt_id=? and registration_token=? and state='PENDING'",command.attemptId(),r.token())!=1)
            throw new IllegalStateException("Dispatch decision changed");
        return new Decision.BudgetCancellationPending(new BudgetCancellationSink.Effect((String)e[0],r.token(),r.workspaceId(),r.partition(),r.runId(),new BudgetCancellationSink.Target.Pending(fence),(String)e[8]));
    }

    private Reason persistDispatchIntent(Dispatch command) {
        var r=command.registration();
        String state=state(r,true);
        if (state==null || !authorized(r.workspaceId(),r.conversationId(),r.actorId())) return Reason.UNAUTHORIZED;
        if (superseded(r.token())) return Reason.RECOVERY_REQUIRED;
        if ("CANCEL_REQUESTED".equals(state)) return Reason.CANCELLED;
        if (!"PUBLISHED".equals(state)) return Reason.RECOVERY_REQUIRED;
        var attempts=jdbc.query("select run_id,workspace_id,actor_id,simulation,started_at,execution_outcome from provider_attempt where id=? for update",
                (rs,n)->new Object[]{rs.getString(1),rs.getString(2),rs.getString(3),rs.getBoolean(4),rs.getTimestamp(5),rs.getString(6)},command.attemptId());
        if (attempts.isEmpty()) return Reason.UNAUTHORIZED;
        var a=attempts.getFirst();
        if (!r.runId().equals(a[0]) || !r.workspaceId().equals(a[1]) || !r.actorId().equals(a[2])
                || ((boolean)a[3])!=(r.partition()==Partition.SIMULATION)) return Reason.UNAUTHORIZED;
        if (a[4]!=null || !"IN_PROGRESS".equals(a[5])) return Reason.RECOVERY_REQUIRED;
        return jdbc.update("insert into workspace_admission_dispatch(attempt_id,registration_token,state) values (?,?,'PENDING') on conflict do nothing",command.attemptId(),r.token())==1 ? null : Reason.RECOVERY_REQUIRED;
    }
    private Decision resolveDenied(Dispatch command,Reason reason) {
        jdbc.update("update workspace_admission_dispatch set state='DENIED',resolved_at=current_timestamp where attempt_id=? and registration_token=? and state='PENDING'",command.attemptId(),command.registration().token());
        return new Decision.Denied(reason);
    }
    private boolean hasPendingFinalization(String workspaceId, Partition partition) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from workspace_admission_finalization f
                   join workspace_admission_registration r on r.token=f.registration_token
                  where r.workspace_id=? and r.partition=? and f.state='PENDING')
                """,Boolean.class,workspaceId,partition.name()));
    }
    private void markFinalizationSettled(String attemptId) {
        jdbc.update("update workspace_admission_finalization set state='SETTLED',settled_at=coalesce(settled_at,current_timestamp) where attempt_id=?",attemptId);
    }

    @Override public CompletionStage<ProviderStartResult> authorizeProviderStart(ClaimFence claim) {
        if (!startupReady.get()) return CompletableFuture.completedFuture(new ProviderStartResult.Denied(Reason.RECOVERY_REQUIRED));
        if (claim==null || blank(claim.workspaceId()) || blank(claim.token()) || blank(claim.claimId())
                || blank(claim.attemptId()) || claim.partition()==null || claim.periodStart()==null || claim.periodEnd()==null
                || !claim.periodStart().equals(claim.periodStart().atZone(ZONE).toLocalDate().atStartOfDay(ZONE).toInstant())
                || !claim.periodEnd().equals(claim.periodEnd().atZone(ZONE).toLocalDate().atStartOfDay(ZONE).toInstant()))
            return CompletableFuture.completedFuture(new ProviderStartResult.Denied(Reason.UNAUTHORIZED));
        return execute(claim.workspaceId(), () -> transactions.execute(status -> {
            if (recoveryScopes.contains(scopeKey(claim.workspaceId(),claim.partition())) || hasPendingRecovery(claim.workspaceId(),claim.partition())) return new ProviderStartResult.Denied(Reason.RECOVERY_REQUIRED);
            var rows=jdbc.query("""
                    select r.run_id,r.actor_id,r.conversation_id,r.partition,r.status
                    from workspace_admission_registration r join workspace_admission_claim c on c.registration_token=r.token
                    where r.token=? and r.workspace_id=? and c.claim_id=? and c.attempt_id=? and r.partition=?
                      and c.period_start=? and c.period_end=? and c.generation=? and c.status in ('CLAIMED','CANCEL_REQUESTED')
                    for update of r,c
                    """,(rs,n) -> new String[]{rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5)},
                    claim.token(),claim.workspaceId(),claim.claimId(),claim.attemptId(),claim.partition().name(),
                    claim.periodStart().atZone(ZONE).toLocalDate(),claim.periodEnd().atZone(ZONE).toLocalDate(),claim.policyGeneration());
            if (rows.isEmpty()) return new ProviderStartResult.Denied(Reason.RECOVERY_REQUIRED);
            var row=rows.getFirst(); var r=new Registration(claim.token(),claim.workspaceId(),row[0],row[1],row[2],Partition.valueOf(row[3]));
            if (superseded(r.token())) return new ProviderStartResult.Denied(Reason.RECOVERY_REQUIRED);
            if (!authorized(r.workspaceId(),r.conversationId(),r.actorId())) return new ProviderStartResult.Denied(Reason.UNAUTHORIZED);
            if ("CANCEL_REQUESTED".equals(row[4])) return new ProviderStartResult.CancelPending(new CancelResult(CancelStatus.AFTER_CLAIM,claims(r),pending(r)));
            if (!"PUBLISHED".equals(row[4])) return new ProviderStartResult.Denied(Reason.RECOVERY_REQUIRED);
            return new ProviderStartResult.Ready();
        }),new ProviderStartResult.Denied(Reason.OVERLOADED));
    }
    private boolean superseded(String token) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from workspace_admission_superseded where registration_token=?)",Boolean.class,token));
    }
    @Override public CompletionStage<EffectInspection> inspectCancellationEffect(String effectId) {
        if (blank(effectId)) return CompletableFuture.completedFuture(new EffectInspection.Unknown());
        return CompletableFuture.supplyAsync(() -> jdbc.query("""
                select r.workspace_id from workspace_admission_effect_outbox o
                join workspace_admission_registration r on r.token=o.registration_token where o.effect_id=?
                """,(rs,n)->rs.getString(1),effectId)).thenCompose(scopes -> {
            if (scopes.isEmpty()) return CompletableFuture.completedFuture(new EffectInspection.Absent());
            return execute(scopes.getFirst(), () -> transactions.execute(status -> inspectEffect(effectId)),new EffectInspection.Unknown());
        });
    }

    private EffectInspection inspectEffect(String effectId) {
        var rows=jdbc.query("""
                select registration_token,target_workspace_id,target_partition,claim_id,pending_epoch,
                       period_start,period_end,generation,delivery_status,intent_receipt,ack_receipt,
                       target_attempt_id,pending_id
                  from workspace_admission_effect_outbox where effect_id=?
                """, (rs,n) -> {
            String token=rs.getString(1), workspace=rs.getString(2);
            var partition=Partition.valueOf(rs.getString(3));
            var start=rs.getDate(6).toLocalDate().atStartOfDay(ZONE).toInstant();
            var end=rs.getDate(7).toLocalDate().atStartOfDay(ZONE).toInstant();
            long generation=rs.getLong(8);
            BudgetCancellationSink.Target target = rs.getString(4)!=null
                    ? new BudgetCancellationSink.Target.Claimed(new ClaimFence(token,rs.getString(4),rs.getString(12),workspace,partition,start,end,generation))
                    : new BudgetCancellationSink.Target.Pending(new PendingFence(token,rs.getString(13),workspace,partition,start,end,rs.getLong(5)));
            EffectStatus effectStatus=switch(rs.getString(9)) {
                case "ACKNOWLEDGED" -> EffectStatus.ACKNOWLEDGED;
                case "STALE" -> EffectStatus.STALE;
                default -> EffectStatus.PENDING;
            };
            return (EffectInspection)new EffectInspection.Found(new EffectView(effectId,token,workspace,partition,target,effectStatus,
                    rs.getString(10),rs.getString(11)));
        },effectId);
        return rows.isEmpty() ? new EffectInspection.Absent() : rows.getFirst();
    }
    @Override public CompletionStage<EffectAcceptance> prepareCancellationEffect(BudgetCancellationSink.Effect effect) {
        if (effect==null || blank(effect.effectId()) || blank(effect.workspaceId()) || blank(effect.token())
                || blank(effect.runId()) || blank(effect.intentReceipt()) || effect.partition()==null || effect.target()==null)
            return CompletableFuture.completedFuture(new EffectAcceptance.Retry());
        return execute(effect.workspaceId(), () -> transactions.execute(status -> prepareEffectInTransaction(effect)),new EffectAcceptance.Retry());
    }

    EffectAcceptance prepareEffectInTransaction(BudgetCancellationSink.Effect effect) {
            var inspected=inspectEffect(effect.effectId());
            if (!(inspected instanceof EffectInspection.Found found)) return new EffectAcceptance.Retry();
            var saved=found.effect();
            Boolean runMatches=jdbc.queryForObject("select exists(select 1 from workspace_admission_effect_outbox where effect_id=? and target_run_id=?)",Boolean.class,effect.effectId(),effect.runId());
            if (!Boolean.TRUE.equals(runMatches)) return new EffectAcceptance.Retry();
            if (!effect.token().equals(saved.token()) || !effect.workspaceId().equals(saved.workspaceId())
                    || effect.partition()!=saved.partition() || !effect.target().equals(saved.target())
                    || !effect.intentReceipt().equals(saved.intentReceipt())) return new EffectAcceptance.Retry();
            var rows=jdbc.query("select run_id,status,actor_id,conversation_id from workspace_admission_registration where token=? for update",
                    (rs,n)->new String[]{rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4)},effect.token());
            if (rows.isEmpty() || !effect.runId().equals(rows.getFirst()[0])) return new EffectAcceptance.Retry();
            if ("TERMINAL".equals(rows.getFirst()[1])) return new EffectAcceptance.Stale();
            if (effect.target() instanceof BudgetCancellationSink.Target.Pending pendingTarget) {
                var fence=pendingTarget.fence();
                var current=pending(new Registration(effect.token(),effect.workspaceId(),effect.runId(),
                        rows.getFirst()[2],rows.getFirst()[3],effect.partition()));
                if (current.isEmpty() || !current.getFirst().equals(fence)) {
                    Boolean oldClaim=jdbc.queryForObject("""
                            select exists(select 1 from workspace_admission_claim where registration_token=?
                              and period_start=? and status in ('CLAIMED','CANCEL_REQUESTED','UNKNOWN'))
                            """,Boolean.class,effect.token(),fence.periodStart().atZone(ZONE).toLocalDate());
                    // Without an immutable pending-to-claim link, a live old-period claim is uncertain.
                    return Boolean.TRUE.equals(oldClaim) ? new EffectAcceptance.Retry() : new EffectAcceptance.Stale();
                }
            }
            if (effect.target() instanceof BudgetCancellationSink.Target.Claimed claimed) {
                var fence=claimed.fence();
                var active=jdbc.query("select status from workspace_admission_claim where claim_id=? and registration_token=? for update",
                        (rs,n)->rs.getString(1),fence.claimId(),effect.token());
                if (active.isEmpty()) return new EffectAcceptance.Retry();
                if ("TERMINAL".equals(active.getFirst())) return new EffectAcceptance.Stale();
                Boolean otherPeriod=jdbc.queryForObject("""
                        select exists(select 1 from workspace_admission_claim where registration_token=?
                          and status in ('CLAIMED','CANCEL_REQUESTED','UNKNOWN') and period_start<>?)
                        """,Boolean.class,effect.token(),fence.periodStart().atZone(ZONE).toLocalDate());
                // Run cancellation cannot safely target an old claim while a different period owns active work.
                if (Boolean.TRUE.equals(otherPeriod)) return new EffectAcceptance.Retry();
                jdbc.update("update workspace_admission_claim set status='CANCEL_REQUESTED',updated_at=current_timestamp where claim_id=? and status='CLAIMED'",fence.claimId());
            }
            jdbc.update("update workspace_admission_registration set status='CANCEL_REQUESTED',updated_at=current_timestamp where token=? and status<>'TERMINAL'",effect.token());
            return new EffectAcceptance.Prepared(effect);
    }
    @Override public CompletionStage<Void> abandon(Registration registration) {
        if (!valid(registration)) return CompletableFuture.failedFuture(new IllegalArgumentException("UNAUTHORIZED"));
        return execute(registration.workspaceId(), () -> transactions.execute(status -> {
            String value=state(registration,true);
            if (value==null) throw new IllegalStateException("RECOVERY_REQUIRED");
            var run=runStateReader.readForUpdate(registration.runId());
            if (run==null || run.status()!=RunAdmissionStateReader.Status.NOT_FOUND || !"REGISTERED".equals(value))
                throw new IllegalStateException("RECOVERY_REQUIRED");
            jdbc.update("update workspace_admission_registration set status='TERMINAL',updated_at=current_timestamp where token=?",registration.token());
            return (Void)null;
        }),null);
    }
    @Override public CompletionStage<Void> complete(Registration registration) {
        if (!valid(registration)) return CompletableFuture.failedFuture(new IllegalArgumentException("UNAUTHORIZED"));
        return execute(registration.workspaceId(), () -> transactions.execute(status -> {
            String value=state(registration,true);
            if (value==null || recoveryScopes.contains(scopeKey(registration.workspaceId(),registration.partition())) || hasPendingRecovery(registration.workspaceId(),registration.partition())) throw new IllegalStateException("RECOVERY_REQUIRED");
            var run=runStateReader.readForUpdate(registration.runId());
            if (run==null || (run.status()!=RunAdmissionStateReader.Status.TERMINAL && run.status()!=RunAdmissionStateReader.Status.COMPLETED)
                    || !registration.conversationId().equals(run.conversationId()))
                throw new IllegalStateException("RECOVERY_REQUIRED");
            Boolean open=jdbc.queryForObject("select exists(select 1 from provider_attempt where run_id=? and workspace_id=? and execution_outcome='IN_PROGRESS')",Boolean.class,registration.runId(),registration.workspaceId());
            if (Boolean.TRUE.equals(open)) throw new IllegalStateException("RECOVERY_REQUIRED");
            jdbc.update("update workspace_admission_claim set status='TERMINAL',updated_at=current_timestamp where registration_token=? and status <> 'UNKNOWN'",registration.token());
            jdbc.update("update workspace_admission_registration set status='TERMINAL',updated_at=current_timestamp where token=?",registration.token());
            return (Void)null;
        }),null);
    }

    @Override public CompletionStage<TerminalRunCompletion> completeTerminalRun(String runId) {
        if (blank(runId)) return CompletableFuture.completedFuture(TerminalRunCompletion.NOT_FOUND);
        final List<Registration> registrations;
        try {
            registrations = jdbc.query(connection -> {
                var statement=connection.prepareStatement("select token,workspace_id,run_id,actor_id,conversation_id,partition from workspace_admission_registration where run_id=?");
                statement.setString(1,runId);
                // This lookup happens before the lane transaction and must not inherit an unbounded statement.
                statement.setQueryTimeout(2);
                return statement;
            }, (rs,n) -> new Registration(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),Partition.valueOf(rs.getString(6))));
        } catch (RuntimeException failure) {
            return CompletableFuture.completedFuture(TerminalRunCompletion.UNKNOWN);
        }
        if (registrations.isEmpty()) return CompletableFuture.completedFuture(TerminalRunCompletion.NOT_FOUND);
        var registration=registrations.getFirst();
        return execute(registration.workspaceId(), () -> transactions.execute(status -> {
            // Recovery must not hold a workspace lane indefinitely behind another transaction.
            jdbc.execute("set local lock_timeout = '2s'");
            jdbc.execute("set local statement_timeout = '5s'");
            String current=state(registration,true);
            if (current==null) return TerminalRunCompletion.UNKNOWN;
            if ("TERMINAL".equals(current)) return TerminalRunCompletion.ALREADY_COMPLETED;
            var run=runStateReader.readForUpdate(runId);
            if (run==null || run.status()==null) return TerminalRunCompletion.UNKNOWN;
            if ((run.status()!=RunAdmissionStateReader.Status.TERMINAL && run.status()!=RunAdmissionStateReader.Status.COMPLETED) || !registration.conversationId().equals(run.conversationId()))
                return TerminalRunCompletion.RETRY;
            if (recoveryScopes.contains(scopeKey(registration.workspaceId(),registration.partition())) || hasPendingRecovery(registration.workspaceId(),registration.partition()))
                return TerminalRunCompletion.RETRY;
            Boolean open=jdbc.queryForObject("select exists(select 1 from provider_attempt where run_id=? and workspace_id=? and execution_outcome='IN_PROGRESS')",Boolean.class,runId,registration.workspaceId());
            if (Boolean.TRUE.equals(open)) return TerminalRunCompletion.RETRY;
            jdbc.update("update workspace_admission_claim set status='TERMINAL',updated_at=current_timestamp where registration_token=? and status <> 'UNKNOWN'",registration.token());
            jdbc.update("update workspace_admission_registration set status='TERMINAL',updated_at=current_timestamp where token=?",registration.token());
            return TerminalRunCompletion.COMPLETED;
        }),TerminalRunCompletion.RETRY).exceptionally(failure -> TerminalRunCompletion.UNKNOWN);
    }

    @Override public CompletionStage<RecoveryReadiness> verifyAndOpen() {
        if (startupReady.get()) return CompletableFuture.completedFuture(RecoveryReadiness.READY);
        if (!startupState.rebuilt()) return CompletableFuture.completedFuture(RecoveryReadiness.RETRY);
        try {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    if (unresolvedStartupFacts()) return RecoveryReadiness.RETRY;
                    var period=YearMonth.from(clock.instant().atZone(ZONE));
                    String afterWorkspace="",afterPartition="";
                    for (;;) {
                        var scopes=startupJdbc.query("""
                                select workspace_id,partition from (
                                    select workspace_id,partition from workspace_monthly_budget
                                    union select workspace_id,case when simulation then 'SIMULATION' else 'REAL' end from provider_attempt
                                ) scopes where (workspace_id,partition)>(?,?) order by workspace_id,partition limit 100
                                """,(rs,n)->new Object[]{rs.getString(1),Partition.valueOf(rs.getString(2))},afterWorkspace,afterPartition);
                        if (scopes.isEmpty()) break;
                        for (var scope:scopes) {
                        String workspace=(String)scope[0]; Partition partition=(Partition)scope[1];
                        lane.submitCritical(workspace,()->transactions.execute(status -> {
                            jdbc.execute("set local lock_timeout = '2s'");
                            jdbc.execute("set local statement_timeout = '5s'");
                            projection.recompute(workspace,partition,period.atDay(1).atStartOfDay(ZONE).toInstant(),
                                    period.plusMonths(1).atDay(1).atStartOfDay(ZONE).toInstant());
                            return null;
                        })).toCompletableFuture().get(10,java.util.concurrent.TimeUnit.SECONDS);
                        afterWorkspace=workspace; afterPartition=partition.name();
                        }
                    }
                    if (unresolvedStartupFacts()) return RecoveryReadiness.RETRY;
                    // A scan of the previous Taipei month cannot authorize the new month's gate.
                    if (!period.equals(YearMonth.from(clock.instant().atZone(ZONE)))) return RecoveryReadiness.RETRY;
                    startupReady.set(true);
                    return RecoveryReadiness.READY;
                } catch (Exception failure) {
                    if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
                    return RecoveryReadiness.FAILED;
                }
            },startupVerifier).minimalCompletionStage();
        } catch (RejectedExecutionException overloaded) {
            return CompletableFuture.completedFuture(RecoveryReadiness.RETRY);
        }
    }

    private boolean unresolvedStartupFacts() {
        if (!recoveryScopes.isEmpty()) return true;
        return Boolean.TRUE.equals(startupJdbc.queryForObject("""
                select exists(select 1 from workspace_admission_finalization where state='PENDING')
                    or exists(select 1 from workspace_admission_dispatch where state='PENDING')
                    or exists(select 1 from workspace_admission_registration where status<>'TERMINAL')
                    or exists(select 1 from workspace_admission_claim where status in ('CLAIMED','CANCEL_REQUESTED'))
                    or exists(select 1 from provider_attempt where execution_outcome='IN_PROGRESS')
                """,Boolean.class));
    }

    @jakarta.annotation.PreDestroy
    void stopStartupVerifier() { startupVerifier.shutdown(); }
}
