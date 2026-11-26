package io.github.samzhu.bot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.samzhu.bot.usage.UsageLedger;
import io.github.samzhu.bot.usage.internal.pricing.PricingPublisher;
import io.github.samzhu.bot.usage.pricing.UsageCaseData;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

@BackendIntegrationTest
class OfficialGeminiCatalogGraphQLTests {
    @LocalServerPort int port;
    @Autowired UsageLedger ledger;
    @Autowired PricingPublisher publisher;
    @Autowired io.github.samzhu.bot.usage.UsageCostOperations costs;
    @Autowired TransactionTemplate transactions;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();

    private final String workspace = "official-catalog-" + UUID.randomUUID();
    private UsageCaseData data;

    @BeforeEach
    void createWorkspace() {
        data = new UsageCaseData(jdbc, transactions);
        data.createWorkspace(workspace, "dev-user");
    }

    @AfterEach
    void cleanUp() throws InterruptedException {
        try {
            data.cleanUp();
        } finally {
            http.close();
        }
    }

    @Test void configuredStandardRatesExposeKnownComponentsForIncompleteUsage() throws Exception {
        String actor = "dev-user";
        // Independent synthetic SDK-shaped data: not a recorded provider response or a model call.
        String attempt = ledger.begin(new UsageLedger.Dispatch(null,workspace,actor,"GOOGLE","generateContent",
                "gemini-3.8-flash","GEMINI_API","2.1.0-RC2",false,"UNKNOWN"));
        assertTrue(ledger.markDispatched(attempt, clock -> Instant.parse("2026-11-16T00:00:00Z")));
        ledger.append(attempt, new UsageLedger.Snapshot("fixture-"+UUID.randomUUID(),1,"CUMULATIVE","SDK_NATIVE",
                "1","2.1.0-RC2","1","INCOMPLETE",
                "{\"promptTokenCount\":1000000,\"cachedContentTokenCount\":200000,\"candidatesTokenCount\":100000,\"thoughtsTokenCount\":50000,\"toolUsePromptTokenCount\":0}"));
        ledger.finish(attempt,"SUCCEEDED");
        JsonNode cost = publishedCost(workspace, attempt);
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

    }

    @Test void unrecognizedGoogleAdapterDoesNotPublishAnEstimateFromItsCounters() throws Exception {
        String actor = "dev-user";
        String id=ledger.begin(new UsageLedger.Dispatch(null,workspace,actor,"GOOGLE","generateContent","gemini-3.8-flash","GEMINI_API","unknown-adapter",false,"UNKNOWN"));
        assertTrue(ledger.markDispatched(id,clock -> Instant.parse("2026-11-16T00:00:00Z")));
        ledger.append(id,new UsageLedger.Snapshot("fixture-"+UUID.randomUUID(),1,"CUMULATIVE","SDK_NATIVE","1","unknown-adapter","1","INCOMPLETE",
                "{\"promptTokenCount\":1000000,\"cachedContentTokenCount\":200000,\"candidatesTokenCount\":100000,\"thoughtsTokenCount\":50000,\"toolUsePromptTokenCount\":0}"));
        ledger.finish(id,"SUCCEEDED");
        JsonNode cost=publishedCost(workspace,id);
        assertEquals("UNKNOWN",cost.path("status").asText(),cost.toString());
        assertTrue(cost.path("knownSubtotal").isNull(),cost.toString());
        assertTrue(cost.path("reasons").toString().contains("UNSUPPORTED_ENDPOINT"),cost.toString());
    }

    @Test void fakeUsageCannotBePricedOnRealGoogleRoute() throws Exception {
        String actor = "dev-user";
        String id=ledger.begin(new UsageLedger.Dispatch(null,workspace,actor,"GOOGLE","generateContent","gemini-3.8-flash","GEMINI_API","2.1.0",false,"UNKNOWN"));
        assertTrue(ledger.markDispatched(id,clock -> Instant.parse("2026-11-16T00:00:00Z")));
        ledger.append(id,new UsageLedger.Snapshot("fixture-"+UUID.randomUUID(),1,"FINAL","FAKE_RAW","pricing-fixture-v1","1","1","COMPLETE",
                "{\"promptTokenCount\":1000000,\"cachedContentTokenCount\":200000,\"candidatesTokenCount\":100000,\"thoughtsTokenCount\":50000,\"toolUsePromptTokenCount\":0}"));
        ledger.finish(id,"SUCCEEDED");
        JsonNode cost=publishedCost(workspace,id);
        assertEquals("UNKNOWN",cost.path("status").asText(),cost.toString());
        assertTrue(cost.path("knownSubtotal").isNull(),cost.toString());
        assertTrue(cost.path("reasons").toString().contains("UNSUPPORTED_ENDPOINT"),cost.toString());
    }

    @Test void announcedBoundarySelectsNewRatesWithoutChangingEarlierSnapshot() throws Exception {
        String actor = "dev-user";
        String before=syntheticAttempt(workspace,actor,Instant.parse("2026-12-31T23:59:59Z"));
        JsonNode oldCost=publishedCost(workspace,before);
        assertEquals(0,new BigDecimal("1.1775").compareTo(new BigDecimal(oldCost.path("knownSubtotal").asText())),oldCost.toString());
        String oldSnapshot=oldCost.path("priceSnapshotId").asText();
        assertFalse(oldSnapshot.isBlank());
        String after=syntheticAttempt(workspace,actor,Instant.parse("2027-01-01T00:00:00Z"));
        JsonNode newCost=publishedCost(workspace,after);
        assertEquals(0,new BigDecimal("2.355").compareTo(new BigDecimal(newCost.path("knownSubtotal").asText())),newCost.toString());
        JsonNode retained=publishedCost(workspace,before);
        assertEquals(oldSnapshot,retained.path("priceSnapshotId").asText());
        assertEquals(0,new BigDecimal("1.1775").compareTo(new BigDecimal(retained.path("knownSubtotal").asText())));
    }

    @Test void actualSdkCaptureAndLaterSourceVersionConvergeWithoutCompleteClaim() throws Exception {
        String actor = "dev-user";
        var adapter=new io.github.samzhu.bot.execution.internal.capture.GoogleUsageCaptureAdapter("gemini-3.8-flash");
        String id=ledger.begin(adapter.dispatch(null,workspace,actor));
        assertTrue(ledger.markDispatched(id,clock -> Instant.parse("2026-11-16T00:00:00Z")));
        ledger.append(id,sdkSnapshot(adapter,1,1000000));
        ledger.finish(id,"SUCCEEDED");
        JsonNode first=publishedCost(workspace,id);
        assertEquals("PARTIAL",first.path("status").asText(),first.toString());
        assertEquals(0,new BigDecimal("1.1775").compareTo(new BigDecimal(first.path("knownSubtotal").asText())));
        assertTrue(first.path("completeAmount").isNull());
        String pinned=first.path("priceSnapshotId").asText();
        ledger.append(id,sdkSnapshot(adapter,2,2000000));
        JsonNode second=publishedCost(workspace,id);
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

    @Test void dispatchAndReconciliationReferenceSameImmutableCatalogMetadata() throws Exception {
        String actor = "dev-user";
        String id=syntheticAttempt(workspace,actor,Instant.parse("2026-12-31T23:59:59Z"));
        JsonNode first=publishedCost(workspace,id);
        String automatic=first.path("priceSnapshotId").asText();
        String entry=jdbc.queryForObject("select catalog_entry_id from usage_price_snapshot where id=?",String.class,automatic);
        assertNotNull(entry);
        String metadata=jdbc.queryForObject("select metadata::text from pricing_catalog_entry where id=?",String.class,entry);
        String catalog=jdbc.queryForObject("select catalog_revision_id from usage_price_snapshot where id=?",String.class,automatic);
        String mutation="mutation { reconcileCost(input:{workspaceId:\""+workspace+"\",attemptId:\""+id+"\",expectedRevision:\""+first.path("currentRevision").asText()+"\",expectedSourceProjectionVersion:\""+first.path("sourceProjectionVersion").asText()+"\",idempotencyKey:\"meta-"+UUID.randomUUID()+"\",catalogRevisionId:\""+catalog+"\",evidenceReference:\"test\",reason:\"test\"}) { outcome revision { priceSnapshotId } } }";
        JsonNode response=graph(mutation); assertFalse(response.has("errors"),response.toString());
        assertEquals("APPLIED",response.path("data").path("reconcileCost").path("outcome").asText());
        JsonNode reconciled=publishedCost(workspace,id);
        assertEquals(first.path("status"),reconciled.path("status"));
        assertEquals(first.path("knownSubtotal"),reconciled.path("knownSubtotal"));
        assertEquals(first.path("completeAmount"),reconciled.path("completeAmount"));
        assertEquals(first.path("reasons"),reconciled.path("reasons"));
        assertEquals(first.path("lineItems"),reconciled.path("lineItems"));
        String audited=response.path("data").path("reconcileCost").path("revision").path("priceSnapshotId").asText();
        assertEquals(entry,jdbc.queryForObject("select catalog_entry_id from usage_price_snapshot where id=?",String.class,audited));
        assertEquals(metadata,jdbc.queryForObject("select metadata::text from pricing_catalog_entry where id=?",String.class,entry));
        String later=syntheticAttempt(workspace,actor,Instant.parse("2027-01-01T00:00:00Z"));
        assertEquals(0,new BigDecimal("2.355").compareTo(new BigDecimal(publishedCost(workspace,later).path("knownSubtotal").asText())));
        assertEquals(metadata,jdbc.queryForObject("select metadata::text from pricing_catalog_entry where id=?",String.class,entry));
        assertEquals("0.50",json.readTree(metadata).path("storageRate").asText());
    }

    @Test
    void missingPricePublishesUnknownAndIsIdempotent() {
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

    @Test
    void calculatorVersionParticipatesInEstimateIdentity() {
        String id=ledger.begin(new UsageLedger.Dispatch(null,workspace,null,"GOOGLE","generateContent","model","GEMINI_API","1",false,"UNKNOWN"));
        assertTrue(ledger.markDispatched(id,java.time.Clock::instant));
        assertTrue(publisher.recompute(id));
        // The fixture acts as an estimate persisted by an earlier calculator.
        jdbc.update("update usage_cost_estimate set calculator_version='old-fixture' where attempt_id=?",id);
        assertTrue(publisher.recompute(id));
        assertEquals(2,jdbc.queryForObject("select count(*) from usage_cost_estimate where attempt_id=?",Integer.class,id));
        assertEquals(io.github.samzhu.bot.pricing.PricingCalculator.VERSION,jdbc.queryForObject("select e.calculator_version from usage_cost_current c join usage_cost_estimate e on e.id=c.estimate_id where c.attempt_id=?",String.class,id));
    }
    private String syntheticAttempt(String workspace,String actor,Instant time) {
        String id=ledger.begin(new UsageLedger.Dispatch(null,workspace,actor,"GOOGLE","generateContent","gemini-3.8-flash","GEMINI_API","2.1.0-RC2",false,"UNKNOWN"));
        assertTrue(ledger.markDispatched(id,clock -> time));
        ledger.append(id,new UsageLedger.Snapshot("fixture-"+UUID.randomUUID(),1,"CUMULATIVE","SDK_NATIVE","1","2.1.0-RC2","1","INCOMPLETE",
                "{\"promptTokenCount\":1000000,\"cachedContentTokenCount\":200000,\"candidatesTokenCount\":100000,\"thoughtsTokenCount\":50000,\"toolUsePromptTokenCount\":0}"));
        ledger.finish(id,"SUCCEEDED");
        return id;
    }

    private JsonNode publishedCost(String workspace,String attempt) throws Exception {
        String query="{ workspaceUsageAttempt(workspaceId:\""+workspace+"\",id:\""+attempt+"\") { cost { status knownSubtotal completeAmount needsReprice priceSnapshotId currentRevision sourceProjectionVersion reasons lineItems { category nativeQuantity unit meaning rate rateBasis exactAmount storedAmount reasons } } } }";
        publisher.recompute(attempt);
        JsonNode result = graph(query);
        assertFalse(result.has("errors"), result.toString());
        JsonNode cost = result.path("data").path("workspaceUsageAttempt").path("cost");
        assertFalse(cost.path("needsReprice").asBoolean(true), cost.toString());
        return cost;
    }

    private JsonNode graph(String query) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/graphql"))
                .timeout(java.time.Duration.ofSeconds(10))
                .header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("query",query)))).build();
        return json.readTree(http.send(request,HttpResponse.BodyHandlers.ofString()).body());
    }
}
