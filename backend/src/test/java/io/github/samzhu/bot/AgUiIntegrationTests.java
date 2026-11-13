package io.github.samzhu.bot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import io.github.samzhu.bot.execution.internal.RunRegistry;
import io.github.samzhu.bot.bot.BotOperations;
import io.github.samzhu.bot.execution.internal.ChatRunRepository;
import org.springframework.ai.session.SessionService;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.net.URI;
import java.net.http.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"bot.budget.effects.initial-delay-ms=100", "bot.budget.effects.delay-ms=100",
                "bot.execution.completion-sweep.initial-delay-ms=100", "bot.execution.completion-sweep.delay-ms=100",
                "bot.pricing.reconcile.initial-delay-ms=3600000", "bot.pricing.reconcile.delay-ms=3600000"})
@Import({TestcontainersConfiguration.class, AgUiIntegrationTests.Scheduling.class})
@ActiveProfiles("test")
class AgUiIntegrationTests {
 @org.springframework.boot.test.context.TestConfiguration
 @org.springframework.scheduling.annotation.EnableScheduling
 static class Scheduling {}
 @LocalServerPort int port;
 @Autowired JdbcTemplate jdbc;
 @MockitoSpyBean io.github.samzhu.bot.usage.WorkspaceAdmission admission;
 @MockitoSpyBean io.github.samzhu.bot.usage.RunAdmissionStateReader runAdmissionReader;
 @Autowired io.github.samzhu.bot.usage.WorkspaceMonthlyBudgetOperations budgets;
 @Autowired io.github.samzhu.bot.usage.BudgetCancellationSink budgetSink;
 @Autowired java.time.Clock usageClock;
 @MockitoSpyBean RunRegistry registry;
 @MockitoSpyBean BotOperations botOperations;
 @MockitoSpyBean ChatRunRepository runs;
 @MockitoSpyBean SessionService sessions;
 @TestBean(name="mockChatModel", methodName="model", enforceOverride=true) ChatModel model;
 static ControlledChatModel model() { return ControlledChatModel.getInstance(); }
 final ObjectMapper json = new ObjectMapper();
 final HttpClient http = HttpClient.newHttpClient();
 @BeforeEach void setup() { model().reset(); jdbc.update("INSERT INTO bot (id,name,system_prompt,created_at) VALUES ('bot-general','General Assistant','trusted',CURRENT_TIMESTAMP) ON CONFLICT (id) DO NOTHING"); }
 HttpResponse<String> post(String path, String body) throws Exception { return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString()); }
 String conversation() throws Exception { var r=post("/graphql", json.writeValueAsString(Map.of("query", "mutation { createConversation(botId: \"bot-general\", title: \"AGUI\") { id } }"))); return json.readTree(r.body()).path("data").path("createConversation").path("id").asText(); }
 String input(String conversation, String run, String message) throws Exception { return json.writeValueAsString(Map.of("threadId",conversation,"runId",run,"state",Map.of(),"messages",List.of(Map.of("id",UUID.randomUUID().toString(),"role","user","content",message)),"tools",List.of(),"context",List.of(),"forwardedProps",Map.of())); }
 private void awaitAttemptOutcome(String run,String expected) {
  long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(8); String actual=null;
  while(System.nanoTime()<deadline) {
   var rows=jdbc.query("select execution_outcome from provider_attempt where run_id=?",(rs,n)->rs.getString(1),run);
   if(!rows.isEmpty()) { actual=rows.getFirst(); if(expected.equals(actual)) break; }
   Thread.yield();
  }
  assertEquals(expected,actual,"attempt must settle through the authoritative recovery path");
 }
 @Test void failedClaimWriteRecoversAbsentWithoutProviderOrSecondDispatch() throws Exception {
  var c=conversation(); var run=UUID.randomUUID().toString();
  jdbc.execute("create or replace function test_reject_claim_write() returns trigger language plpgsql as $$ begin if exists(select 1 from provider_attempt where id=new.attempt_id and run_id='"+run+"') then raise exception 'injected claim write failure'; end if; return new; end $$");
  jdbc.execute("create trigger test_reject_claim_write before insert on workspace_admission_claim for each row execute function test_reject_claim_write()");
  try {
   var response=post("/api/ag-ui",input(c,run,"claim write failure"));
   assertEquals(200,response.statusCode()); assertTrue(response.body().contains("RUN_ERROR"),response.body());
   awaitAttemptOutcome(run,"NOT_DISPATCHED");
   assertEquals(0,model().getRecordedPrompts().size());
   assertEquals(0,jdbc.queryForObject("select count(*) from workspace_admission_claim c join provider_attempt a on a.id=c.attempt_id where a.run_id=?",Integer.class,run));
   assertEquals(0,jdbc.queryForObject("select count(*) from provider_attempt where run_id=? and started_at is not null",Integer.class,run));
   assertEquals(1,jdbc.queryForObject("select count(*) from provider_attempt where run_id=?",Integer.class,run));
   verify(admission,times(1)).dispatch(argThat(d->d.registration().runId().equals(run)));
  } finally {
   jdbc.execute("drop trigger if exists test_reject_claim_write on workspace_admission_claim");
   jdbc.execute("drop function if exists test_reject_claim_write()");
  }
 }
 @Test void overloadRequiresAuthoritativeAbsentBeforeNotDispatched() throws Exception {
  var c=conversation(); var run=UUID.randomUUID().toString();
  doAnswer(call -> {
   io.github.samzhu.bot.usage.WorkspaceAdmission.Dispatch d=call.getArgument(0);
   if(!run.equals(d.registration().runId())) return call.callRealMethod();
   return java.util.concurrent.CompletableFuture.completedFuture(new io.github.samzhu.bot.usage.WorkspaceAdmission.Decision.Denied(io.github.samzhu.bot.usage.WorkspaceAdmission.Reason.OVERLOADED));
  }).when(admission).dispatch(any());
  try {
   var response=post("/api/ag-ui",input(c,run,"injected admission overload"));
   assertEquals(200,response.statusCode()); assertTrue(response.body().contains("RUN_ERROR"),response.body());
   awaitAttemptOutcome(run,"NOT_DISPATCHED");
   assertEquals(0,model().getRecordedPrompts().size());
   assertEquals(0,jdbc.queryForObject("select count(*) from workspace_admission_claim c join provider_attempt a on a.id=c.attempt_id where a.run_id=?",Integer.class,run));
   assertEquals(1,jdbc.queryForObject("select count(*) from provider_attempt where run_id=?",Integer.class,run));
   verify(admission,times(1)).dispatch(argThat(d->d.registration().runId().equals(run)));
   verify(admission,atLeastOnce()).recoverClaim(argThat(r->r.runId().equals(run)),anyString());
  } finally { reset(admission); }
 }
 @Test void lostCommittedClaimResponseIsRecoveredWithoutProviderOrRedispatch() throws Exception {
  var c=conversation(); var run=UUID.randomUUID().toString();
  doAnswer(call -> {
   io.github.samzhu.bot.usage.WorkspaceAdmission.Dispatch d=call.getArgument(0);
   var actual=(java.util.concurrent.CompletionStage<io.github.samzhu.bot.usage.WorkspaceAdmission.Decision>)call.callRealMethod();
   if(!run.equals(d.registration().runId())) return actual;
   return actual.thenCompose(decision -> decision instanceof io.github.samzhu.bot.usage.WorkspaceAdmission.Decision.Allowed
     ? java.util.concurrent.CompletableFuture.failedFuture(new IllegalStateException("injected lost reply"))
     : java.util.concurrent.CompletableFuture.completedFuture(decision));
  }).when(admission).dispatch(any());
  try {
   var response=post("/api/ag-ui",input(c,run,"lost claim reply"));
   assertEquals(200,response.statusCode()); assertTrue(response.body().contains("RUN_ERROR"),response.body());
   awaitAttemptOutcome(run,"UNKNOWN");
   assertEquals(0,model().getRecordedPrompts().size());
   assertEquals(1,jdbc.queryForObject("select count(*) from workspace_admission_claim c join provider_attempt a on a.id=c.attempt_id where a.run_id=? and a.started_at is not null",Integer.class,run));
   assertEquals(1,jdbc.queryForObject("select count(*) from provider_attempt where run_id=?",Integer.class,run));
   verify(admission,times(1)).dispatch(argThat(d->d.registration().runId().equals(run)));
   verify(admission,atLeastOnce()).recoverClaim(argThat(r->r.runId().equals(run)),anyString());
  } finally { reset(admission); }
 }
 @Test void completedRunSettlesAttemptBeforeReleasingRegistration() throws Exception {
  var c=conversation(); var id=UUID.randomUUID().toString();
  var response=post("/api/ag-ui",input(c,id,"registration finalization"));
  assertEquals(200,response.statusCode()); assertTrue(response.body().contains("RUN_FINISHED"),response.body());
  assertEquals("SUCCEEDED",jdbc.queryForObject("select execution_outcome from provider_attempt where run_id=?",String.class,id));
  long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
  String status=null;
  while(System.nanoTime()<deadline) {
   status=jdbc.queryForObject("select status from workspace_admission_registration where run_id=?",String.class,id);
   if("TERMINAL".equals(status)) break;
   Thread.yield();
  }
  assertEquals("TERMINAL",status,"settled attempt must eventually release its registration");
 }
 @Test void terminalRunIsRescannedWhenCompletionNotificationIsLost() throws Exception {
  String c=conversation(), run=UUID.randomUUID().toString();
  var notification=new java.util.concurrent.CountDownLatch(1);
  doAnswer(call -> {
   io.github.samzhu.bot.usage.WorkspaceAdmission.Registration registration=call.getArgument(0);
   if (!run.equals(registration.runId())) return call.callRealMethod();
   notification.countDown();
   return java.util.concurrent.CompletableFuture.failedFuture(new IllegalStateException("injected lost completion notification"));
  }).when(admission).complete(any());
  try {
   var response=post("/api/ag-ui",input(c,run,"terminal notification lost"));
   assertEquals(200,response.statusCode()); assertTrue(response.body().contains("RUN_FINISHED"),response.body());
   assertTrue(notification.await(5,java.util.concurrent.TimeUnit.SECONDS),"initial completion notification was not exercised");
   assertEquals("COMPLETED",jdbc.queryForObject("select status from chat_run where id=?",String.class,run));
   assertEquals("SUCCEEDED",jdbc.queryForObject("select execution_outcome from provider_attempt where run_id=?",String.class,run));
   long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(10); String state=null;
   while(System.nanoTime()<deadline) {
    state=jdbc.queryForObject("select status from workspace_admission_registration where run_id=?",String.class,run);
    if("TERMINAL".equals(state)) break;
    Thread.yield();
   }
   assertEquals("TERMINAL",state,"durable terminal Run must be rescanned after a lost completion notification");
  } finally { reset(admission); }
 }
 @Test void cancellingWhileFinalizationReplyIsHeldPreservesDurableAttemptAndCompletesRun() throws Exception {
  String c=conversation(), run=UUID.randomUUID().toString();
  var committed=new java.util.concurrent.CountDownLatch(1);
  var release=new java.util.concurrent.CompletableFuture<Void>();
  var dropped=new java.util.concurrent.ConcurrentLinkedQueue<Throwable>();
  var events=new java.util.concurrent.ConcurrentLinkedQueue<String>();
  java.util.concurrent.CompletableFuture<HttpResponse<String>> pending=null;
  reactor.core.publisher.Hooks.onErrorDropped(dropped::add);
  doAnswer(call -> {
   io.github.samzhu.bot.usage.WorkspaceAdmission.FinalizeAttempt command=call.getArgument(0);
   if (run.equals(command.registration().runId())) events.add("finalize entered: "+command.kind());
   @SuppressWarnings("unchecked") var actual=(java.util.concurrent.CompletionStage<io.github.samzhu.bot.usage.WorkspaceAdmission.FinalizationResult>)call.callRealMethod();
   if (run.equals(command.registration().runId())) actual.whenComplete((result,error) -> events.add("finalize result "+command.kind()+": "+(error == null ? String.valueOf(result) : error.getClass().getSimpleName())));
   if (!run.equals(command.registration().runId()) || command.kind()!=io.github.samzhu.bot.usage.WorkspaceAdmission.TerminalKind.SUCCEEDED) return actual;
   return actual.thenCompose(result -> {
    events.add("success wrapper reached: "+result);
    if(result!=io.github.samzhu.bot.usage.WorkspaceAdmission.FinalizationResult.SETTLED) return java.util.concurrent.CompletableFuture.completedFuture(result);
    committed.countDown();
    return release.thenApply(ignored -> result);
   });
  }).when(admission).finalizeAttempt(any());
  try {
   var start=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/ag-ui"))
     .timeout(java.time.Duration.ofSeconds(15)).header("Content-Type","application/json")
     .POST(HttpRequest.BodyPublishers.ofString(input(c,run,"hold finalization response"))).build();
   pending=http.sendAsync(start,HttpResponse.BodyHandlers.ofString());
   assertTrue(committed.await(8,java.util.concurrent.TimeUnit.SECONDS),"SUCCEEDED settlement did not commit before cancellation");
   assertEquals("SUCCEEDED",jdbc.queryForObject("select execution_outcome from provider_attempt where run_id=?",String.class,run));
   var cancelRequest=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/runs/"+run+"/cancel"))
     .timeout(java.time.Duration.ofSeconds(10)).header("Content-Type","application/json")
     .POST(HttpRequest.BodyPublishers.ofString("{}")).build();
   var cancelled=http.send(cancelRequest,HttpResponse.BodyHandlers.ofString());
   assertEquals(200,cancelled.statusCode(),cancelled.body());
   assertTrue(json.readTree(cancelled.body()).path("cancelled").asBoolean(),cancelled.body());
   assertEquals("CANCELLED",jdbc.queryForObject("select status from chat_run where id=?",String.class,run));
   assertEquals("SUCCEEDED",jdbc.queryForObject("select execution_outcome from provider_attempt where run_id=?",String.class,run));
   release.complete(null);
   assertTrue(pending.get(10,java.util.concurrent.TimeUnit.SECONDS).body().contains("cancelled"));
   assertEquals(1,jdbc.queryForObject("select count(*) from workspace_admission_finalization f join provider_attempt a on a.id=f.attempt_id where a.run_id=? and f.terminal_kind='SUCCEEDED' and f.state='SETTLED'",Integer.class,run));
   assertEquals(1,jdbc.queryForObject("select count(*) from provider_attempt where run_id=?",Integer.class,run));
   assertEquals(1,model().getRecordedPrompts().size(),"cancellation must not cause a second provider invocation");
   assertTrue(dropped.isEmpty(),"subscriber cancellation must not drop a Reactor error: "+dropped);
   long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(8); String registration=null;
   while(System.nanoTime()<deadline) {
    registration=jdbc.queryForObject("select status from workspace_admission_registration where run_id=?",String.class,run);
    if("TERMINAL".equals(registration)) break;
    Thread.yield();
   }
   assertEquals("TERMINAL",registration,"released finalization must still allow durable registration completion");
  } finally {
   try {
    var snapshot=new LinkedHashMap<String,Object>();
    snapshot.put("finalizationEvents",events.toString());
    snapshot.put("latchRemaining",committed.getCount());
    snapshot.put("providerCalls",model().getRecordedPrompts().size());
    snapshot.put("providerSubscriptions",model().automaticSubscriptions());
    snapshot.put("providerDisposals",model().automaticDisposals());
    snapshot.put("droppedErrors",dropped.size());
    if(pending==null) snapshot.put("http","NOT_STARTED");
    else if(!pending.isDone()) snapshot.put("http","PENDING");
    else try { var response=pending.join(); snapshot.put("httpStatus",response.statusCode()); snapshot.put("httpBody",response.body()); }
      catch(Exception ex) { snapshot.put("httpException",ex.getClass().getSimpleName()); }
    var queries=Map.of(
     "run","select id, status from chat_run where id=?",
     "registration","select run_id,workspace_id,partition,status,pending_id,pending_epoch,user_cancel_requested_at from workspace_admission_registration where run_id=?",
     "attempt","select id,execution_outcome,execution_reason,started_at,projection_state from provider_attempt where run_id=?",
     "claim","select c.claim_id,c.attempt_id,c.status,c.period_start,c.period_end,c.generation from workspace_admission_claim c join workspace_admission_registration r on r.token=c.registration_token where r.run_id=?",
     "dispatch","select d.attempt_id,d.state from workspace_admission_dispatch d join provider_attempt a on a.id=d.attempt_id where a.run_id=?",
     "finalization","select f.attempt_id,f.terminal_kind,f.terminal_reason,f.state from workspace_admission_finalization f join provider_attempt a on a.id=f.attempt_id where a.run_id=?",
     "outbox","select effect_id,delivery_status,target_partition,pending_id,pending_epoch,claim_id,target_attempt_id from workspace_admission_effect_outbox where target_run_id=?");
    queries.forEach((key,sql) -> { try { snapshot.put(key,jdbc.queryForList(sql,run)); } catch(Exception ex) { snapshot.put(key,"ERROR:"+ex.getClass().getSimpleName()); } });
    snapshot.put("budget",jdbc.queryForList("select b.partition,b.enabled,b.cap_usd,b.config_version,p.period_start,p.known_subtotal_usd,p.terminal_effective_unknown,p.gate_generation from conversation c left join workspace_monthly_budget b on b.workspace_id=c.workspace_id left join workspace_monthly_budget_period p on p.workspace_id=b.workspace_id and p.partition=b.partition where c.id=?",c));
    System.out.println("CANCEL_SETTLEMENT_DIAGNOSTIC "+json.writeValueAsString(snapshot));
   } catch(Exception ex) { System.out.println("CANCEL_SETTLEMENT_DIAGNOSTIC_FAILED "+ex.getClass().getSimpleName()); }
   finally { release.complete(null); reactor.core.publisher.Hooks.resetOnErrorDropped(); reset(admission); }
  }
 }
 @Test void knownRolledBackAttemptBeginEventuallyReleasesFailedRunRegistration() throws Exception {
  String conversation=conversation(), run=UUID.randomUUID().toString();
  jdbc.execute("create function test_reject_attempt_begin() returns trigger language plpgsql as $$ begin if new.run_id='"+run+"' then raise exception 'injected attempt insert failure'; end if; return new; end $$");
  jdbc.execute("create trigger test_reject_attempt_begin before insert on provider_attempt for each row execute function test_reject_attempt_begin()");
  try {
   var response=post("/api/ag-ui",input(conversation,run,"attempt begin rollback"));
   assertEquals(200,response.statusCode());
   assertTrue(response.body().contains("RUN_ERROR"),response.body());
   assertEquals("FAILED",jdbc.queryForObject("select status from chat_run where id=?",String.class,run));
   assertEquals(0,jdbc.queryForObject("select count(*) from provider_attempt where run_id=?",Integer.class,run));
   assertEquals(0,model().getRecordedPrompts().size());
   long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(8); String state=null;
   while(System.nanoTime()<deadline) {
    state=jdbc.queryForObject("select status from workspace_admission_registration where run_id=?",String.class,run);
    if("TERMINAL".equals(state)) break;
    Thread.yield();
   }
   assertEquals("TERMINAL",state,"a known rollback and durable Run failure must not leave an orphan registration");
  } finally {
   jdbc.execute("drop trigger if exists test_reject_attempt_begin on provider_attempt");
   jdbc.execute("drop function if exists test_reject_attempt_begin()");
  }
 }
 @Test void monthlyCapDeliversDurableEffectToRealExecutionAndReplaysExactReceipt() throws Exception {
  String c=conversation(), id=UUID.randomUUID().toString();
  String workspace=jdbc.queryForObject("select workspace_id from conversation where id=?",String.class,c);
  var scope=new io.github.samzhu.bot.usage.WorkspaceMonthlyBudgetOperations.Scope(workspace,
    io.github.samzhu.bot.usage.WorkspaceAdmission.Partition.SIMULATION,
    java.time.YearMonth.from(usageClock.instant().atZone(java.time.ZoneId.of("Asia/Taipei"))));
  var previous=budgets.read(new io.github.samzhu.bot.usage.WorkspaceMonthlyBudgetOperations.ReadCommand(scope,"dev-user")).toCompletableFuture().join();
  var disabled=budgets.configure(new io.github.samzhu.bot.usage.WorkspaceMonthlyBudgetOperations.ConfigureCommand(scope,"dev-user",false,previous.capUsd(),previous.configVersion())).toCompletableFuture().join();
  model().setHoldMode(true);
  try {
   var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/ag-ui")).header("Content-Type","application/json")
     .POST(HttpRequest.BodyPublishers.ofString(input(c,id,"budget cancellation"))).build();
   var pending=http.sendAsync(request,HttpResponse.BodyHandlers.ofString());
   assertTrue(model().awaitSubscribed(5,java.util.concurrent.TimeUnit.SECONDS),"provider did not claim before cap change");
   budgets.configure(new io.github.samzhu.bot.usage.WorkspaceMonthlyBudgetOperations.ConfigureCommand(scope,"dev-user",true,java.math.BigDecimal.ZERO,disabled.configVersion())).toCompletableFuture().join();
   var response=pending.get(10,java.util.concurrent.TimeUnit.SECONDS);
   assertEquals(200,response.statusCode()); assertTrue(response.body().contains("cancelled"),response.body());
   assertEquals("CANCELLED",jdbc.queryForObject("select status from chat_run where id=?",String.class,id));
   assertTrue(model().awaitCancelled(5,java.util.concurrent.TimeUnit.SECONDS));
   long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(8); String status=null;
   while(System.nanoTime()<deadline) {
    var rows=jdbc.query("select delivery_status from workspace_admission_effect_outbox where target_run_id=? and claim_id is not null",(rs,n)->rs.getString(1),id);
    if(!rows.isEmpty()) { status=rows.getFirst(); if("ACKNOWLEDGED".equals(status)) break; }
    Thread.yield();
   }
   assertEquals("ACKNOWLEDGED",status,"real sink must commit receipt before usage acknowledges");
   String effectId=jdbc.queryForObject("select effect_id from workspace_admission_effect_outbox where target_run_id=? and claim_id is not null",String.class,id);
   var found=assertInstanceOf(io.github.samzhu.bot.usage.WorkspaceAdmission.EffectInspection.Found.class,admission.inspectCancellationEffect(effectId).toCompletableFuture().join()).effect();
   var effect=new io.github.samzhu.bot.usage.BudgetCancellationSink.Effect(effectId,found.token(),found.workspaceId(),found.partition(),id,found.target(),found.intentReceipt());
   var replay=assertInstanceOf(io.github.samzhu.bot.usage.BudgetCancellationSink.Result.Accepted.class,budgetSink.cancel(effect).toCompletableFuture().join());
   assertEquals(found.ackReceipt(),replay.receiptId());
   assertEquals(replay.receiptId(),jdbc.queryForObject("select acceptance_receipt from execution_budget_cancellation_receipt where effect_id=?",String.class,effectId));
   var claimed=assertInstanceOf(io.github.samzhu.bot.usage.BudgetCancellationSink.Target.Claimed.class,found.target()).fence();
   var forged=new io.github.samzhu.bot.usage.WorkspaceAdmission.ClaimFence(claimed.token(),claimed.claimId(),claimed.attemptId(),"different-workspace",claimed.partition(),claimed.periodStart(),claimed.periodEnd(),claimed.policyGeneration());
   var mismatched=new io.github.samzhu.bot.usage.BudgetCancellationSink.Effect(effectId,found.token(),found.workspaceId(),found.partition(),id,new io.github.samzhu.bot.usage.BudgetCancellationSink.Target.Claimed(forged),found.intentReceipt());
   assertInstanceOf(io.github.samzhu.bot.usage.BudgetCancellationSink.Result.Retry.class,budgetSink.cancel(mismatched).toCompletableFuture().join());
  } finally {
   model().complete();
   var current=budgets.read(new io.github.samzhu.bot.usage.WorkspaceMonthlyBudgetOperations.ReadCommand(scope,"dev-user")).toCompletableFuture().join();
   budgets.configure(new io.github.samzhu.bot.usage.WorkspaceMonthlyBudgetOperations.ConfigureCommand(scope,"dev-user",previous.enabled(),previous.capUsd(),current.configVersion())).toCompletableFuture().join();
  }
 }
 @Test void failedAttemptFinalizationRetainsRegistrationAndBlocksNewDispatchUntilRecovery() throws Exception {
  var c=conversation(); var id=UUID.randomUUID().toString();
  jdbc.execute("create or replace function test_fail_attempt_terminal() returns trigger language plpgsql as $$ begin if new.run_id = '"+id+"' and new.execution_outcome <> 'IN_PROGRESS' then raise exception 'injected terminal write failure'; end if; return new; end $$");
  jdbc.execute("create trigger test_fail_attempt_terminal before update on provider_attempt for each row execute function test_fail_attempt_terminal()");
  try {
   var response=post("/api/ag-ui",input(c,id,"failure recovery"));
   assertEquals(200,response.statusCode());
   var row=jdbc.queryForMap("select token,workspace_id,actor_id,conversation_id,partition from workspace_admission_registration where run_id=?",id);
   var registration=new io.github.samzhu.bot.usage.WorkspaceAdmission.Registration((String)row.get("token"),(String)row.get("workspace_id"),id,(String)row.get("actor_id"),(String)row.get("conversation_id"),io.github.samzhu.bot.usage.WorkspaceAdmission.Partition.valueOf((String)row.get("partition")));
   long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
   while(System.nanoTime()<deadline && admission.cancellationState(registration).toCompletableFuture().join().state()!=io.github.samzhu.bot.usage.WorkspaceAdmission.CancellationState.UNKNOWN) Thread.yield();
   assertEquals(io.github.samzhu.bot.usage.WorkspaceAdmission.CancellationState.UNKNOWN,admission.cancellationState(registration).toCompletableFuture().join().state());
   assertEquals("IN_PROGRESS",jdbc.queryForObject("select execution_outcome from provider_attempt where run_id=?",String.class,id));
   assertNotEquals("TERMINAL",jdbc.queryForObject("select status from workspace_admission_registration where run_id=?",String.class,id));
   int calls=model().getRecordedPrompts().size();
   var next=post("/api/ag-ui",input(conversation(),UUID.randomUUID().toString(),"must stay closed"));
   assertNotEquals(200,next.statusCode(),next.body());
   assertEquals(calls,model().getRecordedPrompts().size());
   jdbc.execute("drop trigger test_fail_attempt_terminal on provider_attempt");
   String attempt=jdbc.queryForObject("select id from provider_attempt where run_id=?",String.class,id);
   assertEquals(io.github.samzhu.bot.usage.WorkspaceAdmission.FinalizationResult.SETTLED,admission.recoverFinalization(registration,attempt).toCompletableFuture().join());
   assertEquals(io.github.samzhu.bot.usage.WorkspaceAdmission.FinalizationResult.SETTLED,admission.recoverFinalization(registration,attempt).toCompletableFuture().join());
   var inspected=admission.inspectClaim(registration,attempt).toCompletableFuture().join();
   assertInstanceOf(io.github.samzhu.bot.usage.WorkspaceAdmission.ClaimInspection.Found.class,inspected);
   assertEquals("SUCCEEDED",jdbc.queryForObject("select execution_outcome from provider_attempt where id=?",String.class,attempt));
   long recoveryDeadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(8);
   String registrationStatus=null;
   while(System.nanoTime()<recoveryDeadline) {
    registrationStatus=jdbc.queryForObject("select status from workspace_admission_registration where run_id=?",String.class,id);
    if("TERMINAL".equals(registrationStatus)) break;
    Thread.yield();
   }
   assertEquals("TERMINAL",registrationStatus,"durable recovery must eventually complete the terminal Run registration without a test-only complete call");
  } finally {
   jdbc.execute("drop trigger if exists test_fail_attempt_terminal on provider_attempt");
   jdbc.execute("drop function if exists test_fail_attempt_terminal()");
  }
 }
 @Test void streamsStandardEventsAndDoesNotRepeatDuplicate() throws Exception {
  var c=conversation(); var id=UUID.randomUUID().toString(); var body=input(c,id,"hello"); var r=post("/api/ag-ui",body);
  assertEquals(200,r.statusCode()); assertTrue(r.headers().firstValue("content-type").orElse("").startsWith("text/event-stream"));
  var events=new ArrayList<JsonNode>(); for(var line:r.body().split("\\R")) if(line.startsWith("data:")) events.add(json.readTree(line.substring(5).trim()));
  assertEquals("RUN_STARTED",events.getFirst().path("type").asText()); assertEquals(id,events.getFirst().path("runId").asText());
  var start=events.stream().filter(e->e.path("type").asText().equals("TEXT_MESSAGE_START")).findFirst().orElseThrow();
  assertEquals("RUN_FINISHED",events.getLast().path("type").asText());
  assertEquals(start.path("messageId").asText(),jdbc.queryForObject("select id from message where run_id=? and role='ASSISTANT'",String.class,id));
  var repeat=post("/api/ag-ui",body); assertEquals(409,repeat.statusCode()); assertEquals("DUPLICATE_RUN",json.readTree(repeat.body()).path("code").asText());
  assertEquals(1,model().getRecordedPrompts().size());
  assertEquals(1,jdbc.queryForObject("select count(*) from chat_run where id=?",Integer.class,id));
  assertEquals(1,jdbc.queryForObject("select count(*) from ai_session where id=?",Integer.class,c));
 }
 @Test void rejectsIdCollisionAndConflictingInputWithoutLeak() throws Exception {
  var a=conversation(); var b=conversation(); var id=UUID.randomUUID().toString();
  assertEquals(200,post("/api/ag-ui",input(a,id,"first")).statusCode());
  var same=post("/api/ag-ui",input(a,id,"changed")); assertEquals(409,same.statusCode()); assertEquals("RUN_CONFLICT",json.readTree(same.body()).path("code").asText());
  jdbc.update("update conversation set user_id=? where id=?", "another-owner", a);
  var other=post("/api/ag-ui",input(b,id,"first")); assertEquals(409,other.statusCode()); assertEquals("RUN_CONFLICT",json.readTree(other.body()).path("code").asText()); assertFalse(other.body().contains(a)); assertFalse(other.body().contains(id));
  assertEquals(1,model().getRecordedPrompts().size());
  assertEquals(1,jdbc.queryForObject("select count(*) from chat_run where id=?",Integer.class,id));
  assertEquals(0,jdbc.queryForObject("select count(*) from ai_session where id=?",Integer.class,b));
 }
 @Test void collisionWithUncertainRunReaderFailsClosedWithoutNewSideEffects() throws Exception {
  var a=conversation(); var b=conversation(); var id=UUID.randomUUID().toString();
  assertEquals(200,post("/api/ag-ui",input(a,id,"first")).statusCode());
  var mode=new java.util.concurrent.atomic.AtomicReference<String>("OFF");
  var collisionFaults=new java.util.concurrent.ConcurrentHashMap<String,java.util.concurrent.atomic.AtomicInteger>();
  var readerEvents=new java.util.concurrent.ConcurrentLinkedQueue<String>();
  doAnswer(call -> {
   if(!id.equals(call.getArgument(0))) return call.callRealMethod();
   String active=mode.get();
   if(active.equals("OFF")) return call.callRealMethod();
   var callers=StackWalker.getInstance().walk(frames -> frames
     .filter(frame -> frame.getClassName().startsWith("io.github.samzhu.bot.usage.internal"))
     .map(frame -> frame.getClassName()+"."+frame.getMethodName()).distinct().limit(8).toList());
   if(callers.stream().anyMatch(name -> name.contains("DefaultWorkspaceAdmission.lambda$register$")))
    collisionFaults.computeIfAbsent(active,ignored -> new java.util.concurrent.atomic.AtomicInteger()).incrementAndGet();
   readerEvents.add("run="+id+" thread="+Thread.currentThread().getName()+" result="+active+" callers="+callers);
   if(active.equals("NOT_FOUND")) return new io.github.samzhu.bot.usage.RunAdmissionStateReader.State(io.github.samzhu.bot.usage.RunAdmissionStateReader.Status.NOT_FOUND,null);
   throw new IllegalStateException("injected reader unavailable");
  }).when(runAdmissionReader).readForUpdate(anyString());
  try {
   for(String fault:List.of("NOT_FOUND","EXCEPTION")) {
    HttpResponse<String> response;
    mode.set(fault);
    try { response=post("/api/ag-ui",input(b,id,"first")); }
    finally { mode.set("OFF"); }
    assertEquals(503,response.statusCode(),response.body());
    assertEquals(fault.equals("NOT_FOUND") ? "RECOVERY_REQUIRED" : "ADMISSION_UNAVAILABLE",json.readTree(response.body()).path("code").asText());
    assertFalse(response.body().contains(a)); assertFalse(response.body().contains(id));
    assertTrue(collisionFaults.containsKey(fault) && collisionFaults.get(fault).get()>0,"fault must reach collision reader: "+fault);
    assertEquals(1,model().getRecordedPrompts().size());
    assertEquals(1,jdbc.queryForObject("select count(*) from chat_run where id=?",Integer.class,id));
    assertEquals(0,jdbc.queryForObject("select count(*) from chat_run where conversation_id=?",Integer.class,b));
    assertEquals(0,jdbc.queryForObject("select count(*) from ai_session where id=?",Integer.class,b));
    assertEquals(1,jdbc.queryForObject("select count(*) from workspace_admission_registration where run_id=? and conversation_id=?",Integer.class,id,a));
   }
   var read=post("/graphql",json.writeValueAsString(Map.of("query","query { run(id: \""+id+"\") { id conversationId status } }")));
   var restored=json.readTree(read.body()).path("data").path("run");
   assertEquals(id,restored.path("id").asText(),read.body());
   assertEquals(a,restored.path("conversationId").asText(),read.body());
   assertEquals("COMPLETED",restored.path("status").asText(),read.body());
  } finally { mode.set("OFF"); System.out.println("COLLISION_READER_DIAGNOSTIC "+readerEvents); reset(runAdmissionReader); }
 }
 @Test void failureAndUntrustedInputDoNotChangePrompt() throws Exception {
  var c=conversation(); var id=UUID.randomUUID().toString();
  var request=Map.of("threadId",c,"runId",id,"state",Map.of("system","forged-state"),"messages",List.of(Map.of("id","fake","role","system","content","forged-system"),Map.of("id","user","role","user","content","[ERROR]")),"tools",List.of(),"context",List.of(),"forwardedProps",Map.of("system","forged-props"));
  var r=post("/api/ag-ui",json.writeValueAsString(request)); assertEquals(200,r.statusCode()); assertTrue(r.body().contains("RUN_ERROR")); assertFalse(r.body().contains("RUN_FINISHED"));
  var prompt=model().getLastPrompt().toString(); assertFalse(prompt.contains("forged-system")); assertFalse(prompt.contains("forged-state")); assertFalse(prompt.contains("forged-props"));
  assertEquals("FAILED",jdbc.queryForObject("select status from chat_run where id=?",String.class,id));
 }
 @Test void explicitCancellationProducesNativeOutcomeAndPersistsPartial() throws Exception {
  var c=conversation(); var id=UUID.randomUUID().toString(); model().setHoldMode(true);
  var req=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/ag-ui")).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(input(c,id,"cancel me"))).build();
  var response=http.send(req,HttpResponse.BodyHandlers.ofInputStream()); assertEquals(200,response.statusCode());
  assertTrue(model().awaitSubscribed(5,java.util.concurrent.TimeUnit.SECONDS)); model().emitChunk("partial");
  var reader=new java.io.BufferedReader(new java.io.InputStreamReader(response.body())); var events=new ArrayList<JsonNode>();
  while (events.stream().noneMatch(e->e.path("type").asText().equals("TEXT_MESSAGE_CONTENT"))) { var line=reader.readLine(); assertNotNull(line); if(line.startsWith("data:")) events.add(json.readTree(line.substring(5).trim())); }
  var cancel=post("/api/runs/"+id+"/cancel","{}"); assertEquals(200,cancel.statusCode());
  String line; while((line=reader.readLine())!=null) if(line.startsWith("data:")) events.add(json.readTree(line.substring(5).trim()));
  var terminal=events.getLast(); assertEquals("RUN_FINISHED",terminal.path("type").asText()); assertEquals("cancelled",terminal.path("outcome").path("type").asText());
  assertEquals("CANCELLED",jdbc.queryForObject("select status from chat_run where id=?",String.class,id));
  assertEquals(Boolean.TRUE,jdbc.queryForObject("select interrupted from message where run_id=? and role='ASSISTANT'",Boolean.class,id));
  assertEquals(events.stream().filter(e->e.path("type").asText().equals("TEXT_MESSAGE_START")).findFirst().orElseThrow().path("messageId").asText(),jdbc.queryForObject("select id from message where run_id=? and role='ASSISTANT'",String.class,id));
 }
 @Test void emptyResponseIsFailureAndDoesNotWriteAssistant() throws Exception {
  var c=conversation(); var id=UUID.randomUUID().toString(); model().setHoldMode(true);
  var req=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/ag-ui")).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(input(c,id,"empty"))).build();
  var future=http.sendAsync(req,HttpResponse.BodyHandlers.ofString()); assertTrue(model().awaitSubscribed(5,java.util.concurrent.TimeUnit.SECONDS)); model().complete();
  var response=future.get(5,java.util.concurrent.TimeUnit.SECONDS); assertTrue(response.body().contains("RUN_ERROR")); assertFalse(response.body().contains("RUN_FINISHED"));
  assertEquals("FAILED",jdbc.queryForObject("select status from chat_run where id=?",String.class,id));
  assertEquals(0,jdbc.queryForObject("select count(*) from message where run_id=? and role='ASSISTANT'",Integer.class,id));
 }
 @Test void textPartsAcceptedAndUnsupportedToolRejected() throws Exception {
  var c=conversation(); var id=UUID.randomUUID().toString();
  var body=Map.of("threadId",c,"runId",id,"messages",List.of(Map.of("id","u","role","user","content",List.of(Map.of("type","text","text","first "),Map.of("type","text","text","second")))),"tools",List.of(),"context",List.of(),"state",Map.of(),"forwardedProps",Map.of());
  assertEquals(200,post("/api/ag-ui",json.writeValueAsString(body)).statusCode()); assertTrue(model().getLastPrompt().toString().contains("first second"));
  var unsupported=Map.of("threadId",c,"runId",UUID.randomUUID().toString(),"messages",List.of(Map.of("id","u","role","user","content","hi")),"tools",List.of(Map.of("name","bad")));
  assertEquals(400,post("/api/ag-ui",json.writeValueAsString(unsupported)).statusCode());
 }
 @Test void activeRunRejectsAnotherAndFailurePreservesPartial() throws Exception {
  var c=conversation(); var id=UUID.randomUUID().toString(); model().setHoldMode(true);
  var req=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/ag-ui")).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(input(c,id,"first"))).build();
  var response=http.send(req,HttpResponse.BodyHandlers.ofInputStream()); assertTrue(model().awaitSubscribed(5,java.util.concurrent.TimeUnit.SECONDS));
  assertEquals(409,post("/api/ag-ui",input(c,UUID.randomUUID().toString(),"second")).statusCode());
  model().emitChunk("partial failure");
  var reader=new java.io.BufferedReader(new java.io.InputStreamReader(response.body())); var events=new ArrayList<JsonNode>();
  while(events.stream().noneMatch(e->e.path("type").asText().equals("TEXT_MESSAGE_CONTENT"))) { var line=reader.readLine(); assertNotNull(line); if(line.startsWith("data:")) events.add(json.readTree(line.substring(5).trim())); }
  model().error(new IllegalStateException("fake failure")); String line; while((line=reader.readLine())!=null) if(line.startsWith("data:")) events.add(json.readTree(line.substring(5).trim()));
  assertEquals("RUN_ERROR",events.getLast().path("type").asText()); assertEquals(1,events.stream().filter(e->e.path("type").asText().equals("RUN_ERROR")).count());
  assertEquals("FAILED",jdbc.queryForObject("select status from chat_run where id=?",String.class,id));
  assertEquals(Boolean.TRUE,jdbc.queryForObject("select interrupted from message where run_id=? and role='ASSISTANT'",Boolean.class,id));
 }
 @Test void cancellationBeforeRegistryRegistrationDoesNotDispatchModel() throws Exception {
  assertCancellationDuringRegistration(false);
 }

 @Test void cancellationAfterRegistrationBeforeStartedDoesNotDispatchModel() throws Exception {
  assertCancellationDuringRegistration(true);
 }

 private void assertCancellationDuringRegistration(boolean afterRegister) throws Exception {
  var c=conversation(); var id=UUID.randomUUID().toString();
  var reachedRegistration=new java.util.concurrent.CountDownLatch(1);
  var cancelReachedGate=new java.util.concurrent.CountDownLatch(1);
  var gateCalls=new java.util.concurrent.atomic.AtomicInteger();
  doAnswer(invocation -> {
   var gate=invocation.callRealMethod();
   if (gateCalls.incrementAndGet() == 2) cancelReachedGate.countDown();
   return gate;
  }).when(registry).lifecycleGate(eq(id));
  var allowRegistration=new java.util.concurrent.CountDownLatch(1);
  var allowPrompt=new java.util.concurrent.CountDownLatch(1);
  doAnswer(invocation -> {
   Object result = afterRegister ? invocation.callRealMethod() : null;
   reachedRegistration.countDown();
   if (!allowRegistration.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("registration gate timed out");
   return afterRegister ? result : invocation.callRealMethod();
  }).when(registry).register(eq(id), any());
  doAnswer(invocation -> {
   if (!allowPrompt.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("prompt gate timed out");
   return invocation.callRealMethod();
  }).when(botOperations).getEffectiveSystemPrompt(anyString(), anyString());
  model().setHoldMode(true);
  var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/ag-ui"))
   .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(input(c,id,"early cancel"))).build();
  var response=http.sendAsync(request,HttpResponse.BodyHandlers.ofString());
  try {
   assertTrue(reachedRegistration.await(5,java.util.concurrent.TimeUnit.SECONDS));
   var cancelRequest=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/runs/"+id+"/cancel"))
    .POST(HttpRequest.BodyPublishers.noBody()).build();
   var cancelled=http.sendAsync(cancelRequest,HttpResponse.BodyHandlers.ofString());
   assertTrue(cancelReachedGate.await(5,java.util.concurrent.TimeUnit.SECONDS));
   allowRegistration.countDown();
   assertEquals("CANCELLED",json.readTree(cancelled.get(5,java.util.concurrent.TimeUnit.SECONDS).body()).path("status").asText());
  } finally { allowRegistration.countDown(); allowPrompt.countDown(); }
  var stream=response.get(5,java.util.concurrent.TimeUnit.SECONDS);
  assertTrue(model().getRecordedPrompts().isEmpty(), "model must not start after cancellation");
  var events=new ArrayList<JsonNode>();
  for(var line:stream.body().split("\\R")) if(line.startsWith("data:")) events.add(json.readTree(line.substring(5).trim()));
  assertEquals(java.util.List.of("RUN_STARTED","RUN_FINISHED"),events.stream().map(e->e.path("type").asText()).toList());
  assertEquals("cancelled",events.getLast().path("outcome").path("type").asText());
  assertEquals("CANCELLED", jdbc.queryForObject("select status from chat_run where id=?", String.class, id));
  assertEquals(0, jdbc.queryForObject("select count(*) from ai_session_event where session_id=?", Integer.class, c));
 }

 @Test void blockedRegistrationDoesNotBlockAnotherRunOnAnotherStripe() throws Exception {
  var a=conversation(); var b=conversation(); var first=UUID.randomUUID().toString();
  String candidate;
  do { candidate=UUID.randomUUID().toString(); }
  while (Math.floorMod(first.hashCode(),256)==Math.floorMod(candidate.hashCode(),256));
  var second=candidate;
  var entered=new java.util.concurrent.CountDownLatch(1);
  var release=new java.util.concurrent.CountDownLatch(1);
  doAnswer(invocation -> {
   entered.countDown();
   if (!release.await(5,java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("gate timed out");
   return invocation.callRealMethod();
  }).when(registry).register(eq(first),any());
  var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/ag-ui"))
   .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(input(a,first,"first"))).build();
  var pending=http.sendAsync(request,HttpResponse.BodyHandlers.ofString());
  try {
   assertTrue(entered.await(5,java.util.concurrent.TimeUnit.SECONDS));
   var other=post("/api/ag-ui",input(b,second,"second"));
   assertEquals(200,other.statusCode());
   assertTrue(other.body().contains("RUN_FINISHED"));
  } finally { release.countDown(); }
  assertEquals(200,pending.get(5,java.util.concurrent.TimeUnit.SECONDS).statusCode());
 }
 @Test void cancellationClaimPrecedesReleaseOfLifecycleGate() throws Exception {
  var c=conversation(); var id=UUID.randomUUID().toString();
  var promptEntered=new java.util.concurrent.CountDownLatch(1);
  var releasePrompt=new java.util.concurrent.CountDownLatch(1);
  var cancelUnlocked=new java.util.concurrent.CountDownLatch(1);
  var releaseCancel=new java.util.concurrent.CountDownLatch(1);
  var calls=new java.util.concurrent.atomic.AtomicInteger();
  class Gate extends java.util.concurrent.locks.ReentrantLock {
   volatile Thread cancelling;
   Gate() { super(true); }
   @Override public void lock() { if(calls.incrementAndGet()==2) cancelling=Thread.currentThread(); super.lock(); }
   @Override public void unlock() {
    super.unlock();
    if(Thread.currentThread()==cancelling) {
     cancelUnlocked.countDown();
     try { if(!releaseCancel.await(5,java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("cancel gate timeout"); }
     catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    }
   }
  }
  doReturn(new Gate()).when(registry).lifecycleGate(eq(id));
  doAnswer(invocation -> {
   promptEntered.countDown();
   if(!releasePrompt.await(5,java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("prompt timeout");
   return invocation.callRealMethod();
  }).when(botOperations).getEffectiveSystemPrompt(anyString(),anyString());
  model().setHoldMode(true);
  var req=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/ag-ui"))
   .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(input(c,id,"claimed cancel"))).build();
  var response=http.sendAsync(req,HttpResponse.BodyHandlers.ofString());
  boolean subscribed;
  try {
   assertTrue(promptEntered.await(5,java.util.concurrent.TimeUnit.SECONDS));
   var cancelReq=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/runs/"+id+"/cancel"))
    .POST(HttpRequest.BodyPublishers.noBody()).build();
   var cancel=http.sendAsync(cancelReq,HttpResponse.BodyHandlers.ofString());
   assertTrue(cancelUnlocked.await(5,java.util.concurrent.TimeUnit.SECONDS));
   releasePrompt.countDown();
   subscribed=model().awaitSubscribed(2,java.util.concurrent.TimeUnit.SECONDS);
   releaseCancel.countDown();
   assertEquals("CANCELLED",json.readTree(cancel.get(5,java.util.concurrent.TimeUnit.SECONDS).body()).path("status").asText());
  } finally { releasePrompt.countDown(); releaseCancel.countDown(); }
  var stream=response.get(5,java.util.concurrent.TimeUnit.SECONDS);
  assertFalse(subscribed,"model started after cancellation acquired and released the lifecycle gate: "+stream.body());
  assertTrue(stream.body().contains("cancelled"));
 }
 @Test void unavailableTerminalWritesLeaveRunActiveUntilExplicitCancellationAfterRecovery() throws Exception {
  var c=conversation(); var id=UUID.randomUUID().toString();
  var finalLookup=new java.util.concurrent.CountDownLatch(1);
  doThrow(new org.springframework.dao.DataAccessResourceFailureException("synthetic session outage"))
   .when(sessions).appendEvents(eq(c),anyList(),anyLong());
  doAnswer(invocation -> {
   if ("FAILED".equals(invocation.getArgument(1))) throw new org.springframework.dao.DataAccessResourceFailureException("synthetic fallback outage");
   return invocation.callRealMethod();
  }).when(runs).updateTerminalStatus(eq(id),anyString(),any(),any());
  doAnswer(invocation -> {
   finalLookup.countDown();
   throw new org.springframework.dao.DataAccessResourceFailureException("synthetic lookup outage");
  }).when(runs).findById(eq(id));
  var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/ag-ui"))
   .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(input(c,id,"simulate outage"))).build();
  var response=http.sendAsync(request,HttpResponse.BodyHandlers.ofString());
  assertTrue(finalLookup.await(5,java.util.concurrent.TimeUnit.SECONDS));
  try { response.get(5,java.util.concurrent.TimeUnit.SECONDS); } catch (java.util.concurrent.ExecutionException ignored) { }
  assertEquals("RUNNING",jdbc.queryForObject("select status from chat_run where id=?",String.class,id));
  assertEquals(0,jdbc.queryForObject("select count(*) from ai_session_event where session_id=?",Integer.class,c));
  reset(runs);
  var cancel=post("/api/runs/"+id+"/cancel","{}");
  assertEquals("CANCELLED",json.readTree(cancel.body()).path("status").asText());
  assertEquals("CANCELLED",jdbc.queryForObject("select status from chat_run where id=?",String.class,id));
 }
}
