package io.github.samzhu.bot.usage.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.samzhu.bot.identity.CurrentUserProvider;
import io.github.samzhu.bot.usage.UsageCostOperations;
import io.github.samzhu.bot.usage.internal.pricing.JdbcUsageCostRevisionOperations.Failure;
import io.github.samzhu.bot.workspace.WorkspaceAccessDeniedException;
import io.github.samzhu.bot.workspace.WorkspaceOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

@Service
public class UsageCostRevisionQueryService {
    private final JdbcTemplate jdbc;
    private final WorkspaceOperations workspaces;
    private final CurrentUserProvider users;
    private final UsageCostOperations costs;
    private final ObjectMapper json=new ObjectMapper();
    private final byte[] key=new byte[32];
    public UsageCostRevisionQueryService(JdbcTemplate jdbc,WorkspaceOperations workspaces,CurrentUserProvider users,UsageCostOperations costs) {
        this.jdbc=jdbc; this.workspaces=workspaces; this.users=users; this.costs=costs; new SecureRandom().nextBytes(key);
    }
    public record Cursor(String scope,String time,String id) {}
    public record PageInfo(boolean hasNextPage,String endCursor) {}
    public record HistoryNode(String id,String attemptId,String kind,String status,String knownSubtotal,String completeAmount,
                              String sourceProjectionVersion,String sourceSnapshotId,String priceSnapshotId,String priceRevision,
                              String catalogRevisionId,String calculatorVersion,String sourceType,String evidenceReference,
                              String reason,String actorId,String recordedAt,List<String> reasons,boolean current,String currency,
                              String priceSelectionState,String priceRatesJson,String priceSourceReference,String priceEffectiveFrom,String priceEffectiveUntil,String sortTime,boolean candidate) {}
    public record CatalogEntry(String id,String provider,String endpoint,String model,String channel,String priceRevision,
                               String effectiveFrom,String effectiveUntil,String ratesJson) {}
    public record CatalogNode(String id,String generation,String sourceReference,String sourceRevision,String createdAt,List<CatalogEntry> entries) {}
    public record HistoryConnection(List<HistoryNode> nodes,PageInfo pageInfo,String currentRevisionId) {}
    public record CatalogConnection(List<CatalogNode> nodes,PageInfo pageInfo) {}

    @org.springframework.transaction.annotation.Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public UsageReadModel.Connection<UsageReadModel.Attempt> reviewAttempts(String workspaceId,Boolean simulation,Integer first,String after) {
        String actor=authorize(workspaceId); int limit=limit(first); boolean simulated=Boolean.TRUE.equals(simulation);
        String scope="review\0"+workspaceId+"\0"+actor+"\0"+simulated;
        Cursor cursor=after==null?null:decode(after,scope);
        if(cursor!=null && !Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from provider_attempt where workspace_id=? and simulation=? and id=? and prepared_at=?)",Boolean.class,workspaceId,simulated,cursor.id(),Timestamp.from(parse(cursor.time()))))) throw new Failure("INVALID_CURSOR");
        String sql="""
            select a.* from provider_attempt a
              left join usage_cost_current c on c.attempt_id=a.id
              left join usage_cost_estimate e on e.attempt_id=a.id and e.id=c.estimate_id
             where a.workspace_id=? and a.simulation=? and a.execution_outcome not in ('IN_PROGRESS','NOT_DISPATCHED')
               and (e.id is null or e.source_version<>a.projection_version or e.status<>'COMPLETE' or a.attempt_coverage<>'VERIFIED')
            """;
        var mapper=(org.springframework.jdbc.core.RowMapper<UsageReadModel.Attempt>)(rs,n)->UsageReadModel.attempt(rs,true);
        var rows=cursor==null?jdbc.query(sql+" order by a.prepared_at desc,a.id desc limit ?",mapper,workspaceId,simulated,limit+1)
                :jdbc.query(sql+" and (a.prepared_at,a.id)<(?,?) order by a.prepared_at desc,a.id desc limit ?",mapper,workspaceId,simulated,Timestamp.from(parse(cursor.time())),cursor.id(),limit+1);
        boolean more=rows.size()>limit;
        var selected=rows.subList(0,Math.min(limit,rows.size()));
        var views=costs.readCosts(selected.stream().map(UsageReadModel.Attempt::id).toList());
        var nodes=selected.stream().map(a->a.withReportCost(views.get(a.id()))).toList();
        String next=nodes.isEmpty()?null:encode(new Cursor(scope,nodes.getLast().preparedAt(),nodes.getLast().id()));
        return new UsageReadModel.Connection<>(nodes,new UsageReadModel.PageInfo(more,next));
    }

    @org.springframework.transaction.annotation.Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public HistoryConnection history(String workspaceId,String attemptId,Integer first,String after) {
        String actor=authorize(workspaceId); int limit=limit(first);
        if(!Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from provider_attempt where id=? and workspace_id=?)",Boolean.class,attemptId,workspaceId))) throw new Failure("USAGE_ATTEMPT_ACCESS_DENIED");
        String scope="history\0"+workspaceId+"\0"+attemptId+"\0"+actor;
        Cursor cursor=after==null?null:decode(after,scope);
        if(cursor!=null && !Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from usage_cost_estimate where attempt_id=? and id=? and created_at=?)",Boolean.class,attemptId,cursor.id(),Timestamp.from(parse(cursor.time()))))) throw new Failure("INVALID_CURSOR");
        String sql="""
            select e.id,e.attempt_id,coalesce(a.kind,'AUTO_DISPATCH'),e.status,e.known_subtotal,e.complete_amount,e.source_version,
                   e.source_snapshot_id,e.price_snapshot_id,p.price_revision,p.catalog_revision_id,e.calculator_version,
                   a.source_type,a.evidence_reference,a.reason,a.actor_id,e.created_at,e.details::text,coalesce(c.estimate_id=e.id,false),
                   p.selection_state,p.rates::text,p.source_reference,p.effective_from,p.effective_until,a.recorded_at,e.publication_kind
              from usage_cost_estimate e left join usage_cost_revision_audit a on a.attempt_id=e.attempt_id and a.estimate_id=e.id
              left join usage_price_snapshot p on p.attempt_id=e.attempt_id and p.id=e.price_snapshot_id
              left join usage_cost_current c on c.attempt_id=e.attempt_id
             where e.attempt_id=?
            """;
        var mapper=(org.springframework.jdbc.core.RowMapper<HistoryNode>)(rs,n)->new HistoryNode(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),decimal(rs.getBigDecimal(5)),decimal(rs.getBigDecimal(6)),Long.toString(rs.getLong(7)),rs.getString(8),rs.getString(9),rs.getString(10),rs.getString(11),rs.getString(12),rs.getString(13),rs.getString(14),rs.getString(15),rs.getString(16),instant(rs.getTimestamp(25)==null?rs.getTimestamp(17):rs.getTimestamp(25)),reasons(rs.getString(18)),rs.getBoolean(19),"USD",rs.getString(20),rs.getString(21),rs.getString(22),instant(rs.getTimestamp(23)),instant(rs.getTimestamp(24)),instant(rs.getTimestamp(17)),"AUTO_CANDIDATE".equals(rs.getString(26)));
        List<HistoryNode> rows=cursor==null?jdbc.query(sql+" order by e.created_at desc,e.id desc limit ?",mapper,attemptId,limit+1):jdbc.query(sql+" and (e.created_at,e.id)<(?,?) order by e.created_at desc,e.id desc limit ?",mapper,attemptId,Timestamp.from(parse(cursor.time())),cursor.id(),limit+1);
        boolean more=rows.size()>limit; var nodes=List.copyOf(rows.subList(0,Math.min(limit,rows.size())));
        var current=jdbc.query("select estimate_id from usage_cost_current where attempt_id=?",(rs,n)->rs.getString(1),attemptId);
        return new HistoryConnection(nodes,new PageInfo(more,nodes.isEmpty()?null:encode(new Cursor(scope,nodes.getLast().sortTime(),nodes.getLast().id()))),current.isEmpty()?null:current.getFirst());
    }
    public CatalogConnection catalog(String workspaceId,Integer first,String after) {
        String actor=authorize(workspaceId); int limit=limit(first); String scope="catalog\0"+workspaceId+"\0"+actor;
        Cursor cursor=after==null?null:decode(after,scope);
        if(cursor!=null && !Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from pricing_catalog_revision where id=? and created_at=?)",Boolean.class,cursor.id(),Timestamp.from(parse(cursor.time()))))) throw new Failure("INVALID_CURSOR");
        String sql="select id,generation,source_reference,source_revision,created_at from pricing_catalog_revision";
        var mapper=(org.springframework.jdbc.core.RowMapper<CatalogNode>)(rs,n)->new CatalogNode(rs.getString(1),Long.toString(rs.getLong(2)),rs.getString(3),rs.getString(4),rs.getTimestamp(5).toInstant().toString(),List.of());
        List<CatalogNode> rows=cursor==null?jdbc.query(sql+" order by created_at desc,id desc limit ?",mapper,limit+1):jdbc.query(sql+" where (created_at,id)<(?,?) order by created_at desc,id desc limit ?",mapper,Timestamp.from(parse(cursor.time())),cursor.id(),limit+1);
        boolean more=rows.size()>limit; var nodes=rows.subList(0,Math.min(limit,rows.size())).stream().map(c->new CatalogNode(c.id(),c.generation(),c.sourceReference(),c.sourceRevision(),c.createdAt(),catalogEntries(c.id()))).toList();
        return new CatalogConnection(nodes,new PageInfo(more,nodes.isEmpty()?null:encode(new Cursor(scope,nodes.getLast().createdAt(),nodes.getLast().id()))));
    }
    private List<CatalogEntry> catalogEntries(String catalogId) {
        return jdbc.query("select id,provider,endpoint,model,channel,price_revision,effective_from,effective_until,rates::text from pricing_catalog_entry where revision_id=? order by provider,endpoint,model,channel,effective_from,id",
                (rs,n)->new CatalogEntry(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getString(6),rs.getTimestamp(7).toInstant().toString(),rs.getTimestamp(8).toInstant().toString(),rs.getString(9)),catalogId);
    }
    private String authorize(String workspaceId) {
        String actor=users.getCurrentUserId();
        try { workspaces.requireMember(workspaceId,actor); } catch(WorkspaceAccessDeniedException e) { throw new Failure("WORKSPACE_ACCESS_DENIED"); }
        if(!workspaces.isManager(workspaceId,actor)) throw new Failure("WORKSPACE_MANAGER_REQUIRED"); return actor;
    }
    private static int limit(Integer first) { int n=first==null?20:first; if(n<1||n>100) throw new Failure("INVALID_PAGE_SIZE"); return n; }
    private static String decimal(java.math.BigDecimal n) { return n==null?null:n.toPlainString(); }
    private static String instant(Timestamp value) { return value==null?null:value.toInstant().toString(); }
    private List<String> reasons(String details) { try { var result=new java.util.ArrayList<String>(); json.readTree(details).path("reasons").forEach(n->result.add(n.asText())); return List.copyOf(result); } catch(Exception e) { throw new IllegalStateException("Invalid estimate details",e); } }
    private static Instant parse(String value) { try {return Instant.parse(value);} catch(RuntimeException e) {throw new Failure("INVALID_CURSOR");} }
    private byte[] mac(byte[] data) throws Exception { var mac=Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(key,"HmacSHA256")); return mac.doFinal(data); }
    private String encode(Cursor c) { try { byte[] value=json.writeValueAsBytes(c); var b=Base64.getUrlEncoder().withoutPadding(); return b.encodeToString(value)+"."+b.encodeToString(mac(value)); } catch(Exception e) { throw new IllegalStateException(e); } }
    private Cursor decode(String value,String scope) {
        try { if(value.length()>4096) throw new IllegalArgumentException(); String[] parts=value.split("\\.",-1); if(parts.length!=2) throw new IllegalArgumentException(); var b=Base64.getUrlDecoder(); byte[] data=b.decode(parts[0]); if(!MessageDigest.isEqual(mac(data),b.decode(parts[1]))) throw new IllegalArgumentException(); Cursor c=json.readValue(data,Cursor.class); if(!scope.equals(c.scope()) || c.id()==null || c.time()==null || !value.equals(encode(c))) throw new IllegalArgumentException(); return c; }
        catch(Exception e) { throw new Failure("INVALID_CURSOR"); }
    }
}
