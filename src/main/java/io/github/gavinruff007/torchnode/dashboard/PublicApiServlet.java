package io.github.gavinruff007.torchnode.dashboard;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.core.JsonGenerator;
import io.github.gavinruff007.torchnode.analysis.NetworkAnalytics;
import io.github.gavinruff007.torchnode.enr.EnrEvidence;
import io.github.gavinruff007.torchnode.model.NodeIds;
import io.github.gavinruff007.torchnode.model.NodeIdentity;
import io.github.gavinruff007.torchnode.storage.SqliteNodeStore;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Stable read-only representation of selected evidence; no UI JSON model is part of this contract. */
public final class PublicApiServlet extends HttpServlet {
    private static final int DEFAULT_LIMIT = 20, MAX_LIMIT = 50, MAX_EXPORT = 99;
    private static final Set<String> DOMAINS = Set.of("discovery", "enr", "enrichment");
    private static final ObjectMapper JSON = mapper();
    private final String databasePath;
    private final ScannerService scannerService;

    public PublicApiServlet(String databasePath, ScannerService scannerService) {
        this.databasePath = databasePath;
        this.scannerService = scannerService;
    }

    private static ObjectMapper mapper() {
        var module = new SimpleModule();
        module.addSerializer(Instant.class, new JsonSerializer<Instant>() {
            @Override public void serialize(Instant value, JsonGenerator generator, SerializerProvider provider) throws IOException {
                generator.writeString(value.toString());
            }
        });
        return new ObjectMapper().registerModule(module);
    }

    @Override protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setHeader("Cache-Control", "no-store");
        String path = request.getPathInfo() == null ? "" : request.getPathInfo();
        try {
            synchronized (scannerService) {
                route(path, request, response);
            }
        } catch (ApiError e) {
            error(response, e.status, e.code, e.getMessage());
        } catch (Exception e) {
            error(response, 500, "internal_error", "Unable to read evidence");
        }
    }

    @Override protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Allow", "GET, HEAD");
        error(response, 405, "method_not_allowed", "Public API v1 is read-only");
    }
    @Override protected void doPut(HttpServletRequest request, HttpServletResponse response) throws IOException { doPost(request,response); }
    @Override protected void doDelete(HttpServletRequest request, HttpServletResponse response) throws IOException { doPost(request,response); }

    private void route(String path, HttpServletRequest request, HttpServletResponse response) throws Exception {
        if (path.isEmpty() || "/".equals(path)) {
            json(response, Map.of("apiVersion", "v1", "application", "TorchNode Observatory",
                    "resources", List.of("/api/v1/identities", "/api/v1/identities/{identity}",
                            "/api/v1/identities/{identity}/evidence/{domain}",
                            "/api/v1/identities/{identity}/runs", "/api/v1/identities/{identity}/changes",
                            "/api/v1/analytics", "/api/v1/exports/analytics.csv"),
                    "documentation", "/api/v1/help", "defaultPageSize", DEFAULT_LIMIT,
                    "maxPageSize", MAX_LIMIT, "maxExportRows", MAX_EXPORT));
            return;
        }
        if ("/help".equals(path)) { help(response); return; }
        if ("/identities".equals(path)) { identities(request,response); return; }
        if ("/analytics".equals(path)) { json(response, Map.of("apiVersion","v1","data",analytics(request))); return; }
        if ("/exports/analytics.csv".equals(path)) { analyticsCsv(request,response); return; }
        String[] parts = path.split("/");
        if (parts.length < 3 || !"identities".equals(parts[1])) throw new ApiError(404,"not_found","Resource not found");
        String id = identity(parts[2]);
        if (parts.length == 3) { detail(id,response); return; }
        if (parts.length == 4 && "exports".equals(parts[3])) throw new ApiError(404,"not_found","Resource not found");
        if (parts.length == 4 && "runs".equals(parts[3])) { history(id,"runs",request,response,false); return; }
        if (parts.length == 4 && "changes".equals(parts[3])) { history(id,"changes",request,response,false); return; }
        if (parts.length == 5 && "runs".equals(parts[3])) { occurrence(id,"runs",parts[4],response); return; }
        if (parts.length == 6 && "evidence".equals(parts[3]) &&
                Set.of("discovery","enr").contains(parts[4])) {
            occurrence(id,parts[4],parts[5],response); return;
        }
        if (parts.length == 5 && "evidence".equals(parts[3]) && DOMAINS.contains(parts[4])) {
            history(id,parts[4],request,response,false); return;
        }
        if (parts.length == 5 && "exports".equals(parts[3]) && parts[4].endsWith(".csv")) {
            String kind=parts[4].substring(0,parts[4].length()-4);
            if (!Set.of("discovery","enr","enrichment","runs","changes").contains(kind))
                throw new ApiError(404,"not_found","Export not found");
            history(id,kind,request,response,true); return;
        }
        throw new ApiError(404,"not_found","Resource not found");
    }

    private static String identity(String raw) {
        if (raw.length() > 130) throw new ApiError(400,"invalid_identity","Identity must be a 128-digit public key hex value");
        String normalized=NodeIds.normalize(raw);
        if (normalized.isEmpty()) throw new ApiError(400,"invalid_identity","Identity must be a 128-digit public key hex value");
        return normalized;
    }

    private Connection readOnly() throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:"+Path.of(databasePath).toAbsolutePath().toUri()+"?mode=ro");
    }

    private void identities(HttpServletRequest request,HttpServletResponse response) throws Exception {
        int limit=limit(request,MAX_LIMIT);
        String cursor=decode(request.getParameter("cursor"));
        if(cursor!=null && !cursor.matches("[0-9a-f]{128}")) throw new ApiError(400,"invalid_cursor","Cursor is invalid");
        if(cursor!=null && !exists(cursor))throw new ApiError(400,"invalid_cursor","Cursor is invalid or expired");
        var data=new ArrayList<Map<String,Object>>();
        // Distinct public-key identities are indexed in discovery evidence and latest projections.
        String sql="SELECT node_id FROM (SELECT node_id FROM discovery_observations UNION SELECT node_id FROM nodes " +
                "UNION SELECT node_id FROM inspection_runs UNION SELECT node_id FROM enr_observations) " +
                "WHERE length(node_id)=128 AND node_id NOT GLOB '*[^0-9a-f]*' " +
                "AND (? IS NULL OR node_id>?) ORDER BY node_id LIMIT ?";
        try(var connection=readOnly();var query=connection.prepareStatement(sql)) {
            query.setString(1,cursor);query.setString(2,cursor);query.setInt(3,limit+1);
            try(var rows=query.executeQuery()) { while(rows.next()) data.add(Map.of("identity",rows.getString(1),
                    "links",Map.of("self","/api/v1/identities/"+rows.getString(1)))); }
        }
        boolean more=data.size()>limit;
        if(more)data.remove(data.size()-1);
        String next=more?encode((String)data.get(data.size()-1).get("identity")):null;
        json(response,collection(data,limit,next));
    }

    private void detail(String id,HttpServletResponse response) throws Exception {
        var projections=new ArrayList<Map<String,Object>>();
        try(var connection=readOnly();var query=connection.prepareStatement("""
                SELECT key,last_seen,discovery_source,node_type,client_version FROM nodes
                WHERE node_id=? ORDER BY last_seen DESC,key LIMIT 5
                """)) {
            query.setString(1,id);
            try(var rows=query.executeQuery()) { while(rows.next()) {
                var row=new LinkedHashMap<String,Object>();
                row.put("observedAt",rows.getObject("last_seen")==null?null:Instant.ofEpochSecond(rows.getLong("last_seen")).toString());
                row.put("discoverySource",rows.getString("discovery_source"));
                row.put("nodeType",rows.getString("node_type"));
                row.put("clientVersion",rows.getString("client_version"));
                projections.add(row);
            }}
        }
        if(!exists(id))throw new ApiError(404,"identity_not_found","Identity not found");
        var latest=projections.isEmpty()?null:projections.get(0);
        var data=new LinkedHashMap<String,Object>();
        data.put("identity",id);
        data.put("latestProjection",latest);
        data.put("projectionMeaning","Selected latest projection row; endpoint and historical evidence are separately queryable");
        data.put("links",Map.of("discovery","/api/v1/identities/"+id+"/evidence/discovery",
                "enr","/api/v1/identities/"+id+"/evidence/enr",
                "enrichment","/api/v1/identities/"+id+"/evidence/enrichment",
                "runs","/api/v1/identities/"+id+"/runs",
                "changes","/api/v1/identities/"+id+"/changes"));
        json(response,Map.of("apiVersion","v1","data",data));
    }

    private boolean exists(String id) throws SQLException {
        try(var connection=readOnly();var query=connection.prepareStatement("""
                SELECT 1 FROM discovery_observations WHERE node_id=? UNION
                SELECT 1 FROM nodes WHERE node_id=? UNION
                SELECT 1 FROM inspection_runs WHERE node_id=? UNION
                SELECT 1 FROM enr_observations WHERE node_id=? LIMIT 1
                """)) {
            for(int n=1;n<=4;n++)query.setString(n,id);
            try(var rows=query.executeQuery()){return rows.next();}
        }
    }

    private void history(String id,String kind,HttpServletRequest request,HttpServletResponse response,boolean csv) throws Exception {
        int limit=limit(request,csv?MAX_EXPORT:MAX_LIMIT,csv?MAX_EXPORT:DEFAULT_LIMIT);
        String cursor=decode(request.getParameter("cursor"));
        if(cursor!=null && cursor.length()>128)throw new ApiError(400,"invalid_cursor","Cursor is invalid");
        if(!exists(id))throw new ApiError(404,"identity_not_found","Identity not found");
        validateCursor(id,kind,cursor);
        var identity=new NodeIdentity(id);
        var data=new ArrayList<Map<String,Object>>();
        try(var store=new SqliteNodeStore(databasePath,true)) {
            int fetch=limit+1;
            switch(kind) {
                case "discovery" -> {
                    long before=cursor==null?0:Long.parseLong(cursor);
                    for(var row:store.discoveryHistory(identity,fetch,before)) {
                        var item=new LinkedHashMap<String,Object>();
                        item.put("id",Long.toString(row.id()));item.put("evidenceType","discovery");
                        item.put("identity",id);item.put("observedAt",row.observation().observedAt());
                        item.put("source",row.observation().source());item.put("provenance",row.observation().provenance());
                        item.put("endpoints",row.observation().endpoints());data.add(item);
                    }
                }
                case "enr" -> { for(var row:store.enrHistory(identity,fetch,cursor==null?0:Long.parseLong(cursor))) {
                    var e=row.evidence();var item=new LinkedHashMap<String,Object>();
                    item.put("id",Long.toString(row.id()));item.put("evidenceType","enr");item.put("identity",id);
                    item.put("observedAt",e.observedAt());item.put("provenance",e.provenance());item.put("outcome",e.outcome());
                    item.put("signature",e.signature());item.put("identityComparison",e.identityComparison());
                    item.put("structurallyValid",e.structurallyValid());item.put("usable",e.usable());
                    item.put("record",e.record());data.add(item);
                }}
                case "enrichment" -> { for(var row:store.enrichmentHistory(identity,fetch,cursor)) {
                    var item=new LinkedHashMap<String,Object>();item.put("id",row.lookupId());
                    item.put("evidenceType","enrichment");item.put("identity",id);item.put("address",row.address());
                    item.put("datasetKey",row.datasetKey());item.put("lookedUpAt",row.lookedUpAt());
                    item.put("evidence",row.evidence());data.add(item);
                }}
                case "runs" -> { for(var row:store.inspectionHistory(identity,fetch,cursor)) {
                    var item=new LinkedHashMap<String,Object>();item.put("id",row.id());item.put("evidenceType","inspectionRun");
                    item.put("identity",id);item.put("startedAt",row.startedAt());item.put("completedAt",row.completedAt());
                    item.put("trigger",row.trigger());item.put("discoverySource",row.discoverySource());
                    item.put("evidence",row.evidence());data.add(item);
                }}
                case "changes" -> { for(var row:store.changeHistory(identity,fetch,cursor)) {
                    var item=new LinkedHashMap<String,Object>();item.put("id",row.id());item.put("evidenceType","derivedChange");
                    item.put("derived",true);item.put("identity",id);item.put("domain",row.domain());
                    item.put("changeType",row.changeType());item.put("subject",row.subject());
                    item.put("observationKind",row.observationKind());item.put("previousObservationId",row.previousObservationId());
                    item.put("currentObservationId",row.currentObservationId());item.put("previousObservedAt",row.previousObservedAt());
                    item.put("currentObservedAt",row.currentObservedAt());item.put("previousValue",row.previousValue());
                    item.put("currentValue",row.currentValue());item.put("source",row.source());
                    item.put("endpoint",row.endpoint());item.put("addressFamily",row.addressFamily());
                    item.put("derivationVersion",row.derivationVersion());
                    item.put("previousSource",sourceLink(id,row.observationKind(),row.previousObservationId()));
                    item.put("currentSource",sourceLink(id,row.observationKind(),row.currentObservationId()));
                    data.add(item);
                }}
                default -> throw new ApiError(404,"not_found","Resource not found");
            }
        }
        boolean more=data.size()>limit;if(more)data.remove(data.size()-1);
        String next=more?encode((String)data.get(data.size()-1).get("id")):null;
        if(csv) csv(response,kind,id,data,limit,next);
        else json(response,collection(data,limit,next));
    }

    private static String sourceLink(String identity,String kind,String occurrence) {
        if(occurrence==null)return null;
        String family=switch(kind) {
            case "DISCOVERY" -> "evidence/discovery";
            case "ENR" -> "evidence/enr";
            case "INSPECTION" -> "runs";
            default -> null;
        };
        return family==null?null:"/api/v1/identities/"+identity+"/"+family+"/"+occurrence;
    }

    private void occurrence(String id,String kind,String occurrence,HttpServletResponse response) throws Exception {
        if(occurrence.length()>128)throw new ApiError(400,"invalid_occurrence","Occurrence ID is invalid");
        if(!exists(id))throw new ApiError(404,"identity_not_found","Identity not found");
        if("runs".equals(kind)) {
            try(var store=new SqliteNodeStore(databasePath,true)) {
                var run=store.findInspectionRun(occurrence);
                if(run.isEmpty() || !id.equals(run.get().nodeId()))throw new ApiError(404,"occurrence_not_found","Occurrence not found");
                var row=run.get();var data=new LinkedHashMap<String,Object>();
                data.put("id",row.id());data.put("identity",id);data.put("evidenceType","inspectionRun");
                data.put("startedAt",row.startedAt());data.put("completedAt",row.completedAt());
                data.put("trigger",row.trigger());data.put("discoverySource",row.discoverySource());
                data.put("evidence",row.evidence());json(response,Map.of("apiVersion","v1","data",data));return;
            }
        }
        if(!occurrence.matches("[1-9][0-9]{0,17}"))throw new ApiError(400,"invalid_occurrence","Occurrence ID is invalid");
        String table="discovery".equals(kind)?"discovery_observations":"enr_observations";
        try(var connection=readOnly();var query=connection.prepareStatement("SELECT * FROM "+table+" WHERE node_id=? AND id=? LIMIT 1")) {
            query.setString(1,id);query.setString(2,occurrence);
            try(var rows=query.executeQuery()) {
                if(!rows.next())throw new ApiError(404,"occurrence_not_found","Occurrence not found");
                var data=new LinkedHashMap<String,Object>();data.put("id",occurrence);data.put("identity",id);
                data.put("evidenceType",kind);data.put("observedAt",rows.getString("observed_at"));
                data.put("provenance",rows.getString("provenance"));
                if("discovery".equals(kind)) {
                    data.put("source",rows.getString("source"));
                    data.put("endpoints",JSON.readValue(rows.getString("endpoints_json"),new TypeReference<List<Map<String,Object>>>() {}));
                } else {
                    var e=EnrEvidence.fromMap(JSON.readValue(rows.getString("evidence_json"),new TypeReference<Map<String,Object>>() {}),JSON);
                    data.put("outcome",e.outcome());data.put("signature",e.signature());
                    data.put("identityComparison",e.identityComparison());data.put("structurallyValid",e.structurallyValid());
                    data.put("usable",e.usable());data.put("record",e.record());
                }
                json(response,Map.of("apiVersion","v1","data",data));
            }
        }
    }

    private void validateCursor(String id,String kind,String cursor) throws SQLException {
        if(cursor==null)return;
        String table=switch(kind) {
            case "discovery" -> "discovery_observations"; case "enr" -> "enr_observations";
            case "enrichment" -> "network_enrichment_lookups"; case "runs" -> "inspection_runs";
            case "changes" -> "change_events"; default -> throw new ApiError(400,"invalid_cursor","Cursor is invalid");
        };
        if(Set.of("discovery","enr").contains(kind) && !cursor.matches("[1-9][0-9]{0,17}"))
            throw new ApiError(400,"invalid_cursor","Cursor is invalid");
        String sql="enrichment".equals(kind)?"SELECT 1 FROM network_enrichment_lookups WHERE lookup_id=? AND address IN " +
                "(SELECT address FROM discovery_endpoint_index WHERE node_id=?) LIMIT 1":
                "SELECT 1 FROM "+table+" WHERE id=? AND node_id=? LIMIT 1";
        try(var connection=readOnly();var query=connection.prepareStatement(sql)) {
            query.setString(1,cursor);query.setString(2,id);
            try(var rows=query.executeQuery()){if(!rows.next())throw new ApiError(400,"invalid_cursor","Cursor is invalid or expired");}
        }
    }

    private NetworkAnalytics.Report analytics(HttpServletRequest request) throws Exception {
        String mode=request.getParameter("mode");
        if(mode!=null && !"all".equals(mode))throw new ApiError(400,"invalid_window","mode must be all or omitted");
        if("all".equals(mode) && (request.getParameter("start")!=null || request.getParameter("end")!=null))
            throw new ApiError(400,"invalid_window","All available mode cannot have bounds");
        NetworkAnalytics.Scope scope;
        try {
            if("all".equals(mode))scope=NetworkAnalytics.Scope.allAvailable();
            else {
                Instant end=request.getParameter("end")==null?Instant.now():Instant.parse(request.getParameter("end"));
                Instant start=request.getParameter("start")==null?end.minus(1,ChronoUnit.DAYS):Instant.parse(request.getParameter("start"));
                scope=new NetworkAnalytics.Scope(start,end);
            }
        } catch (Exception e) {throw new ApiError(400,"invalid_window","UTC window must be positive and at most 31 days");}
        try {return new NetworkAnalytics(databasePath).measure(scope);}
        catch(SQLException e){
            if(e.getMessage()!=null && (e.getMessage().contains("safety limit") || e.getMessage().contains("25,000 inspections")))
                throw new ApiError(413,"analytics_limit_exceeded","Analytics evidence-row safety limit exceeded");
            throw e;
        }
    }

    private void analyticsCsv(HttpServletRequest request,HttpServletResponse response) throws Exception {
        var report=analytics(request);
        response.setContentType("text/csv;charset=UTF-8");
        response.setHeader("Content-Disposition","attachment; filename=torchnode-analytics-v1.csv");
        response.setHeader("X-TorchNode-API-Version","v1");
        response.setHeader("X-TorchNode-Generated-At",report.generatedAt().toString());
        var writer=response.getWriter();
        writer.println("scope_mode,start_inclusive,end_exclusive,metric_id,counting_unit,denominator_meaning,denominator,unknown,bucket_semantics,bucket_label,count");
        for(var metric:report.metrics())for(var bucket:metric.buckets())
            writer.println(String.join(",",csvCell(report.scope().mode()),csvCell(report.scope().mode()==NetworkAnalytics.Mode.ALL_AVAILABLE?null:report.scope().startInclusive()),
                    csvCell(report.scope().mode()==NetworkAnalytics.Mode.ALL_AVAILABLE?null:report.scope().endExclusive()),
                    csvCell(metric.id()),csvCell(metric.countingUnit()),csvCell(metric.denominatorMeaning()),
                    csvCell(metric.denominator()),csvCell(metric.unknown()),csvCell(metric.bucketSemantics()),
                    csvCell(bucket.label()),csvCell(bucket.count())));
    }

    private void csv(HttpServletResponse response,String kind,String id,List<Map<String,Object>> rows,int limit,String next) throws IOException {
        if(next!=null)throw new ApiError(413,"export_limit_exceeded","Export exceeds 99 rows; use paginated JSON");
        response.setContentType("text/csv;charset=UTF-8");
        response.setHeader("Content-Disposition","attachment; filename=torchnode-"+kind+"-v1.csv");
        response.setHeader("X-TorchNode-API-Version","v1");
        response.setHeader("X-TorchNode-Generated-At",Instant.now().toString());
        response.setHeader("X-TorchNode-Row-Unit",kind.equals("runs")?"inspection run":kind.equals("changes")?"derived change event":"evidence occurrence");
        var writer=response.getWriter();
        writer.println("api_version,identity,evidence_type,occurrence_id,observed_at,source,payload_json");
        for(var row:rows) {
            Object time=row.containsKey("observedAt")?row.get("observedAt"):
                    row.containsKey("lookedUpAt")?row.get("lookedUpAt"):
                    row.containsKey("startedAt")?row.get("startedAt"):row.get("currentObservedAt");
            String observed=String.valueOf(time);
            if("null".equals(observed))observed=null;
            writer.println(String.join(",",csvCell("v1"),csvCell(id),csvCell(row.get("evidenceType")),
                    csvCell(row.get("id")),csvCell(observed),csvCell(row.containsKey("source")?row.get("source"):
                            row.containsKey("discoverySource")?row.get("discoverySource"):kind.equals("enr")?"ENR":null),
                    csvCell(JSON.writeValueAsString(row))));
        }
    }

    private static String csvCell(Object value) {
        if(value==null)return "";
        String s=value.toString();
        if(!s.isEmpty() && "=+-@".indexOf(s.charAt(0))>=0)s="'"+s;
        return "\""+s.replace("\"","\"\"")+"\"";
    }

    private static int limit(HttpServletRequest request,int max) {return limit(request,max,DEFAULT_LIMIT);}
    private static int limit(HttpServletRequest request,int max,int fallback) {
        String raw=request.getParameter("limit");
        if(raw==null)return fallback;
        if(!raw.matches("[1-9][0-9]{0,3}"))throw new ApiError(400,"invalid_limit","limit must be a positive integer");
        int value=Integer.parseInt(raw);
        if(value>max)throw new ApiError(413,"limit_exceeded","limit exceeds "+max);
        return value;
    }
    private static String encode(String raw) {return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));}
    private static String decode(String raw) {
        if(raw==null)return null;
        if(raw.length()>256 || !raw.matches("[A-Za-z0-9_-]+"))throw new ApiError(400,"invalid_cursor","Cursor is invalid");
        try {return new String(Base64.getUrlDecoder().decode(raw),StandardCharsets.UTF_8);}
        catch(IllegalArgumentException e){throw new ApiError(400,"invalid_cursor","Cursor is invalid");}
    }
    private static Map<String,Object> collection(List<Map<String,Object>> data,int limit,String next) {
        var pagination=new LinkedHashMap<String,Object>();pagination.put("limit",limit);pagination.put("nextCursor",next);
        return Map.of("apiVersion","v1","data",data,"pagination",pagination);
    }
    private static void json(HttpServletResponse response,Object value) throws IOException {
        response.setContentType("application/json;charset=UTF-8");JSON.writeValue(response.getWriter(),value);
    }
    private static void error(HttpServletResponse response,int status,String code,String message) throws IOException {
        response.setStatus(status);json(response,Map.of("apiVersion","v1","error",Map.of("code",code,"message",message)));
    }
    private static void help(HttpServletResponse response) throws IOException {
        response.setContentType("text/html;charset=UTF-8");
        response.setHeader("Content-Security-Policy","default-src 'none'; style-src 'unsafe-inline'");
        response.getWriter().print("<!doctype html><html lang=\"en\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>TorchNode API v1</title>"+
                "<style>body{font:1rem system-ui;max-width:52rem;margin:auto;padding:1rem;line-height:1.5}code{overflow-wrap:anywhere}a:focus{outline:3px solid #48f}</style>"+
                "<h1>TorchNode API v1</h1><p>Read-only evidence from this local datastore. See <a href=\"/api/v1\">API discovery</a> and the <a href=\"/reports/network\">network report</a>.</p>"+
                "<p>Identity is a cryptographic public key, never an IP. Latest projection is a convenience view. Discovery, ENR, enrichment, inspection runs and derived changes are separate evidence domains.</p>"+
                "<p>GET <code>/api/v1/identities?limit=20</code>; follow opaque <code>nextCursor</code> with <code>cursor=</code>. Page limit 50. Identity evidence and CSV exports require a full identity. CSV exports reject over 99 rows.</p>"+
                "<p>GET <code>/api/v1/identities/{identity}/evidence/discovery</code>, <code>enr</code>, <code>enrichment</code>, <code>runs</code>, <code>changes</code>. CSV: <code>/api/v1/identities/{identity}/exports/{domain}.csv</code>.</p>"+
                "<p>Analytics: <a href=\"/api/v1/analytics\">/api/v1/analytics</a> and <a href=\"/api/v1/exports/analytics.csv\">analytics CSV</a>. UTC time windows are [start,end), at most 31 days; mode=all uses existing safety limits.</p>"+
                "<p>Unknown values remain null or explicit statuses. Ties are deterministically ordered, not causal. API v1 may gain fields and enum values; breaking changes require a future version. SQLite schema v5 is independent.</p>"+
                "<p>Responses use UTF-8 and no-store. Errors contain code and message. CORS and rate limiting are absent; deployment remains local/operator controlled.</p></html>");
    }
    private static final class ApiError extends RuntimeException {
        final int status;final String code;
        ApiError(int status,String code,String message){super(message);this.status=status;this.code=code;}
    }
}
