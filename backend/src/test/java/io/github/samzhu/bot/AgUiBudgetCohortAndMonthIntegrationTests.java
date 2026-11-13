package io.github.samzhu.bot;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.samzhu.bot.usage.BudgetCancellationSink;
import io.github.samzhu.bot.usage.WorkspaceAdmission;
import io.github.samzhu.bot.usage.WorkspaceMonthlyBudgetOperations;
import io.github.samzhu.bot.usage.UsageLedger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties={"bot.budget.effects.initial-delay-ms=100","bot.budget.effects.delay-ms=100",
                "bot.pricing.reconcile.initial-delay-ms=3600000","bot.pricing.reconcile.delay-ms=3600000"})
@Import({TestcontainersConfiguration.class,AgUiIntegrationTests.Scheduling.class})
@ActiveProfiles("test")
class AgUiBudgetCohortAndMonthIntegrationTests {
    static final class MutableClock extends Clock {
        final AtomicReference<Instant> now=new AtomicReference<>(Instant.parse("2026-11-30T15:59:59Z"));
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(now.get(),zone); }
        @Override public Instant instant() { return now.get(); }
    }
    @TestBean(name="usageClock",methodName="clock",enforceOverride=true) Clock usageClock;
    static Clock clock() { return new MutableClock(); }
    @TestBean(name="mockChatModel",methodName="model",enforceOverride=true) ChatModel chatModel;
    static ControlledChatModel model() { return ControlledChatModel.getInstance(); }
    @MockitoSpyBean WorkspaceAdmission admission;
    @Autowired WorkspaceMonthlyBudgetOperations budgets;
    @Autowired BudgetCancellationSink sink;
    @Autowired UsageLedger ledger;
    @Autowired JdbcTemplate jdbc;
    @LocalServerPort int port;
    final HttpClient http=HttpClient.newHttpClient();
    final ObjectMapper json=new ObjectMapper();
    @BeforeEach void setup() {
        ((MutableClock)usageClock).now.set(Instant.parse("2026-11-30T15:59:59Z"));
        model().reset();
        jdbc.update("insert into bot(id,name,system_prompt,created_at) values ('bot-general','General','test',current_timestamp) on conflict(id) do nothing");
    }
    String workspace() {
        String workspace="budget-synthetic-"+UUID.randomUUID();
        jdbc.update("insert into workspace(id,name) values (?,?)",workspace,"synthetic budget fixture");
        jdbc.update("insert into workspace_member(workspace_id,user_id,role,active) values (?,'dev-user','MANAGER',true)",workspace);
        return workspace;
    }
    HttpRequest request(String path,Object body) throws Exception {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
    }
    String conversation(String workspace) throws Exception {
        var response=http.send(request("/graphql",Map.of("query","mutation { createConversation(botId: \"bot-general\", title: \"budget fixture\", workspaceId: \""+workspace+"\") { id } }")),HttpResponse.BodyHandlers.ofString());
        String id=json.readTree(response.body()).path("data").path("createConversation").path("id").asText();
        assertFalse(id.isBlank(),response.body()); return id;
    }
    CompletableFuture<HttpResponse<String>> start(String conversation,String run) throws Exception {
        var body=Map.of("threadId",conversation,"runId",run,"state",Map.of(),"messages",List.of(Map.of("id",UUID.randomUUID().toString(),"role","user","content","synthetic budget run")),"tools",List.of(),"context",List.of(),"forwardedProps",Map.of());
        return http.sendAsync(request("/api/ag-ui",body),HttpResponse.BodyHandlers.ofString());
    }
    BudgetCancellationSink.Effect effect(String effectId,String run) {
        var found=assertInstanceOf(WorkspaceAdmission.EffectInspection.Found.class,admission.inspectCancellationEffect(effectId).toCompletableFuture().join()).effect();
        return new BudgetCancellationSink.Effect(effectId,found.token(),found.workspaceId(),found.partition(),run,found.target(),found.intentReceipt());
    }
    WorkspaceMonthlyBudgetOperations.Scope scope(String workspace,WorkspaceAdmission.Partition partition) {
        return new WorkspaceMonthlyBudgetOperations.Scope(workspace,partition,YearMonth.from(usageClock.instant().atZone(ZoneId.of("Asia/Taipei"))));
    }
    private void recordCohortSnapshot(String phase,String preRun,String publishedRun,
                                      CompletableFuture<HttpResponse<String>> pre,CompletableFuture<HttpResponse<String>> published) {
        try {
            var snapshot=new java.util.LinkedHashMap<String,Object>();
            snapshot.put("phase",phase);
            snapshot.put("preSse",responseSnapshot(pre)); snapshot.put("publishedSse",responseSnapshot(published));
            snapshot.put("runs",jdbc.queryForList("select id,conversation_id,status,error_message from chat_run where id in (?,?)",preRun,publishedRun));
            snapshot.put("registrations",jdbc.queryForList("select token,run_id,workspace_id,partition,status,pending_id,pending_epoch from workspace_admission_registration where run_id in (?,?)",preRun,publishedRun));
            snapshot.put("attempts",jdbc.queryForList("select id,run_id,execution_outcome,execution_reason,started_at from provider_attempt where run_id in (?,?)",preRun,publishedRun));
            snapshot.put("claims",jdbc.queryForList("select c.claim_id,c.registration_token,c.attempt_id,c.status,c.period_start,c.period_end,c.generation from workspace_admission_claim c join workspace_admission_registration r on r.token=c.registration_token where r.run_id in (?,?)",preRun,publishedRun));
            snapshot.put("effects",jdbc.queryForList("select effect_id,registration_token,target_run_id,target_workspace_id,target_partition,pending_id,pending_epoch,claim_id,target_attempt_id,period_start,period_end,generation,delivery_status,delivery_lease_id,delivery_lease_until,intent_receipt,ack_receipt from workspace_admission_effect_outbox where target_run_id in (?,?)",preRun,publishedRun));
            snapshot.put("receipts",jdbc.queryForList("select effect_id,registration_token,run_id,target_kind,target_id,target_attempt_id,target_period_start,target_period_end,target_epoch_or_generation,intent_receipt,acceptance_receipt from execution_budget_cancellation_receipt where run_id in (?,?)",preRun,publishedRun));
            String query="query { pre: run(id: \""+preRun+"\") { id status errorMessage } published: run(id: \""+publishedRun+"\") { id status errorMessage } }";
            try {
                var graph=http.sendAsync(request("/graphql",Map.of("query",query)),HttpResponse.BodyHandlers.ofString()).get(3,TimeUnit.SECONDS);
                snapshot.put("graphqlStatus",graph.statusCode()); snapshot.put("graphqlBody",graph.body());
            } catch(Exception unavailable) { snapshot.put("graphqlError",unavailable.getClass().getSimpleName()); }
            // All IDs, input, and model output in this fixture are synthetic.
            System.out.println("D_COHORT_SNAPSHOT "+json.writeValueAsString(snapshot));
        } catch(Exception unavailable) {
            System.out.println("D_COHORT_SNAPSHOT_ERROR "+phase+" "+unavailable.getClass().getSimpleName());
        }
    }
    private Object responseSnapshot(CompletableFuture<HttpResponse<String>> future) {
        if(future==null) return "not-started";
        if(!future.isDone()) return "pending";
        try {
            var response=future.getNow(null);
            return response==null ? "no-response" : Map.of("status",response.statusCode(),"body",response.body());
        } catch(RuntimeException failed) { return "response-error:"+failed.getClass().getSimpleName(); }
    }
    @Test void staleOldMonthEffectCannotCancelNewClaimButCurrentCapEffectCan() throws Exception {
        String workspace=workspace(), conversation=conversation(workspace), run=UUID.randomUUID().toString();
        var publishing=new CountDownLatch(1); var release=new CountDownLatch(1);
        doAnswer(call -> {
            WorkspaceAdmission.Registration registration=call.getArgument(0);
            if(run.equals(registration.runId())) { publishing.countDown(); if(!release.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("publish latch timeout"); }
            return call.callRealMethod();
        }).when(admission).publish(any());
        model().setHoldMode(true);
        var pending=start(conversation,run);
        try {
            assertTrue(publishing.await(8,TimeUnit.SECONDS));
            var old=jdbc.queryForMap("select token,pending_id,pending_epoch,pending_period_start,pending_period_end from workspace_admission_registration where run_id=?",run);
            ((MutableClock)usageClock).now.set(Instant.parse("2026-11-30T16:00:01Z"));
            release.countDown();
            assertTrue(model().awaitSubscribed(8,TimeUnit.SECONDS));
            var claim=jdbc.queryForMap("select claim_id,attempt_id,period_start,period_end from workspace_admission_claim where registration_token=?",old.get("token"));
            assertEquals(java.sql.Date.valueOf("2026-12-01"),claim.get("period_start"));
            // Delayed historical outbox fixture: immutable old pending fence is captured before the month boundary.
            String oldId=UUID.randomUUID().toString(), intent=UUID.randomUUID().toString();
            jdbc.update("""
                insert into workspace_admission_effect_outbox(effect_id,registration_token,target_workspace_id,target_partition,target_run_id,
                    pending_id,pending_epoch,period_start,period_end,generation,delivery_status,intent_receipt)
                values (?,?,?,'SIMULATION',?,?,?,?,?,0,'PENDING',?)
                """,oldId,old.get("token"),workspace,run,old.get("pending_id"),old.get("pending_epoch"),old.get("pending_period_start"),old.get("pending_period_end"),intent);
            var stale=effect(oldId,run);
            assertInstanceOf(BudgetCancellationSink.Result.Stale.class,sink.cancel(stale).toCompletableFuture().get(10,TimeUnit.SECONDS));
            assertEquals("RUNNING",jdbc.queryForObject("select status from chat_run where id=?",String.class,run));
            assertEquals(0,jdbc.queryForObject("select count(*) from execution_budget_cancellation_receipt where effect_id=?",Integer.class,oldId));
            assertFalse(pending.isDone());
            var current=scope(workspace,WorkspaceAdmission.Partition.SIMULATION);
            var before=budgets.read(new WorkspaceMonthlyBudgetOperations.ReadCommand(current,"dev-user")).toCompletableFuture().join();
            budgets.configure(new WorkspaceMonthlyBudgetOperations.ConfigureCommand(current,"dev-user",true,BigDecimal.ZERO,before.configVersion())).toCompletableFuture().join();
            String currentId=jdbc.queryForObject("select effect_id from workspace_admission_effect_outbox where target_run_id=? and claim_id=?",String.class,run,claim.get("claim_id"));
            var currentEffect=effect(currentId,run);
            var target=assertInstanceOf(BudgetCancellationSink.Target.Claimed.class,currentEffect.target()).fence();
            assertEquals(claim.get("claim_id"),target.claimId()); assertEquals(claim.get("attempt_id"),target.attemptId());
            var accepted=assertInstanceOf(BudgetCancellationSink.Result.Accepted.class,sink.cancel(currentEffect).toCompletableFuture().get(10,TimeUnit.SECONDS));
            var matched=assertInstanceOf(BudgetCancellationSink.ReceiptInspection.Matched.class,sink.inspectAcceptance(currentEffect,accepted.receiptId()).toCompletableFuture().get(10,TimeUnit.SECONDS));
            assertEquals(currentEffect,matched.effect()); assertEquals(accepted.receiptId(),matched.acceptanceReceipt());
            assertEquals("CANCELLED",jdbc.queryForObject("select status from chat_run where id=?",String.class,run));
            assertEquals(accepted.receiptId(),jdbc.queryForObject("select acceptance_receipt from execution_budget_cancellation_receipt where effect_id=?",String.class,currentId));
            var replay=assertInstanceOf(BudgetCancellationSink.Result.Accepted.class,sink.cancel(currentEffect).toCompletableFuture().get(10,TimeUnit.SECONDS));
            assertEquals(accepted.receiptId(),replay.receiptId());
            assertTrue(pending.get(10,TimeUnit.SECONDS).body().contains("cancelled"));
        } finally { release.countDown(); model().complete(); reset(admission); }
    }
    @Test void enablingCapCancelsWholePendingCohortWithoutCrossingScopes() throws Exception {
        String targetWorkspace=workspace(), otherWorkspace=workspace();
        String preConversation=conversation(targetWorkspace), publishedConversation=conversation(targetWorkspace);
        String realConversation=conversation(targetWorkspace), otherConversation=conversation(otherWorkspace);
        String preRun=UUID.randomUUID().toString(), publishedRun=UUID.randomUUID().toString(), otherRun=UUID.randomUUID().toString();
        var publicationEntered=new CountDownLatch(1); var releasePublication=new CountDownLatch(1);
        var dispatchEntered=new CountDownLatch(1); var releaseDispatch=new CompletableFuture<Void>();
        var heldDispatch=new java.util.concurrent.atomic.AtomicBoolean();
        doAnswer(call -> {
            WorkspaceAdmission.Registration registration=call.getArgument(0);
            if(preRun.equals(registration.runId())) {
                publicationEntered.countDown();
                if(!releasePublication.await(15,TimeUnit.SECONDS)) throw new IllegalStateException("publication latch timeout");
            }
            return call.callRealMethod();
        }).when(admission).publish(any());
        doAnswer(call -> {
            WorkspaceAdmission.Dispatch command=call.getArgument(0);
            if(publishedRun.equals(command.registration().runId()) && heldDispatch.compareAndSet(false,true)) {
                dispatchEntered.countDown();
                return releaseDispatch.thenCompose(ignored -> admission.dispatch(command));
            }
            return call.callRealMethod();
        }).when(admission).dispatch(any());
        model().setHoldMode(true);
        var other=start(otherConversation,otherRun);
        CompletableFuture<HttpResponse<String>> pre=null, published=null;
        try {
            assertTrue(model().awaitSubscribed(8,TimeUnit.SECONDS));
            assertEquals(1,jdbc.queryForObject("select count(*) from workspace_admission_claim where registration_token=(select token from workspace_admission_registration where run_id=?)",Integer.class,otherRun));
            String realRun=UUID.randomUUID().toString();
            var real=assertInstanceOf(WorkspaceAdmission.RegisterResult.Registered.class,admission.register(
                    new WorkspaceAdmission.Register(targetWorkspace,realRun,"dev-user",realConversation,WorkspaceAdmission.Partition.REAL)).toCompletableFuture().get(10,TimeUnit.SECONDS)).registration();
            // REAL uses a synthetic Run and fake ledger metadata; it never invokes a paid provider.
            jdbc.update("insert into chat_run(id,conversation_id,client_request_id,input_text,status) values (?,?,?,'synthetic','PENDING')",realRun,realConversation,UUID.randomUUID().toString());
            assertInstanceOf(WorkspaceAdmission.PublicationResult.Ready.class,admission.publish(real).toCompletableFuture().get(10,TimeUnit.SECONDS));
            String realAttempt=ledger.begin(new UsageLedger.Dispatch(realRun,targetWorkspace,"dev-user","FAKE","test","model","FAKE","1",false,"VERIFIED"));
            var realClaim=assertInstanceOf(WorkspaceAdmission.Decision.Allowed.class,admission.dispatch(new WorkspaceAdmission.Dispatch(real,realAttempt)).toCompletableFuture().get(10,TimeUnit.SECONDS)).claim();
            pre=start(preConversation,preRun);
            assertTrue(publicationEntered.await(8,TimeUnit.SECONDS));
            published=start(publishedConversation,publishedRun);
            assertTrue(dispatchEntered.await(8,TimeUnit.SECONDS));
            assertEquals("REGISTERED",jdbc.queryForObject("select status from workspace_admission_registration where run_id=?",String.class,preRun));
            assertEquals("PUBLISHED",jdbc.queryForObject("select status from workspace_admission_registration where run_id=?",String.class,publishedRun));
            var preFence=jdbc.queryForMap("select token,pending_id,pending_epoch from workspace_admission_registration where run_id=?",preRun);
            var publishedFence=jdbc.queryForMap("select token,pending_id,pending_epoch from workspace_admission_registration where run_id=?",publishedRun);
            var simulation=scope(targetWorkspace,WorkspaceAdmission.Partition.SIMULATION);
            var before=budgets.read(new WorkspaceMonthlyBudgetOperations.ReadCommand(simulation,"dev-user")).toCompletableFuture().join();
            assertFalse(before.enabled());
            budgets.configure(new WorkspaceMonthlyBudgetOperations.ConfigureCommand(simulation,"dev-user",true,BigDecimal.ZERO,before.configVersion())).toCompletableFuture().join();
            var ids=jdbc.query("select target_run_id,effect_id from workspace_admission_effect_outbox where target_workspace_id=? and target_partition='SIMULATION' and target_run_id in (?,?) and pending_id is not null order by target_run_id",(rs,n)->Map.entry(rs.getString(1),rs.getString(2)),targetWorkspace,preRun,publishedRun);
            assertEquals(2,ids.size(),"both pending registrations must have exact durable effects");
            assertEquals(java.util.Set.of(preRun,publishedRun),ids.stream().map(Map.Entry::getKey).collect(java.util.stream.Collectors.toSet()));
            var original=new java.util.HashMap<String,BudgetCancellationSink.Effect>();
            for(var row:ids) {
                var exact=effect(row.getValue(),row.getKey());
                var fence=assertInstanceOf(BudgetCancellationSink.Target.Pending.class,exact.target()).fence();
                var expected=row.getKey().equals(preRun)?preFence:publishedFence;
                assertEquals(expected.get("token"),exact.token()); assertEquals(expected.get("pending_id"),fence.pendingId());
                assertEquals(((Number)expected.get("pending_epoch")).longValue(),fence.epoch());
                assertEquals(targetWorkspace,fence.workspaceId()); assertEquals(WorkspaceAdmission.Partition.SIMULATION,fence.partition());
                original.put(row.getValue(),exact);
            }
            // Only the real scheduled delivery drives the cohort. A prepublication Retry is not an acknowledgement.
            releasePublication.countDown(); releaseDispatch.complete(null);
            HttpResponse<String> preResponse=null,publishedResponse=null;
            try { preResponse=pre.get(10,TimeUnit.SECONDS); } catch(Exception ignored) { /* Snapshot retains timeout or failure. */ }
            try { publishedResponse=published.get(10,TimeUnit.SECONDS); } catch(Exception ignored) { /* Snapshot retains timeout or failure. */ }
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
            var states=new java.util.HashMap<String,String>();
            var registrationStates=new java.util.HashMap<String,String>();
            while(System.nanoTime()<deadline) {
                for(var row:ids) {
                    states.put(row.getValue(),jdbc.queryForObject("select delivery_status from workspace_admission_effect_outbox where effect_id=?",String.class,row.getValue()));
                    registrationStates.put(row.getKey(),jdbc.queryForObject("select status from workspace_admission_registration where run_id=?",String.class,row.getKey()));
                }
                if(states.values().stream().allMatch(state -> state.equals("ACKNOWLEDGED") || state.equals("STALE"))
                        && registrationStates.values().stream().allMatch("TERMINAL"::equals)) break;
                Thread.yield();
            }
            recordCohortSnapshot("after-durable-wait",preRun,publishedRun,pre,published);
            assertNotNull(preResponse,"prepublication SSE did not complete");
            assertNotNull(publishedResponse,"published SSE did not complete");
            assertTrue(preResponse.body().contains("cancelled"),"prepublication SSE: "+preResponse.body());
            assertTrue(publishedResponse.body().contains("cancelled"),"published SSE: "+publishedResponse.body());
            for(var row:ids) {
                var exact=original.get(row.getValue());
                var current=assertInstanceOf(WorkspaceAdmission.EffectInspection.Found.class,admission.inspectCancellationEffect(row.getValue()).toCompletableFuture().get(10,TimeUnit.SECONDS)).effect();
                assertEquals(exact.token(),current.token()); assertEquals(exact.workspaceId(),current.workspaceId());
                assertEquals(exact.partition(),current.partition()); assertEquals(exact.target(),current.target()); assertEquals(exact.intentReceipt(),current.intentReceipt());
                assertEquals("CANCELLED",jdbc.queryForObject("select status from chat_run where id=?",String.class,row.getKey()));
                assertEquals("TERMINAL",registrationStates.get(row.getKey()),"registration did not settle: "+registrationStates);
                String state=states.get(row.getValue());
                assertTrue("ACKNOWLEDGED".equals(state) || "STALE".equals(state),"cohort effect did not durably converge: "+states);
                if("ACKNOWLEDGED".equals(state)) {
                    assertNotNull(current.ackReceipt());
                    var matched=assertInstanceOf(BudgetCancellationSink.ReceiptInspection.Matched.class,sink.inspectAcceptance(exact,current.ackReceipt()).toCompletableFuture().get(10,TimeUnit.SECONDS));
                    assertEquals(exact,matched.effect()); assertEquals(current.ackReceipt(),matched.acceptanceReceipt());
                    assertEquals(current.ackReceipt(),jdbc.queryForObject("select acceptance_receipt from execution_budget_cancellation_receipt where effect_id=?",String.class,row.getValue()));
                } else {
                    assertInstanceOf(WorkspaceAdmission.EffectAcceptance.Stale.class,admission.prepareCancellationEffect(exact).toCompletableFuture().get(10,TimeUnit.SECONDS));
                }
            }
            assertEquals("RUNNING",jdbc.queryForObject("select status from chat_run where id=?",String.class,otherRun));
            assertFalse(other.isDone());
            assertEquals(1,model().getRecordedPrompts().size(),"only the unrelated workspace may reach the provider");
            assertEquals(WorkspaceAdmission.CancellationState.NONE,admission.cancellationState(real).toCompletableFuture().join().state());
            assertInstanceOf(WorkspaceAdmission.ProviderStartResult.Ready.class,admission.authorizeProviderStart(realClaim).toCompletableFuture().get(10,TimeUnit.SECONDS));
            assertEquals("PENDING",jdbc.queryForObject("select status from chat_run where id=?",String.class,realRun));
            assertEquals(0,jdbc.queryForObject("select count(*) from workspace_admission_effect_outbox where registration_token=?",Integer.class,real.token()));
            assertEquals(0,jdbc.queryForObject("select count(*) from workspace_admission_effect_outbox where target_run_id=?",Integer.class,otherRun));
            assertEquals(WorkspaceAdmission.FinalizationResult.SETTLED,admission.finalizeAttempt(new WorkspaceAdmission.FinalizeAttempt(real,realAttempt,realClaim,WorkspaceAdmission.TerminalKind.UNKNOWN)).toCompletableFuture().get(10,TimeUnit.SECONDS));
            jdbc.update("update chat_run set status='FAILED' where id=? and status='PENDING'",realRun);
            var cleaned=admission.completeTerminalRun(realRun).toCompletableFuture().get(10,TimeUnit.SECONDS);
            assertTrue(cleaned==WorkspaceAdmission.TerminalRunCompletion.COMPLETED || cleaned==WorkspaceAdmission.TerminalRunCompletion.ALREADY_COMPLETED,cleaned.toString());
            model().complete();
            other.get(10,TimeUnit.SECONDS);
        } finally {
            recordCohortSnapshot("before-finally-release",preRun,publishedRun,pre,published);
            releasePublication.countDown(); releaseDispatch.complete(null); model().complete(); reset(admission);
        }
    }

    @Test void simultaneousScheduledAndDirectPendingDeliveryPreservesOneReceipt() throws Exception {
        String workspace=workspace(), conversation=conversation(workspace), run=UUID.randomUUID().toString();
        var dispatchEntered=new CountDownLatch(1); var releaseDispatch=new CompletableFuture<Void>();
        var heldDispatch=new java.util.concurrent.atomic.AtomicBoolean();
        var prepared=new CountDownLatch(2);
        doAnswer(call -> {
            WorkspaceAdmission.Dispatch command=call.getArgument(0);
            if(run.equals(command.registration().runId()) && heldDispatch.compareAndSet(false,true)) {
                dispatchEntered.countDown();
                return releaseDispatch.thenCompose(ignored -> admission.dispatch(command));
            }
            return call.callRealMethod();
        }).when(admission).dispatch(any());
        doAnswer(call -> {
            BudgetCancellationSink.Effect delivered=call.getArgument(0);
            @SuppressWarnings("unchecked") var result=(java.util.concurrent.CompletionStage<WorkspaceAdmission.EffectAcceptance>)call.callRealMethod();
            if(run.equals(delivered.runId())) result.thenAccept(value -> {
                if(value instanceof WorkspaceAdmission.EffectAcceptance.Prepared) prepared.countDown();
            });
            return result;
        }).when(admission).prepareCancellationEffect(any());
        var stream=start(conversation,run);
        try(var connection=jdbc.getDataSource().getConnection()) {
            assertTrue(dispatchEntered.await(8,TimeUnit.SECONDS),"published pending dispatch did not reach the test latch");
            connection.setAutoCommit(false); int blocker;
            try(var statement=connection.createStatement(); var rows=statement.executeQuery("select pg_backend_pid()")) { assertTrue(rows.next()); blocker=rows.getInt(1); }
            try(var statement=connection.prepareStatement("select id from chat_run where id=? for update")) {
                statement.setString(1,run); try(var rows=statement.executeQuery()) { assertTrue(rows.next()); }
            }
            var selected=scope(workspace,WorkspaceAdmission.Partition.SIMULATION);
            var budget=budgets.read(new WorkspaceMonthlyBudgetOperations.ReadCommand(selected,"dev-user")).toCompletableFuture().join();
            budgets.configure(new WorkspaceMonthlyBudgetOperations.ConfigureCommand(selected,"dev-user",true,BigDecimal.ZERO,budget.configVersion())).toCompletableFuture().join();
            String effectId=jdbc.queryForObject("select effect_id from workspace_admission_effect_outbox where target_run_id=? and pending_id is not null",String.class,run);
            var exact=effect(effectId,run);
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8); boolean waiting=false;
            while(System.nanoTime()<deadline) {
                waiting=Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from pg_stat_activity where ?=any(pg_blocking_pids(pid)))",Boolean.class,blocker));
                if(waiting) break; Thread.yield();
            }
            assertTrue(waiting,"scheduled delivery did not reach the locked Run");
            assertEquals(0,jdbc.queryForObject("select count(*) from execution_budget_cancellation_receipt where effect_id=?",Integer.class,effectId));
            var duplicate=sink.cancel(exact).toCompletableFuture();
            assertTrue(prepared.await(8,TimeUnit.SECONDS),"both exact deliveries must pass the authoritative prepare before the Run lock is released");
            connection.rollback();
            var duplicateResult=duplicate.get(10,TimeUnit.SECONDS);
            System.out.println("Controlled concurrent delivery result: "+duplicateResult.getClass().getSimpleName());
            assertTrue(duplicateResult instanceof BudgetCancellationSink.Result.Retry || duplicateResult instanceof BudgetCancellationSink.Result.Accepted,
                    "concurrent delivery must retry or return the same durable receipt: "+duplicateResult);
            long ackDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(12); String state=null;
            while(System.nanoTime()<ackDeadline) {
                state=jdbc.queryForObject("select delivery_status from workspace_admission_effect_outbox where effect_id=?",String.class,effectId);
                if("ACKNOWLEDGED".equals(state)) break; Thread.yield();
            }
            assertEquals("ACKNOWLEDGED",state,"the scheduled delivery must eventually persist its matching acknowledgement");
            String receipt=jdbc.queryForObject("select acceptance_receipt from execution_budget_cancellation_receipt where effect_id=?",String.class,effectId);
            if(duplicateResult instanceof BudgetCancellationSink.Result.Accepted accepted) assertEquals(receipt,accepted.receiptId());
            assertEquals(receipt,jdbc.queryForObject("select ack_receipt from workspace_admission_effect_outbox where effect_id=?",String.class,effectId));
            assertInstanceOf(BudgetCancellationSink.ReceiptInspection.Matched.class,sink.inspectAcceptance(exact,receipt).toCompletableFuture().get(10,TimeUnit.SECONDS));
            assertEquals("CANCELLED",jdbc.queryForObject("select status from chat_run where id=?",String.class,run));
            assertTrue(model().getRecordedPrompts().isEmpty());
            releaseDispatch.complete(null);
            assertTrue(stream.get(10,TimeUnit.SECONDS).body().contains("cancelled"));
        } finally { releaseDispatch.complete(null); model().complete(); reset(admission); }
    }

}
