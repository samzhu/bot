package io.github.samzhu.bot;
import com.fasterxml.jackson.databind.*;
import io.github.samzhu.bot.identity.CurrentUserProvider;
import org.junit.jupiter.api.*;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import java.net.URI;
import java.net.http.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class WorkspaceAuthorizationIntegrationTests {
 @LocalServerPort int port;
 @Autowired JdbcTemplate jdbc;
 @Autowired javax.sql.DataSource dataSource;
 @MockitoBean CurrentUserProvider actor;
 @TestBean(name="mockChatModel",methodName="model",enforceOverride=true) ChatModel chat;
 static ControlledChatModel model(){return ControlledChatModel.getInstance();}
 final ObjectMapper json=new ObjectMapper(); final HttpClient http=HttpClient.newHttpClient();
 @BeforeEach void setup(){when(actor.getCurrentUserId()).thenReturn("workspace-test-user");model().reset();jdbc.update("insert into bot(id,name,target) values ('workspace-test-bot','test','LOCAL') on conflict do nothing");}
 HttpResponse<String> post(String path,Object body)throws Exception{return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());}
 JsonNode graph(String query)throws Exception{return json.readTree(post("/graphql",Map.of("query",query)).body());}
 @Test void personalAndInvalidScopeAreDistinct()throws Exception{
  var result=graph("{ workspaces { id name role personal } }"); assertFalse(result.has("errors"),result.toString()); assertEquals(1,result.path("data").path("workspaces").size()); assertTrue(result.path("data").path("workspaces").get(0).path("personal").asBoolean());
  var denied=graph("{ conversations(workspaceId: \"no-such-workspace\") { id } }"); assertEquals("WORKSPACE_ACCESS_DENIED",denied.path("errors").get(0).path("extensions").path("code").asText());
 }
 @Test void deniedStartHasNoSideEffects()throws Exception{
  var r=post("/api/ag-ui",Map.of("threadId","missing-thread","runId",UUID.randomUUID().toString(),"messages",List.of(Map.of("id","u","role","user","content","hello"))));
  assertEquals(404,r.statusCode()); assertEquals("CONVERSATION_ACCESS_DENIED",json.readTree(r.body()).path("code").asText()); assertTrue(model().getRecordedPrompts().isEmpty()); assertEquals(0,jdbc.queryForObject("select count(*) from chat_run where conversation_id='missing-thread'",Integer.class));
 }
 @Test void scopeOwnerAndRevocationProtectAllPaths() throws Exception {
  String actorId="scope-owner"; when(actor.getCurrentUserId()).thenReturn(actorId);
  jdbc.update("insert into workspace(id,name) values ('shared-test','Shared') on conflict do nothing");
  jdbc.update("insert into workspace_member(workspace_id,user_id,role,active) values ('shared-test',?,'MEMBER',true),('shared-test','scope-manager','MANAGER',true) on conflict (workspace_id,user_id) do update set active=true",actorId);
  var created=graph("mutation { createConversation(botId: \"workspace-test-bot\", workspaceId: \"shared-test\") { id workspaceId } }");
  assertFalse(created.has("errors"),created.toString()); String id=created.path("data").path("createConversation").path("id").asText(); assertEquals("shared-test",created.path("data").path("createConversation").path("workspaceId").asText());
  assertEquals(0,graph("{ conversations { id } }").path("data").path("conversations").size());
  assertEquals(0,graph("{ conversations(workspaceId: null) { id } }").path("data").path("conversations").size());
  assertEquals(1,graph("{ conversations(workspaceId: \"shared-test\") { id } }").path("data").path("conversations").size());
  when(actor.getCurrentUserId()).thenReturn("scope-manager");
  assertTrue(graph("{ conversation(id: \""+id+"\") { id messages { id } runs { id } } }").path("data").path("conversation").isNull());
  assertEquals("CONVERSATION_ACCESS_DENIED",graph("mutation { setConversationPinned(id: \""+id+"\", pinned:true) { id } }").path("errors").get(0).path("extensions").path("code").asText());
  var r=post("/api/ag-ui",Map.of("threadId",id,"runId",UUID.randomUUID().toString(),"messages",List.of(Map.of("id","u","role","user","content","hello"))));
  assertEquals(404,r.statusCode()); assertEquals("CONVERSATION_ACCESS_DENIED",json.readTree(r.body()).path("code").asText()); assertFalse(r.body().contains(id)); assertEquals(0,jdbc.queryForObject("select count(*) from chat_run where conversation_id=?",Integer.class,id)); assertEquals(0,jdbc.queryForObject("select count(*) from message where conversation_id=?",Integer.class,id)); assertEquals(0,jdbc.queryForObject("select count(*) from ai_session where id=?",Integer.class,id)); assertTrue(model().getRecordedPrompts().isEmpty());
  when(actor.getCurrentUserId()).thenReturn(actorId); jdbc.update("update workspace_member set active=false where workspace_id='shared-test' and user_id=?",actorId);
  assertEquals("WORKSPACE_ACCESS_DENIED",graph("{ conversations(workspaceId: \"shared-test\") { id } }").path("errors").get(0).path("extensions").path("code").asText());
  assertTrue(graph("{ conversation(id: \""+id+"\") { id } }").path("data").path("conversation").isNull());
 }
 @Test void activeCancellationRechecksMembership() throws Exception {
  String owner="cancel-owner"; when(actor.getCurrentUserId()).thenReturn(owner);
  var created=graph("mutation { createConversation(botId: \"workspace-test-bot\") { id workspaceId } }");
  String id=created.path("data").path("createConversation").path("id").asText(); String workspace=created.path("data").path("createConversation").path("workspaceId").asText();
  String run=UUID.randomUUID().toString(); model().setHoldMode(true);
  var body=Map.of("threadId",id,"runId",run,"messages",List.of(Map.of("id","u","role","user","content","hello")));
  var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/ag-ui")).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
  var stream=http.send(request,HttpResponse.BodyHandlers.ofInputStream()); assertEquals(200,stream.statusCode()); assertTrue(model().awaitSubscribed(5,java.util.concurrent.TimeUnit.SECONDS));
  when(actor.getCurrentUserId()).thenReturn("another-user");
  assertTrue(graph("{ run(id: \""+run+"\") { id } }").path("data").path("run").isNull());
  var foreign=post("/api/runs/"+run+"/cancel",Map.of()); var missing=post("/api/runs/not-a-run/cancel",Map.of());
  assertEquals(404,foreign.statusCode()); assertEquals(missing.body(),foreign.body());
  var duplicate=post("/api/ag-ui",body); assertEquals(404,duplicate.statusCode()); assertEquals("CONVERSATION_ACCESS_DENIED",json.readTree(duplicate.body()).path("code").asText());
  assertEquals(1,model().getRecordedPrompts().size());
  when(actor.getCurrentUserId()).thenReturn(owner);
  jdbc.update("update workspace_member set active=false where workspace_id=? and user_id=?",workspace,owner);
  assertTrue(graph("{ run(id: \""+run+"\") { id } }").path("data").path("run").isNull());
  var denied=post("/api/runs/"+run+"/cancel",Map.of()); assertEquals(404,denied.statusCode()); assertEquals("RUN_ACCESS_DENIED",json.readTree(denied.body()).path("code").asText()); assertFalse(denied.body().contains(run)); assertEquals("RUNNING",jdbc.queryForObject("select status from chat_run where id=?",String.class,run));
  jdbc.update("update workspace_member set active=true where workspace_id=? and user_id=?",workspace,owner);
  assertEquals(200,post("/api/runs/"+run+"/cancel",Map.of()).statusCode()); stream.body().close();
 }
 @Test void migrationBackfillsAndPreservesHistoryAndSession() throws Exception {
  try (var connection=dataSource.getConnection(); var sql=connection.createStatement()) {
   connection.setAutoCommit(false); sql.execute("create schema workspace_migration_test"); sql.execute("set local search_path to workspace_migration_test");
   for (String file : List.of("V1__initial_schema.sql","V3__session_schema.sql","V4__add_conversation_pinned.sql","V5__gke_bot_and_run_mode.sql")) {
    try(var in=getClass().getResourceAsStream("/db/migration/"+file)){ sql.execute(new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8)); }
   }
   sql.execute("insert into bot(id,name) values ('b','B')");
   sql.execute("insert into conversation(id,bot_id,title,user_id) values ('c1','b','one','alice'),('c2','b','two','alice'),('c3','b','three','bob')");
   sql.execute("insert into chat_run(id,conversation_id,client_request_id,input_text,status) values ('r','c1','request','hello','COMPLETED')");
   sql.execute("insert into message(id,conversation_id,run_id,role,content,interrupted) values ('m','c1','r','USER','hello',false),('m2','c1','r','ASSISTANT','partial',true)");
   sql.execute("insert into ai_session(id,created_at,metadata,event_version) values ('c1',now(),'{\"principalName\":\"alice\"}',7)");
   sql.execute("insert into ai_session_event(id,session_id,\"timestamp\",event_type,message_type,message_content,payload,metadata) values ('event1','c1',now(),'MESSAGE','USER','hello','{}','{}')");
   try(var in=getClass().getResourceAsStream("/db/migration/V6__workspace.sql")){sql.execute(new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));}
   try(var rows=sql.executeQuery("select count(distinct workspace_id),count(*) from conversation")){ assertTrue(rows.next()); assertEquals(2,rows.getInt(1)); assertEquals(3,rows.getInt(2)); }
   try(var rows=sql.executeQuery("select count(*) from workspace_member where role='MANAGER' and active")){assertTrue(rows.next()); assertEquals(2,rows.getInt(1));}
   try(var rows=sql.executeQuery("select c.user_id,r.id,r.client_request_id,s.metadata,s.event_version,e.id,e.message_content from conversation c join chat_run r on r.conversation_id=c.id join ai_session s on s.id=c.id join ai_session_event e on e.session_id=s.id where c.id='c1'")){assertTrue(rows.next());assertEquals("alice",rows.getString(1));assertEquals("r",rows.getString(2));assertEquals("request",rows.getString(3));assertEquals("{\"principalName\":\"alice\"}",rows.getString(4));assertEquals(7,rows.getLong(5));assertEquals("event1",rows.getString(6));assertEquals("hello",rows.getString(7));}
   try(var rows=sql.executeQuery("select id,content,interrupted from message order by id")){assertTrue(rows.next());assertEquals("m",rows.getString(1));assertEquals("hello",rows.getString(2));assertFalse(rows.getBoolean(3));assertTrue(rows.next());assertEquals("m2",rows.getString(1));assertEquals("partial",rows.getString(2));assertTrue(rows.getBoolean(3));}
   try(var rows=sql.executeQuery("select count(*) from conversation c join workspace w on w.id=c.workspace_id join workspace_member m on m.workspace_id=w.id and m.user_id=c.user_id where w.personal_owner_id=c.user_id and m.role='MANAGER' and m.active")){assertTrue(rows.next());assertEquals(3,rows.getInt(1));}
   try(var rows=sql.executeQuery("select count(*) from pg_constraint where conrelid in ('conversation'::regclass,'workspace_member'::regclass) and contype in ('f','c') and conname in ('fk_conversation_workspace','workspace_member_workspace_id_fkey','workspace_member_role_check')")){assertTrue(rows.next());assertEquals(3,rows.getInt(1));}
   try(var rows=sql.executeQuery("select obj_description('workspace'::regclass), col_description('conversation'::regclass,(select attnum from pg_attribute where attrelid='conversation'::regclass and attname='workspace_id'))")){assertTrue(rows.next());assertNotNull(rows.getString(1));assertNotNull(rows.getString(2));}
   connection.rollback();
  }
 }
 @Test void explicitNullAndOmittedScopeResolvePersonal() throws Exception {
  when(actor.getCurrentUserId()).thenReturn("null-scope-owner");
  var personal=graph("{ workspaces { id personal } }"); String id=personal.path("data").path("workspaces").get(0).path("id").asText();
  var a=graph("mutation { createConversation(botId: \"workspace-test-bot\") { workspaceId } }");
  var b=graph("mutation { createConversation(botId: \"workspace-test-bot\", workspaceId: null) { workspaceId } }");
  assertEquals(id,a.path("data").path("createConversation").path("workspaceId").asText()); assertEquals(id,b.path("data").path("createConversation").path("workspaceId").asText());
  assertEquals(2,graph("{ conversations(workspaceId: null) { id } }").path("data").path("conversations").size());
 }
 @Test void legacyStartAndCancelMissingAreNonLeaking() throws Exception {
  var start=post("/api/conversations/unknown-conversation/runs",Map.of("clientRequestId","request","message","hello"));
  assertEquals(404,start.statusCode()); assertEquals("CONVERSATION_ACCESS_DENIED",json.readTree(start.body()).path("code").asText()); assertFalse(start.body().contains("unknown-conversation"));
  var cancel=post("/api/runs/unknown-run/cancel",Map.of()); assertEquals(404,cancel.statusCode()); assertEquals("RUN_ACCESS_DENIED",json.readTree(cancel.body()).path("code").asText()); assertFalse(cancel.body().contains("unknown-run"));
  assertTrue(model().getRecordedPrompts().isEmpty());
 }
 @Test void revokedConversationRejectsSseAcceptWithoutSideEffects() throws Exception {
  when(actor.getCurrentUserId()).thenReturn("sse-owner");
  var created=graph("mutation { createConversation(botId: \"workspace-test-bot\") { id workspaceId } }");
  String id=created.path("data").path("createConversation").path("id").asText(); String workspace=created.path("data").path("createConversation").path("workspaceId").asText();
  jdbc.update("update workspace_member set active=false where workspace_id=? and user_id=?",workspace,"sse-owner");
  var body=Map.of("threadId",id,"runId",UUID.randomUUID().toString(),"messages",List.of(Map.of("id","u","role","user","content","hello")));
  var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/ag-ui")).header("Content-Type","application/json").header("Accept","text/event-stream").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
  var response=http.send(request,HttpResponse.BodyHandlers.ofString()); assertEquals(404,response.statusCode(),response.body()); assertEquals("CONVERSATION_ACCESS_DENIED",json.readTree(response.body()).path("code").asText()); assertTrue(response.headers().firstValue("content-type").orElse("").startsWith("application/json"));
  assertEquals(0,jdbc.queryForObject("select count(*) from chat_run where conversation_id=?",Integer.class,id)); assertEquals(0,jdbc.queryForObject("select count(*) from message where conversation_id=?",Integer.class,id)); assertEquals(0,jdbc.queryForObject("select count(*) from ai_session where id=?",Integer.class,id)); assertTrue(model().getRecordedPrompts().isEmpty());
 }
 HttpResponse<String> postSse(Object body) throws Exception {
  return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/ag-ui")).header("Content-Type","application/json").header("Accept","text/event-stream").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
 }
 void assertJsonError(HttpResponse<String> response,int status,String code) throws Exception {
  assertEquals(status,response.statusCode(),response.body());
  assertTrue(response.headers().firstValue("content-type").orElse("").startsWith("application/json"));
  assertEquals(code,json.readTree(response.body()).path("code").asText());
 }
 @Test void httpAgentAcceptPreservesSuccessAndErrors() throws Exception {
  when(actor.getCurrentUserId()).thenReturn("sse-path-owner");
  assertJsonError(postSse(Map.of()),400,"INVALID_AG_UI_INPUT");
  var unknown=Map.of("threadId","unknown-thread","runId",UUID.randomUUID().toString(),"messages",List.of(Map.of("id","u","role","user","content","hi")));
  assertJsonError(postSse(unknown),404,"CONVERSATION_ACCESS_DENIED");
  var created=graph("mutation { createConversation(botId: \"workspace-test-bot\") { id workspaceId } }");
  String id=created.path("data").path("createConversation").path("id").asText(); String workspace=created.path("data").path("createConversation").path("workspaceId").asText();
  var request=Map.of("threadId",id,"runId",UUID.randomUUID().toString(),"messages",List.of(Map.of("id","u","role","user","content","hi")));
  var success=postSse(request); assertEquals(200,success.statusCode()); assertTrue(success.headers().firstValue("content-type").orElse("").startsWith("text/event-stream")); assertTrue(success.body().contains("RUN_FINISHED"));
  assertJsonError(postSse(request),409,"DUPLICATE_RUN");
  var conflicting=Map.of("threadId",id,"runId",request.get("runId"),"messages",List.of(Map.of("id","u","role","user","content","changed")));
  assertJsonError(postSse(conflicting),409,"RUN_CONFLICT");
  jdbc.update("insert into bot(id,name,target,owner_id) values ('sse-gke','remote','GKE_REMOTE','sse-path-owner') on conflict do nothing");
  jdbc.update("insert into conversation(id,bot_id,title,user_id,workspace_id) values ('sse-gke-conv','sse-gke','remote','sse-path-owner',?) on conflict do nothing",workspace);
  var unavailable=Map.of("threadId","sse-gke-conv","runId",UUID.randomUUID().toString(),"messages",List.of(Map.of("id","u","role","user","content","hi")));
  assertJsonError(postSse(unavailable),503,"GKE_AGENT_NOT_CONNECTED");
 }
}
