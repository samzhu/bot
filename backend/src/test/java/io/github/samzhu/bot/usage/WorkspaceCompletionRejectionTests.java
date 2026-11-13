package io.github.samzhu.bot;

import io.github.samzhu.bot.conversation.ConversationOperations;
import io.github.samzhu.bot.usage.UsageLedger;
import io.github.samzhu.bot.usage.WorkspaceAdmission;
import io.github.samzhu.bot.usage.internal.admission.WorkspaceLane;
import io.github.samzhu.bot.workspace.WorkspaceOperations;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.UUID;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class WorkspaceCompletionRejectionTests {
    @Autowired WorkspaceAdmission admission;
    @Autowired WorkspaceOperations workspaces;
    @Autowired ConversationOperations conversations;
    @Autowired UsageLedger ledger;
    @Autowired WorkspaceLane lane;
    @Autowired JdbcTemplate jdbc;

    @Test void rejectedCompletionExecutorDoesNotLoseAcceptedResultOrPermits() throws Exception {
        String actor="completion-reject-"+UUID.randomUUID(),workspace=workspaces.personal(actor).id();
        String bot="completion-bot-"+UUID.randomUUID(),run="completion-run-"+UUID.randomUUID();
        jdbc.update("insert into bot(id,name,target) values (?,?,'LOCAL')",bot,bot);
        var conversation=conversations.createConversation(bot,"fixture",actor,workspace);
        var registration=assertInstanceOf(WorkspaceAdmission.RegisterResult.Registered.class,admission.register(
                new WorkspaceAdmission.Register(workspace,run,actor,conversation.id(),WorkspaceAdmission.Partition.REAL)).toCompletableFuture().join()).registration();
        jdbc.update("insert into chat_run(id,conversation_id,client_request_id,input_text,status) values (?,?,?,'test','PENDING')",run,conversation.id(),UUID.randomUUID().toString());
        assertInstanceOf(WorkspaceAdmission.PublicationResult.Ready.class,admission.publish(registration).toCompletableFuture().join());
        String attempt=ledger.begin(new UsageLedger.Dispatch(run,workspace,actor,"FAKE","test","model","FAKE","1",false,"UNKNOWN"));
        var claim=assertInstanceOf(WorkspaceAdmission.Decision.Allowed.class,admission.dispatch(new WorkspaceAdmission.Dispatch(registration,attempt)).toCompletableFuture().join()).claim();
        var executor=(ThreadPoolExecutor)ReflectionTestUtils.getField(lane,"criticalCompletions");
        assertNotNull(executor); executor.shutdown(); // Controlled executor failure; behavior is observed through public APIs and PG.
        assertEquals(WorkspaceAdmission.FinalizationResult.SETTLED,admission.finalizeAttempt(new WorkspaceAdmission.FinalizeAttempt(
                registration,attempt,claim,WorkspaceAdmission.TerminalKind.UNKNOWN)).toCompletableFuture().get(3,TimeUnit.SECONDS));
        assertEquals("SETTLED",jdbc.queryForObject("select state from workspace_admission_finalization where attempt_id=?",String.class,attempt));
        for(int i=0;i<70;i++) assertInstanceOf(WorkspaceAdmission.ClaimInspection.Found.class,
                admission.recoverClaim(registration,attempt).toCompletableFuture().get(3,TimeUnit.SECONDS));
    }

    @Test void blockedFallbackCallbackDoesNotBlockWorkspaceLane() throws Exception {
        String actor="fallback-callback-"+UUID.randomUUID(),workspace=workspaces.personal(actor).id();
        String otherActor="fallback-other-"+UUID.randomUUID(),otherWorkspace=workspaces.personal(otherActor).id();
        String bot="fallback-bot-"+UUID.randomUUID(),run="fallback-run-"+UUID.randomUUID();
        jdbc.update("insert into bot(id,name,target) values (?,?,'LOCAL')",bot,bot);
        var conversation=conversations.createConversation(bot,"fixture",actor,workspace);
        var otherConversation=conversations.createConversation(bot,"other",otherActor,otherWorkspace);
        var registration=assertInstanceOf(WorkspaceAdmission.RegisterResult.Registered.class,admission.register(
                new WorkspaceAdmission.Register(workspace,run,actor,conversation.id(),WorkspaceAdmission.Partition.REAL)).toCompletableFuture().join()).registration();
        var other=assertInstanceOf(WorkspaceAdmission.RegisterResult.Registered.class,admission.register(
                new WorkspaceAdmission.Register(otherWorkspace,"other-run-"+UUID.randomUUID(),otherActor,otherConversation.id(),WorkspaceAdmission.Partition.REAL)).toCompletableFuture().join()).registration();
        var executor=(ThreadPoolExecutor)ReflectionTestUtils.getField(lane,"criticalCompletions");
        assertNotNull(executor); executor.shutdown();
        var entered=new java.util.concurrent.CountDownLatch(1); var release=new java.util.concurrent.CountDownLatch(1);
        try {
            var blocked=admission.recoverClaim(registration,"missing-attempt").thenApply(value -> {
                entered.countDown();
                try { if (!release.await(10,TimeUnit.SECONDS)) throw new AssertionError("callback timeout"); }
                catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new RuntimeException(ex); }
                return value;
            });
            assertTrue(entered.await(5,TimeUnit.SECONDS));
            var queued=admission.recoverClaim(registration,"another-missing-attempt").toCompletableFuture();
            assertEquals(WorkspaceAdmission.CancellationState.NONE,admission.cancellationState(registration).toCompletableFuture().get(5,TimeUnit.SECONDS).state());
            assertEquals(WorkspaceAdmission.CancellationState.NONE,admission.cancellationState(other).toCompletableFuture().get(5,TimeUnit.SECONDS).state());
            release.countDown();
            assertInstanceOf(WorkspaceAdmission.ClaimInspection.Unknown.class,blocked.toCompletableFuture().get(5,TimeUnit.SECONDS));
            assertInstanceOf(WorkspaceAdmission.ClaimInspection.Unknown.class,queued.get(5,TimeUnit.SECONDS));
        } finally { release.countDown(); }
    }
}
