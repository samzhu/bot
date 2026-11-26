package io.github.samzhu.bot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.samzhu.bot.execution.ExecutionOperations;
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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@io.github.samzhu.bot.UseTestFakes
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class AgUiDenialIntegrationTests {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
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
    @Test void unavailableCodesUseOnlySafeAllowlist() throws Exception {
        String workspace=workspace(), conversation=conversation(workspace);
        String marker="synthetic-private-detail";
        try {
            for (var entry:Map.of("RECOVERY_REQUIRED","RECOVERY_REQUIRED","OVERLOADED","OVERLOADED", "ADMISSION_UNAVAILABLE","ADMISSION_UNAVAILABLE",marker,"SERVICE_UNAVAILABLE").entrySet()) {
                String run=UUID.randomUUID().toString();
                doReturn(new ExecutionOperations.StartRunOutcome(ExecutionOperations.StartRunOutcome.Type.SERVICE_UNAVAILABLE,null,null,entry.getKey()))
                        .when(execution).startAgUiRun(eq(conversation),eq(run),anyString(),eq("dev-user"),any());
                var response=start(conversation,run);
                assertFalse(response.body().contains(marker));
                assertDenial(response,entry.getValue(),conversation,run);
            }
        } finally { reset(execution); }
    }
}
