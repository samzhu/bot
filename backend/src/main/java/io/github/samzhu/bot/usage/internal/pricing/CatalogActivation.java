package io.github.samzhu.bot.usage.internal.pricing;

import io.github.samzhu.bot.pricing.PriceCatalog;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
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
        String id=UUID.randomUUID().toString();
        String hash=contentHash(sourceReference,entries);
        Revision created=transactions.execute(tx -> {
            var inserted=jdbc.query("insert into pricing_catalog_revision(id,source_reference,source_revision,content_hash) values (?,?,?,?) on conflict (source_revision) do nothing returning id,generation",(rs,n)->new Revision(rs.getString(1),rs.getLong(2)),id,sourceReference,sourceRevision,hash);
            if (inserted.isEmpty()) {
                var existing=jdbc.queryForMap("select id,generation,content_hash from pricing_catalog_revision where source_revision=?",sourceRevision);
                if (!hash.equals(existing.get("content_hash"))) throw new IllegalStateException("Catalog revision payload conflict");
                return new Revision((String)existing.get("id"),((Number)existing.get("generation")).longValue());
            }
            long generation=inserted.getFirst().generation();
            for (var e:entries) {
                String rates;
                try { rates=json.writeValueAsString(e.snapshot().rates()); } catch(Exception ex) { throw new IllegalArgumentException(ex); }
                jdbc.update("insert into pricing_catalog_entry(id,revision_id,provider,endpoint,model,channel,price_revision,effective_from,effective_until,rates) values (?,?,?,?,?,?,?,?,?,?::jsonb)",
                    UUID.randomUUID().toString(),id,e.snapshot().provider(),e.snapshot().endpoint(),e.snapshot().model(),e.snapshot().channel(),e.snapshot().revision(),Timestamp.from(e.fromInclusive()),Timestamp.from(e.untilExclusive()),rates);
            }
            return new Revision(id,generation);
        });
        activate(created);
        return created;
    }
    private String contentHash(String source, List<PriceCatalog.Entry> entries) {
        try {
            var normalized=entries.stream().map(e -> {
                var p=e.snapshot();
                try { return json.writeValueAsString(List.of(p.provider(),p.endpoint(),p.model(),p.channel(),p.revision(),e.fromInclusive().toString(),e.untilExclusive().toString(),new java.util.TreeMap<>(p.rates()))); }
                catch (Exception ex) { throw new IllegalArgumentException(ex); }
            }).sorted().toList();
            byte[] bytes=json.writeValueAsBytes(List.of(source,normalized));
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch(Exception e) { throw new IllegalArgumentException("Invalid catalog",e); }
    }
    void activate(Revision revision) { synchronized(lock) { if(active==null || revision.generation()>active.generation()) active=revision; } }
    @EventListener(ApplicationReadyEvent.class)
    @org.springframework.core.annotation.Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE)
    public void restore() {
        var rows=jdbc.query("select id,generation from pricing_catalog_revision order by generation desc limit 1",(rs,n)->new Revision(rs.getString(1),rs.getLong(2)));
        if (!rows.isEmpty()) activate(rows.getFirst());
    }
}
