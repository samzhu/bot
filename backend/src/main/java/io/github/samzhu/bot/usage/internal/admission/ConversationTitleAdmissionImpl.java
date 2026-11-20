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
    private final TransactionTemplate transactions;
    private final WorkspaceLane lanes;
    private final WeeklyBudgetQuery weekly;
    private final Clock clock;
    private final io.github.samzhu.bot.usage.UsageLedger ledger;
    private final io.github.samzhu.bot.usage.internal.pricing.PricingPublisher pricing;
    private final io.github.samzhu.bot.usage.ConversationTitlePartition partition;
    private final io.github.samzhu.bot.usage.internal.pricing.CatalogActivation catalog;
    private final com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();

    ConversationTitleAdmissionImpl(JdbcTemplate jdbc, TransactionTemplate transactions, WorkspaceLane lanes,
            WeeklyBudgetQuery weekly, Clock clock, io.github.samzhu.bot.usage.ConversationTitlePartition partition, io.github.samzhu.bot.usage.UsageLedger ledger,
            io.github.samzhu.bot.usage.internal.pricing.PricingPublisher pricing,
            io.github.samzhu.bot.usage.internal.pricing.CatalogActivation catalog) {
        this.jdbc = jdbc; this.transactions = transactions; this.lanes = lanes;
        this.weekly = weekly; this.clock = clock; this.partition = partition; this.catalog = catalog; this.ledger = ledger; this.pricing = pricing;
    }

    @Override public Decision allow(String conversationId) {
        var scopes = jdbc.query("select workspace_id from conversation where id=?", (rs, n) -> rs.getString(1), conversationId);
        if (scopes.size() != 1) return Decision.DENIED;
        try {
            return lanes.submit(scopes.getFirst(), () -> transactions.execute(status -> {
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
                    if (cap.signum() == 0 || (spent != null && spent.compareTo(cap) >= 0) || (boolean) budget.get("terminal_effective_unknown")) return Decision.DENIED;
                }
                var week = weekly.select(scopes.getFirst(), actor, partition, now);
                if (week.enabled() && (week.capUsd().signum() == 0 ||
                    (week.knownSubtotalUsd() != null && week.knownSubtotalUsd().compareTo(week.capUsd()) >= 0) || week.terminalUnknownCount() > 0)) return Decision.DENIED;
                // Existing fake-only flows with no enabled budget retain their established behavior.
                if (!(boolean) budget.get("enabled") && !week.enabled() && partition == WorkspaceAdmission.Partition.SIMULATION) return Decision.ALLOWED;
                if (partition == WorkspaceAdmission.Partition.REAL && (!(boolean) budget.get("enabled") || !week.enabled())) return Decision.DENIED;
                if (partition == WorkspaceAdmission.Partition.REAL && Boolean.TRUE.equals(jdbc.queryForObject("""
                    select exists(select 1 from provider_attempt where workspace_id=? and simulation=false
                    and run_id is not null and started_at is not null and execution_outcome='IN_PROGRESS')
                    """, Boolean.class, scopes.getFirst()))) return Decision.DEFERRED;
                var selected = catalog.sample(clock, Clock::instant);
                if (selected == null || selected.revisionId() == null) return Decision.DENIED;
                var rates = jdbc.query("""
                    select rates::text from pricing_catalog_entry where revision_id=? and provider='GOOGLE'
                    and endpoint='interactions' and model='gemini-3.5-flash-lite' and channel='GEMINI_API'
                    and effective_from <= ? and effective_until > ?
                    """, (rs,n) -> rs.getString(1), selected.revisionId(), java.sql.Timestamp.from(now), java.sql.Timestamp.from(now));
                if (rates.size() != 1) return Decision.DENIED;
                java.math.BigDecimal amount;
                try {
                    var rate = json.readTree(rates.getFirst());
                    var input = rate(rate, "input").max(rate(rate, "cache_read"));
                    var output = rate(rate, "output").max(rate(rate, "thoughts"));
                    amount = input.multiply(java.math.BigDecimal.valueOf(1_048_576))
                        .add(output.multiply(java.math.BigDecimal.valueOf(64)))
                        .divide(java.math.BigDecimal.valueOf(1_000_000), 12, java.math.RoundingMode.CEILING);
                } catch (Exception ex) { return Decision.DENIED; }
                var held = jdbc.queryForObject("""
                    select coalesce(sum(amount_usd),0) from workspace_title_reservation
                    where workspace_id=? and partition=? and month_start=? and state in ('HELD','UNKNOWN')
                    """, java.math.BigDecimal.class, scopes.getFirst(), partition.name(), month.atDay(1));
                if ((boolean) budget.get("enabled")) {
                    var spent = (java.math.BigDecimal) budget.get("known_subtotal_usd");
                    if ((spent == null ? java.math.BigDecimal.ZERO : spent).add(held).add(amount)
                        .compareTo((java.math.BigDecimal) budget.get("cap_usd")) > 0) return Decision.DENIED;
                }
                if (week.enabled()) {
                    var weekHeld = jdbc.queryForObject("""
                        select coalesce(sum(amount_usd),0) from workspace_title_reservation
                        where workspace_id=? and actor_id=? and partition=? and reserved_at>=? and reserved_at<? and state in ('HELD','UNKNOWN')
                        """, java.math.BigDecimal.class, scopes.getFirst(), actor, partition.name(), java.sql.Timestamp.from(week.start()), java.sql.Timestamp.from(week.end()));
                    var spent = week.knownSubtotalUsd() == null ? java.math.BigDecimal.ZERO : week.knownSubtotalUsd();
                    if (spent.add(weekHeld).add(amount).compareTo(week.capUsd()) > 0) return Decision.DENIED;
                }
                String attempt = java.util.UUID.randomUUID().toString();
                jdbc.update("""
                    insert into provider_attempt(id,run_id,workspace_id,actor_id,provider,endpoint,model,channel,adapter_version,
                        simulation,attempt_coverage,prepared_at,started_at,execution_outcome,usage_presence,capture_completeness,projection_state,
                        catalog_marker,catalog_revision_id,purpose,title_conversation_id)
                    values (?,null,?,?,'GOOGLE','interactions','gemini-3.5-flash-lite','GEMINI_API','title-interactions-v1',
                        ?,'VERIFIED',?,?,'IN_PROGRESS','PENDING','UNKNOWN','MISSING','CAPTURED',?,'CONVERSATION_TITLE',?)
                    """, attempt, scopes.getFirst(), actor, partition == WorkspaceAdmission.Partition.SIMULATION,
                    java.sql.Timestamp.from(now), java.sql.Timestamp.from(now), selected.revisionId(), conversationId);
                int inserted = jdbc.update("""
                    insert into workspace_title_reservation(conversation_id,workspace_id,actor_id,partition,reserved_at,month_start,amount_usd,catalog_revision_id,attempt_id,state)
                    values (?,?,?,?,?,?,?,?,?,'HELD') on conflict (conversation_id) do nothing
                    """, conversationId, scopes.getFirst(), actor, partition.name(), java.sql.Timestamp.from(now), month.atDay(1), amount, selected.revisionId(), attempt);
                if (inserted != 1) status.setRollbackOnly();
                return inserted == 1 ? Decision.ALLOWED : Decision.DENIED;
            })).toCompletableFuture().join();
        } catch (RuntimeException ex) { return Decision.DENIED; }
    }

    @Override public boolean capture(String conversationId, io.github.samzhu.bot.conversation.ConversationTitleGenerator.Result result) {
        var ids = jdbc.query("select attempt_id from workspace_title_reservation where conversation_id=?", (rs,n) -> rs.getString(1), conversationId);
        if (ids.isEmpty()) return true; // Existing unbudgeted simulation Fake has no provider attempt.
        String id = ids.getFirst();
        try {
            var u = result.usage();
            if (u == null || u.input() == null || u.cached() == null || u.output() == null || u.thought() == null || u.tool() == null || u.total() == null
                    || u.input() < 0 || u.cached() < 0 || u.output() < 0 || u.thought() < 0 || u.tool() != 0 || u.total() < 0
                    || u.cached() > u.input() || u.input() > 1_048_576 || (long)u.output()+u.thought() > 64
                    || (long)u.input()+u.output()+u.thought() != u.total()) {
                unknown(conversationId); return false;
            }
            var raw = json.createObjectNode();
            raw.put("total_input_tokens", u.input()); raw.put("total_cached_tokens", u.cached());
            raw.put("total_output_tokens", u.output()); raw.put("total_thought_tokens", u.thought());
            raw.put("total_tool_use_tokens", u.tool()); raw.put("total_tokens", u.total());
            ledger.append(id, new io.github.samzhu.bot.usage.UsageLedger.Snapshot("title-final", 1, "FINAL", "SDK_NATIVE",
                "interactions-v1", "title-interactions-v1", "1", "COMPLETE", json.writeValueAsString(raw)));
            if (result.responseId() != null && !result.responseId().isBlank()) ledger.recordResponseId(id, result.responseId());
            ledger.finish(id, "SUCCEEDED");
            pricing.recompute(id);
            var scopes = jdbc.query("select workspace_id from workspace_title_reservation where conversation_id=?", (rs,n) -> rs.getString(1), conversationId);
            if (scopes.isEmpty()) return false;
            return Boolean.TRUE.equals(lanes.submitCritical(scopes.getFirst(), () -> transactions.execute(status -> {
                var rows = jdbc.query("select partition,month_start from workspace_title_reservation where conversation_id=?", (rs,n) -> new Object[]{rs.getString(1),rs.getDate(2)}, conversationId);
                if (rows.isEmpty()) return false;
                // The committed cost projection precedes hold release under the shared budget lock.
                jdbc.query("select workspace_id from workspace_monthly_budget where workspace_id=? and partition=? for update", (rs,n) -> rs.getString(1), scopes.getFirst(), rows.getFirst()[0]);
                return jdbc.update("""
                    update workspace_title_reservation h set state='SETTLED'
                    where h.conversation_id=? and h.state='HELD' and exists (
                        select 1 from provider_attempt a join usage_cost_current c on c.attempt_id=a.id
                        join usage_cost_estimate e on e.id=c.estimate_id
                        where a.id=h.attempt_id and e.source_version=a.projection_version
                          and e.status='COMPLETE' and e.complete_amount<=h.amount_usd)
                    """, conversationId) == 1;
            })).toCompletableFuture().join());
        } catch (Exception ex) { unknown(conversationId); return false; }
    }

    @Override public void unknown(String conversationId) {
        var ids = jdbc.query("select attempt_id from workspace_title_reservation where conversation_id=?", (rs,n) -> rs.getString(1), conversationId);
        if (ids.isEmpty()) return;
        try { ledger.finish(ids.getFirst(), "UNKNOWN"); }
        finally { jdbc.update("update workspace_title_reservation set state='UNKNOWN' where conversation_id=? and state='HELD'", conversationId); }
    }

    private static java.math.BigDecimal rate(com.fasterxml.jackson.databind.JsonNode node, String key) {
        var value = node.path(key);
        if (!value.isTextual() || !value.asText().matches("[0-9]+(?:\\.[0-9]+)?")) throw new IllegalArgumentException("Invalid rate");
        return new java.math.BigDecimal(value.asText());
    }
}
