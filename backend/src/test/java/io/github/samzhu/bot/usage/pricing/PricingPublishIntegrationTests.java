package io.github.samzhu.bot;

import io.github.samzhu.bot.usage.UsageLedger;
import io.github.samzhu.bot.usage.internal.pricing.PricingPublisher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import static org.junit.jupiter.api.Assertions.*;

@org.junit.jupiter.api.TestMethodOrder(org.junit.jupiter.api.MethodOrderer.OrderAnnotation.class)
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class PricingPublishIntegrationTests {
    @Autowired UsageLedger ledger;
    @Autowired PricingPublisher publisher;
    @Autowired JdbcTemplate jdbc;
    @Autowired io.github.samzhu.bot.usage.internal.pricing.CatalogActivation catalog;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
    @Autowired javax.sql.DataSource dataSource;
    @Autowired io.github.samzhu.bot.usage.UsageCostOperations costs;
    @Autowired io.github.samzhu.bot.usage.internal.pricing.PricingReconciliation reconciliation;
    @Test @org.junit.jupiter.api.Order(1) void missingPricePublishesUnknownAndIsIdempotent() {
        String workspace="pricing-test-"+java.util.UUID.randomUUID();
        jdbc.update("insert into workspace(id,name) values (?,?)",workspace,"pricing fixture");
        String attempt=ledger.begin(new UsageLedger.Dispatch(null,workspace,null,"GOOGLE","generateContent","fixture-model","GEMINI_API","1",false,"UNKNOWN"));
        assertTrue(ledger.markDispatched(attempt,java.time.Clock::instant));
        var source=ledger.readPricingInput(attempt);
        assertTrue(publisher.recompute(attempt));
        assertFalse(publisher.recompute(attempt));
        assertEquals(1,jdbc.queryForObject("select count(*) from usage_cost_estimate where attempt_id=?",Integer.class,attempt));
        assertEquals(source.projectionVersion(),jdbc.queryForObject("select source_version from usage_cost_estimate where attempt_id=?",Long.class,attempt));
        assertEquals("UNKNOWN",jdbc.queryForObject("select status from usage_cost_estimate where attempt_id=?",String.class,attempt));
        var unknown=costs.readCosts(java.util.List.of(attempt)).get(attempt);
        assertTrue(unknown.reasons().containsAll(java.util.List.of("MISSING_PRICE","COVERAGE_UNKNOWN","USAGE_UNKNOWN","EXTRA_COST_UNKNOWN")));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () -> jdbc.update("""
            insert into usage_cost_estimate(id,attempt_id,source_version,source_snapshot_id,price_snapshot_id,calculator_version,status,known_subtotal,complete_amount,details,payload_hash)
            select ?,attempt_id,source_version,source_snapshot_id,price_snapshot_id,calculator_version,status,known_subtotal,complete_amount,details,payload_hash
              from usage_cost_estimate where attempt_id=?
            """,java.util.UUID.randomUUID().toString(),attempt));
    }
    @Test @org.junit.jupiter.api.Order(2) void noCatalogDoesNotRetroactivelyAcquirePricesAndLateUsageKeepsOriginal() {
        String workspace="pricing-catalog-"+java.util.UUID.randomUUID();
        jdbc.update("insert into workspace(id,name) values (?,?)",workspace,"pricing fixture");
        String before=ledger.begin(new UsageLedger.Dispatch(null,workspace,null,"GOOGLE","generateContent","fixture-no-catalog","GEMINI_API","1",false,"VERIFIED"));
        assertTrue(ledger.markDispatched(before,java.time.Clock::instant));
        assertEquals("NO_CATALOG",jdbc.queryForObject("select catalog_marker from provider_attempt where id=?",String.class,before));
        var rate = new io.github.samzhu.bot.pricing.PriceSnapshot("GOOGLE","generateContent","fixture-no-catalog","GEMINI_API","fixture-1",java.util.Map.of("input","1","cache_read","0.1","output","2","thoughts","2","tool_input","1"));
        var revision=catalog.publish("fixture-rev-1","fixture-only",java.util.List.of(new io.github.samzhu.bot.pricing.PriceCatalog.Entry(rate,java.time.Instant.parse("2000-01-01T00:00:00Z"),java.time.Instant.parse("2100-01-01T00:00:00Z"))));
        assertTrue(publisher.recompute(before));
        assertEquals("MISSING",jdbc.queryForObject("select selection_state from usage_price_snapshot where attempt_id=?",String.class,before));
        String after=ledger.begin(new UsageLedger.Dispatch(null,workspace,null,"GOOGLE","generateContent","fixture-no-catalog","GEMINI_API","1",false,"VERIFIED"));
        assertTrue(ledger.markDispatched(after,java.time.Clock::instant));
        assertEquals(revision.id(),jdbc.queryForObject("select catalog_revision_id from provider_attempt where id=?",String.class,after));
        ledger.append(after,new UsageLedger.Snapshot("first",1,"CUMULATIVE","FAKE_RAW","1","1","1","COMPLETE","{\"promptTokenCount\":1000,\"cachedContentTokenCount\":200,\"candidatesTokenCount\":300,\"thoughtsTokenCount\":100,\"toolUsePromptTokenCount\":0}"));
        assertTrue(publisher.recompute(after));
        assertEquals("fixture-1",jdbc.queryForObject("select price_revision from usage_price_snapshot where attempt_id=?",String.class,after));
        var subtotal=jdbc.queryForMap("select e.status,e.known_subtotal,e.complete_amount from usage_cost_current c join usage_cost_estimate e on e.id=c.estimate_id where c.attempt_id=?",after);
        assertEquals("PARTIAL",subtotal.get("status"));
        assertEquals(new java.math.BigDecimal("0.001620000000"),subtotal.get("known_subtotal"));
        assertNull(subtotal.get("complete_amount"));
        catalog.publish("fixture-rev-2","fixture-only-new",java.util.List.of());
        ledger.append(after,new UsageLedger.Snapshot("late",2,"FINAL","FAKE_RAW","1","1","1","COMPLETE","{\"promptTokenCount\":1001,\"cachedContentTokenCount\":200,\"candidatesTokenCount\":300,\"thoughtsTokenCount\":100,\"toolUsePromptTokenCount\":0}"));
        assertTrue(publisher.recompute(after));
        assertEquals("fixture-1",jdbc.queryForObject("select price_revision from usage_price_snapshot where attempt_id=?",String.class,after));
        assertEquals(2,jdbc.queryForObject("select count(*) from usage_cost_estimate where attempt_id=?",Integer.class,after));
    }
    @Test @org.junit.jupiter.api.Order(3) void activationSerializesWithDispatchAndRestartRestoresLatest() throws Exception {
        String workspace="pricing-race-"+java.util.UUID.randomUUID();
        jdbc.update("insert into workspace(id,name) values (?,?)",workspace,"pricing fixture");
        var old=catalog.publish("race-rev-old","race-old",java.util.List.of());
        String id=ledger.begin(new UsageLedger.Dispatch(null,workspace,null,"GOOGLE","generateContent","model","GEMINI_API","1",false,"UNKNOWN"));
        var entered=new java.util.concurrent.CountDownLatch(1);
        var proceed=new java.util.concurrent.CountDownLatch(1);
        var executor=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var dispatch=executor.submit(() -> ledger.markDispatched(id,clock -> {
                entered.countDown();
                try { if (!proceed.await(10,java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("timeout"); }
                catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException(ex); }
                return java.time.Instant.parse("2026-11-13T00:00:00Z");
            }));
            assertTrue(entered.await(10,java.util.concurrent.TimeUnit.SECONDS));
            var publish=executor.submit(() -> catalog.publish("race-rev-new","race-new",java.util.List.of()));
            long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
            while (System.nanoTime()<deadline && jdbc.queryForObject("select count(*) from pricing_catalog_revision where source_reference='race-new'",Integer.class)==0) Thread.onSpinWait();
            assertEquals(1,jdbc.queryForObject("select count(*) from pricing_catalog_revision where source_reference='race-new'",Integer.class));
            assertFalse(publish.isDone());
            proceed.countDown();
            assertTrue(dispatch.get(10,java.util.concurrent.TimeUnit.SECONDS));
            var next=publish.get(10,java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(old.id(),jdbc.queryForObject("select catalog_revision_id from provider_attempt where id=?",String.class,id));
            assertEquals(java.time.Instant.parse("2026-11-13T00:00:00Z"),jdbc.queryForObject("select started_at from provider_attempt where id=?",java.sql.Timestamp.class,id).toInstant());
            String later=ledger.begin(new UsageLedger.Dispatch(null,workspace,null,"GOOGLE","generateContent","model","GEMINI_API","1",false,"UNKNOWN"));
            assertTrue(ledger.markDispatched(later,java.time.Clock::instant));
            assertEquals(next.id(),jdbc.queryForObject("select catalog_revision_id from provider_attempt where id=?",String.class,later));
            var retried=catalog.publish("race-rev-old","race-old",java.util.List.of());
            assertEquals(old.id(),retried.id());
            assertEquals(next.id(),catalog.sample(java.time.Clock.systemUTC(),java.time.Clock::instant).revisionId());
            assertThrows(IllegalStateException.class,() -> catalog.publish("race-rev-old","changed-source",java.util.List.of()));
            var restored=new io.github.samzhu.bot.usage.internal.pricing.CatalogActivation(jdbc,transactionManager);
            restored.restore();
            var decision=restored.sample(java.time.Clock.systemUTC(),java.time.Clock::instant);
            assertEquals(next.id(),decision.revisionId());
        } finally { proceed.countDown(); executor.shutdownNow(); }
    }
    @Test @org.junit.jupiter.api.Order(4) void staleComputeCannotBecomeCurrentAndDurableScanRecovers() throws Exception {
        String workspace="pricing-cas-"+java.util.UUID.randomUUID();
        jdbc.update("insert into workspace(id,name) values (?,?)",workspace,"fixture");
        String id=ledger.begin(new UsageLedger.Dispatch(null,workspace,null,"GOOGLE","generateContent","unknown-model","GEMINI_API","1",false,"UNKNOWN"));
        assertTrue(ledger.markDispatched(id,java.time.Clock::instant));
        var executor=java.util.concurrent.Executors.newSingleThreadExecutor();
        try (var lock=dataSource.getConnection()) {
            lock.setAutoCommit(false);
            try (var statement=lock.prepareStatement("select id from provider_attempt where id=? for update")) { statement.setString(1,id); statement.executeQuery().close(); }
            var pending=executor.submit(() -> publisher.recompute(id));
            long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
            boolean waiting=false;
            while (System.nanoTime()<deadline) {
                waiting=Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from pg_stat_activity where datname=current_database() and wait_event_type='Lock' and query like '%projection_version,projection_state,projection_snapshot_id%')",Boolean.class));
                if(waiting) break;
                Thread.onSpinWait();
            }
            assertTrue(waiting,"pricing publish should wait for attempt row");
            try(var update=lock.prepareStatement("update provider_attempt set projection_version=projection_version+1,execution_outcome='FAILED' where id=?")){update.setString(1,id);update.executeUpdate();}
            lock.commit();
            var conflict=assertThrows(java.util.concurrent.ExecutionException.class, () -> pending.get(10,java.util.concurrent.TimeUnit.SECONDS));
            assertInstanceOf(PricingPublisher.Conflict.class, conflict.getCause());
            assertEquals(0,jdbc.queryForObject("select count(*) from usage_cost_estimate where attempt_id=?",Integer.class,id));
            reconciliation.scanPending();
            var current=costs.readCosts(java.util.List.of(id)).get(id);
            assertEquals("UNKNOWN",current.status()); assertFalse(current.needsReprice());
            assertNull(current.completeAmount());
            long count=jdbc.queryForObject("select count(*) from usage_cost_estimate where attempt_id=?",Long.class,id);
            reconciliation.scanPending();
            assertEquals(count,jdbc.queryForObject("select count(*) from usage_cost_estimate where attempt_id=?",Long.class,id));
            jdbc.update("update provider_attempt set projection_version=projection_version+1 where id=?",id);
            var stale=costs.readCosts(java.util.List.of(id)).get(id);
            assertTrue(stale.needsReprice()); assertNull(stale.completeAmount()); assertNull(stale.knownSubtotal());
            reconciliation.scanPending();
            assertFalse(costs.readCosts(java.util.List.of(id)).get(id).needsReprice());
        } finally { executor.shutdownNow(); }
    }
    @Test @org.junit.jupiter.api.Order(5) void calculatorVersionParticipatesInEstimateIdentity() {
        String workspace="pricing-version-"+java.util.UUID.randomUUID();
        jdbc.update("insert into workspace(id,name) values (?,?)",workspace,"fixture");
        String id=ledger.begin(new UsageLedger.Dispatch(null,workspace,null,"GOOGLE","generateContent","model","GEMINI_API","1",false,"UNKNOWN"));
        assertTrue(ledger.markDispatched(id,java.time.Clock::instant));
        assertTrue(publisher.recompute(id));
        // Test-only fault injection represents an older calculator's immutable estimate.
        jdbc.update("update usage_cost_estimate set calculator_version='old-fixture' where attempt_id=?",id);
        assertTrue(publisher.recompute(id));
        assertEquals(2,jdbc.queryForObject("select count(*) from usage_cost_estimate where attempt_id=?",Integer.class,id));
        assertEquals(io.github.samzhu.bot.pricing.PricingCalculator.VERSION,jdbc.queryForObject("select e.calculator_version from usage_cost_current c join usage_cost_estimate e on e.id=c.estimate_id where c.attempt_id=?",String.class,id));
    }
}
