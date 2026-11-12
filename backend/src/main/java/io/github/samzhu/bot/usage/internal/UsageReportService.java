package io.github.samzhu.bot.usage.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.samzhu.bot.identity.CurrentUserProvider;
import io.github.samzhu.bot.usage.UsageCostOperations;
import io.github.samzhu.bot.workspace.WorkspaceAccessDeniedException;
import io.github.samzhu.bot.workspace.WorkspaceOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

@Service
public class UsageReportService {
    private static final ZoneId ZONE=ZoneId.of("Asia/Taipei");
    private final JdbcTemplate jdbc;
    private final CurrentUserProvider user;
    private final WorkspaceOperations workspace;
    private final UsageCostOperations costs;
    private final Clock clock;
    private final ObjectMapper json=new ObjectMapper();
    // A process-local signing key prevents tampering with the fixed window; after restart,
    // previous cursors intentionally fail INVALID_CURSOR and the client reloads page one.
    private final byte[] cursorKey=new byte[32];
    public UsageReportService(JdbcTemplate jdbc, CurrentUserProvider user, WorkspaceOperations workspace,
                              UsageCostOperations costs, Clock clock) {
        this.jdbc=jdbc; this.user=user; this.workspace=workspace; this.costs=costs; this.clock=clock;
        new SecureRandom().nextBytes(cursorKey);
    }
    public record Filter(String workspaceId, boolean manager, String actor, int days, String provider, String model,
                         boolean simulation, String memberId, boolean unassigned) {}
    private record Cursor(String scope, String from, String to, String anchorTime, String anchorId) {}
    private record Row(UsageReadModel.Attempt attempt, Instant started, String actor, String schema, String raw) {}
    public record Summary(String status, String currency, String knownSubtotal, String completeTotal,
                          String unknownCount, String coverageUnknownCount, String recordedAttemptCount,
                          String unassignedCount, String sharePercent) {}
    public record Metric(String knownValue, String completeValue, String unknownCount, String unit, String meaning) {}
    public record NativeGroup(String provider, String endpoint, String model, String schemaVersion,
                              Metric input, Metric output, Metric cacheRead, Metric cacheWrite) {}
    public record Daily(String day, String knownSubtotal, String completeTotal, String unknownCount,
                        String recordedAttemptCount, Integer visualRatioPercent) {}
    public record Report(String workspaceId, int periodDays, String from, String to, String asOf, String timeZone,
                         boolean simulation, Summary summary, List<NativeGroup> nativeGroups, List<Daily> daily,
                         UsageReadModel.Connection<UsageReadModel.Attempt> attempts) {}
    public static class Invalid extends RuntimeException { public Invalid(String code) { super(code); } }
    public static class ManagerRequired extends RuntimeException {}

    @Transactional(readOnly=true, isolation=Isolation.REPEATABLE_READ)
    public Report report(String workspaceId, boolean manager, Integer periodDays, String provider, String model,
                         Boolean simulation, Integer first, String after, String memberId, Boolean unassignedOnly) {
        String actor=user.getCurrentUserId();
        workspace.requireMember(workspaceId,actor);
        if (manager && !workspace.isManager(workspaceId,actor)) throw new WorkspaceAccessDeniedException();
        int days=periodDays==null?7:periodDays;
        if (days!=7 && days!=30) throw new Invalid("INVALID_REPORT_PERIOD");
        int limit=first==null?20:first;
        if (limit<1 || limit>100) throw new Invalid("INVALID_PAGE_SIZE");
        boolean unassigned=Boolean.TRUE.equals(unassignedOnly);
        if (manager && memberId!=null && unassigned) throw new Invalid("INVALID_REPORT_FILTER");
        if (!manager) { memberId=null; unassigned=false; }
        Filter f=new Filter(workspaceId,manager,actor,days,provider,model,Boolean.TRUE.equals(simulation),memberId,unassigned);
        Instant asOf=clock.instant();
        Instant to=asOf;
        Instant from=LocalDate.ofInstant(to,ZONE).minusDays(days-1).atStartOfDay(ZONE).toInstant();
        Cursor cursor=null;
        if (after!=null) {
            cursor=decode(after);
            if (!scope(f).equals(cursor.scope())) throw new Invalid("INVALID_CURSOR");
            try { from=Instant.parse(cursor.from()); to=Instant.parse(cursor.to()); Instant.parse(cursor.anchorTime()); }
            catch (RuntimeException ex) { throw new Invalid("INVALID_CURSOR"); }
            if (!from.isBefore(to) || to.isAfter(asOf) || !from.equals(LocalDate.ofInstant(to,ZONE).minusDays(days-1).atStartOfDay(ZONE).toInstant())) throw new Invalid("INVALID_CURSOR");
        }
        String sql="""
            select a.*,s.schema_version as report_schema,s.raw_usage::text as report_raw
              from provider_attempt a left join usage_snapshot s on s.id=a.projection_snapshot_id
                 and s.attempt_id=a.id and a.projection_state='VALID'
             where a.workspace_id=? and a.started_at>=? and a.started_at<? and a.simulation=?
            """;
        List<Object> args=new ArrayList<>(List.of(workspaceId,Timestamp.from(from),Timestamp.from(postgresExclusiveUpperBound(to)),f.simulation()));
        if (!manager) { sql+=" and a.actor_id=?"; args.add(actor); }
        else if (unassigned) sql+=" and a.actor_id is null";
        else if (memberId!=null) { sql+=" and a.actor_id=?"; args.add(memberId); }
        if (provider!=null) { sql+=" and a.provider=?"; args.add(provider); }
        if (model!=null) { sql+=" and a.model=?"; args.add(model); }
        sql+=" order by a.prepared_at desc,a.id desc";
        var rows=jdbc.query(sql,(rs,n)->new Row(UsageReadModel.attempt(rs,manager),rs.getTimestamp("started_at").toInstant(),rs.getString("actor_id"),rs.getString("report_schema"),rs.getString("report_raw")),args.toArray());
        int offset=0;
        if (cursor!=null) {
            final Cursor c=cursor;
            int idx=-1;
            for (int i=0;i<rows.size();i++) if (rows.get(i).attempt().id().equals(c.anchorId()) && rows.get(i).attempt().preparedAt().equals(c.anchorTime())) {idx=i;break;}
            if (idx<0) throw new Invalid("INVALID_CURSOR");
            offset=idx+1;
        }
        var ids=rows.stream().map(r->r.attempt().id()).toList();
        Map<String,UsageCostOperations.Cost> allCosts=new HashMap<>();
        for (int i=0;i<ids.size();i+=500) allCosts.putAll(costs.readCosts(ids.subList(i,Math.min(ids.size(),i+500))));
        var total=new Totals();
        Map<LocalDate,Totals> daily=new LinkedHashMap<>();
        LocalDate day=LocalDate.ofInstant(from,ZONE), last=LocalDate.ofInstant(to,ZONE);
        while (!day.isAfter(last)) { daily.put(day,new Totals()); day=day.plusDays(1); }
        Map<String,Group> groups=new LinkedHashMap<>();
        for (var row:rows) {
            var cost=allCosts.get(row.attempt().id());
            total.add(row,cost); daily.get(LocalDate.ofInstant(row.started(),ZONE)).add(row,cost);
            String schema=row.schema()==null?"UNKNOWN":row.schema();
            String key=String.join("\u0000",row.attempt().provider(),row.attempt().endpoint(),row.attempt().model(),schema);
            groups.computeIfAbsent(key,k->new Group(row.attempt().provider(),row.attempt().endpoint(),row.attempt().model(),schema)).add(row);
        }
        BigDecimal max=daily.values().stream().map(t->t.known).filter(Objects::nonNull).max(BigDecimal::compareTo).orElse(null);
        var buckets=daily.entrySet().stream().map(e->e.getValue().daily(e.getKey(),max)).toList();
        int end=Math.min(rows.size(),offset+limit);
        var page=rows.subList(offset,end).stream()
                .map(row -> row.attempt().withReportCost(snapshotCost(allCosts.get(row.attempt().id())))).toList();
        String next=page.isEmpty()?null:encode(new Cursor(scope(f),from.toString(),to.toString(),page.getLast().preparedAt(),page.getLast().id()));
        var conn=new UsageReadModel.Connection<>(page,new UsageReadModel.PageInfo(end<rows.size(),next));
        return new Report(workspaceId,days,from.toString(),to.toString(),asOf.toString(),ZONE.toString(),f.simulation(),total.summary(),groups.values().stream().map(Group::view).toList(),buckets,conn);
    }
    public UsageReadModel.Attempt managerAttempt(String workspaceId,String id) {
        String actor=user.getCurrentUserId(); workspace.requireMember(workspaceId,actor);
        if (!workspace.isManager(workspaceId,actor)) throw new WorkspaceAccessDeniedException();
        return jdbc.query("select * from provider_attempt where workspace_id=? and id=?",(rs,n)->UsageReadModel.attempt(rs,true),workspaceId,id).stream().findFirst().orElse(null);
    }
    // PostgreSQL stores timestamps to microseconds; ceiling an exclusive nanosecond
    // boundary preserves exactly which representable timestamps are strictly before it.
    static Instant postgresExclusiveUpperBound(Instant value) {
        int remainder=value.getNano()%1_000;
        return remainder==0?value:value.plusNanos(1_000-remainder);
    }
    private static UsageCostOperations.Cost snapshotCost(UsageCostOperations.Cost c) {
        if (c==null) return null;
        var lines=c.lineItems().stream().map(l -> new UsageCostOperations.LineItem(l.category(),l.nativeQuantity(),l.unit(),l.meaning(),l.rate(),l.rateBasis(),l.exactAmount(),l.storedAmount(),List.copyOf(l.reasons()))).toList();
        return new UsageCostOperations.Cost(c.attemptId(),c.status(),c.knownSubtotal(),c.completeAmount(),c.currency(),c.simulation(),c.needsReprice(),c.sourceProjectionVersion(),c.estimateSourceVersion(),c.currentRevision(),c.priceRevision(),c.priceSnapshotId(),c.priceSourceReference(),c.priceEffectiveFrom(),c.priceEffectiveUntil(),c.calculatorVersion(),c.sourceSnapshotId(),c.sourceLayer(),c.schemaVersion(),c.adapterVersion(),c.captureVersion(),c.captureCompleteness(),c.semantic(),c.ordinal(),List.copyOf(c.reasons()),lines);
    }
    private static String dec(BigDecimal x) { return x==null?null:x.toPlainString(); }
    private static final class Totals {
        long count,unknown,coverage,unassigned; BigDecimal known;
        void add(Row r,UsageCostOperations.Cost c) {
            count++; if(r.actor()==null) unassigned++;
            boolean covered="VERIFIED".equals(r.attempt().attemptCoverage()); if(!covered) coverage++;
            boolean complete=c!=null && !c.needsReprice() && "COMPLETE".equals(c.status()) && c.completeAmount()!=null;
            if(!complete || !covered) unknown++;
            String value=c==null || c.needsReprice()?null:complete?c.completeAmount():c.knownSubtotal();
            if(value!=null) { BigDecimal x=new BigDecimal(value); known=known==null?x:known.add(x); }
        }
        Summary summary() { return new Summary(count==0?"EMPTY":unknown==0?"COMPLETE":known==null?"UNKNOWN":"PARTIAL","USD",dec(known),unknown==0&&count>0?dec(known):null,Long.toString(unknown),Long.toString(coverage),Long.toString(count),Long.toString(unassigned),null); }
        Daily daily(LocalDate day,BigDecimal max) {
            Integer ratio=known==null||max==null||max.signum()<=0?null:known.multiply(BigDecimal.valueOf(100)).divide(max,0,RoundingMode.HALF_UP).intValueExact();
            return new Daily(day.toString(),dec(known),unknown==0&&count>0?dec(known):null,Long.toString(unknown),Long.toString(count),ratio);
        }
    }
    private final class Group {
        final String provider,endpoint,model,schema; final MetricSum[] metrics={new MetricSum(),new MetricSum(),new MetricSum(),new MetricSum()};
        Group(String p,String e,String m,String s){provider=p;endpoint=e;model=m;schema=s;}
        void add(Row row) {
            String[] keys;
            if (("GOOGLE".equals(provider)||"FAKE_GEMINI".equals(provider)) && "generateContent".equals(endpoint))
                keys=new String[]{"promptTokenCount","candidatesTokenCount","cachedContentTokenCount",null};
            else if ("FAKE_OPENAI".equals(provider) && "responses-fixture-ttl".equals(endpoint) && "pricing-fixture-v1".equals(schema))
                keys=new String[]{"input_tokens","output_tokens","input_tokens_details.cached_tokens","input_tokens_details.cache_write_tokens"};
            else if ("FAKE_ANTHROPIC".equals(provider) && "messages".equals(endpoint) && "pricing-fixture-v1".equals(schema))
                keys=new String[]{"input_tokens","output_tokens","cache_read_input_tokens","cache_creation_input_tokens"};
            else if ("FAKE".equals(provider)) keys=new String[]{"input","output","cacheRead","cacheWrite"};
            else keys=new String[]{null,null,null,null};
            JsonNode raw=null; try { if(row.raw()!=null) raw=json.readTree(row.raw()); } catch(Exception ignored) {}
            for(int i=0;i<4;i++) {
                JsonNode value=raw;
                if(keys[i]==null) value=null;
                else for(String key:keys[i].split("\\.")) value=value==null?null:value.get(key);
                if(value==null || !value.isIntegralNumber() || value.bigIntegerValue().signum()<0) metrics[i].missing++;
                else metrics[i].add(value.bigIntegerValue());
            }
        }
        NativeGroup view(){return new NativeGroup(provider,endpoint,model,schema,metrics[0].view(meaning(0)),metrics[1].view(meaning(1)),metrics[2].view(meaning(2)),metrics[3].view(meaning(3)));}
        String meaning(int i){
            if ("GOOGLE".equals(provider)||"FAKE_GEMINI".equals(provider)) return new String[]{"Gemini promptTokenCount，包含已快取的輸入","Gemini candidatesTokenCount，不包含獨立的 thoughtsTokenCount","Gemini cachedContentTokenCount，為 prompt 的子集","Provider field not available"}[i];
            if ("FAKE_ANTHROPIC".equals(provider)) return new String[]{"Anthropic input_tokens，不包含 cache read/write","Anthropic output_tokens","Anthropic cache_read_input_tokens","Anthropic cache_creation_input_tokens"}[i];
            if ("FAKE_OPENAI".equals(provider)) return new String[]{"OpenAI input_tokens，包含 cache read/write","OpenAI output_tokens，包含 reasoning","OpenAI cached_tokens，為 input 子集","OpenAI cache_write_tokens，為 input 子集"}[i];
            return provider+" native "+new String[]{"input","output","cacheRead","cacheWrite"}[i];
        }
    }
    private static final class MetricSum {
        BigInteger sum; long missing;
        void add(BigInteger n){sum=sum==null?n:sum.add(n);}
        Metric view(String meaning){String value=sum==null?null:sum.toString();return new Metric(value,missing==0?value:null,Long.toString(missing),"token",meaning);}
    }
    private String scope(Filter f) {
        try { return Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(f))); }
        catch(Exception e){throw new IllegalStateException(e);}
    }
    private String encode(Cursor c) {
        try {byte[] data=json.writeValueAsBytes(c);return Base64.getUrlEncoder().withoutPadding().encodeToString(data)+"."+Base64.getUrlEncoder().withoutPadding().encodeToString(mac(data));}
        catch(Exception e){throw new IllegalStateException(e);}
    }
    private Cursor decode(String value) {
        try {
            if(value.length()>4096) throw new IllegalArgumentException(); String[] p=value.split("\\.",-1); if(p.length!=2) throw new IllegalArgumentException();
            var decoder=Base64.getUrlDecoder(); byte[] data=decoder.decode(p[0]), sig=decoder.decode(p[1]);
            if(!MessageDigest.isEqual(sig,mac(data))) throw new IllegalArgumentException();
            Cursor c=json.readValue(data,Cursor.class); if(c.scope()==null||c.from()==null||c.to()==null||c.anchorTime()==null||c.anchorId()==null) throw new IllegalArgumentException();
            if(!encode(c).equals(value)) throw new IllegalArgumentException(); return c;
        } catch(Exception e){throw new Invalid("INVALID_CURSOR");}
    }
    private byte[] mac(byte[] data) throws Exception {Mac m=Mac.getInstance("HmacSHA256");m.init(new SecretKeySpec(cursorKey,"HmacSHA256"));return m.doFinal(data);}
}
