package io.github.samzhu.bot;

import io.github.samzhu.bot.conversation.ConversationOperations;
import io.github.samzhu.bot.identity.CurrentUserProvider;
import io.github.samzhu.bot.usage.UsageCostRevisionOperations;
import io.github.samzhu.bot.usage.UsageLedger;
import io.github.samzhu.bot.usage.WorkspaceAdmission;
import io.github.samzhu.bot.usage.WorkspaceMonthlyBudgetOperations;
import io.github.samzhu.bot.workspace.WorkspaceOperations;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@SpringBootTest(properties={"bot.pricing.reconcile.initial-delay-ms=3600000","bot.budget.effects.initial-delay-ms=3600000"})
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class WorkspaceBudgetBoundaryEvidenceTests {
    @Autowired WorkspaceAdmission admission;
    @Autowired WorkspaceMonthlyBudgetOperations budgets;
    @Autowired WorkspaceOperations workspaces;
    @Autowired ConversationOperations conversations;
    @Autowired UsageLedger ledger;
    @Autowired UsageCostRevisionOperations revisions;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean CurrentUserProvider currentUser;
    record Claimed(WorkspaceAdmission.Registration registration,String attempt,WorkspaceAdmission.ClaimFence claim) {}
    Claimed claim(String actor,String workspace,String model) {
        String bot="boundary-bot-"+UUID.randomUUID(),run="boundary-run-"+UUID.randomUUID();
        jdbc.update("insert into bot(id,name,target) values (?,?,'LOCAL')",bot,bot);
        var conversation=conversations.createConversation(bot,"synthetic",actor,workspace);
        var registration=assertInstanceOf(WorkspaceAdmission.RegisterResult.Registered.class,admission.register(new WorkspaceAdmission.Register(workspace,run,actor,conversation.id(),WorkspaceAdmission.Partition.SIMULATION)).toCompletableFuture().join()).registration();
        jdbc.update("insert into chat_run(id,conversation_id,client_request_id,input_text,status) values (?,?,?,'test','PENDING')",run,conversation.id(),UUID.randomUUID().toString());
        assertInstanceOf(WorkspaceAdmission.PublicationResult.Ready.class,admission.publish(registration).toCompletableFuture().join());
        String attempt=ledger.begin(new UsageLedger.Dispatch(run,workspace,actor,"FAKE_GEMINI","generateContent",model,"FAKE","1",true,"VERIFIED"));
        var claim=assertInstanceOf(WorkspaceAdmission.Decision.Allowed.class,admission.dispatch(new WorkspaceAdmission.Dispatch(registration,attempt)).toCompletableFuture().join()).claim();
        return new Claimed(registration,attempt,claim);
    }
    WorkspaceMonthlyBudgetOperations.Scope scope(String workspace) {
        return new WorkspaceMonthlyBudgetOperations.Scope(workspace,WorkspaceAdmission.Partition.SIMULATION,YearMonth.now(ZoneId.of("Asia/Taipei")));
    }
    String catalog(String model,String rate) {
        String id="boundary-catalog-"+UUID.randomUUID();
        jdbc.update("insert into pricing_catalog_revision(id,source_reference,source_revision,content_hash) values (?,'synthetic-test',?,'fixture')",id,id);
        jdbc.update("insert into pricing_catalog_entry(id,revision_id,provider,endpoint,model,channel,price_revision,effective_from,effective_until,rates) values (?,?,'FAKE_GEMINI','generateContent',?,'FAKE','fixture',now()-interval '1 day',now()+interval '1 day',?::jsonb)","entry-"+UUID.randomUUID(),id,model,"{\"input\":\""+rate+"\"}");
        return id;
    }
    @Test void partialKnownAtCapWinsOverUnknownAndReplacingCurrentDoesNotDoubleCount() {
        String actor="partial-"+UUID.randomUUID(), workspace=workspaces.personal(actor).id(),model="partial-"+UUID.randomUUID();
        when(currentUser.getCurrentUserId()).thenReturn(actor);
        budgets.configure(new WorkspaceMonthlyBudgetOperations.ConfigureCommand(scope(workspace),actor,true,new BigDecimal("0.001"),0)).toCompletableFuture().join();
        var victim=claim(actor,workspace,model); var source=claim(actor,workspace,model);
        ledger.append(source.attempt(),new UsageLedger.Snapshot("synthetic",1,"FINAL","FAKE_RAW","pricing-fixture-v1","1","1","COMPLETE","{\"promptTokenCount\":1000,\"cachedContentTokenCount\":0,\"candidatesTokenCount\":500,\"thoughtsTokenCount\":0,\"toolUsePromptTokenCount\":0}"));
        assertEquals(WorkspaceAdmission.FinalizationResult.SETTLED,admission.finalizeAttempt(new WorkspaceAdmission.FinalizeAttempt(source.registration(),source.attempt(),source.claim(),WorkspaceAdmission.TerminalKind.SUCCEEDED)).toCompletableFuture().join());
        String version=jdbc.queryForObject("select projection_version::text from provider_attempt where id=?",String.class,source.attempt());
        var first=revisions.reconcile(new UsageCostRevisionOperations.ReconcileCommand(workspace,source.attempt(),null,version,"partial-first",catalog(model,"2"),"synthetic evidence","synthetic test"));
        assertEquals("PARTIAL",first.revision().status());
        var budget=budgets.read(new WorkspaceMonthlyBudgetOperations.ReadCommand(scope(workspace),actor)).toCompletableFuture().join();
        assertTrue(budget.terminalEffectiveUnknown()); assertTrue(budget.knownSubtotalUsd().compareTo(new BigDecimal("0.001"))>=0);
        var denied=assertInstanceOf(WorkspaceAdmission.RegisterResult.Denied.class,admission.register(new WorkspaceAdmission.Register(workspace,"new-"+UUID.randomUUID(),actor,victim.registration().conversationId(),WorkspaceAdmission.Partition.SIMULATION)).toCompletableFuture().join());
        assertEquals(WorkspaceAdmission.Reason.MONTH_CAP,denied.reason());
        assertEquals(1,jdbc.queryForObject("select count(*) from workspace_admission_effect_outbox where claim_id=?",Integer.class,victim.claim().claimId()));
        var second=revisions.reconcile(new UsageCostRevisionOperations.ReconcileCommand(workspace,source.attempt(),first.revision().id(),version,"partial-second",catalog(model,"3"),"synthetic evidence","synthetic replacement"));
        assertEquals("PARTIAL",second.revision().status());
        var updated=budgets.read(new WorkspaceMonthlyBudgetOperations.ReadCommand(scope(workspace),actor)).toCompletableFuture().join();
        assertEquals(0,updated.knownSubtotalUsd().compareTo(new BigDecimal(second.revision().knownSubtotal())));
        assertEquals(1,jdbc.queryForObject("select count(*) from workspace_admission_effect_outbox where claim_id=?",Integer.class,victim.claim().claimId()));
    }
    @Test void unknownOnlyBlocksNewAdmissionWithoutCancellationEffect() {
        String actor="unknown-"+UUID.randomUUID(), workspace=workspaces.personal(actor).id(),model="unknown-"+UUID.randomUUID();
        budgets.configure(new WorkspaceMonthlyBudgetOperations.ConfigureCommand(scope(workspace),actor,true,new BigDecimal("1"),0)).toCompletableFuture().join();
        var victim=claim(actor,workspace,model); var unknown=claim(actor,workspace,model);
        assertEquals(WorkspaceAdmission.FinalizationResult.SETTLED,admission.finalizeAttempt(new WorkspaceAdmission.FinalizeAttempt(unknown.registration(),unknown.attempt(),unknown.claim(),WorkspaceAdmission.TerminalKind.UNKNOWN)).toCompletableFuture().join());
        var denied=assertInstanceOf(WorkspaceAdmission.RegisterResult.Denied.class,admission.register(new WorkspaceAdmission.Register(workspace,"new-"+UUID.randomUUID(),actor,victim.registration().conversationId(),WorkspaceAdmission.Partition.SIMULATION)).toCompletableFuture().join());
        assertEquals(WorkspaceAdmission.Reason.MONTH_UNKNOWN,denied.reason());
        assertEquals(0,jdbc.queryForObject("select count(*) from workspace_admission_effect_outbox where registration_token=?",Integer.class,victim.registration().token()));
    }
}
