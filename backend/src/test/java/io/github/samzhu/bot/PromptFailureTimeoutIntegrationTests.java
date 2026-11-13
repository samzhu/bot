package io.github.samzhu.bot;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.samzhu.bot.execution.internal.RunRegistry;
import io.github.samzhu.bot.usage.WorkspaceAdmission;
import io.github.samzhu.bot.conversation.ConversationRecoveryReader;
import io.github.samzhu.bot.conversation.ConversationOperations;
import io.github.samzhu.bot.workspace.WorkspaceOperations;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.session.CreateSessionRequest;
import org.springframework.ai.session.SessionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import javax.sql.DataSource;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={"bot.run.timeout=1s", "bot.execution.completion-sweep.initial-delay-ms=100", "bot.execution.completion-sweep.delay-ms=100"})
@Import({TestcontainersConfiguration.class, AgUiIntegrationTests.Scheduling.class})
@ActiveProfiles("test")
class PromptFailureTimeoutIntegrationTests {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @MockitoSpyBean SessionService sessions;
    @MockitoSpyBean WorkspaceAdmission admission;
    @MockitoSpyBean ConversationRecoveryReader recoveryReader;
    @MockitoSpyBean(name="usageFinalizationScheduler") reactor.core.scheduler.Scheduler finalizationScheduler;
    @Autowired RunRegistry registry;
    @Autowired ConversationOperations conversations;
    @Autowired WorkspaceOperations workspaces;
    @TestBean(name="mockChatModel", methodName="model", enforceOverride=true) ChatModel model;
    static ControlledChatModel model() { return ControlledChatModel.getInstance(); }
    final HttpClient http=HttpClient.newHttpClient();
    final ObjectMapper json=new ObjectMapper();
    HttpRequest request(String path,Object body) throws Exception {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
    }
    private void assertLostSettlementReplyIsRescanned(boolean registryMiss,boolean revokeMember,boolean rejectContinuation) throws Exception {
        model().reset();
        jdbc.update("insert into bot(id,name,system_prompt,created_at) values ('bot-general','General','test',current_timestamp) on conflict(id) do nothing");
        var created=http.send(request("/graphql",Map.of("query","mutation { createConversation(botId: \"bot-general\", title: \"prompt scan\") { id } }")),HttpResponse.BodyHandlers.ofString());
        String conversation=json.readTree(created.body()).path("data").path("createConversation").path("id").asText();
        assertFalse(conversation.isBlank());
        String workspace=jdbc.queryForObject("select workspace_id from conversation where id=?",String.class,conversation);
        String requestId="prompt-scan-"+UUID.randomUUID();
        var armed=new java.util.concurrent.atomic.AtomicBoolean();
        var rejected=new java.util.concurrent.CountDownLatch(1);
        if(rejectContinuation) doAnswer(call -> {
            if(armed.compareAndSet(true,false)) { rejected.countDown(); throw new java.util.concurrent.RejectedExecutionException("injected continuation rejection"); }
            return call.callRealMethod();
        }).when(finalizationScheduler).schedule(any(Runnable.class));
        doAnswer(call -> {
            if (revokeMember) assertEquals(1,jdbc.update("update workspace_member set active=false where workspace_id=? and user_id='dev-user' and active=true",workspace));
            if (registryMiss) {
                String run=jdbc.queryForObject("select id from chat_run where conversation_id=? and client_request_id=?",String.class,conversation,requestId);
                assertNotNull(registry.remove(run));
            }
            throw new IllegalStateException("synthetic prompt failure");
        }).when(sessions).findOrCreate(any(CreateSessionRequest.class));
        doAnswer(call -> {
            WorkspaceAdmission.FinalizeAttempt command=call.getArgument(0);
            @SuppressWarnings("unchecked") var actual=(java.util.concurrent.CompletionStage<WorkspaceAdmission.FinalizationResult>)call.callRealMethod();
            if (!conversation.equals(command.registration().conversationId()) || command.reason()!=WorkspaceAdmission.TerminalReason.PROMPT_CONTEXT_UNAVAILABLE) return actual;
            if(rejectContinuation) return actual.thenApply(result -> { armed.set(true); return result; });
            return actual.thenCompose(result -> java.util.concurrent.CompletableFuture.failedFuture(new IllegalStateException("injected lost settlement response")));
        }).when(admission).finalizeAttempt(any());
        try {
            var pending=http.sendAsync(request("/api/conversations/"+conversation+"/runs",Map.of("clientRequestId",requestId,"message","test")),HttpResponse.BodyHandlers.ofString());
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(12); String run=null, status=null;
            while(System.nanoTime()<deadline) {
                var rows=jdbc.query("select id,status from chat_run where conversation_id=? and client_request_id=?",(rs,n)->new String[]{rs.getString(1),rs.getString(2)},conversation,requestId);
                if(!rows.isEmpty()) { run=rows.getFirst()[0]; status=rows.getFirst()[1]; if("FAILED".equals(status)) break; }
                Thread.yield();
            }
            assertNotNull(run); assertEquals("FAILED",status,"settled durable fact must recover Run after lost response");
            if(rejectContinuation) assertTrue(rejected.await(5,TimeUnit.SECONDS),"settled continuation rejection was not reached");
            assertEquals("NOT_DISPATCHED",jdbc.queryForObject("select execution_outcome from provider_attempt where run_id=?",String.class,run));
            assertEquals("PROMPT_CONTEXT_UNAVAILABLE",jdbc.queryForObject("select execution_reason from provider_attempt where run_id=?",String.class,run));
            String registration=null;
            while(System.nanoTime()<deadline) {
                registration=jdbc.queryForObject("select status from workspace_admission_registration where run_id=?",String.class,run);
                if("TERMINAL".equals(registration)) break;
                Thread.yield();
            }
            assertEquals("TERMINAL",registration);
            assertTrue(model().getRecordedPrompts().isEmpty());
            assertEquals(0,jdbc.queryForObject("select count(*) from ai_session where id=?",Integer.class,conversation));
            if(revokeMember) {
                var denied=http.send(request("/graphql",Map.of("query","query { run(id: \""+run+"\") { id } }")),HttpResponse.BodyHandlers.ofString());
                assertTrue(json.readTree(denied.body()).path("data").path("run").isNull(),denied.body());
            }
            if(!registryMiss) assertTrue(pending.get(5,TimeUnit.SECONDS).body().contains("run.failed"));
        } finally {
            if(revokeMember) jdbc.update("update workspace_member set active=true where workspace_id=? and user_id='dev-user'",workspace);
            reset(sessions,admission,finalizationScheduler);
        }
    }
    @Test void lostSettlementReplyIsRescannedWithActiveHandle() throws Exception { assertLostSettlementReplyIsRescanned(false,false,false); }
    @Test void lostSettlementReplyIsRescannedAfterRegistryMiss() throws Exception { assertLostSettlementReplyIsRescanned(true,false,false); }
    @Test void settledPromptFailureRecoversAfterActorRevocation() throws Exception { assertLostSettlementReplyIsRescanned(false,true,false); }
    @Test void rejectedSettlementContinuationIsRescanned() throws Exception { assertLostSettlementReplyIsRescanned(false,false,true); }

    @Test void blockedRecoveryReadDoesNotHoldLifecycleStripeAndCancelWins() throws Exception {
        model().reset();
        jdbc.update("insert into bot(id,name,system_prompt,created_at) values ('bot-general','General','test',current_timestamp) on conflict(id) do nothing");
        var created=http.send(request("/graphql",Map.of("query","mutation { createConversation(botId: \"bot-general\", title: \"blocked scan\") { id } }")),HttpResponse.BodyHandlers.ofString());
        String conversation=json.readTree(created.body()).path("data").path("createConversation").path("id").asText();
        assertFalse(conversation.isBlank());
        String requestId="prompt-blocked-read-"+UUID.randomUUID();
        String otherActor="scan-other-"+UUID.randomUUID();
        String otherWorkspace=workspaces.personal(otherActor).id();
        String otherConversation=conversations.createConversation("bot-general","independent",otherActor,otherWorkspace).id();
        doThrow(new IllegalStateException("synthetic prompt failure")).when(sessions).findOrCreate(any(CreateSessionRequest.class));
        doAnswer(call -> {
            WorkspaceAdmission.FinalizeAttempt command=call.getArgument(0);
            @SuppressWarnings("unchecked") var actual=(java.util.concurrent.CompletionStage<WorkspaceAdmission.FinalizationResult>)call.callRealMethod();
            if(!conversation.equals(command.registration().conversationId())) return actual;
            return actual.thenCompose(result -> java.util.concurrent.CompletableFuture.failedFuture(new IllegalStateException("lost response")));
        }).when(admission).finalizeAttempt(any());
        doAnswer(call -> {
            if(conversation.equals(call.getArgument(0))) jdbc.execute("select pg_advisory_xact_lock(370037003)");
            return call.callRealMethod();
        }).when(recoveryReader).readIdentity(any());
        try(var lock=dataSource.getConnection()) {
            lock.setAutoCommit(false); int blocker;
            try(var statement=lock.createStatement(); var rows=statement.executeQuery("select pg_backend_pid()")) { assertTrue(rows.next()); blocker=rows.getInt(1); }
            try(var statement=lock.createStatement()) { statement.execute("select pg_advisory_xact_lock(370037003)"); }
            var pending=http.sendAsync(request("/api/conversations/"+conversation+"/runs",Map.of("clientRequestId",requestId,"message","test")),HttpResponse.BodyHandlers.ofString());
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8); boolean waiting=false;
            while(System.nanoTime()<deadline) {
                waiting=Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from pg_stat_activity where ?=any(pg_blocking_pids(pid)))",Boolean.class,blocker));
                if(waiting) break; Thread.yield();
            }
            assertTrue(waiting,"scanner did not reach blocked recovery read");
            String run=jdbc.queryForObject("select id from chat_run where conversation_id=? and client_request_id=?",String.class,conversation,requestId);
            var cancel=http.sendAsync(request("/api/runs/"+run+"/cancel",Map.of()),HttpResponse.BodyHandlers.ofString());
            var result=cancel.get(5,TimeUnit.SECONDS);
            assertEquals(200,result.statusCode(),result.body());
            assertEquals("CANCELLED",json.readTree(result.body()).path("status").asText(),result.body());
            var other=admission.register(new WorkspaceAdmission.Register(otherWorkspace,UUID.randomUUID().toString(),otherActor,otherConversation,WorkspaceAdmission.Partition.SIMULATION)).toCompletableFuture().get(5,TimeUnit.SECONDS);
            var registered=assertInstanceOf(WorkspaceAdmission.RegisterResult.Registered.class,other);
            admission.abandon(registered.registration()).toCompletableFuture().get(5,TimeUnit.SECONDS);
            lock.rollback();
            pending.get(10,TimeUnit.SECONDS);
            assertEquals("CANCELLED",jdbc.queryForObject("select status from chat_run where id=?",String.class,run));
            assertEquals("PROMPT_CONTEXT_UNAVAILABLE",jdbc.queryForObject("select execution_reason from provider_attempt where run_id=?",String.class,run));
            assertTrue(model().getRecordedPrompts().isEmpty());
        } finally { reset(sessions,admission,recoveryReader); }
    }

    @Test void blockedRecoveryRunRowDoesNotHoldHandleMonitor() throws Exception {
        model().reset();
        jdbc.update("insert into bot(id,name,system_prompt,created_at) values ('bot-general','General','test',current_timestamp) on conflict(id) do nothing");
        var created=http.send(request("/graphql",Map.of("query","mutation { createConversation(botId: \"bot-general\", title: \"blocked run row\") { id } }")),HttpResponse.BodyHandlers.ofString());
        String conversation=json.readTree(created.body()).path("data").path("createConversation").path("id").asText();
        String requestId="prompt-blocked-row-"+UUID.randomUUID();
        var identityEntered=new java.util.concurrent.CountDownLatch(1); var releaseIdentity=new java.util.concurrent.CountDownLatch(1);
        var first=new java.util.concurrent.atomic.AtomicBoolean(true);
        doThrow(new IllegalStateException("synthetic prompt failure")).when(sessions).findOrCreate(any(CreateSessionRequest.class));
        doAnswer(call -> {
            WorkspaceAdmission.FinalizeAttempt command=call.getArgument(0);
            @SuppressWarnings("unchecked") var actual=(java.util.concurrent.CompletionStage<WorkspaceAdmission.FinalizationResult>)call.callRealMethod();
            if(!conversation.equals(command.registration().conversationId())) return actual;
            return actual.thenCompose(result -> java.util.concurrent.CompletableFuture.failedFuture(new IllegalStateException("lost response")));
        }).when(admission).finalizeAttempt(any());
        doAnswer(call -> {
            if(conversation.equals(call.getArgument(0)) && first.compareAndSet(true,false)) {
                identityEntered.countDown();
                if(!releaseIdentity.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("identity latch timeout");
            }
            return call.callRealMethod();
        }).when(recoveryReader).readIdentity(any());
        var pending=http.sendAsync(request("/api/conversations/"+conversation+"/runs",Map.of("clientRequestId",requestId,"message","test")),HttpResponse.BodyHandlers.ofString());
        try(var lock=dataSource.getConnection()) {
            assertTrue(identityEntered.await(10,TimeUnit.SECONDS));
            String run=jdbc.queryForObject("select id from chat_run where conversation_id=? and client_request_id=?",String.class,conversation,requestId);
            var handle=registry.get(run); assertNotNull(handle);
            lock.setAutoCommit(false); int blocker;
            try(var statement=lock.createStatement(); var rows=statement.executeQuery("select pg_backend_pid()")) { assertTrue(rows.next()); blocker=rows.getInt(1); }
            try(var statement=lock.prepareStatement("select id from chat_run where id=? for update")) { statement.setString(1,run); try(var rows=statement.executeQuery()) { assertTrue(rows.next()); } }
            releaseIdentity.countDown();
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5); boolean waiting=false;
            while(System.nanoTime()<deadline) {
                waiting=Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from pg_stat_activity where ?=any(pg_blocking_pids(pid)))",Boolean.class,blocker));
                if(waiting) break; Thread.yield();
            }
            assertTrue(waiting,"scanner did not reach locked Run row");
            java.util.concurrent.CompletableFuture.runAsync(handle::markPromptFailureAwaitingSettlement).get(1,TimeUnit.SECONDS);
            var cancel=http.sendAsync(request("/api/runs/"+run+"/cancel",Map.of()),HttpResponse.BodyHandlers.ofString());
            lock.rollback();
            var result=cancel.get(10,TimeUnit.SECONDS);
            String winner=json.readTree(result.body()).path("status").asText();
            assertTrue("FAILED".equals(winner) || "CANCELLED".equals(winner),result.body());
            assertEquals(winner,jdbc.queryForObject("select status from chat_run where id=?",String.class,run));
            pending.get(10,TimeUnit.SECONDS);
        } finally { releaseIdentity.countDown(); reset(sessions,admission,recoveryReader); }
    }

    @Test void freshContextRecoversSettledPromptFactWithoutCallback() throws Exception {
        model().reset();
        jdbc.update("insert into bot(id,name,system_prompt,created_at) values ('bot-general','General','test',current_timestamp) on conflict(id) do nothing");
        var created=http.send(request("/graphql",Map.of("query","mutation { createConversation(botId: \"bot-general\", title: \"fresh context\") { id } }")),HttpResponse.BodyHandlers.ofString());
        String conversation=json.readTree(created.body()).path("data").path("createConversation").path("id").asText();
        String requestId="prompt-fresh-context-"+UUID.randomUUID();
        var scanEntered=new java.util.concurrent.CountDownLatch(1); var releaseScan=new java.util.concurrent.CountDownLatch(1);
        doThrow(new IllegalStateException("synthetic prompt failure")).when(sessions).findOrCreate(any(CreateSessionRequest.class));
        doAnswer(call -> {
            WorkspaceAdmission.FinalizeAttempt command=call.getArgument(0);
            @SuppressWarnings("unchecked") var actual=(java.util.concurrent.CompletionStage<WorkspaceAdmission.FinalizationResult>)call.callRealMethod();
            if(!conversation.equals(command.registration().conversationId())) return actual;
            return actual.thenCompose(result -> java.util.concurrent.CompletableFuture.failedFuture(new IllegalStateException("lost response")));
        }).when(admission).finalizeAttempt(any());
        doAnswer(call -> {
            if(conversation.equals(call.getArgument(0))) {
                scanEntered.countDown();
                if(!releaseScan.await(30,TimeUnit.SECONDS)) throw new IllegalStateException("scan latch timeout");
            }
            return call.callRealMethod();
        }).when(recoveryReader).readIdentity(any());
        var pending=http.sendAsync(request("/api/conversations/"+conversation+"/runs",Map.of("clientRequestId",requestId,"message","test")),HttpResponse.BodyHandlers.ofString());
        try {
            assertTrue(scanEntered.await(10,TimeUnit.SECONDS));
            String run=jdbc.queryForObject("select id from chat_run where conversation_id=? and client_request_id=?",String.class,conversation,requestId);
            assertEquals("RUNNING",jdbc.queryForObject("select status from chat_run where id=?",String.class,run));
            assertEquals("SETTLED",jdbc.queryForObject("select f.state from workspace_admission_finalization f join provider_attempt a on a.id=f.attempt_id where a.run_id=?",String.class,run));
            var ds=(com.zaxxer.hikari.HikariDataSource)dataSource;
            try(var fresh=new org.springframework.boot.builder.SpringApplicationBuilder(BotApplication.class)
                    .web(org.springframework.boot.WebApplicationType.NONE).profiles("test")
                    .properties("spring.datasource.url="+ds.getJdbcUrl(),"spring.datasource.username="+ds.getUsername(),
                            "spring.datasource.password="+ds.getPassword(),"spring.docker.compose.enabled=false").run()) {
                assertEquals("FAILED",jdbc.queryForObject("select status from chat_run where id=?",String.class,run));
                assertEquals("TERMINAL",jdbc.queryForObject("select status from workspace_admission_registration where run_id=?",String.class,run));
                assertEquals("PROMPT_CONTEXT_UNAVAILABLE",jdbc.queryForObject("select execution_reason from provider_attempt where run_id=?",String.class,run));
                assertTrue(model().getRecordedPrompts().isEmpty());
            }
        } finally { releaseScan.countDown(); reset(sessions,admission,recoveryReader); pending.cancel(true); }
    }

    @Test void timeoutDoesNotPublishRunTerminalWhilePromptFinalizationIsPending() throws Exception { assertBlockedPromptSettlement(false); }
    @Test void cancelWhilePromptSettlementBlockedWinsAfterSettlement() throws Exception { assertBlockedPromptSettlement(true); }
    private void assertBlockedPromptSettlement(boolean cancel) throws Exception {
        model().reset();
        jdbc.update("insert into bot(id,name,system_prompt,created_at) values ('bot-general','General','test',current_timestamp) on conflict(id) do nothing");
        var created=http.send(request("/graphql",Map.of("query","mutation { createConversation(botId: \"bot-general\", title: \"prompt timeout\") { id } }")),HttpResponse.BodyHandlers.ofString());
        String conversation=json.readTree(created.body()).path("data").path("createConversation").path("id").asText();
        assertFalse(conversation.isBlank(),created.body());
        String requestId="prompt-timeout-"+UUID.randomUUID();
        var entered=new java.util.concurrent.CountDownLatch(1); var release=new java.util.concurrent.CountDownLatch(1);
        doAnswer(call -> { entered.countDown(); if(!release.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("latch timeout"); throw new IllegalStateException("synthetic prompt failure"); })
                .when(sessions).findOrCreate(any(CreateSessionRequest.class));
        jdbc.execute("create function test_hold_prompt_timeout() returns trigger language plpgsql as $$ begin if new.execution_outcome <> 'IN_PROGRESS' and exists(select 1 from chat_run where id=new.run_id and conversation_id='"+conversation+"') then perform pg_advisory_xact_lock(370037002); end if; return new; end $$");
        jdbc.execute("create trigger test_hold_prompt_timeout before update on provider_attempt for each row execute function test_hold_prompt_timeout()");
        var pending=http.sendAsync(request("/api/conversations/"+conversation+"/runs",Map.of("clientRequestId",requestId,"message","test")),HttpResponse.BodyHandlers.ofString());
        try (var connection=dataSource.getConnection()) {
            assertTrue(entered.await(10,TimeUnit.SECONDS));
            String run=jdbc.queryForObject("select id from chat_run where conversation_id=? and client_request_id=?",String.class,conversation,requestId);
            connection.setAutoCommit(false);
            int blocker;
            try(var statement=connection.createStatement(); var rows=statement.executeQuery("select pg_backend_pid()")) { assertTrue(rows.next()); blocker=rows.getInt(1); }
            try(var statement=connection.createStatement()) { statement.execute("select pg_advisory_xact_lock(370037002)"); }
            release.countDown();
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5); boolean waiting=false;
            while(System.nanoTime()<deadline) {
                waiting=Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from pg_stat_activity where ?=any(pg_blocking_pids(pid)))",Boolean.class,blocker));
                if(waiting) break;
                Thread.yield();
            }
            assertTrue(waiting,"settlement did not reach trigger");
            assertEquals("PENDING",jdbc.queryForObject("select f.state from workspace_admission_finalization f join provider_attempt a on a.id=f.attempt_id where a.run_id=?",String.class,run));
            assertEquals("IN_PROGRESS",jdbc.queryForObject("select execution_outcome from provider_attempt where run_id=?",String.class,run));
            java.util.concurrent.CompletableFuture<HttpResponse<String>> cancellation=null;
            if(cancel) {
                var requested=new java.util.concurrent.CountDownLatch(1);
                doAnswer(call -> { requested.countDown(); return call.callRealMethod(); }).when(admission).cancelRun(any());
                cancellation=http.sendAsync(request("/api/runs/"+run+"/cancel",Map.of()),HttpResponse.BodyHandlers.ofString());
                assertTrue(requested.await(5,TimeUnit.SECONDS),"cancel did not reach admission while settle was blocked");
            }
            // Waiting beyond the configured deadline tests the timeout branch while settlement remains locked.
            try { pending.get(3,TimeUnit.SECONDS); } catch(java.util.concurrent.TimeoutException expected) { /* SSE may stay open or close safely. */ }
            assertEquals("RUNNING",jdbc.queryForObject("select status from chat_run where id=?",String.class,run),"deadline cannot publish terminal before prompt settlement");
            connection.rollback();
            if(cancel) {
                var cancelled=cancellation.get(15,TimeUnit.SECONDS);
                assertEquals(200,cancelled.statusCode());
                assertEquals("CANCELLED",json.readTree(cancelled.body()).path("status").asText(),cancelled.body());
            }
            pending.get(15,TimeUnit.SECONDS);
            assertEquals("NOT_DISPATCHED",jdbc.queryForObject("select execution_outcome from provider_attempt where run_id=?",String.class,run));
            assertEquals("PROMPT_CONTEXT_UNAVAILABLE",jdbc.queryForObject("select execution_reason from provider_attempt where run_id=?",String.class,run));
            if(cancel) {
                assertEquals("CANCELLED",jdbc.queryForObject("select status from chat_run where id=?",String.class,run));
                assertEquals("PROMPT_CONTEXT_UNAVAILABLE",jdbc.queryForObject("select f.terminal_reason from workspace_admission_finalization f join provider_attempt a on a.id=f.attempt_id where a.run_id=?",String.class,run));
            }
            assertTrue(model().getRecordedPrompts().isEmpty());
        } finally {
            release.countDown();
            jdbc.execute("drop trigger if exists test_hold_prompt_timeout on provider_attempt");
            jdbc.execute("drop function if exists test_hold_prompt_timeout()");
            reset(sessions,admission);
        }
    }
}
