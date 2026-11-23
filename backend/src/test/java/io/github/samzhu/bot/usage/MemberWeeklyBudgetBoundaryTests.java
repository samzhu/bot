package io.github.samzhu.bot;

import io.github.samzhu.bot.usage.*;
import io.github.samzhu.bot.workspace.WorkspaceOperations;
import io.github.samzhu.bot.conversation.ConversationOperations;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import java.time.Instant;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

@io.github.samzhu.bot.UseTestFakes
@SpringBootTest
@Import({TestcontainersConfiguration.class,WorkspaceStartupMonthBoundaryTests.ClockConfiguration.class})
@ActiveProfiles("test")
class MemberWeeklyBudgetBoundaryTests {
 @Autowired WorkspaceStartupMonthBoundaryTests.MutableClock clock;
 @Autowired WorkspaceOperations workspaces; @Autowired ConversationOperations conversations;
 @Autowired WorkspaceAdmission admission; @Autowired MemberWeeklyBudgetOperations weekly; @Autowired JdbcTemplate jdbc;
 @Test void oldMonthUnknownStillBlocksSameWeekAndNextWeekClears() {
  clock.instant.set(Instant.parse("2026-12-01T01:00:00Z"));
  String actor="weekly-boundary-"+UUID.randomUUID(), workspace=workspaces.personal(actor).id();
  String bot="weekly-bot-"+UUID.randomUUID(); jdbc.update("insert into bot(id,name,target) values (?,?,'LOCAL')",bot,bot);
  var conversation=conversations.createConversation(bot,"fixture",actor,workspace);
  jdbc.update("""
    insert into provider_attempt(id,workspace_id,actor_id,provider,endpoint,model,channel,adapter_version,simulation,attempt_coverage,prepared_at,started_at,execution_outcome,usage_presence,capture_completeness,projection_state)
    values (?,?,?,'fake','test','model','fake','1',false,'UNKNOWN',?,?,'UNKNOWN','UNKNOWN','UNKNOWN','UNKNOWN')
    """,UUID.randomUUID().toString(),workspace,actor,java.sql.Timestamp.from(Instant.parse("2026-11-30T01:00:00Z")),java.sql.Timestamp.from(Instant.parse("2026-11-30T01:00:00Z")));
  weekly.configure(new MemberWeeklyBudgetOperations.Configure(workspace,actor,WorkspaceAdmission.Partition.REAL,actor,true,"10","0","boundary")).toCompletableFuture().join();
  var first=assertInstanceOf(WorkspaceAdmission.RegisterResult.Denied.class,admission.register(new WorkspaceAdmission.Register(workspace,UUID.randomUUID().toString(),actor,conversation.id(),WorkspaceAdmission.Partition.REAL)).toCompletableFuture().join());
  assertEquals("WEEK_UNKNOWN",first.reason().name());
  clock.instant.set(Instant.parse("2026-12-06T16:00:00Z"));
  assertInstanceOf(WorkspaceAdmission.RegisterResult.Registered.class,admission.register(new WorkspaceAdmission.Register(workspace,UUID.randomUUID().toString(),actor,conversation.id(),WorkspaceAdmission.Partition.REAL)).toCompletableFuture().join());
 }
 @Test void actorAndSimulationPartitionsDoNotInheritUnknown() {
  clock.instant.set(Instant.parse("2026-12-01T01:00:00Z"));
  String actor="weekly-scope-"+UUID.randomUUID(),workspace=workspaces.personal(actor).id(),other="other-"+UUID.randomUUID();
  jdbc.update("insert into workspace_member(workspace_id,user_id,role,active) values (?,?,'MEMBER',true)",workspace,other);
  for(String owner:new String[]{actor,null}) jdbc.update("""
    insert into provider_attempt(id,workspace_id,actor_id,provider,endpoint,model,channel,adapter_version,simulation,attempt_coverage,prepared_at,started_at,execution_outcome,usage_presence,capture_completeness,projection_state)
    values (?,?,?,'fake','test','model','fake','1',false,'UNKNOWN',?,?,'UNKNOWN','UNKNOWN','UNKNOWN','UNKNOWN')
    """,UUID.randomUUID().toString(),workspace,owner,java.sql.Timestamp.from(clock.instant()),java.sql.Timestamp.from(clock.instant()));
  var own=weekly.read(new MemberWeeklyBudgetOperations.Read(workspace,actor,WorkspaceAdmission.Partition.REAL,actor,true)).toCompletableFuture().join();
  var otherRead=weekly.read(new MemberWeeklyBudgetOperations.Read(workspace,other,WorkspaceAdmission.Partition.REAL,actor,false)).toCompletableFuture().join();
  var simulated=weekly.read(new MemberWeeklyBudgetOperations.Read(workspace,actor,WorkspaceAdmission.Partition.SIMULATION,actor,true)).toCompletableFuture().join();
  assertEquals(1,own.terminalUnknownCount()); assertEquals(0,otherRead.terminalUnknownCount()); assertEquals(0,simulated.terminalUnknownCount());
 }
 @Test void lateTerminalEvidenceRemainsInDispatchWeek() {
  clock.instant.set(Instant.parse("2026-12-07T01:00:00Z"));
  String actor="weekly-late-"+UUID.randomUUID(),workspace=workspaces.personal(actor).id();
  jdbc.update("""
    insert into provider_attempt(id,workspace_id,actor_id,provider,endpoint,model,channel,adapter_version,simulation,attempt_coverage,prepared_at,started_at,execution_outcome,usage_presence,capture_completeness,projection_state)
    values (?,?,?,'fake','test','model','fake','1',false,'UNKNOWN',?,?,'UNKNOWN','UNKNOWN','UNKNOWN','UNKNOWN')
    """,UUID.randomUUID().toString(),workspace,actor,java.sql.Timestamp.from(Instant.parse("2026-12-01T01:00:00Z")),java.sql.Timestamp.from(Instant.parse("2026-12-01T01:00:00Z")));
  var read=new MemberWeeklyBudgetOperations.Read(workspace,actor,WorkspaceAdmission.Partition.REAL,actor,true);
  assertEquals(0,weekly.read(read).toCompletableFuture().join().terminalUnknownCount());
  clock.instant.set(Instant.parse("2026-12-01T01:00:00Z"));
  assertEquals(1,weekly.read(read).toCompletableFuture().join().terminalUnknownCount());
 }
}
