package io.github.gavinruff007.torchnode.storage;

import io.github.gavinruff007.torchnode.discovery.DiscoveryEvidenceBatch;
import io.github.gavinruff007.torchnode.topology.SqliteReadDeadline;
import java.nio.file.Path;
import java.sql.*;
import java.time.*;
import java.util.*;

/** Read-only B4 repository, not an HTTP API. All ordering has a unique immutable tie-breaker. */
public final class DiscoveryEvidenceRepository {
    public static final int MAX_PAGE=200,MAX_CANDIDATES=50000,MAX_DETAILS=256;
    public record Query(Instant start,Instant end,String observer,String protocol,String identity,int limit) {
        public Query {
            if(start==null || end==null || !start.isBefore(end) || Duration.between(start,end).compareTo(Duration.ofDays(31))>0)throw new IllegalArgumentException("Positive UTC window of at most 31 days required");
            if(observer!=null)observer=UUID.fromString(observer).toString();
            if(protocol==null || !Set.of("all","discv4","discv5").contains(protocol))throw new IllegalArgumentException("Invalid protocol");
            if(identity!=null && !identity.matches("[0-9a-f]{128}"))throw new IllegalArgumentException("Canonical public identity required");
            if(limit<1 || limit>MAX_PAGE)throw new IllegalArgumentException("Page limit 1..200 required");
        }
        String signature(){return DiscoveryEvidenceBatch.hash(DiscoveryEvidenceBatch.json(Arrays.asList(start.toString(),end.toString(),observer,protocol,identity)));}
    }
    public record Cursor(long highWatermark,String afterAt,String afterId,String querySignature) {
        public Cursor {if(highWatermark<0 || afterId==null || !afterId.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("Invalid evidence cursor");afterAt=DiscoveryEvidenceStore.clock(Instant.parse(afterAt));}
    }
    public record Coverage(boolean queryComplete,boolean displayedSubset,boolean relationshipMeasurementAvailable,
                           boolean collectionComplete,boolean networkCoverageKnown,String countingUnit,String consistency) {}
    public record Page(List<Map<String,Object>> observations,Cursor nextCursor,Coverage coverage) {}
    public record Details(Map<String,Object> evidence,List<Map<String,Object>> validations,List<Map<String,Object>> candidates,
                          List<Map<String,Object>> rejections,boolean validationsTruncated,boolean candidatesTruncated) {}
    public record CandidateCounts(Map<String,Long> reasons,Long total,boolean measurementAvailable,boolean queryComplete,
                                  boolean aggregateTruncated,int safetyLimit,boolean excludesUnknownTimestamps,String denominator) {}
    public static final class ScopeLimit extends SQLException {
        public final int safetyLimit=MAX_CANDIDATES;
        public final boolean queryComplete=false,aggregateReturned=false;
        public ScopeLimit(){super("Candidate scope exceeds 50,000 records; narrow filters. No aggregate returned.");}
    }
    private final String path;private final Duration budget;
    public DiscoveryEvidenceRepository(String path){this(path,Duration.ofSeconds(5));}
    public DiscoveryEvidenceRepository(String path,Duration budget){if(budget==null || budget.isNegative() || budget.isZero() || budget.compareTo(Duration.ofSeconds(5))>0)throw new IllegalArgumentException("Invalid SQLite deadline");this.path=path;this.budget=budget;}
    private final class Read implements AutoCloseable {
        final Connection db;final SqliteReadDeadline handler;final long end=System.nanoTime()+budget.toNanos();
        Read()throws SQLException {
            db=DriverManager.getConnection("jdbc:sqlite:"+Path.of(path).toAbsolutePath().toUri()+"?mode=ro");
            try {
                try(var s=db.createStatement()){s.execute("PRAGMA query_only=ON");s.execute("PRAGMA busy_timeout="+Math.max(1,Math.min(500,budget.toMillis())));}
                handler=new SqliteReadDeadline(db,budget);db.setAutoCommit(false);
            }catch(SQLException e){db.close();throw e;}
        }
        void check()throws SQLException {if(Thread.currentThread().isInterrupted() || System.nanoTime()-end>=0)throw new SQLTimeoutException("Discovery evidence query deadline exceeded");}
        @Override public void close()throws SQLException{try{handler.close();}finally{db.close();}}
    }
    private record Filter(String sql,List<Object> values) {}
    private static Filter filter(Query query,String prefix) {
        String sql=prefix+"observed_at>=? AND "+prefix+"observed_at<?";
        var values=new ArrayList<Object>(List.of(DiscoveryEvidenceStore.clock(query.start()),DiscoveryEvidenceStore.clock(query.end())));
        if(query.observer()!=null){sql+=" AND "+prefix+"observer_id=?";values.add(query.observer());}
        if(!query.protocol().equals("all")){sql+=" AND "+prefix+"protocol=?";values.add(query.protocol());}
        if(query.identity()!=null){sql+=" AND ("+prefix+"source_identity=? OR "+prefix+"target_identity=?)";values.add(query.identity());values.add(query.identity());}
        return new Filter(sql,values);
    }
    private static List<Map<String,Object>> rows(Connection db,String sql,List<?> values)throws SQLException {
        try(var q=db.prepareStatement(sql)) {
            for(int i=0;i<values.size();i++)q.setObject(i+1,values.get(i));
            var result=new ArrayList<Map<String,Object>>();
            try(var r=q.executeQuery()){var meta=r.getMetaData();while(r.next()){var row=new LinkedHashMap<String,Object>();for(int i=1;i<=meta.getColumnCount();i++)row.put(meta.getColumnLabel(i),r.getObject(i));result.add(Collections.unmodifiableMap(row));}}
            return List.copyOf(result);
        }
    }
    public Page relationships(Query query,Cursor cursor)throws SQLException {
        if(cursor!=null && !query.signature().equals(cursor.querySignature()))throw new IllegalArgumentException("Cursor belongs to another filter/window");
        try(var read=new Read()) {
            long high=cursor==null?((Number)rows(read.db,"SELECT COALESCE(MAX(seq),0) n FROM discovery_relationship_observations",List.of()).getFirst().get("n")).longValue():cursor.highWatermark();
            var f=filter(query,"o.");String where=f.sql()+" AND o.seq<=? AND NOT EXISTS(SELECT 1 FROM discovery_evidence_rejections r WHERE r.evidence_id=o.evidence_id)";
            var args=new ArrayList<Object>(f.values());args.add(high);
            if(cursor!=null){where+=" AND (o.observed_at>? OR (o.observed_at=? AND o.id>?))";args.add(cursor.afterAt());args.add(cursor.afterAt());args.add(cursor.afterId());}
            args.add(query.limit()+1);
            var result=rows(read.db,"SELECT o.* FROM discovery_relationship_observations o WHERE "+where+" ORDER BY o.observed_at,o.id LIMIT ?",args);
            boolean more=result.size()>query.limit();var page=more?List.copyOf(result.subList(0,query.limit())):result;
            Cursor next=null;if(more){var last=page.getLast();next=new Cursor(high,(String)last.get("observed_at"),(String)last.get("id"),query.signature());}
            boolean available=!page.isEmpty() || available(read.db,query,true);
            read.check();return new Page(page,next,new Coverage(true,more,available,false,false,"persisted REPORTED_NEIGHBOR observations","Insert high-watermark; concurrent evidence rejection can remove invalidated observations. No global-network coverage claim."));
        }
    }
    private static boolean available(Connection db,Query query,boolean attributable)throws SQLException {
        String sql="e.observed_at>=? AND e.observed_at<?";
        var args=new ArrayList<Object>(List.of(DiscoveryEvidenceStore.clock(query.start()),DiscoveryEvidenceStore.clock(query.end())));
        if(attributable)sql+=" AND e.classification='ATTRIBUTABLE_EXCHANGE' AND NOT EXISTS(SELECT 1 FROM discovery_evidence_rejections r WHERE r.evidence_id=e.id)";
        if(query.observer()!=null){sql+=" AND d.observer_id=?";args.add(query.observer());}
        if(!query.protocol().equals("all")){sql+=" AND e.protocol=?";args.add(query.protocol());}
        if(query.identity()!=null){sql+=" AND EXISTS(SELECT 1 FROM discovery_record_evidence v WHERE v.evidence_id=e.id AND (v.source_identity=? OR v.target_identity=?))";args.add(query.identity());args.add(query.identity());}
        return !rows(db,"SELECT 1 FROM discovery_exchange_evidence e LEFT JOIN discovery_runs d ON d.id=e.run_id WHERE "+sql+" LIMIT 1",args).isEmpty();
    }
    public Optional<Details> evidence(String id)throws SQLException {
        if(id==null || !id.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("Stable evidence ID required");
        try(var read=new Read()) {
            var result=rows(read.db,"SELECT * FROM discovery_exchange_evidence WHERE id=?",List.of(id));
            if(result.isEmpty()){read.check();return Optional.empty();}
            var validations=rows(read.db,"SELECT * FROM discovery_record_evidence WHERE evidence_id=? ORDER BY packet_index,record_index LIMIT ?",List.of(id,MAX_DETAILS+1));
            var candidates=rows(read.db,"SELECT * FROM discovery_candidates WHERE evidence_id=? ORDER BY packet_index,record_index,id LIMIT ?",List.of(id,MAX_DETAILS+1));
            var rejected=rows(read.db,"SELECT * FROM discovery_evidence_rejections WHERE evidence_id=? ORDER BY reason LIMIT 129",List.of(id));
            read.check();return Optional.of(new Details(result.getFirst(),validations.subList(0,Math.min(MAX_DETAILS,validations.size())),candidates.subList(0,Math.min(MAX_DETAILS,candidates.size())),rejected,validations.size()>MAX_DETAILS,candidates.size()>MAX_DETAILS));
        }
    }
    public CandidateCounts candidateCounts(Query query)throws SQLException {
        try(var read=new Read()) {
            String sql="c.observed_at>=? AND c.observed_at<?";var args=new ArrayList<Object>(List.of(DiscoveryEvidenceStore.clock(query.start()),DiscoveryEvidenceStore.clock(query.end())));
            if(query.observer()!=null){sql+=" AND d.observer_id=?";args.add(query.observer());}
            if(!query.protocol().equals("all")){sql+=" AND e.protocol=?";args.add(query.protocol());}
            if(query.identity()!=null){sql+=" AND (c.source_identity=? OR c.target_identity=?)";args.add(query.identity());args.add(query.identity());}
            args.add(MAX_CANDIDATES+1);
            var result=rows(read.db,"WITH bounded AS (SELECT c.reason FROM discovery_candidates c JOIN discovery_exchange_evidence e ON e.id=c.evidence_id LEFT JOIN discovery_runs d ON d.id=e.run_id WHERE "+sql+" ORDER BY c.observed_at,c.id LIMIT ?) SELECT reason,COUNT(*) n FROM bounded GROUP BY reason ORDER BY reason",args);
            Map<String,Long> counts=new LinkedHashMap<>();long total=0;for(var row:result){long n=((Number)row.get("n")).longValue();total+=n;counts.put((String)row.get("reason"),n);}
            if(total>MAX_CANDIDATES)throw new ScopeLimit();
            boolean measured=total>0 || available(read.db,query,false);read.check();
            return new CandidateCounts(Collections.unmodifiableMap(counts),measured?total:null,measured,true,false,MAX_CANDIDATES,true,"persisted candidate/diagnostic records within [start,end), not network measurements; unknown-time records excluded");
        }
    }
}
