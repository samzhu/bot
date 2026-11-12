package io.github.samzhu.bot;

import io.github.samzhu.bot.usage.UsageLedger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import static org.junit.jupiter.api.Assertions.*;

/** Isolated synthetic configuration; these rates are not a production price list. */
@SpringBootTest(properties = {
    "bot.pricing.catalog.revision=synthetic-config-v1",
    "bot.pricing.catalog.source-reference=synthetic-only",
    "bot.pricing.catalog.entries[0].provider=GOOGLE",
    "bot.pricing.catalog.entries[0].endpoint=generateContent",
    "bot.pricing.catalog.entries[0].model=synthetic-model",
    "bot.pricing.catalog.entries[0].channel=GEMINI_API",
    "bot.pricing.catalog.entries[0].price-revision=synthetic-price-v1",
    "bot.pricing.catalog.entries[0].from-inclusive=2000-01-01T00:00:00Z",
    "bot.pricing.catalog.entries[0].until-exclusive=2100-01-01T00:00:00Z",
    "bot.pricing.catalog.entries[0].rates.input=1"
})
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class PricingCatalogConfigurationIntegrationTests {
    @Autowired JdbcTemplate jdbc;
    @Autowired UsageLedger ledger;
    @Test void externalConfigurationIsPersistedBeforeUse() {
        String workspace="pricing-config-"+java.util.UUID.randomUUID();
        jdbc.update("insert into workspace(id,name) values (?,?)",workspace,"fixture");
        String attempt=ledger.begin(new UsageLedger.Dispatch(null,workspace,null,"GOOGLE","generateContent","synthetic-model","GEMINI_API","1",true,"VERIFIED"));
        assertTrue(ledger.markDispatched(attempt,java.time.Clock::instant));
        assertEquals("synthetic-config-v1",jdbc.queryForObject("select r.source_revision from provider_attempt a join pricing_catalog_revision r on r.id=a.catalog_revision_id where a.id=?",String.class,attempt));
        assertEquals(1,jdbc.queryForObject("select count(*) from pricing_catalog_entry e join pricing_catalog_revision r on r.id=e.revision_id where r.source_revision='synthetic-config-v1'",Integer.class));
    }
}
