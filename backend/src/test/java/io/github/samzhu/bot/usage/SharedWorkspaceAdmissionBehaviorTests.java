package io.github.samzhu.bot;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.samzhu.bot.conversation.ConversationOperations;
import io.github.samzhu.bot.usage.MemberWeeklyBudgetOperations;
import io.github.samzhu.bot.usage.UsageCostRevisionOperations;
import io.github.samzhu.bot.usage.UsageLedger;
import io.github.samzhu.bot.usage.SyntheticRunOwner;
import io.github.samzhu.bot.usage.WorkspaceAdmission;
import io.github.samzhu.bot.usage.WorkspaceMonthlyBudgetOperations;
import io.github.samzhu.bot.usage.pricing.UsageCaseData;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import static io.github.samzhu.bot.usage.WorkspaceAdmission.Partition.REAL;
import static io.github.samzhu.bot.usage.WorkspaceAdmission.Partition.SIMULATION;
import static org.junit.jupiter.api.Assertions.*;

@BackendIntegrationTest
class SharedWorkspaceAdmissionBehaviorTests {
    private static final String ACTOR = "dev-user";

    @LocalServerPort int port;
    private final ObjectMapper json = new ObjectMapper();
    @Autowired WorkspaceAdmission admission;
    @Autowired WorkspaceMonthlyBudgetOperations budgets;
    @Autowired MemberWeeklyBudgetOperations weekly;
    @Autowired UsageCostRevisionOperations revisions;
    @Autowired UsageLedger ledger;
    @Autowired ConversationOperations conversations;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate transactions;
    @Autowired Clock clock;

    private final String workspace = "admission-behavior-" + UUID.randomUUID();
    private UsageCaseData data;
    private String conversation;

    @BeforeEach
    void createWorkspace() {
        data = new UsageCaseData(jdbc, transactions);
        data.createWorkspace(workspace, ACTOR);
        conversation = conversations.createConversation("bot-general", "Admission fixture", ACTOR, workspace).id();
    }

    @AfterEach
    void cleanUp() throws InterruptedException {
        // The facade calls in this suite never start an execution worker. Only these synthetic
        // pending registrations/Run rows are retired before ordinary case cleanup.
        transactions.executeWithoutResult(status -> {
            jdbc.update("delete from execution_budget_cancellation_receipt where workspace_id=?", workspace);
            jdbc.update("""
                    delete from workspace_admission_superseded where registration_token in
                      (select token from workspace_admission_registration where workspace_id=?)
                    """, workspace);
            jdbc.update("delete from workspace_admission_effect_outbox where target_workspace_id=?", workspace);
            jdbc.update("update workspace_admission_registration set status='TERMINAL' where workspace_id=?", workspace);
            jdbc.update("update chat_run set status='CANCELLED' where conversation_id=? and status='PENDING'",
                    conversation);
            jdbc.update("delete from member_weekly_budget_command where workspace_id=?", workspace);
            jdbc.update("delete from member_weekly_budget where workspace_id=?", workspace);
        });
        data.cleanUp();
    }

    @Test
    void authorizedRegistrationIsDurableAndBindsItsScope() {
        String run = UUID.randomUUID().toString();
        var registration = assertInstanceOf(WorkspaceAdmission.RegisterResult.Registered.class,
                register(run, REAL)).registration();
        assertEquals(workspace, registration.workspaceId());
        assertEquals(run, registration.runId());
        assertEquals(conversation, registration.conversationId());
        assertEquals(ACTOR, registration.actorId());
        assertEquals(REAL, registration.partition());
        assertEquals("REGISTERED", jdbc.queryForObject("""
                select status from workspace_admission_registration where token=? and workspace_id=?
                 and actor_id=? and run_id=? and conversation_id=? and partition='REAL'
                """, String.class, registration.token(), workspace, ACTOR, run, conversation));
        var forged = new WorkspaceAdmission.Registration(registration.token(), workspace, run,
                "outsider", conversation, REAL);
        assertEquals(WorkspaceAdmission.CancellationState.UNKNOWN,
                admission.cancellationState(forged).toCompletableFuture().join().state());
    }

    @Test
    void publishedRegistrationClaimsPreparedAttemptBeforeProviderStart() {
        var run = UUID.randomUUID().toString();
        var registration = assertInstanceOf(WorkspaceAdmission.RegisterResult.Registered.class,
                register(run, REAL)).registration();
        jdbc.update("""
                insert into chat_run(id,conversation_id,client_request_id,input_text,status)
                values (?,?,?,'fixture','PENDING')
                """, run, conversation, UUID.randomUUID().toString());
        var owner = SyntheticRunOwner.attach(jdbc, registration);
        assertInstanceOf(WorkspaceAdmission.PublicationResult.Ready.class, admission.publish(registration).toCompletableFuture().join());
        var attempt = ledger.begin(new UsageLedger.Dispatch(run, workspace, ACTOR,
                "FAKE", "test", "model", "FAKE", "1", false, "UNKNOWN"), owner);
        var claim = assertInstanceOf(WorkspaceAdmission.Decision.Allowed.class,
                admission.dispatch(new WorkspaceAdmission.Dispatch(registration, attempt, owner)).toCompletableFuture().join()).claim();
        try {
            assertEquals(attempt, claim.attemptId());
            assertEquals(claim, assertInstanceOf(WorkspaceAdmission.ClaimInspection.Found.class,
                    admission.inspectClaim(registration, attempt).toCompletableFuture().join()).claim());
            assertNotNull(jdbc.queryForObject("select started_at from provider_attempt where id=?",
                    java.sql.Timestamp.class, attempt));
            assertInstanceOf(WorkspaceAdmission.ProviderStartResult.Ready.class,
                    admission.authorizeProviderStart(claim).toCompletableFuture().join());
            weekly.configure(new MemberWeeklyBudgetOperations.Configure(workspace, ACTOR, REAL, ACTOR,
                    true, "0", "0", "weekly-cap")).toCompletableFuture().join();
            var newRun = assertInstanceOf(WorkspaceAdmission.RegisterResult.Denied.class,
                    register(UUID.randomUUID().toString(), REAL));
            assertEquals(WorkspaceAdmission.Reason.WEEK_CAP, newRun.reason());
            assertInstanceOf(WorkspaceAdmission.ProviderStartResult.Ready.class,
                    admission.authorizeProviderStart(claim).toCompletableFuture().join());
            var cancelled = admission.cancelRun(registration).toCompletableFuture().join();
            assertEquals(WorkspaceAdmission.CancelStatus.AFTER_CLAIM, cancelled.status());
            assertEquals(java.util.List.of(claim), cancelled.claimedTargets());
            assertInstanceOf(WorkspaceAdmission.ProviderStartResult.CancelPending.class,
                    admission.authorizeProviderStart(claim).toCompletableFuture().join());
        } finally {
            var finish = new WorkspaceAdmission.FinalizeAttempt(registration, attempt, claim,
                    WorkspaceAdmission.TerminalKind.UNKNOWN, null, owner);
            assertEquals(WorkspaceAdmission.FinalizationResult.SETTLED, admission.finalizeAttempt(finish).toCompletableFuture().join());
            String version = sourceVersion(attempt);
            assertEquals(WorkspaceAdmission.FinalizationResult.SETTLED, admission.finalizeAttempt(finish).toCompletableFuture().join());
            assertEquals(version, sourceVersion(attempt));
            jdbc.update("update chat_run set status='CANCELLED' where id=?", run);
            admission.complete(registration).toCompletableFuture().join();
            assertEquals("TERMINAL", jdbc.queryForObject("select status from workspace_admission_registration where token=?",
                    String.class, registration.token()));
        }
    }

    @Test
    void concurrentIdenticalZeroCapCommandsCreateOneEffect() throws Exception {
        String run = UUID.randomUUID().toString();
        var pending = assertInstanceOf(WorkspaceAdmission.RegisterResult.Registered.class,
                register(run, REAL)).registration();
        jdbc.update("""
                insert into chat_run(id,conversation_id,client_request_id,input_text,status)
                values (?,?,?,'fixture','PENDING')
                """, run, conversation, UUID.randomUUID().toString());
        assertInstanceOf(WorkspaceAdmission.PublicationResult.Ready.class, admission.publish(pending).toCompletableFuture().join());

        var activePeriod = scope(REAL);
        try (var requests = Executors.newFixedThreadPool(2)) {
            var first = requests.submit(() -> configureZero(activePeriod, "0"));
            var second = requests.submit(() -> configureZero(activePeriod, "0.00"));
            var outcomes = java.util.Set.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
            assertEquals(java.util.Set.of(WorkspaceMonthlyBudgetOperations.ConfigureOutcome.APPLIED,
                    WorkspaceMonthlyBudgetOperations.ConfigureOutcome.REPLAYED), outcomes);
        }
        assertEquals(1L, jdbc.queryForObject("select config_version from workspace_monthly_budget where workspace_id=? and partition='REAL'",
                Long.class, workspace));
        assertEquals(1, jdbc.queryForObject("select count(*) from workspace_monthly_budget_command where workspace_id=?",
                Integer.class, workspace));
        assertEquals(1, jdbc.queryForObject("select count(*) from workspace_admission_effect_outbox where registration_token=?",
                Integer.class, pending.token()));
    }

    @Test
    void monthlyUnknownAndCapBothPrecedeWeeklyCap() {
        String model = "budget-fixture";
        String attempt = seedUnknown(model);
        weekly.configure(new MemberWeeklyBudgetOperations.Configure(workspace, ACTOR, SIMULATION, ACTOR,
                true, "0", "0", "priority")).toCompletableFuture().join();
        configure(new BigDecimal("10"), 0);
        assertEquals(WorkspaceAdmission.Reason.MONTH_UNKNOWN, denial());
        configure(BigDecimal.ZERO, 1);
        assertEquals(WorkspaceAdmission.Reason.MONTH_CAP, denial());
        assertEquals(0, jdbc.queryForObject("select count(*) from workspace_admission_effect_outbox where target_attempt_id=?",
                Integer.class, attempt));
    }

    @Test
    void unknownCostDenialClearsAfterAuditedPricingWithoutResettingPolicy() throws Exception {
        String model = "budget-fixture";
        String attempt = seedUnknown(model);
        configure(BigDecimal.TEN, 0);
        assertEquals(WorkspaceAdmission.Reason.MONTH_UNKNOWN, denial());

        var budgetScope = new WorkspaceMonthlyBudgetOperations.ReadCommand(scope(SIMULATION), ACTOR);
        var blocked = budgets.read(budgetScope).toCompletableFuture().join();
        assertTrue(blocked.terminalEffectiveUnknown());
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            assertEquals("1", reportUnknownCount(client, ""));
            assertEquals("0", reportUnknownCount(client, ", model: \"unmatched-filter\""));
        }
        assertEquals(blocked, budgets.read(budgetScope).toCompletableFuture().join());
        assertEquals(WorkspaceAdmission.Reason.MONTH_UNKNOWN, denial());

        attachUsageEvidence(attempt);
        String catalog = catalog(model, """
                {"input":"1","cache_read":"0.1","output":"2","thoughts":"2","tool_input":"1"}
                """);
        var reconciled = revisions.reconcile(new UsageCostRevisionOperations.ReconcileCommand(
                workspace, attempt, null, sourceVersion(attempt), "resolve", catalog, "tracked invoice evidence", "usage arrived"));
        assertEquals("COMPLETE", reconciled.revision().status());
        assertEquals("0.001620000000", reconciled.revision().completeAmount());
        var budget = budgets.read(new WorkspaceMonthlyBudgetOperations.ReadCommand(scope(SIMULATION), ACTOR)).toCompletableFuture().join();
        assertTrue(budget.enabled());
        assertFalse(budget.terminalEffectiveUnknown());
        assertEquals(1, budget.configVersion());
        assertInstanceOf(WorkspaceAdmission.RegisterResult.Registered.class, register(UUID.randomUUID().toString(), SIMULATION));
    }

    private String reportUnknownCount(HttpClient client, String filter) throws Exception {
        String query = """
                { workspaceUsageReport(workspaceId:"%s",periodDays:7,simulation:true%s) {
                    summary { unknownCount }
                  }
                }
                """.formatted(workspace, filter);
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/graphql"))
                .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("query", query))))
                .build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        var envelope = json.readTree(response.body());
        assertFalse(envelope.has("errors"), envelope.toString());
        return envelope.path("data").path("workspaceUsageReport").path("summary").path("unknownCount").asText();
    }

    @Test
    void budgetConfigurationPersistsAcrossMonthsButPeriodTotalsAreSeparate() {
        var firstScope = new WorkspaceMonthlyBudgetOperations.Scope(workspace, REAL, YearMonth.of(2026, 11));
        var nextScope = new WorkspaceMonthlyBudgetOperations.Scope(workspace, REAL, YearMonth.of(2026, 12));
        budgets.configure(new WorkspaceMonthlyBudgetOperations.ConfigureCommand(
                firstScope, ACTOR, true, new BigDecimal("12.5"), 0)).toCompletableFuture().join();
        assertEquals(1, jdbc.update("""
                update workspace_monthly_budget_period set known_subtotal_usd=3,terminal_effective_unknown=true
                where workspace_id=? and partition='REAL' and period_start=?
                """, workspace, firstScope.period().atDay(1)));
        var first = budgets.read(new WorkspaceMonthlyBudgetOperations.ReadCommand(firstScope, ACTOR))
                .toCompletableFuture().join();
        assertEquals(0, new BigDecimal("3").compareTo(first.knownSubtotalUsd()));
        assertTrue(first.terminalEffectiveUnknown());
        var next = budgets.read(new WorkspaceMonthlyBudgetOperations.ReadCommand(nextScope, ACTOR)).toCompletableFuture().join();
        assertTrue(next.enabled());
        assertEquals(0, new BigDecimal("12.5").compareTo(next.capUsd()));
        assertEquals(0, next.knownSubtotalUsd().signum());
        assertFalse(next.terminalEffectiveUnknown());
        assertEquals(1, next.configVersion());
    }

    @Test
    void weeklyUnknownStaysWithTheAttributedMemberAndPartition() {
        String other = "weekly-member-" + UUID.randomUUID();
        jdbc.update("insert into workspace_member(workspace_id,user_id,role) values (?,?,'MEMBER')", workspace, other);
        var ownRequest = new MemberWeeklyBudgetOperations.Read(workspace, ACTOR, SIMULATION, ACTOR, true);
        seedUnknown("weekly-fixture");
        jdbc.update("""
                insert into provider_attempt(id,workspace_id,provider,endpoint,model,channel,adapter_version,
                    simulation,attempt_coverage,prepared_at,started_at,execution_outcome,usage_presence,
                    capture_completeness,projection_state)
                values (?,?,'FAKE','test','weekly-fixture','FAKE','1',true,'UNKNOWN',now(),now(),
                    'UNKNOWN','UNKNOWN','UNKNOWN','UNKNOWN')
                """, UUID.randomUUID().toString(), workspace);
        assertEquals(1, weekly.read(ownRequest).toCompletableFuture().join().terminalUnknownCount());
        var otherRequest = new MemberWeeklyBudgetOperations.Read(workspace, other, SIMULATION, ACTOR, false);
        assertEquals(0, weekly.read(otherRequest).toCompletableFuture().join().terminalUnknownCount());
        var real = new MemberWeeklyBudgetOperations.Read(workspace, ACTOR, REAL, ACTOR, true);
        assertEquals(0, weekly.read(real).toCompletableFuture().join().terminalUnknownCount());
    }

    @Test
    void budgetCancellationDoesNotImpersonateUserAndUserReplayKeepsFirstTimestamp() {
        var pending = preparedRun();
        var registration = pending.registration();
        budgets.configure(new WorkspaceMonthlyBudgetOperations.ConfigureCommand(
                scope(REAL), ACTOR, true, BigDecimal.ZERO, 0)).toCompletableFuture().join();
        assertNull(cancelMarker(registration));
        assertInstanceOf(WorkspaceAdmission.Decision.BudgetCancellationPending.class,
                admission.dispatch(new WorkspaceAdmission.Dispatch(registration, pending.attempt(), pending.owner()))
                        .toCompletableFuture().join());
        assertEquals(WorkspaceAdmission.CancelStatus.BEFORE_CLAIM,
                admission.cancelRun(registration).toCompletableFuture().join().status());
        var first = cancelMarker(registration);
        assertNotNull(first);
        assertEquals(WorkspaceAdmission.CancelStatus.BEFORE_CLAIM,
                admission.cancelRun(registration).toCompletableFuture().join().status());
        assertEquals(first, cancelMarker(registration));
    }

    @Test
    void revokedUserCannotCreateCancelMarker() {
        var pending = preparedRun();
        jdbc.update("update workspace_member set active=false where workspace_id=? and user_id=?", workspace, ACTOR);
        assertEquals(WorkspaceAdmission.CancelStatus.UNKNOWN,
                admission.cancelRun(pending.registration()).toCompletableFuture().join().status());
        assertNull(cancelMarker(pending.registration()));
    }

    @Test
    void missingEffectAndUserMarkerDoNotPretendUserCancelled() {
        var pending = preparedRun();
        jdbc.update("update workspace_admission_registration set status='CANCEL_REQUESTED' where token=?",
                pending.registration().token());
        var denied = assertInstanceOf(WorkspaceAdmission.Decision.Denied.class,
                admission.dispatch(new WorkspaceAdmission.Dispatch(pending.registration(), pending.attempt(), pending.owner()))
                        .toCompletableFuture().join());
        assertEquals(WorkspaceAdmission.Reason.RECOVERY_REQUIRED, denied.reason());
    }

    @Test
    void priorPeriodEffectIsStaleAndCannotPreventCurrentClaimFromStarting() {
        var pending = preparedRun();
        var registration = pending.registration();
        var claim = assertInstanceOf(WorkspaceAdmission.Decision.Allowed.class,
                admission.dispatch(new WorkspaceAdmission.Dispatch(registration, pending.attempt(), pending.owner()))
                        .toCompletableFuture().join()).claim();
        var effect = UUID.randomUUID().toString();
        var receipt = UUID.randomUUID().toString();
        jdbc.update("""
                insert into workspace_admission_effect_outbox(effect_id,registration_token,target_workspace_id,
                    target_partition,target_run_id,pending_id,pending_epoch,period_start,period_end,
                    generation,delivery_status,intent_receipt)
                select ?,r.token,r.workspace_id,r.partition,r.run_id,r.pending_id,r.pending_epoch,
                    r.pending_period_start-interval '1 month',r.pending_period_start,0,'PENDING',?
                  from workspace_admission_registration r where r.token=?
                """, effect, receipt, registration.token());
        var found = assertInstanceOf(WorkspaceAdmission.EffectInspection.Found.class,
                admission.inspectCancellationEffect(effect).toCompletableFuture().join()).effect();
        assertEquals(receipt, found.intentReceipt());
        var request = new io.github.samzhu.bot.usage.BudgetCancellationSink.Effect(
                effect, registration.token(), workspace, REAL, registration.runId(), found.target(), receipt);
        assertInstanceOf(WorkspaceAdmission.EffectAcceptance.Stale.class,
                admission.prepareCancellationEffect(request).toCompletableFuture().join());
        assertInstanceOf(WorkspaceAdmission.ProviderStartResult.Ready.class,
                admission.authorizeProviderStart(claim).toCompletableFuture().join());
    }

    @Test
    void partialCostReachingCapWinsOverUnknownAndReplacementDoesNotDoubleCount() {
        var pending = assertInstanceOf(WorkspaceAdmission.RegisterResult.Registered.class,
                register(UUID.randomUUID().toString(), SIMULATION)).registration();
        String model = "partial-cap-fixture";
        String attempt = seedUnknown(model);
        configure(new BigDecimal("0.001"), 0);
        assertEquals(WorkspaceAdmission.Reason.MONTH_UNKNOWN, denial());
        assertEquals(0, effectCount(pending));

        attachUsageEvidence(attempt);
        String version = sourceVersion(attempt);
        var first = revisions.reconcile(new UsageCostRevisionOperations.ReconcileCommand(
                workspace, attempt, null, version, "first", catalog(model, "{\"input\":\"2\"}"),
                "audit evidence", "partial"));
        assertEquals("PARTIAL", first.revision().status());
        assertEquals(WorkspaceAdmission.Reason.MONTH_CAP, denial());
        assertEquals(1, effectCount(pending));

        var second = revisions.reconcile(new UsageCostRevisionOperations.ReconcileCommand(
                workspace, attempt, first.revision().id(), version, "second", catalog(model, "{\"input\":\"3\"}"),
                "audit evidence", "replacement"));
        assertEquals("PARTIAL", second.revision().status());
        var budget = budgets.read(new WorkspaceMonthlyBudgetOperations.ReadCommand(scope(SIMULATION), ACTOR))
                .toCompletableFuture().join();
        assertTrue(budget.terminalEffectiveUnknown());
        assertEquals(0, new BigDecimal(second.revision().knownSubtotal()).compareTo(budget.knownSubtotalUsd()));
        assertEquals(1, effectCount(pending));
    }

    private int effectCount(WorkspaceAdmission.Registration registration) {
        return jdbc.queryForObject("select count(*) from workspace_admission_effect_outbox where registration_token=?",
                Integer.class, registration.token());
    }

    private void attachUsageEvidence(String attempt) {
        String snapshot = UUID.randomUUID().toString();
        jdbc.update("""
                insert into usage_snapshot(id,attempt_id,event_id,payload_hash,ordinal,semantic,source_layer,schema_version,
                  adapter_version,capture_version,capture_completeness,raw_state,raw_usage,captured_at)
                values (?,?,?,'fixture',1,'FINAL','FAKE_RAW','pricing-fixture-v1','1','1','COMPLETE','JSONB',
                  '{"promptTokenCount":1000,"cachedContentTokenCount":200,"candidatesTokenCount":300,
                    "thoughtsTokenCount":100,"toolUsePromptTokenCount":0,"totalTokenCount":1400}'::jsonb,now())
                """, snapshot, attempt, UUID.randomUUID().toString());
        jdbc.update("""
                update provider_attempt set projection_state='VALID', projection_snapshot_id=?, usage_presence='PRESENT',
                  capture_completeness='COMPLETE' where id=?
                """, snapshot, attempt);

    }

    private String catalog(String model, String rates) {
        String catalog = "budget-rates-" + UUID.randomUUID();
        data.createCatalogRevision(catalog, catalog);
        jdbc.update("""
                insert into pricing_catalog_entry(id,revision_id,provider,endpoint,model,channel,price_revision,
                  effective_from,effective_until,rates)
                values (?,?,'FAKE_GEMINI','generateContent',?,'FAKE','fixture',
                  now()-interval '1 day',now()+interval '1 day',
                  ?::jsonb)
                """, UUID.randomUUID().toString(), catalog, model, rates);
        return catalog;
    }

    private record PreparedRun(WorkspaceAdmission.Registration registration, String attempt,
                               io.github.samzhu.bot.usage.RunOwnershipGuard.Fence owner) {}

    private PreparedRun preparedRun() {
        var run = UUID.randomUUID().toString();
        var registration = assertInstanceOf(WorkspaceAdmission.RegisterResult.Registered.class,
                register(run, REAL)).registration();
        jdbc.update("""
                insert into chat_run(id,conversation_id,client_request_id,input_text,status)
                values (?,?,?,'fixture','PENDING')
                """, run, conversation, UUID.randomUUID().toString());
        var owner = SyntheticRunOwner.attach(jdbc, registration);
        assertInstanceOf(WorkspaceAdmission.PublicationResult.Ready.class,
                admission.publish(registration).toCompletableFuture().join());
        String attempt = ledger.begin(new UsageLedger.Dispatch(run, workspace, ACTOR,
                "FAKE", "test", "fixture", "FAKE", "1", false, "UNKNOWN"), owner);
        return new PreparedRun(registration, attempt, owner);
    }

    private java.sql.Timestamp cancelMarker(WorkspaceAdmission.Registration registration) {
        return jdbc.queryForObject("select user_cancel_requested_at from workspace_admission_registration where token=?",
                java.sql.Timestamp.class, registration.token());
    }

    private WorkspaceAdmission.RegisterResult register(String run, WorkspaceAdmission.Partition partition) {
        return admission.register(new WorkspaceAdmission.Register(workspace, run, ACTOR, conversation, partition))
                .toCompletableFuture().join();
    }

    private WorkspaceAdmission.Reason denial() {
        return assertInstanceOf(WorkspaceAdmission.RegisterResult.Denied.class,
                register(UUID.randomUUID().toString(), SIMULATION)).reason();
    }

    private WorkspaceMonthlyBudgetOperations.Scope scope(WorkspaceAdmission.Partition partition) {
        return new WorkspaceMonthlyBudgetOperations.Scope(workspace, partition,
                YearMonth.from(clock.instant().atZone(ZoneId.of("Asia/Taipei"))));
    }

    private void configure(BigDecimal cap, long version) {
        budgets.configure(new WorkspaceMonthlyBudgetOperations.ConfigureCommand(scope(SIMULATION), ACTOR, true, cap, version))
                .toCompletableFuture().join();
    }

    private WorkspaceMonthlyBudgetOperations.ConfigureOutcome configureZero(
            WorkspaceMonthlyBudgetOperations.Scope scope, String decimal) {
        return budgets.configureIdempotent(new WorkspaceMonthlyBudgetOperations.IdempotentConfigureCommand(
                scope, ACTOR, true, decimal, "0", "zero")).toCompletableFuture().join().outcome();
    }

    private String seedUnknown(String model) {
        String attempt = UUID.randomUUID().toString();
        jdbc.update("""
                insert into provider_attempt(id,workspace_id,actor_id,provider,endpoint,model,channel,adapter_version,
                  simulation,attempt_coverage,prepared_at,started_at,finished_at,execution_outcome,usage_presence,
                  capture_completeness,projection_state,projection_version)
                values (?,?,?,'FAKE_GEMINI','generateContent',?,'FAKE','1',true,'VERIFIED',
                  now(),now(),now(),'IN_PROGRESS','PENDING','UNKNOWN','MISSING',1)
                """, attempt, workspace, ACTOR, model);
        ledger.finish(attempt, "UNKNOWN");
        return attempt;
    }

    private String sourceVersion(String attempt) {
        return jdbc.queryForObject("select projection_version::text from provider_attempt where id=?",
                String.class, attempt);
    }
}
