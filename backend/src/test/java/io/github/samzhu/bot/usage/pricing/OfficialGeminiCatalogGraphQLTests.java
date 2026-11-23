package io.github.samzhu.bot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.samzhu.bot.usage.UsageLedger;
import io.github.samzhu.bot.workspace.WorkspaceOperations;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import static org.junit.jupiter.api.Assertions.*;

@io.github.samzhu.bot.UseTestFakes
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"bot.pricing.reconcile.initial-delay-ms=100", "bot.pricing.reconcile.delay-ms=200"})
@Import({TestcontainersConfiguration.class, OfficialGeminiCatalogGraphQLTests.Scheduling.class})
@ActiveProfiles("test")
class OfficialGeminiCatalogGraphQLTests {
    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    @org.springframework.scheduling.annotation.EnableScheduling
    static class Scheduling {}

    @LocalServerPort int port;
    @Autowired UsageLedger ledger;
    @Autowired WorkspaceOperations workspaces;
    @Autowired javax.sql.DataSource dataSource;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();

    @Test void configuredStandardRatesExposeKnownComponentsForIncompleteUsage() throws Exception {
        String actor = "dev-user", workspace = workspaces.personal(actor).id();
        // Independent synthetic SDK-shaped data: not a recorded provider response or a model call.
        String attempt = ledger.begin(new UsageLedger.Dispatch(null,workspace,actor,"GOOGLE","generateContent",
                "gemini-3.8-flash","GEMINI_API","2.1.0-RC2",false,"UNKNOWN"));
        assertTrue(ledger.markDispatched(attempt, clock -> Instant.parse("2026-11-16T00:00:00Z")));
        ledger.append(attempt, new UsageLedger.Snapshot("fixture-"+UUID.randomUUID(),1,"CUMULATIVE","SDK_NATIVE",
                "1","2.1.0-RC2","1","INCOMPLETE",
                "{\"promptTokenCount\":1000000,\"cachedContentTokenCount\":200000,\"candidatesTokenCount\":100000,\"thoughtsTokenCount\":50000,\"toolUsePromptTokenCount\":0}"));
        ledger.finish(attempt,"SUCCEEDED");
        String query="{ workspaceUsageAttempt(workspaceId:\""+workspace+"\",id:\""+attempt+"\") { cost { status knownSubtotal completeAmount needsReprice reasons lineItems { category storedAmount } } } }";
        JsonNode cost = null;
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        do {
            var result=graph(query);
            assertFalse(result.has("errors"),result.toString());
            cost=result.path("data").path("workspaceUsageAttempt").path("cost");
            if (!cost.path("needsReprice").asBoolean(true)) break;
            Thread.sleep(50);
        } while (System.nanoTime()<deadline);
        assertFalse(cost.path("needsReprice").asBoolean(true),cost.toString());
        assertEquals("PARTIAL",cost.path("status").asText(),cost.toString());
        assertEquals(0,new BigDecimal("1.1775").compareTo(new BigDecimal(cost.path("knownSubtotal").asText())),cost.toString());
        assertTrue(cost.path("completeAmount").isNull());
        var expected=Map.of("input","0.60", "cache_read","0.015", "output","0.375", "thoughts","0.1875", "tool_input","0");
        for (var line : cost.path("lineItems")) {
            String category=line.path("category").asText();
            assertTrue(expected.containsKey(category),category);
            assertEquals(0,new BigDecimal(expected.get(category)).compareTo(new BigDecimal(line.path("storedAmount").asText())),line.toString());
        }
        assertEquals(5,cost.path("lineItems").size());
        System.out.println("GRAPHQL_COST="+cost);

    }

    @Test void announcedBoundarySelectsNewRatesWithoutChangingEarlierSnapshot() throws Exception {
        String actor="dev-user", workspace=workspaces.personal(actor).id();
        String before=syntheticAttempt(workspace,actor,Instant.parse("2026-12-31T23:59:59Z"));
        JsonNode oldCost=awaitCost(workspace,before);
        assertEquals(0,new BigDecimal("1.1775").compareTo(new BigDecimal(oldCost.path("knownSubtotal").asText())),oldCost.toString());
        String oldSnapshot=oldCost.path("priceSnapshotId").asText();
        assertFalse(oldSnapshot.isBlank());
        String after=syntheticAttempt(workspace,actor,Instant.parse("2027-01-01T00:00:00Z"));
        JsonNode newCost=awaitCost(workspace,after);
        assertEquals(0,new BigDecimal("2.355").compareTo(new BigDecimal(newCost.path("knownSubtotal").asText())),newCost.toString());
        JsonNode retained=awaitCost(workspace,before);
        assertEquals(oldSnapshot,retained.path("priceSnapshotId").asText());
        assertEquals(0,new BigDecimal("1.1775").compareTo(new BigDecimal(retained.path("knownSubtotal").asText())));
    }

    @Test void negativeCatalogRateFailsStartupBeforeActivation() {
        var ds=(com.zaxxer.hikari.HikariDataSource)dataSource;
        String revision="negative-"+UUID.randomUUID();
        var error=assertThrows(Exception.class, () -> {
            try (var context=new org.springframework.boot.builder.SpringApplicationBuilder(BotApplication.class, io.github.samzhu.bot.TestChatModelConfiguration.class, io.github.samzhu.bot.TestUsageCaptureConfiguration.class)
                    .web(org.springframework.boot.WebApplicationType.NONE).profiles("test")
                    .properties("spring.datasource.url="+ds.getJdbcUrl(),"spring.datasource.username="+ds.getUsername(),
                            "spring.datasource.password="+ds.getPassword(),"spring.docker.compose.enabled=false")
                    .run("--bot.pricing.catalog.revision="+revision,
                            "--bot.pricing.catalog.entries[0].provider=GOOGLE",
                            "--bot.pricing.catalog.entries[0].endpoint=generateContent",
                            "--bot.pricing.catalog.entries[0].model=gemini-3.8-flash",
                            "--bot.pricing.catalog.entries[0].channel=GEMINI_API",
                            "--bot.pricing.catalog.entries[0].price-revision=negative-fixture",
                            "--bot.pricing.catalog.entries[0].from-inclusive=2026-11-16T00:00:00Z",
                            "--bot.pricing.catalog.entries[0].until-exclusive=2027-01-01T00:00:00Z",
                            "--bot.pricing.catalog.entries[0].metadata.service-tier=STANDARD",
                            "--bot.pricing.catalog.entries[0].metadata.token-unit=USD_PER_1000000_TOKENS",
                            "--bot.pricing.catalog.entries[0].metadata.sources[0]=https://ai.google.dev/gemini-api/docs/pricing",
                            "--bot.pricing.catalog.entries[0].metadata.verified-date=2026-11-16",
                            "--bot.pricing.catalog.entries[0].metadata.storage-rate=0.50",
                            "--bot.pricing.catalog.entries[0].metadata.storage-unit=USD_PER_1000000_TOKEN_HOURS",
                            "--bot.pricing.catalog.entries[0].rates.input=-1",
                            "--bot.pricing.catalog.entries[0].rates.cache_read=0.075",
                            "--bot.pricing.catalog.entries[0].rates.output=3.75",
                            "--bot.pricing.catalog.entries[0].rates.thoughts=3.75",
                            "--bot.pricing.catalog.entries[0].rates.tool_input=0.75")) { }
        });
        Throwable cause=error;
        while (cause.getCause()!=null) cause=cause.getCause();
        assertEquals("Invalid catalog rate",cause.getMessage());
        assertEquals(0,jdbc.queryForObject("select count(*) from pricing_catalog_revision where source_revision=?",Integer.class,revision));
    }

    @Test void missingProductionMetadataFailsStartupBeforeActivation() {
        var ds=(com.zaxxer.hikari.HikariDataSource)dataSource;
        String revision="metadata-missing-"+UUID.randomUUID();
        var error=assertThrows(Exception.class, () -> {
            try (var context=new org.springframework.boot.builder.SpringApplicationBuilder(BotApplication.class, io.github.samzhu.bot.TestChatModelConfiguration.class, io.github.samzhu.bot.TestUsageCaptureConfiguration.class)
                    .web(org.springframework.boot.WebApplicationType.NONE).profiles("test")
                    .properties("spring.datasource.url="+ds.getJdbcUrl(),"spring.datasource.username="+ds.getUsername(),
                            "spring.datasource.password="+ds.getPassword(),"spring.docker.compose.enabled=false")
                    .run("--bot.pricing.catalog.revision="+revision,
                            "--bot.pricing.catalog.entries[0].provider=GOOGLE",
                            "--bot.pricing.catalog.entries[0].endpoint=generateContent",
                            "--bot.pricing.catalog.entries[0].model=gemini-3.8-flash",
                            "--bot.pricing.catalog.entries[0].channel=GEMINI_API",
                            "--bot.pricing.catalog.entries[0].price-revision=metadata-missing-fixture",
                            "--bot.pricing.catalog.entries[0].from-inclusive=2026-11-16T00:00:00Z",
                            "--bot.pricing.catalog.entries[0].until-exclusive=2027-01-01T00:00:00Z",
                            "--bot.pricing.catalog.entries[0].rates.input=0.75",
                            "--bot.pricing.catalog.entries[0].rates.cache_read=0.075",
                            "--bot.pricing.catalog.entries[0].rates.output=3.75",
                            "--bot.pricing.catalog.entries[0].rates.thoughts=3.75",
                            "--bot.pricing.catalog.entries[0].rates.tool_input=0.75")) { }
        });
        Throwable cause=error;
        while (cause.getCause()!=null) cause=cause.getCause();
        assertEquals("Invalid catalog metadata",cause.getMessage());
        assertEquals(0,jdbc.queryForObject("select count(*) from pricing_catalog_revision where source_revision=?",Integer.class,revision));
    }

    @Test void overlappingCatalogIntervalsFailStartupBeforeActivation() {
        var ds=(com.zaxxer.hikari.HikariDataSource)dataSource;
        String revision="overlap-"+UUID.randomUUID();
        var error=assertThrows(Exception.class, () -> {
            try (var context=new org.springframework.boot.builder.SpringApplicationBuilder(BotApplication.class, io.github.samzhu.bot.TestChatModelConfiguration.class, io.github.samzhu.bot.TestUsageCaptureConfiguration.class)
                    .web(org.springframework.boot.WebApplicationType.NONE).profiles("test")
                    .properties("spring.datasource.url="+ds.getJdbcUrl(),"spring.datasource.username="+ds.getUsername(),
                            "spring.datasource.password="+ds.getPassword(),"spring.docker.compose.enabled=false")
                    .run("--bot.pricing.catalog.revision="+revision,
                            "--bot.pricing.catalog.entries[0].provider=GOOGLE",
                            "--bot.pricing.catalog.entries[0].endpoint=generateContent",
                            "--bot.pricing.catalog.entries[0].model=gemini-3.8-flash",
                            "--bot.pricing.catalog.entries[0].channel=GEMINI_API",
                            "--bot.pricing.catalog.entries[0].price-revision=overlap-fixture",
                            "--bot.pricing.catalog.entries[0].from-inclusive=2026-11-16T00:00:00Z",
                            "--bot.pricing.catalog.entries[0].until-exclusive=2027-01-01T00:00:00Z",
                            "--bot.pricing.catalog.entries[0].metadata.service-tier=STANDARD",
                            "--bot.pricing.catalog.entries[0].metadata.token-unit=USD_PER_1000000_TOKENS",
                            "--bot.pricing.catalog.entries[0].metadata.sources[0]=https://ai.google.dev/gemini-api/docs/pricing",
                            "--bot.pricing.catalog.entries[0].metadata.verified-date=2026-11-16",
                            "--bot.pricing.catalog.entries[0].metadata.storage-rate=0.50",
                            "--bot.pricing.catalog.entries[0].metadata.storage-unit=USD_PER_1000000_TOKEN_HOURS",
                            "--bot.pricing.catalog.entries[0].rates.input=0.75",
                            "--bot.pricing.catalog.entries[0].rates.cache_read=0.075",
                            "--bot.pricing.catalog.entries[0].rates.output=3.75",
                            "--bot.pricing.catalog.entries[0].rates.thoughts=3.75",
                            "--bot.pricing.catalog.entries[0].rates.tool_input=0.75",
                            "--bot.pricing.catalog.entries[1].provider=GOOGLE",
                            "--bot.pricing.catalog.entries[1].endpoint=generateContent",
                            "--bot.pricing.catalog.entries[1].model=gemini-3.8-flash",
                            "--bot.pricing.catalog.entries[1].channel=GEMINI_API",
                            "--bot.pricing.catalog.entries[1].price-revision=overlap-fixture2",
                            "--bot.pricing.catalog.entries[1].from-inclusive=2026-12-01T00:00:00Z",
                            "--bot.pricing.catalog.entries[1].until-exclusive=2027-02-01T00:00:00Z",
                            "--bot.pricing.catalog.entries[1].metadata.service-tier=STANDARD",
                            "--bot.pricing.catalog.entries[1].metadata.token-unit=USD_PER_1000000_TOKENS",
                            "--bot.pricing.catalog.entries[1].metadata.sources[0]=https://ai.google.dev/gemini-api/docs/pricing",
                            "--bot.pricing.catalog.entries[1].metadata.verified-date=2026-11-16",
                            "--bot.pricing.catalog.entries[1].metadata.storage-rate=0.50",
                            "--bot.pricing.catalog.entries[1].metadata.storage-unit=USD_PER_1000000_TOKEN_HOURS",
                            "--bot.pricing.catalog.entries[1].rates.input=0.75",
                            "--bot.pricing.catalog.entries[1].rates.cache_read=0.075",
                            "--bot.pricing.catalog.entries[1].rates.output=3.75",
                            "--bot.pricing.catalog.entries[1].rates.thoughts=3.75",
                            "--bot.pricing.catalog.entries[1].rates.tool_input=0.75")) { }
        });
        Throwable cause=error;
        while (cause.getCause()!=null) cause=cause.getCause();
        assertEquals("Overlapping catalog entries",cause.getMessage());
        assertEquals(0,jdbc.queryForObject("select count(*) from pricing_catalog_revision where source_revision=?",Integer.class,revision));
    }

    @Test void missingRequiredGeminiRateFailsStartupBeforeActivation() {
        var ds=(com.zaxxer.hikari.HikariDataSource)dataSource;
        String revision="missing-"+UUID.randomUUID();
        var error=assertThrows(Exception.class, () -> {
            try (var context=new org.springframework.boot.builder.SpringApplicationBuilder(BotApplication.class, io.github.samzhu.bot.TestChatModelConfiguration.class, io.github.samzhu.bot.TestUsageCaptureConfiguration.class)
                    .web(org.springframework.boot.WebApplicationType.NONE).profiles("test")
                    .properties("spring.datasource.url="+ds.getJdbcUrl(),"spring.datasource.username="+ds.getUsername(),
                            "spring.datasource.password="+ds.getPassword(),"spring.docker.compose.enabled=false")
                    .run("--bot.pricing.catalog.revision="+revision,
                            "--bot.pricing.catalog.entries[0].provider=GOOGLE",
                            "--bot.pricing.catalog.entries[0].endpoint=generateContent",
                            "--bot.pricing.catalog.entries[0].model=gemini-3.8-flash",
                            "--bot.pricing.catalog.entries[0].channel=GEMINI_API",
                            "--bot.pricing.catalog.entries[0].price-revision=missing-fixture",
                            "--bot.pricing.catalog.entries[0].from-inclusive=2026-11-16T00:00:00Z",
                            "--bot.pricing.catalog.entries[0].until-exclusive=2027-01-01T00:00:00Z",
                            "--bot.pricing.catalog.entries[0].metadata.service-tier=STANDARD",
                            "--bot.pricing.catalog.entries[0].metadata.token-unit=USD_PER_1000000_TOKENS",
                            "--bot.pricing.catalog.entries[0].metadata.sources[0]=https://ai.google.dev/gemini-api/docs/pricing",
                            "--bot.pricing.catalog.entries[0].metadata.verified-date=2026-11-16",
                            "--bot.pricing.catalog.entries[0].metadata.storage-rate=0.50",
                            "--bot.pricing.catalog.entries[0].metadata.storage-unit=USD_PER_1000000_TOKEN_HOURS",
                            "--bot.pricing.catalog.entries[0].rates.input=0.75",
                            "--bot.pricing.catalog.entries[0].rates.cache_read=0.075",
                            "--bot.pricing.catalog.entries[0].rates.thoughts=3.75",
                            "--bot.pricing.catalog.entries[0].rates.tool_input=0.75")) { }
        });
        Throwable cause=error;
        while (cause.getCause()!=null) cause=cause.getCause();
        assertEquals("Missing required catalog rate",cause.getMessage());
        assertEquals(0,jdbc.queryForObject("select count(*) from pricing_catalog_revision where source_revision=?",Integer.class,revision));
    }

    @Test void configuredCatalogRestartIsIdempotentAndConflictingRevisionIsRejected() {
        var ds=(com.zaxxer.hikari.HikariDataSource)dataSource;
        String revision="gemini-standard-2026-11-16";
        String id=jdbc.queryForObject("select id from pricing_catalog_revision where source_revision=?",String.class,revision);
        try (var restarted=new org.springframework.boot.builder.SpringApplicationBuilder(BotApplication.class, io.github.samzhu.bot.TestChatModelConfiguration.class, io.github.samzhu.bot.TestUsageCaptureConfiguration.class)
                .web(org.springframework.boot.WebApplicationType.NONE).profiles("test")
                .properties("spring.datasource.url="+ds.getJdbcUrl(),"spring.datasource.username="+ds.getUsername(),
                        "spring.datasource.password="+ds.getPassword(),"spring.docker.compose.enabled=false").run()) {
            assertEquals(1,jdbc.queryForObject("select count(*) from pricing_catalog_revision where source_revision=?",Integer.class,revision));
            assertEquals(id,jdbc.queryForObject("select id from pricing_catalog_revision where source_revision=?",String.class,revision));
        }
        var error=assertThrows(Exception.class,() -> {
            try (var context=new org.springframework.boot.builder.SpringApplicationBuilder(BotApplication.class, io.github.samzhu.bot.TestChatModelConfiguration.class, io.github.samzhu.bot.TestUsageCaptureConfiguration.class)
                    .web(org.springframework.boot.WebApplicationType.NONE).profiles("test")
                    .properties("spring.datasource.url="+ds.getJdbcUrl(),"spring.datasource.username="+ds.getUsername(),
                            "spring.datasource.password="+ds.getPassword(),"spring.docker.compose.enabled=false")
                    .run("--bot.pricing.catalog.entries[0].provider=GOOGLE",
                            "--bot.pricing.catalog.entries[0].endpoint=generateContent",
                            "--bot.pricing.catalog.entries[0].model=gemini-3.8-flash",
                            "--bot.pricing.catalog.entries[0].channel=GEMINI_API",
                            "--bot.pricing.catalog.entries[0].price-revision=changed",
                            "--bot.pricing.catalog.entries[0].from-inclusive=2026-11-16T00:00:00Z",
                            "--bot.pricing.catalog.entries[0].until-exclusive=2027-01-01T00:00:00Z",
                            "--bot.pricing.catalog.entries[0].metadata.service-tier=STANDARD",
                            "--bot.pricing.catalog.entries[0].metadata.token-unit=USD_PER_1000000_TOKENS",
                            "--bot.pricing.catalog.entries[0].metadata.sources[0]=https://ai.google.dev/gemini-api/docs/pricing",
                            "--bot.pricing.catalog.entries[0].metadata.verified-date=2026-11-16",
                            "--bot.pricing.catalog.entries[0].metadata.storage-rate=0.50",
                            "--bot.pricing.catalog.entries[0].metadata.storage-unit=USD_PER_1000000_TOKEN_HOURS",
                            "--bot.pricing.catalog.entries[0].rates.input=9",
                            "--bot.pricing.catalog.entries[0].rates.cache_read=0.075",
                            "--bot.pricing.catalog.entries[0].rates.output=3.75",
                            "--bot.pricing.catalog.entries[0].rates.thoughts=3.75",
                            "--bot.pricing.catalog.entries[0].rates.tool_input=0.75")) { }
        });
        Throwable cause=error; while(cause.getCause()!=null) cause=cause.getCause();
        assertEquals("Catalog revision payload conflict",cause.getMessage());
        assertEquals(id,jdbc.queryForObject("select id from pricing_catalog_revision where source_revision=?",String.class,revision));
    }

    @Test void actualSdkCaptureAndLaterSourceVersionConvergeWithoutCompleteClaim() throws Exception {
        String actor="dev-user", workspace=workspaces.personal(actor).id();
        var adapter=new io.github.samzhu.bot.execution.internal.capture.GoogleUsageCaptureAdapter("gemini-3.8-flash");
        String id=ledger.begin(adapter.dispatch(null,workspace,actor));
        assertTrue(ledger.markDispatched(id,clock -> Instant.parse("2026-11-16T00:00:00Z")));
        ledger.append(id,sdkSnapshot(adapter,1,1000000));
        ledger.finish(id,"SUCCEEDED");
        JsonNode first=awaitCost(workspace,id);
        assertEquals("PARTIAL",first.path("status").asText(),first.toString());
        assertEquals(0,new BigDecimal("1.1775").compareTo(new BigDecimal(first.path("knownSubtotal").asText())));
        assertTrue(first.path("completeAmount").isNull());
        String pinned=first.path("priceSnapshotId").asText();
        ledger.append(id,sdkSnapshot(adapter,2,2000000));
        JsonNode second=awaitCost(workspace,id);
        assertEquals("PARTIAL",second.path("status").asText(),second.toString());
        assertEquals(0,new BigDecimal("1.9275").compareTo(new BigDecimal(second.path("knownSubtotal").asText())),second.toString());
        assertTrue(second.path("completeAmount").isNull());
        assertEquals(pinned,second.path("priceSnapshotId").asText());
        assertTrue(second.path("reasons").toString().contains("CAPTURE_INCOMPLETE"));
        assertTrue(second.path("reasons").toString().contains("COVERAGE_UNKNOWN"));
    }

    private UsageLedger.Snapshot sdkSnapshot(io.github.samzhu.bot.execution.internal.capture.GoogleUsageCaptureAdapter adapter,long ordinal,int prompt) {
        var nativeUsage=com.google.genai.types.GenerateContentResponseUsageMetadata.fromJson(
                "{\"promptTokenCount\":"+prompt+",\"cachedContentTokenCount\":200000,\"candidatesTokenCount\":100000,\"thoughtsTokenCount\":50000,\"toolUsePromptTokenCount\":0}");
        var metadata=org.springframework.ai.chat.metadata.ChatResponseMetadata.builder()
                .usage(new org.springframework.ai.chat.metadata.DefaultUsage(0,0,0,nativeUsage)).build();
        return adapter.capture(new org.springframework.ai.chat.model.ChatResponse(java.util.List.of(),metadata),"sdk-"+UUID.randomUUID(),ordinal).orElseThrow();
    }

    @Test void configuredEntryMetadataIsPersistedForReview() throws Exception {
        assertTrue(jdbc.queryForObject("select exists(select 1 from information_schema.columns where table_schema=current_schema() and table_name='pricing_catalog_entry' and column_name='metadata')",Boolean.class),"entry metadata must be persisted");
        String stored=jdbc.queryForObject("select e.metadata::text from pricing_catalog_entry e join pricing_catalog_revision r on r.id=e.revision_id where r.source_revision=? and e.price_revision=?",String.class,"gemini-standard-2026-11-16","gemini-3.8-flash-standard-2026");
        JsonNode metadata=json.readTree(stored);
        assertEquals("STANDARD",metadata.path("serviceTier").asText());
        assertEquals("USD_PER_1000000_TOKENS",metadata.path("tokenUnit").asText());
        assertEquals("2026-11-16",metadata.path("verifiedDate").asText());
        assertTrue(metadata.path("sources").toString().contains("https://ai.google.dev/gemini-api/docs/pricing"));
        assertEquals("0.50",metadata.path("storageRate").asText());
        assertEquals("USD_PER_1000000_TOKEN_HOURS",metadata.path("storageUnit").asText());
    }

    @Test void sameRevisionWithChangedMetadataFailsBeforeReplacement(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        String revision="gemini-standard-2026-11-16";
        String original=jdbc.queryForObject("select id from pricing_catalog_revision where source_revision=?",String.class,revision);
        String yaml;
        try(var in=getClass().getResourceAsStream("/application.yaml")) { yaml=new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8); }
        assertTrue(yaml.contains("storage-rate: \"0.50\""));
        var config=dir.resolve("application.yaml");
        java.nio.file.Files.writeString(config,yaml.replace("storage-rate: \"0.50\"","storage-rate: \"0.51\""));
        var ds=(com.zaxxer.hikari.HikariDataSource)dataSource;
        var error=assertThrows(Exception.class,() -> {
            try(var context=new org.springframework.boot.builder.SpringApplicationBuilder(BotApplication.class, io.github.samzhu.bot.TestChatModelConfiguration.class, io.github.samzhu.bot.TestUsageCaptureConfiguration.class)
                    .web(org.springframework.boot.WebApplicationType.NONE).profiles("test")
                    .properties("spring.datasource.url="+ds.getJdbcUrl(),"spring.datasource.username="+ds.getUsername(),
                            "spring.datasource.password="+ds.getPassword(),"spring.docker.compose.enabled=false")
                    .run("--spring.config.additional-location="+config.toUri())) { }
        });
        Throwable cause=error; while(cause.getCause()!=null) cause=cause.getCause();
        assertEquals("Catalog revision payload conflict",cause.getMessage());
        assertEquals(original,jdbc.queryForObject("select id from pricing_catalog_revision where source_revision=?",String.class,revision));
        assertEquals("0.50",jdbc.queryForObject("select e.metadata->>'storageRate' from pricing_catalog_entry e join pricing_catalog_revision r on r.id=e.revision_id where r.source_revision=? and e.price_revision='gemini-3.8-flash-standard-2026'",String.class,revision));
    }

    @Test void dispatchAndReconciliationReferenceSameImmutableCatalogMetadata() throws Exception {
        String actor="dev-user",workspace=workspaces.personal(actor).id();
        String id=syntheticAttempt(workspace,actor,Instant.parse("2026-12-31T23:59:59Z"));
        JsonNode first=awaitCost(workspace,id);
        String automatic=first.path("priceSnapshotId").asText();
        String entry=jdbc.queryForObject("select catalog_entry_id from usage_price_snapshot where id=?",String.class,automatic);
        assertNotNull(entry);
        String metadata=jdbc.queryForObject("select metadata::text from pricing_catalog_entry where id=?",String.class,entry);
        String catalog=jdbc.queryForObject("select catalog_revision_id from usage_price_snapshot where id=?",String.class,automatic);
        String mutation="mutation { reconcileCost(input:{workspaceId:\""+workspace+"\",attemptId:\""+id+"\",expectedRevision:\""+first.path("currentRevision").asText()+"\",expectedSourceProjectionVersion:\""+first.path("sourceProjectionVersion").asText()+"\",idempotencyKey:\"meta-"+UUID.randomUUID()+"\",catalogRevisionId:\""+catalog+"\",evidenceReference:\"test\",reason:\"test\"}) { outcome revision { priceSnapshotId } } }";
        JsonNode response=graph(mutation); assertFalse(response.has("errors"),response.toString());
        assertEquals("APPLIED",response.path("data").path("reconcileCost").path("outcome").asText());
        String audited=response.path("data").path("reconcileCost").path("revision").path("priceSnapshotId").asText();
        assertEquals(entry,jdbc.queryForObject("select catalog_entry_id from usage_price_snapshot where id=?",String.class,audited));
        assertEquals(metadata,jdbc.queryForObject("select metadata::text from pricing_catalog_entry where id=?",String.class,entry));
        String later=syntheticAttempt(workspace,actor,Instant.parse("2027-01-01T00:00:00Z"));
        assertEquals(0,new BigDecimal("2.355").compareTo(new BigDecimal(awaitCost(workspace,later).path("knownSubtotal").asText())));
        assertEquals(metadata,jdbc.queryForObject("select metadata::text from pricing_catalog_entry where id=?",String.class,entry));
        assertEquals("0.50",json.readTree(metadata).path("storageRate").asText());
    }

    @Test void legacyCatalogWithoutMetadataKeepsHistoricalHashAcrossRestart() {
        String id=UUID.randomUUID().toString(),entry=UUID.randomUUID().toString(),revision="legacy-fixture-"+UUID.randomUUID();
        jdbc.update("insert into pricing_catalog_revision(id,source_reference,source_revision,content_hash) values (?,'legacy-source',?,'b7d8a998e7050db252188e6b7c219ce0ca7154eeb2c05f10398224143e63e02d')",id,revision);
        jdbc.update("insert into pricing_catalog_entry(id,revision_id,provider,endpoint,model,channel,price_revision,effective_from,effective_until,rates) values (?,?,'FAKE','fixture','model','FAKE','old-price','2020-01-01T00:00:00Z','2030-01-01T00:00:00Z','{\"input\":\"1\"}'::jsonb)",entry,id);
        var ds=(com.zaxxer.hikari.HikariDataSource)dataSource;
        try(var context=new org.springframework.boot.builder.SpringApplicationBuilder(BotApplication.class, io.github.samzhu.bot.TestChatModelConfiguration.class, io.github.samzhu.bot.TestUsageCaptureConfiguration.class)
                .web(org.springframework.boot.WebApplicationType.NONE).profiles("test")
                .properties("spring.datasource.url="+ds.getJdbcUrl(),"spring.datasource.username="+ds.getUsername(),
                        "spring.datasource.password="+ds.getPassword(),"spring.docker.compose.enabled=false")
                .run("--bot.pricing.catalog.revision="+revision,"--bot.pricing.catalog.source-reference=legacy-source",
                        "--bot.pricing.catalog.entries[0].provider=FAKE","--bot.pricing.catalog.entries[0].endpoint=fixture",
                        "--bot.pricing.catalog.entries[0].model=model","--bot.pricing.catalog.entries[0].channel=FAKE",
                        "--bot.pricing.catalog.entries[0].price-revision=old-price",
                        "--bot.pricing.catalog.entries[0].from-inclusive=2020-01-01T00:00:00Z",
                        "--bot.pricing.catalog.entries[0].until-exclusive=2030-01-01T00:00:00Z",
                        "--bot.pricing.catalog.entries[0].rates.input=1")) {
            assertEquals(id,jdbc.queryForObject("select id from pricing_catalog_revision where source_revision=?",String.class,revision));
            assertEquals(1,jdbc.queryForObject("select count(*) from pricing_catalog_entry where id=? and metadata is null",Integer.class,entry));
        }
    }

    private String syntheticAttempt(String workspace,String actor,Instant time) {
        String id=ledger.begin(new UsageLedger.Dispatch(null,workspace,actor,"GOOGLE","generateContent","gemini-3.8-flash","GEMINI_API","2.1.0-RC2",false,"UNKNOWN"));
        assertTrue(ledger.markDispatched(id,clock -> time));
        ledger.append(id,new UsageLedger.Snapshot("fixture-"+UUID.randomUUID(),1,"CUMULATIVE","SDK_NATIVE","1","2.1.0-RC2","1","INCOMPLETE",
                "{\"promptTokenCount\":1000000,\"cachedContentTokenCount\":200000,\"candidatesTokenCount\":100000,\"thoughtsTokenCount\":50000,\"toolUsePromptTokenCount\":0}"));
        ledger.finish(id,"SUCCEEDED");
        return id;
    }

    private JsonNode awaitCost(String workspace,String attempt) throws Exception {
        String query="{ workspaceUsageAttempt(workspaceId:\""+workspace+"\",id:\""+attempt+"\") { cost { status knownSubtotal completeAmount needsReprice priceSnapshotId currentRevision sourceProjectionVersion reasons } } }";
        JsonNode cost=null; long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        do {
            JsonNode result=graph(query); assertFalse(result.has("errors"),result.toString());
            cost=result.path("data").path("workspaceUsageAttempt").path("cost");
            if(!cost.path("needsReprice").asBoolean(true)) return cost;
            Thread.sleep(50);
        } while(System.nanoTime()<deadline);
        fail("pricing did not converge: "+cost); return cost;
    }

    private JsonNode graph(String query) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/graphql"))
                .header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("query",query)))).build();
        return json.readTree(http.send(request,HttpResponse.BodyHandlers.ofString()).body());
    }
}
