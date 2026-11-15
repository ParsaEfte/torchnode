package io.github.gavinruff007.torchnode.dashboard;

import io.github.gavinruff007.torchnode.topology.*;
import io.github.gavinruff007.torchnode.analysis.NetworkAnalytics.Scope;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.core.JsonGenerator;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.sql.SQLException;
import java.time.*;
import java.util.*;

/** Authenticated read-only API; no scanner/helper/inspection operation is called here. */
public final class TopologyServlet extends HttpServlet {
    private static final ObjectMapper JSON=mapper();
    private final TopologyService service;
    private java.util.function.BooleanSupplier collector;
    public TopologyServlet(String databasePath,java.util.function.BooleanSupplier collector){this(databasePath);this.collector=collector;}
    private final java.util.concurrent.Semaphore capacity=new java.util.concurrent.Semaphore(2);
    public TopologyServlet(String databasePath){this(new TopologyService(databasePath));}
    TopologyServlet(TopologyService service){this.service=service;}
    private static ObjectMapper mapper() {
        var module=new SimpleModule();module.addSerializer(Instant.class,new JsonSerializer<Instant>(){
            @Override public void serialize(Instant value,JsonGenerator generator,SerializerProvider provider)throws IOException{generator.writeString(value.toString());}
        });return new ObjectMapper().registerModule(module);
    }
    private static Instant at(HttpServletRequest request,String name,Instant fallback){return request.getParameter(name)==null?fallback:Instant.parse(request.getParameter(name));}
    private static TopologyService.Query query(HttpServletRequest request) {
        Instant end=at(request,"end",Instant.now()),start=at(request,"start",end.minus(Duration.ofDays(1)));
        String relation=request.getParameter("relation");
        return new TopologyService.Query(new Scope(start,end),Objects.requireNonNullElse(request.getParameter("protocol"),"all"),
                relation==null || relation.equals("all")?null:RelationshipObservation.Relation.valueOf(relation),request.getParameter("q"),
                request.getParameter("limit")==null?100:Integer.parseInt(request.getParameter("limit")),request.getParameter("after"),request.getParameter("observer"),request.getParameter("highWatermark")==null?null:Long.valueOf(request.getParameter("highWatermark")),Objects.requireNonNullElse(request.getParameter("availability"),"all"));
    }
    @Override protected void doGet(HttpServletRequest request,HttpServletResponse response)throws IOException {
        response.setHeader("Cache-Control","no-store");response.setContentType("application/json;charset=UTF-8");
        if(!capacity.tryAcquire()){response.setHeader("Retry-After","1");error(response,429,"topology_busy","Two topology reads are already active; retry shortly");return;}
        try {
            if(request.getParameter("mode")!=null)throw new IllegalArgumentException("Topology accepts bounded windows only");
            if(request.getParameter("limit")!=null && Integer.parseInt(request.getParameter("limit"))>TopologyService.MAX_NODES){error(response,413,"graph_limit_exceeded","Maximum graph page size is 200 identities");return;}
            var query=query(request);String path=Objects.requireNonNullElse(request.getPathInfo(),"");Object data;
            if(path.isEmpty() || path.equals("/") || path.equals("/graph") || path.equals("/exports/graph.json")) {
                var graph=service.graph(query);graph.evidenceCoverage().put("collectorStatus",collector==null?"unknown":collector.getAsBoolean()?"running":"not running");data=graph;
                if(path.equals("/exports/graph.json"))response.setHeader("Content-Disposition","attachment; filename=torchnode-topology-evidence-page.json");
            }
            else if(path.equals("/candidates"))data=service.candidates(query);
            else if(path.startsWith("/evidence/")){data=service.persistedEvidence(path.substring(10));if(data==null){error(response,404,"not_found","No persisted evidence");return;}}
            else if(path.equals("/summary")) {
                var graph=service.graph(query);
                var summaryData=new LinkedHashMap<String,Object>(Map.of("scope",graph.scope(),"generatedAt",graph.generatedAt(),"protocol",graph.protocol(),"relationFilter",graph.relationFilter(),"search",graph.search(),"summary",graph.summary(),"coverage",graph.coverage(),"limitation",graph.limitation(),"denominatorMeaning",graph.denominatorMeaning(),"countingUnit",graph.countingUnit()));summaryData.put("evidenceCoverage",graph.evidenceCoverage());summaryData.put("highWatermark",graph.highWatermark());data=summaryData;
            } else if(path.equals("/compare")) {
                Instant end=at(request,"baselineEnd",query.scope().startInclusive());
                Instant start=at(request,"baselineStart",end.minus(Duration.between(query.scope().startInclusive(),query.scope().endExclusive())));
                data=service.compare(query,new Scope(start,end));
            } else {
                String[] parts=path.split("/");
                if(parts.length==4 && parts[1].equals("nodes") && parts[3].equals("neighborhood"))data=service.neighborhood(query,parts[2]);
                else if(parts.length==4 && parts[1].equals("nodes") && parts[3].equals("evidence"))data=service.evidence(query,parts[2]);
                else if(parts.length==3 && parts[1].equals("edges")) {
                    data=service.edgeEvidence(query,parts[2]);if(data==null){error(response,404,"edge_evidence_unavailable","No eligible observation in this scope");return;}
                } else {error(response,404,"not_found","Unknown topology resource");return;}
            }
            JSON.writeValue(response.getWriter(),Map.of("apiVersion","v1","data",data));
        } catch(TopologyService.ScopeLimit e){
            response.setStatus(413);JSON.writeValue(response.getWriter(),Map.of("apiVersion","v1","error",Map.of("code","topology_scope_limit","message",e.getMessage()),
                    "query",Map.of("safetyLimitOccurrences",TopologyService.MAX_OCCURRENCES,"safetyLimitExceeded",true,"queryComplete",false,"aggregateReturned",false,"partialResultReturned",false,"networkCoverageKnown",false)));
        }
        catch(IllegalArgumentException | DateTimeException e){error(response,400,"invalid_topology_query",e.getMessage());}
        catch(SQLException e){
            boolean interrupted=e instanceof java.sql.SQLTimeoutException || e instanceof org.sqlite.SQLiteException sqlite && sqlite.getResultCode()==org.sqlite.SQLiteErrorCode.SQLITE_INTERRUPT;
            error(response,503,interrupted?"topology_query_timeout":"topology_query_unavailable",interrupted?"SQLite execution deadline or cancellation interrupted the query; no aggregate was returned. Narrow the scope and retry.":"Cannot measure this scope within the database query limits; narrow the window or retry");
        }
        catch(Exception e){error(response,500,"topology_read_failed","Unable to read topology evidence");}
        finally{capacity.release();}
    }
    @Override protected void doPost(HttpServletRequest request,HttpServletResponse response)throws IOException {response.setHeader("Allow","GET, HEAD");error(response,405,"method_not_allowed","Topology is read-only");}
    @Override protected void doPut(HttpServletRequest request,HttpServletResponse response)throws IOException{doPost(request,response);}
    @Override protected void doDelete(HttpServletRequest request,HttpServletResponse response)throws IOException{doPost(request,response);}
    private static void error(HttpServletResponse response,int status,String code,String message)throws IOException {
        response.setStatus(status);response.setContentType("application/json;charset=UTF-8");
        JSON.writeValue(response.getWriter(),Map.of("apiVersion","v1","error",Map.of("code",code,"message",Objects.requireNonNullElse(message,"Invalid query"))));
    }
}
