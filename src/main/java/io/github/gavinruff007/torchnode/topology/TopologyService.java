package io.github.gavinruff007.torchnode.topology;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.gavinruff007.torchnode.analysis.NetworkAnalytics.Scope;
import io.github.gavinruff007.torchnode.analysis.NetworkAnalytics.Mode;
import io.github.gavinruff007.torchnode.model.NodeIds;
import java.nio.file.Path;
import java.sql.*;
import java.time.*;
import java.util.*;

/** Phase A is a read-only identity evidence graph. Legacy records cannot supply complete edges. */
public final class TopologyService {
    public static final int MAX_RELATIONSHIP_OBSERVATIONS=2000, MAX_EDGES=400;
    public static final int MAX_NODES=200, MAX_OCCURRENCES=50000, MAX_EVIDENCE=20;
    private static final ObjectMapper JSON=new ObjectMapper();
    public static final String LIMITATION="Observer-local recorded identities, not the Ethereum network. Directed REPORTED_NEIGHBOR evidence means an authenticated responder reported a target ENR, never an active peer session. Legacy identities remain unlinked.";
    public record Query(Scope scope,String protocol,RelationshipObservation.Relation relation,String search,int limit,String after,String observer,Long highWatermark,String availability) {
        public Query(Scope scope,String protocol,RelationshipObservation.Relation relation,String search,int limit,String after,String observer,Long highWatermark){this(scope,protocol,relation,search,limit,after,observer,highWatermark,"all");}
        public Query(Scope scope,String protocol,RelationshipObservation.Relation relation,String search,int limit,String after,String observer){this(scope,protocol,relation,search,limit,after,observer,null);}
        public Query(Scope scope,String protocol,RelationshipObservation.Relation relation,String search,int limit,String after){this(scope,protocol,relation,search,limit,after,null,null);}
        public Query {
            if(!Set.of("all","attributable","identity-only").contains(availability))throw new IllegalArgumentException("Invalid evidence availability filter");
            if(highWatermark!=null && highWatermark<0)throw new IllegalArgumentException("Invalid high-watermark");
            if(observer!=null)observer=UUID.fromString(observer).toString();
            if(scope==null || scope.mode()!=Mode.WINDOW)throw new IllegalArgumentException("Topology requires a positive UTC window of at most 31 days");
            if(!Set.of("all","discv4","discv5").contains(Objects.requireNonNull(protocol)))throw new IllegalArgumentException("Protocol must be all, discv4 or discv5");
            search=search==null?"":search.toLowerCase(Locale.ROOT);
            if(!search.matches("[0-9a-f]{0,128}"))throw new IllegalArgumentException("Search is a canonical identity hex prefix, not an endpoint");
            if(limit<1 || limit>MAX_NODES)throw new IllegalArgumentException("Node limit must be 1..200");
            if(after!=null && !after.matches("[0-9a-f]{128}"))throw new IllegalArgumentException("Invalid identity cursor");
        }
    }
    public record EvidenceRef(String kind,String occurrenceId,String href) {}
    public record Vertex(String identity,Instant firstObservedAt,Instant lastObservedAt,long observationCount,
                         List<String> evidenceKinds,List<String> discoverySources,String validationStatus,EvidenceRef latestEvidence) {}
    public record Coverage(long sourceOccurrences,long excludedInvalidEnrs,long excludedInvalidIdentities,long excludedMalformedClocks,
                           int safetyLimitOccurrences,boolean safetyLimitReached,boolean queryComplete,boolean aggregateTruncated,
                           boolean networkCoverageKnown,boolean completeRelationshipAttribution,String reason) {}
    public record Summary(long observedIdentityCount,int returnedIdentityCount,long attributableEdgeCount,
                          boolean relationshipAnalyticsAvailable,Object degreeDistribution,Object connectedComponents,
                          Object repeatedRelationshipObservations,Object relationshipFreshness) {}
    public record Graph(Scope scope,String protocol,String relationFilter,String search,Instant generatedAt,String limitation,
                        String countingUnit,String denominatorMeaning,Summary summary,Coverage coverage,List<Vertex> nodes,
                        List<Map<String,Object>> edges,String nextCursor,boolean displayedSubset,String paginationConsistency,Map<String,String> relationCapabilities,long highWatermark,boolean relationshipEdgesTruncated,Map<String,Object> evidenceCoverage) {}
    public record Evidence(String kind,String occurrenceId,Instant observedAt,String provenance,String validationStatus,
                           String href,JsonNode payload,boolean payloadOmitted,boolean provenanceTruncated) {}
    public record EvidencePage(Scope scope,String identity,List<Evidence> observations,boolean truncated,String limitation) {}
    public record IdentityComparison(long newlyObserved,long reobserved,long notReobserved,List<String> newlyObservedIdentities,
                                     List<String> reobservedIdentities,List<String> notReobservedIdentities,boolean listsTruncated,String meaning) {}
    public record RelationshipComparison(boolean available,Object newlyObserved,Object reobserved,Object notReobserved,String reason) {}
    public record Comparison(Scope current,Scope baseline,IdentityComparison identities,RelationshipComparison relationships,String limitation,Map<String,Object> currentCoverage,Map<String,Object> baselineCoverage) {}
    public static final class ScopeLimit extends SQLException { public ScopeLimit(){super("Topology scope exceeds the bounded work budget (50,000 legacy occurrences, 2,000 relationship observations or 50,000 diagnostics); narrow the window");} }
    private final String databasePath;
    private final Duration executionBudget;
    public TopologyService(String databasePath){this(databasePath,Duration.ofSeconds(5));}
    public TopologyService(String databasePath,Duration executionBudget){
        if(executionBudget==null || executionBudget.isZero() || executionBudget.isNegative() || executionBudget.compareTo(Duration.ofSeconds(5))>0)throw new IllegalArgumentException("Invalid SQLite execution budget");
        this.databasePath=databasePath;this.executionBudget=executionBudget;
    }
    private record ReadSession(Connection db,SqliteReadDeadline deadline) implements AutoCloseable {
        @Override public void close()throws SQLException{try{deadline.close();}finally{db.close();}}
    }
    private ReadSession open() throws SQLException {
        var db=DriverManager.getConnection("jdbc:sqlite:"+Path.of(databasePath).toAbsolutePath().toUri()+"?mode=ro");
        try {
            try(var s=db.createStatement()) {
                s.execute("PRAGMA query_only=ON");s.execute("PRAGMA busy_timeout=3000");
                try(var r=s.executeQuery("SELECT MAX(version) FROM schema_migrations")){if(!r.next() || r.getInt(1)<5)throw new SQLException("Topology requires observation schema v5");}
            }
            db.setAutoCommit(false);return new ReadSession(db,new SqliteReadDeadline(db,executionBudget));
        } catch(SQLException e){db.close();throw e;}
    }
    // Same fractional-width normalization as NetworkAnalytics: exact nanosecond [start,end) boundaries.
    private static String clock(String c){return "substr("+c+",1,19)||CASE WHEN substr("+c+",20,1)='.' THEN substr(substr("+c+",21,instr(substr("+c+",21),'Z')-1)||'000000000',1,9) ELSE '000000000' END";}
    private static String key(Instant at){String s=at.toString();int dot=s.indexOf('.');return s.substring(0,19)+(dot<0?"000000000":(s.substring(dot+1,s.length()-1)+"000000000").substring(0,9));}
    private static String bound(String c){return c+" IS NOT NULL AND "+clock(c)+">=? AND "+clock(c)+"<?";}
    private static String trustedEnr(){return "CASE WHEN json_valid(evidence_json) THEN signature_validation='VALID' AND identity_comparison='MATCH' AND json_extract(evidence_json,'$.structurallyValid')=1 AND lower(json_extract(evidence_json,'$.record.identity.nodeId'))=lower(node_id) ELSE 0 END";}
    private static List<String> sources(Query query,boolean payload) {
        String body=payload?",substr(endpoints_json,1,8193) payload,substr(provenance,1,4097) provenance":",NULL payload,NULL provenance";
        var parts=new ArrayList<String>();
        String discovery="SELECT node_id,'discovery' kind,CAST(id AS TEXT) occurrence_id,observed_at stamp,source protocol,0 valid_enr"+body+" FROM discovery_observations WHERE source<>'ENR'";
        if(!query.protocol().equals("all"))discovery+=" AND source='"+query.protocol()+"'";
        parts.add(discovery+" AND "+bound("observed_at")+" AND lower(node_id) LIKE ?");
        if(query.protocol().equals("all")) {
            parts.add("SELECT node_id,'enr',CAST(id AS TEXT),observed_at,'ENR',CASE WHEN "+trustedEnr()+" THEN 1 ELSE -1 END"+
                    (payload?",substr(evidence_json,1,8193),substr(provenance,1,4097)":",NULL,NULL")+" FROM enr_observations WHERE "+bound("observed_at")+" AND lower(node_id) LIKE ?");
            parts.add("SELECT node_id,'inspection',id,started_at,'INSPECTION',0"+
                    (payload?",substr(e.evidence_json,1,8193),substr(r.trigger,1,4097)":",NULL,NULL")+" FROM inspection_runs r"+
                    (payload?" JOIN inspection_evidence e ON e.hash=r.evidence_hash":"")+" WHERE "+bound("started_at")+" AND lower(node_id) LIKE ?");
        }
        return parts;
    }
    private static PreparedStatement prepare(Connection db,String sql,Query query,int sourceCount) throws SQLException {
        var q=db.prepareStatement(sql);q.setQueryTimeout(5);
        int index=1;
        for(int i=0;i<sourceCount;i++){q.setString(index++,key(query.scope().startInclusive()));q.setString(index++,key(query.scope().endExclusive()));q.setString(index++,query.search()+"%");}
        return q;
    }
    private static String href(String identity,String kind,String occurrence) {
        if(kind.equals("relationship"))return "/api/v1/topology/evidence/"+occurrence;
        String base="/api/v1/identities/"+identity;
        occurrence=java.net.URLEncoder.encode(occurrence,java.nio.charset.StandardCharsets.UTF_8).replace("+","%20");
        return kind.equals("inspection")?base+"/runs/"+occurrence:base+"/evidence/"+kind+"/"+occurrence;
    }
    private static final class Accumulator {
        final String identity;Instant first,last;long count;boolean validated;EvidenceRef latest;
        final SortedSet<String> kinds=new TreeSet<>(),protocols=new TreeSet<>();
        Accumulator(String identity){this.identity=identity;}
        void add(Instant at,String kind,String protocol,String occurrence,boolean valid) {
            count++;validated|=valid;kinds.add(kind);if(kind.equals("discovery"))protocols.add(protocol);
            if(first==null || at.isBefore(first))first=at;
            var reference=new EvidenceRef(kind,occurrence,href(identity,kind,occurrence));
            if(last==null || at.isAfter(last) || at.equals(last) && reference.href().compareTo(latest.href())>0){last=at;latest=reference;}
        }
        Vertex vertex(){return new Vertex(identity,first,last,count,List.copyOf(kinds),List.copyOf(protocols),validated?"VALIDATED_ENR_IDENTITY":"RECORDED_IDENTITY_CLAIM",latest);}
    }
    private record Measured(SortedMap<String,Accumulator> nodes,Coverage coverage,List<Map<String,Object>> edges,long highWatermark,boolean available,Map<String,Object> diagnostics) {}
    private Measured measure(Connection db,Query query) throws SQLException {
        long high=query.highWatermark()==null?0:query.highWatermark();if(query.highWatermark()==null)try(var st=db.createStatement();var rs=st.executeQuery("SELECT COALESCE(MAX(seq),0) FROM discovery_relationship_observations")){high=rs.getLong(1);}
        long deadline=System.nanoTime()+executionBudget.toNanos();var edges=new ArrayList<Map<String,Object>>();var parts=sources(query,false);var nodes=new TreeMap<String,Accumulator>();long rows=0,invalid=0,badIdentity=0,badClock=0;
        if(query.observer()==null && !query.availability().equals("attributable"))try(var q=prepare(db,String.join(" UNION ALL ",parts)+" LIMIT "+(MAX_OCCURRENCES+1),query,parts.size());var r=q.executeQuery()) {
            while(r.next()) {
                if(++rows>MAX_OCCURRENCES)throw new ScopeLimit();
                if(r.getInt("valid_enr")==-1){invalid++;continue;}
                String identity=NodeIds.normalize(r.getString("node_id"));if(identity.isEmpty()){badIdentity++;continue;}
                Instant at;try{at=Instant.parse(r.getString("stamp"));}catch(RuntimeException e){badClock++;continue;}
                if(at.isBefore(query.scope().startInclusive()) || !at.isBefore(query.scope().endExclusive())){badClock++;continue;}
                nodes.computeIfAbsent(identity,Accumulator::new).add(at,r.getString("kind"),r.getString("protocol"),r.getString("occurrence_id"),r.getInt("valid_enr")==1);
            }
        }
        try(var q=db.prepareStatement("SELECT o.*,e.raw_json,d.started_at run_started,d.local_identity,d.key_lifecycle FROM discovery_relationship_observations o JOIN discovery_exchange_evidence e ON e.id=o.evidence_id JOIN discovery_runs d ON d.id=o.run_id AND d.observer_id=o.observer_id JOIN discovery_record_evidence v ON v.evidence_id=o.evidence_id AND v.packet_index=o.packet_index AND v.record_index=o.record_index JOIN discovery_exchange_claims c ON c.exchange_key=o.correlation_ref AND c.evidence_id=o.evidence_id WHERE o.seq<=? AND o.observed_at>=? AND o.observed_at<? AND e.contract_version=2 AND o.contract_version=2 AND o.relation_type='REPORTED_NEIGHBOR' AND o.authentication='HELPER_ATTESTED_AUTHENTICATED' AND o.enr_signature='VALID' AND o.identity_comparison='MATCH' AND e.run_id=o.run_id AND e.protocol=o.protocol AND e.classification='ATTRIBUTABLE_EXCHANGE' AND e.authentication=o.authentication AND v.source_identity=o.source_identity AND v.target_identity=o.target_identity AND v.authentication=o.authentication AND v.enr_signature='VALID' AND v.identity_comparison='MATCH' AND v.correlation='AUTHENTICATED_REQUEST_MATCH' AND NOT EXISTS(SELECT 1 FROM discovery_evidence_rejections r WHERE r.evidence_id=o.evidence_id) AND (? IS NULL OR o.observer_id=?) AND (?='all' OR o.protocol=?) AND (o.source_identity LIKE ? OR o.target_identity LIKE ?) AND ? ORDER BY o.seq LIMIT 2001")) {
            q.setLong(1,high);q.setString(2,io.github.gavinruff007.torchnode.storage.DiscoveryEvidenceStore.clock(query.scope().startInclusive()));
            q.setString(3,io.github.gavinruff007.torchnode.storage.DiscoveryEvidenceStore.clock(query.scope().endExclusive()));
            q.setString(4,query.observer());q.setString(5,query.observer());q.setString(6,query.protocol());q.setString(7,query.protocol());q.setString(8,query.search()+"%");q.setString(9,query.search()+"%");q.setBoolean(10,query.relation()==null || query.relation()==RelationshipObservation.Relation.REPORTED_NEIGHBOR);
            try(var r=q.executeQuery()) { int bounded=0;while(r.next()) {
                if(++bounded>2000)throw new ScopeLimit();
                if(System.nanoTime()>=deadline)throw new SQLTimeoutException("Topology validation deadline exceeded");
                if(query.observer()!=null && !query.observer().equals(r.getString("observer_id")))continue;
                if(!query.protocol().equals("all") && !query.protocol().equals(r.getString("protocol")))continue;
                if(query.relation()!=null && query.relation()!=RelationshipObservation.Relation.REPORTED_NEIGHBOR)continue;
                String source=r.getString("source_identity"),target=r.getString("target_identity");
                if(!source.startsWith(query.search()) && !target.startsWith(query.search()))continue;
                var raw=JSON.readTree(r.getString("raw_json"));
                var run=new io.github.gavinruff007.torchnode.discovery.ObserverInstance.Run(UUID.fromString(r.getString("observer_id")),UUID.fromString(r.getString("run_id")),Instant.parse(r.getString("run_started")),r.getString("local_identity"),io.github.gavinruff007.torchnode.discovery.ObserverInstance.KeyLifecycle.valueOf(r.getString("key_lifecycle")));
                var capture=new io.github.gavinruff007.torchnode.discovery.Discv5EvidenceCapture(run,run.localDiscoveryIdentity());capture.ready(2,run.localDiscoveryIdentity());capture.accept(raw);
                if(!raw.path("partial").isBoolean() || raw.path("partial").asBoolean() || raw.path("packets").findValues("omittedRecords").stream().anyMatch(n->n.asLong()!=0))continue;
                int packet=r.getInt("packet_index"),record=r.getInt("record_index");
                if(capture.snapshot().reports().stream().noneMatch(report->report.qualifiesForGraphPromotion() && report.packetIndex()==packet && report.recordIndex()==record && report.sourceResponderIdentity().equals(source) && report.targetIdentity().equals(target)))continue;
                if(query.availability().equals("identity-only"))continue;
                rows++;Instant at=Instant.parse(r.getString("observed_at"));
                for(String id:List.of(source,target))nodes.computeIfAbsent(id,Accumulator::new).add(at,"relationship","discv5",r.getString("evidence_id"),true);
                var edge=new LinkedHashMap<String,Object>();
                edge.put("id",r.getString("id"));edge.put("sourceIdentity",source);edge.put("targetIdentity",target);edge.put("observer",r.getString("observer_id"));edge.put("run",r.getString("run_id"));edge.put("protocol","discv5");edge.put("relationType","REPORTED_NEIGHBOR");edge.put("observedAt",at.toString());edge.put("evidenceId",r.getString("evidence_id"));
                edges.add(edge);
            }}
        }catch(com.fasterxml.jackson.core.JsonProcessingException | IllegalArgumentException e){throw new SQLException("Invalid persisted relationship evidence",e);}
        var diagnostics=new LinkedHashMap<String,Object>();
        String conditions="e.observed_at>=? AND e.observed_at<?";
        if(query.observer()!=null)conditions+=" AND d.observer_id=?";
        if(!query.protocol().equals("all"))conditions+=" AND e.protocol=?";
        if(!query.search().isEmpty())conditions+=" AND EXISTS(SELECT 1 FROM discovery_record_evidence v WHERE v.evidence_id=e.id AND (v.source_identity LIKE ? OR v.target_identity LIKE ?))";
        boolean available=false;
        for(boolean candidates:List.of(false,true))try(var q=db.prepareStatement("SELECT COUNT(*) FROM (SELECT 1 FROM discovery_exchange_evidence e LEFT JOIN discovery_runs d ON d.id=e.run_id "+(candidates?"JOIN discovery_candidates c ON c.evidence_id=e.id ":"")+"WHERE "+(candidates?conditions.replace("e.observed_at","c.observed_at"):conditions)+(candidates?"":" AND e.classification='ATTRIBUTABLE_EXCHANGE' AND NOT EXISTS(SELECT 1 FROM discovery_evidence_rejections r WHERE r.evidence_id=e.id)")+" LIMIT 50001)")) {
            int i=1;q.setString(i++,io.github.gavinruff007.torchnode.storage.DiscoveryEvidenceStore.clock(query.scope().startInclusive()));q.setString(i++,io.github.gavinruff007.torchnode.storage.DiscoveryEvidenceStore.clock(query.scope().endExclusive()));if(query.observer()!=null)q.setString(i++,query.observer());if(!query.protocol().equals("all"))q.setString(i++,query.protocol());if(!query.search().isEmpty()){q.setString(i++,query.search()+"%");q.setString(i++,query.search()+"%");}
            try(var r=q.executeQuery()){long n=r.getLong(1);if(n>50000)throw new ScopeLimit();if(candidates)diagnostics.put("nonPromotableCandidateCount",n);else {available=n>0;diagnostics.put("attributableExchangeCount",n);}}
        }
        available|=!edges.isEmpty();diagnostics.put("relationshipObservationCount",edges.size());diagnostics.put("distinctDirectedRelationshipCount",aggregate(edges).size());diagnostics.put("relationshipMeasurementAvailable",available);diagnostics.put("collectorStatus","unknown: read-only historical API does not inspect the collector");diagnostics.put("collectionComplete",false);diagnostics.put("networkCoverageKnown",false);
        return new Measured(nodes,new Coverage(rows,invalid,badIdentity,badClock,MAX_OCCURRENCES,rows==MAX_OCCURRENCES,true,false,false,false,"Legacy identities are unlinked. Relationships are independently revalidated against persisted responder, request, run and ENR evidence. Network and collection coverage are unknown. At most 2000 relationship observations and 400 rendered observations; node pagination can omit endpoints."),List.copyOf(edges),high,available,diagnostics);
    }
    private static List<Map<String,Object>> aggregate(List<Map<String,Object>> observations) {
        var groups=new TreeMap<String,List<Map<String,Object>>>();
        for(var observation:observations)groups.computeIfAbsent(observation.get("sourceIdentity")+":"+observation.get("targetIdentity"),k->new ArrayList<>()).add(observation);
        var result=new ArrayList<Map<String,Object>>();for(var group:groups.values()) {
            var edge=new LinkedHashMap<String,Object>(group.getFirst());edge.put("observationCount",group.size());edge.put("observations",List.copyOf(group));
            edge.put("firstObservedAt",group.stream().map(e->(String)e.get("observedAt")).min(String::compareTo).orElseThrow());edge.put("lastObservedAt",group.stream().map(e->(String)e.get("observedAt")).max(String::compareTo).orElseThrow());result.add(edge);
        }return List.copyOf(result);
    }
    private Graph graph(Query query,Measured measured,List<Vertex> vertices,String next,boolean subset) {
        return new Graph(query.scope(),query.protocol(),query.relation()==null?"all":query.relation().name(),query.search(),Instant.now(),LIMITATION,
                "canonical identities; source occurrences are separate counting units","all matching timestamped discovery receipts (excluding ENR duplicates), usable ENRs and inspection targets; protocol-specific filters select discovery receipts only",
                new Summary(measured.nodes().size(),vertices.size(),aggregate(measured.edges()).size(),measured.available(),null,null,null,null),measured.coverage(),vertices,aggregate(measured.edges()).stream().filter(e->vertices.stream().anyMatch(v->v.identity().equals(e.get("sourceIdentity"))) && vertices.stream().anyMatch(v->v.identity().equals(e.get("targetIdentity")))).limit(400).toList(),next,subset,
                "Canonical identity ascending is the unique keyset order. Each request has its own read snapshot; backfilled identities at or below the cursor require a reload. Counts may change between requests.",
                Map.of("DISCOVERED_BY","Unavailable: historical observer identity missing","REPORTED_NEIGHBOR","Authenticated responder reports target ENR; not an active peer connection","DIRECT_SESSION","Unavailable: actual ephemeral local session identity missing"),measured.highWatermark(),measured.edges().size()>400 || subset,measured.diagnostics());
    }
    public Graph graph(Query query) throws SQLException {
        try(var session=open()) {
            var db=session.db();
            var measured=measure(db,query);var vertices=new ArrayList<Vertex>();String next="";
            for(var entry:measured.nodes().entrySet()) {
                if(query.after()!=null && entry.getKey().compareTo(query.after())<=0)continue;
                if(vertices.size()==query.limit()){next=vertices.getLast().identity();break;}
                vertices.add(entry.getValue().vertex());
            }
            return graph(query,measured,vertices,next,query.after()!=null || measured.nodes().size()>vertices.size());
        }
    }
    public Graph neighborhood(Query query,String identity) throws SQLException {
        identity=RelationshipObservation.requiredIdentity(identity);
        var focused=new Query(query.scope(),query.protocol(),query.relation(),identity,query.limit(),null,query.observer(),query.highWatermark(),query.availability());
        try(var session=open()) {
            var db=session.db();
            var measured=measure(db,focused);var node=measured.nodes().get(identity);
            var vertices=new ArrayList<Vertex>();
            if(node!=null) {
                vertices.add(node.vertex());
                for(var neighbor:measured.nodes().values()) {
                    if(vertices.size()==focused.limit())break;
                    if(!neighbor.identity.equals(identity))vertices.add(neighbor.vertex());
                }
            }
            return graph(focused,measured,vertices,"",measured.nodes().size()>vertices.size());
        }
    }
    public EvidencePage evidence(Query query,String identity) throws SQLException {
        identity=RelationshipObservation.requiredIdentity(identity);
        var focused=new Query(query.scope(),query.protocol(),query.relation(),identity,MAX_EVIDENCE,null,query.observer());
        var parts=sources(focused,true);var values=new ArrayList<Evidence>();boolean truncated=false;
        String union=String.join(" UNION ALL ",parts);
        try(var session=open();var q=prepare(session.db(),"SELECT * FROM ("+union+") ORDER BY "+clock("stamp")+" DESC,kind,occurrence_id LIMIT "+(MAX_EVIDENCE+1),focused,parts.size());var r=q.executeQuery()) {
            while(r.next()) {
                if(values.size()==MAX_EVIDENCE){truncated=true;break;}
                String raw=r.getString("payload"),kind=r.getString("kind"),occurrence=r.getString("occurrence_id");
                boolean omitted=raw==null || raw.length()>8192;JsonNode payload=null;
                if(!omitted)try{payload=JSON.readTree(raw);}catch(Exception e){omitted=true;}
                String provenance=r.getString("provenance");boolean provenanceTruncated=provenance!=null && provenance.length()>4096;
                if(provenanceTruncated)provenance=provenance.substring(0,4096);
                values.add(new Evidence(kind,occurrence,Instant.parse(r.getString("stamp")),provenance,
                        r.getInt("valid_enr")==1?"VALIDATED_ENR_IDENTITY":r.getInt("valid_enr")==-1?"INVALID_OR_MISMATCHED_ENR":"RECORDED_CLAIM_OR_MEASUREMENT",
                        href(identity,kind,occurrence),payload,omitted,provenanceTruncated));
            }
            if(query.observer()!=null || query.availability().equals("attributable"))values.clear();
            var related=measure(session.db(),new Query(query.scope(),query.protocol(),query.relation(),identity,MAX_NODES,null,query.observer(),query.highWatermark(),query.availability())).edges();
            for(var edge:related.stream().limit(MAX_EVIDENCE).toList()){values.add(new Evidence("relationship",(String)edge.get("id"),Instant.parse((String)edge.get("observedAt")),"observer="+edge.get("observer")+" run="+edge.get("run")+" responder="+edge.get("sourceIdentity"),"AUTHENTICATED_REPORTED_NEIGHBOR","/api/v1/topology/edges/"+edge.get("id"),JSON.valueToTree(edge),false,false));}
            values.sort(Comparator.comparing(Evidence::observedAt).reversed().thenComparing(Evidence::occurrenceId));truncated|=values.size()>MAX_EVIDENCE || related.size()>MAX_EVIDENCE;
            return new EvidencePage(query.scope(),identity,values.stream().limit(MAX_EVIDENCE).toList(),truncated,"At most 20 occurrences; payloads over 8192 characters omitted with full source links. Endpoints are evidence, not graph identities. No relationship is inferred from these rows.");
        }
    }
    public Object candidates(Query query)throws SQLException {
        try(var session=open()) {
            String sql="SELECT c.* FROM discovery_candidates c JOIN discovery_exchange_evidence e ON e.id=c.evidence_id LEFT JOIN discovery_runs d ON d.id=e.run_id WHERE c.observed_at>=? AND c.observed_at<?";
            if(query.observer()!=null)sql+=" AND d.observer_id=?";if(!query.protocol().equals("all"))sql+=" AND e.protocol=?";
            sql+=" AND (COALESCE(c.source_identity,'') LIKE ? OR COALESCE(c.target_identity,'') LIKE ?) ORDER BY c.observed_at,c.id LIMIT 201";
            var values=new ArrayList<Map<String,Object>>();try(var q=session.db().prepareStatement(sql)){int i=1;q.setString(i++,io.github.gavinruff007.torchnode.storage.DiscoveryEvidenceStore.clock(query.scope().startInclusive()));q.setString(i++,io.github.gavinruff007.torchnode.storage.DiscoveryEvidenceStore.clock(query.scope().endExclusive()));if(query.observer()!=null)q.setString(i++,query.observer());if(!query.protocol().equals("all"))q.setString(i++,query.protocol());q.setString(i++,query.search()+"%");q.setString(i++,query.search()+"%");try(var r=q.executeQuery()){while(r.next()){var row=new LinkedHashMap<String,Object>();for(String name:List.of("id","evidence_id","observed_at","reason","source_identity","target_identity"))row.put(name,r.getString(name));values.add(row);}}}
            return Map.of("candidates",values.stream().limit(200).toList(),"truncated",values.size()>200,"graphEligible",false,"unknownTimestampsExcluded",true);
        }
    }
    public Object persistedEvidence(String id)throws SQLException {return new io.github.gavinruff007.torchnode.storage.DiscoveryEvidenceRepository(databasePath).evidence(id).orElse(null);}
    public Object edgeEvidence(Query query,String id)throws SQLException {
        if(!id.matches("[0-9a-f]{64}"))return null;
        try(var session=open()) {
            for(var edge:measure(session.db(),query).edges())if(id.equals(edge.get("id")))
                return new io.github.gavinruff007.torchnode.storage.DiscoveryEvidenceRepository(databasePath).evidence((String)edge.get("evidenceId")).orElseThrow();
        }
        return null;
    }
    public Comparison compare(Query current,Scope baseline) throws SQLException {
        var previous=new Query(baseline,current.protocol(),current.relation(),current.search(),current.limit(),null,current.observer(),current.highWatermark(),current.availability());
        try(var session=open()) {
            var db=session.db();
            var currentMeasured=measure(db,current);var baselineMeasured=measure(db,previous);var now=currentMeasured.nodes().keySet();var before=baselineMeasured.nodes().keySet();
            var currentEdges=new TreeSet<String>();var baselineEdges=new TreeSet<String>();for(var e:currentMeasured.edges())currentEdges.add(e.get("sourceIdentity")+":"+e.get("targetIdentity"));for(var e:baselineMeasured.edges())baselineEdges.add(e.get("sourceIdentity")+":"+e.get("targetIdentity"));var both=new TreeSet<>(currentEdges);both.retainAll(baselineEdges);var currentOnly=new TreeSet<>(currentEdges);currentOnly.removeAll(baselineEdges);var baselineOnly=new TreeSet<>(baselineEdges);baselineOnly.removeAll(currentEdges);
            var added=new TreeSet<>(now);added.removeAll(before);var repeated=new TreeSet<>(now);repeated.retainAll(before);var absent=new TreeSet<>(before);absent.removeAll(now);
            return new Comparison(current.scope(),baseline,new IdentityComparison(added.size(),repeated.size(),absent.size(),added.stream().limit(current.limit()).toList(),repeated.stream().limit(current.limit()).toList(),absent.stream().limit(current.limit()).toList(),
                    Math.max(added.size(),Math.max(repeated.size(),absent.size()))>current.limit(),"Canonical identity observations, not relationship changes. Not re-observed does not mean disconnected."),
                    new RelationshipComparison(currentMeasured.available() && baselineMeasured.available(),currentMeasured.available() && baselineMeasured.available()?currentOnly:null,currentMeasured.available() && baselineMeasured.available()?both:null,currentMeasured.available() && baselineMeasured.available()?baselineOnly:null,"Bounded stored observations only; absence is not disconnection. Collection and network coverage unknown in both windows; no partial aggregate returned on scope overflow."),LIMITATION,currentMeasured.diagnostics(),baselineMeasured.diagnostics());
        }
    }
}
