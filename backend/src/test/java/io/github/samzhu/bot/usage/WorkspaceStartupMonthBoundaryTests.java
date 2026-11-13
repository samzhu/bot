package io.github.samzhu.bot;

import io.github.samzhu.bot.usage.WorkspaceAdmission;
import io.github.samzhu.bot.workspace.WorkspaceOperations;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties="bot.execution.startup-coordinator.enabled=false")
@Import({TestcontainersConfiguration.class,WorkspaceStartupMonthBoundaryTests.ClockConfiguration.class})
@ActiveProfiles("test")
class WorkspaceStartupMonthBoundaryTests {
    static final class MutableClock extends Clock {
        final AtomicReference<Instant> instant=new AtomicReference<>(Instant.parse("2026-11-30T15:59:00Z"));
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return instant.get(); }
    }
    @TestConfiguration(proxyBeanMethods=false)
    static class ClockConfiguration {
        @Bean @Primary MutableClock startupClock() { return new MutableClock(); }
    }
    @Autowired WorkspaceAdmission admission;
    @Autowired WorkspaceOperations workspaces;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired MutableClock clock;

    @Test void finalVerificationCannotOpenUsingPreviousMonthProjection() throws Exception {
        String workspace=workspaces.personal("month-gate-"+UUID.randomUUID()).id();
        jdbc.update("insert into workspace_monthly_budget(workspace_id,partition,enabled,cap_usd) values (?,'REAL',true,100)",workspace);
        jdbc.update("insert into workspace_monthly_budget_period(workspace_id,partition,period_start) values (?,'REAL',date '2026-11-01')",workspace);
        try(var connection=dataSource.getConnection()) {
            connection.setAutoCommit(false);
            int blocker;
            try(var statement=connection.createStatement(); var rs=statement.executeQuery("select pg_backend_pid()")) { rs.next(); blocker=rs.getInt(1); }
            try(var lock=connection.prepareStatement("select workspace_id from workspace_monthly_budget_period where workspace_id=? and partition='REAL' and period_start=date '2026-11-01' for update")) {
                lock.setString(1,workspace); lock.executeQuery().close();
            }
            var result=admission.verifyAndOpen().toCompletableFuture();
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            boolean blocked;
            do {
                blocked=Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from pg_stat_activity where ?=any(pg_blocking_pids(pid)))",Boolean.class,blocker));
                if (blocked) break;
                Thread.sleep(20);
            } while (System.nanoTime()<deadline);
            assertTrue(blocked,"the old-period gate recomputation must reach the held database lock");
            clock.instant.set(Instant.parse("2026-11-30T16:00:00Z"));
            connection.commit();
            assertEquals(WorkspaceAdmission.RecoveryReadiness.RETRY,result.get(10,TimeUnit.SECONDS));
            assertEquals(WorkspaceAdmission.RecoveryReadiness.READY,admission.verifyAndOpen().toCompletableFuture().get(10,TimeUnit.SECONDS));
            assertEquals(1,jdbc.queryForObject("select count(*) from workspace_monthly_budget_period where workspace_id=? and partition='REAL' and period_start=date '2026-12-01'",Integer.class,workspace));
        }
    }
}
