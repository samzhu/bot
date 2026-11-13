package io.github.samzhu.bot.usage.internal.pricing;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.samzhu.bot.identity.CurrentUserProvider;
import io.github.samzhu.bot.usage.UsageCostRevisionOperations;
import io.github.samzhu.bot.usage.UsageCostOperations;
import io.github.samzhu.bot.workspace.WorkspaceAccessDeniedException;
import io.github.samzhu.bot.workspace.WorkspaceOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import io.github.samzhu.bot.usage.UsageLedger;
import com.fasterxml.jackson.core.type.TypeReference;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
public class JdbcUsageCostRevisionOperations implements UsageCostRevisionOperations {
    private final JdbcTemplate jdbc;
    private final WorkspaceOperations workspaces;
    private final CurrentUserProvider users;
    private final Clock clock;
    private final UsageLedger ledger;
    private final TransactionTemplate transactions;
    private final UsageCostOperations costs;
    private final io.github.samzhu.bot.usage.internal.admission.WorkspaceLane lane;
    private final io.github.samzhu.bot.usage.internal.admission.BudgetProjection projection;
    private final ObjectMapper json = new ObjectMapper();

    public JdbcUsageCostRevisionOperations(JdbcTemplate jdbc, WorkspaceOperations workspaces,
                                           CurrentUserProvider users, Clock usageClock, UsageLedger ledger, TransactionTemplate transactions, UsageCostOperations costs,
                                           io.github.samzhu.bot.usage.internal.admission.WorkspaceLane lane,
                                           io.github.samzhu.bot.usage.internal.admission.BudgetProjection projection) {
        this.jdbc = jdbc; this.workspaces = workspaces; this.users = users; this.clock = usageClock;
        this.ledger=ledger; this.transactions=transactions;
        this.costs=costs; this.lane=lane; this.projection=projection;
    }
    public static class Failure extends RuntimeException {
        public Failure(String code) { super(code); }
    }

    @Override
    public Result manual(ManualCommand c) {
        String actor=authorize(c.workspaceId());
        try {
            return lane.submitCritical(c.workspaceId(), () -> transactions.execute(tx -> {
                io.github.samzhu.bot.usage.internal.admission.UsageWriterLock.registrationForAttempt(jdbc,c.attemptId());
                Result result=manualInTransaction(c,actor);
                refreshBudget(c.workspaceId(),c.attemptId());
                return result;
            })).toCompletableFuture().join();
        } catch (java.util.concurrent.CompletionException ex) {
            if (ex.getCause() instanceof RuntimeException cause) throw cause;
            throw ex;
        }
    }
    private Result manualInTransaction(ManualCommand c,String actor) {
        authorize(c.workspaceId(),actor);
        var attempts = jdbc.query("select projection_version,execution_outcome,projection_state,projection_snapshot_id from provider_attempt where id=? and workspace_id=? for update",
                (rs,n) -> new Attempt(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4)), c.attemptId(), c.workspaceId());
        if (attempts.isEmpty()) throw new Failure("USAGE_ATTEMPT_ACCESS_DENIED");
        // Authorization may have changed while waiting for the attempt lock.
        authorize(c.workspaceId(),actor);
        var attempt = attempts.getFirst();
        requireEligibleOutcome(attempt.outcome());
        String amount = amount(c.amount());
        String key = required(c.idempotencyKey(), 128), source = required(c.sourceType(), 64);
        if (!"MANUAL_ESTIMATE".equals(source)) throw new Failure("INVALID_COST_REVISION");
        String evidence = required(c.evidenceReference(), 1024), reason = required(c.reason(), 2048);
        long version = version(c.expectedSourceProjectionVersion());
        var payload = new LinkedHashMap<String, Object>();
        payload.put("kind", "MANUAL_ESTIMATE"); payload.put("workspaceId", c.workspaceId());
        payload.put("attemptId", c.attemptId()); payload.put("expectedRevision", c.expectedRevision());
        payload.put("expectedSourceProjectionVersion", Long.toString(version)); payload.put("idempotencyKey", key);
        payload.put("amount", amount); payload.put("sourceType", source);
        payload.put("actorId", actor);
        payload.put("evidenceReference", evidence); payload.put("reason", reason);
        String payloadJson = json(payload);
        var replay = jdbc.query("select a.command_payload = ?::jsonb, a.estimate_id from usage_cost_revision_audit a where a.attempt_id=? and a.idempotency_key=?",
                (rs,n) -> new Replay(rs.getBoolean(1),rs.getString(2)), payloadJson,c.attemptId(),key);
        if (!replay.isEmpty()) {
            if (!replay.getFirst().same()) throw new Failure("CONFLICT");
            return result("REPLAYED",replay.getFirst().estimateId(),c.attemptId());
        }
        var current=jdbc.query("select estimate_id from usage_cost_current where attempt_id=?",(rs,n)->rs.getString(1),c.attemptId());
        if (attempt.version()!=version || !Objects.equals(c.expectedRevision(),current.isEmpty()?null:current.getFirst())) throw new Failure("CONFLICT");
        var prices=jdbc.query("select id from usage_price_snapshot where attempt_id=? and origin='AUTO_DISPATCH'",(rs,n)->rs.getString(1),c.attemptId());
        String price=prices.isEmpty()?null:prices.getFirst();
        String id=UUID.randomUUID().toString();
        String sourceSnapshot="VALID".equals(attempt.projectionState())?attempt.snapshotId():null;
        String manualDetails="{\"reasons\":[\"MANUAL_ESTIMATE\"],\"lines\":[]}";
        var subtotal=new BigDecimal(amount);
        // V8's estimate identity includes calculator_version; a manual declaration needs
        // a unique marker here, not a claim that a provider calculator produced it.
        String calculator="manual-"+id;
        jdbc.update("insert into usage_cost_estimate(id,attempt_id,source_version,source_snapshot_id,price_snapshot_id,calculator_version,status,known_subtotal,details,payload_hash,publication_kind) values (?,?,?,?,?,?,'UNKNOWN',?,?::jsonb,?,'AUDITED')",id,c.attemptId(),version,sourceSnapshot,price,calculator,subtotal,manualDetails,estimateHash(c.attemptId(),version,sourceSnapshot,price,calculator,"UNKNOWN",subtotal,null,manualDetails));
        jdbc.update("insert into usage_cost_revision_audit(id,attempt_id,estimate_id,kind,idempotency_key,command_payload,actor_id,recorded_at,source_type,evidence_reference,reason) values (?,?,?,'MANUAL_ESTIMATE',?,?::jsonb,?,?,?,?,?)",UUID.randomUUID().toString(),c.attemptId(),id,key,payloadJson,actor,java.sql.Timestamp.from(clock.instant()),source,evidence,reason);
        jdbc.update("insert into usage_cost_current(attempt_id,estimate_id) values (?,?) on conflict (attempt_id) do update set estimate_id=excluded.estimate_id",c.attemptId(),id);
        return result("APPLIED",id,c.attemptId());
    }
    @Override public Result reconcile(ReconcileCommand c) {
        String actor=authorize(c.workspaceId());
        // Scope must win over malformed input; publish repeats this lock and authorization
        // after calculation because the first short transaction deliberately releases it.
        PreparedReconciliation prepared=transactions.execute(tx -> {
            var scope=jdbc.query("select execution_outcome from provider_attempt where id=? and workspace_id=? for update",(rs,n)->rs.getString(1),c.attemptId(),c.workspaceId());
            if(scope.isEmpty()) throw new Failure("USAGE_ATTEMPT_ACCESS_DENIED");
            authorize(c.workspaceId());
            requireEligibleOutcome(scope.getFirst());
            long sourceVersion=version(c.expectedSourceProjectionVersion());
            String commandKey=required(c.idempotencyKey(),128), catalogId=required(c.catalogRevisionId(),64), proof=required(c.evidenceReference(),1024), explanation=required(c.reason(),2048);
            var payload=new LinkedHashMap<String,Object>();
            payload.put("kind","RECONCILIATION"); payload.put("workspaceId",c.workspaceId()); payload.put("attemptId",c.attemptId());
            payload.put("expectedRevision",c.expectedRevision()); payload.put("expectedSourceProjectionVersion",Long.toString(sourceVersion));
            payload.put("idempotencyKey",commandKey); payload.put("catalogRevisionId",catalogId); payload.put("sourceType","SERVER_RAW_CATALOG"); payload.put("evidenceReference",proof); payload.put("reason",explanation); payload.put("actorId",actor);
            String canonical=json(payload);
            return new PreparedReconciliation(sourceVersion,commandKey,catalogId,proof,explanation,canonical,checkReconcile(c,sourceVersion,commandKey,canonical,actor));
        });
        long version=prepared.version(); String key=prepared.key(),catalog=prepared.catalog(),evidence=prepared.evidence(),reason=prepared.reason(),payloadJson=prepared.payload();
        Result prior=prepared.prior();
        if(prior!=null) return prior;
        var input=ledger.readPricingInput(c.attemptId());
        if(input==null || input.projectionVersion()!=version || !Objects.equals(input.workspaceId(),c.workspaceId())) throw new Failure("CONFLICT");
        if(input.startedAt()==null) throw new Failure("SOURCE_UNAVAILABLE");
        var revisions=jdbc.query("select source_reference from pricing_catalog_revision where id=?",(rs,n)->rs.getString(1),catalog);
        if(revisions.isEmpty()) throw new Failure("SOURCE_UNAVAILABLE");
        var choices=jdbc.query("select id,price_revision,rates::text,effective_from,effective_until from pricing_catalog_entry where revision_id=? and provider=? and endpoint=? and model=? and channel=? and effective_from<=? and ?<effective_until",
            (rs,n)->new CatalogChoice(rs.getString(1),rs.getString(2),rs.getString(3),rs.getTimestamp(4),rs.getTimestamp(5)),catalog,input.provider(),input.endpoint(),input.model(),input.channel(),java.sql.Timestamp.from(input.startedAt()),java.sql.Timestamp.from(input.startedAt()));
        CatalogChoice choice=choices.size()==1?choices.getFirst():null;
        Map<String,String> rates=null;
        try { if(choice!=null) rates=json.readValue(choice.rates(),new TypeReference<Map<String,String>>(){}); }
        catch(JsonProcessingException e) { throw new IllegalStateException(e); }
        var calculated=ReconciliationCalculator.calculate(input,choice==null?null:choice.revision(),rates);
        if(choices.size()>1) calculated=ReconciliationCalculator.withPriceOverlap(calculated);
        var result=calculated;
        java.util.function.Supplier<Result> publish=() -> transactions.execute(tx -> {
            Result replay=checkReconcile(c,version,key,payloadJson,actor);
            if(replay!=null) return replay;
            var currentInput=ledger.readPricingInput(c.attemptId());
            if(currentInput==null || currentInput.projectionVersion()!=version || !Objects.equals(currentInput.effectiveSnapshotId(),input.effectiveSnapshotId())) throw new Failure("CONFLICT");
            String priceId=UUID.randomUUID().toString(), estimateId=UUID.randomUUID().toString();
            String selection=choices.size()>1?"OVERLAPPING":choice==null?"MISSING":"MATCHED";
            jdbc.update("insert into usage_price_snapshot(id,attempt_id,origin,selection_state,provider,endpoint,model,channel,price_revision,rates,catalog_revision_id,catalog_entry_id,source_reference,effective_from,effective_until) values (?,?,'AUDITED_RECONCILIATION',?,?,?,?,?,?,?::jsonb,?,?,?,?,?)",priceId,c.attemptId(),selection,input.provider(),input.endpoint(),input.model(),input.channel(),choice==null?null:choice.revision(),choice==null?null:choice.rates(),catalog,choice==null?null:choice.id(),revisions.getFirst(),choice==null?null:choice.from(),choice==null?null:choice.until());
            var estimate=result.estimate();
            var complete="COMPLETE".equals(result.status())?estimate.completeAmount():null;
            jdbc.update("insert into usage_cost_estimate(id,attempt_id,source_version,source_snapshot_id,price_snapshot_id,calculator_version,status,known_subtotal,complete_amount,details,payload_hash,publication_kind) values (?,?,?,?,?,?,?,?,?,?::jsonb,?, 'AUDITED')",estimateId,c.attemptId(),version,input.effectiveSnapshotId(),priceId,estimate.calculatorVersion(),result.status(),estimate.knownSubtotal(),complete,result.details(),estimateHash(c.attemptId(),version,input.effectiveSnapshotId(),priceId,estimate.calculatorVersion(),result.status(),estimate.knownSubtotal(),complete,result.details()));
            jdbc.update("insert into usage_cost_revision_audit(id,attempt_id,estimate_id,kind,idempotency_key,command_payload,actor_id,recorded_at,source_type,evidence_reference,reason) values (?,?,?,'RECONCILIATION',?,?::jsonb,?,?, 'SERVER_RAW_CATALOG',?,?)",UUID.randomUUID().toString(),c.attemptId(),estimateId,key,payloadJson,actor,java.sql.Timestamp.from(clock.instant()),evidence,reason);
            jdbc.update("insert into usage_cost_current(attempt_id,estimate_id) values (?,?) on conflict (attempt_id) do update set estimate_id=excluded.estimate_id",c.attemptId(),estimateId);
            return result("APPLIED",estimateId,c.attemptId());
        });
        try {
            return lane.submitCritical(c.workspaceId(), () -> transactions.execute(tx -> {
                io.github.samzhu.bot.usage.internal.admission.UsageWriterLock.registrationForAttempt(jdbc,c.attemptId());
                Result saved=publish.get();
                refreshBudget(c.workspaceId(),c.attemptId());
                return saved;
            })).toCompletableFuture().join();
        } catch (java.util.concurrent.CompletionException ex) {
            if (ex.getCause() instanceof RuntimeException cause) throw cause;
            throw ex;
        }
    }
    private Result checkReconcile(ReconcileCommand c,long version,String key,String payloadJson,String actor) {
        var rows=jdbc.query("select projection_version,execution_outcome from provider_attempt where id=? and workspace_id=? for update",(rs,n)->new Object[]{rs.getLong(1),rs.getString(2)},c.attemptId(),c.workspaceId());
        if(rows.isEmpty()) throw new Failure("USAGE_ATTEMPT_ACCESS_DENIED");
        authorize(c.workspaceId(),actor);
        requireEligibleOutcome((String)rows.getFirst()[1]);
        var replay=jdbc.query("select command_payload=?::jsonb,estimate_id from usage_cost_revision_audit where attempt_id=? and idempotency_key=?",(rs,n)->new Replay(rs.getBoolean(1),rs.getString(2)),payloadJson,c.attemptId(),key);
        if(!replay.isEmpty()) { if(!replay.getFirst().same()) throw new Failure("CONFLICT"); return result("REPLAYED",replay.getFirst().estimateId(),c.attemptId()); }
        var current=jdbc.query("select estimate_id from usage_cost_current where attempt_id=?",(rs,n)->rs.getString(1),c.attemptId());
        if((Long)rows.getFirst()[0]!=version || !Objects.equals(c.expectedRevision(),current.isEmpty()?null:current.getFirst())) throw new Failure("CONFLICT");
        return null;
    }
    private record CatalogChoice(String id,String revision,String rates,java.sql.Timestamp from,java.sql.Timestamp until) {}
    private record PreparedReconciliation(long version,String key,String catalog,String evidence,String reason,String payload,Result prior) {}
    private Result result(String outcome,String estimateId,String attemptId) {
        var cost=costs.readCosts(java.util.List.of(attemptId)).get(attemptId);
        if(cost==null) throw new IllegalStateException("Attempt cost disappeared while locked");
        return new Result(outcome,revision(estimateId),cost);
    }
    private static String estimateHash(String attempt,long version,String snapshot,String price,String calculator,String status,BigDecimal subtotal,BigDecimal complete,String details) {
        var payload=new java.util.TreeMap<String,Object>();
        payload.put("attemptId",attempt); payload.put("sourceVersion",Long.toString(version)); payload.put("sourceSnapshotId",snapshot);
        payload.put("priceSnapshotId",price); payload.put("calculatorVersion",calculator); payload.put("status",status); payload.put("currency","USD");
        payload.put("knownSubtotal",subtotal==null?null:subtotal.toPlainString()); payload.put("completeAmount",complete==null?null:complete.toPlainString()); payload.put("details",details);
        return PricingPublisher.canonicalPayloadHash(payload);
    }

    private String authorize(String workspaceId) {
        return authorize(workspaceId,users.getCurrentUserId());
    }
    private String authorize(String workspaceId,String actor) {
        try { workspaces.requireMember(workspaceId,actor); }
        catch (WorkspaceAccessDeniedException ex) { throw new Failure("WORKSPACE_ACCESS_DENIED"); }
        if (!workspaces.isManager(workspaceId,actor)) throw new Failure("WORKSPACE_MANAGER_REQUIRED");
        return actor;
    }
    private void refreshBudget(String workspaceId,String attemptId) {
        var rows=jdbc.query("select simulation,started_at from provider_attempt where id=? and workspace_id=?",
                (rs,n)->new Object[]{rs.getBoolean(1),rs.getTimestamp(2)},attemptId,workspaceId);
        if (rows.isEmpty() || rows.getFirst()[1]==null) return;
        var row=rows.getFirst(); var zone=java.time.ZoneId.of("Asia/Taipei");
        var period=java.time.YearMonth.from(((java.sql.Timestamp)row[1]).toInstant().atZone(zone));
        projection.recompute(workspaceId,(boolean)row[0] ? io.github.samzhu.bot.usage.WorkspaceAdmission.Partition.SIMULATION : io.github.samzhu.bot.usage.WorkspaceAdmission.Partition.REAL,
                period.atDay(1).atStartOfDay(zone).toInstant(),period.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant());
    }
    private Revision revision(String id) {
        return jdbc.queryForObject("select e.id,e.attempt_id,a.kind,e.status,e.known_subtotal,e.complete_amount,e.source_version,a.source_type,a.evidence_reference,a.reason,a.actor_id,a.recorded_at,e.source_snapshot_id,e.price_snapshot_id,p.price_revision,p.catalog_revision_id,e.calculator_version,e.details::text from usage_cost_estimate e join usage_cost_revision_audit a on a.estimate_id=e.id left join usage_price_snapshot p on p.id=e.price_snapshot_id where e.id=?",
                (rs,n)->new Revision(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getBigDecimal(5)==null?null:rs.getBigDecimal(5).stripTrailingZeros().toPlainString(),rs.getBigDecimal(6)==null?null:rs.getBigDecimal(6).toPlainString(),Long.toString(rs.getLong(7)),rs.getString(8),rs.getString(9),rs.getString(10),rs.getString(11),rs.getTimestamp(12).toInstant().toString(),rs.getString(13),rs.getString(14),rs.getString(15),rs.getString(16),rs.getString(17),reasonList(rs.getString(18)),"USD"),id);
    }
    private java.util.List<String> reasonList(String details) { try { var values=new java.util.ArrayList<String>(); json.readTree(details).path("reasons").forEach(v->values.add(v.asText())); return java.util.List.copyOf(values); } catch(JsonProcessingException ex) { throw new IllegalStateException("Invalid estimate details",ex); } }
    private static void requireEligibleOutcome(String outcome) {
        if ("IN_PROGRESS".equals(outcome) || "NOT_DISPATCHED".equals(outcome)) throw new Failure("INVALID_COST_REVISION");
    }
    private static String required(String value,int max) {
        // Java whitespace excludes non-breaking spaces, which cannot supply evidence either.
        if(value==null||value.codePoints().allMatch(cp -> Character.isWhitespace(cp)||Character.isSpaceChar(cp))||value.strip().length()>max) throw new Failure("INVALID_COST_REVISION");
        return value.strip();
    }
    private static long version(String value) { try { if(value==null||!value.matches("[0-9]+")) throw new IllegalArgumentException(); return Long.parseLong(value); } catch(RuntimeException ex) { throw new Failure("INVALID_COST_REVISION"); } }
    private static String amount(String value) { try { if(value==null||!value.matches("[0-9]+(?:\\.[0-9]+)?")) throw new IllegalArgumentException(); var n=new BigDecimal(value); if(n.scale()>12||n.precision()-n.scale()>18) throw new IllegalArgumentException(); return n.setScale(12,RoundingMode.UNNECESSARY).toPlainString(); } catch(RuntimeException ex) { throw new Failure("INVALID_AMOUNT"); } }
    private String json(Object value) { try { return json.writeValueAsString(value); } catch(JsonProcessingException e) { throw new IllegalStateException(e); } }
    private record Attempt(long version,String outcome,String projectionState,String snapshotId) {}
    private record Replay(boolean same,String estimateId) {}
}
