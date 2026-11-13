package io.github.samzhu.bot;

import io.github.samzhu.bot.conversation.ConversationOperations;
import io.github.samzhu.bot.usage.WorkspaceAdmission;
import io.github.samzhu.bot.workspace.WorkspaceOperations;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties="bot.execution.startup-coordinator.enabled=false")
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class WorkspaceStartupBarrierTests {
    @Autowired WorkspaceAdmission admission;
    @Autowired WorkspaceOperations workspaces;
    @Autowired ConversationOperations conversations;
    @Autowired JdbcTemplate jdbc;
    @Autowired javax.sql.DataSource dataSource;

    @Test void admissionRemainsClosedBeforeFinalStartupVerificationEvenWhenBudgetDisabled() {
        String actor="startup-gate-"+UUID.randomUUID(),workspace=workspaces.personal(actor).id();
        String bot="startup-bot-"+UUID.randomUUID();
        jdbc.update("insert into bot(id,name,target) values (?,?,'LOCAL')",bot,bot);
        var conversation=conversations.createConversation(bot,"fixture",actor,workspace);
        var result=assertInstanceOf(WorkspaceAdmission.RegisterResult.Denied.class,admission.register(
                new WorkspaceAdmission.Register(workspace,"startup-run-"+UUID.randomUUID(),actor,conversation.id(),WorkspaceAdmission.Partition.REAL)).toCompletableFuture().join());
        assertEquals(WorkspaceAdmission.Reason.RECOVERY_REQUIRED,result.reason());
    }

    @Test void verifiedEmptyRecoveryOpensDisabledBudgetAdmission() {
        String actor="startup-ready-"+UUID.randomUUID(),workspace=workspaces.personal(actor).id();
        String bot="startup-bot-"+UUID.randomUUID();
        jdbc.update("insert into bot(id,name,target) values (?,?,'LOCAL')",bot,bot);
        var conversation=conversations.createConversation(bot,"fixture",actor,workspace);
        assertEquals(WorkspaceAdmission.RecoveryReadiness.READY,admission.verifyAndOpen().toCompletableFuture().join());
        assertInstanceOf(WorkspaceAdmission.RegisterResult.Registered.class,admission.register(
                new WorkspaceAdmission.Register(workspace,"ready-run-"+UUID.randomUUID(),actor,conversation.id(),WorkspaceAdmission.Partition.REAL)).toCompletableFuture().join());
    }

    @Test void unresolvedRegistrationKeepsFinalVerificationClosed() {
        String actor="startup-pending-"+UUID.randomUUID(),workspace=workspaces.personal(actor).id();
        String bot="startup-bot-"+UUID.randomUUID(),token=UUID.randomUUID().toString();
        jdbc.update("insert into bot(id,name,target) values (?,?,'LOCAL')",bot,bot);
        var conversation=conversations.createConversation(bot,"fixture",actor,workspace);
        jdbc.update("""
                insert into workspace_admission_registration(token,run_id,workspace_id,actor_id,conversation_id,partition,status,pending_id,pending_period_start,pending_period_end)
                values (?,?,?,?,?,'REAL','REGISTERED',?,date '2026-11-01',date '2026-12-01')
                """,token,"unresolved-"+UUID.randomUUID(),workspace,actor,conversation.id(),UUID.randomUUID().toString());
        assertEquals(WorkspaceAdmission.RecoveryReadiness.RETRY,admission.verifyAndOpen().toCompletableFuture().join());
        var denied=assertInstanceOf(WorkspaceAdmission.RegisterResult.Denied.class,admission.register(
                new WorkspaceAdmission.Register(workspace,"new-"+UUID.randomUUID(),actor,conversation.id(),WorkspaceAdmission.Partition.REAL)).toCompletableFuture().join());
        assertEquals(WorkspaceAdmission.Reason.RECOVERY_REQUIRED,denied.reason());
    }

    @Test void knownTerminalCostUnknownAllowsReadyButBlocksEnabledBudget() {
        String actor="startup-cost-"+UUID.randomUUID(),workspace=workspaces.personal(actor).id();
        String bot="startup-bot-"+UUID.randomUUID(),run="finished-run-"+UUID.randomUUID();
        jdbc.update("insert into bot(id,name,target) values (?,?,'LOCAL')",bot,bot);
        var conversation=conversations.createConversation(bot,"fixture",actor,workspace);
        jdbc.update("insert into chat_run(id,conversation_id,client_request_id,input_text,status) values (?,?,?,'test','FAILED')",run,conversation.id(),UUID.randomUUID().toString());
        jdbc.update("insert into workspace_monthly_budget(workspace_id,partition,enabled,cap_usd) values (?,'REAL',true,100)",workspace);
        jdbc.update("""
                insert into provider_attempt(id,run_id,workspace_id,actor_id,provider,endpoint,model,channel,adapter_version,
                    simulation,attempt_coverage,prepared_at,started_at,execution_outcome,usage_presence,capture_completeness,projection_state)
                values (?,?,?,?,'FAKE','test','model','FAKE','1',false,'UNKNOWN',current_timestamp,current_timestamp,'UNKNOWN','UNKNOWN','UNKNOWN','UNKNOWN')
                """,UUID.randomUUID().toString(),run,workspace,actor);
        assertEquals(WorkspaceAdmission.RecoveryReadiness.READY,admission.verifyAndOpen().toCompletableFuture().join());
        var denied=assertInstanceOf(WorkspaceAdmission.RegisterResult.Denied.class,admission.register(
                new WorkspaceAdmission.Register(workspace,"next-"+UUID.randomUUID(),actor,conversation.id(),WorkspaceAdmission.Partition.REAL)).toCompletableFuture().join());
        assertEquals(WorkspaceAdmission.Reason.MONTH_UNKNOWN,denied.reason());
    }

    @Test void bootRebuildAbandonsRegistrationWhoseRunWasNeverCreated() {
        String actor="boot-absence-"+UUID.randomUUID(),workspace=workspaces.personal(actor).id();
        String bot="boot-bot-"+UUID.randomUUID(),token=UUID.randomUUID().toString(),run="missing-run-"+UUID.randomUUID();
        jdbc.update("insert into bot(id,name,target) values (?,?,'LOCAL')",bot,bot);
        var conversation=conversations.createConversation(bot,"fixture",actor,workspace);
        jdbc.update("""
                insert into workspace_admission_registration(token,run_id,workspace_id,actor_id,conversation_id,partition,status,pending_id,pending_period_start,pending_period_end)
                values (?,?,?,?,?,'REAL','REGISTERED',?,date '2026-11-01',date '2026-12-01')
                """,token,run,workspace,actor,conversation.id(),UUID.randomUUID().toString());
        var ds=(com.zaxxer.hikari.HikariDataSource)dataSource;
        try(var restarted=new org.springframework.boot.builder.SpringApplicationBuilder(BotApplication.class)
                .web(org.springframework.boot.WebApplicationType.NONE).profiles("test")
                .properties("spring.datasource.url="+ds.getJdbcUrl(),"spring.datasource.username="+ds.getUsername(),
                        "spring.datasource.password="+ds.getPassword(),"spring.docker.compose.enabled=false").run()) {
            assertEquals("TERMINAL",jdbc.queryForObject("select status from workspace_admission_registration where token=?",String.class,token));
        }
    }

    @Test void bootDoesNotAbandonAbsentRunWithDurableCancellationEffect() {
        String actor="boot-effect-"+UUID.randomUUID(),workspace=workspaces.personal(actor).id();
        String bot="boot-bot-"+UUID.randomUUID(),token=UUID.randomUUID().toString(),run="missing-run-"+UUID.randomUUID();
        String pending=UUID.randomUUID().toString(),effect=UUID.randomUUID().toString();
        jdbc.update("insert into bot(id,name,target) values (?,?,'LOCAL')",bot,bot);
        var conversation=conversations.createConversation(bot,"fixture",actor,workspace);
        jdbc.update("""
                insert into workspace_admission_registration(token,run_id,workspace_id,actor_id,conversation_id,partition,status,pending_id,pending_period_start,pending_period_end)
                values (?,?,?,?,?,'REAL','REGISTERED',?,date '2026-11-01',date '2026-12-01')
                """,token,run,workspace,actor,conversation.id(),pending);
        jdbc.update("""
                insert into workspace_admission_effect_outbox(effect_id,registration_token,target_workspace_id,target_partition,target_run_id,
                    pending_id,pending_epoch,period_start,period_end,generation,delivery_status,intent_receipt)
                values (?,?,?,'REAL',?,?,0,date '2026-11-01',date '2026-12-01',0,'PENDING',?)
                """,effect,token,workspace,run,pending,UUID.randomUUID().toString());
        var ds=(com.zaxxer.hikari.HikariDataSource)dataSource;
        assertThrows(RuntimeException.class,()->new org.springframework.boot.builder.SpringApplicationBuilder(BotApplication.class)
                .web(org.springframework.boot.WebApplicationType.NONE).profiles("test")
                .properties("spring.datasource.url="+ds.getJdbcUrl(),"spring.datasource.username="+ds.getUsername(),
                        "spring.datasource.password="+ds.getPassword(),"spring.docker.compose.enabled=false").run());
        assertEquals("REGISTERED",jdbc.queryForObject("select status from workspace_admission_registration where token=?",String.class,token));
        assertEquals("PENDING",jdbc.queryForObject("select delivery_status from workspace_admission_effect_outbox where effect_id=?",String.class,effect));
    }

    @Test void bootDoesNotTreatRegistrationLockTimeoutAsRunAbsence() throws Exception {
        String actor="boot-lock-"+UUID.randomUUID(),workspace=workspaces.personal(actor).id();
        String bot="boot-bot-"+UUID.randomUUID(),token=UUID.randomUUID().toString(),run="missing-run-"+UUID.randomUUID();
        jdbc.update("insert into bot(id,name,target) values (?,?,'LOCAL')",bot,bot);
        var conversation=conversations.createConversation(bot,"fixture",actor,workspace);
        jdbc.update("""
                insert into workspace_admission_registration(token,run_id,workspace_id,actor_id,conversation_id,partition,status,pending_id,pending_period_start,pending_period_end)
                values (?,?,?,?,?,'REAL','REGISTERED',?,date '2026-11-01',date '2026-12-01')
                """,token,run,workspace,actor,conversation.id(),UUID.randomUUID().toString());
        var ds=(com.zaxxer.hikari.HikariDataSource)dataSource;
        try (var connection=dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (var lock=connection.prepareStatement("select token from workspace_admission_registration where token=? for update")) {
                lock.setString(1,token); lock.executeQuery().close();
            }
            assertThrows(RuntimeException.class,()->new org.springframework.boot.builder.SpringApplicationBuilder(BotApplication.class)
                    .web(org.springframework.boot.WebApplicationType.NONE).profiles("test")
                    .properties("spring.datasource.url="+ds.getJdbcUrl(),"spring.datasource.username="+ds.getUsername(),
                            "spring.datasource.password="+ds.getPassword(),"spring.docker.compose.enabled=false").run());
            connection.rollback();
        }
        assertEquals("REGISTERED",jdbc.queryForObject("select status from workspace_admission_registration where token=?",String.class,token));
    }

    @Test void bootDoesNotTreatTypedRunReaderTimeoutAsAbsence() throws Exception {
        String actor="boot-run-lock-"+UUID.randomUUID(),workspace=workspaces.personal(actor).id();
        String bot="boot-bot-"+UUID.randomUUID(),token=UUID.randomUUID().toString(),run="locked-run-"+UUID.randomUUID();
        jdbc.update("insert into bot(id,name,target) values (?,?,'LOCAL')",bot,bot);
        var conversation=conversations.createConversation(bot,"fixture",actor,workspace);
        jdbc.update("insert into chat_run(id,conversation_id,client_request_id,input_text,status) values (?,?,?,'test','FAILED')",run,conversation.id(),UUID.randomUUID().toString());
        jdbc.update("""
                insert into workspace_admission_registration(token,run_id,workspace_id,actor_id,conversation_id,partition,status,pending_id,pending_period_start,pending_period_end)
                values (?,?,?,?,?,'REAL','REGISTERED',?,date '2026-11-01',date '2026-12-01')
                """,token,run,workspace,actor,conversation.id(),UUID.randomUUID().toString());
        var ds=(com.zaxxer.hikari.HikariDataSource)dataSource;
        try (var connection=dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (var lock=connection.prepareStatement("select id from chat_run where id=? for update")) {
                lock.setString(1,run); lock.executeQuery().close();
            }
            assertThrows(RuntimeException.class,()->new org.springframework.boot.builder.SpringApplicationBuilder(BotApplication.class)
                    .web(org.springframework.boot.WebApplicationType.NONE).profiles("test")
                    .properties("spring.datasource.url="+ds.getJdbcUrl(),"spring.datasource.username="+ds.getUsername(),
                            "spring.datasource.password="+ds.getPassword(),"spring.docker.compose.enabled=false").run());
            connection.rollback();
        }
        assertEquals("REGISTERED",jdbc.queryForObject("select status from workspace_admission_registration where token=?",String.class,token));
        assertEquals("FAILED",jdbc.queryForObject("select status from chat_run where id=?",String.class,run));
    }
}
