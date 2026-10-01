package io.github.gavinruff007.torchnode.analysis;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.gavinruff007.torchnode.inspection.NetworkVerification;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.HashSet;

/** Read-only measurements of recorded evidence, never an estimate of the Ethereum population. */
public final class NetworkAnalytics {
    private static final ObjectMapper JSON=new ObjectMapper();
    public enum Mode { WINDOW, ALL_AVAILABLE }
    public record Scope(Instant startInclusive, Instant endExclusive, Mode mode) {
        public Scope(Instant startInclusive,Instant endExclusive) { this(startInclusive,endExclusive,Mode.WINDOW); }
        public static Scope allAvailable() {
            return new Scope(Instant.parse("0001-01-01T00:00:00Z"),
                    Instant.parse("9999-12-31T23:59:59.999999999Z"),Mode.ALL_AVAILABLE);
        }
        public Scope {
            if (startInclusive == null || endExclusive == null || !startInclusive.isBefore(endExclusive)
                    || mode==null || mode==Mode.WINDOW && Duration.between(startInclusive, endExclusive).compareTo(Duration.ofDays(31)) > 0)
                throw new IllegalArgumentException("Analytics window must be positive and at most 31 days");
        }
    }
    public record Bucket(String label, long count) {}
    public record Metric(String id, String countingUnit, String denominatorMeaning,
                         long denominator, long unknown, String evidenceRule, String bucketSemantics,
                         List<Bucket> buckets) {
        public Metric { buckets = List.copyOf(buckets); }
    }
    public record Report(Scope scope, Instant generatedAt, String populationWarning,
                         long excludedUntimedRuns, long excludedUntimedEndpointAttempts,
                         long excludedUntimedRpcResponses,
                         List<Metric> metrics) {
        public Report { metrics = List.copyOf(metrics); }
    }
    public record Snapshot(Instant generatedAt,long latestProjectionRows,long distinctProjectionIdentities,
                           String meaning) {}

    private final String databasePath;
    public NetworkAnalytics(String databasePath) { this.databasePath = databasePath; }

    private Connection readOnlyConnection() throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:"+Path.of(databasePath).toAbsolutePath().toUri()+"?mode=ro");
    }

    /** Latest node projections only; deliberately separate from historical measurement windows. */
    public Snapshot snapshot() throws SQLException {
        try(var db=readOnlyConnection();
            var statement=db.createStatement()) {
            statement.execute("PRAGMA query_only=ON");
            statement.setQueryTimeout(15);
            try(var rows=statement.executeQuery("SELECT COUNT(*),COUNT(DISTINCT CASE WHEN length(node_id)=128 "+
                    "AND node_id NOT GLOB '*[^0-9a-fA-F]*' THEN lower(node_id) END) FROM nodes")) {
                rows.next();
                return new Snapshot(Instant.now(),rows.getLong(1),rows.getLong(2),
                        "Latest node projection rows and distinct cryptographic identities, not a historical distribution");
            }
        }
    }

    // Instant.toString has variable fractional width. This expression preserves nanosecond ordering.
    private static String time(String column) {
        return "substr("+column+",1,19) || CASE WHEN substr("+column+",20,1)='.' THEN " +
                "substr(substr("+column+",21,instr(substr("+column+",21),'Z')-1) || '000000000',1,9) " +
                "ELSE '000000000' END";
    }
    private static String bound(String column) {
        return column+" IS NOT NULL AND "+time(column)+">=? AND "+time(column)+"<?";
    }
    private static String key(Instant instant) {
        String s=instant.toString(); int dot=s.indexOf('.');
        return s.substring(0,19)+(dot<0?"000000000":(s.substring(dot+1,s.length()-1)+"000000000").substring(0,9));
    }
    private static PreparedStatement query(Connection db, String sql, Scope scope) throws SQLException {
        PreparedStatement statement=db.prepareStatement(sql);
        String start=key(scope.startInclusive()), end=key(scope.endExclusive());
        for(int i=1;i<=statement.getParameterMetaData().getParameterCount();i+=2) {
            statement.setString(i,start); statement.setString(i+1,end);
        }
        statement.setQueryTimeout(15);
        return statement;
    }
    private static long scalar(Connection db, String sql, Scope scope) throws SQLException {
        try(var q=query(db,sql,scope);var rows=q.executeQuery()){return rows.next()?rows.getLong(1):0;}
    }
    private static long scalar(Connection db,String sql) throws SQLException {
        try(var q=db.prepareStatement(sql);var rows=q.executeQuery()){return rows.next()?rows.getLong(1):0;}
    }
    private static List<Bucket> buckets(Connection db,String sql,Scope scope) throws SQLException {
        var result=new ArrayList<Bucket>();
        try(var q=query(db,sql,scope);var rows=q.executeQuery()){
            while(rows.next())result.add(new Bucket(rows.getString(1),rows.getLong(2)));
        }
        return result;
    }
    private static long total(List<Bucket> buckets) { return buckets.stream().mapToLong(Bucket::count).sum(); }
    private static Metric metric(String id,String unit,String denominatorMeaning,long denominator,long unknown,
                                 String rule,List<Bucket> buckets) {
        String semantics = switch (id) {
            case "discovery-providers", "address-families", "trusted-enr-families", "enr-trust",
                    "rpc-chainId", "rpc-networkId", "beacon-clients",
                    "hello-capabilities", "network-verification", "country-lookup-status",
                    "asn-lookup-status", "hosting-classification" ->
                    "Buckets may overlap; each bucket count is a numerator, not a share of a partition";
            case "execution-versions", "country-distribution-top-100", "asn-distribution-top-100" ->
                    "Top 100 overlapping category memberships only; omitted tail is stated in the evidence rule";
            default -> "Each bucket is a named count; do not infer a percentage or partition from bucket totals";
        };
        return new Metric(id,unit,denominatorMeaning,denominator,unknown,rule,semantics,buckets);
    }

    public Report measure(Scope scope) throws SQLException {
        try(Connection db=readOnlyConnection()) {
            try(var s=db.createStatement()) {
                s.execute("PRAGMA query_only=ON");
                try(var rows=s.executeQuery("SELECT MAX(version) FROM schema_migrations")) {
                    if(!rows.next() || rows.getInt(1)<5)throw new SQLException("Analytics requires schema v5");
                }
            }
            String[] evidenceTables={"discovery_endpoint_index", "discovery_observations", "enr_observations",
                    "inspection_runs", "network_enrichment_lookups", "change_events"};
            String[] clocks={"observed_at", "observed_at", "observed_at", "started_at", "looked_up_at",
                    "current_observed_at"};
            long eligibleRows=0;
            for(int i=0;i<evidenceTables.length;i++) {
                eligibleRows+=scalar(db,"SELECT COUNT(*) FROM "+evidenceTables[i]+" WHERE "+bound(clocks[i]),scope);
                if(eligibleRows>250000)throw new SQLException("Analytics scope exceeds the explicit 250,000 evidence-row safety limit; narrow the window");
            }
            var metrics=new ArrayList<Metric>();
            String d=bound("d.observed_at"), r=bound("r.started_at"), e=bound("e.observed_at");
            String observed="SELECT d.node_id FROM discovery_observations d WHERE "+d+
                    " UNION SELECT r.node_id FROM inspection_runs r WHERE "+r+
                    " UNION SELECT e.node_id FROM enr_observations e WHERE "+e;
            long identities=scalar(db,"SELECT COUNT(*) FROM ("+observed+")",scope);
            metrics.add(metric("observed-identities","canonical identities","all identities with timestamped discovery, inspection or ENR evidence in window",identities,0,
                    "Identity deduplicated across evidence domains",List.of(new Bucket("observed",identities))));
            long discovery=scalar(db,"SELECT COUNT(*) FROM discovery_observations d WHERE "+d,scope);
            metrics.add(metric("discovery-occurrences","observations","timestamped discovery receipts in window",discovery,0,
                    "Repeated receipts remain separate",List.of(new Bucket("receipts",discovery))));
            var providers=buckets(db,"SELECT d.source,COUNT(DISTINCT d.node_id) FROM discovery_observations d WHERE "+d+
                    " GROUP BY d.source ORDER BY d.source",scope);
            long withDiscovery=scalar(db,"SELECT COUNT(DISTINCT d.node_id) FROM discovery_observations d WHERE "+d,scope);
            metrics.add(metric("discovery-providers","canonical identities","observed identities; an identity may occur in multiple source buckets",identities,Math.max(0,identities-withDiscovery),
                    "Provider observation does not imply population coverage",providers));
            var providerOccurrences=buckets(db,"SELECT d.source,COUNT(*) FROM discovery_observations d WHERE "+d+
                    " GROUP BY d.source ORDER BY d.source",scope);
            metrics.add(metric("discovery-by-source","observations","discovery receipts in window",discovery,0,
                    "Occurrences intentionally retain repeat sampling",providerOccurrences));
            long overlap=scalar(db,"SELECT COUNT(*) FROM (SELECT d.node_id FROM discovery_observations d WHERE "+d+
                    " AND d.source IN ('discv4','discv5') GROUP BY d.node_id HAVING COUNT(DISTINCT d.source)=2)",scope);
            metrics.add(metric("discv4-discv5-overlap","canonical identities","observed identities",identities,Math.max(0,identities-withDiscovery),
                    "Both providers observed the identity in the window",List.of(new Bucket("both",overlap))));
            String endpoint="FROM discovery_endpoint_index x JOIN discovery_observations d ON d.id=x.observation_id WHERE "+d;
            var families=buckets(db,"SELECT x.address_family,COUNT(DISTINCT x.node_id) "+endpoint+
                    " GROUP BY x.address_family ORDER BY x.address_family",scope);
            long withEndpoint=scalar(db,"SELECT COUNT(DISTINCT x.node_id) "+endpoint,scope);
            metrics.add(metric("address-families","canonical identities","observed identities; family buckets overlap",identities,Math.max(0,identities-withEndpoint),
                    "Observed endpoint, including trusted ENR; advertisement is not reachability",families));
            var addresses=buckets(db,"SELECT x.address_family,COUNT(DISTINCT x.address) "+endpoint+
                    " GROUP BY x.address_family ORDER BY x.address_family",scope);
            metrics.add(metric("normalized-addresses","addresses","distinct normalized addresses by family in window",total(addresses),0,
                    "Address count is not identity count",addresses));
            var endpointContexts=buckets(db,"SELECT d.source||':'||x.address_family||':'||x.purpose,COUNT(*) "+endpoint+
                    " GROUP BY d.source,x.address_family,x.purpose ORDER BY 1",scope);
            metrics.add(metric("endpoint-occurrences","endpoint observations","typed endpoint entries in window",total(endpointContexts),0,
                    "Source, family and purpose are retained; repeated entries remain occurrences",endpointContexts));
            long dual=scalar(db,"SELECT COUNT(*) FROM (SELECT x.node_id "+endpoint+
                    " AND x.address_family IN ('IPV4','IPV6') GROUP BY x.node_id HAVING COUNT(DISTINCT x.address_family)=2)",scope);
            metrics.add(metric("dual-family","canonical identities","observed identities",identities,Math.max(0,identities-withEndpoint),
                    "Both IPv4 and IPv6 endpoints observed",List.of(new Bucket("both",dual))));
            long runs=scalar(db,"SELECT COUNT(*) FROM inspection_runs r WHERE "+r,scope);
            long inspected=scalar(db,"SELECT COUNT(DISTINCT r.node_id) FROM inspection_runs r WHERE "+r,scope);
            metrics.add(metric("inspections","inspection attempts","timestamped inspection runs",runs,0,
                    "Run start timestamp selects the window",List.of(new Bucket("runs",runs))));
            metrics.add(metric("inspected-identities","canonical identities","all observed identities in window",identities,
                    Math.max(0,identities-inspected),"Identity deduplicated across timestamped inspection runs",
                    List.of(new Bucket("inspected",inspected))));
            var enr=buckets(db,"SELECT CASE WHEN json_extract(e.evidence_json,'$.structurallyValid')=1 AND e.signature_validation='VALID' AND e.identity_comparison='MATCH' THEN 'trusted' ELSE 'diagnostic or untrusted' END,COUNT(DISTINCT e.node_id) "+
                    "FROM enr_observations e WHERE "+e+" GROUP BY 1 ORDER BY 1",scope);
            long enrIdentities=scalar(db,"SELECT COUNT(DISTINCT e.node_id) FROM enr_observations e WHERE "+e,scope);
            metrics.add(metric("enr-trust","canonical identities","identities with ENR evidence in window; buckets may overlap",enrIdentities,0,
                    "Trusted requires structural validity, valid signature and identity match, following EnrEvidence.usable()",enr));
            metrics.add(metric("enr-coverage","canonical identities","observed identities in window",identities,Math.max(0,identities-enrIdentities),
                    "Any ENR evidence, including diagnostic evidence",List.of(new Bucket("with ENR evidence",enrIdentities))));
            var trustedFamilies=buckets(db,"SELECT x.address_family,COUNT(DISTINCT x.node_id) "+endpoint+
                    " AND d.source='ENR' GROUP BY x.address_family ORDER BY x.address_family",scope);
            long trusted=enr.stream().filter(bucket->bucket.label().equals("trusted")).mapToLong(Bucket::count).sum();
            long trustedWithEndpoint=scalar(db,"SELECT COUNT(DISTINCT x.node_id) "+endpoint+" AND d.source='ENR'",scope);
            metrics.add(metric("trusted-enr-families","canonical identities","identities with trusted ENR in window; family buckets may overlap",trusted,Math.max(0,trusted-trustedWithEndpoint),
                    "Only validated ENR-derived endpoint observations",trustedFamilies));
            measureInspections(db,scope,metrics,identities);
            measureEnrichment(db,scope,metrics);
            String c=bound("c.current_observed_at");
            var changes=buckets(db,"SELECT c.derivation_version||':'||c.change_type,COUNT(*) FROM change_events c WHERE "+c+
                    " GROUP BY c.derivation_version,c.change_type ORDER BY c.derivation_version,c.change_type",scope);
            metrics.add(metric("change-activity","change events","persisted derived events in window",total(changes),0,
                    "Buckets retain derivation rule version; first observed is not a transition",changes));
            long untimedRuns=scalar(db,"SELECT COUNT(*) FROM inspection_runs WHERE started_at IS NULL");
            long untimedAttempts=scalar(db,"SELECT COUNT(*) FROM inspection_runs r JOIN inspection_evidence v ON v.hash=r.evidence_hash "+
                    "JOIN json_each(v.evidence_json,'$.endpointAttempts') a LEFT JOIN json_each(r.timing_json,'$.endpointAttemptTimes') t ON t.key=a.key WHERE t.value IS NULL");
            long untimedRpc=scalar(db,"SELECT COUNT(*) FROM inspection_runs r JOIN inspection_evidence v ON v.hash=r.evidence_hash "+
                    "WHERE json_extract(v.evidence_json,'$.rpc') IS NOT NULL AND json_extract(r.timing_json,'$.rpcObservedAt') IS NULL");
            return new Report(scope,Instant.now(),"Among identities observed by TorchNode under this measurement scope; this is an observer sample, not an Ethereum population estimate",
                    untimedRuns,untimedAttempts,untimedRpc,metrics);
        }
    }

    private static void measureInspections(Connection db,Scope scope,List<Metric> metrics,long identities) throws SQLException {
        // Per-attempt clocks, not the run clock, select protocol and API observations.
        String attempt="FROM (SELECT r.node_id,a.value attempt FROM inspection_runs r JOIN inspection_evidence v ON v.hash=r.evidence_hash "+
                "JOIN json_each(v.evidence_json,'$.endpointAttempts') a "+
                "JOIN json_each(r.timing_json,'$.endpointAttemptTimes') t ON t.key=a.key WHERE "+bound("t.value")+") q";
        for(String[] stage:new String[][]{{"P2P TCP","tcp"},{"RLPx Auth","rlpx"},{"RLPx Hello","hello"},{"ETH Status","status"}}) {
            String limitation="lower(coalesce(json_extract(s.value,'$.reason'),'')||' '||coalesce(json_extract(s.value,'$.reasonCode'),''))";
            String state="CASE WHEN "+limitation+" LIKE '%operation not permitted%' OR "+limitation+
                    " LIKE '%permission denied%' OR "+limitation+" LIKE '%network is unreachable%' OR "+
                    limitation+" LIKE '%no route to host%' THEN 'OBSERVER_LIMITED' ELSE COALESCE(json_extract(s.value,'$.state'),'UNKNOWN') END";
            String sql="SELECT "+state+",COUNT(*) "+attempt+
                    " JOIN json_each(q.attempt,'$.diagnostics') s WHERE json_extract(s.value,'$.name')='"+stage[0]+"' GROUP BY 1 ORDER BY 1";
            var values=buckets(db,sql,scope);
            long notTested=values.stream().filter(b->b.label().equals("NOT_TESTED") || b.label().equals("UNKNOWN")).mapToLong(Bucket::count).sum();
            metrics.add(metric("p2p-"+stage[1],"endpoint stage attempts","actual attempted stages; NOT_TESTED is shown separately",total(values)-notTested,notTested,
                    "PASS, FAILED, TIMEOUT, NOT_TESTED and identifiable observer limitations retain separate states",values));
        }
        long p2pIdentities=scalar(db,"SELECT COUNT(DISTINCT q.node_id) "+attempt,scope);
        metrics.add(metric("p2p-coverage","canonical identities","observed identities in window",identities,
                Math.max(0,identities-p2pIdentities),"Identities with at least one timestamped endpoint attempt",List.of(new Bucket("attempted",p2pIdentities))));
        long reachableIpv6=scalar(db,"SELECT COUNT(DISTINCT q.node_id) "+attempt+
                " JOIN json_each(q.attempt,'$.diagnostics') s WHERE json_extract(q.attempt,'$.addressFamily')='IPV6' "+
                "AND json_extract(s.value,'$.name')='P2P TCP' AND json_extract(s.value,'$.state')='PASS'",scope);
        long ipv6Attempted=scalar(db,"SELECT COUNT(DISTINCT q.node_id) "+attempt+
                " JOIN json_each(q.attempt,'$.diagnostics') s WHERE json_extract(q.attempt,'$.addressFamily')='IPV6' "+
                "AND json_extract(s.value,'$.name')='P2P TCP' AND json_extract(s.value,'$.state')!='NOT_TESTED'",scope);
        metrics.add(metric("ipv6-tcp-pass","canonical identities","identities with timestamped IPv6 TCP attempts; a selected subset of IPv6 advertisements",ipv6Attempted,0,
                "Actual IPv6 TCP PASS, separate from advertisement",List.of(new Bucket("IPv6 TCP PASS",reachableIpv6))));
        String hello="SELECT q.node_id,json_extract(q.attempt,'$.p2p.hello.clientId') raw "+attempt+
                " JOIN json_each(q.attempt,'$.diagnostics') s WHERE json_extract(s.value,'$.name')='RLPx Hello' "+
                "AND json_extract(s.value,'$.state')='PASS' AND json_extract(q.attempt,'$.p2p.hello.clientId') IS NOT NULL";
        String rpc="SELECT r.node_id,json_extract(v.evidence_json,'$.rpc.clientVersion') raw "+
                "FROM inspection_runs r JOIN inspection_evidence v ON v.hash=r.evidence_hash WHERE "+
                bound("json_extract(r.timing_json,'$.rpcObservedAt')")+
                " AND json_extract(v.evidence_json,'$.rpc.clientVersion') IS NOT NULL";
        String clients="SELECT node_id,raw FROM ("+hello+" UNION ALL "+rpc+")";
        String prefix="substr(raw,1,instr(raw||'/','/')-1)";
        String impl="CASE WHEN lower("+prefix+") LIKE '%geth%' THEN 'Geth' "+
                "WHEN lower("+prefix+") LIKE '%nethermind%' THEN 'Nethermind' "+
                "WHEN lower("+prefix+") LIKE '%besu%' THEN 'Besu' "+
                "WHEN lower("+prefix+") LIKE '%erigon%' THEN 'Erigon' "+
                "WHEN lower("+prefix+") LIKE '%reth%' THEN 'Reth' ELSE "+prefix+" END";
        var client=buckets(db,"SELECT classification,COUNT(*) FROM (SELECT node_id,CASE WHEN COUNT(DISTINCT implementation)>1 "+
                "THEN 'CONFLICT' ELSE MIN(implementation) END classification FROM (SELECT node_id,"+impl+
                " implementation FROM ("+clients+")) WHERE implementation IS NOT NULL GROUP BY node_id) "+
                "GROUP BY classification ORDER BY classification",scope);
        long known=total(client);
        metrics.add(metric("execution-clients","canonical identities","all observed identities in window",identities,Math.max(0,identities-known),
                "Successful authenticated Hello or responding RPC clientVersion; conflicts stay explicit",client));
        String rawVersion="substr(raw,instr(raw,'/')+1,instr(substr(raw,instr(raw,'/')+1)||'/','/')-1)";
        String version="CASE WHEN instr(raw,'/')=0 THEN 'UNKNOWN_VERSION' WHEN substr("+rawVersion+",1,1) IN ('v','V') "+
                "THEN COALESCE(NULLIF(substr("+rawVersion+",2),''),'UNKNOWN_VERSION') ELSE COALESCE(NULLIF("+rawVersion+",''),'UNKNOWN_VERSION') END";
        var versions=buckets(db,"SELECT "+impl+"||'/'||"+version+",COUNT(DISTINCT node_id) "+
                "FROM ("+clients+") WHERE raw IS NOT NULL GROUP BY 1 ORDER BY 2 DESC,1 LIMIT 100",scope);
        long allVersionMemberships=scalar(db,"SELECT COALESCE(SUM(n),0) FROM (SELECT COUNT(DISTINCT node_id) n "+
                "FROM ("+clients+") WHERE raw IS NOT NULL GROUP BY "+impl+"||'/'||"+version+")",scope);
        metrics.add(metric("execution-versions","canonical identities per exact parsed version","identities with usable execution client evidence; multiple versions may occur",known,0,
                "Existing slash parser semantics; top 100 version buckets; omitted membership tail="+
                        Math.max(0,allVersionMemberships-total(versions))+"; raw evidence retained in history",versions));
        String api="FROM inspection_runs r JOIN inspection_evidence v ON v.hash=r.evidence_hash "+
                "JOIN json_each(v.evidence_json,'$.%sAttempts') a "+
                "JOIN json_each(r.timing_json,'$.%sAttemptsTimes') t ON t.key=a.key WHERE "+bound("t.value");
        for(String domain:List.of("rpc","beacon")) {
            String reachable=domain.equals("rpc")?"rpcReachable":"beaconReachable";
            String state="CASE WHEN json_extract(a.value,'$."+reachable+"')=1 THEN 'PASS' "+
                    "WHEN json_extract(a.value,'$.tcpOpen')=0 THEN 'NO_TCP_CONNECTION' "+
                    "WHEN json_extract(a.value,'$."+reachable+"')=0 THEN 'NO_SUPPORTED_RESPONSE' ELSE 'UNKNOWN' END";
            var values=buckets(db,"SELECT "+state+",COUNT(*) "+api.formatted(domain,domain)+" GROUP BY 1 ORDER BY 1",scope);
            metrics.add(metric(domain+"-attempts","candidate endpoint attempts","actual timestamped candidate probes",total(values),0,
                    "Failed candidate probe does not establish service absence",values));
            long attemptedIdentities=scalar(db,"SELECT COUNT(DISTINCT r.node_id) "+api.formatted(domain,domain),scope);
            metrics.add(metric(domain+"-coverage","canonical identities","observed identities in window",identities,
                    Math.max(0,identities-attemptedIdentities),"At least one timestamped candidate probe",List.of(new Bucket("attempted",attemptedIdentities))));
        }
        String rpcResponse="FROM inspection_runs r JOIN inspection_evidence v ON v.hash=r.evidence_hash WHERE "+
                bound("json_extract(r.timing_json,'$.rpcObservedAt')")+" AND json_extract(v.evidence_json,'$.rpc') IS NOT NULL";
        long rpcResponseIdentities=scalar(db,"SELECT COUNT(DISTINCT r.node_id) "+rpcResponse,scope);
        for(String keyName:List.of("chainId","networkId")) {
            var values=buckets(db,"SELECT CAST(json_extract(v.evidence_json,'$.rpc."+keyName+"') AS TEXT),COUNT(DISTINCT r.node_id) "+
                    rpcResponse+" AND json_extract(v.evidence_json,'$.rpc."+keyName+"') IS NOT NULL GROUP BY 1 ORDER BY 1",scope);
            long classified=scalar(db,"SELECT COUNT(DISTINCT r.node_id) "+rpcResponse+
                    " AND json_extract(v.evidence_json,'$.rpc."+keyName+"') IS NOT NULL",scope);
            metrics.add(metric("rpc-"+keyName,"canonical identities per observed value","identities with timestamped RPC responses; conflicting values may overlap",rpcResponseIdentities,
                    Math.max(0,rpcResponseIdentities-classified),
                    "Observed response value, not independent network verification",values));
        }
        String beaconResponse="FROM inspection_runs r JOIN inspection_evidence v ON v.hash=r.evidence_hash WHERE "+
                bound("json_extract(r.timing_json,'$.beaconObservedAt')")+" AND json_extract(v.evidence_json,'$.beacon') IS NOT NULL";
        var beaconClients=buckets(db,"SELECT substr(json_extract(v.evidence_json,'$.beacon.version'),1,instr(json_extract(v.evidence_json,'$.beacon.version')||'/','/')-1),"+
                "COUNT(DISTINCT r.node_id) "+beaconResponse+" AND json_extract(v.evidence_json,'$.beacon.version') IS NOT NULL GROUP BY 1 ORDER BY 1",scope);
        long beaconResponseIdentities=scalar(db,"SELECT COUNT(DISTINCT r.node_id) "+beaconResponse,scope);
        long beaconClassified=scalar(db,"SELECT COUNT(DISTINCT r.node_id) "+beaconResponse+
                " AND json_extract(v.evidence_json,'$.beacon.version') IS NOT NULL",scope);
        metrics.add(metric("beacon-clients","canonical identities per observed implementation","identities with timestamped Beacon version responses",beaconResponseIdentities,
                Math.max(0,beaconResponseIdentities-beaconClassified),
                "Consensus API version evidence only; conflicting observations may overlap",beaconClients));
        measureVerification(db,scope,metrics,identities);
        var capability=buckets(db,"SELECT CASE WHEN cap.type='text' THEN lower(cap.value) "+
                "WHEN cap.type='object' THEN lower(json_extract(cap.value,'$.name'))||'/'||json_extract(cap.value,'$.version') END,COUNT(DISTINCT q.node_id) "+attempt+
                " JOIN json_each(q.attempt,'$.p2p.hello.capabilities') cap JOIN json_each(q.attempt,'$.diagnostics') s "+
                "WHERE json_extract(s.value,'$.name')='RLPx Hello' AND json_extract(s.value,'$.state')='PASS' "+
                "AND cap.type IN ('text','object') GROUP BY 1 ORDER BY 1",scope);
        long successfulHello=scalar(db,"SELECT COUNT(DISTINCT q.node_id) "+attempt+
                " JOIN json_each(q.attempt,'$.diagnostics') s WHERE json_extract(s.value,'$.name')='RLPx Hello' "+
                "AND json_extract(s.value,'$.state')='PASS'",scope);
        long helloWithCapabilityList=scalar(db,"SELECT COUNT(DISTINCT q.node_id) "+attempt+
                " JOIN json_each(q.attempt,'$.diagnostics') s WHERE json_extract(s.value,'$.name')='RLPx Hello' "+
                "AND json_extract(s.value,'$.state')='PASS' AND json_type(q.attempt,'$.p2p.hello.capabilities')='array'",scope);
        metrics.add(metric("hello-capabilities","canonical identities per advertised capability","successful authenticated Hello identities; buckets overlap",successfulHello,
                Math.max(0,successfulHello-helloWithCapabilityList),
                "Advertisement is not successful protocol exercise",capability));
    }

    private static void measureVerification(Connection db,Scope scope,List<Metric> metrics,long identities) throws SQLException {
        String sql="SELECT r.node_id,json_extract(v.evidence_json,'$.rpc'),json_extract(v.evidence_json,'$.beacon'),"+
                "json_extract(v.evidence_json,'$.endpointAttempts'),r.timing_json,r.started_at FROM inspection_runs r "+
                "JOIN inspection_evidence v ON v.hash=r.evidence_hash WHERE r.started_at IS NOT NULL AND "+
                "COALESCE("+time("r.completed_at")+","+time("r.started_at")+")>=? AND "+
                time("r.started_at")+"<? ORDER BY r.started_at,r.id LIMIT 25001";
        Map<String,HashSet<String>> byStatus=new HashMap<>();Map<String,Long> comparisons=new HashMap<>();int rowsSeen=0;
        try(var q=query(db,sql,scope);var rows=q.executeQuery()) {
            while(rows.next()) {
                if(++rowsSeen>25000)throw new SQLException("Verification window exceeds 25,000 inspections; narrow the window");
                try {
                    var timing=jsonMap(rows.getString(5));
                    Map<String,Object> rpc=inside(timing.get("rpcObservedAt"),scope)?jsonMap(rows.getString(2)):null;
                    Map<String,Object> beacon=inside(timing.get("beaconObservedAt"),scope)?jsonMap(rows.getString(3)):null;
                    var statuses=new ArrayList<Map<String,Object>>();
                    var attempts=rows.getString(4)==null?List.of():JSON.readValue(rows.getString(4),new TypeReference<List<Map<String,Object>>>() {});
                    var times=asList(timing.get("endpointAttemptTimes"));
                    boolean anyAttempt=asList(timing.get("rpcAttemptsTimes")).stream().anyMatch(value->inside(value,scope)) ||
                            asList(timing.get("beaconAttemptsTimes")).stream().anyMatch(value->inside(value,scope)) ||
                            times.stream().anyMatch(value->inside(value,scope));
                    if(!inside(rows.getString(6),scope) && rpc==null && beacon==null && !anyAttempt)continue;
                    for(int i=0;i<Math.min(attempts.size(),times.size());i++) {
                        if(!inside(times.get(i),scope))continue;
                        var attempt=asMap(attempts.get(i));
                        boolean pass=asList(attempt.get("diagnostics")).stream().map(NetworkAnalytics::asMap).anyMatch(stage->
                                "ETH Status".equals(stage.get("name")) && "PASS".equals(stage.get("state")));
                        if(pass) {
                            var status=asMap(asMap(attempt.get("p2p")).get("status"));
                            if(status!=null)statuses.add(status);
                        }
                    }
                    if(statuses.isEmpty())statuses.add(null);
                    var seenComparisons=new HashSet<String>();
                    for(var statusEvidence:statuses) {
                        var result=NetworkVerification.from(rpc,beacon,statusEvidence);
                        String status=String.valueOf(result.get("status"));
                        byStatus.computeIfAbsent(status,ignored->new HashSet<>()).add(rows.getString(1));
                        for(var rawRow:asList(result.get("rows"))) {
                            var row=asMap(rawRow);
                            if(row!=null && "Comparison".equals(row.get("source")) &&
                                    seenComparisons.add(row.get("label")+"|"+row.get("status")))
                                comparisons.merge(String.valueOf(row.get("status")),1L,Long::sum);
                        }
                    }
                }catch(Exception e){throw new SQLException("Cannot derive network verification from inspection evidence",e);}
            }
        }
        var buckets=new ArrayList<Bucket>();
        byStatus.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry->buckets.add(new Bucket(entry.getKey(),entry.getValue().size())));
        metrics.add(metric("network-verification","canonical identities per derived outcome","inspected identities in window; different run outcomes may overlap",identities,
                Math.max(0,identities-byStatus.values().stream().flatMap(java.util.Set::stream).distinct().count()),
                "Existing NetworkVerification comparison rules applied to run evidence; one source remains OBSERVED",buckets));
        var comparisonBuckets=new ArrayList<Bucket>();
        comparisons.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry->comparisonBuckets.add(new Bucket(entry.getKey(),entry.getValue())));
        metrics.add(metric("verification-comparisons","comparison occurrences","independent-interface comparison rows in timestamp-eligible runs",
                total(comparisonBuckets),0,"MATCH and MISMATCH are comparison outcomes; no row is promoted to VERIFIED",comparisonBuckets));
    }
    private static Map<String,Object> jsonMap(String json) throws Exception {
        return json==null?null:JSON.readValue(json,new TypeReference<Map<String,Object>>() {});
    }
    @SuppressWarnings("unchecked")
    private static Map<String,Object> asMap(Object value) {
        return value instanceof Map<?,?> map?(Map<String,Object>)map:null;
    }
    private static List<?> asList(Object value) { return value instanceof List<?> list?list:List.of(); }
    private static boolean inside(Object value,Scope scope) {
        if(value==null)return false;
        try {
            var at=Instant.parse(value.toString());
            return !at.isBefore(scope.startInclusive()) && at.isBefore(scope.endExclusive());
        } catch(Exception ignored) { return false; }
    }

    private static void measureEnrichment(Connection db,Scope scope,List<Metric> metrics) throws SQLException {
        // A lookup is scoped by its own lookup clock and dataset key, never promoted to node location.
        String where=bound("l.looked_up_at");
        for(String field:List.of("country","asn")) {
            var values=buckets(db,"SELECT l.dataset_key||':'||COALESCE(json_extract(l.evidence_json,'$."+field+".status'),'UNKNOWN'),"+
                    "COUNT(DISTINCT l.address) FROM network_enrichment_lookups l WHERE "+where+
                    " GROUP BY 1 ORDER BY 1",scope);
            metrics.add(metric(field+"-lookup-status","address/dataset/status memberships","timestamped lookup address/dataset/status memberships; one address may have multiple contexts or outcomes",total(values),0,
                    "Offline endpoint context only; dataset changes do not imply node movement",values));
        }
        for(String[] dimension:new String[][]{{"country","countryCode"},{"asn","asn"}}) {
            String path="$."+dimension[0]+"."+dimension[1];
            var values=buckets(db,"SELECT l.dataset_key||':'||COALESCE(CAST(json_extract(l.evidence_json,'"+path+"') AS TEXT),'UNKNOWN'),"+
                    "COUNT(DISTINCT l.address) FROM network_enrichment_lookups l WHERE "+where+
                    " AND json_extract(l.evidence_json,'$."+dimension[0]+".status')='FOUND' "+
                    "GROUP BY 1 ORDER BY 2 DESC,1 LIMIT 100",scope);
            long found=scalar(db,"SELECT COALESCE(SUM(n),0) FROM (SELECT COUNT(DISTINCT l.address) n FROM network_enrichment_lookups l WHERE "+where+
                    " AND json_extract(l.evidence_json,'$."+dimension[0]+".status')='FOUND' GROUP BY l.dataset_key,json_extract(l.evidence_json,'"+path+"'))",scope);
            metrics.add(metric(dimension[0]+"-distribution-top-100","normalized address/category memberships per dataset","FOUND address/category memberships in window",found,0,
                    "Top 100 values; omitted tail="+Math.max(0,found-total(values))+"; country is approximate network context, ASN is registration context",values));
        }
        var hosting=buckets(db,"SELECT l.dataset_key||':'||COALESCE(json_extract(l.evidence_json,'$.hostingClassification'),'UNKNOWN'),"+
                "COUNT(DISTINCT l.address) FROM network_enrichment_lookups l WHERE "+where+" GROUP BY 1 ORDER BY 1",scope);
        metrics.add(metric("hosting-classification","address/dataset/status memberships","timestamped hosting-classification address/dataset/status memberships",total(hosting),0,
                "NOT_AVAILABLE unless an independent hosting source exists; ASN organization is not hosting proof",hosting));
    }
}
