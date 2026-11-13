package io.github.samzhu.bot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.samzhu.bot.execution.ExecutionOperations;
import io.github.samzhu.bot.usage.UsageLedger;
import io.github.samzhu.bot.usage.WorkspaceAdmission;
import io.github.samzhu.bot.usage.WorkspaceMonthlyBudgetOperations;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class AgUiDenialIntegrationTests {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired WorkspaceAdmission admission;
    @Autowired WorkspaceMonthlyBudgetOperations budgets;
    @Autowired UsageLedger ledger;
    @Autowired Clock usageClock;
    @MockitoSpyBean ExecutionOperations execution;
    @TestBean(name="mockChatModel",methodName="model",enforceOverride=true) ChatModel chatModel;
    static ControlledChatModel model() { return ControlledChatModel.getInstance(); }
    final ObjectMapper json=new ObjectMapper();
    final HttpClient http=HttpClient.newHttpClient();
    @BeforeEach void setup() {
        model().reset();
        jdbc.update("insert into bot(id,name,system_prompt,created_at) values ('bot-general','General','test',current_timestamp) on conflict(id) do nothing");
    }
    String workspace() {
        String id="denial-fixture-"+UUID.randomUUID();
        jdbc.update("insert into workspace(id,name) values (?,?)",id,"synthetic denial fixture");
        jdbc.update("insert into workspace_member(workspace_id,user_id,role,active) values (?,'dev-user','MANAGER',true)",id);
        return id;
    }
    HttpResponse<String> post(String path,Object body,String accept) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path))
                .header("Content-Type","application/json").header("Accept",accept)
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
    }
    String conversation(String workspace) throws Exception {
        var response=post("/graphql",Map.of("query","mutation { createConversation(botId: \"bot-general\", title: \"denial\", workspaceId: \""+workspace+"\") { id } }"),"application/json");
        String id=json.readTree(response.body()).path("data").path("createConversation").path("id").asText();
        assertFalse(id.isBlank(),response.body()); return id;
    }
    HttpResponse<String> start(String conversation,String run) throws Exception {
        var body=Map.of("threadId",conversation,"runId",run,"state",Map.of(),"messages",List.of(Map.of("id",UUID.randomUUID().toString(),"role","user","content","synthetic denial")),"tools",List.of(),"context",List.of(),"forwardedProps",Map.of());
        return post("/api/ag-ui",body,"text/event-stream");
    }
    WorkspaceMonthlyBudgetOperations.Scope scope(String workspace) {
        return new WorkspaceMonthlyBudgetOperations.Scope(workspace,WorkspaceAdmission.Partition.SIMULATION,
                YearMonth.from(usageClock.instant().atZone(ZoneId.of("Asia/Taipei"))));
    }
    void assertDenial(HttpResponse<String> response,String code,String conversation,String run) throws Exception {
        assertEquals(503,response.statusCode(),response.body());
        assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("application/json"),response.toString());
        assertEquals(0,jdbc.queryForObject("select count(*) from chat_run where id=?",Integer.class,run));
        assertEquals(0,jdbc.queryForObject("select count(*) from workspace_admission_registration where run_id=?",Integer.class,run));
        assertEquals(0,jdbc.queryForObject("select count(*) from ai_session where id=?",Integer.class,conversation));
        assertTrue(model().getRecordedPrompts().isEmpty());
        JsonNode body=json.readTree(response.body());
        assertEquals(code,body.path("code").asText(),response.body());
        assertEquals(1,body.size(),"no raw error details may be returned");
    }
    @Test void capZeroReturnsBudgetCodeForSseAcceptWithoutRunOrProvider() throws Exception {
        String workspace=workspace(), conversation=conversation(workspace), run=UUID.randomUUID().toString();
        budgets.configure(new WorkspaceMonthlyBudgetOperations.ConfigureCommand(scope(workspace),"dev-user",true,BigDecimal.ZERO,0)).toCompletableFuture().get(10,TimeUnit.SECONDS);
        assertDenial(start(conversation,run),"MONTH_CAP",conversation,run);
    }
    @Test void unknownOnlyReturnsBudgetCodeForSseAcceptWithoutRunOrProvider() throws Exception {
        String workspace=workspace(), sourceConversation=conversation(workspace), deniedConversation=conversation(workspace);
        String sourceRun=UUID.randomUUID().toString(), deniedRun=UUID.randomUUID().toString();
        var registration=assertInstanceOf(WorkspaceAdmission.RegisterResult.Registered.class,admission.register(
                new WorkspaceAdmission.Register(workspace,sourceRun,"dev-user",sourceConversation,WorkspaceAdmission.Partition.SIMULATION)).toCompletableFuture().get(10,TimeUnit.SECONDS)).registration();
        jdbc.update("insert into chat_run(id,conversation_id,client_request_id,input_text,status) values (?,?,?,'synthetic','PENDING')",sourceRun,sourceConversation,UUID.randomUUID().toString());
        assertInstanceOf(WorkspaceAdmission.PublicationResult.Ready.class,admission.publish(registration).toCompletableFuture().get(10,TimeUnit.SECONDS));
        String attempt=ledger.begin(new UsageLedger.Dispatch(sourceRun,workspace,"dev-user","FAKE","test","model","FAKE","1",true,"UNKNOWN"));
        var claim=assertInstanceOf(WorkspaceAdmission.Decision.Allowed.class,admission.dispatch(new WorkspaceAdmission.Dispatch(registration,attempt)).toCompletableFuture().get(10,TimeUnit.SECONDS)).claim();
        assertEquals(WorkspaceAdmission.FinalizationResult.SETTLED,admission.finalizeAttempt(new WorkspaceAdmission.FinalizeAttempt(registration,attempt,claim,WorkspaceAdmission.TerminalKind.UNKNOWN)).toCompletableFuture().get(10,TimeUnit.SECONDS));
        jdbc.update("update chat_run set status='FAILED' where id=?",sourceRun);
        var configured=budgets.configure(new WorkspaceMonthlyBudgetOperations.ConfigureCommand(scope(workspace),"dev-user",true,new BigDecimal("100"),0)).toCompletableFuture().get(10,TimeUnit.SECONDS);
        assertTrue(configured.terminalEffectiveUnknown());
        assertTrue(configured.knownSubtotalUsd()==null || configured.knownSubtotalUsd().signum()==0);
        assertDenial(start(deniedConversation,deniedRun),"MONTH_UNKNOWN",deniedConversation,deniedRun);
    }
    @Test void disconnectedGkeKeepsItsOwnCodeForSseAccept() throws Exception {
        String workspace=workspace(), bot="denial-gke-"+UUID.randomUUID(), conversation="denial-conv-"+UUID.randomUUID(), run=UUID.randomUUID().toString();
        jdbc.update("insert into bot(id,name,target,description,instructions,owner_id,created_at,updated_at) values (?,'GKE','GKE_REMOTE','synthetic','synthetic','dev-user',current_timestamp,current_timestamp)",bot);
        jdbc.update("insert into conversation(id,bot_id,title,user_id,pinned,created_at,updated_at,workspace_id) values (?,?,'synthetic','dev-user',false,current_timestamp,current_timestamp,?)",conversation,bot,workspace);
        assertDenial(start(conversation,run),"GKE_AGENT_NOT_CONNECTED",conversation,run);
    }
    @Test void unavailableCodesUseOnlySafeAllowlist() throws Exception {
        String workspace=workspace(), conversation=conversation(workspace);
        String marker="synthetic-private-detail";
        try {
            for (var entry:Map.of("RECOVERY_REQUIRED","RECOVERY_REQUIRED","OVERLOADED","OVERLOADED", "ADMISSION_UNAVAILABLE","ADMISSION_UNAVAILABLE",marker,"SERVICE_UNAVAILABLE").entrySet()) {
                String run=UUID.randomUUID().toString();
                doReturn(new ExecutionOperations.StartRunOutcome(ExecutionOperations.StartRunOutcome.Type.SERVICE_UNAVAILABLE,null,null,entry.getKey()))
                        .when(execution).startAgUiRun(eq(conversation),eq(run),anyString(),eq("dev-user"));
                var response=start(conversation,run);
                assertFalse(response.body().contains(marker));
                assertDenial(response,entry.getValue(),conversation,run);
            }
        } finally { reset(execution); }
    }
}
