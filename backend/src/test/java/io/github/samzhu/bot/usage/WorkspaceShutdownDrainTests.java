package io.github.samzhu.bot;

import com.zaxxer.hikari.HikariDataSource;
import io.github.samzhu.bot.conversation.ConversationOperations;
import io.github.samzhu.bot.usage.UsageLedger;
import io.github.samzhu.bot.usage.WorkspaceAdmission;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class WorkspaceShutdownDrainTests {
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;

    @Test void contextShutdownDrainsAcceptedLaneWorkBeforeDatasourceCloses() throws Exception {
        var ds=(HikariDataSource)dataSource;
        var context=new SpringApplicationBuilder(BotApplication.class).web(WebApplicationType.NONE).profiles("test")
                .properties("spring.datasource.url="+ds.getJdbcUrl(),"spring.datasource.username="+ds.getUsername(),
                        "spring.datasource.password="+ds.getPassword(),"spring.docker.compose.enabled=false").run();
        var admission=context.getBean(WorkspaceAdmission.class);
        var conversations=context.getBean(ConversationOperations.class);
        var ledger=context.getBean(UsageLedger.class);
        String suffix=UUID.randomUUID().toString().substring(0,8), a="Aa"+suffix,b="BB"+suffix,actor="shutdown-"+UUID.randomUUID();
        assertEquals(a.hashCode(),b.hashCode());
        String bot="shutdown-bot-"+UUID.randomUUID(),runA="shutdown-a-"+UUID.randomUUID(),runB="shutdown-b-"+UUID.randomUUID();
        jdbc.update("insert into workspace(id,name) values (?,?),(?,?)",a,"A",b,"B");
        jdbc.update("insert into workspace_member(workspace_id,user_id,role,active) values (?,?,'MANAGER',true),(?,?,'MANAGER',true)",a,actor,b,actor);
        jdbc.update("insert into bot(id,name,target) values (?,?,'LOCAL')",bot,bot);
        var convA=conversations.createConversation(bot,"fixture-a",actor,a); var convB=conversations.createConversation(bot,"fixture-b",actor,b);
        var regA=assertInstanceOf(WorkspaceAdmission.RegisterResult.Registered.class,admission.register(new WorkspaceAdmission.Register(a,runA,actor,convA.id(),WorkspaceAdmission.Partition.REAL)).toCompletableFuture().join()).registration();
        var regB=assertInstanceOf(WorkspaceAdmission.RegisterResult.Registered.class,admission.register(new WorkspaceAdmission.Register(b,runB,actor,convB.id(),WorkspaceAdmission.Partition.REAL)).toCompletableFuture().join()).registration();
        jdbc.update("insert into chat_run(id,conversation_id,client_request_id,input_text,status) values (?,?,?,'test','PENDING'),(?,?,?,'test','PENDING')",runA,convA.id(),UUID.randomUUID().toString(),runB,convB.id(),UUID.randomUUID().toString());
        assertInstanceOf(WorkspaceAdmission.PublicationResult.Ready.class,admission.publish(regB).toCompletableFuture().join());
        String attempt=ledger.begin(new UsageLedger.Dispatch(runB,b,actor,"FAKE","test","model","FAKE","1",false,"UNKNOWN"));
        var claim=assertInstanceOf(WorkspaceAdmission.Decision.Allowed.class,admission.dispatch(new WorkspaceAdmission.Dispatch(regB,attempt)).toCompletableFuture().join()).claim();
        CompletableFuture<Void> close=null;
        try(var connection=dataSource.getConnection()) {
            connection.setAutoCommit(false); int blocker;
            try(var s=connection.createStatement();var rs=s.executeQuery("select pg_backend_pid()")) { rs.next(); blocker=rs.getInt(1); }
            try(var s=connection.prepareStatement("select token from workspace_admission_registration where token=? for update")) { s.setString(1,regA.token()); s.executeQuery().close(); }
            var blocked=admission.publish(regA).toCompletableFuture();
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5); boolean waiting;
            do { waiting=Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from pg_stat_activity where ?=any(pg_blocking_pids(pid)))",Boolean.class,blocker));
                if(waiting) break; Thread.sleep(20); } while(System.nanoTime()<deadline);
            assertTrue(waiting,"first workspace operation must hold its stripe at the PG lock");
            var terminal=admission.finalizeAttempt(new WorkspaceAdmission.FinalizeAttempt(regB,attempt,claim,WorkspaceAdmission.TerminalKind.UNKNOWN)).toCompletableFuture();
            assertEquals("PENDING",jdbc.queryForObject("select state from workspace_admission_finalization where attempt_id=?",String.class,attempt));
            var closing=CompletableFuture.runAsync(context::close); close=closing;
            try { assertThrows(java.util.concurrent.TimeoutException.class,()->closing.get(500,TimeUnit.MILLISECONDS)); }
            finally { connection.rollback(); }
            assertInstanceOf(WorkspaceAdmission.PublicationResult.Ready.class,blocked.get(10,TimeUnit.SECONDS));
            assertEquals(WorkspaceAdmission.FinalizationResult.SETTLED,terminal.get(10,TimeUnit.SECONDS));
            close.get(10,TimeUnit.SECONDS);
            assertEquals("SETTLED",jdbc.queryForObject("select state from workspace_admission_finalization where attempt_id=?",String.class,attempt));
        } finally {
            if (context.isActive()) context.close();
            if (close!=null) close.handle((value,error)->null).get(10,TimeUnit.SECONDS);
        }
    }

    @Test void drainTimeoutPreservesTerminalIntentForAutomaticRestartRecovery() throws Exception {
        var ds=(HikariDataSource)dataSource;
        var context=new SpringApplicationBuilder(BotApplication.class).web(WebApplicationType.NONE).profiles("test")
                .properties("spring.datasource.url="+ds.getJdbcUrl(),"spring.datasource.username="+ds.getUsername(),
                        "spring.datasource.password="+ds.getPassword(),"spring.docker.compose.enabled=false").run();
        var admission=context.getBean(WorkspaceAdmission.class); var conversations=context.getBean(ConversationOperations.class);
        var ledger=context.getBean(UsageLedger.class);
        String suffix=UUID.randomUUID().toString().substring(0,8),a="Aa"+suffix,b="BB"+suffix,actor="shutdown-timeout-"+UUID.randomUUID();
        String bot="shutdown-bot-"+UUID.randomUUID(),runA="shutdown-a-"+UUID.randomUUID(),runB="shutdown-b-"+UUID.randomUUID();
        jdbc.update("insert into workspace(id,name) values (?,?),(?,?)",a,"A",b,"B");
        jdbc.update("insert into workspace_member(workspace_id,user_id,role,active) values (?,?,'MANAGER',true),(?,?,'MANAGER',true)",a,actor,b,actor);
        jdbc.update("insert into bot(id,name,target) values (?,?,'LOCAL')",bot,bot);
        var convA=conversations.createConversation(bot,"fixture-a",actor,a); var convB=conversations.createConversation(bot,"fixture-b",actor,b);
        var regA=assertInstanceOf(WorkspaceAdmission.RegisterResult.Registered.class,admission.register(new WorkspaceAdmission.Register(a,runA,actor,convA.id(),WorkspaceAdmission.Partition.REAL)).toCompletableFuture().join()).registration();
        var regB=assertInstanceOf(WorkspaceAdmission.RegisterResult.Registered.class,admission.register(new WorkspaceAdmission.Register(b,runB,actor,convB.id(),WorkspaceAdmission.Partition.REAL)).toCompletableFuture().join()).registration();
        jdbc.update("insert into chat_run(id,conversation_id,client_request_id,input_text,status) values (?,?,?,'test','PENDING'),(?,?,?,'test','PENDING')",runA,convA.id(),UUID.randomUUID().toString(),runB,convB.id(),UUID.randomUUID().toString());
        assertInstanceOf(WorkspaceAdmission.PublicationResult.Ready.class,admission.publish(regB).toCompletableFuture().join());
        String attempt=ledger.begin(new UsageLedger.Dispatch(runB,b,actor,"FAKE","test","model","FAKE","1",false,"UNKNOWN"));
        var claim=assertInstanceOf(WorkspaceAdmission.Decision.Allowed.class,admission.dispatch(new WorkspaceAdmission.Dispatch(regB,attempt)).toCompletableFuture().join()).claim();
        CompletableFuture<Void> closing=null;
        try(var connection=dataSource.getConnection()) {
            connection.setAutoCommit(false); int blocker;
            try(var s=connection.createStatement();var rs=s.executeQuery("select pg_backend_pid()")) { rs.next(); blocker=rs.getInt(1); }
            try(var s=connection.prepareStatement("select token from workspace_admission_registration where token=? for update")) { s.setString(1,regA.token()); s.executeQuery().close(); }
            var blocked=admission.publish(regA).toCompletableFuture();
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5); boolean waiting;
            do { waiting=Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from pg_stat_activity where ?=any(pg_blocking_pids(pid)))",Boolean.class,blocker));
                if(waiting) break; Thread.sleep(20); } while(System.nanoTime()<deadline);
            assertTrue(waiting);
            var terminal=admission.finalizeAttempt(new WorkspaceAdmission.FinalizeAttempt(regB,attempt,claim,WorkspaceAdmission.TerminalKind.UNKNOWN)).toCompletableFuture();
            assertEquals("PENDING",jdbc.queryForObject("select state from workspace_admission_finalization where attempt_id=?",String.class,attempt));
            closing=CompletableFuture.runAsync(context::close);
            closing.get(15,TimeUnit.SECONDS);
            assertEquals("PENDING",jdbc.queryForObject("select state from workspace_admission_finalization where attempt_id=?",String.class,attempt));
            connection.rollback();
            blocked.handle((result,error)->null).get(10,TimeUnit.SECONDS);
            terminal.handle((result,error)->null).get(10,TimeUnit.SECONDS);
        } finally {
            if (context.isActive()) context.close();
            if (closing!=null) closing.handle((value,error)->null).get(15,TimeUnit.SECONDS);
        }
        try(var restored=new SpringApplicationBuilder(BotApplication.class).web(WebApplicationType.NONE).profiles("test")
                .properties("spring.datasource.url="+ds.getJdbcUrl(),"spring.datasource.username="+ds.getUsername(),
                        "spring.datasource.password="+ds.getPassword(),"spring.docker.compose.enabled=false").run()) {
            assertEquals("SETTLED",jdbc.queryForObject("select state from workspace_admission_finalization where attempt_id=?",String.class,attempt));
            assertEquals("TERMINAL",jdbc.queryForObject("select status from workspace_admission_registration where token=?",String.class,regB.token()));
        }
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void shutdownWaitsForAcceptedCompletionCallbackBeforeClosingDatasource(boolean fallback) throws Exception {
        var ds=(HikariDataSource)dataSource;
        var context=new SpringApplicationBuilder(BotApplication.class).web(WebApplicationType.NONE).profiles("test")
                .properties("spring.datasource.url="+ds.getJdbcUrl(),"spring.datasource.username="+ds.getUsername(),
                        "spring.datasource.password="+ds.getPassword(),"spring.docker.compose.enabled=false").run();
        var localDs=(HikariDataSource)context.getBean(DataSource.class);
        var admission=context.getBean(WorkspaceAdmission.class);
        var conversations=context.getBean(ConversationOperations.class);
        String workspace="callback-"+UUID.randomUUID(), actor="callback-"+UUID.randomUUID(), bot="callback-"+UUID.randomUUID(), run="callback-"+UUID.randomUUID();
        jdbc.update("insert into workspace(id,name) values (?,?)",workspace,workspace);
        jdbc.update("insert into workspace_member(workspace_id,user_id,role,active) values (?,?,'MANAGER',true)",workspace,actor);
        jdbc.update("insert into bot(id,name,target) values (?,?,'LOCAL')",bot,bot);
        var conversation=conversations.createConversation(bot,"fixture",actor,workspace);
        var registration=assertInstanceOf(WorkspaceAdmission.RegisterResult.Registered.class,admission.register(new WorkspaceAdmission.Register(workspace,run,actor,conversation.id(),WorkspaceAdmission.Partition.REAL)).toCompletableFuture().join()).registration();
        jdbc.update("insert into chat_run(id,conversation_id,client_request_id,input_text,status) values (?,?,?,'test','PENDING')",run,conversation.id(),UUID.randomUUID().toString());
        if (fallback) {
            var lane=context.getBean(io.github.samzhu.bot.usage.internal.admission.WorkspaceLane.class);
            var executor=(java.util.concurrent.ThreadPoolExecutor)org.springframework.test.util.ReflectionTestUtils.getField(lane,"completions");
            assertNotNull(executor); executor.shutdown(); // Inject rejection; public completion must use its bounded fallback.
        }
        var entered=new java.util.concurrent.CountDownLatch(1); var release=new java.util.concurrent.CountDownLatch(1);
        CompletableFuture<Void> closing=null;
        try (var lock=dataSource.getConnection()) {
            lock.setAutoCommit(false);
            try(var stmt=lock.prepareStatement("select token from workspace_admission_registration where token=? for update")) { stmt.setString(1,registration.token()); stmt.executeQuery().close(); }
            var callback=admission.publish(registration).thenApply(value -> {
                entered.countDown();
                try { if(!release.await(8,TimeUnit.SECONDS)) throw new IllegalStateException("callback timeout");
                    try(var c=localDs.getConnection(); var stmt=c.createStatement(); var rs=stmt.executeQuery("select 1")) { assertTrue(rs.next()); }
                } catch(Exception e) { throw new RuntimeException(e); }
                return value;
            }).toCompletableFuture();
            lock.rollback();
            assertTrue(entered.await(5,TimeUnit.SECONDS));
            closing=CompletableFuture.runAsync(context::close);
            var closeRef=closing;
            assertThrows(java.util.concurrent.TimeoutException.class,()->closeRef.get(500,TimeUnit.MILLISECONDS),"context must retain datasource while accepted callback is active");
            assertFalse(localDs.isClosed());
            release.countDown();
            assertInstanceOf(WorkspaceAdmission.PublicationResult.Ready.class,callback.get(5,TimeUnit.SECONDS));
            closing.get(5,TimeUnit.SECONDS);
        } finally {
            release.countDown();
            if(context.isActive()) context.close();
            if(closing!=null) closing.handle((v,e)->null).get(10,TimeUnit.SECONDS);
        }
    }

}
