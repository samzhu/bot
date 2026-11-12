package io.github.samzhu.bot.usage.internal.pricing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import java.sql.Timestamp;

/** Rebuildable queue: source-version mismatch survives process restart. */
@Component
public class PricingReconciliation {
    private static final Logger log=LoggerFactory.getLogger(PricingReconciliation.class);
    private final JdbcTemplate jdbc;
    private final PricingPublisher publisher;
    private record Candidate(String id, Timestamp preparedAt) {}
    public PricingReconciliation(JdbcTemplate jdbc, PricingPublisher publisher) { this.jdbc=jdbc; this.publisher=publisher; }
    @Scheduled(initialDelayString = "${bot.pricing.reconcile.initial-delay-ms:30000}", fixedDelayString = "${bot.pricing.reconcile.delay-ms:30000}")
    public void scanPending() {
        Timestamp afterTime=null; String afterId=null;
        for (;;) {
            String base="""
                select a.id,a.prepared_at from provider_attempt a
                left join usage_cost_current c on c.attempt_id=a.id
                left join usage_cost_estimate e on e.id=c.estimate_id
                where a.execution_outcome <> 'IN_PROGRESS'
                  and (e.id is null or e.source_version <> a.projection_version)
                """;
            var mapper=(org.springframework.jdbc.core.RowMapper<Candidate>)(rs,n)->new Candidate(rs.getString(1),rs.getTimestamp(2));
            var page=afterTime==null ? jdbc.query(base+" order by a.prepared_at,a.id limit 100",mapper)
                    : jdbc.query(base+" and (a.prepared_at,a.id)>(?,?) order by a.prepared_at,a.id limit 100",mapper,afterTime,afterId);
            if (page.isEmpty()) return;
            for (var candidate:page) {
                try { publisher.recompute(candidate.id()); }
                catch (RuntimeException ex) { log.warn("Pricing reconciliation failed for attempt {} ({})", candidate.id(), ex.getClass().getSimpleName()); }
            }
            var last=page.getLast(); afterTime=last.preparedAt(); afterId=last.id();
            if (page.size()<100) return;
        }
    }
}
