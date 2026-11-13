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
import java.math.BigDecimal;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class WorkspaceStartupCoordinatorIntegrationTests {
    @Autowired WorkspaceAdmission admission;
    @Autowired WorkspaceMonthlyBudgetOperations budgets;
    @Autowired WorkspaceOperations workspaces;
    @Autowired ConversationOperations conversations;
    @Autowired UsageLedger ledger;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;

    @Test void defaultStartupSweepsTerminalRunThenOpensWithUnknownCostBlocked() {
        String actor="coordinator-"+UUID.randomUUID(),workspace=workspaces.personal(actor).id();
        String bot="coordinator-bot-"+UUID.randomUUID(),run="coordinator-run-"+UUID.randomUUID();
        jdbc.update("insert into bot(id,name,target) values (?,?,'LOCAL')",bot,bot);
        var conversation=conversations.createConversation(bot,"fixture",actor,workspace);
        var scope=new WorkspaceMonthlyBudgetOperations.Scope(workspace,WorkspaceAdmission.Partition.REAL,YearMonth.now(ZoneId.of("Asia/Taipei")));
        budgets.configure(new WorkspaceMonthlyBudgetOperations.ConfigureCommand(scope,actor,true,new BigDecimal("100"),0)).toCompletableFuture().join();
        var registration=assertInstanceOf(WorkspaceAdmission.RegisterResult.Registered.class,admission.register(
                new WorkspaceAdmission.Register(workspace,run,actor,conversation.id(),WorkspaceAdmission.Partition.REAL)).toCompletableFuture().join()).registration();
        jdbc.update("insert into chat_run(id,conversation_id,client_request_id,input_text,status) values (?,?,?,'test','PENDING')",run,conversation.id(),UUID.randomUUID().toString());
        assertInstanceOf(WorkspaceAdmission.PublicationResult.Ready.class,admission.publish(registration).toCompletableFuture().join());
        String attempt=ledger.begin(new UsageLedger.Dispatch(run,workspace,actor,"FAKE","test","model","FAKE","1",false,"UNKNOWN"));
        var claim=assertInstanceOf(WorkspaceAdmission.Decision.Allowed.class,admission.dispatch(new WorkspaceAdmission.Dispatch(registration,attempt)).toCompletableFuture().join()).claim();
        assertEquals(WorkspaceAdmission.FinalizationResult.SETTLED,admission.finalizeAttempt(
                new WorkspaceAdmission.FinalizeAttempt(registration,attempt,claim,WorkspaceAdmission.TerminalKind.UNKNOWN)).toCompletableFuture().join());
        jdbc.update("update chat_run set status='FAILED' where id=?",run);
        var ds=(HikariDataSource)dataSource;
        try(var restarted=new SpringApplicationBuilder(BotApplication.class).web(WebApplicationType.NONE).profiles("test")
                .properties("spring.datasource.url="+ds.getJdbcUrl(),"spring.datasource.username="+ds.getUsername(),
                        "spring.datasource.password="+ds.getPassword(),"spring.docker.compose.enabled=false").run()) {
            assertEquals("TERMINAL",jdbc.queryForObject("select status from workspace_admission_registration where token=?",String.class,registration.token()));
            var restored=restarted.getBean(WorkspaceAdmission.class);
            assertEquals(WorkspaceAdmission.RecoveryReadiness.READY,restored.verifyAndOpen().toCompletableFuture().join());
            var denied=assertInstanceOf(WorkspaceAdmission.RegisterResult.Denied.class,restored.register(
                    new WorkspaceAdmission.Register(workspace,"next-"+UUID.randomUUID(),actor,conversation.id(),WorkspaceAdmission.Partition.REAL)).toCompletableFuture().join());
            assertEquals(WorkspaceAdmission.Reason.MONTH_UNKNOWN,denied.reason());
        }
    }

    @Test void unresolvedDispatchFailsStartupWithinBudgetAndPreservesEvidence() {
        String actor="coordinator-pending-"+UUID.randomUUID(),workspace=workspaces.personal(actor).id();
        String bot="coordinator-bot-"+UUID.randomUUID(),run="coordinator-run-"+UUID.randomUUID();
        jdbc.update("insert into bot(id,name,target) values (?,?,'LOCAL')",bot,bot);
        var conversation=conversations.createConversation(bot,"fixture",actor,workspace);
        var registration=assertInstanceOf(WorkspaceAdmission.RegisterResult.Registered.class,admission.register(
                new WorkspaceAdmission.Register(workspace,run,actor,conversation.id(),WorkspaceAdmission.Partition.REAL)).toCompletableFuture().join()).registration();
        jdbc.update("insert into chat_run(id,conversation_id,client_request_id,input_text,status) values (?,?,?,'test','PENDING')",run,conversation.id(),UUID.randomUUID().toString());
        assertInstanceOf(WorkspaceAdmission.PublicationResult.Ready.class,admission.publish(registration).toCompletableFuture().join());
        String attempt=ledger.begin(new UsageLedger.Dispatch(run,workspace,actor,"FAKE","test","model","FAKE","1",false,"UNKNOWN"));
        jdbc.update("insert into workspace_admission_dispatch(attempt_id,registration_token,state) values (?,?,'PENDING')",attempt,registration.token());
        jdbc.update("update chat_run set status='FAILED' where id=?",run);
        var ds=(HikariDataSource)dataSource;
        long start=System.nanoTime();
        assertThrows(RuntimeException.class,()->new SpringApplicationBuilder(BotApplication.class).web(WebApplicationType.NONE).profiles("test")
                .properties("spring.datasource.url="+ds.getJdbcUrl(),"spring.datasource.username="+ds.getUsername(),
                        "spring.datasource.password="+ds.getPassword(),"spring.docker.compose.enabled=false",
                        "bot.execution.startup-coordinator.budget=2s").run());
        assertTrue(java.time.Duration.ofNanos(System.nanoTime()-start).compareTo(java.time.Duration.ofSeconds(20))<0);
        assertEquals("PENDING",jdbc.queryForObject("select state from workspace_admission_dispatch where attempt_id=?",String.class,attempt));
        assertNotEquals("TERMINAL",jdbc.queryForObject("select status from workspace_admission_registration where token=?",String.class,registration.token()));
    }
}
