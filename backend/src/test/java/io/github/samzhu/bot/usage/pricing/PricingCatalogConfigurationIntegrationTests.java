package io.github.samzhu.bot;

import io.github.samzhu.bot.usage.UsageLedger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import static org.junit.jupiter.api.Assertions.*;

/** Isolated synthetic live publication; these rates are not a production price list. */
@io.github.samzhu.bot.UseTestFakes
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class PricingCatalogConfigurationIntegrationTests {
    @Autowired JdbcTemplate jdbc;
    @Autowired UsageLedger ledger;
    @Autowired io.github.samzhu.bot.usage.internal.pricing.CatalogActivation activation;
    @Test void controlledPublicationIsPersistedBeforeUse() {
        activation.publish("synthetic-config-v1", "synthetic-only", java.util.List.of(
                new io.github.samzhu.bot.pricing.PriceCatalog.Entry(
                        new io.github.samzhu.bot.pricing.PriceSnapshot("GOOGLE", "generateContent", "synthetic-model", "GEMINI_API", "synthetic-price-v1", java.util.Map.of("input", "1")),
                        java.time.Instant.parse("2000-01-01T00:00:00Z"), java.time.Instant.parse("2100-01-01T00:00:00Z"))));
        String workspace="pricing-config-"+java.util.UUID.randomUUID();
        jdbc.update("insert into workspace(id,name) values (?,?)",workspace,"fixture");
        String attempt=ledger.begin(new UsageLedger.Dispatch(null,workspace,null,"GOOGLE","generateContent","synthetic-model","GEMINI_API","1",true,"VERIFIED"));
        assertTrue(ledger.markDispatched(attempt,java.time.Clock::instant));
        assertEquals("synthetic-config-v1",jdbc.queryForObject("select r.source_revision from provider_attempt a join pricing_catalog_revision r on r.id=a.catalog_revision_id where a.id=?",String.class,attempt));
        assertEquals(1,jdbc.queryForObject("select count(*) from pricing_catalog_entry e join pricing_catalog_revision r on r.id=e.revision_id where r.source_revision='synthetic-config-v1'",Integer.class));
    }
}
