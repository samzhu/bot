package io.github.samzhu.bot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.samzhu.bot.usage.pricing.UsageCaseData;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.*;

@BackendIntegrationTest
class SharedUsageCostRevisionIntegrationTests {
    private static final String SOURCE_VERSION = "1";
    private static final String MANUAL = """
            mutation ManualRevision($input: ManualCostRevisionInput!) {
              manualCostRevision(input: $input) {
                outcome
                revision { id actorId knownSubtotal completeAmount status }
                effectiveCost { currentRevision sourceProjectionVersion knownSubtotal completeAmount needsReprice }
              }
            }
            """;

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate transactions;

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final String workspace = "manual-cost-workspace-" + UUID.randomUUID();
    private final String attempt = "manual-cost-attempt-" + UUID.randomUUID();
    private final String initialRevision = "manual-cost-initial-" + UUID.randomUUID();
    private UsageCaseData data;

    @BeforeEach
    void createAttempt() {
        data = new UsageCaseData(jdbc, transactions);
        data.createWorkspace(workspace, "dev-user");
        transactions.executeWithoutResult(status -> {
            String catalog = jdbc.queryForObject("""
                    select id from pricing_catalog_revision where source_revision='gemini-standard-2026-11-16'
                    """, String.class);
            String price = "manual-cost-price-" + UUID.randomUUID();
            jdbc.update("""
                    insert into provider_attempt(id,workspace_id,actor_id,provider,endpoint,model,channel,adapter_version,
                      simulation,attempt_coverage,prepared_at,started_at,finished_at,execution_outcome,execution_reason,
                      usage_presence,capture_completeness,projection_state,projection_error,projection_version,
                      catalog_marker,catalog_revision_id)
                    values (?,?,'dev-user','FAKE','local','manual-fixture','test','test',false,'VERIFIED',now(),now(),now(),
                      'UNKNOWN','CAPTURE_FAILED','UNKNOWN','UNKNOWN','UNKNOWN','CAPTURE_FAILED',1,'CAPTURED',?)
                    """, attempt, workspace, catalog);
            jdbc.update("""
                    insert into usage_price_snapshot(id,attempt_id,selection_state,provider,endpoint,model,channel,
                      catalog_revision_id)
                    values (?,?,'MISSING','FAKE','local','manual-fixture','test',?)
                    """, price, attempt, catalog);
            // Start from a terminal attempt already priced for its source version, so the
            // scheduler does not race the first manual command to create the current row.
            jdbc.update("""
                    insert into usage_cost_estimate(id,attempt_id,source_version,price_snapshot_id,calculator_version,
                      status,details,payload_hash)
                    values (?,?,1,?,'fixture','UNKNOWN','{"reasons":["MISSING_PRICE"],"lines":[]}'::jsonb,'fixture')
                    """, initialRevision, attempt, price);
            jdbc.update("insert into usage_cost_current(attempt_id,estimate_id) values (?,?)", attempt, initialRevision);
        });
    }

    @AfterEach
    void cleanUp() throws InterruptedException {
        try {
            data.cleanUp();
        } finally {
            http.close();
        }
    }

    @Test
    void manualTenToTwelveReplacesCurrentAmountButNeverClearsUnknown() throws Exception {
        var ten = mutationResult(manual(initialRevision, SOURCE_VERSION, "key-ten", "10", "evidence-10"));
        assertEquals("APPLIED", ten.path("outcome").asText());
        String revision10 = ten.path("revision").path("id").asText();
        var twelve = mutationResult(manual(revision10, SOURCE_VERSION, "key-twelve", "12", "evidence-12"));
        var revision = twelve.path("revision");
        assertEquals("12", revision.path("knownSubtotal").asText());
        assertTrue(revision.path("completeAmount").isNull());
        assertEquals("UNKNOWN", revision.path("status").asText());
        String revision12 = revision.path("id").asText();
        assertEquals(revision12, storedCurrent());

        var replay = mutationResult(manual(initialRevision, SOURCE_VERSION, "key-ten", "10", "evidence-10"));
        assertEquals("REPLAYED", replay.path("outcome").asText());
        assertEquals(revision10, replay.path("revision").path("id").asText());
        assertEquals(revision12, replay.path("effectiveCost").path("currentRevision").asText());
        assertEquals(0, new BigDecimal("12").compareTo(
                new BigDecimal(replay.path("effectiveCost").path("knownSubtotal").asText())));
        assertEquals(revision12, storedCurrent());
    }

    @Test
    void sameKeySamePayloadReplaysOriginalRevisionAndDifferentPayloadConflicts() throws Exception {
        var first = mutationResult(manual(initialRevision, SOURCE_VERSION, "same-key", "10", "same-evidence"));
        String original = first.path("revision").path("id").asText();
        var replay = mutationResult(manual(initialRevision, SOURCE_VERSION, "same-key", "10", "same-evidence"));
        assertEquals("REPLAYED", replay.path("outcome").asText());
        assertEquals(original, replay.path("revision").path("id").asText());
        assertCode("CONFLICT", manual(initialRevision, SOURCE_VERSION, "same-key", "11", "different-evidence"));

        assertEquals(1, jdbc.update("""
                update workspace_member set active=false where workspace_id=? and user_id='dev-user'
                """, workspace));
        assertCode("WORKSPACE_ACCESS_DENIED",
                manual(initialRevision, SOURCE_VERSION, "same-key", "10", "same-evidence"));
        assertEquals(original, storedCurrent());
        assertEquals(1, auditCount());
    }

    @Test
    void staleRevisionOrSourceProjectionVersionConflicts() throws Exception {
        var first = mutationResult(manual(initialRevision, SOURCE_VERSION, "cas-one", "10", "evidence"));
        String current = first.path("revision").path("id").asText();
        assertCode("CONFLICT", manual(initialRevision, SOURCE_VERSION, "cas-two", "12", "evidence"));
        assertEquals(1, jdbc.update("update provider_attempt set projection_version=2 where id=?", attempt));

        var lateReplay = mutationResult(manual(initialRevision, SOURCE_VERSION, "cas-one", "10", "evidence"));
        assertEquals("REPLAYED", lateReplay.path("outcome").asText());
        assertEquals(current, lateReplay.path("revision").path("id").asText());
        var cost = lateReplay.path("effectiveCost");
        assertEquals("2", cost.path("sourceProjectionVersion").asText());
        assertTrue(cost.path("needsReprice").asBoolean());
        assertTrue(cost.path("completeAmount").isNull());
        assertEquals(1, jdbc.queryForObject("""
                select count(*) from usage_cost_revision_audit where attempt_id=? and idempotency_key='cas-one'
                """, Integer.class, attempt));
        assertCode("CONFLICT", manual(current, SOURCE_VERSION, "cas-three", "12", "evidence"));
        assertEquals(current, storedCurrent());
        assertEquals(1, auditCount());
    }

    @Test
    void manualAmountRejectsNegativeOverflowAndExcessScaleButAllowsEvidencedZeroAsUnknown() throws Exception {
        for (String amount : List.of("-1", "1000000000000000000", "1.0000000000001")) {
            assertCode("INVALID_AMOUNT",
                    manual(initialRevision, SOURCE_VERSION, "invalid-amount", amount, "evidence"));
        }
        assertEquals(0, auditCount());
        assertEquals(initialRevision, storedCurrent());

        var zero = mutationResult(manual(initialRevision, SOURCE_VERSION, "evidenced-zero", "0", "manager evidence"));
        assertEquals("UNKNOWN", zero.path("revision").path("status").asText());
        assertTrue(zero.path("revision").path("completeAmount").isNull());
    }

    private JsonNode manual(String expectedRevision, String sourceVersion, String key, String amount,
                            String evidence) throws Exception {
        var input = Map.of(
                "workspaceId", workspace, "attemptId", attempt, "expectedRevision", expectedRevision,
                "expectedSourceProjectionVersion", sourceVersion, "idempotencyKey", key, "amount", amount,
                "sourceType", "MANUAL_ESTIMATE", "evidenceReference", evidence, "reason", "manual test");
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/graphql"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(
                        Map.of("query", MANUAL, "variables", Map.of("input", input)))))
                .build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        return json.readTree(response.body());
    }

    private JsonNode mutationResult(JsonNode response) {
        assertFalse(response.has("errors"), response.toString());
        var result = response.path("data").path("manualCostRevision");
        assertEquals("dev-user", result.path("revision").path("actorId").asText());
        return result;
    }

    private void assertCode(String code, JsonNode response) {
        assertEquals(code, response.path("errors").path(0).path("extensions").path("code").asText(),
                response.toString());
    }

    private String storedCurrent() {
        return jdbc.queryForObject("select estimate_id from usage_cost_current where attempt_id=?", String.class, attempt);
    }

    private int auditCount() {
        return jdbc.queryForObject("select count(*) from usage_cost_revision_audit where attempt_id=?", Integer.class, attempt);
    }
}
