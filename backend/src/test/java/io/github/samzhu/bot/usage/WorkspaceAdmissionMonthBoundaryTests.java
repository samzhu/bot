package io.github.samzhu.bot;

import io.github.samzhu.bot.conversation.ConversationOperations;
import io.github.samzhu.bot.usage.BudgetCancellationSink;
import io.github.samzhu.bot.usage.UsageLedger;
import io.github.samzhu.bot.usage.WorkspaceAdmission;
import io.github.samzhu.bot.workspace.WorkspaceOperations;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.convention.TestBean;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class WorkspaceAdmissionMonthBoundaryTests {
    static final class MutableClock extends Clock {
        final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-30T15:59:59Z"));
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return Clock.fixed(now.get(),zone); }
        public Instant instant() { return now.get(); }
    }
    @TestBean(name="usageClock",methodName="mutableClock",enforceOverride=true)
    Clock clock;
    static Clock mutableClock() { return new MutableClock(); }
    @Autowired WorkspaceAdmission admission;
    @Autowired WorkspaceOperations workspaces;
    @Autowired ConversationOperations conversations;
    @Autowired UsageLedger ledger;
    @Autowired JdbcTemplate jdbc;
    @Autowired javax.sql.DataSource dataSource;

    @Test void oldPendingEffectRemainsInspectableAndCannotCancelNewMonthClaim() {
        ((MutableClock)clock).now.set(Instant.parse("2026-11-30T15:59:59Z"));
        String actor="month-user-"+UUID.randomUUID(), workspace=workspaces.personal(actor).id();
        String bot="month-bot-"+UUID.randomUUID(),run="month-run-"+UUID.randomUUID();
        jdbc.update("insert into bot(id,name,target) values (?,?,'LOCAL')",bot,bot);
        var conversation=conversations.createConversation(bot,"fixture",actor,workspace);
        var registration=assertInstanceOf(WorkspaceAdmission.RegisterResult.Registered.class,
                admission.register(new WorkspaceAdmission.Register(workspace,run,actor,conversation.id(),WorkspaceAdmission.Partition.REAL)).toCompletableFuture().join()).registration();
        jdbc.update("insert into chat_run(id,conversation_id,client_request_id,input_text,status) values (?,?,?,'test','PENDING')",run,conversation.id(),UUID.randomUUID().toString());
        assertInstanceOf(WorkspaceAdmission.PublicationResult.Ready.class,admission.publish(registration).toCompletableFuture().join());
        var pending=jdbc.queryForMap("select pending_id,pending_epoch,pending_period_start,pending_period_end from workspace_admission_registration where token=?",registration.token());
        String id=UUID.randomUUID().toString(), receipt=UUID.randomUUID().toString();
        jdbc.update("""
                insert into workspace_admission_effect_outbox(effect_id,registration_token,target_workspace_id,target_partition,target_run_id,
                  pending_id,pending_epoch,period_start,period_end,generation,delivery_status,intent_receipt)
                values (?,?,?,'REAL',?,?,?,?,?,0,'PENDING',?)
                """,id,registration.token(),workspace,run,pending.get("pending_id"),pending.get("pending_epoch"),pending.get("pending_period_start"),pending.get("pending_period_end"),receipt);
        var before=assertInstanceOf(WorkspaceAdmission.EffectInspection.Found.class,admission.inspectCancellationEffect(id).toCompletableFuture().join()).effect();
        ((MutableClock)clock).now.set(Instant.parse("2026-11-30T16:00:01Z"));
        String attempt=ledger.begin(new UsageLedger.Dispatch(run,workspace,actor,"FAKE","test","model","FAKE","1",false,"UNKNOWN"));
        var allowed=assertInstanceOf(WorkspaceAdmission.Decision.Allowed.class,admission.dispatch(new WorkspaceAdmission.Dispatch(registration,attempt)).toCompletableFuture().join());
        assertEquals(Instant.parse("2026-11-30T16:00:00Z"),allowed.claim().periodStart());
        var after=assertInstanceOf(WorkspaceAdmission.EffectInspection.Found.class,admission.inspectCancellationEffect(id).toCompletableFuture().join()).effect();
        assertEquals(before.target(),after.target());
        var effect=new BudgetCancellationSink.Effect(id,registration.token(),workspace,WorkspaceAdmission.Partition.REAL,run,before.target(),receipt);
        assertInstanceOf(WorkspaceAdmission.EffectAcceptance.Stale.class,admission.prepareCancellationEffect(effect).toCompletableFuture().join());
        assertInstanceOf(WorkspaceAdmission.ProviderStartResult.Ready.class,admission.authorizeProviderStart(allowed.claim()).toCompletableFuture().join());
        var ds=(com.zaxxer.hikari.HikariDataSource)dataSource;
        try (var restarted=new org.springframework.boot.builder.SpringApplicationBuilder(BotApplication.class)
                .web(org.springframework.boot.WebApplicationType.NONE).profiles("test")
                .properties("spring.datasource.url="+ds.getJdbcUrl(),"spring.datasource.username="+ds.getUsername(),
                        "spring.datasource.password="+ds.getPassword(),"spring.docker.compose.enabled=false",
                        "bot.pricing.reconcile.initial-delay-ms=3600000","bot.pricing.reconcile.delay-ms=3600000").run()) {
            var restored=restarted.getBean(WorkspaceAdmission.class);
            var replay=assertInstanceOf(WorkspaceAdmission.EffectInspection.Found.class,restored.inspectCancellationEffect(id).toCompletableFuture().join()).effect();
            assertEquals(before.target(),replay.target());
            assertEquals(receipt,replay.intentReceipt());
            assertInstanceOf(WorkspaceAdmission.EffectAcceptance.Stale.class,restored.prepareCancellationEffect(effect).toCompletableFuture().join());
        }
    }
}
