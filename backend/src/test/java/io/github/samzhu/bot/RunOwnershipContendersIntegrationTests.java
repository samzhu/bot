package io.github.samzhu.bot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@UseTestFakes
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class RunOwnershipContendersIntegrationTests {
    @Autowired JdbcTemplate jdbc;
    @Autowired io.github.samzhu.bot.usage.UsageLedger ledger;
    @Autowired io.github.samzhu.bot.usage.ConversationTitleAdmission titleAdmission;
    @LocalServerPort int port;
    private final ObjectMapper json=new ObjectMapper();
    private final HttpClient http=HttpClient.newHttpClient();

    private ConfigurableApplicationContext restart() {
        var ds=(HikariDataSource)jdbc.getDataSource();
        return new SpringApplicationBuilder(BotApplication.class,TestChatModelConfiguration.class,TestUsageCaptureConfiguration.class)
                .web(WebApplicationType.NONE).profiles("test")
                .properties("spring.datasource.url="+ds.getJdbcUrl(),"spring.datasource.username="+ds.getUsername(),
                        "spring.datasource.password="+ds.getPassword(),"spring.docker.compose.enabled=false",
                        "bot.execution.startup-coordinator.budget=3s","bot.pricing.reconcile.initial-delay-ms=3600000")
                .run();
    }

    @Test void expiredTitleClaimCannotAdmitNewAttempt() throws Exception {
        jdbc.update("insert into bot(id,name,system_prompt) values ('bot-general','General','synthetic') on conflict do nothing");
        var response=http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/graphql"))
                .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(
                        Map.of("query","mutation { createConversation(botId: \"bot-general\") { id } }")))).build(),HttpResponse.BodyHandlers.ofString());
        String conversation=json.readTree(response.body()).path("data").path("createConversation").path("id").asText();
        assertFalse(conversation.isBlank(),response.body());
        jdbc.update("update conversation_title_work set state='CLAIMED',claimed_until=clock_timestamp()-interval '1 second' where conversation_id=?",conversation);
        assertEquals(io.github.samzhu.bot.usage.ConversationTitleAdmission.Decision.DENIED,titleAdmission.allow(conversation));
        assertEquals(0,jdbc.queryForObject("select count(*) from provider_attempt where title_conversation_id=?",Integer.class,conversation));
        assertEquals(0,jdbc.queryForObject("select count(*) from workspace_title_reservation where conversation_id=?",Integer.class,conversation));
    }

    @Test void expiredTitleReservationBecomesUnknownWhileLiveTitleIsPreserved() throws Exception {
        jdbc.update("insert into bot(id,name,system_prompt) values ('bot-general','General','synthetic') on conflict do nothing");
        String[] conversations=new String[3], attempts={UUID.randomUUID().toString(),UUID.randomUUID().toString(),UUID.randomUUID().toString()};
        String catalog=UUID.randomUUID().toString();
        jdbc.update("insert into pricing_catalog_revision(id,source_reference,source_revision,content_hash) values (?,'synthetic',?,'synthetic')",catalog,catalog);
        try {
            for (int i=0;i<3;i++) {
                var response=http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/graphql"))
                    .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(
                        Map.of("query","mutation { createConversation(botId: \"bot-general\") { id } }")))).build(),HttpResponse.BodyHandlers.ofString());
                String c=json.readTree(response.body()).path("data").path("createConversation").path("id").asText();
                assertFalse(c.isBlank(),response.body()); conversations[i]=c;
                String workspace=jdbc.queryForObject("select workspace_id from conversation where id=?",String.class,c);
                jdbc.update("update conversation_title_work set state='CLAIMED',claimed_until=clock_timestamp()+(? * interval '1 second') where conversation_id=?",i==1?600:-1,c);
                jdbc.update("""
                    insert into provider_attempt(id,workspace_id,actor_id,provider,endpoint,model,channel,adapter_version,simulation,
                      attempt_coverage,prepared_at,started_at,execution_outcome,usage_presence,capture_completeness,projection_state,purpose,title_conversation_id)
                    values (?,?,'dev-user','FAKE','test','fake','FAKE','1',true,'VERIFIED',now(),now(),'IN_PROGRESS','PENDING','UNKNOWN','MISSING','CONVERSATION_TITLE',?)
                    """,attempts[i],workspace,c);
                jdbc.update("""
                    insert into workspace_title_reservation(conversation_id,workspace_id,actor_id,partition,reserved_at,month_start,amount_usd,catalog_revision_id,attempt_id,state)
                    values (?,?,'dev-user','SIMULATION',now(),date_trunc('month',clock_timestamp() at time zone 'Asia/Taipei')::date,1,?,?,'HELD')
                    """,c,workspace,catalog,attempts[i]);
                if (i==2) jdbc.update("update provider_attempt set execution_outcome='SUCCEEDED',projection_version=7,projection_state='UNKNOWN' where id=?",attempts[i]);
            }
            try (var second=restart()) {
                assertEquals("UNKNOWN",jdbc.queryForObject("select execution_outcome from provider_attempt where id=?",String.class,attempts[0]));
                assertEquals("UNKNOWN",jdbc.queryForObject("select state from workspace_title_reservation where conversation_id=?",String.class,conversations[0]));
                assertEquals("IN_PROGRESS",jdbc.queryForObject("select execution_outcome from provider_attempt where id=?",String.class,attempts[1]));
                assertEquals("HELD",jdbc.queryForObject("select state from workspace_title_reservation where conversation_id=?",String.class,conversations[1]));
                assertEquals("SUCCEEDED",jdbc.queryForObject("select execution_outcome from provider_attempt where id=?",String.class,attempts[2]));
                assertEquals(7L,jdbc.queryForObject("select projection_version from provider_attempt where id=?",Long.class,attempts[2]));
                assertEquals("HELD",jdbc.queryForObject("select state from workspace_title_reservation where conversation_id=?",String.class,conversations[2]));
                var late=new io.github.samzhu.bot.usage.UsageLedger.Snapshot("late",1,"FINAL","FAKE","1","1","1","COMPLETE","{}");
                assertThrows(RuntimeException.class,()->ledger.append(attempts[0],late));
                assertEquals(0,jdbc.queryForObject("select count(*) from usage_snapshot where attempt_id=?",Integer.class,attempts[0]));
            }
        } finally {
            for (String c:conversations) if (c!=null) jdbc.update("delete from workspace_title_reservation where conversation_id=?",c);
            for (String id:attempts) jdbc.update("delete from provider_attempt where id=?",id);
            jdbc.update("delete from pricing_catalog_revision where id=?",catalog);
        }
    }

    @Test void twoRecoveryContendersOneTakeoverAndUnknownStartRemainsDiagnosable() throws Exception {
        jdbc.update("insert into bot(id,name,system_prompt) values ('bot-general','General','synthetic') on conflict do nothing");
        var response=http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/graphql"))
                .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(
                        Map.of("query","mutation { createConversation(botId: \"bot-general\") { id } }")))).build(),HttpResponse.BodyHandlers.ofString());
        String conversation=json.readTree(response.body()).path("data").path("createConversation").path("id").asText();
        assertFalse(conversation.isBlank(),response.body());
        String workspace=jdbc.queryForObject("select workspace_id from conversation where id=?",String.class,conversation);
        String run=UUID.randomUUID().toString();
        jdbc.update("insert into chat_run(id,conversation_id,client_request_id,input_text,status) values (?,?,?,'synthetic','RUNNING')",run,conversation,run);
        jdbc.update("insert into run_ownership(run_id,owner_token,generation,lease_until) values (?,?,1,clock_timestamp()+interval '1 hour')",run,UUID.randomUUID().toString());
        try (var rowLock=jdbc.getDataSource().getConnection(); var pool=Executors.newFixedThreadPool(2)) {
            rowLock.setAutoCommit(false);
            try (var stmt=rowLock.prepareStatement("select id from chat_run where id=? for update")) { stmt.setString(1,run); stmt.execute(); }
            jdbc.update("update run_ownership set lease_until=clock_timestamp()-interval '1 second' where run_id=?",run);
            var start=new CountDownLatch(1);
            var first=pool.submit(() -> { start.await(); return restart(); });
            var second=pool.submit(() -> { start.await(); return restart(); });
            start.countDown();
            try {
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
                int waiting=0;
                while (System.nanoTime()<deadline) {
                    waiting=jdbc.queryForObject("""
                            select count(*) from pg_stat_activity where datname=current_database()
                              and wait_event_type='Lock' and lower(query) like '%from chat_run%' and lower(query) like '%for update%'
                            """,Integer.class);
                    if (waiting>=2) break;
                    Thread.sleep(10);
                }
                assertTrue(waiting>=2,"both independent recovery contenders must wait on the Run row; observed="+waiting);
            } finally { rowLock.commit(); }
            try (var a=first.get(30,TimeUnit.SECONDS); var b=second.get(30,TimeUnit.SECONDS)) {
                assertEquals("FAILED",jdbc.queryForObject("select status from chat_run where id=?",String.class,run));
                assertEquals(2L,jdbc.queryForObject("select generation from run_ownership where run_id=?",Long.class,run));
            } finally { pool.shutdownNow(); }
        }
        for (int i=0;i<2;i++) try (var again=restart()) {
            assertEquals(2L,jdbc.queryForObject("select generation from run_ownership where run_id=?",Long.class,run));
            assertEquals("FAILED",jdbc.queryForObject("select status from chat_run where id=?",String.class,run));
            assertEquals(0,jdbc.queryForObject("select count(*) from provider_attempt where run_id=?",Integer.class,run));
        }
        String unknownRun=UUID.randomUUID().toString(), token=UUID.randomUUID().toString();
        jdbc.update("""
                insert into workspace_admission_registration(token,run_id,workspace_id,actor_id,conversation_id,partition,status,
                 pending_id,pending_period_start,pending_period_end) values (?,?,?,'dev-user',?,'SIMULATION','REGISTERED',?,date '2026-11-01',date '2026-12-01')
                """,token,unknownRun,workspace,conversation,UUID.randomUUID().toString());
        try {
            var error=assertThrows(RuntimeException.class, () -> { try(var ignored=restart()) { } });
            assertTrue(error.getMessage().contains("Startup admission recovery did not reach READY"),error.toString());
            assertEquals("REGISTERED",jdbc.queryForObject("select status from workspace_admission_registration where token=?",String.class,token));
            assertNull(jdbc.queryForObject("select start_lease_until from workspace_admission_registration where token=?",java.time.OffsetDateTime.class,token));
            assertEquals(2L,jdbc.queryForObject("select generation from run_ownership where run_id=?",Long.class,run));
        } finally { jdbc.update("delete from workspace_admission_registration where token=?",token); }
    }
}
