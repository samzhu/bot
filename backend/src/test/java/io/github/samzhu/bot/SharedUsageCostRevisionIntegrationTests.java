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
                revision { id actorId knownSubtotal completeAmount status sourceSnapshotId }
                effectiveCost { currentRevision sourceProjectionVersion knownSubtotal completeAmount needsReprice }
              }
            }
            """;

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate transactions;
    @Autowired io.github.samzhu.bot.workspace.WorkspaceOperations workspaces;
    @Autowired io.github.samzhu.bot.usage.UsageLedger ledger;
    @Autowired io.github.samzhu.bot.usage.UsageCostOperations costs;
    @Autowired io.github.samzhu.bot.usage.internal.admission.WorkspaceLane lane;
    @Autowired io.github.samzhu.bot.usage.internal.admission.BudgetProjection projection;
    @Autowired io.github.samzhu.bot.usage.internal.pricing.PricingPublisher publisher;
    @Autowired java.time.Clock clock;

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final String workspace = "manual-cost-workspace-" + UUID.randomUUID();
    private final String attempt = "manual-cost-attempt-" + UUID.randomUUID();
    private final String initialRevision = "manual-cost-initial-" + UUID.randomUUID();
    private UsageCaseData data;
    private UsageCaseData foreignData;

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
            // Seed a committed initial pointer independently of the manual commands under test.
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
            if (foreignData != null) foreignData.cleanUp();
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

    @Test
    void notDispatchedAttemptCannotBeManuallyCorrected() throws Exception {
        transactions.executeWithoutResult(status -> {
            jdbc.update("""
                    update provider_attempt
                       set execution_outcome='NOT_DISPATCHED', execution_reason=null, started_at=null
                     where id=?
                    """, attempt);
            jdbc.update("""
                    update usage_cost_estimate
                       set status='NOT_DISPATCHED', details='{"reasons":["NOT_DISPATCHED"],"lines":[]}'::jsonb
                     where id=?
                    """, initialRevision);
        });
        assertCode("INVALID_COST_REVISION",
                manual(initialRevision, SOURCE_VERSION, "not-dispatched", "10", "evidence"));
        assertNoManualRevision();
    }

    @Test
    void manualSourceTypeAndUnicodeWhitespaceEvidenceAreRejected() throws Exception {
        assertCode("INVALID_COST_REVISION", manual(initialRevision, SOURCE_VERSION, "wrong-source", "1",
                "evidence", "SERVER_RAW_CATALOG", "reason"));
        assertCode("INVALID_COST_REVISION", manual(initialRevision, SOURCE_VERSION, "unicode-blank", "0",
                "\u3000", "MANUAL_ESTIMATE", "\u3000"));
        assertNoManualRevision();
    }

    @Test
    void manualRequiresSubstantiveUnicodeEvidenceAndReason() throws Exception {
        for (String blank : List.of("\u00a0", "\u202f", " \u3000\u00a0\u202f ")) {
            assertCode("INVALID_COST_REVISION", manual(initialRevision, SOURCE_VERSION, "blank-evidence", "1",
                    blank, "MANUAL_ESTIMATE", "reason"));
            assertCode("INVALID_COST_REVISION", manual(initialRevision, SOURCE_VERSION, "blank-reason", "1",
                    "proof", "MANUAL_ESTIMATE", blank));
        }
        assertNoManualRevision();

        var valid = mutationResult(manual(initialRevision, SOURCE_VERSION, "unicode-valid", "1",
                "\u00a0證據\u202f", "MANUAL_ESTIMATE", "\u202f理由\u00a0"));
        assertEquals("APPLIED", valid.path("outcome").asText());
        assertEquals(1, auditCount());
    }

    @Test
    void manualRevisionPinsEffectiveSnapshotIdForLaterAudit() throws Exception {
        String snapshot = "manual-cost-source-" + UUID.randomUUID();
        transactions.executeWithoutResult(status -> {
            jdbc.update("""
                    insert into usage_snapshot(id,attempt_id,event_id,payload_hash,ordinal,semantic,source_layer,
                      schema_version,adapter_version,capture_version,capture_completeness,raw_state,raw_usage,captured_at)
                    values (?,?,?,'fixture',1,'FINAL','FAKE_RAW','1','1','1','COMPLETE','JSONB','{"input":10}'::jsonb,now())
                    """, snapshot, attempt, "event-" + snapshot);
            jdbc.update("""
                    update provider_attempt
                       set projection_state='VALID', usage_presence='PRESENT', projection_snapshot_id=?
                     where id=?
                    """, snapshot, attempt);
            jdbc.update("update usage_cost_estimate set source_snapshot_id=? where id=?", snapshot, initialRevision);
        });
        var result = mutationResult(manual(initialRevision, SOURCE_VERSION, "snapshot-source", "10", "evidence"));
        var revision = result.path("revision");
        assertEquals(snapshot, revision.path("sourceSnapshotId").asText());
        String current = storedCurrent();
        assertEquals(revision.path("id").asText(), current);
        assertEquals(snapshot, jdbc.queryForObject("""
                select source_snapshot_id from usage_cost_estimate where attempt_id=? and id=?
                """, String.class, attempt, current));
    }

    @Test
    void concurrentManualCommandsWithSameExpectedRevisionOnlyOneApplies() throws Exception {
        try (var requests = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var start = new java.util.concurrent.CountDownLatch(1);
            var left = requests.submit(() -> {
                start.await();
                return manual(initialRevision, SOURCE_VERSION, "race-one", "10", "one");
            });
            var right = requests.submit(() -> {
                start.await();
                return manual(initialRevision, SOURCE_VERSION, "race-two", "12", "two");
            });
            start.countDown();
            var results = List.of(left.get(10, java.util.concurrent.TimeUnit.SECONDS),
                    right.get(10, java.util.concurrent.TimeUnit.SECONDS));
            var applied = results.stream().filter(node -> !node.has("errors")).toList();
            assertEquals(1, applied.size(), results.toString());
            assertEquals("APPLIED", mutationResult(applied.getFirst()).path("outcome").asText());
            assertCode("CONFLICT", results.stream().filter(node -> node.has("errors")).findFirst().orElseThrow());
            assertEquals(1, auditCount());
        }
    }

    @Test
    void revisionHistoryPreservesAuditedPointerWhenEffectiveCostIsStale() throws Exception {
        var first = mutationResult(manual(initialRevision, SOURCE_VERSION, "history-first", "10", "proof"));
        var second = mutationResult(manual(first.path("revision").path("id").asText(), SOURCE_VERSION, "history-next", "12", "proof"));
        var current = second.path("revision").path("id").asText();
        jdbc.update("update provider_attempt set projection_version=2 where id=?", attempt);

        String document = """
                query History($workspace: ID!, $attempt: ID!, $cursor: String) {
                  costRevisions(workspaceId: $workspace, attemptId: $attempt, first: 1, after: $cursor) {
                    currentRevisionId nodes { id current } pageInfo { endCursor }
                  }
                  workspaceUsageAttempt(workspaceId: $workspace, id: $attempt) {
                    cost { status knownSubtotal completeAmount needsReprice currentRevision }
                  }
                }
                """;
        var response = graph(document, Map.of("workspace", workspace, "attempt", attempt));
        assertFalse(response.has("errors"), response.toString());
        var history = response.path("data").path("costRevisions");
        assertEquals(current, history.path("currentRevisionId").asText());
        assertTrue(history.path("nodes").path(0).path("current").asBoolean());
        var cost = response.path("data").path("workspaceUsageAttempt").path("cost");
        assertTrue(cost.path("needsReprice").asBoolean());
        assertTrue(cost.path("completeAmount").isNull());
        assertTrue(cost.path("currentRevision").isNull());

        String cursor = history.path("pageInfo").path("endCursor").asText();
        assertFalse(cursor.isBlank());
        assertCode("INVALID_CURSOR", graph(document,
                Map.of("workspace", workspace, "attempt", attempt, "cursor", "!" + cursor)));
        revokeManager();
        assertCode("WORKSPACE_ACCESS_DENIED", graph(document,
                Map.of("workspace", workspace, "attempt", attempt, "cursor", cursor)));
    }

    @Test
    void publishedCatalogRequiresCurrentManagerAndRejectsTamperedCursor() throws Exception {
        String document = """
                query Catalog($workspace: ID!, $cursor: String) {
                  publishedPricingCatalogs(workspaceId: $workspace, first: 1, after: $cursor) {
                    nodes { sourceRevision } pageInfo { endCursor }
                  }
                }
                """;
        var result = graph(document, Map.of("workspace", workspace));
        assertFalse(result.has("errors"), result.toString());
        var page = result.path("data").path("publishedPricingCatalogs");
        assertEquals("gemini-standard-2026-11-16", page.path("nodes").path(0).path("sourceRevision").asText());
        var cursor = page.path("pageInfo").path("endCursor").asText();
        assertFalse(cursor.isBlank());
        assertCode("INVALID_CURSOR", graph(document, Map.of("workspace", workspace, "cursor", "!" + cursor)));
        revokeManager();
        assertCode("WORKSPACE_ACCESS_DENIED", graph(document, Map.of("workspace", workspace, "cursor", cursor)));
    }

    @Test
    void scopeAndEligibilityPrecedeInvalidCommandsForManualAndReconcile() throws Exception {
        String unknown = "missing-" + UUID.randomUUID();
        String foreign = "foreign-" + UUID.randomUUID();
        foreignData = new UsageCaseData(jdbc, transactions);
        foreignData.createWorkspace(foreign, "different-owner");
        String foreignAttempt = "foreign-attempt-" + UUID.randomUUID();
        jdbc.update("""
                insert into provider_attempt(id,workspace_id,provider,endpoint,model,channel,adapter_version,simulation,
                  attempt_coverage,prepared_at,started_at,execution_outcome,usage_presence,capture_completeness,projection_state)
                values (?,?,'FAKE','local','scope','test','1',false,'VERIFIED',now(),now(),'SUCCEEDED','UNKNOWN','UNKNOWN','MISSING')
                """, foreignAttempt, foreign);
        for (String id : List.of(unknown, foreignAttempt)) {
            assertCode("USAGE_ATTEMPT_ACCESS_DENIED",
                    manualForAttempt(id, initialRevision, "bad", "foreign", "-1", "proof", "MANUAL_ESTIMATE", "reason"));
            assertCode("USAGE_ATTEMPT_ACCESS_DENIED", reconcile(id, "bad", "missing", "proof", "reason"));
        }

        for (String outcome : List.of("IN_PROGRESS", "NOT_DISPATCHED")) {
            jdbc.update("""
                    update provider_attempt set execution_outcome=?, started_at=case when ?='NOT_DISPATCHED'
                      then null else now() end, finished_at=null where id=?
                    """, outcome, outcome, attempt);
            assertCode("INVALID_COST_REVISION", manual(initialRevision, "bad", "ineligible", "-1", "proof"));
            assertCode("INVALID_COST_REVISION", reconcile(attempt, "bad", "missing", "proof", "reason"));
        }
        revokeManager();
        assertCode("WORKSPACE_ACCESS_DENIED", manual(initialRevision, "bad", "denied", "-1", "proof"));
        assertCode("WORKSPACE_ACCESS_DENIED", reconcile(attempt, "bad", "missing", "proof", "reason"));
        assertNoAuditedReconciliation();
    }

    @Test
    void reconciliationRejectsMissingCatalogAndBlankEvidenceWithoutWrites() throws Exception {
        assertCode("SOURCE_UNAVAILABLE", reconcile(attempt, SOURCE_VERSION, "missing", "proof", "reason"));
        for (String whitespace : List.of("\u00a0", "\u202f", " \u3000\u00a0 ")) {
            assertCode("INVALID_COST_REVISION", reconcile(attempt, SOURCE_VERSION, "missing", whitespace, "reason"));
            assertCode("INVALID_COST_REVISION", reconcile(attempt, SOURCE_VERSION, "missing", "proof", whitespace));
        }
        assertNoAuditedReconciliation();
        assertEquals(initialRevision, storedCurrent());
    }

    @Test
    void anotherManagerCannotReplayOriginalActorsIdempotencyKey() throws Exception {
        var first = mutationResult(manual(initialRevision, SOURCE_VERSION, "actor-key", "10", "proof"));
        String other = addOtherManager();
        // Build a second caller at the public service boundary without changing the HTTP identity Bean.
        io.github.samzhu.bot.usage.UsageCostRevisionOperations second =
                new io.github.samzhu.bot.usage.internal.pricing.JdbcUsageCostRevisionOperations(
                        jdbc, workspaces, () -> other, clock, ledger, transactions, costs, lane, projection);
        var command = new io.github.samzhu.bot.usage.UsageCostRevisionOperations.ManualCommand(
                workspace, attempt, initialRevision, SOURCE_VERSION, "actor-key", "10",
                "MANUAL_ESTIMATE", "proof", "manual test");
        var conflict = assertThrows(io.github.samzhu.bot.usage.internal.pricing.JdbcUsageCostRevisionOperations.Failure.class,
                () -> second.manual(command));
        assertEquals("CONFLICT", conflict.getMessage());
        assertEquals(first.path("revision").path("id").asText(), storedCurrent());
        assertEquals(1, auditCount());
    }

    @Test
    void costReviewCursorBindsSimulationAndManagerActor() throws Exception {
        String other = addOtherManager();
        String query = """
                query Review($workspace: ID!, $simulation: Boolean!, $cursor: String) {
                  costReviewAttempts(workspaceId: $workspace, simulation: $simulation, first: 1, after: $cursor) {
                    nodes { id } pageInfo { endCursor }
                  }
                }
                """;
        var response = graph(query, Map.of("workspace", workspace, "simulation", false));
        assertFalse(response.has("errors"), response.toString());
        String cursor = response.path("data").path("costReviewAttempts").path("pageInfo").path("endCursor").asText();
        assertFalse(cursor.isBlank());
        assertCode("INVALID_CURSOR", graph(query, Map.of("workspace", workspace, "simulation", true, "cursor", cursor)));

        // Mint and validate with the same signer. Only the authenticated principal changes.
        var actor = new java.util.concurrent.atomic.AtomicReference<>("dev-user");
        var scopedQueries = new io.github.samzhu.bot.usage.internal.UsageCostRevisionQueryService(
                jdbc, workspaces, actor::get, costs);
        var signed = scopedQueries.reviewAttempts(workspace, false, 1, null).pageInfo().endCursor();
        assertFalse(signed.isBlank());
        assertDoesNotThrow(() -> scopedQueries.reviewAttempts(workspace, false, 1, signed));
        actor.set(other);
        var conflict = assertThrows(io.github.samzhu.bot.usage.internal.pricing.JdbcUsageCostRevisionOperations.Failure.class,
                () -> scopedQueries.reviewAttempts(workspace, false, 1, signed));
        assertEquals("INVALID_CURSOR", conflict.getMessage());
    }

    @Test
    void automaticCandidatesPreserveAuditedCurrentAndReuseTheDispatchPriceSnapshot() throws Exception {
        var manual = mutationResult(manual(initialRevision, SOURCE_VERSION, "current", "10", "proof"));
        var current = manual.path("revision").path("id").asText();
        var price = jdbc.queryForObject("select price_snapshot_id from usage_cost_estimate where id=?",
                String.class, current);
        publisher.recompute(attempt);
        assertEquals(current, storedCurrent());

        ledger.append(attempt, new io.github.samzhu.bot.usage.UsageLedger.Snapshot("late", 1, "FINAL",
                "FAKE_RAW", "1", "1", "1", "COMPLETE", "{\"input\":10}"));
        Long latest = jdbc.queryForObject("select projection_version from provider_attempt where id=?", Long.class, attempt);
        publisher.recompute(attempt);
        publisher.recompute(attempt);
        assertEquals(current, storedCurrent());
        assertEquals(latest, jdbc.queryForObject("""
                select last_processed_projection_version from usage_cost_manual_source_checkpoint where attempt_id=?
                """, Long.class, attempt));
        assertEquals(1, jdbc.queryForObject("""
                select count(*) from usage_cost_estimate where attempt_id=? and source_version=?
                    and price_snapshot_id=? and publication_kind='AUTO_CANDIDATE'
                """, Integer.class, attempt, latest, price));
        assertEquals(1, auditCount());
    }

    private String addOtherManager() {
        var other = "other-manager-" + UUID.randomUUID();
        jdbc.update("insert into workspace_member(workspace_id,user_id,role) values (?,?,'MANAGER')", workspace, other);
        return other;
    }

    private JsonNode reconcile(String target, String version, String catalog, String evidence, String reason) throws Exception {
        return graph("""
                mutation Reconcile($input: ReconcileCostInput!) {
                  reconcileCost(input: $input) { outcome }
                }
                """, Map.of("input", Map.of("workspaceId", workspace, "attemptId", target,
                "expectedRevision", initialRevision, "expectedSourceProjectionVersion", version,
                "idempotencyKey", "reconcile", "catalogRevisionId", catalog,
                "evidenceReference", evidence, "reason", reason)));
    }

    private void revokeManager() {
        assertEquals(1, jdbc.update("update workspace_member set active=false where workspace_id=? and user_id='dev-user'", workspace));
    }

    private void assertNoAuditedReconciliation() {
        assertEquals(0, auditCount());
        assertEquals(0, jdbc.queryForObject("""
                select count(*) from usage_price_snapshot where attempt_id=? and origin='AUDITED_RECONCILIATION'
                """, Integer.class, attempt));
    }

    private void assertNoManualRevision() {
        assertEquals(0, auditCount());
        assertEquals(initialRevision, storedCurrent());
    }

    private JsonNode manual(String expectedRevision, String sourceVersion, String key, String amount,
                            String evidence) throws Exception {
        return manual(expectedRevision, sourceVersion, key, amount, evidence, "MANUAL_ESTIMATE", "manual test");
    }

    private JsonNode manual(String expectedRevision, String sourceVersion, String key, String amount,
                            String evidence, String sourceType, String reason) throws Exception {
        return manualForAttempt(attempt, expectedRevision, sourceVersion, key, amount, evidence, sourceType, reason);
    }

    private JsonNode manualForAttempt(String target, String expectedRevision, String sourceVersion, String key,
                                      String amount, String evidence, String sourceType, String reason) throws Exception {
        var input = Map.of(
                "workspaceId", workspace, "attemptId", target, "expectedRevision", expectedRevision,
                "expectedSourceProjectionVersion", sourceVersion, "idempotencyKey", key, "amount", amount,
                "sourceType", sourceType, "evidenceReference", evidence, "reason", reason);
        return graph(MANUAL, Map.of("input", input));
    }

    private JsonNode graph(String query, Map<String, ?> variables) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/graphql"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(
                        Map.of("query", query, "variables", variables))))
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
