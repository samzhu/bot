package io.github.samzhu.bot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

@io.github.samzhu.bot.UseTestFakes
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class WorkspaceMonthlyBudgetGraphQLTests {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired javax.sql.DataSource dataSource;
    @Autowired io.github.samzhu.bot.usage.WorkspaceAdmission admission;
    @Autowired io.github.samzhu.bot.conversation.ConversationOperations conversations;
    @org.springframework.test.context.bean.override.mockito.MockitoBean io.github.samzhu.bot.identity.CurrentUserProvider users;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean io.github.samzhu.bot.usage.WorkspaceMonthlyBudgetOperations budgets;
    private final ObjectMapper json=new ObjectMapper();
    private final HttpClient http=HttpClient.newHttpClient();

    @org.junit.jupiter.api.BeforeEach void identity() { org.mockito.Mockito.when(users.getCurrentUserId()).thenReturn("dev-user"); }

    private JsonNode graph(String query) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/graphql"))
                .header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("query",query)))).build();
        return json.readTree(http.send(request,HttpResponse.BodyHandlers.ofString()).body());
    }

    @Test void untouchedLegacyRowGetsDefaultButSavedNullAndDisabledChoiceStaySaved() throws Exception {
        String workspace=graph("{ personalWorkspace { id } }").path("data").path("personalWorkspace").path("id").asText();
        jdbc.update("insert into workspace_monthly_budget(workspace_id,partition,enabled,cap_usd,config_version) values (?,'REAL',false,null,0)",workspace);
        var initial=graph("{ workspaceMonthlyBudget(workspaceId:\""+workspace+"\",simulation:false) { capUsd enabled configVersion } }");
        assertFalse(initial.has("errors"),initial.toString());
        var first=initial.path("data").path("workspaceMonthlyBudget");
        assertEquals(0,new java.math.BigDecimal("10").compareTo(new java.math.BigDecimal(first.path("capUsd").asText())));
        assertFalse(first.path("enabled").asBoolean());
        assertEquals("0",first.path("configVersion").asText());
        String key="explicit-null-"+java.util.UUID.randomUUID();
        String mutation="mutation { configureWorkspaceMonthlyBudget(input:{workspaceId:\""+workspace+"\",simulation:false,enabled:false,capUsd:null,expectedConfigVersion:\"0\",idempotencyKey:\""+key+"\"}) { outcome budget { capUsd enabled configVersion } } }";
        var saved=graph(mutation); assertFalse(saved.has("errors"),saved.toString());
        assertEquals("APPLIED",saved.path("data").path("configureWorkspaceMonthlyBudget").path("outcome").asText());
        var replay=graph(mutation); assertEquals("REPLAYED",replay.path("data").path("configureWorkspaceMonthlyBudget").path("outcome").asText());
        var after=graph("{ workspaceMonthlyBudget(workspaceId:\""+workspace+"\",simulation:false) { capUsd enabled configVersion } }").path("data").path("workspaceMonthlyBudget");
        assertTrue(after.path("capUsd").isNull()); assertFalse(after.path("enabled").asBoolean()); assertEquals("1",after.path("configVersion").asText());
        assertEquals(1,jdbc.queryForObject("select count(*) from workspace_monthly_budget_command where workspace_id=? and partition='REAL'",Integer.class,workspace));
    }

    @Test void versionZeroWithCommandEvidenceIsNotOverwritten() throws Exception {
        String workspace=graph("{ personalWorkspace { id } }").path("data").path("personalWorkspace").path("id").asText();
        jdbc.update("insert into workspace_monthly_budget(workspace_id,partition,enabled,cap_usd,config_version) values (?,'REAL',false,null,0)",workspace);
        jdbc.update("insert into workspace_monthly_budget_command(workspace_id,partition,actor_id,idempotency_key,expected_config_version,enabled,cap_usd) values (?,'REAL','dev-user','legacy',0,false,null)",workspace);
        var result=graph("{ workspaceMonthlyBudget(workspaceId:\""+workspace+"\",simulation:false) { capUsd enabled configVersion } }");
        assertFalse(result.has("errors"),result.toString());
        assertTrue(result.path("data").path("workspaceMonthlyBudget").path("capUsd").isNull());
    }

    @Test void savedBudgetSurvivesFreshApplicationWithChangedDefault() throws Exception {
        String workspace=graph("{ personalWorkspace { id } }").path("data").path("personalWorkspace").path("id").asText();
        var saved=configure(workspace,"restart-"+java.util.UUID.randomUUID(),"0","7.25");
        assertFalse(saved.has("errors"),saved.toString());
        var ds=(com.zaxxer.hikari.HikariDataSource)dataSource;
        try (var context=new org.springframework.boot.builder.SpringApplicationBuilder(BotApplication.class, io.github.samzhu.bot.TestChatModelConfiguration.class, io.github.samzhu.bot.TestUsageCaptureConfiguration.class)
                .web(org.springframework.boot.WebApplicationType.SERVLET).profiles("test")
                .properties("server.port=0", "spring.datasource.url="+ds.getJdbcUrl(),
                        "spring.datasource.username="+ds.getUsername(), "spring.datasource.password="+ds.getPassword(),
                        "spring.docker.compose.enabled=false")
                .run("--bot.budget.monthly.default-cap-usd=25")) {
            int restartPort=((org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext)context).getWebServer().getPort();
            var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+restartPort+"/graphql"))
                    .header("Content-Type","application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("query", "{ workspaceMonthlyBudget(workspaceId:\""+workspace+"\",simulation:false) { capUsd enabled configVersion } }")))).build();
            var result=json.readTree(http.send(request,HttpResponse.BodyHandlers.ofString()).body());
            assertFalse(result.has("errors"),result.toString());
            var budget=result.path("data").path("workspaceMonthlyBudget");
            assertEquals(0,new java.math.BigDecimal("7.25").compareTo(new java.math.BigDecimal(budget.path("capUsd").asText())));
            assertTrue(budget.path("enabled").asBoolean()); assertEquals("1",budget.path("configVersion").asText());
        }
    }

    private JsonNode configure(String workspace,String key,String version,String cap) throws Exception {
        return configurePartition(workspace,key,version,cap,false);
    }

    private JsonNode configurePartition(String workspace,String key,String version,String cap,boolean simulation) throws Exception {
        return graph("mutation { configureWorkspaceMonthlyBudget(input:{workspaceId:\""+workspace+"\",simulation:"+simulation+",enabled:true,capUsd:\""+cap+"\",expectedConfigVersion:\""+version+"\",idempotencyKey:\""+key+"\"}) { outcome budget { capUsd configVersion } } }");
    }

    @Test void authorizationPrecedesMutationValidationAndMemberCanRead() throws Exception {
        String workspace=graph("{ personalWorkspace { id } }").path("data").path("personalWorkspace").path("id").asText();
        String member="member-"+java.util.UUID.randomUUID(), outsider="outsider-"+java.util.UUID.randomUUID();
        jdbc.update("insert into workspace_member(workspace_id,user_id,role,active) values (?,?,'MEMBER',true)",workspace,member);
        org.mockito.Mockito.when(users.getCurrentUserId()).thenReturn(member);
        var read=graph("{ workspaceMonthlyBudget(workspaceId:\""+workspace+"\",simulation:false) { workspaceId state } }");
        assertFalse(read.has("errors"),read.toString());
        assertEquals("WORKSPACE_MANAGER_REQUIRED",configure(workspace,"key","bad","bad").path("errors").path(0).path("extensions").path("code").asText());
        org.mockito.Mockito.when(users.getCurrentUserId()).thenReturn(outsider);
        assertEquals("WORKSPACE_ACCESS_DENIED",configure(workspace,"key","bad","bad").path("errors").path(0).path("extensions").path("code").asText());
        var unknown=configure("missing-"+java.util.UUID.randomUUID(),"key","bad","bad");
        assertEquals("WORKSPACE_ACCESS_DENIED",unknown.path("errors").path(0).path("extensions").path("code").asText());
    }

    @Test void commandReplaySurvivesContextRestartAndReturnsCurrentBudget() throws Exception {
        String workspace=graph("{ personalWorkspace { id } }").path("data").path("personalWorkspace").path("id").asText();
        String key="restart-key-"+java.util.UUID.randomUUID();
        assertFalse(configure(workspace,key,"0","5.0").has("errors"));
        assertFalse(configure(workspace,"later-"+java.util.UUID.randomUUID(),"1","8").has("errors"));
        var ds=(com.zaxxer.hikari.HikariDataSource)dataSource;
        try(var restarted=new org.springframework.boot.builder.SpringApplicationBuilder(BotApplication.class, io.github.samzhu.bot.TestChatModelConfiguration.class, io.github.samzhu.bot.TestUsageCaptureConfiguration.class)
                .web(org.springframework.boot.WebApplicationType.NONE).profiles("test")
                .properties("spring.datasource.url="+ds.getJdbcUrl(),"spring.datasource.username="+ds.getUsername(),
                        "spring.datasource.password="+ds.getPassword(),"spring.docker.compose.enabled=false").run()) {
            var budgets=restarted.getBean(io.github.samzhu.bot.usage.WorkspaceMonthlyBudgetOperations.class);
            var scope=new io.github.samzhu.bot.usage.WorkspaceMonthlyBudgetOperations.Scope(workspace,
                    io.github.samzhu.bot.usage.WorkspaceAdmission.Partition.REAL,java.time.YearMonth.now(java.time.ZoneId.of("Asia/Taipei")));
            var replay=budgets.configureIdempotent(new io.github.samzhu.bot.usage.WorkspaceMonthlyBudgetOperations.IdempotentConfigureCommand(
                    scope,"dev-user",true,"5.00","0",key)).toCompletableFuture().join();
            assertEquals(io.github.samzhu.bot.usage.WorkspaceMonthlyBudgetOperations.ConfigureOutcome.REPLAYED,replay.outcome());
            assertEquals(0,new java.math.BigDecimal("8").compareTo(replay.budget().capUsd()));
            assertEquals(2,replay.budget().configVersion());
        }
    }

    @Test void concurrentIdenticalZeroCapCommandsCreateOneEffect() throws Exception {
        String workspace=graph("{ personalWorkspace { id } }").path("data").path("personalWorkspace").path("id").asText();
        String bot="zero-bot-"+java.util.UUID.randomUUID(),run="zero-run-"+java.util.UUID.randomUUID();
        jdbc.update("insert into bot(id,name,target) values (?,?,'LOCAL')",bot,bot);
        var conversation=conversations.createConversation(bot,"fixture","dev-user",workspace);
        var registration=assertInstanceOf(io.github.samzhu.bot.usage.WorkspaceAdmission.RegisterResult.Registered.class,admission.register(
                new io.github.samzhu.bot.usage.WorkspaceAdmission.Register(workspace,run,"dev-user",conversation.id(),
                        io.github.samzhu.bot.usage.WorkspaceAdmission.Partition.REAL)).toCompletableFuture().join()).registration();
        jdbc.update("insert into chat_run(id,conversation_id,client_request_id,input_text,status) values (?,?,?,'test','PENDING')",run,conversation.id(),java.util.UUID.randomUUID().toString());
        assertInstanceOf(io.github.samzhu.bot.usage.WorkspaceAdmission.PublicationResult.Ready.class,admission.publish(registration).toCompletableFuture().join());
        String key="zero-key-"+java.util.UUID.randomUUID();
        var first=java.util.concurrent.CompletableFuture.supplyAsync(()->{ try { return configure(workspace,key,"0","0"); } catch(Exception e) { throw new RuntimeException(e); } });
        var second=java.util.concurrent.CompletableFuture.supplyAsync(()->{ try { return configure(workspace,key,"0","0.00"); } catch(Exception e) { throw new RuntimeException(e); } });
        var a=first.get(10,java.util.concurrent.TimeUnit.SECONDS); var b=second.get(10,java.util.concurrent.TimeUnit.SECONDS);
        assertFalse(a.has("errors"),a.toString()); assertFalse(b.has("errors"),b.toString());
        assertEquals(java.util.Set.of("APPLIED","REPLAYED"),java.util.Set.of(a.path("data").path("configureWorkspaceMonthlyBudget").path("outcome").asText(),
                b.path("data").path("configureWorkspaceMonthlyBudget").path("outcome").asText()));
        assertEquals(1L,jdbc.queryForObject("select config_version from workspace_monthly_budget where workspace_id=? and partition='REAL'",Long.class,workspace));
        assertEquals(1,jdbc.queryForObject("select count(*) from workspace_admission_effect_outbox where registration_token=?",Integer.class,registration.token()));
        assertEquals(1,jdbc.queryForObject("select count(*) from workspace_monthly_budget_command where workspace_id=? and partition='REAL'",Integer.class,workspace));
    }

    @Test void failedCommandReceiptRollsBackConfigurationAndCancellationEffect() throws Exception {
        String workspace=graph("{ personalWorkspace { id } }").path("data").path("personalWorkspace").path("id").asText();
        String bot="rollback-bot-"+java.util.UUID.randomUUID(),run="rollback-run-"+java.util.UUID.randomUUID();
        jdbc.update("insert into bot(id,name,target) values (?,?,'LOCAL')",bot,bot);
        var conversation=conversations.createConversation(bot,"fixture","dev-user",workspace);
        var registration=assertInstanceOf(io.github.samzhu.bot.usage.WorkspaceAdmission.RegisterResult.Registered.class,admission.register(
                new io.github.samzhu.bot.usage.WorkspaceAdmission.Register(workspace,run,"dev-user",conversation.id(),
                        io.github.samzhu.bot.usage.WorkspaceAdmission.Partition.REAL)).toCompletableFuture().join()).registration();
        jdbc.update("insert into chat_run(id,conversation_id,client_request_id,input_text,status) values (?,?,?,'test','PENDING')",run,conversation.id(),java.util.UUID.randomUUID().toString());
        assertInstanceOf(io.github.samzhu.bot.usage.WorkspaceAdmission.PublicationResult.Ready.class,admission.publish(registration).toCompletableFuture().join());
        String key="rollback-key-"+java.util.UUID.randomUUID(),function="fail_budget_"+java.util.UUID.randomUUID().toString().replace("-","");
        jdbc.execute("create function "+function+"() returns trigger language plpgsql as $$ begin if new.idempotency_key='"+key+"' then raise exception 'injected command receipt failure'; end if; return new; end $$");
        jdbc.execute("create trigger "+function+" before insert on workspace_monthly_budget_command for each row execute function "+function+"()");
        try {
            assertTrue(configure(workspace,key,"0","0").has("errors"));
            assertFalse(jdbc.queryForObject("select enabled from workspace_monthly_budget where workspace_id=? and partition='REAL'",Boolean.class,workspace));
            assertEquals(0L,jdbc.queryForObject("select config_version from workspace_monthly_budget where workspace_id=? and partition='REAL'",Long.class,workspace));
            assertEquals(0,jdbc.queryForObject("select count(*) from workspace_admission_effect_outbox where registration_token=?",Integer.class,registration.token()));
            assertEquals("PUBLISHED",jdbc.queryForObject("select status from workspace_admission_registration where token=?",String.class,registration.token()));
        } finally {
            jdbc.execute("drop trigger if exists "+function+" on workspace_monthly_budget_command");
            jdbc.execute("drop function if exists "+function+"()");
        }
    }

    @Test void revokedManagerCannotReplayOrReadPreviousResult() throws Exception {
        String workspace=graph("{ personalWorkspace { id } }").path("data").path("personalWorkspace").path("id").asText();
        String key="revoked-"+java.util.UUID.randomUUID();
        assertFalse(configure(workspace,key,"0","5").has("errors"));
        jdbc.update("update workspace_member set active=false where workspace_id=? and user_id='dev-user'",workspace);
        var replay=configure(workspace,key,"0","5.00");
        assertEquals("WORKSPACE_ACCESS_DENIED",replay.path("errors").path(0).path("extensions").path("code").asText());
        assertTrue(replay.path("data").isNull());
        assertEquals(1L,jdbc.queryForObject("select config_version from workspace_monthly_budget where workspace_id=? and partition='REAL'",Long.class,workspace));
        assertEquals(1,jdbc.queryForObject("select count(*) from workspace_monthly_budget_command where workspace_id=?",Integer.class,workspace));
    }

    @Test void lostResponseAfterCommitCanReplayWithoutRepeatingEffect() throws Exception {
        String workspace=graph("{ personalWorkspace { id } }").path("data").path("personalWorkspace").path("id").asText();
        String bot="lost-budget-bot-"+java.util.UUID.randomUUID(),run="lost-budget-run-"+java.util.UUID.randomUUID();
        jdbc.update("insert into bot(id,name,target) values (?,?,'LOCAL')",bot,bot);
        var conversation=conversations.createConversation(bot,"fixture","dev-user",workspace);
        var registration=assertInstanceOf(io.github.samzhu.bot.usage.WorkspaceAdmission.RegisterResult.Registered.class,admission.register(
                new io.github.samzhu.bot.usage.WorkspaceAdmission.Register(workspace,run,"dev-user",conversation.id(),
                        io.github.samzhu.bot.usage.WorkspaceAdmission.Partition.REAL)).toCompletableFuture().join()).registration();
        jdbc.update("insert into chat_run(id,conversation_id,client_request_id,input_text,status) values (?,?,?,'test','PENDING')",run,conversation.id(),java.util.UUID.randomUUID().toString());
        assertInstanceOf(io.github.samzhu.bot.usage.WorkspaceAdmission.PublicationResult.Ready.class,admission.publish(registration).toCompletableFuture().join());
        String key="lost-response-"+java.util.UUID.randomUUID();
        var inject=new java.util.concurrent.atomic.AtomicBoolean(true);
        org.mockito.Mockito.doAnswer(call -> {
            @SuppressWarnings("unchecked")
            var real=(java.util.concurrent.CompletionStage<io.github.samzhu.bot.usage.WorkspaceMonthlyBudgetOperations.ConfigureResult>)call.callRealMethod();
            return real.thenCompose(result -> inject.compareAndSet(true,false)
                    ? java.util.concurrent.CompletableFuture.failedFuture(new IllegalStateException("injected lost response after commit"))
                    : java.util.concurrent.CompletableFuture.completedFuture(result));
        }).when(budgets).configureIdempotent(org.mockito.ArgumentMatchers.argThat(command -> command!=null && key.equals(command.idempotencyKey())));
        assertTrue(configure(workspace,key,"0","0").has("errors"));
        assertEquals(1L,jdbc.queryForObject("select config_version from workspace_monthly_budget where workspace_id=? and partition='REAL'",Long.class,workspace));
        assertEquals(1,jdbc.queryForObject("select count(*) from workspace_monthly_budget_command where workspace_id=?",Integer.class,workspace));
        assertEquals(1,jdbc.queryForObject("select count(*) from workspace_admission_effect_outbox where registration_token=?",Integer.class,registration.token()));
        var replay=configure(workspace,key,"0","0.00");
        assertFalse(replay.has("errors"),replay.toString());
        assertEquals("REPLAYED",replay.path("data").path("configureWorkspaceMonthlyBudget").path("outcome").asText());
        assertEquals(1L,jdbc.queryForObject("select config_version from workspace_monthly_budget where workspace_id=? and partition='REAL'",Long.class,workspace));
        assertEquals(1,jdbc.queryForObject("select count(*) from workspace_admission_effect_outbox where registration_token=?",Integer.class,registration.token()));
    }
}
