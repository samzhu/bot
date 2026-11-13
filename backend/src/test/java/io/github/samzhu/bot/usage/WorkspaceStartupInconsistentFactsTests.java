package io.github.samzhu.bot;

import com.zaxxer.hikari.HikariDataSource;
import io.github.samzhu.bot.conversation.ConversationOperations;
import io.github.samzhu.bot.usage.RunAdmissionStateReader;
import io.github.samzhu.bot.usage.UsageLedger;
import io.github.samzhu.bot.usage.WorkspaceAdmission;
import io.github.samzhu.bot.workspace.WorkspaceOperations;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.annotation.DirtiesContext;

import javax.sql.DataSource;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class WorkspaceStartupInconsistentFactsTests {
    @Autowired WorkspaceAdmission admission;
    @Autowired WorkspaceOperations workspaces;
    @Autowired ConversationOperations conversations;
    @Autowired UsageLedger ledger;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;

    @ParameterizedTest
    @ValueSource(strings={"ATTEMPT","CLAIM","DISPATCH","FINALIZATION"})
    void absentReaderWithDurableAttemptFactsFailsStartupWithoutAbandoning(String fact) {
        String actor="inconsistent-"+UUID.randomUUID(), workspace=workspaces.personal(actor).id();
        String bot="inconsistent-bot-"+UUID.randomUUID(),run="inconsistent-run-"+UUID.randomUUID();
        jdbc.update("insert into bot(id,name,target) values (?,?,'LOCAL')",bot,bot);
        var conversation=conversations.createConversation(bot,"fixture",actor,workspace);
        var registration=assertInstanceOf(WorkspaceAdmission.RegisterResult.Registered.class,admission.register(
                new WorkspaceAdmission.Register(workspace,run,actor,conversation.id(),WorkspaceAdmission.Partition.REAL)).toCompletableFuture().join()).registration();
        jdbc.update("insert into chat_run(id,conversation_id,client_request_id,input_text,status) values (?,?,?,'test','PENDING')",run,conversation.id(),UUID.randomUUID().toString());
        assertInstanceOf(WorkspaceAdmission.PublicationResult.Ready.class,admission.publish(registration).toCompletableFuture().join());
        String attempt=ledger.begin(new UsageLedger.Dispatch(run,workspace,actor,"FAKE","test","model","FAKE","1",false,"UNKNOWN"));
        if (fact.equals("CLAIM") || fact.equals("FINALIZATION")) {
            var claim=assertInstanceOf(WorkspaceAdmission.Decision.Allowed.class,admission.dispatch(new WorkspaceAdmission.Dispatch(registration,attempt)).toCompletableFuture().join()).claim();
            if (fact.equals("FINALIZATION")) jdbc.update("insert into workspace_admission_finalization(attempt_id,registration_token,claim_id,terminal_kind,state) values (?,?,?,'UNKNOWN','PENDING')",attempt,registration.token(),claim.claimId());
        }
        if (fact.equals("DISPATCH")) jdbc.update("insert into workspace_admission_dispatch(attempt_id,registration_token,state) values (?,?,'PENDING')",attempt,registration.token());
        jdbc.update("update workspace_admission_registration set status='REGISTERED' where token=?",registration.token());
        assertThrows(RuntimeException.class,()->restart(AbsentReaderFixture.class));
        assertEquals("REGISTERED",jdbc.queryForObject("select status from workspace_admission_registration where token=?",String.class,registration.token()));
        assertEquals(1,jdbc.queryForObject("select count(*) from provider_attempt where id=?",Integer.class,attempt));
        if (fact.equals("CLAIM") || fact.equals("FINALIZATION")) assertEquals(1,jdbc.queryForObject("select count(*) from workspace_admission_claim where attempt_id=?",Integer.class,attempt));
        if (fact.equals("DISPATCH")) assertEquals(1,jdbc.queryForObject("select count(*) from workspace_admission_dispatch where attempt_id=?",Integer.class,attempt));
        if (fact.equals("FINALIZATION")) assertEquals(1,jdbc.queryForObject("select count(*) from workspace_admission_finalization where attempt_id=?",Integer.class,attempt));
    }

    @Test void unknownReaderDoesNotAbandonRegistration() {
        String actor="reader-unknown-"+UUID.randomUUID(),workspace=workspaces.personal(actor).id();
        String bot="reader-bot-"+UUID.randomUUID(),token=UUID.randomUUID().toString();
        jdbc.update("insert into bot(id,name,target) values (?,?,'LOCAL')",bot,bot);
        var conversation=conversations.createConversation(bot,"fixture",actor,workspace);
        jdbc.update("""
                insert into workspace_admission_registration(token,run_id,workspace_id,actor_id,conversation_id,partition,status,pending_id,pending_period_start,pending_period_end)
                values (?,?,?,?,?,'REAL','REGISTERED',?,date '2026-11-01',date '2026-12-01')
                """,token,"missing-"+UUID.randomUUID(),workspace,actor,conversation.id(),UUID.randomUUID().toString());
        assertThrows(RuntimeException.class,()->restart(UnknownReaderFixture.class));
        assertEquals("REGISTERED",jdbc.queryForObject("select status from workspace_admission_registration where token=?",String.class,token));
    }

    private void restart(Class<?> override) {
        var ds=(HikariDataSource)dataSource;
        try(var ignored=new SpringApplicationBuilder(BotApplication.class,override).web(WebApplicationType.NONE).profiles("test")
                .properties("spring.datasource.url="+ds.getJdbcUrl(),"spring.datasource.username="+ds.getUsername(),
                        "spring.datasource.password="+ds.getPassword(),"spring.docker.compose.enabled=false",
                        "bot.execution.startup-coordinator.budget=2s").run()) { }
    }
}

@TestConfiguration(proxyBeanMethods=false)
class AbsentReaderFixture {
    @Bean @Primary RunAdmissionStateReader absentRunReader() {
        return runId -> new RunAdmissionStateReader.State(RunAdmissionStateReader.Status.NOT_FOUND,null);
    }
}

@TestConfiguration(proxyBeanMethods=false)
class UnknownReaderFixture {
    @Bean @Primary RunAdmissionStateReader unavailableRunReader() {
        return runId -> { throw new IllegalStateException("injected authority lookup failure"); };
    }
}
