package io.github.samzhu.bot;

import com.zaxxer.hikari.HikariDataSource;
import io.github.samzhu.bot.conversation.ConversationOperations;
import io.github.samzhu.bot.usage.UsageLedger;
import io.github.samzhu.bot.usage.WorkspaceAdmission;
import io.github.samzhu.bot.usage.WorkspaceMonthlyBudgetOperations;
import io.github.samzhu.bot.workspace.WorkspaceOperations;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import javax.sql.DataSource;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class WorkspaceAdmissionRestartTests {
    @Autowired WorkspaceAdmission admission;
    @Autowired WorkspaceOperations workspaces;
    @Autowired ConversationOperations conversations;
    @Autowired UsageLedger ledger;
    @Autowired WorkspaceMonthlyBudgetOperations budgets;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;

    @Test void pendingTerminalIntentKeepsGateClosedAfterNewContextUntilRecovery() {
        String actor="restart-user-"+UUID.randomUUID(),workspace=workspaces.personal(actor).id();
        String bot="restart-bot-"+UUID.randomUUID(),run="restart-run-"+UUID.randomUUID();
        var currentScope=new WorkspaceMonthlyBudgetOperations.Scope(workspace,WorkspaceAdmission.Partition.REAL,YearMonth.now(ZoneId.of("Asia/Taipei")));
        budgets.configure(new WorkspaceMonthlyBudgetOperations.ConfigureCommand(currentScope,actor,true,new java.math.BigDecimal("100"),0)).toCompletableFuture().join();
        jdbc.update("insert into bot(id,name,target) values (?,?,'LOCAL')",bot,bot);
        var conversation=conversations.createConversation(bot,"fixture",actor,workspace);
        var registration=assertInstanceOf(WorkspaceAdmission.RegisterResult.Registered.class,
                admission.register(new WorkspaceAdmission.Register(workspace,run,actor,conversation.id(),WorkspaceAdmission.Partition.REAL)).toCompletableFuture().join()).registration();
        jdbc.update("insert into chat_run(id,conversation_id,client_request_id,input_text,status) values (?,?,?,'test','PENDING')",run,conversation.id(),UUID.randomUUID().toString());
        assertInstanceOf(WorkspaceAdmission.PublicationResult.Ready.class,admission.publish(registration).toCompletableFuture().join());
        String attempt=ledger.begin(new UsageLedger.Dispatch(run,workspace,actor,"FAKE","test","model","FAKE","1",false,"UNKNOWN"));
        var allowed=assertInstanceOf(WorkspaceAdmission.Decision.Allowed.class,admission.dispatch(new WorkspaceAdmission.Dispatch(registration,attempt)).toCompletableFuture().join());
        jdbc.update("insert into workspace_admission_finalization(attempt_id,registration_token,claim_id,terminal_kind,terminal_reason,state) values (?,?,?,'UNKNOWN','CAPTURE_FAILED','PENDING')",attempt,registration.token(),allowed.claim().claimId());
        var ds=(HikariDataSource)dataSource;
        try(var restarted=new SpringApplicationBuilder(BotApplication.class).web(WebApplicationType.NONE).profiles("test")
                .properties("spring.datasource.url="+ds.getJdbcUrl(),"spring.datasource.username="+ds.getUsername(),
                        "spring.datasource.password="+ds.getPassword(),"spring.docker.compose.enabled=false",
                        "bot.pricing.reconcile.initial-delay-ms=3600000","bot.pricing.reconcile.delay-ms=3600000").run()) {
            var restored=restarted.getBean(WorkspaceAdmission.class);
            var denied=assertInstanceOf(WorkspaceAdmission.RegisterResult.Denied.class,restored.register(
                    new WorkspaceAdmission.Register(workspace,"later-"+UUID.randomUUID(),actor,conversation.id(),WorkspaceAdmission.Partition.REAL)).toCompletableFuture().join());
            assertEquals(WorkspaceAdmission.Reason.MONTH_UNKNOWN,denied.reason());
            assertEquals("SETTLED",jdbc.queryForObject("select state from workspace_admission_finalization where attempt_id=?",String.class,attempt));
            assertEquals("UNKNOWN",jdbc.queryForObject("select execution_outcome from provider_attempt where id=?",String.class,attempt));
            assertEquals("CAPTURE_FAILED",jdbc.queryForObject("select execution_reason from provider_attempt where id=?",String.class,attempt));
            assertEquals("CAPTURE_FAILED",jdbc.queryForObject("select projection_error from provider_attempt where id=?",String.class,attempt));
            assertEquals("TERMINAL",jdbc.queryForObject("select status from workspace_admission_registration where token=?",String.class,registration.token()));
            assertInstanceOf(WorkspaceAdmission.RegisterResult.Registered.class,restored.register(
                    new WorkspaceAdmission.Register(workspace,"sim-"+UUID.randomUUID(),actor,conversation.id(),WorkspaceAdmission.Partition.SIMULATION)).toCompletableFuture().join());
            assertEquals(WorkspaceAdmission.FinalizationResult.SETTLED,restored.recoverFinalization(registration,attempt).toCompletableFuture().join());
            assertEquals("UNKNOWN",jdbc.queryForObject("select execution_outcome from provider_attempt where id=?",String.class,attempt));
            var scope=new WorkspaceMonthlyBudgetOperations.Scope(workspace,WorkspaceAdmission.Partition.REAL,YearMonth.from(allowed.claim().periodStart().atZone(ZoneId.of("Asia/Taipei"))));
            var budget=restarted.getBean(WorkspaceMonthlyBudgetOperations.class).read(new WorkspaceMonthlyBudgetOperations.ReadCommand(scope,actor)).toCompletableFuture().join();
            assertTrue(budget.enabled());
            assertTrue(budget.terminalEffectiveUnknown());
            assertNull(budget.knownSubtotalUsd());
            var blocked=assertInstanceOf(WorkspaceAdmission.RegisterResult.Denied.class,restored.register(
                    new WorkspaceAdmission.Register(workspace,"next-"+UUID.randomUUID(),actor,conversation.id(),WorkspaceAdmission.Partition.REAL)).toCompletableFuture().join());
            assertEquals(WorkspaceAdmission.Reason.MONTH_UNKNOWN,blocked.reason());
        }
    }
    @Test void pendingDispatchIntentSurvivesContextRestartAndCannotBecomeFalseNoClaim() {
        String actor="dispatch-restart-"+UUID.randomUUID(),workspace=workspaces.personal(actor).id();
        String bot="dispatch-bot-"+UUID.randomUUID(),run="dispatch-run-"+UUID.randomUUID();
        jdbc.update("insert into bot(id,name,target) values (?,?,'LOCAL')",bot,bot);
        var conversation=conversations.createConversation(bot,"fixture",actor,workspace);
        var registration=assertInstanceOf(WorkspaceAdmission.RegisterResult.Registered.class,admission.register(
                new WorkspaceAdmission.Register(workspace,run,actor,conversation.id(),WorkspaceAdmission.Partition.REAL)).toCompletableFuture().join()).registration();
        jdbc.update("insert into chat_run(id,conversation_id,client_request_id,input_text,status) values (?,?,?,'test','PENDING')",run,conversation.id(),UUID.randomUUID().toString());
        assertInstanceOf(WorkspaceAdmission.PublicationResult.Ready.class,admission.publish(registration).toCompletableFuture().join());
        String attempt=ledger.begin(new UsageLedger.Dispatch(run,workspace,actor,"FAKE","test","model","FAKE","1",false,"UNKNOWN"));
        jdbc.update("insert into workspace_admission_dispatch(attempt_id,registration_token,state) values (?,?,'PENDING')",attempt,registration.token());
        var ds=(HikariDataSource)dataSource;
        assertThrows(RuntimeException.class,()->new SpringApplicationBuilder(BotApplication.class).web(WebApplicationType.NONE).profiles("test")
                .properties("spring.datasource.url="+ds.getJdbcUrl(),"spring.datasource.username="+ds.getUsername(),
                        "spring.datasource.password="+ds.getPassword(),"spring.docker.compose.enabled=false",
                        "bot.execution.startup-coordinator.budget=2s",
                        "bot.pricing.reconcile.initial-delay-ms=3600000","bot.pricing.reconcile.delay-ms=3600000").run());
        assertEquals("PENDING",jdbc.queryForObject("select state from workspace_admission_dispatch where attempt_id=?",String.class,attempt));
        assertEquals("UNKNOWN",jdbc.queryForObject("select execution_outcome from provider_attempt where id=?",String.class,attempt));
        assertEquals(0,jdbc.queryForObject("select count(*) from workspace_admission_claim where attempt_id=?",Integer.class,attempt));
        assertNotEquals("TERMINAL",jdbc.queryForObject("select status from workspace_admission_registration where token=?",String.class,registration.token()));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value=WorkspaceAdmission.TerminalKind.class,names={"SUCCEEDED","FAILED","CANCELLED"})
    void committedSuccessfulTerminalIntentSurvivesInitializerOnContextRestart(WorkspaceAdmission.TerminalKind kind) {
        String actor="intent-restart-"+UUID.randomUUID(),workspace=workspaces.personal(actor).id();
        String bot="intent-bot-"+UUID.randomUUID(),run="intent-run-"+UUID.randomUUID();
        jdbc.update("insert into bot(id,name,target) values (?,?,'LOCAL')",bot,bot);
        var conversation=conversations.createConversation(bot,"fixture",actor,workspace);
        var registration=assertInstanceOf(WorkspaceAdmission.RegisterResult.Registered.class,admission.register(
                new WorkspaceAdmission.Register(workspace,run,actor,conversation.id(),WorkspaceAdmission.Partition.REAL)).toCompletableFuture().join()).registration();
        jdbc.update("insert into chat_run(id,conversation_id,client_request_id,input_text,status) values (?,?,?,'test','PENDING')",run,conversation.id(),UUID.randomUUID().toString());
        assertInstanceOf(WorkspaceAdmission.PublicationResult.Ready.class,admission.publish(registration).toCompletableFuture().join());
        String attempt=ledger.begin(new UsageLedger.Dispatch(run,workspace,actor,"FAKE","test","model","FAKE","1",false,"UNKNOWN"));
        var claim=assertInstanceOf(WorkspaceAdmission.Decision.Allowed.class,admission.dispatch(new WorkspaceAdmission.Dispatch(registration,attempt)).toCompletableFuture().join()).claim();
        String function="fail_restart_"+UUID.randomUUID().toString().replace("-","");
        jdbc.execute("create function "+function+"() returns trigger language plpgsql as $$ begin if new.id='"+attempt+"' and new.execution_outcome='"+kind.name()+"' then raise exception 'injected settlement failure'; end if; return new; end $$");
        jdbc.execute("create trigger "+function+" before update on provider_attempt for each row execute function "+function+"()");
        try {
            assertEquals(WorkspaceAdmission.FinalizationResult.RECOVERY_REQUIRED,admission.finalizeAttempt(
                    new WorkspaceAdmission.FinalizeAttempt(registration,attempt,claim,kind)).toCompletableFuture().join());
            assertEquals("PENDING",jdbc.queryForObject("select state from workspace_admission_finalization where attempt_id=?",String.class,attempt));
        } finally {
            jdbc.execute("drop trigger if exists "+function+" on provider_attempt");
            jdbc.execute("drop function if exists "+function+"()");
        }
        var ds=(HikariDataSource)dataSource;
        try(var restarted=new SpringApplicationBuilder(BotApplication.class).web(WebApplicationType.NONE).profiles("test")
                .properties("spring.datasource.url="+ds.getJdbcUrl(),"spring.datasource.username="+ds.getUsername(),
                        "spring.datasource.password="+ds.getPassword(),"spring.docker.compose.enabled=false").run()) {
            assertEquals(WorkspaceAdmission.FinalizationResult.SETTLED,restarted.getBean(WorkspaceAdmission.class)
                    .recoverFinalization(registration,attempt).toCompletableFuture().join());
            assertEquals(kind.name(),jdbc.queryForObject("select execution_outcome from provider_attempt where id=?",String.class,attempt));
        }
    }

    @Test void conflictingKnownTerminalAndIntentFailsClosedOnRestart() {
        String actor="conflict-user-"+UUID.randomUUID(),workspace=workspaces.personal(actor).id();
        String bot="conflict-bot-"+UUID.randomUUID(),run="conflict-run-"+UUID.randomUUID();
        jdbc.update("insert into bot(id,name,target) values (?,?,'LOCAL')",bot,bot);
        var conversation=conversations.createConversation(bot,"fixture",actor,workspace);
        var registration=assertInstanceOf(WorkspaceAdmission.RegisterResult.Registered.class,admission.register(
                new WorkspaceAdmission.Register(workspace,run,actor,conversation.id(),WorkspaceAdmission.Partition.REAL)).toCompletableFuture().join()).registration();
        jdbc.update("insert into chat_run(id,conversation_id,client_request_id,input_text,status) values (?,?,?,'test','PENDING')",run,conversation.id(),UUID.randomUUID().toString());
        assertInstanceOf(WorkspaceAdmission.PublicationResult.Ready.class,admission.publish(registration).toCompletableFuture().join());
        String attempt=ledger.begin(new UsageLedger.Dispatch(run,workspace,actor,"FAKE","test","model","FAKE","1",false,"UNKNOWN"));
        var claim=assertInstanceOf(WorkspaceAdmission.Decision.Allowed.class,admission.dispatch(new WorkspaceAdmission.Dispatch(registration,attempt)).toCompletableFuture().join()).claim();
        jdbc.update("insert into workspace_admission_finalization(attempt_id,registration_token,claim_id,terminal_kind,state) values (?,?,?,'SUCCEEDED','PENDING')",attempt,registration.token(),claim.claimId());
        jdbc.update("update provider_attempt set execution_outcome='FAILED' where id=?",attempt);
        var ds=(HikariDataSource)dataSource;
        assertThrows(RuntimeException.class,()->new SpringApplicationBuilder(BotApplication.class).web(WebApplicationType.NONE).profiles("test")
                .properties("spring.datasource.url="+ds.getJdbcUrl(),"spring.datasource.username="+ds.getUsername(),
                        "spring.datasource.password="+ds.getPassword(),"spring.docker.compose.enabled=false").run());
        assertEquals("FAILED",jdbc.queryForObject("select execution_outcome from provider_attempt where id=?",String.class,attempt));
        assertEquals("PENDING",jdbc.queryForObject("select state from workspace_admission_finalization where attempt_id=?",String.class,attempt));
    }

}
