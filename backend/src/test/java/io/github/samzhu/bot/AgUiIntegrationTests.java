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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class AgUiIntegrationTests {
 @LocalServerPort int port;
 @Autowired JdbcTemplate jdbc;
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
 }
 @Test void rejectsIdCollisionAndConflictingInputWithoutLeak() throws Exception {
  var a=conversation(); var b=conversation(); var id=UUID.randomUUID().toString();
  assertEquals(200,post("/api/ag-ui",input(a,id,"first")).statusCode());
  var same=post("/api/ag-ui",input(a,id,"changed")); assertEquals(409,same.statusCode()); assertEquals("RUN_CONFLICT",json.readTree(same.body()).path("code").asText());
  jdbc.update("update conversation set user_id=? where id=?", "another-owner", a);
  var other=post("/api/ag-ui",input(b,id,"first")); assertEquals(409,other.statusCode()); assertEquals("RUN_CONFLICT",json.readTree(other.body()).path("code").asText()); assertFalse(other.body().contains(a)); assertFalse(other.body().contains(id));
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