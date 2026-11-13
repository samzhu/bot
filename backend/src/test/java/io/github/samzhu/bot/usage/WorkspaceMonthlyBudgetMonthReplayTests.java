package io.github.samzhu.bot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.samzhu.bot.conversation.ConversationOperations;
import io.github.samzhu.bot.usage.WorkspaceAdmission;
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
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({TestcontainersConfiguration.class,WorkspaceStartupMonthBoundaryTests.ClockConfiguration.class})
@ActiveProfiles("test")
class WorkspaceMonthlyBudgetMonthReplayTests {
    @LocalServerPort int port;
    @Autowired WorkspaceStartupMonthBoundaryTests.MutableClock clock;
    @Autowired WorkspaceAdmission admission;
    @Autowired ConversationOperations conversations;
    @Autowired JdbcTemplate jdbc;
    final ObjectMapper json=new ObjectMapper();
    final HttpClient http=HttpClient.newHttpClient();

    private JsonNode graph(String query) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/graphql"))
                .header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("query",query)))).build();
        return json.readTree(http.send(request,HttpResponse.BodyHandlers.ofString()).body());
    }
    private JsonNode configure(String workspace,String key,String version,String cap) throws Exception {
        return graph("mutation { configureWorkspaceMonthlyBudget(input:{workspaceId:\""+workspace+"\",simulation:false,enabled:true,capUsd:\""+cap+"\",expectedConfigVersion:\""+version+"\",idempotencyKey:\""+key+"\"}) { outcome budget { period capUsd configVersion } } }");
    }

    @Test void oldCommandReplayAfterMonthAndLaterConfigReturnsCurrentWithoutNewEffect() throws Exception {
        String workspace=graph("{ personalWorkspace { id } }").path("data").path("personalWorkspace").path("id").asText();
        String bot="month-replay-bot-"+UUID.randomUUID(),run="month-replay-run-"+UUID.randomUUID();
        jdbc.update("insert into bot(id,name,target) values (?,?,'LOCAL')",bot,bot);
        var conversation=conversations.createConversation(bot,"fixture","dev-user",workspace);
        var registration=assertInstanceOf(WorkspaceAdmission.RegisterResult.Registered.class,admission.register(
                new WorkspaceAdmission.Register(workspace,run,"dev-user",conversation.id(),WorkspaceAdmission.Partition.REAL)).toCompletableFuture().join()).registration();
        jdbc.update("insert into chat_run(id,conversation_id,client_request_id,input_text,status) values (?,?,?,'test','PENDING')",run,conversation.id(),UUID.randomUUID().toString());
        assertInstanceOf(WorkspaceAdmission.PublicationResult.Ready.class,admission.publish(registration).toCompletableFuture().join());
        String first="month-zero-"+UUID.randomUUID();
        assertFalse(configure(workspace,first,"0","0").has("errors"));
        assertFalse(configure(workspace,"later-"+UUID.randomUUID(),"1","8").has("errors"));
        assertEquals(1,jdbc.queryForObject("select count(*) from workspace_admission_effect_outbox where registration_token=?",Integer.class,registration.token()));
        clock.instant.set(Instant.parse("2026-11-30T16:00:00Z"));
        var result=configure(workspace,first,"0","0.00");
        assertFalse(result.has("errors"),result.toString());
        var replay=result.path("data").path("configureWorkspaceMonthlyBudget");
        assertEquals("REPLAYED",replay.path("outcome").asText());
        assertEquals("2026-12",replay.path("budget").path("period").asText());
        assertEquals(0,new java.math.BigDecimal("8").compareTo(new java.math.BigDecimal(replay.path("budget").path("capUsd").asText())));
        assertEquals("2",replay.path("budget").path("configVersion").asText());
        assertEquals(1,jdbc.queryForObject("select count(*) from workspace_admission_effect_outbox where registration_token=?",Integer.class,registration.token()));
    }
}
