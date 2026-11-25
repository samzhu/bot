package io.github.samzhu.bot.usage.internal.admission;

import io.github.samzhu.bot.usage.ConversationTitleAdmission;
import io.github.samzhu.bot.usage.WorkspaceAdmission;
import java.time.Clock;
import java.time.YearMonth;
import java.time.ZoneId;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
class ConversationTitleAdmissionImpl implements ConversationTitleAdmission {
    private static final ZoneId ZONE = ZoneId.of("Asia/Taipei");
    private final JdbcTemplate jdbc;
    private final JdbcTemplate recoveryJdbc;
    private final TransactionTemplate transactions;
    private final WorkspaceLane lanes;
    private final WeeklyBudgetQuery weekly;
    private final BudgetProjection projection;
    private final Clock clock;
    private final io.github.samzhu.bot.usage.UsageLedger ledger;
    private final io.github.samzhu.bot.usage.internal.pricing.PricingPublisher pricing;
    private final io.github.samzhu.bot.usage.ConversationTitlePartition partition;
    private final io.github.samzhu.bot.usage.internal.pricing.CatalogActivation catalog;
    private final com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();

    ConversationTitleAdmissionImpl(JdbcTemplate jdbc, TransactionTemplate transactions, WorkspaceLane lanes,
            WeeklyBudgetQuery weekly, BudgetProjection projection, Clock clock, io.github.samzhu.bot.usage.ConversationTitlePartition partition, io.github.samzhu.bot.usage.UsageLedger ledger,
            io.github.samzhu.bot.usage.internal.pricing.PricingPublisher pricing,
            io.github.samzhu.bot.usage.internal.pricing.CatalogActivation catalog) {
        this.jdbc = jdbc; this.recoveryJdbc=new JdbcTemplate(java.util.Objects.requireNonNull(jdbc.getDataSource()));
        this.recoveryJdbc.setQueryTimeout(5); this.transactions = transactions; this.lanes = lanes;
        this.weekly = weekly; this.projection=projection; this.clock = clock; this.partition = partition; this.catalog = catalog; this.ledger = ledger; this.pricing = pricing;
    }

    @Override public Decision allow(String conversationId) {
        var scopes = jdbc.query("select workspace_id from conversation where id=?", (rs, n) -> rs.getString(1), conversationId);
        if (scopes.size() != 1) return Decision.DENIED;
        try {
            return lanes.submit(scopes.getFirst(), () -> transactions.execute(status -> {
                // Recovery and admission serialize on the title work row before budget locks.
                jdbc.query("select conversation_id from conversation_title_work where conversation_id=? for update",(rs,n)->rs.getString(1),conversationId);
                if (!hasLiveClaim(conversationId)) return Decision.DENIED;
                var rows = jdbc.query("""
                    select c.workspace_id,c.user_id from conversation c
                    join workspace_member m on m.workspace_id=c.workspace_id and m.user_id=c.user_id and m.active=true
                    join conversation_title_work w on w.conversation_id=c.id and w.state='CLAIMED'
                    where c.id=? and c.workspace_id=?
                    """, (rs,n) -> new String[]{rs.getString(1),rs.getString(2)}, conversationId, scopes.getFirst());
                if (rows.size() != 1) return Decision.DENIED;
                var actor = rows.getFirst()[1];
                var partition = this.partition.current();
                var now = clock.instant();
                var month = YearMonth.from(now.atZone(ZONE));
                jdbc.update("insert into workspace_monthly_budget(workspace_id,partition) values (?,?) on conflict do nothing", scopes.getFirst(), partition.name());
                jdbc.update("insert into workspace_monthly_budget_period(workspace_id,partition,period_start) values (?,?,?) on conflict do nothing", scopes.getFirst(), partition.name(), month.atDay(1));
                var budget = jdbc.queryForMap("""
                    select b.enabled,b.cap_usd,p.known_subtotal_usd,p.terminal_effective_unknown
                    from workspace_monthly_budget b join workspace_monthly_budget_period p using(workspace_id,partition)
                    where b.workspace_id=? and b.partition=? and p.period_start=? for update of b,p
                    """, scopes.getFirst(), partition.name(), month.atDay(1));
                if ((boolean) budget.get("enabled")) {
                    var cap = (java.math.BigDecimal) budget.get("cap_usd");
                    var spent = (java.math.BigDecimal) budget.get("known_subtotal_usd");
                    if (cap.signum() == 0 || (spent != null && spent.compareTo(cap) >= 0)) return Decision.DENIED_MONTH;
                    if ((boolean) budget.get("terminal_effective_unknown")) return Decision.DENIED_UNKNOWN;
                }
                var week = weekly.select(scopes.getFirst(), actor, partition, now);
                if (week.enabled()) {
                    if (week.capUsd().signum() == 0 || (week.knownSubtotalUsd() != null && week.knownSubtotalUsd().compareTo(week.capUsd()) >= 0)) return Decision.DENIED_WEEK;
                    if (week.terminalUnknownCount() > 0) return Decision.DENIED_UNKNOWN;
                }
                // Existing fake-only flows with no enabled budget retain their established behavior.
                if (!(boolean) budget.get("enabled") && !week.enabled() && partition == WorkspaceAdmission.Partition.SIMULATION)
                    return hasLiveClaim(conversationId) ? Decision.ALLOWED : Decision.DENIED;
                if (partition == WorkspaceAdmission.Partition.REAL && !(boolean) budget.get("enabled") && !week.enabled()) {
                    var selected = catalog.sample(clock, Clock::instant);
                    if (!hasLiveClaim(conversationId)) return Decision.DENIED;
                    createAttempt(scopes.getFirst(), actor, partition, now, selected == null ? null : selected.revisionId(), conversationId);
                    if (!hasLiveClaim(conversationId)) { status.setRollbackOnly(); return Decision.DENIED; }
                    return Decision.ALLOWED;
                }
                if (partition == WorkspaceAdmission.Partition.REAL && Boolean.TRUE.equals(jdbc.queryForObject("""
                    select exists(select 1 from provider_attempt where workspace_id=? and simulation=false
                    and run_id is not null and started_at is not null and execution_outcome='IN_PROGRESS')
                    """, Boolean.class, scopes.getFirst()))) return Decision.DEFERRED;
                var selected = catalog.sample(clock, Clock::instant);
                if (selected == null || selected.revisionId() == null) return Decision.DENIED_RATE;
                var rates = jdbc.query("""
                    select rates::text from pricing_catalog_entry where revision_id=? and provider='GOOGLE'
                    and endpoint='interactions' and model='gemini-3.5-flash-lite' and channel='GEMINI_API'
                    and effective_from <= ? and effective_until > ?
                    """, (rs,n) -> rs.getString(1), selected.revisionId(), java.sql.Timestamp.from(now), java.sql.Timestamp.from(now));
                if (rates.size() != 1) return Decision.DENIED_RATE;
                java.math.BigDecimal amount;
                try {
                    var rate = json.readTree(rates.getFirst());
                    var input = rate(rate, "input"); var cached = rate(rate, "cache_read");
                    var output = rate(rate, "output"); var thoughts = rate(rate, "thoughts");
                    var exact = input.max(cached).multiply(java.math.BigDecimal.valueOf(1_048_576))
                        .add(output.max(thoughts).multiply(java.math.BigDecimal.valueOf(64)))
                        .divide(java.math.BigDecimal.valueOf(1_000_000));
                    // Four independently rounded lines can each increase by at most half an ulp.
                    // Rates with <= 6 decimal places yield exact scale-12 lines for integer tokens.
                    boolean needsRoundingMargin = java.util.stream.Stream.of(input, cached, output, thoughts)
                        .anyMatch(r -> r.stripTrailingZeros().scale() > 6);
                    amount = exact.add(needsRoundingMargin ? new java.math.BigDecimal("0.000000000002") : java.math.BigDecimal.ZERO)
                        .setScale(12, java.math.RoundingMode.CEILING);
                    if (amount.compareTo(new java.math.BigDecimal("999999999999999999.999999999999")) > 0) return Decision.DENIED_RATE;
                } catch (Exception ex) { return Decision.DENIED_RATE; }
                var held = TitleReservationSettlement.monthRemaining(jdbc, scopes.getFirst(), partition.name(), month.atDay(1));
                if ((boolean) budget.get("enabled")) {
                    var spent = (java.math.BigDecimal) budget.get("known_subtotal_usd");
                    if ((spent == null ? java.math.BigDecimal.ZERO : spent).add(held).add(amount)
                        .compareTo((java.math.BigDecimal) budget.get("cap_usd")) > 0) return Decision.DENIED_MONTH;
                }
                if (week.enabled()) {
                    var weekHeld = TitleReservationSettlement.weekRemaining(jdbc, scopes.getFirst(), actor, partition.name(), week.start(), week.end());
                    var spent = week.knownSubtotalUsd() == null ? java.math.BigDecimal.ZERO : week.knownSubtotalUsd();
                    if (spent.add(weekHeld).add(amount).compareTo(week.capUsd()) > 0) return Decision.DENIED_WEEK;
                }
                if (!hasLiveClaim(conversationId)) return Decision.DENIED;
                String attempt = createAttempt(scopes.getFirst(), actor, partition, now, selected.revisionId(), conversationId);
                int inserted = jdbc.update("""
                    insert into workspace_title_reservation(conversation_id,workspace_id,actor_id,partition,reserved_at,month_start,amount_usd,catalog_revision_id,attempt_id,state)
                    values (?,?,?,?,?,?,?,?,?,'HELD') on conflict (conversation_id) do nothing
                    """, conversationId, scopes.getFirst(), actor, partition.name(), java.sql.Timestamp.from(now), month.atDay(1), amount, selected.revisionId(), attempt);
                if (inserted != 1 || !hasLiveClaim(conversationId)) {
                    status.setRollbackOnly();
                    return Decision.DENIED;
                }
                return Decision.ALLOWED;
            })).toCompletableFuture().join();
        } catch (RuntimeException ex) { return Decision.DENIED; }
    }

    private String createAttempt(String workspace, String actor, WorkspaceAdmission.Partition partition,
            java.time.Instant now, String revision, String conversationId) {
        String attempt = java.util.UUID.randomUUID().toString();
        jdbc.update("""
            insert into provider_attempt(id,run_id,workspace_id,actor_id,provider,endpoint,model,channel,adapter_version,
                simulation,attempt_coverage,prepared_at,started_at,execution_outcome,usage_presence,capture_completeness,projection_state,
                catalog_marker,catalog_revision_id,purpose,title_conversation_id)
            values (?,null,?,?,'GOOGLE','interactions','gemini-3.5-flash-lite','GEMINI_API','title-interactions-v1',
                ?,'VERIFIED',?,?,'IN_PROGRESS','PENDING','UNKNOWN','MISSING',?,?,'CONVERSATION_TITLE',?)
            """, attempt, workspace, actor, partition == WorkspaceAdmission.Partition.SIMULATION,
            java.sql.Timestamp.from(now), java.sql.Timestamp.from(now), revision == null ? "NO_CATALOG" : "CAPTURED", revision, conversationId);
        return attempt;
    }

    private boolean hasLiveClaim(String conversationId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from conversation_title_work
                  where conversation_id=? and state='CLAIMED' and claimed_until>clock_timestamp())
                """,Boolean.class,conversationId));
    }

    @Override public boolean capture(String conversationId, io.github.samzhu.bot.conversation.ConversationTitleGenerator.Result result) {
        var ids = jdbc.query("select id from provider_attempt where purpose='CONVERSATION_TITLE' and title_conversation_id=?", (rs,n) -> rs.getString(1), conversationId);
        if (ids.isEmpty()) return true; // Existing unbudgeted simulation Fake has no provider attempt.
        String id = ids.getFirst();
        try {
            var u = result.usage();
            boolean complete = u != null && u.input() != null && u.cached() != null && u.output() != null && u.thought() != null && u.tool() != null && u.total() != null
                    && u.input() >= 0 && u.cached() >= 0 && u.output() >= 0 && u.thought() >= 0 && u.tool() == 0 && u.total() >= 0
                    && u.cached() <= u.input() && u.input() <= 1_048_576 && (long)u.output()+u.thought() <= 64
                    && (long)u.input()+u.output()+u.thought() == u.total();
            var raw = json.createObjectNode();
            if (u != null) {
                if (u.input() != null) raw.put("total_input_tokens", u.input());
                if (u.cached() != null) raw.put("total_cached_tokens", u.cached());
                if (u.output() != null) raw.put("total_output_tokens", u.output());
                if (u.thought() != null) raw.put("total_thought_tokens", u.thought());
                if (u.tool() != null) raw.put("total_tool_use_tokens", u.tool());
                if (u.total() != null) raw.put("total_tokens", u.total());
            }
            ledger.append(id, new io.github.samzhu.bot.usage.UsageLedger.Snapshot("title-final", 1,
                complete ? "FINAL" : u == null ? "UNKNOWN" : "CUMULATIVE", "SDK_NATIVE",
                "interactions-v1", "title-interactions-v1", "1", complete ? "COMPLETE" : "INCOMPLETE",
                u == null ? null : json.writeValueAsString(raw)));
            if (result.responseId() != null && !result.responseId().isBlank()) ledger.recordResponseId(id, result.responseId());
            ledger.finish(id, "SUCCEEDED");
            // Pricing is recoverable from the durable attempt and must not discard a valid title.
            try { pricing.recompute(id); } catch (RuntimeException ignored) { return true; }
            return true;
        } catch (Exception ex) { unknown(conversationId); return false; }
    }

    @Override public void unknown(String conversationId) {
        settleUnknown(conversationId,false);
    }

    @Override public int recoverExpiredBatch() {
        var ids=recoveryJdbc.query("""
                select w.conversation_id from conversation_title_work w
                  join provider_attempt a on a.title_conversation_id=w.conversation_id and a.purpose='CONVERSATION_TITLE'
                  left join workspace_title_reservation h on h.conversation_id=w.conversation_id
                 where w.state in ('CLAIMED','FAILED') and w.claimed_until<=clock_timestamp()
                   and (h.conversation_id is null or (h.attempt_id=a.id and h.state='HELD'))
                   and a.execution_outcome='IN_PROGRESS'
                 order by w.conversation_id limit 100
                """,(rs,n)->rs.getString(1));
        int recovered=0;
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(250);
        for (String id:ids) {
            if (System.nanoTime()>=deadline) break;
            if (settleUnknown(id,true)) recovered++;
        }
        return recovered;
    }

    private boolean settleUnknown(String conversationId,boolean onlyExpired) {
        var scopes=jdbc.query("""
                select a.workspace_id,a.id,case when a.simulation then 'SIMULATION' else 'REAL' end
                from provider_attempt a where a.purpose='CONVERSATION_TITLE' and a.title_conversation_id=?
                """, (rs,n)->new String[]{rs.getString(1),rs.getString(2),rs.getString(3)},conversationId);
        if (scopes.size()!=1 || scopes.getFirst()[1]==null) return false;
        var scope=scopes.getFirst();
        try {
            return Boolean.TRUE.equals(lanes.submitCritical(scope[0], () -> transactions.execute(status -> {
                jdbc.execute("set local lock_timeout = '2s'");
                jdbc.execute("set local statement_timeout = '5s'");
                jdbc.query("select conversation_id from conversation_title_work where conversation_id=? for update",(rs,n)->rs.getString(1),conversationId);
                var work=jdbc.query("select state,claimed_until is not null and claimed_until<=clock_timestamp() from conversation_title_work where conversation_id=?",
                        (rs,n)->new Object[]{rs.getString(1),rs.getBoolean(2)},conversationId);
                if (work.size()!=1 || !("CLAIMED".equals(work.getFirst()[0]) || "FAILED".equals(work.getFirst()[0]))
                        || (onlyExpired && !Boolean.TRUE.equals(work.getFirst()[1]))) return false;
                var attempts=jdbc.query("""
                        select execution_outcome,simulation,started_at from provider_attempt
                         where id=? and workspace_id=? and purpose='CONVERSATION_TITLE' and title_conversation_id=? for update
                        """,(rs,n)->new Object[]{rs.getString(1),rs.getBoolean(2),rs.getTimestamp(3)},scope[1],scope[0],conversationId);
                if (attempts.size()!=1) return false;
                var attempt=attempts.getFirst();
                // A committed success and its projection remain authoritative even if the hold is still HELD.
                if (!("IN_PROGRESS".equals(attempt[0]) || "UNKNOWN".equals(attempt[0]))) return false;
                if ("IN_PROGRESS".equals(attempt[0])) {
                    jdbc.update("""
                            update provider_attempt set execution_outcome='UNKNOWN',usage_presence='UNKNOWN',projection_state='UNKNOWN',
                                projection_error='TITLE_RECOVERY',projection_version=projection_version+1,finished_at=clock_timestamp()
                             where id=? and execution_outcome='IN_PROGRESS'
                            """,scope[1]);
                    if (attempt[2]!=null) {
                        var period=YearMonth.from(((java.sql.Timestamp)attempt[2]).toInstant().atZone(ZONE));
                        projection.recompute(scope[0],WorkspaceAdmission.Partition.valueOf(scope[2]),
                                period.atDay(1).atStartOfDay(ZONE).toInstant(),period.plusMonths(1).atDay(1).atStartOfDay(ZONE).toInstant());
                    }
                }
                var holds=jdbc.query("select attempt_id,state from workspace_title_reservation where conversation_id=? for update",
                        (rs,n)->new String[]{rs.getString(1),rs.getString(2)},conversationId);
                if (!holds.isEmpty()) {
                    if (!scope[1].equals(holds.getFirst()[0]) || !"HELD".equals(holds.getFirst()[1])) { status.setRollbackOnly(); return false; }
                    int changed=jdbc.update("""
                            update workspace_title_reservation set state='UNKNOWN'
                             where conversation_id=? and workspace_id=? and attempt_id=? and partition=? and state='HELD'
                            """,conversationId,scope[0],scope[1],scope[2]);
                    if (changed!=1) { status.setRollbackOnly(); return false; }
                }
                jdbc.update("update conversation_title_work set state='FAILED' where conversation_id=? and state='CLAIMED'",conversationId);
                return true;
            })).toCompletableFuture().get(10,java.util.concurrent.TimeUnit.SECONDS));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Title recovery interrupted",interrupted);
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException failure) {
            throw new IllegalStateException("Title recovery outcome is unknown",failure);
        }
    }

    private static java.math.BigDecimal rate(com.fasterxml.jackson.databind.JsonNode node, String key) {
        var value = node.path(key);
        if (!value.isTextual() || !value.asText().matches("[0-9]+(?:\\.[0-9]+)?")) throw new IllegalArgumentException("Invalid rate");
        return new java.math.BigDecimal(value.asText());
    }
}
