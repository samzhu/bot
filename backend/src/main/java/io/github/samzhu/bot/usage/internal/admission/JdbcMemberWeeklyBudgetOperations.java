package io.github.samzhu.bot.usage.internal.admission;

import io.github.samzhu.bot.usage.MemberWeeklyBudgetOperations;
import io.github.samzhu.bot.usage.WorkspaceAdmission;
import io.github.samzhu.bot.workspace.WorkspaceOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.concurrent.CompletionStage;

@Service
public class JdbcMemberWeeklyBudgetOperations implements MemberWeeklyBudgetOperations {
    private static final ZoneId ZONE=ZoneId.of("Asia/Taipei");
    private final JdbcTemplate jdbc; private final WorkspaceOperations workspaces; private final WorkspaceLane lane;
    private final TransactionTemplate tx; private final Clock clock; private final DefaultWorkspaceAdmission admission; private final WeeklyBudgetQuery query;
    public JdbcMemberWeeklyBudgetOperations(JdbcTemplate jdbc, WorkspaceOperations workspaces, WorkspaceLane lane,
            org.springframework.transaction.PlatformTransactionManager manager, Clock clock, DefaultWorkspaceAdmission admission, WeeklyBudgetQuery query) {
        this.jdbc=jdbc; this.workspaces=workspaces; this.lane=lane; this.tx=new TransactionTemplate(manager); this.clock=clock; this.admission=admission; this.query=query;
    }
    private void authorize(String workspace,String member,String actor,boolean self) {
        if (actor==null || !workspaces.isMember(workspace,actor)) throw new Failure("WORKSPACE_ACCESS_DENIED");
        if (!self && !workspaces.isManager(workspace,actor)) throw new Failure("WORKSPACE_MANAGER_REQUIRED");
        if ((self && !actor.equals(member)) || member==null || !workspaces.isMember(workspace,member)) throw new Failure("MEMBER_ACCESS_DENIED");
    }
    @Override public CompletionStage<Budget> read(Read c) {
        return lane.submit(c.workspaceId(),()-> { authorize(c.workspaceId(),c.memberUserId(),c.actorId(),c.self());
            return tx.execute(s->select(c.workspaceId(),c.memberUserId(),c.partition(),clock.instant())); });
    }
    @Override public CompletionStage<Result> configure(Configure c) {
        return lane.submit(c.workspaceId(),()-> { authorize(c.workspaceId(),c.memberUserId(),c.actorId(),false);
            BigDecimal cap; long version;
            try {
                if(c.partition()==null || c.expectedConfigVersion()==null || !c.expectedConfigVersion().matches("[0-9]+") || c.idempotencyKey()==null || c.idempotencyKey().isBlank() || c.idempotencyKey().length()>128 || (c.capUsd()!=null && !c.capUsd().matches("[0-9]+(?:\\.[0-9]+)?"))) throw new IllegalArgumentException();
                version=Long.parseLong(c.expectedConfigVersion()); cap=c.capUsd()==null?null:new BigDecimal(c.capUsd()).stripTrailingZeros();
                if((c.enabled() && cap==null) || (cap!=null && (cap.scale()>12 || cap.compareTo(new BigDecimal("999999999999999999.999999999999"))>0))) throw new IllegalArgumentException();
            } catch(RuntimeException ex) { throw new Failure("INVALID_WEEKLY_BUDGET_CONFIGURATION"); }
            return tx.execute(s->{
                // Title admission uses this same workspace/partition row before reading weekly policy.
                jdbc.update("insert into workspace_monthly_budget(workspace_id,partition) values (?,?) on conflict do nothing",c.workspaceId(),c.partition().name());
                jdbc.query("select workspace_id from workspace_monthly_budget where workspace_id=? and partition=? for update",
                        (rs,n)->rs.getString(1),c.workspaceId(),c.partition().name());
                jdbc.update("insert into member_weekly_budget(workspace_id,member_user_id,partition) values (?,?,?) on conflict do nothing",c.workspaceId(),c.memberUserId(),c.partition().name());
                var rows=jdbc.query("select expected_config_version,enabled,cap_usd from member_weekly_budget_command where workspace_id=? and member_user_id=? and partition=? and actor_id=? and idempotency_key=?",
                    (rs,n)->new Object[]{rs.getLong(1),rs.getBoolean(2),rs.getBigDecimal(3)},c.workspaceId(),c.memberUserId(),c.partition().name(),c.actorId(),c.idempotencyKey());
                String outcome="APPLIED";
                if(!rows.isEmpty()) { var p=rows.getFirst(); BigDecimal old=(BigDecimal)p[2];
                    if((long)p[0]!=version || (boolean)p[1]!=c.enabled() || !(old==null?cap==null:cap!=null && old.compareTo(cap)==0)) throw new Failure("WEEKLY_BUDGET_CONFIGURATION_CONFLICT"); outcome="REPLAYED";
                } else {
                    int n=jdbc.update("update member_weekly_budget set enabled=?,cap_usd=?,config_version=config_version+1 where workspace_id=? and member_user_id=? and partition=? and config_version=?",c.enabled(),cap,c.workspaceId(),c.memberUserId(),c.partition().name(),version);
                    if(n!=1) throw new Failure("WEEKLY_BUDGET_CONFIGURATION_CONFLICT");
                    jdbc.update("insert into member_weekly_budget_command(workspace_id,member_user_id,partition,actor_id,idempotency_key,expected_config_version,enabled,cap_usd) values (?,?,?,?,?,?,?,?)",c.workspaceId(),c.memberUserId(),c.partition().name(),c.actorId(),c.idempotencyKey(),version,c.enabled(),cap);
                }
                return new Result(outcome,select(c.workspaceId(),c.memberUserId(),c.partition(),clock.instant()));
            });
        });
    }
    private Budget select(String workspace,String member,WorkspaceAdmission.Partition partition,Instant at) {
        var b=query.select(workspace,member,partition,at);
        return new Budget(b.workspaceId(),b.memberUserId(),b.partition(),b.start(),b.end(),b.enabled(),b.capUsd(),b.knownSubtotalUsd(),b.terminalUnknownCount(),b.configVersion(),admission.scopeRequiresRecovery(workspace,partition));
    }
    public static class Failure extends RuntimeException { public Failure(String code) { super(code); } }
}
