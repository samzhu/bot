package io.github.samzhu.bot;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.samzhu.bot.identity.CurrentUserProvider;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.sql.Connection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"bot.pricing.reconcile.initial-delay-ms=3600000", "bot.pricing.reconcile.delay-ms=3600000"})
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class UsageCostRevisionIntegrationTests {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired io.github.samzhu.bot.usage.internal.pricing.PricingPublisher pricingPublisher;
    @Autowired io.github.samzhu.bot.usage.internal.pricing.PricingReconciliation pricingReconciliation;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean io.github.samzhu.bot.usage.UsageLedger ledger;
    @MockitoBean CurrentUserProvider actor;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void setActor() {
        org.mockito.Mockito.when(actor.getCurrentUserId()).thenReturn("revision-manager");
    }

    @Test
    void manualTenToTwelveReplacesCurrentAmountButNeverClearsUnknown() throws Exception {
        Fixture f = fixture();
        JsonNode ten = manual(f, null, f.sourceVersion(), "key-ten", "10", "evidence-10");
        assertFalse(ten.has("errors"), ten.toString());
        String revision10 = ten.path("data").path("manualCostRevision").path("revision").path("id").asText();
        assertEquals("APPLIED", ten.path("data").path("manualCostRevision").path("outcome").asText());
        JsonNode twelve = manual(f, revision10, f.sourceVersion(), "key-twelve", "12", "evidence-12");
        assertFalse(twelve.has("errors"), twelve.toString());
        assertEquals("12", twelve.path("data").path("manualCostRevision").path("revision").path("knownSubtotal").asText());
        assertTrue(twelve.path("data").path("manualCostRevision").path("revision").path("completeAmount").isNull());
        assertEquals("UNKNOWN", twelve.path("data").path("manualCostRevision").path("revision").path("status").asText());
        String revision12=twelve.path("data").path("manualCostRevision").path("revision").path("id").asText();
        assertEquals(revision12,jdbc.queryForObject("select estimate_id from usage_cost_current where attempt_id=?",String.class,f.attemptId()));
        JsonNode oldReplay=manual(f,null,f.sourceVersion(),"key-ten","10","evidence-10");
        assertFalse(oldReplay.has("errors"),oldReplay.toString());
        assertEquals("REPLAYED",oldReplay.path("data").path("manualCostRevision").path("outcome").asText());
        assertEquals(revision10,oldReplay.path("data").path("manualCostRevision").path("revision").path("id").asText());
        assertEquals(revision12,oldReplay.path("data").path("manualCostRevision").path("effectiveCost").path("currentRevision").asText());
        assertEquals(0,new java.math.BigDecimal("12").compareTo(new java.math.BigDecimal(oldReplay.path("data").path("manualCostRevision").path("effectiveCost").path("knownSubtotal").asText())));
        assertEquals(revision12,jdbc.queryForObject("select estimate_id from usage_cost_current where attempt_id=?",String.class,f.attemptId()));
        pricingPublisher.recompute(f.attemptId());
        assertEquals(revision12,jdbc.queryForObject("select estimate_id from usage_cost_current where attempt_id=?",String.class,f.attemptId()));
        assertEquals(f.sourceVersion(),jdbc.queryForObject("select last_processed_projection_version::text from usage_cost_manual_source_checkpoint where attempt_id=?",String.class,f.attemptId()));
        String beforeRestartPointer=jdbc.queryForObject("select estimate_id from usage_cost_current where attempt_id=?",String.class,f.attemptId());
        new io.github.samzhu.bot.usage.internal.pricing.PricingReconciliation(jdbc,pricingPublisher).scanPending();
        assertEquals(beforeRestartPointer,jdbc.queryForObject("select estimate_id from usage_cost_current where attempt_id=?",String.class,f.attemptId()));
        assertEquals(f.sourceVersion(),jdbc.queryForObject("select last_processed_projection_version::text from usage_cost_manual_source_checkpoint where attempt_id=?",String.class,f.attemptId()));
    }

    @Test
    void sameKeySamePayloadReplaysOriginalRevisionAndDifferentPayloadConflicts() throws Exception {
        Fixture f = fixture();
        JsonNode first = manual(f, null, f.sourceVersion(), "same-key", "10", "same-evidence");
        assertFalse(first.has("errors"), first.toString());
        String original = first.path("data").path("manualCostRevision").path("revision").path("id").asText();
        JsonNode replay = manual(f, null, f.sourceVersion(), "same-key", "10", "same-evidence");
        assertFalse(replay.has("errors"), replay.toString());
        assertEquals("REPLAYED", replay.path("data").path("manualCostRevision").path("outcome").asText());
        assertEquals(original, replay.path("data").path("manualCostRevision").path("revision").path("id").asText());
        JsonNode conflict = manual(f, null, f.sourceVersion(), "same-key", "11", "different-evidence");
        assertEquals("CONFLICT", conflict.path("errors").get(0).path("extensions").path("code").asText());
        org.mockito.Mockito.when(actor.getCurrentUserId()).thenReturn("revoked-manager");
        JsonNode denied=manual(f,null,f.sourceVersion(),"same-key","10","same-evidence");
        assertEquals("WORKSPACE_ACCESS_DENIED",denied.path("errors").get(0).path("extensions").path("code").asText());
    }

    @Test
    void staleRevisionOrSourceProjectionVersionConflicts() throws Exception {
        Fixture f = fixture();
        JsonNode first = manual(f, null, f.sourceVersion(), "cas-one", "10", "evidence");
        assertFalse(first.has("errors"), first.toString());
        String current = first.path("data").path("manualCostRevision").path("revision").path("id").asText();
        JsonNode staleRevision = manual(f, null, f.sourceVersion(), "cas-two", "12", "evidence");
        assertEquals("CONFLICT", staleRevision.path("errors").get(0).path("extensions").path("code").asText());
        jdbc.update("update provider_attempt set projection_version=projection_version+1 where id=?", f.attemptId());
        String currentSource=jdbc.queryForObject("select projection_version::text from provider_attempt where id=?",String.class,f.attemptId());
        JsonNode lateReplay=manual(f,null,f.sourceVersion(),"cas-one","10","evidence");
        assertFalse(lateReplay.has("errors"),lateReplay.toString());
        assertEquals("REPLAYED",lateReplay.path("data").path("manualCostRevision").path("outcome").asText());
        assertEquals(current,lateReplay.path("data").path("manualCostRevision").path("revision").path("id").asText());
        assertEquals(currentSource,lateReplay.path("data").path("manualCostRevision").path("effectiveCost").path("sourceProjectionVersion").asText());
        assertTrue(lateReplay.path("data").path("manualCostRevision").path("effectiveCost").path("needsReprice").asBoolean());
        assertTrue(lateReplay.path("data").path("manualCostRevision").path("effectiveCost").path("completeAmount").isNull());
        assertEquals(1,jdbc.queryForObject("select count(*) from usage_cost_revision_audit where attempt_id=? and idempotency_key='cas-one'",Integer.class,f.attemptId()));
        JsonNode staleSource = manual(f, current, f.sourceVersion(), "cas-three", "12", "evidence");
        assertEquals("CONFLICT", staleSource.path("errors").get(0).path("extensions").path("code").asText());
    }

    @Test
    void concurrentManualCommandsWithSameExpectedRevisionOnlyOneApplies() throws Exception {
        Fixture f=fixture();
        CountDownLatch ready=new CountDownLatch(2), start=new CountDownLatch(1);
        ExecutorService pool=Executors.newFixedThreadPool(2);
        try {
            Future<JsonNode> a=pool.submit(()->{ready.countDown();start.await();return manual(f,null,f.sourceVersion(),"concurrent-a","10","evidence-a");});
            Future<JsonNode> b=pool.submit(()->{ready.countDown();start.await();return manual(f,null,f.sourceVersion(),"concurrent-b","12","evidence-b");});
            assertTrue(ready.await(5,TimeUnit.SECONDS)); start.countDown();
            JsonNode ra=a.get(10,TimeUnit.SECONDS), rb=b.get(10,TimeUnit.SECONDS);
            int applied=("APPLIED".equals(ra.path("data").path("manualCostRevision").path("outcome").asText())?1:0)
                    +("APPLIED".equals(rb.path("data").path("manualCostRevision").path("outcome").asText())?1:0);
            assertEquals(1,applied,ra+" / "+rb);
            assertEquals(1,jdbc.queryForObject("select count(*) from usage_cost_revision_audit where attempt_id=?",Integer.class,f.attemptId()));
        } finally { pool.shutdownNow(); }
    }

    @Test
    void costReviewIncludesOldUnknownsPagesStableAndRechecksManager() throws Exception {
        Fixture f=fixture();
        String old="review-old-"+UUID.randomUUID();
        jdbc.update("insert into provider_attempt(id,workspace_id,actor_id,provider,endpoint,model,channel,adapter_version,simulation,attempt_coverage,prepared_at,started_at,finished_at,execution_outcome,usage_presence,capture_completeness,projection_state,projection_error) values (?,?,?,'FAKE','local','m','test','1',false,'UNKNOWN',now()-interval '60 days',now()-interval '60 days',now(),'UNKNOWN','UNKNOWN','UNKNOWN','UNKNOWN','PROCESS_RESTART')",old,f.workspaceId(),"revision-manager");
        String active="review-active-"+UUID.randomUUID();
        jdbc.update("insert into provider_attempt(id,workspace_id,actor_id,provider,endpoint,model,channel,adapter_version,simulation,attempt_coverage,prepared_at,started_at,finished_at,execution_outcome,usage_presence,capture_completeness,projection_state,projection_error) values (?,?,?,'FAKE','local','m','test','1',false,'VERIFIED',now(),now(),now(),'UNKNOWN','ABSENT','COMPLETE','MISSING','NO_USAGE')",active,f.workspaceId(),"revision-manager");
        String inProgress="review-running-"+UUID.randomUUID();
        jdbc.update("insert into provider_attempt(id,workspace_id,actor_id,provider,endpoint,model,channel,adapter_version,simulation,attempt_coverage,prepared_at,started_at,execution_outcome,usage_presence,capture_completeness,projection_state) values (?,?,?,'FAKE','local','m','test','1',false,'VERIFIED',now(),now(),'IN_PROGRESS','PENDING','UNKNOWN','MISSING')",inProgress,f.workspaceId(),"revision-manager");
        String undispatched="review-not-dispatched-"+UUID.randomUUID();
        jdbc.update("insert into provider_attempt(id,workspace_id,actor_id,provider,endpoint,model,channel,adapter_version,simulation,attempt_coverage,prepared_at,execution_outcome,usage_presence,capture_completeness,projection_state) values (?,?,?,'FAKE','local','m','test','1',false,'VERIFIED',now(),'NOT_DISPATCHED','UNKNOWN','UNKNOWN','MISSING')",undispatched,f.workspaceId(),"revision-manager");
        JsonNode first=graph("{ costReviewAttempts(workspaceId:\""+f.workspaceId()+"\",first:20){nodes{id startedAt} pageInfo{hasNextPage endCursor}} }");
        assertFalse(first.has("errors"),first.toString());
        JsonNode nodes=first.path("data").path("costReviewAttempts").path("nodes");
        assertTrue(java.util.stream.StreamSupport.stream(nodes.spliterator(),false).anyMatch(n->old.equals(n.path("id").asText())),"historical unknown must not be cut off at 30 days");
        assertFalse(java.util.stream.StreamSupport.stream(nodes.spliterator(),false).anyMatch(n->inProgress.equals(n.path("id").asText())||undispatched.equals(n.path("id").asText())));
        org.mockito.Mockito.when(actor.getCurrentUserId()).thenReturn("revoked-manager");
        JsonNode denied=graph("{ costReviewAttempts(workspaceId:\""+f.workspaceId()+"\",first:20){nodes{id}} }");
        assertEquals("WORKSPACE_ACCESS_DENIED",denied.path("errors").get(0).path("extensions").path("code").asText());
    }

    @Test
    void costReviewCursorBindsSimulationAndManagerActor() throws Exception {
        Fixture f=fixture();
        for(int i=0;i<2;i++) {
            String id="review-cursor-"+UUID.randomUUID();
            jdbc.update("insert into provider_attempt(id,workspace_id,actor_id,provider,endpoint,model,channel,adapter_version,simulation,attempt_coverage,prepared_at,started_at,finished_at,execution_outcome,usage_presence,capture_completeness,projection_state) values (?,?,?,'FAKE','local','m','test','1',false,'UNKNOWN',now(),now(),now(),'UNKNOWN','UNKNOWN','UNKNOWN','UNKNOWN')",id,f.workspaceId(),"revision-manager");
        }
        JsonNode first=graph("{ costReviewAttempts(workspaceId:\""+f.workspaceId()+"\",simulation:false,first:1){pageInfo{endCursor}} }");
        assertFalse(first.has("errors"),first.toString());
        String cursor=first.path("data").path("costReviewAttempts").path("pageInfo").path("endCursor").asText();
        JsonNode wrongSimulation=graph("{ costReviewAttempts(workspaceId:\""+f.workspaceId()+"\",simulation:true,first:1,after:\""+cursor+"\"){nodes{id}} }");
        assertEquals("INVALID_CURSOR",wrongSimulation.path("errors").get(0).path("extensions").path("code").asText());
        jdbc.update("insert into workspace_member(workspace_id,user_id,role,active) values (?,'revision-manager-2','MANAGER',true) on conflict(workspace_id,user_id) do update set role='MANAGER',active=true",f.workspaceId());
        org.mockito.Mockito.when(actor.getCurrentUserId()).thenReturn("revision-manager-2");
        JsonNode wrongActor=graph("{ costReviewAttempts(workspaceId:\""+f.workspaceId()+"\",simulation:false,first:1,after:\""+cursor+"\"){nodes{id}} }");
        assertEquals("INVALID_CURSOR",wrongActor.path("errors").get(0).path("extensions").path("code").asText());
    }

    @Test
    void costReviewSelectorIncludesEachIncompleteBranchAndExcludesCompleteVerifiedCurrent() throws Exception {
        Fixture f=fixture();
        String missing="review-selector-missing-"+UUID.randomUUID();
        String stale="review-selector-stale-"+UUID.randomUUID();
        String partial="review-selector-partial-"+UUID.randomUUID();
        String unknownCoverage="review-selector-coverage-"+UUID.randomUUID();
        String complete="review-selector-complete-"+UUID.randomUUID();

        insertReviewAttempt(f.workspaceId(),missing,"VERIFIED",11,null,null,null);
        insertReviewAttempt(f.workspaceId(),stale,"VERIFIED",11,10L,"COMPLETE","2");
        insertReviewAttempt(f.workspaceId(),partial,"VERIFIED",11,11L,"PARTIAL","2");
        insertReviewAttempt(f.workspaceId(),unknownCoverage,"UNKNOWN",11,11L,"COMPLETE","2");
        insertReviewAttempt(f.workspaceId(),complete,"VERIFIED",11,11L,"COMPLETE","2");

        JsonNode result=graph("{ costReviewAttempts(workspaceId:\""+f.workspaceId()+"\",simulation:false,first:100){nodes{id}} }");
        assertFalse(result.has("errors"),result.toString());
        var nodes=result.path("data").path("costReviewAttempts").path("nodes");
        java.util.Set<String> ids=new java.util.HashSet<>();
        nodes.forEach(node->ids.add(node.path("id").asText()));
        assertTrue(ids.contains(missing),"attempt without a current estimate must be reviewed");
        assertTrue(ids.contains(stale),"source-version mismatch must be reviewed");
        assertTrue(ids.contains(partial),"non-COMPLETE current estimate must be reviewed");
        assertTrue(ids.contains(unknownCoverage),"unknown dispatch coverage must be reviewed");
        assertFalse(ids.contains(complete),"current COMPLETE estimate with matching source and VERIFIED coverage is not review work");
    }

    @Test
    void invalidManualAndReconcileInputsDoNotMaskUnknownOrCrossWorkspaceAttempt() throws Exception {
        Fixture f=fixture();
        String foreignWorkspace="revision-foreign-"+UUID.randomUUID();
        String foreignAttempt="revision-foreign-attempt-"+UUID.randomUUID();
        jdbc.update("insert into workspace(id,name) values (?,?)",foreignWorkspace,"foreign attempt scope");
        jdbc.update("""
            insert into provider_attempt(id,workspace_id,actor_id,provider,endpoint,model,channel,adapter_version,
              simulation,attempt_coverage,prepared_at,started_at,finished_at,execution_outcome,usage_presence,
              capture_completeness,projection_state)
            values (?,?,'revision-manager','FAKE','local','foreign-model','test','fixture',false,'VERIFIED',now(),now(),now(),
              'SUCCEEDED','UNKNOWN','UNKNOWN','UNKNOWN')
            """,foreignAttempt,foreignWorkspace);
        String unknownAttempt="revision-missing-attempt-"+UUID.randomUUID();

        for(String attemptId:java.util.List.of(unknownAttempt,foreignAttempt)) {
            JsonNode deniedManual=manual(new Fixture(f.workspaceId(),attemptId,"not-a-version"),null,"not-a-version",
                    "invalid-scope-manual-"+UUID.randomUUID(),"-1","evidence");
            assertEquals("USAGE_ATTEMPT_ACCESS_DENIED",deniedManual.path("errors").get(0).path("extensions").path("code").asText(),deniedManual.toString());
            JsonNode deniedReconcile=reconcile(new Fixture(f.workspaceId(),attemptId,"not-a-version"),null,"not-a-version",
                    "invalid-scope-reconcile-"+UUID.randomUUID(),"missing-catalog","evidence","reason");
            assertEquals("USAGE_ATTEMPT_ACCESS_DENIED",deniedReconcile.path("errors").get(0).path("extensions").path("code").asText(),deniedReconcile.toString());
            assertEquals(0,jdbc.queryForObject("select count(*) from usage_cost_revision_audit where attempt_id=?",Integer.class,attemptId));
            assertEquals(0,jdbc.queryForObject("select count(*) from usage_cost_current where attempt_id=?",Integer.class,attemptId));
        }
    }

    @Test
    void lateSourceRevisionCreatesPublicCandidateWithoutReplacingManualCurrent() throws Exception {
        Fixture f=fixture();
        String source="candidate-catalog-"+UUID.randomUUID();
        jdbc.update("insert into pricing_catalog_revision(id,source_reference,source_revision,content_hash) values (?,?,?,?)",
                source,"candidate-test",source,"candidate-hash");
        jdbc.update("insert into pricing_catalog_entry(id,revision_id,provider,endpoint,model,channel,price_revision,effective_from,effective_until,rates) values (?,?, 'FAKE','local','test-model','test','rate-1',now()-interval '10 days',now()+interval '10 days','{\"input\":\"100\",\"output\":\"200\"}'::jsonb)",
                "candidate-entry-"+UUID.randomUUID(),source);
        String firstSnapshot="candidate-snapshot-v1-"+UUID.randomUUID();
        jdbc.update("insert into usage_snapshot(id,attempt_id,event_id,payload_hash,ordinal,semantic,source_layer,schema_version,adapter_version,capture_version,capture_completeness,raw_state,raw_usage,captured_at) values (?,?,?,'hash-v1',1,'FINAL','FAKE_RAW','candidate-v1','1','1','COMPLETE','JSONB','{\"input\":10,\"output\":2}'::jsonb,now())",
                firstSnapshot,f.attemptId(),"event-"+firstSnapshot);
        jdbc.update("update provider_attempt set projection_state='VALID',usage_presence='PRESENT',projection_snapshot_id=?,catalog_marker='CAPTURED',catalog_revision_id=? where id=?",
                firstSnapshot,source,f.attemptId());

        JsonNode applied=manual(f,null,f.sourceVersion(),"candidate-manual-r1","10","manual-v1-evidence");
        assertFalse(applied.has("errors"),applied.toString());
        String manualId=applied.path("data").path("manualCostRevision").path("revision").path("id").asText();
        pricingPublisher.recompute(f.attemptId());
        String fixedPrice=jdbc.queryForObject("select id from usage_price_snapshot where attempt_id=? and origin='AUTO_DISPATCH'",String.class,f.attemptId());
        String firstCheckpoint=jdbc.queryForObject("select last_processed_projection_version::text from usage_cost_manual_source_checkpoint where attempt_id=?",String.class,f.attemptId());
        assertEquals(f.sourceVersion(),firstCheckpoint);

        long v2=Long.parseLong(f.sourceVersion())+1;
        String secondSnapshot="candidate-snapshot-v2-"+UUID.randomUUID();
        jdbc.update("insert into usage_snapshot(id,attempt_id,event_id,payload_hash,ordinal,semantic,source_layer,schema_version,adapter_version,capture_version,capture_completeness,raw_state,raw_usage,captured_at) values (?,?,?,'hash-v2',2,'FINAL','FAKE_RAW','candidate-v2','1','1','COMPLETE','JSONB','{\"input\":12,\"output\":3}'::jsonb,now())",
                secondSnapshot,f.attemptId(),"event-"+secondSnapshot);
        jdbc.update("update provider_attempt set projection_state='VALID',usage_presence='PRESENT',projection_snapshot_id=?,projection_version=? where id=?",
                secondSnapshot,v2,f.attemptId());
        pricingReconciliation.scanPending();

        String current=jdbc.queryForObject("select estimate_id from usage_cost_current where attempt_id=?",String.class,f.attemptId());
        assertEquals(manualId,current);
        assertEquals(Long.toString(v2),jdbc.queryForObject("select last_processed_projection_version::text from usage_cost_manual_source_checkpoint where attempt_id=?",String.class,f.attemptId()));
        assertEquals(1,jdbc.queryForObject("select count(*) from usage_cost_estimate where attempt_id=? and source_version=? and publication_kind='AUTO_CANDIDATE'",Integer.class,f.attemptId(),v2));
        assertEquals(fixedPrice,jdbc.queryForObject("select price_snapshot_id from usage_cost_estimate where attempt_id=? and source_version=? and publication_kind='AUTO_CANDIDATE'",String.class,f.attemptId(),v2));

        JsonNode history=graph("{ costRevisions(workspaceId:\""+f.workspaceId()+"\",attemptId:\""+f.attemptId()+"\",first:100){currentRevisionId nodes{id status sourceProjectionVersion sourceSnapshotId priceSnapshotId current candidate reasons}} }");
        assertFalse(history.has("errors"),history.toString());
        assertEquals(manualId,history.path("data").path("costRevisions").path("currentRevisionId").asText());
        JsonNode candidate=null;
        for(JsonNode node:history.path("data").path("costRevisions").path("nodes"))
            if(Long.toString(v2).equals(node.path("sourceProjectionVersion").asText())) candidate=node;
        assertNotNull(candidate,history.toString());
        assertTrue(candidate.path("candidate").asBoolean());
        assertFalse(candidate.path("current").asBoolean());
        assertEquals(secondSnapshot,candidate.path("sourceSnapshotId").asText());
        assertEquals(fixedPrice,candidate.path("priceSnapshotId").asText());
        assertFalse(candidate.path("reasons").isEmpty(),"candidate must retain calculation reasons for review");

        pricingReconciliation.scanPending();
        assertEquals(manualId,jdbc.queryForObject("select estimate_id from usage_cost_current where attempt_id=?",String.class,f.attemptId()));
        assertEquals(1,jdbc.queryForObject("select count(*) from usage_cost_estimate where attempt_id=? and source_version=? and publication_kind='AUTO_CANDIDATE'",Integer.class,f.attemptId(),v2));
        assertEquals(fixedPrice,jdbc.queryForObject("select price_snapshot_id from usage_cost_estimate where attempt_id=? and source_version=? and publication_kind='AUTO_CANDIDATE'",String.class,f.attemptId(),v2));
    }

    @Test
    void autoCandidateCalculationConflictsWhenOnlyCurrentPointerChanges() throws Exception {
        Fixture f=fixture();
        JsonNode applied=manual(f,null,f.sourceVersion(),"current-race-manual","10","manual evidence");
        assertFalse(applied.has("errors"),applied.toString());
        String manualId=applied.path("data").path("manualCostRevision").path("revision").path("id").asText();
        String alternate="current-race-alt-"+UUID.randomUUID();
        jdbc.update("insert into usage_cost_estimate(id,attempt_id,source_version,price_snapshot_id,calculator_version,status,details,payload_hash) values (?,?,?,null,'current-race','UNKNOWN','{\"reasons\":[],\"lines\":[]}'::jsonb,?)",
                alternate,f.attemptId(),Long.parseLong(f.sourceVersion()),"hash-"+alternate);
        jdbc.update("update usage_cost_current set estimate_id=? where attempt_id=?",alternate,f.attemptId());

        try(Connection lock=jdbc.getDataSource().getConnection()) {
            lock.setAutoCommit(false);
            try(var statement=lock.createStatement()) { statement.execute("lock table usage_price_snapshot in access exclusive mode"); }
            ExecutorService executor=Executors.newSingleThreadExecutor();
            try {
                Future<Boolean> pending=executor.submit(()->pricingPublisher.recompute(f.attemptId()));
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8); boolean waiting=false;
                while(System.nanoTime()<deadline&&!waiting) {
                    Integer count=jdbc.queryForObject("select count(*) from pg_stat_activity where wait_event_type='Lock' and query like '%usage_price_snapshot%' and pid<>pg_backend_pid()",Integer.class);
                    waiting=count!=null&&count>0; if(!waiting) Thread.yield();
                }
                assertTrue(waiting,"pricing calculation did not reach the pinned-price lookup after reading current");
                jdbc.update("update usage_cost_current set estimate_id=? where attempt_id=?",manualId,f.attemptId());
                lock.commit();
                ExecutionException failure=assertThrows(ExecutionException.class,()->pending.get(8,TimeUnit.SECONDS));
                assertInstanceOf(io.github.samzhu.bot.usage.internal.pricing.PricingPublisher.Conflict.class,failure.getCause());
                assertEquals(manualId,jdbc.queryForObject("select estimate_id from usage_cost_current where attempt_id=?",String.class,f.attemptId()));
                assertEquals(0,jdbc.queryForObject("select count(*) from usage_cost_estimate where attempt_id=? and publication_kind='AUTO_CANDIDATE'",Integer.class,f.attemptId()));
                assertEquals(0,jdbc.queryForObject("select count(*) from usage_cost_manual_source_checkpoint where attempt_id=?",Integer.class,f.attemptId()));
            } finally { executor.shutdownNow(); lock.rollback(); }
        }
    }

    @Test
    void reconcilePrecisionUnsupportedRetainsExactAmountAndRawWithoutPublishingZeroComplete() throws Exception {
        String suffix=UUID.randomUUID().toString().substring(0,8);
        String workspace="precision-w-"+suffix, attempt="precision-a-"+suffix;
        String snapshot="precision-s-"+suffix, catalog="precision-c-"+suffix, entry="precision-e-"+suffix;
        String raw="{\"promptTokenCount\":1,\"cachedContentTokenCount\":0,\"candidatesTokenCount\":0,\"thoughtsTokenCount\":0,\"toolUsePromptTokenCount\":0,\"totalTokenCount\":1}";
        jdbc.update("insert into workspace(id,name) values (?,?)",workspace,"precision fixture");
        jdbc.update("insert into workspace_member(workspace_id,user_id,role,active) values (?,'revision-manager','MANAGER',true)",workspace);
        jdbc.update("insert into provider_attempt(id,workspace_id,actor_id,provider,endpoint,model,channel,adapter_version,simulation,attempt_coverage,prepared_at,started_at,finished_at,execution_outcome,usage_presence,capture_completeness,projection_state,projection_version) values (?,?,?,'FAKE_GEMINI','generateContent','fixture-gemini','FAKE','1',true,'VERIFIED',now(),now(),now(),'SUCCEEDED','PRESENT','COMPLETE','VALID',1)",attempt,workspace,"revision-manager");
        jdbc.update("insert into usage_snapshot(id,attempt_id,event_id,payload_hash,ordinal,semantic,source_layer,schema_version,adapter_version,capture_version,capture_completeness,raw_state,raw_usage,captured_at) values (?,?,?,'precision-hash',1,'FINAL','FAKE_RAW','pricing-fixture-v1','1','1','COMPLETE','JSONB',?::jsonb,now())",snapshot,attempt,"event-"+snapshot,raw);
        jdbc.update("update provider_attempt set projection_snapshot_id=? where id=?",snapshot,attempt);
        jdbc.update("insert into pricing_catalog_revision(id,source_reference,source_revision,content_hash) values (?,'precision-test',?,'precision-hash')",catalog,"precision-revision-"+suffix);
        jdbc.update("insert into pricing_catalog_entry(id,revision_id,provider,endpoint,model,channel,price_revision,effective_from,effective_until,rates) values (?,?,'FAKE_GEMINI','generateContent','fixture-gemini','FAKE','tiny-rate',now()-interval '1 day',now()+interval '1 day','{\"input\":\"0.0000000000000000001\",\"cache_read\":\"1\",\"output\":\"1\",\"thoughts\":\"1\",\"tool_input\":\"1\"}'::jsonb)",entry,catalog);

        JsonNode response=reconcile(new Fixture(workspace,attempt,"1"),null,"1","precision-reconcile-"+suffix,catalog,"precision evidence","tiny rate test");
        assertFalse(response.has("errors"),response.toString());
        JsonNode operation=response.path("data").path("reconcileCost");
        JsonNode revision=operation.path("revision");
        assertEquals("PARTIAL",revision.path("status").asText());
        assertTrue(revision.path("reasons").toString().contains("PRECISION_UNSUPPORTED"));
        assertTrue(revision.path("completeAmount").isNull());
        assertTrue(operation.path("effectiveCost").path("completeAmount").isNull());
        assertTrue(operation.path("effectiveCost").path("amount").isNull());

        String estimate=revision.path("id").asText();
        String details=jdbc.queryForObject("select details::text from usage_cost_estimate where id=?",String.class,estimate);
        JsonNode detailTree=json.readTree(details);
        JsonNode inputLine=null;
        for(JsonNode line:detailTree.path("lines")) if("input".equals(line.path("category").asText())) inputLine=line;
        assertNotNull(inputLine,details);
        assertEquals("0.0000000000000000000000001",inputLine.path("exactAmount").asText());
        assertEquals("PRECISION_UNSUPPORTED",inputLine.path("reason").asText());
        assertTrue(inputLine.path("storedAmount").isNull());
        assertNotEquals("COMPLETE",jdbc.queryForObject("select status from usage_cost_estimate where id=?",String.class,estimate));
        assertNull(jdbc.queryForObject("select complete_amount from usage_cost_estimate where id=?",java.math.BigDecimal.class,estimate));
        assertTrue(jdbc.queryForObject("select raw_usage=?::jsonb from usage_snapshot where id=?",Boolean.class,raw,snapshot));
        assertEquals(snapshot,jdbc.queryForObject("select projection_snapshot_id from provider_attempt where id=?",String.class,attempt));
    }

    private void insertReviewAttempt(String workspace,String id,String coverage,long projectionVersion,
                                     Long sourceVersion,String costStatus,String amount) {
        jdbc.update("""
            insert into provider_attempt(id,workspace_id,actor_id,provider,endpoint,model,channel,adapter_version,
              simulation,attempt_coverage,prepared_at,started_at,finished_at,execution_outcome,usage_presence,
              capture_completeness,projection_state,projection_version)
            values (?,?, 'revision-manager','FAKE','local','review-model','test','fixture',false,?,now(),now(),now(),
              'SUCCEEDED','UNKNOWN','UNKNOWN','UNKNOWN',?)
            """,id,workspace,coverage,projectionVersion);
        if(sourceVersion==null) return;

        String suffix=UUID.randomUUID().toString().substring(0,12);
        String price="review-p-"+suffix;
        String estimate="review-e-"+suffix;
        jdbc.update("insert into usage_price_snapshot(id,attempt_id,selection_state,provider,endpoint,model,channel) values (?,?,'MISSING','FAKE','local','review-model','test')",price,id);
        jdbc.update("""
            insert into usage_cost_estimate(id,attempt_id,source_version,price_snapshot_id,calculator_version,status,
              known_subtotal,complete_amount,details,payload_hash)
            values (?,?,?,?,'review-fixture',?,?,?,'{\"reasons\":[],\"lines\":[]}'::jsonb,?)
            """,estimate,id,sourceVersion,price,costStatus,
                amount==null?null:new java.math.BigDecimal(amount),
                "COMPLETE".equals(costStatus)?new java.math.BigDecimal(amount):null,"hash-"+suffix);
        jdbc.update("insert into usage_cost_current(attempt_id,estimate_id) values (?,?)",id,estimate);
    }

    @Test
    void reconcileRejectsMissingRawOrCatalogAsSourceUnavailable() throws Exception {
        Fixture f=fixture();
        JsonNode result=reconcile(f,null,f.sourceVersion(),"missing-source-"+UUID.randomUUID(),"no-such-catalog","catalog-evidence","test reason");
        assertEquals("SOURCE_UNAVAILABLE",result.path("errors").get(0).path("extensions").path("code").asText());
        assertEquals(0,jdbc.queryForObject("select count(*) from usage_cost_revision_audit where attempt_id=?",Integer.class,f.attemptId()));
        JsonNode blank=reconcile(f,null,f.sourceVersion(),"blank-evidence-"+UUID.randomUUID(),"no-such-catalog","\u3000","reason");
        assertEquals("INVALID_COST_REVISION",blank.path("errors").get(0).path("extensions").path("code").asText());
    }

    @Test
    void notDispatchedAttemptCannotBeManuallyCorrected() throws Exception {
        Fixture f=fixture();
        jdbc.update("update provider_attempt set execution_outcome='NOT_DISPATCHED',started_at=null where id=?",f.attemptId());
        JsonNode result=manual(f,null,f.sourceVersion(),"not-dispatched","10","evidence");
        assertEquals("INVALID_COST_REVISION",result.path("errors").get(0).path("extensions").path("code").asText());
        assertEquals(0,jdbc.queryForObject("select count(*) from usage_cost_revision_audit where attempt_id=?",Integer.class,f.attemptId()));
    }

    @Test
    void nonTerminalAndNotDispatchedRejectBeforeContentValidation() throws Exception {
        Fixture f=fixture();
        for (String outcome : java.util.List.of("IN_PROGRESS","NOT_DISPATCHED")) {
            jdbc.update("update provider_attempt set execution_outcome=?,started_at=case when ?='NOT_DISPATCHED' then null else started_at end where id=?",outcome,outcome,f.attemptId());
            JsonNode manual=manual(f,null,f.sourceVersion(),"terminal-manual-"+UUID.randomUUID(),"-1","evidence");
            assertEquals("INVALID_COST_REVISION",manual.path("errors").get(0).path("extensions").path("code").asText(),manual.toString());
            JsonNode reconcile=reconcile(f,null,"bad","terminal-reconcile-"+UUID.randomUUID(),"missing-catalog","evidence","reason");
            assertEquals("INVALID_COST_REVISION",reconcile.path("errors").get(0).path("extensions").path("code").asText(),reconcile.toString());
        }
        assertEquals(0,jdbc.queryForObject("select count(*) from usage_cost_revision_audit where attempt_id=?",Integer.class,f.attemptId()));
        assertEquals(0,jdbc.queryForObject("select count(*) from usage_price_snapshot where attempt_id=?",Integer.class,f.attemptId()));
        assertEquals(0,jdbc.queryForObject("select count(*) from usage_cost_current where attempt_id=?",Integer.class,f.attemptId()));
    }

    @Test
    void manualSourceTypeAndUnicodeWhitespaceEvidenceAreRejected() throws Exception {
        Fixture f=fixture();
        JsonNode source=manual(f,null,f.sourceVersion(),"wrong-source","1","evidence","SERVER_RAW_CATALOG","reason");
        assertEquals("INVALID_COST_REVISION",source.path("errors").get(0).path("extensions").path("code").asText());
        JsonNode blank=manual(f,null,f.sourceVersion(),"unicode-blank","0","\u3000","MANUAL_ESTIMATE","\u3000");
        assertEquals("INVALID_COST_REVISION",blank.path("errors").get(0).path("extensions").path("code").asText());
        assertEquals(0,jdbc.queryForObject("select count(*) from usage_cost_revision_audit where attempt_id=?",Integer.class,f.attemptId()));
    }

    @Test
    void nonBreakingSpaceAloneIsNotEvidenceOrReason() throws Exception {
        Fixture f=fixture();
        for (boolean evidenceBlank : java.util.List.of(true,false)) {
            String evidence=evidenceBlank?"\u00a0":"evidence", reason=evidenceBlank?"reason":"\u00a0";
            JsonNode manual=manual(f,null,f.sourceVersion(),"blank-manual-"+UUID.randomUUID(),"1",evidence,"MANUAL_ESTIMATE",reason);
            assertEquals("INVALID_COST_REVISION",manual.path("errors").get(0).path("extensions").path("code").asText(),manual.toString());
            JsonNode reconcile=reconcile(f,null,f.sourceVersion(),"blank-reconcile-"+UUID.randomUUID(),"catalog",evidence,reason);
            assertEquals("INVALID_COST_REVISION",reconcile.path("errors").get(0).path("extensions").path("code").asText(),reconcile.toString());
        }
        assertEquals(0,jdbc.queryForObject("select count(*) from usage_cost_revision_audit where attempt_id=?",Integer.class,f.attemptId()));
        assertEquals(0,jdbc.queryForObject("select count(*) from usage_price_snapshot where attempt_id=?",Integer.class,f.attemptId()));
        assertEquals(0,jdbc.queryForObject("select count(*) from usage_cost_current where attempt_id=?",Integer.class,f.attemptId()));
    }

    @Test
    void manualRequiresSubstantiveUnicodeEvidenceAndReason() throws Exception {
        Fixture f=fixture();
        for (String blank : java.util.List.of("\u00a0", "\u202f", " \u3000\u00a0\u202f ")) {
            for (boolean evidenceBlank : java.util.List.of(true, false)) {
                JsonNode response=manual(f,null,f.sourceVersion(),"blank-"+UUID.randomUUID(),"1",
                        evidenceBlank?blank:"proof","MANUAL_ESTIMATE",evidenceBlank?"reason":blank);
                assertEquals("INVALID_COST_REVISION",response.path("errors").path(0).path("extensions").path("code").asText(),response.toString());
            }
        }
        assertEquals(0,jdbc.queryForObject("select count(*) from usage_cost_revision_audit where attempt_id=?",Integer.class,f.attemptId()));
        JsonNode valid=manual(f,null,f.sourceVersion(),"unicode-valid","1","\u00a0證據\u202f","MANUAL_ESTIMATE","\u202f理由\u00a0");
        assertFalse(valid.has("errors"),valid.toString());
    }

    @Test
    void terminalOutcomePrecedesContentAndScopeAndAuthorizationPrecedeOutcome() throws Exception {
        Fixture f=fixture();
        for (String outcome : java.util.List.of("IN_PROGRESS", "NOT_DISPATCHED")) {
            jdbc.update("update provider_attempt set execution_outcome=?,started_at=case when ?='NOT_DISPATCHED' then null else now() end,finished_at=null where id=?",outcome,outcome,f.attemptId());
            JsonNode manual=manual(f,null,"bad","bad-terminal-"+outcome,"-1","\u00a0","MANUAL_ESTIMATE","\u202f");
            assertEquals("INVALID_COST_REVISION",manual.path("errors").get(0).path("extensions").path("code").asText(),manual.toString());
            JsonNode reconcile=reconcile(f,null,"bad","bad-terminal-reconcile-"+outcome,"missing","\u00a0","\u202f");
            assertEquals("INVALID_COST_REVISION",reconcile.path("errors").get(0).path("extensions").path("code").asText(),reconcile.toString());
            Fixture missing=new Fixture(f.workspaceId(),"missing-"+UUID.randomUUID(),"bad");
            assertEquals("USAGE_ATTEMPT_ACCESS_DENIED",manual(missing,null,"bad","k","-1","\u00a0").path("errors").get(0).path("extensions").path("code").asText());
            assertEquals("USAGE_ATTEMPT_ACCESS_DENIED",reconcile(missing,null,"bad","k","missing","\u00a0","\u202f").path("errors").get(0).path("extensions").path("code").asText());
            org.mockito.Mockito.when(actor.getCurrentUserId()).thenReturn("revoked-manager");
            assertEquals("WORKSPACE_ACCESS_DENIED",manual(f,null,"bad","k","-1","\u00a0").path("errors").get(0).path("extensions").path("code").asText());
            assertEquals("WORKSPACE_ACCESS_DENIED",reconcile(f,null,"bad","k","missing","\u00a0","\u202f").path("errors").get(0).path("extensions").path("code").asText());
            org.mockito.Mockito.when(actor.getCurrentUserId()).thenReturn("revision-manager");
        }
        assertEquals(0,jdbc.queryForObject("select count(*) from usage_cost_revision_audit where attempt_id=?",Integer.class,f.attemptId()));
        assertEquals(0,jdbc.queryForObject("select count(*) from usage_cost_current where attempt_id=?",Integer.class,f.attemptId()));
        assertEquals(0,jdbc.queryForObject("select count(*) from usage_price_snapshot where attempt_id=?",Integer.class,f.attemptId()));
    }

    @Test
    void reconciliationRequiresSubstantiveUnicodeEvidenceAndReason() throws Exception {
        Fixture f=fixture();
        String snapshot="unicode-snapshot-"+UUID.randomUUID(), catalog="unicode-catalog-"+UUID.randomUUID();
        jdbc.update("update provider_attempt set provider='GOOGLE',endpoint='generateContent',model='gemini-test',channel='GEMINI_API',projection_state='VALID' where id=?",f.attemptId());
        jdbc.update("insert into usage_snapshot(id,attempt_id,event_id,payload_hash,ordinal,semantic,source_layer,schema_version,adapter_version,capture_version,capture_completeness,raw_state,raw_usage,captured_at) values (?,?,?,'hash',1,'FINAL','SDK_NATIVE','google-v1','1','1','COMPLETE','JSONB','{\"promptTokenCount\":10,\"candidatesTokenCount\":4}'::jsonb,now())",snapshot,f.attemptId(),"event-"+snapshot);
        jdbc.update("update provider_attempt set projection_snapshot_id=? where id=?",snapshot,f.attemptId());
        jdbc.update("insert into pricing_catalog_revision(id,source_reference,source_revision,content_hash) values (?,'fixture',?,'hash')",catalog,catalog);
        jdbc.update("insert into pricing_catalog_entry(id,revision_id,provider,endpoint,model,channel,price_revision,effective_from,effective_until,rates) values (?,?,'GOOGLE','generateContent','gemini-test','GEMINI_API','test-price',now()-interval '1 day',now()+interval '1 day','{\"input\":\"1\",\"output\":\"2\"}'::jsonb)","entry-"+UUID.randomUUID(),catalog);
        for (String blank : java.util.List.of("\u00a0", "\u202f", " \u3000\u00a0\u202f ")) {
            for (boolean evidenceBlank : java.util.List.of(true, false)) {
                JsonNode response=reconcile(f,null,f.sourceVersion(),"blank-"+UUID.randomUUID(),catalog,evidenceBlank?blank:"proof",evidenceBlank?"reason":blank);
                assertEquals("INVALID_COST_REVISION",response.path("errors").path(0).path("extensions").path("code").asText(),response.toString());
            }
        }
        assertEquals(0,jdbc.queryForObject("select count(*) from usage_cost_revision_audit where attempt_id=?",Integer.class,f.attemptId()));
        assertEquals(0,jdbc.queryForObject("select count(*) from usage_price_snapshot where attempt_id=?",Integer.class,f.attemptId()));
        JsonNode valid=reconcile(f,null,f.sourceVersion(),"unicode-valid",catalog,"\u00a0證據\u202f","\u202f理由\u00a0");
        assertFalse(valid.has("errors"),valid.toString());
    }

    @Test
    void manualAmountRejectsNegativeOverflowAndExcessScaleButAllowsEvidencedZeroAsUnknown() throws Exception {
        Fixture f=fixture();
        for (String amount : java.util.List.of("-1", "1000000000000000000", "1.0000000000001")) {
            JsonNode rejected=manual(f,null,f.sourceVersion(),"invalid-amount-"+UUID.randomUUID(),amount,"evidence");
            assertEquals("INVALID_AMOUNT",rejected.path("errors").get(0).path("extensions").path("code").asText(),rejected.toString());
        }
        assertEquals(0,jdbc.queryForObject("select count(*) from usage_cost_revision_audit where attempt_id=?",Integer.class,f.attemptId()));
        JsonNode zero=manual(f,null,f.sourceVersion(),"evidenced-zero","0","manager evidence","MANUAL_ESTIMATE","manager reason");
        assertFalse(zero.has("errors"),zero.toString());
        assertEquals("UNKNOWN",zero.path("data").path("manualCostRevision").path("revision").path("status").asText());
        assertTrue(zero.path("data").path("manualCostRevision").path("revision").path("completeAmount").isNull());
    }

    @Test
    void manualRevisionPinsEffectiveSnapshotIdForLaterAudit() throws Exception {
        Fixture f=fixture();
        String snapshot="revision-snapshot-"+UUID.randomUUID();
        jdbc.update("insert into usage_snapshot(id,attempt_id,event_id,payload_hash,ordinal,semantic,source_layer,schema_version,adapter_version,capture_version,capture_completeness,raw_state,raw_usage,captured_at) values (?,?,?,'hash',1,'FINAL','FAKE_RAW','1','1','1','COMPLETE','JSONB','{\"input\":10}'::jsonb,now())",snapshot,f.attemptId(),"event-"+snapshot);
        jdbc.update("update provider_attempt set projection_state='VALID',usage_presence='PRESENT',projection_snapshot_id=? where id=?",snapshot,f.attemptId());
        JsonNode result=manual(f,null,f.sourceVersion(),"snapshot-source","10","evidence");
        assertFalse(result.has("errors"),result.toString());
        String estimate=jdbc.queryForObject("select estimate_id from usage_cost_current where attempt_id=?",String.class,f.attemptId());
        assertEquals(snapshot,jdbc.queryForObject("select source_snapshot_id from usage_cost_estimate where id=?",String.class,estimate));
    }

    @Test
    void revokeManagerWhileMutationWaitsForAttemptLockPreventsAnyWrite() throws Exception {
        Fixture f=fixture();
        try(Connection lock=jdbc.getDataSource().getConnection()) {
            lock.setAutoCommit(false);
            try(var ps=lock.prepareStatement("select id from provider_attempt where id=? for update")) { ps.setString(1,f.attemptId()); ps.executeQuery().close(); }
            ExecutorService executor=Executors.newSingleThreadExecutor();
            try {
                Future<JsonNode> pending=executor.submit(()->manual(f,null,f.sourceVersion(),"revoke-lock","10","evidence"));
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5); boolean waiting=false;
                while(System.nanoTime()<deadline&&!waiting) {
                    Integer count=jdbc.queryForObject("select count(*) from pg_stat_activity where wait_event_type='Lock' and query like '%provider_attempt%'",Integer.class);
                    waiting=count!=null&&count>0; if(!waiting) Thread.yield();
                }
                assertTrue(waiting,"mutation did not reach attempt row lock");
                jdbc.update("update workspace_member set active=false where workspace_id=? and user_id='revision-manager'",f.workspaceId());
                lock.commit();
                JsonNode denied=pending.get(5,TimeUnit.SECONDS);
                assertEquals("WORKSPACE_ACCESS_DENIED",denied.path("errors").get(0).path("extensions").path("code").asText());
                assertEquals(0,jdbc.queryForObject("select count(*) from usage_cost_revision_audit where attempt_id=?",Integer.class,f.attemptId()));
                assertEquals(0,jdbc.queryForObject("select count(*) from usage_cost_current where attempt_id=?",Integer.class,f.attemptId()));
            } finally { executor.shutdownNow(); lock.rollback(); }
        }
    }

    @Test
    void anotherManagerCannotReplayOriginalActorsIdempotencyKey() throws Exception {
        Fixture f=fixture();
        JsonNode applied=manual(f,null,f.sourceVersion(),"actor-key","10","evidence");
        assertFalse(applied.has("errors"),applied.toString());
        jdbc.update("insert into workspace_member(workspace_id,user_id,role,active) values (?,'revision-manager-2','MANAGER',true) on conflict(workspace_id,user_id) do update set role='MANAGER',active=true",f.workspaceId());
        org.mockito.Mockito.when(actor.getCurrentUserId()).thenReturn("revision-manager-2");
        JsonNode replay=manual(f,null,f.sourceVersion(),"actor-key","10","evidence");
        assertEquals("CONFLICT",replay.path("errors").get(0).path("extensions").path("code").asText());
    }

    @Test
    void revisionHistoryRechecksManagerAndRejectsTamperedCursor() throws Exception {
        Fixture f=fixture();
        JsonNode first=manual(f,null,f.sourceVersion(),"history-one","10","evidence");
        assertFalse(first.has("errors"),first.toString());
        String revision=first.path("data").path("manualCostRevision").path("revision").path("id").asText();
        assertFalse(manual(f,revision,f.sourceVersion(),"history-two","12","evidence").has("errors"));
        String current=jdbc.queryForObject("select estimate_id from usage_cost_current where attempt_id=?",String.class,f.attemptId());
        jdbc.update("update provider_attempt set projection_version=projection_version+1 where id=?",f.attemptId());
        JsonNode page=graph("{ costRevisions(workspaceId:\""+f.workspaceId()+"\",attemptId:\""+f.attemptId()+"\",first:1){currentRevisionId nodes{id current} pageInfo{endCursor hasNextPage}} }");
        assertFalse(page.has("errors"),page.toString());
        assertEquals(current,page.path("data").path("costRevisions").path("currentRevisionId").asText());
        assertTrue(page.path("data").path("costRevisions").path("nodes").get(0).path("current").asBoolean());
        JsonNode stale=graph("{ workspaceUsageAttempt(workspaceId:\""+f.workspaceId()+"\",id:\""+f.attemptId()+"\"){cost{status knownSubtotal completeAmount needsReprice}} }");
        assertFalse(stale.has("errors"),stale.toString());
        assertTrue(stale.path("data").path("workspaceUsageAttempt").path("cost").path("completeAmount").isNull());
        assertTrue(stale.path("data").path("workspaceUsageAttempt").path("cost").path("needsReprice").asBoolean());
        String cursor=page.path("data").path("costRevisions").path("pageInfo").path("endCursor").asText();
        String changed=cursor.substring(0,cursor.length()-1)+(cursor.endsWith("A")?"B":"A");
        JsonNode tampered=graph("{ costRevisions(workspaceId:\""+f.workspaceId()+"\",attemptId:\""+f.attemptId()+"\",first:1,after:\""+changed+"\"){nodes{id}} }");
        assertEquals("INVALID_CURSOR",tampered.path("errors").get(0).path("extensions").path("code").asText());
        org.mockito.Mockito.when(actor.getCurrentUserId()).thenReturn("revoked-manager");
        JsonNode denied=graph("{ costRevisions(workspaceId:\""+f.workspaceId()+"\",attemptId:\""+f.attemptId()+"\",first:1,after:\""+cursor+"\"){nodes{id}} }");
        assertEquals("WORKSPACE_ACCESS_DENIED",denied.path("errors").get(0).path("extensions").path("code").asText());
    }

    @Test
    void publishedCatalogPageRequiresCurrentManagerAndRejectsTamperedCursor() throws Exception {
        String suffix=UUID.randomUUID().toString().substring(0,8), workspace="catalog-page-"+suffix;
        jdbc.update("insert into workspace(id,name) values (?,?)",workspace,"catalog page fixture");
        jdbc.update("insert into workspace_member(workspace_id,user_id,role,active) values (?,'revision-manager','MANAGER',true)",workspace);
        for(int i=0;i<2;i++) jdbc.update("insert into pricing_catalog_revision(id,source_reference,source_revision,content_hash) values (?,?,?,?)","cat-"+suffix+"-"+i,"fixture","rev-"+suffix+"-"+i,"hash-"+i);
        JsonNode first=graph("{ publishedPricingCatalogs(workspaceId:\""+workspace+"\",first:1){nodes{id} pageInfo{endCursor hasNextPage}} }");
        assertFalse(first.has("errors"),first.toString());
        String cursor=first.path("data").path("publishedPricingCatalogs").path("pageInfo").path("endCursor").asText();
        String tampered=cursor.substring(0,cursor.length()-1)+(cursor.endsWith("A")?"B":"A");
        JsonNode bad=graph("{ publishedPricingCatalogs(workspaceId:\""+workspace+"\",first:1,after:\""+tampered+"\"){nodes{id}} }");
        assertEquals("INVALID_CURSOR",bad.path("errors").get(0).path("extensions").path("code").asText());
        org.mockito.Mockito.when(actor.getCurrentUserId()).thenReturn("revoked-manager");
        JsonNode denied=graph("{ publishedPricingCatalogs(workspaceId:\""+workspace+"\",first:1,after:\""+cursor+"\"){nodes{id}} }");
        assertEquals("WORKSPACE_ACCESS_DENIED",denied.path("errors").get(0).path("extensions").path("code").asText());
    }

    @Test
    void googleFinalUsageWithUnknownAttemptCoverageRemainsIncomplete() throws Exception {
        String suffix=UUID.randomUUID().toString().substring(0,8), workspace="google-recon-"+suffix, id="google-attempt-"+suffix, snapshot="google-snapshot-"+suffix, catalog="google-catalog-"+suffix;
        jdbc.update("insert into workspace(id,name) values (?,?)",workspace,"Google coverage fixture");
        jdbc.update("insert into workspace_member(workspace_id,user_id,role,active) values (?,'revision-manager','MANAGER',true)",workspace);
        jdbc.update("insert into provider_attempt(id,workspace_id,actor_id,provider,endpoint,model,channel,adapter_version,simulation,attempt_coverage,prepared_at,started_at,finished_at,execution_outcome,usage_presence,capture_completeness,projection_state,projection_version) values (?,?,?,'GOOGLE','generateContent','gemini-test','GEMINI_API','1',false,'UNKNOWN',now(),now(),now(),'SUCCEEDED','PRESENT','COMPLETE','VALID',1)",id,workspace,"revision-manager");
        String raw="{\"promptTokenCount\":10,\"candidatesTokenCount\":4,\"cachedContentTokenCount\":0,\"toolUsePromptTokenCount\":0,\"totalTokenCount\":14}";
        jdbc.update("insert into usage_snapshot(id,attempt_id,event_id,payload_hash,ordinal,semantic,source_layer,schema_version,adapter_version,capture_version,capture_completeness,raw_state,raw_usage,captured_at) values (?,?,?,'hash',1,'FINAL','SDK_NATIVE','google-v1','1','1','COMPLETE','JSONB',?::jsonb,now())",snapshot,id,"event-"+suffix,raw);
        jdbc.update("update provider_attempt set projection_snapshot_id=? where id=?",snapshot,id);
        jdbc.update("insert into pricing_catalog_revision(id,source_reference,source_revision,content_hash) values (?,'fixture',?,'hash')",catalog,"google-rev-"+suffix);
        jdbc.update("insert into pricing_catalog_entry(id,revision_id,provider,endpoint,model,channel,price_revision,effective_from,effective_until,rates) values (?,?, 'GOOGLE','generateContent','gemini-test','GEMINI_API','test-price',now()-interval '1 day',now()+interval '1 day','{\"input\":\"1\",\"output\":\"2\"}'::jsonb)","google-entry-"+suffix,catalog);
        JsonNode result=reconcile(new Fixture(workspace,id,"1"),null,"1","google-reconcile-"+suffix,catalog,"google-evidence","coverage check");
        assertFalse(result.has("errors"),result.toString());
        JsonNode revision=result.path("data").path("reconcileCost").path("revision");
        assertEquals("PARTIAL",revision.path("status").asText());
        assertTrue(revision.path("completeAmount").isNull());
        assertEquals(snapshot,revision.path("sourceSnapshotId").asText());
        assertEquals("10",jdbc.queryForObject("select raw_usage->>'promptTokenCount' from usage_snapshot where id=?",String.class,snapshot));
        String key="google-reconcile-"+suffix;
        String current=jdbc.queryForObject("select estimate_id from usage_cost_current where attempt_id=?",String.class,id);
        JsonNode replay=reconcile(new Fixture(workspace,id,"1"),null,"1",key,catalog,"google-evidence","coverage check");
        assertEquals("REPLAYED",replay.path("data").path("reconcileCost").path("outcome").asText());
        JsonNode conflict=reconcile(new Fixture(workspace,id,"1"),null,"1",key,catalog,"different-evidence","coverage check");
        assertEquals("CONFLICT",conflict.path("errors").get(0).path("extensions").path("code").asText());
        assertEquals(current,jdbc.queryForObject("select estimate_id from usage_cost_current where attempt_id=?",String.class,id));
    }

    @Test
    void reconcileNotDispatchedIsRejectedWithoutAuditOrPriceSnapshot() throws Exception {
        Fixture f=fixture();
        jdbc.update("update provider_attempt set execution_outcome='NOT_DISPATCHED',started_at=null where id=?",f.attemptId());
        JsonNode result=reconcile(f,null,f.sourceVersion(),"nd-reconcile-"+UUID.randomUUID(),"missing-catalog","catalog-evidence","reason");
        assertEquals("INVALID_COST_REVISION",result.path("errors").get(0).path("extensions").path("code").asText());
        assertEquals(0,jdbc.queryForObject("select count(*) from usage_cost_revision_audit where attempt_id=?",Integer.class,f.attemptId()));
        assertEquals(0,jdbc.queryForObject("select count(*) from usage_price_snapshot where attempt_id=? and origin='AUDITED_RECONCILIATION'",Integer.class,f.attemptId()));
    }

    @Test
    void reconcileUnsupportedProviderDoesNotWriteRevisionOrAuditedPrice() throws Exception {
        Fixture f=fixture();
        String snapshot="unsupported-snap-"+UUID.randomUUID(), catalog="unsupported-cat-"+UUID.randomUUID();
        jdbc.update("insert into usage_snapshot(id,attempt_id,event_id,payload_hash,ordinal,semantic,source_layer,schema_version,adapter_version,capture_version,capture_completeness,raw_state,raw_usage,captured_at) values (?,?,?,'hash',1,'FINAL','FAKE_RAW','x','1','1','COMPLETE','JSONB','{\"v\":1}'::jsonb,now())",snapshot,f.attemptId(),"event-"+snapshot);
        jdbc.update("update provider_attempt set provider='UNSUPPORTED_PROVIDER',endpoint='unsupported',channel='unsupported',projection_state='VALID',projection_snapshot_id=? where id=?",snapshot,f.attemptId());
        jdbc.update("insert into pricing_catalog_revision(id,source_reference,source_revision,content_hash) values (?,'test',?,'hash')",catalog,"unsupported-rev-"+UUID.randomUUID());
        jdbc.update("insert into pricing_catalog_entry(id,revision_id,provider,endpoint,model,channel,price_revision,effective_from,effective_until,rates) values (?,?,'UNSUPPORTED_PROVIDER','unsupported','test-model','unsupported','p1',now()-interval '1 day',now()+interval '1 day','{}'::jsonb)","unsupported-entry-"+UUID.randomUUID(),catalog);
        JsonNode result=reconcile(f,null,f.sourceVersion(),"unsupported-key-"+UUID.randomUUID(),catalog,"catalog-evidence","unsupported source");
        assertEquals("UNSUPPORTED_SOURCE",result.path("errors").get(0).path("extensions").path("code").asText());
        assertEquals(0,jdbc.queryForObject("select count(*) from usage_cost_revision_audit where attempt_id=?",Integer.class,f.attemptId()));
        assertEquals(0,jdbc.queryForObject("select count(*) from usage_price_snapshot where attempt_id=? and origin='AUDITED_RECONCILIATION'",Integer.class,f.attemptId()));
    }

    @Test
    void overlappingCatalogEntriesCannotProduceCompleteReconciliation() throws Exception {
        String suffix=UUID.randomUUID().toString().substring(0,8), workspace="overlap-w-"+suffix, id="overlap-a-"+suffix, snap="overlap-s-"+suffix, catalog="overlap-c-"+suffix;
        jdbc.update("insert into workspace(id,name) values (?,?)",workspace,"overlap fixture");
        jdbc.update("insert into workspace_member(workspace_id,user_id,role,active) values (?,'revision-manager','MANAGER',true)",workspace);
        jdbc.update("insert into provider_attempt(id,workspace_id,actor_id,provider,endpoint,model,channel,adapter_version,simulation,attempt_coverage,prepared_at,started_at,finished_at,execution_outcome,usage_presence,capture_completeness,projection_state,projection_version) values (?,?,?,'FAKE_GEMINI','generateContent','fixture-gemini','FAKE','1',true,'VERIFIED',now(),now(),now(),'SUCCEEDED','PRESENT','COMPLETE','VALID',1)",id,workspace,"revision-manager");
        String raw="{\"promptTokenCount\":1000,\"cachedContentTokenCount\":200,\"candidatesTokenCount\":300,\"thoughtsTokenCount\":100,\"toolUsePromptTokenCount\":0,\"totalTokenCount\":1400}";
        jdbc.update("insert into usage_snapshot(id,attempt_id,event_id,payload_hash,ordinal,semantic,source_layer,schema_version,adapter_version,capture_version,capture_completeness,raw_state,raw_usage,captured_at) values (?,?,?,'hash',1,'FINAL','FAKE_RAW','pricing-fixture-v1','1','1','COMPLETE','JSONB',?::jsonb,now())",snap,id,"event-"+suffix,raw);
        jdbc.update("update provider_attempt set projection_snapshot_id=? where id=?",snap,id);
        jdbc.update("insert into pricing_catalog_revision(id,source_reference,source_revision,content_hash) values (?,'fixture',?,'hash')",catalog,"overlap-rev-"+suffix);
        for(int i=0;i<2;i++) jdbc.update("insert into pricing_catalog_entry(id,revision_id,provider,endpoint,model,channel,price_revision,effective_from,effective_until,rates) values (?,?, 'FAKE_GEMINI','generateContent','fixture-gemini','FAKE',?,now()-interval '1 day',now()+interval '1 day','{\"input\":\"1\",\"cache_read\":\"0.1\",\"output\":\"2\",\"thoughts\":\"2\",\"tool_input\":\"1\"}'::jsonb)","overlap-entry-"+i+"-"+suffix,catalog,"price-"+i);
        JsonNode result=reconcile(new Fixture(workspace,id,"1"),null,"1","overlap-key-"+suffix,catalog,"overlap evidence","overlap check");
        assertFalse(result.has("errors"),result.toString());
        JsonNode revision=result.path("data").path("reconcileCost").path("revision");
        assertNotEquals("COMPLETE",revision.path("status").asText());
        assertTrue(revision.path("completeAmount").isNull());
        assertTrue(revision.path("reasons").toString().contains("OVERLAPPING_PRICE"));
    }

    @Test
    void lateProjectionVersionDuringReconcileCalculationConflictsWithoutWrites() throws Exception {
        String suffix=UUID.randomUUID().toString().substring(0,8), workspace="cas-w-"+suffix, id="cas-a-"+suffix, snap="cas-s-"+suffix, catalog="cas-c-"+suffix;
        jdbc.update("insert into workspace(id,name) values (?,?)",workspace,"CAS fixture");
        jdbc.update("insert into workspace_member(workspace_id,user_id,role,active) values (?,'revision-manager','MANAGER',true)",workspace);
        jdbc.update("insert into provider_attempt(id,workspace_id,actor_id,provider,endpoint,model,channel,adapter_version,simulation,attempt_coverage,prepared_at,started_at,finished_at,execution_outcome,usage_presence,capture_completeness,projection_state,projection_version) values (?,?,?,'FAKE_GEMINI','generateContent','fixture-gemini','FAKE','1',true,'VERIFIED',now(),now(),now(),'SUCCEEDED','PRESENT','COMPLETE','VALID',1)",id,workspace,"revision-manager");
        String raw="{\"promptTokenCount\":1000,\"cachedContentTokenCount\":200,\"candidatesTokenCount\":300,\"thoughtsTokenCount\":100,\"toolUsePromptTokenCount\":0,\"totalTokenCount\":1400}";
        jdbc.update("insert into usage_snapshot(id,attempt_id,event_id,payload_hash,ordinal,semantic,source_layer,schema_version,adapter_version,capture_version,capture_completeness,raw_state,raw_usage,captured_at) values (?,?,?,'hash',1,'FINAL','FAKE_RAW','pricing-fixture-v1','1','1','COMPLETE','JSONB',?::jsonb,now())",snap,id,"event-"+suffix,raw);
        jdbc.update("update provider_attempt set projection_snapshot_id=? where id=?",snap,id);
        jdbc.update("insert into pricing_catalog_revision(id,source_reference,source_revision,content_hash) values (?,'fixture',?,'hash')",catalog,"cas-rev-"+suffix);
        jdbc.update("insert into pricing_catalog_entry(id,revision_id,provider,endpoint,model,channel,price_revision,effective_from,effective_until,rates) values (?,?,'FAKE_GEMINI','generateContent','fixture-gemini','FAKE','test-price',now()-interval '1 day',now()+interval '1 day','{\"input\":\"1\",\"cache_read\":\"0.1\",\"output\":\"2\",\"thoughts\":\"2\",\"tool_input\":\"1\"}'::jsonb)","cas-entry-"+suffix,catalog);
        java.util.concurrent.CountDownLatch sampled=new java.util.concurrent.CountDownLatch(1), release=new java.util.concurrent.CountDownLatch(1);
        org.mockito.Mockito.doAnswer(invocation->{var view=(io.github.samzhu.bot.usage.UsageLedger.PricingInputView)invocation.callRealMethod();sampled.countDown();if(!release.await(5,TimeUnit.SECONDS))throw new AssertionError("release timeout");return view;}).when(ledger).readPricingInput(id);
        ExecutorService executor=Executors.newSingleThreadExecutor();
        try {
            Future<JsonNode> pending=executor.submit(()->reconcile(new Fixture(workspace,id,"1"),null,"1","cas-key-"+suffix,catalog,"CAS evidence","late source"));
            assertTrue(sampled.await(5,TimeUnit.SECONDS),"reconcile did not sample usage");
            jdbc.update("update provider_attempt set projection_version=projection_version+1 where id=?",id);
            String lateVersion=jdbc.queryForObject("select projection_version::text from provider_attempt where id=?",String.class,id);
            JsonNode manualCurrent=manual(new Fixture(workspace,id,lateVersion),null,lateVersion,"manual-during-reconcile-"+suffix,"12","late source evidence");
            assertFalse(manualCurrent.has("errors"),manualCurrent.toString());
            String manualPointer=jdbc.queryForObject("select estimate_id from usage_cost_current where attempt_id=?",String.class,id);
            release.countDown();
            JsonNode result=pending.get(5,TimeUnit.SECONDS);
            assertEquals("CONFLICT",result.path("errors").get(0).path("extensions").path("code").asText());
            assertEquals(1,jdbc.queryForObject("select count(*) from usage_cost_revision_audit where attempt_id=?",Integer.class,id));
            assertEquals(manualPointer,jdbc.queryForObject("select estimate_id from usage_cost_current where attempt_id=?",String.class,id));
            assertEquals(0,jdbc.queryForObject("select count(*) from usage_price_snapshot where attempt_id=? and origin='AUDITED_RECONCILIATION'",Integer.class,id));
        } finally { release.countDown(); executor.shutdownNow(); org.mockito.Mockito.reset(ledger); }
    }

    @Test
    void currentRevisionChangeAloneDuringReconcileCalculationConflicts() throws Exception {
        String suffix=UUID.randomUUID().toString().substring(0,8), workspace="current-cas-w-"+suffix, id="current-cas-a-"+suffix, snap="current-cas-s-"+suffix, catalog="current-cas-c-"+suffix;
        jdbc.update("insert into workspace(id,name) values (?,?)",workspace,"current CAS fixture");
        jdbc.update("insert into workspace_member(workspace_id,user_id,role,active) values (?,'revision-manager','MANAGER',true)",workspace);
        jdbc.update("insert into provider_attempt(id,workspace_id,actor_id,provider,endpoint,model,channel,adapter_version,simulation,attempt_coverage,prepared_at,started_at,finished_at,execution_outcome,usage_presence,capture_completeness,projection_state,projection_version) values (?,?,?,'FAKE_GEMINI','generateContent','fixture-gemini','FAKE','1',true,'VERIFIED',now(),now(),now(),'SUCCEEDED','PRESENT','COMPLETE','VALID',1)",id,workspace,"revision-manager");
        String raw="{\"promptTokenCount\":1000,\"cachedContentTokenCount\":200,\"candidatesTokenCount\":300,\"thoughtsTokenCount\":100,\"toolUsePromptTokenCount\":0,\"totalTokenCount\":1400}";
        jdbc.update("insert into usage_snapshot(id,attempt_id,event_id,payload_hash,ordinal,semantic,source_layer,schema_version,adapter_version,capture_version,capture_completeness,raw_state,raw_usage,captured_at) values (?,?,?,'hash',1,'FINAL','FAKE_RAW','pricing-fixture-v1','1','1','COMPLETE','JSONB',?::jsonb,now())",snap,id,"event-"+suffix,raw);
        jdbc.update("update provider_attempt set projection_snapshot_id=? where id=?",snap,id);
        jdbc.update("insert into pricing_catalog_revision(id,source_reference,source_revision,content_hash) values (?,'fixture',?,'hash')",catalog,"current-cas-rev-"+suffix);
        jdbc.update("insert into pricing_catalog_entry(id,revision_id,provider,endpoint,model,channel,price_revision,effective_from,effective_until,rates) values (?,?,'FAKE_GEMINI','generateContent','fixture-gemini','FAKE','test-price',now()-interval '1 day',now()+interval '1 day','{\"input\":\"1\",\"cache_read\":\"0.1\",\"output\":\"2\",\"thoughts\":\"2\",\"tool_input\":\"1\"}'::jsonb)","current-cas-entry-"+suffix,catalog);
        CountDownLatch sampled=new CountDownLatch(1), release=new CountDownLatch(1);
        org.mockito.Mockito.doAnswer(invocation->{var view=(io.github.samzhu.bot.usage.UsageLedger.PricingInputView)invocation.callRealMethod();sampled.countDown();if(!release.await(5,TimeUnit.SECONDS))throw new AssertionError("release timeout");return view;}).when(ledger).readPricingInput(id);
        ExecutorService executor=Executors.newSingleThreadExecutor();
        try {
            Future<JsonNode> pending=executor.submit(()->reconcile(new Fixture(workspace,id,"1"),null,"1","current-cas-key-"+suffix,catalog,"evidence","current race"));
            assertTrue(sampled.await(5,TimeUnit.SECONDS));
            assertEquals("1",jdbc.queryForObject("select projection_version::text from provider_attempt where id=?",String.class,id));
            JsonNode manual=manual(new Fixture(workspace,id,"1"),null,"1","current-racer-"+suffix,"12","manual evidence");
            assertFalse(manual.has("errors"),manual.toString());
            String pointer=jdbc.queryForObject("select estimate_id from usage_cost_current where attempt_id=?",String.class,id);
            release.countDown();
            JsonNode result=pending.get(5,TimeUnit.SECONDS);
            assertEquals("CONFLICT",result.path("errors").get(0).path("extensions").path("code").asText());
            assertEquals("1",jdbc.queryForObject("select projection_version::text from provider_attempt where id=?",String.class,id));
            assertEquals(pointer,jdbc.queryForObject("select estimate_id from usage_cost_current where attempt_id=?",String.class,id));
            assertEquals(1,jdbc.queryForObject("select count(*) from usage_cost_revision_audit where attempt_id=?",Integer.class,id));
        } finally { release.countDown(); executor.shutdownNow(); org.mockito.Mockito.reset(ledger); }
    }

    private JsonNode graph(String query) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/graphql")).header("Content-Type","application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("query",query)))).build();
        return json.readTree(http.send(request,HttpResponse.BodyHandlers.ofString()).body());
    }

    private JsonNode reconcile(Fixture f,String expectedRevision,String sourceVersion,String key,String catalogRevision,String evidenceReference,String reason) throws Exception {
        String expected=expectedRevision==null?"null":"\""+expectedRevision+"\"";
        String query="mutation { reconcileCost(input:{workspaceId:\""+f.workspaceId()+"\",attemptId:\""+f.attemptId()+"\",expectedRevision:"+expected
            +",expectedSourceProjectionVersion:\""+sourceVersion+"\",idempotencyKey:\""+key+"\",catalogRevisionId:\""+catalogRevision
            +"\",evidenceReference:\""+evidenceReference+"\",reason:\""+reason+"\"}) { outcome revision{id attemptId kind status knownSubtotal completeAmount sourceProjectionVersion sourceSnapshotId priceSnapshotId catalogRevisionId sourceType evidenceReference reason actorId recordedAt reasons} effectiveCost{currentRevision sourceProjectionVersion knownSubtotal completeAmount amount status needsReprice} } }";
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/graphql")).header("Content-Type","application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("query",query)))).build();
        return json.readTree(http.send(request,HttpResponse.BodyHandlers.ofString()).body());
    }

    private Fixture fixture() {
        String workspace = "revision-workspace";
        jdbc.update("insert into workspace(id,name) values (?,?) on conflict do nothing", workspace, "Revision test");
        jdbc.update("insert into workspace_member(workspace_id,user_id,role,active) values (?,'revision-manager','MANAGER',true) on conflict(workspace_id,user_id) do update set role='MANAGER',active=true", workspace);
        String id = "revision-attempt-" + UUID.randomUUID();
        jdbc.update("""
            insert into provider_attempt(id,workspace_id,actor_id,provider,endpoint,model,channel,adapter_version,
              simulation,attempt_coverage,prepared_at,started_at,finished_at,execution_outcome,execution_reason,
              usage_presence,capture_completeness,projection_state,projection_error)
            values (?,?,?,'FAKE','local','test-model','test','test',false,'VERIFIED',now(),now(),now(),
              'UNKNOWN','CAPTURE_FAILED','UNKNOWN','UNKNOWN','UNKNOWN','CAPTURE_FAILED')
            """, id, workspace, "revision-manager");
        long version = jdbc.queryForObject("select projection_version from provider_attempt where id=?", Long.class, id);
        return new Fixture(workspace, id, Long.toString(version));
    }

    private JsonNode manual(Fixture f, String expectedRevision, String expectedSourceVersion,
                            String key, String amount, String evidence) throws Exception {
        return manual(f, expectedRevision, expectedSourceVersion, key, amount, evidence, "MANUAL_ESTIMATE", "manual test");
    }

    private JsonNode manual(Fixture f, String expectedRevision, String expectedSourceVersion,
                            String key, String amount, String evidence, String sourceType, String reason) throws Exception {
        String expected = expectedRevision == null ? "null" : "\"" + expectedRevision + "\"";
        String query = "mutation { manualCostRevision(input:{workspaceId:\"" + f.workspaceId()
            + "\",attemptId:\"" + f.attemptId() + "\",expectedRevision:" + expected
            + ",expectedSourceProjectionVersion:\"" + expectedSourceVersion + "\",idempotencyKey:\"" + key
            + "\",amount:\"" + amount + "\",sourceType:\"" + sourceType + "\",evidenceReference:\"" + evidence
            + "\",reason:\"" + reason + "\"}) { outcome revision{id attemptId kind status knownSubtotal completeAmount sourceProjectionVersion sourceType evidenceReference reason actorId recordedAt} effectiveCost{currentRevision sourceProjectionVersion knownSubtotal completeAmount amount status needsReprice} } }";
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/graphql"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("query", query))))
            .build();
        return json.readTree(http.send(request, HttpResponse.BodyHandlers.ofString()).body());
    }

    private record Fixture(String workspaceId, String attemptId, String sourceVersion) {}
}
