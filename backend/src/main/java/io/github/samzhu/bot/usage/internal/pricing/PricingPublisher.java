package io.github.samzhu.bot.usage.internal.pricing;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.samzhu.bot.pricing.PriceCatalog;
import io.github.samzhu.bot.pricing.PriceSnapshot;
import io.github.samzhu.bot.pricing.PricingCalculator;
import io.github.samzhu.bot.usage.UsageLedger;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Owns the short publish transaction; provider calculation happens before row locking. */
@Service
public class PricingPublisher {
    private final UsageLedger ledger;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper json = new ObjectMapper();
    public PricingPublisher(UsageLedger ledger, JdbcTemplate jdbc, TransactionTemplate transactions) {
        this.ledger = ledger; this.jdbc = jdbc; this.transactions = transactions;
    }
    public static class Conflict extends RuntimeException {
        public Conflict() { super("Pricing source conflict"); }
    }
    private record PriceChoice(String state, String revision, Map<String,String> rates, String catalogId,
                               String entryId, String source, java.time.Instant from, java.time.Instant until) {}
    public boolean recompute(String id) {
        var input = ledger.readPricingInput(id);
        if (input == null) return false;
        var currentBefore=jdbc.query("select estimate_id from usage_cost_current where attempt_id=?",(rs,n)->rs.getString(1),id);
        String observedCurrent=currentBefore.isEmpty()?null:currentBefore.getFirst();
        PriceChoice choice = priceChoice(input);
        boolean undispatched = "NOT_DISPATCHED".equals(input.executionOutcome()) && input.startedAt() == null;
        var snapshot = input.effectiveSnapshot();
        var fixture = FakePricingEvidence.dialect(input);
        boolean realGoogle = "GOOGLE".equals(input.provider()) && "generateContent".equals(input.endpoint()) && "GEMINI_API".equals(input.channel());
        String calcProvider = fixture != null ? fixture.provider() : "gemini";
        String calcEndpoint = fixture != null ? fixture.endpoint() : "generateContent";
        boolean extraCostsAbsent = fixture != null && fixture.textOnlyEvidence();
        PricingCalculator.Estimate estimate = null;
        List<String> reasons = new ArrayList<>();
        if (undispatched) reasons.add("NOT_DISPATCHED");
        else if (!"MATCHED".equals(choice.state())) {
            reasons.add(switch (choice.state()) { case "MISSING" -> "MISSING_PRICE"; case "OVERLAPPING" -> "OVERLAPPING_PRICE"; default -> "LEGACY_UNPRICED"; });
            if ("MISSING".equals(choice.state()) && snapshot != null && "VALID".equals(input.projectionState())
                    && "JSONB".equals(snapshot.rawState()) && (realGoogle || fixture != null)) {
                estimate = new PricingCalculator().estimate(new PricingCalculator.Input(calcProvider, calcEndpoint, input.model(), input.channel(), snapshot.rawJson(),
                    "FINAL".equals(snapshot.semantic()), "VERIFIED".equals(input.attemptCoverage()), extraCostsAbsent), null);
                for (var r:estimate.reasons()) if (!reasons.contains(r.name())) reasons.add(r.name());
                if (!"COMPLETE".equals(snapshot.captureCompleteness())) reasons.add("CAPTURE_INCOMPLETE");
                java.util.Collections.sort(reasons);
            }
        }
        else if (!"VALID".equals(input.projectionState()) || snapshot == null || !"JSONB".equals(snapshot.rawState())) reasons.add(input.projectionReason() == null ? "USAGE_UNKNOWN" : input.projectionReason());
        else if (!realGoogle && fixture == null) reasons.add("UNSUPPORTED_ENDPOINT");
        else {
            var calcPrice = new PriceSnapshot(calcProvider, calcEndpoint, input.model(), input.channel(), choice.revision(), choice.rates());
            estimate = new PricingCalculator().estimate(new PricingCalculator.Input(calcProvider, calcEndpoint, input.model(), input.channel(), snapshot.rawJson(),
                "FINAL".equals(snapshot.semantic()), "VERIFIED".equals(input.attemptCoverage()), extraCostsAbsent), calcPrice);
            estimate.reasons().forEach(r -> reasons.add(r.name()));
            if (!"COMPLETE".equals(snapshot.captureCompleteness())) reasons.add("CAPTURE_INCOMPLETE");
            java.util.Collections.sort(reasons);
        }
        if (!undispatched) {
            if (!"VERIFIED".equals(input.attemptCoverage()) && !reasons.contains("COVERAGE_UNKNOWN")) reasons.add("COVERAGE_UNKNOWN");
            if (snapshot == null) {
                String reason=input.projectionReason() == null ? "USAGE_UNKNOWN" : input.projectionReason();
                if (!reasons.contains(reason)) reasons.add(reason);
            } else {
                if (!"FINAL".equals(snapshot.semantic()) && !reasons.contains("NOT_FINAL")) reasons.add("NOT_FINAL");
                if (!"COMPLETE".equals(snapshot.captureCompleteness()) && !reasons.contains("CAPTURE_INCOMPLETE")) reasons.add("CAPTURE_INCOMPLETE");
            }
            if (!extraCostsAbsent && !reasons.contains("EXTRA_COST_UNKNOWN")) reasons.add("EXTRA_COST_UNKNOWN");
        }
        java.util.Collections.sort(reasons);
        var subtotal = estimate == null ? null : estimate.knownSubtotal();
        String status = undispatched ? "NOT_DISPATCHED" : "LEGACY_UNPRICED".equals(choice.state()) ? "UNPRICED"
                : estimate == null ? "UNKNOWN" : !reasons.isEmpty() ? subtotal == null ? "UNKNOWN" : "PARTIAL" : estimate.status().name();
        var complete = estimate == null || !"COMPLETE".equals(status) ? null : estimate.completeAmount();
        String details;
        try {
            List<Map<String,Object>> detailLines=new ArrayList<>();
            if (estimate != null) for (var line:estimate.lines()) {
                Map<String,Object> value=new java.util.LinkedHashMap<>();
                value.put("category",line.category()); value.put("nativeQuantity",line.nativeQuantity());
                value.put("ratePerMillion",line.ratePerMillion()); value.put("exactAmount",line.exactAmount());
                value.put("storedAmount",line.storedAmount()==null ? null : line.storedAmount().toPlainString());
                value.put("reason",line.reason()); detailLines.add(value);
            }
            details = canonicalDetails(reasons, detailLines);
        }
        catch (Exception e) { throw new IllegalStateException(e); }
        Boolean result = transactions.execute(tx -> {
            var rows = jdbc.query("select projection_version,projection_state,projection_snapshot_id,execution_outcome from provider_attempt where id=? for update",
                (rs,n)-> new Object[]{rs.getLong(1),rs.getString(2),rs.getString(3),rs.getString(4)},id);
            if (rows.isEmpty()) return false;
            var row=rows.getFirst();
            String effective="VALID".equals(row[1]) ? (String)row[2] : null;
            if ((Long)row[0] != input.projectionVersion() || !java.util.Objects.equals(effective,input.effectiveSnapshotId())
                    || !java.util.Objects.equals(row[3], input.executionOutcome())) throw new Conflict();
            var currentNow=jdbc.query("select estimate_id from usage_cost_current where attempt_id=?",(rs,n)->rs.getString(1),id);
            if (!java.util.Objects.equals(observedCurrent,currentNow.isEmpty()?null:currentNow.getFirst())) throw new Conflict();
            var manual=jdbc.query("select 1 from usage_cost_current c join usage_cost_revision_audit a on a.estimate_id=c.estimate_id and a.attempt_id=c.attempt_id where c.attempt_id=?",(rs,n)->rs.getInt(1),id);
            boolean protectedCurrent=!manual.isEmpty();
            var existing=jdbc.query("select id,selection_state,price_revision,rates::text,catalog_revision_id,catalog_entry_id,source_reference,extract(epoch from effective_from)::text,extract(epoch from effective_until)::text from usage_price_snapshot where attempt_id=? and origin='AUTO_DISPATCH'",(rs,n)->new String[]{rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getString(6),rs.getString(7),rs.getString(8),rs.getString(9)},id);
            String priceId;
            if (existing.isEmpty()) {
                priceId=UUID.randomUUID().toString();
                String rates = choice.rates() == null ? null : toJson(choice.rates());
                jdbc.update("insert into usage_price_snapshot(id,attempt_id,selection_state,provider,endpoint,model,channel,price_revision,rates,catalog_revision_id,catalog_entry_id,source_reference,effective_from,effective_until) values (?,?,?,?,?,?,?,?,?::jsonb,?,?,?,?,?)",priceId,id,choice.state(),input.provider(),input.endpoint(),input.model(),input.channel(),choice.revision(),rates,choice.catalogId(),choice.entryId(),choice.source(),choice.from()==null ? null : java.sql.Timestamp.from(choice.from()),choice.until()==null ? null : java.sql.Timestamp.from(choice.until()));
            } else {
                var p=existing.getFirst(); priceId=p[0];
                if (!choice.equals(fromStored(p))) throw new Conflict();
            }
            var payload=new java.util.TreeMap<String,Object>();
            payload.put("attemptId",id); payload.put("sourceVersion",Long.toString(input.projectionVersion()));
            payload.put("sourceSnapshotId",input.effectiveSnapshotId()); payload.put("priceSnapshotId",priceId);
            payload.put("priceRevision",choice.revision()); payload.put("calculatorVersion",PricingCalculator.VERSION);
            payload.put("status",status); payload.put("currency","USD");
            payload.put("knownSubtotal",subtotal==null ? null : subtotal.toPlainString());
            payload.put("completeAmount",complete==null ? null : complete.toPlainString()); payload.put("details",details);
            String hash=canonicalPayloadHash(payload);
            var already=jdbc.query("""
                select id,payload_hash from usage_cost_estimate
                 where attempt_id=? and source_version=? and source_snapshot_id is not distinct from ?
                   and price_snapshot_id=? and calculator_version=?
                """,(rs,n)->new String[]{rs.getString(1),rs.getString(2)},id,input.projectionVersion(),input.effectiveSnapshotId(),priceId,PricingCalculator.VERSION);
            if (!already.isEmpty()) {
                if (!already.getFirst()[1].equals(hash)) throw new Conflict();
                String existingId=already.getFirst()[0];
                if (protectedCurrent) checkpoint(id,input.projectionVersion());
                else jdbc.update("insert into usage_cost_current(attempt_id,estimate_id) values (?,?) on conflict (attempt_id) do update set estimate_id=excluded.estimate_id where usage_cost_current.estimate_id<>excluded.estimate_id",id,existingId);
                return false;
            }
            String estimateId=UUID.randomUUID().toString();
            jdbc.update("insert into usage_cost_estimate(id,attempt_id,source_version,source_snapshot_id,price_snapshot_id,calculator_version,status,known_subtotal,complete_amount,details,payload_hash,publication_kind) values (?,?,?,?,?,?,?,?,?,?::jsonb,?,?)",
                estimateId,id,input.projectionVersion(),input.effectiveSnapshotId(),priceId,PricingCalculator.VERSION,status,subtotal,complete,details,hash,protectedCurrent?"AUTO_CANDIDATE":"AUTO_CURRENT");
            // Preserve an audited current, but durably retain this source-version candidate
            // before marking the source processed so restart cannot lose the calculation.
            if (protectedCurrent) checkpoint(id,input.projectionVersion());
            else jdbc.update("insert into usage_cost_current(attempt_id,estimate_id) values (?,?) on conflict (attempt_id) do update set estimate_id=excluded.estimate_id",id,estimateId);
            return true;
        });
        return Boolean.TRUE.equals(result);
    }
    private void checkpoint(String id,long version) {
        jdbc.update("insert into usage_cost_manual_source_checkpoint(attempt_id,last_processed_projection_version) values (?,?) on conflict (attempt_id) do update set last_processed_projection_version=excluded.last_processed_projection_version",id,version);
    }
    // Persisted hashes must be independent of JVM-specific map iteration order.
    static String canonicalDetails(List<String> reasons, List<? extends Map<String, Object>> lines) {
        try {
            var root = new java.util.TreeMap<String, Object>();
            root.put("reasons", reasons.stream().sorted().toList());
            root.put("lines", lines.stream().map(java.util.TreeMap::new).toList());
            return new ObjectMapper().writeValueAsString(root);
        } catch (Exception ex) {
            throw new IllegalArgumentException("Cannot serialize pricing evidence", ex);
        }
    }
    static String canonicalPayloadHash(Map<String,Object> payload) {
        try {
            String canonical=new ObjectMapper().writeValueAsString(new java.util.TreeMap<>(payload));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new IllegalArgumentException("Cannot hash pricing evidence",e); }
    }
    private PriceChoice priceChoice(UsageLedger.PricingInputView input) {
        // Late usage must use the dispatch selection; a newer catalog requires an audited revision.
        var pinned=jdbc.query("select id,selection_state,price_revision,rates::text,catalog_revision_id,catalog_entry_id,source_reference,extract(epoch from effective_from)::text,extract(epoch from effective_until)::text from usage_price_snapshot where attempt_id=? and origin='AUTO_DISPATCH'",(rs,n)->new String[]{rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getString(6),rs.getString(7),rs.getString(8),rs.getString(9)},input.attemptId());
        if (!pinned.isEmpty()) return fromStored(pinned.getFirst());
        var markers=jdbc.query("select catalog_marker,catalog_revision_id from provider_attempt where id=?",(rs,n)->new String[]{rs.getString(1),rs.getString(2)},input.attemptId());
        if (markers.isEmpty() || "LEGACY_UNPRICED".equals(markers.getFirst()[0])) return new PriceChoice("LEGACY_UNPRICED",null,null,null,null,null,null,null);
        if (!"CAPTURED".equals(markers.getFirst()[0]) || input.startedAt()==null) return new PriceChoice("MISSING",null,null,null,null,null,null,null);
        var matches=jdbc.query("""
            select e.id,e.price_revision,e.rates::text,r.source_reference,e.effective_from,e.effective_until
              from pricing_catalog_entry e join pricing_catalog_revision r on r.id=e.revision_id
             where e.revision_id=? and e.provider=? and e.endpoint=? and e.model=? and e.channel=?
               and e.effective_from<=? and ?<e.effective_until
            """, (rs,n)-> {
                try { return new PriceChoice("MATCHED",rs.getString(2),json.readValue(rs.getString(3),new TypeReference<Map<String,String>>(){}),markers.getFirst()[1],rs.getString(1),rs.getString(4),rs.getTimestamp(5).toInstant(),rs.getTimestamp(6).toInstant()); }
                catch(Exception ex) { throw new java.sql.SQLException("Invalid catalog",ex); }
            }, markers.getFirst()[1],input.provider(),input.endpoint(),input.model(),input.channel(),java.sql.Timestamp.from(input.startedAt()),java.sql.Timestamp.from(input.startedAt()));
        return matches.isEmpty() ? new PriceChoice("MISSING",null,null,markers.getFirst()[1],null,null,null,null)
             : matches.size()>1 ? new PriceChoice("OVERLAPPING",null,null,markers.getFirst()[1],null,null,null,null) : matches.getFirst();
    }
    private PriceChoice fromStored(String[] p) {
        try { return new PriceChoice(p[1],p[2],p[3]==null ? null : json.readValue(p[3],new TypeReference<Map<String,String>>(){}),p[4],p[5],p[6],p[7]==null ? null : instantFromEpoch(p[7]),p[8]==null ? null : instantFromEpoch(p[8])); }
        catch (Exception e) { throw new IllegalStateException("Invalid stored price snapshot",e); }
    }
    private static java.time.Instant instantFromEpoch(String s) {
        var seconds=new java.math.BigDecimal(s);
        long whole=seconds.setScale(0,java.math.RoundingMode.FLOOR).longValueExact();
        int nanos=seconds.subtract(java.math.BigDecimal.valueOf(whole)).movePointRight(9).intValueExact();
        return java.time.Instant.ofEpochSecond(whole,nanos);
    }
    private String toJson(Object o) { try { return json.writeValueAsString(o); } catch (Exception e) { throw new IllegalStateException(e); } }
}
