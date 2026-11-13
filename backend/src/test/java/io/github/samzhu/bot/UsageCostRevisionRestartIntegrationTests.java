package io.github.samzhu.bot;

import static org.junit.jupiter.api.Assertions.*;

import com.zaxxer.hikari.HikariDataSource;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.context.ConfigurableApplicationContext;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class UsageCostRevisionRestartIntegrationTests {
    @Autowired JdbcTemplate jdbc;
    @Autowired javax.sql.DataSource dataSource;

    @Test
    void newApplicationContextKeepsManualCurrentAndCheckpointAndHistoryPriceEvidence() {
        String suffix=UUID.randomUUID().toString().substring(0,8), workspace="restart-w-"+suffix, attempt="restart-a-"+suffix;
        String catalog="restart-c-"+suffix, entry="restart-e-"+suffix, price="restart-p-"+suffix, estimate="restart-est-"+suffix, revision="restart-rev-"+suffix;
        String sourceSnapshot="restart-s8-"+suffix, effectiveSnapshot="restart-s9-"+suffix;
        String sourceRaw="{\"fixture\":\"manual-source-v8\",\"count\":8}", effectiveRaw="{\"fixture\":\"late-effective-v9\",\"count\":9}";
        jdbc.update("insert into workspace(id,name) values (?,?)",workspace,"restart fixture");
        // The attempt's immutable dispatch marker has an FK to the catalog revision.
        jdbc.update("insert into pricing_catalog_revision(id,source_reference,source_revision,content_hash) values (?,'restart-test',?,'hash')",catalog,"restart-rev-"+suffix);
        jdbc.update("insert into provider_attempt(id,workspace_id,provider,endpoint,model,channel,adapter_version,simulation,attempt_coverage,prepared_at,started_at,execution_outcome,usage_presence,capture_completeness,projection_state,projection_version,catalog_marker,catalog_revision_id) values (?,?, 'FAKE','local','restart-model','FAKE','1',true,'VERIFIED',now(),now(),'UNKNOWN','UNKNOWN','UNKNOWN','UNKNOWN',9,'CAPTURED',?)",attempt,workspace,catalog);
        jdbc.update("insert into usage_snapshot(id,attempt_id,event_id,payload_hash,ordinal,semantic,source_layer,schema_version,adapter_version,capture_version,capture_completeness,raw_state,raw_usage,captured_at) values (?,?,?,'restart-hash-v8',8,'FINAL','FAKE_RAW','restart-fixture-v1','1','1','COMPLETE','JSONB',?::jsonb,now())",sourceSnapshot,attempt,"restart-event-v8-"+suffix,sourceRaw);
        jdbc.update("insert into usage_snapshot(id,attempt_id,event_id,payload_hash,ordinal,semantic,source_layer,schema_version,adapter_version,capture_version,capture_completeness,raw_state,raw_usage,captured_at) values (?,?,?,'restart-hash-v9',9,'FINAL','FAKE_RAW','restart-fixture-v1','1','1','COMPLETE','JSONB',?::jsonb,now())",effectiveSnapshot,attempt,"restart-event-v9-"+suffix,effectiveRaw);
        jdbc.update("update provider_attempt set projection_state='VALID',usage_presence='PRESENT',capture_completeness='COMPLETE',projection_snapshot_id=? where id=?",effectiveSnapshot,attempt);
        jdbc.update("insert into pricing_catalog_entry(id,revision_id,provider,endpoint,model,channel,price_revision,effective_from,effective_until,rates) values (?,?,'FAKE','local','restart-model','FAKE','p1',now()-interval '1 day',now()+interval '1 day','{}'::jsonb)",entry,catalog);
        jdbc.update("insert into usage_price_snapshot(id,attempt_id,selection_state,provider,endpoint,model,channel,price_revision,rates,catalog_revision_id,catalog_entry_id,source_reference,effective_from,effective_until,origin) values (?,?,'MATCHED','FAKE','local','restart-model','FAKE','p1','{}'::jsonb,?,?, 'restart-source',now()-interval '1 day',now()+interval '1 day','AUTO_DISPATCH')",price,attempt,catalog,entry);
        // The durable candidate predates the source advance; recovery must process it without replacing manual current.
        jdbc.update("insert into usage_cost_estimate(id,attempt_id,source_version,source_snapshot_id,price_snapshot_id,calculator_version,status,known_subtotal,complete_amount,details,payload_hash,publication_kind) values (?,?,8,?,?, 'restart-test','UNKNOWN',null,null,'{\"reasons\":[\"SOURCE_VERSION_STALE\"],\"lines\":[]}'::jsonb,'hash','AUDITED')",estimate,attempt,sourceSnapshot,price);
        jdbc.update("insert into usage_cost_revision_audit(id,attempt_id,estimate_id,kind,idempotency_key,command_payload,actor_id,recorded_at,source_type,evidence_reference,reason) values (?,?,?,'MANUAL_ESTIMATE','restart-key','{}'::jsonb,'restart-manager',now(),'MANUAL_ESTIMATE','evidence','restart')",revision,attempt,estimate);
        jdbc.update("insert into usage_cost_current(attempt_id,estimate_id) values (?,?)",attempt,estimate);
        jdbc.update("insert into usage_cost_manual_source_checkpoint(attempt_id,last_processed_projection_version) values (?,8)",attempt);
        String jdbcUrl=((HikariDataSource)dataSource).getJdbcUrl();
        String username=((HikariDataSource)dataSource).getUsername();
        String password=((HikariDataSource)dataSource).getPassword();
        String beforeCurrent=jdbc.queryForObject("select estimate_id from usage_cost_current where attempt_id=?",String.class,attempt);
        try(ConfigurableApplicationContext restarted=new SpringApplicationBuilder(BotApplication.class)
                .web(org.springframework.boot.WebApplicationType.NONE).profiles("test")
                .properties("spring.datasource.url="+jdbcUrl,"spring.datasource.username="+username,"spring.datasource.password="+password,
                        "spring.docker.compose.enabled=false","bot.pricing.reconcile.initial-delay-ms=3600000",
                        "bot.pricing.reconcile.delay-ms=3600000")
                .run()) {
            JdbcTemplate after=restarted.getBean(JdbcTemplate.class);
            assertEquals(jdbcUrl,((HikariDataSource)restarted.getBean(javax.sql.DataSource.class)).getJdbcUrl());
            assertTrue(restarted.getBeansOfType(TestcontainersConfiguration.class).isEmpty());
            assertEquals(beforeCurrent,after.queryForObject("select estimate_id from usage_cost_current where attempt_id=?",String.class,attempt));
            assertEquals(sourceSnapshot,after.queryForObject("select source_snapshot_id from usage_cost_estimate where id=?",String.class,estimate));
            assertTrue(after.queryForObject("select raw_usage=?::jsonb from usage_snapshot where id=?",Boolean.class,sourceRaw,sourceSnapshot));
            assertTrue(after.queryForObject("select raw_usage=?::jsonb from usage_snapshot where id=?",Boolean.class,effectiveRaw,effectiveSnapshot));
            assertEquals(2,after.queryForObject("select count(*) from usage_snapshot where attempt_id=?",Integer.class,attempt));
            assertEquals("8",after.queryForObject("select last_processed_projection_version::text from usage_cost_manual_source_checkpoint where attempt_id=?",String.class,attempt));
            assertEquals("8",after.queryForObject("select source_version::text from usage_cost_estimate where id=?",String.class,estimate));

            restarted.getBean(io.github.samzhu.bot.usage.internal.pricing.PricingReconciliation.class).scanPending();
            assertEquals(1,after.queryForObject("select count(*) from usage_cost_revision_audit where attempt_id=?",Integer.class,attempt));
            assertEquals(beforeCurrent,after.queryForObject("select estimate_id from usage_cost_current where attempt_id=?",String.class,attempt));
            assertEquals("9",after.queryForObject("select last_processed_projection_version::text from usage_cost_manual_source_checkpoint where attempt_id=?",String.class,attempt));
            assertEquals("8",after.queryForObject("select source_version::text from usage_cost_estimate where id=?",String.class,estimate));
            String candidate=after.queryForObject("select id from usage_cost_estimate where attempt_id=? and source_version=9 and publication_kind='AUTO_CANDIDATE'",String.class,attempt);
            assertNotNull(candidate);
            assertEquals(effectiveSnapshot,after.queryForObject("select source_snapshot_id from usage_cost_estimate where id=?",String.class,candidate));
            assertEquals(price,after.queryForObject("select price_snapshot_id from usage_cost_estimate where id=?",String.class,candidate));
            assertNotNull(after.queryForObject("select details::text from usage_cost_estimate where id=?",String.class,candidate));
            assertEquals(1,after.queryForObject("select count(*) from usage_cost_estimate where attempt_id=? and source_version=9 and publication_kind='AUTO_CANDIDATE'",Integer.class,attempt));
            assertTrue(after.queryForObject("select raw_usage=?::jsonb from usage_snapshot where id=?",Boolean.class,sourceRaw,sourceSnapshot));
            assertTrue(after.queryForObject("select raw_usage=?::jsonb from usage_snapshot where id=?",Boolean.class,effectiveRaw,effectiveSnapshot));
            assertEquals("MATCHED",after.queryForObject("select selection_state from usage_price_snapshot where id=?",String.class,price));
            assertEquals("restart-source",after.queryForObject("select source_reference from usage_price_snapshot where id=?",String.class,price));
            assertEquals(1,after.queryForObject("select count(*) from usage_cost_revision_audit where attempt_id=? and idempotency_key='restart-key'",Integer.class,attempt));

            // Once checkpointed, another scan must not recreate the manual revision or replace its pointer.
            restarted.getBean(io.github.samzhu.bot.usage.internal.pricing.PricingReconciliation.class).scanPending();
            assertEquals(beforeCurrent,after.queryForObject("select estimate_id from usage_cost_current where attempt_id=?",String.class,attempt));
            assertEquals(1,after.queryForObject("select count(*) from usage_cost_revision_audit where attempt_id=?",Integer.class,attempt));
            assertEquals("9",after.queryForObject("select last_processed_projection_version::text from usage_cost_manual_source_checkpoint where attempt_id=?",String.class,attempt));
            assertEquals(candidate,after.queryForObject("select id from usage_cost_estimate where attempt_id=? and source_version=9 and publication_kind='AUTO_CANDIDATE'",String.class,attempt));
            assertEquals(1,after.queryForObject("select count(*) from usage_cost_estimate where attempt_id=? and source_version=9 and publication_kind='AUTO_CANDIDATE'",Integer.class,attempt));
        }
    }
}
