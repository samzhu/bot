package io.github.samzhu.bot.usage.internal.pricing;

import io.github.samzhu.bot.pricing.PriceCatalog;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/** Activation and dispatch sampling share only this short in-memory lock. */
@Component
public class CatalogActivation {
    public record Revision(String id, long generation) {}
    public record Decision(Instant startedAt, String revisionId) {}
    private final Object lock = new Object();
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper json = new ObjectMapper();
    private Revision active;
    public CatalogActivation(JdbcTemplate jdbc, org.springframework.transaction.PlatformTransactionManager manager) {
        this.jdbc=jdbc; this.transactions=new TransactionTemplate(manager);
        this.transactions.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    public Decision sample(Clock clock, Function<Clock, Instant> authorize) {
        synchronized (lock) {
            Instant time=authorize.apply(clock);
            return time == null ? null : new Decision(time, active == null ? null : active.id());
        }
    }
    public Revision publish(String sourceRevision, String sourceReference, List<PriceCatalog.Entry> entries) {
        if (sourceRevision == null || sourceRevision.isBlank() || sourceReference == null || sourceReference.isBlank()) throw new IllegalArgumentException("source and revision required");
        validateEntries(entries);
        Revision created=transactions.execute(tx -> persist(sourceRevision, sourceReference, entries));
        activate(created);
        return created;
    }

    /** Join the release transaction without changing the live catalog. */
    public void publishRelease(String sourceRevision, String sourceReference, List<PriceCatalog.Entry> entries) {
        if (!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Release catalog requires an active transaction");
        if (sourceRevision == null || sourceRevision.isBlank() || sourceReference == null || sourceReference.isBlank())
            throw new IllegalArgumentException("source and revision required");
        validateEntries(entries);
        persist(sourceRevision, sourceReference, entries);
    }

    private Revision persist(String sourceRevision, String sourceReference, List<PriceCatalog.Entry> entries) {
        String id=UUID.randomUUID().toString();
        String hash=contentHash(sourceReference,entries);
        var inserted=jdbc.query("insert into pricing_catalog_revision(id,source_reference,source_revision,content_hash) values (?,?,?,?) on conflict (source_revision) do nothing returning id,generation",(rs,n)->new Revision(rs.getString(1),rs.getLong(2)),id,sourceReference,sourceRevision,hash);
        if (inserted.isEmpty()) {
            var existing=jdbc.queryForMap("select id,generation,content_hash from pricing_catalog_revision where source_revision=?",sourceRevision);
            if (!hash.equals(existing.get("content_hash"))) throw new IllegalStateException("Catalog revision payload conflict");
            return new Revision((String)existing.get("id"),((Number)existing.get("generation")).longValue());
        }
        for (var e:entries) {
            String rates;
            try { rates=json.writeValueAsString(e.snapshot().rates()); } catch(Exception ex) { throw new IllegalArgumentException(ex); }
            String metadata;
            try { metadata=e.metadata()==null ? null : json.writeValueAsString(e.metadata()); } catch(Exception ex) { throw new IllegalArgumentException(ex); }
            jdbc.update("insert into pricing_catalog_entry(id,revision_id,provider,endpoint,model,channel,price_revision,effective_from,effective_until,rates,metadata) values (?,?,?,?,?,?,?,?,?,?::jsonb,?::jsonb)",
                UUID.randomUUID().toString(),id,e.snapshot().provider(),e.snapshot().endpoint(),e.snapshot().model(),e.snapshot().channel(),e.snapshot().revision(),Timestamp.from(e.fromInclusive()),Timestamp.from(e.untilExclusive()),rates,metadata);
        }
        return inserted.getFirst();
    }
    private void validateEntries(List<PriceCatalog.Entry> entries) {
        // Reject invalid rates before committing a revision that dispatch could sample.
        for (var entry : entries) {
            var price = entry.snapshot();
            // The production Gemini text route requires every priced token category it uses.
            if ("GOOGLE".equals(price.provider()) && "generateContent".equals(price.endpoint())
                    && "gemini-3.8-flash".equals(price.model()) && "GEMINI_API".equals(price.channel())
                    && !price.rates().keySet().containsAll(java.util.Set.of("input", "cache_read", "output", "thoughts", "tool_input")))
                throw new IllegalArgumentException("Missing required catalog rate");
            for (var rate : entry.snapshot().rates().values()) {
                if (!rate.matches("[0-9]+(?:\\.[0-9]+)?"))
                    throw new IllegalArgumentException("Invalid catalog rate");
            }
        }
        for (int i=0; i<entries.size(); i++) {
            var a=entries.get(i);
            for (int j=i+1; j<entries.size(); j++) {
                var b=entries.get(j);
                var x=a.snapshot(); var y=b.snapshot();
                if (x.provider().equals(y.provider()) && x.endpoint().equals(y.endpoint())
                        && x.model().equals(y.model()) && x.channel().equals(y.channel())
                        && a.fromInclusive().isBefore(b.untilExclusive()) && b.fromInclusive().isBefore(a.untilExclusive()))
                    throw new IllegalArgumentException("Overlapping catalog entries");
            }
        }
    }
    private String contentHash(String source, List<PriceCatalog.Entry> entries) {
        try {
            var normalized=entries.stream().map(e -> {
                var p=e.snapshot();
                try {
                    var fields=new java.util.ArrayList<Object>(List.of(p.provider(),p.endpoint(),p.model(),p.channel(),p.revision(),e.fromInclusive().toString(),e.untilExclusive().toString(),new java.util.TreeMap<>(p.rates())));
                    // Preserve historical hashes when legacy entries have no metadata.
                    if (e.metadata()!=null) fields.add(e.metadata());
                    return json.writeValueAsString(fields);
                }
                catch (Exception ex) { throw new IllegalArgumentException(ex); }
            }).sorted().toList();
            byte[] bytes=json.writeValueAsBytes(List.of(source,normalized));
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch(Exception e) { throw new IllegalArgumentException("Invalid catalog",e); }
    }
    public void verifyPublished(String revision, String source, List<PriceCatalog.Entry> entries) {
        validateEntries(entries);
        var hashes = jdbc.queryForList("select content_hash from pricing_catalog_revision where source_revision=?", String.class, revision);
        if (hashes.isEmpty()) throw new IllegalStateException("Release provisioning required: configured pricing catalog is missing");
        if (!contentHash(source, entries).equals(hashes.getFirst()))
            throw new IllegalStateException("Catalog revision payload conflict");
    }
    public void restoreRevision(String revision) {
        var rows = jdbc.query("select id,generation from pricing_catalog_revision where source_revision=?",
                (rs,n) -> new Revision(rs.getString(1),rs.getLong(2)), revision);
        if (rows.size() != 1) throw new IllegalStateException("Release provisioning required: configured pricing catalog is missing");
        synchronized (lock) { active = rows.getFirst(); }
    }
    void activate(Revision revision) { synchronized(lock) { if(active==null || revision.generation()>active.generation()) active=revision; } }
    public void restore(boolean required) {
        var rows=jdbc.query("select id,generation from pricing_catalog_revision order by generation desc limit 1",(rs,n)->new Revision(rs.getString(1),rs.getLong(2)));
        if (rows.isEmpty()) {
            if (required) throw new IllegalStateException("Release provisioning required: pricing catalog is missing");
        } else activate(rows.getFirst());
    }
}
