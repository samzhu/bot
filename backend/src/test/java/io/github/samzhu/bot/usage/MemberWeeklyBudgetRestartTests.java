package io.github.samzhu.bot;

import io.github.samzhu.bot.usage.*;
import io.github.samzhu.bot.workspace.WorkspaceOperations;
import io.github.samzhu.bot.conversation.ConversationOperations;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

@io.github.samzhu.bot.UseTestFakes
@SpringBootTest(properties="bot.test.weekly-restart-isolation=true")
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class MemberWeeklyBudgetRestartTests {
 @Autowired WorkspaceAdmission admission; @Autowired WorkspaceOperations workspaces;
 @Autowired ConversationOperations conversations; @Autowired UsageLedger ledger;
 @Autowired io.github.samzhu.bot.usage.MemberWeeklyBudgetOperations weekly;
 @Autowired JdbcTemplate jdbc; @Autowired javax.sql.DataSource dataSource;
 record Fixture(WorkspaceAdmission.Registration registration,String attempt,RunOwnershipGuard.Fence fence) {}
 Fixture fixture(String actor,String workspace) {
  String bot="restart-bot-"+UUID.randomUUID(),run="restart-run-"+UUID.randomUUID();
  jdbc.update("insert into bot(id,name,target) values (?,?,'LOCAL')",bot,bot);
  var conversation=conversations.createConversation(bot,"fixture",actor,workspace);
  var registration=assertInstanceOf(WorkspaceAdmission.RegisterResult.Registered.class,admission.register(new WorkspaceAdmission.Register(workspace,run,actor,conversation.id(),WorkspaceAdmission.Partition.REAL)).toCompletableFuture().join()).registration();
  jdbc.update("insert into chat_run(id,conversation_id,client_request_id,input_text,status) values (?,?,?,'test','PENDING')",run,conversation.id(),UUID.randomUUID().toString());
  var fence=SyntheticRunOwner.attach(jdbc,registration);
  assertInstanceOf(WorkspaceAdmission.PublicationResult.Ready.class,admission.publish(registration).toCompletableFuture().join());
  return new Fixture(registration,ledger.begin(new UsageLedger.Dispatch(run,workspace,actor,"FAKE","test","model","FAKE","1",false,"UNKNOWN"),fence),fence);
 }
    @Test void weeklyPolicyAndUnknownSurviveFreshContextOnSameDatabase() {
        String actor="weekly-restart-"+UUID.randomUUID(),workspace=workspaces.personal(actor).id();
        var f=fixture(actor,workspace);
        var allowed=assertInstanceOf(WorkspaceAdmission.Decision.Allowed.class,admission.dispatch(new WorkspaceAdmission.Dispatch(f.registration(),f.attempt(),f.fence())).toCompletableFuture().join());
        assertEquals(WorkspaceAdmission.FinalizationResult.SETTLED,admission.finalizeAttempt(new WorkspaceAdmission.FinalizeAttempt(f.registration(),f.attempt(),allowed.claim(),WorkspaceAdmission.TerminalKind.UNKNOWN,null,f.fence())).toCompletableFuture().join());
        weekly.configure(new io.github.samzhu.bot.usage.MemberWeeklyBudgetOperations.Configure(workspace,actor,WorkspaceAdmission.Partition.REAL,actor,true,"10","0","restart")).toCompletableFuture().join();
        var ds=(com.zaxxer.hikari.HikariDataSource)dataSource;
        try(var context=new org.springframework.boot.builder.SpringApplicationBuilder(BotApplication.class, io.github.samzhu.bot.TestChatModelConfiguration.class, io.github.samzhu.bot.TestUsageCaptureConfiguration.class).web(org.springframework.boot.WebApplicationType.NONE).profiles("test")
                .properties("spring.datasource.url="+ds.getJdbcUrl(),"spring.datasource.username="+ds.getUsername(),"spring.datasource.password="+ds.getPassword(),"spring.docker.compose.enabled=false").run()) {
            var recovered=context.getBean(io.github.samzhu.bot.usage.MemberWeeklyBudgetOperations.class).read(new io.github.samzhu.bot.usage.MemberWeeklyBudgetOperations.Read(workspace,actor,WorkspaceAdmission.Partition.REAL,actor,true)).toCompletableFuture().join();
            assertTrue(recovered.enabled()); assertEquals(1,recovered.terminalUnknownCount()); assertEquals(1,recovered.configVersion());
        }
    }
}
