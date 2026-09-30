import io.github.gavinruff007.torchnode.model.NodeRecord;
import io.github.gavinruff007.torchnode.storage.SqliteNodeStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Deterministic isolated benchmark; refuses to open any existing path. */
class BenchmarkChangeDetection {
    private static Map<String,Object> fact(String at,boolean pass) {
        return Map.of("rpcAttempts",List.of(Map.of("endpoint","http://192.0.2.1:8545",
                "attemptedAt",at,"tcpOpen",true,"rpcReachable",pass)));
    }
    private static long count(String path,String table) throws Exception {
        try(var db=DriverManager.getConnection("jdbc:sqlite:"+path);var query=db.createStatement();
            var rows=query.executeQuery("SELECT COUNT(*) FROM "+table)){rows.next();return rows.getLong(1);}
    }
    public static void main(String[] args) throws Exception {
        if(args.length!=1 || Files.exists(Path.of(args[0])))throw new IllegalArgumentException("Supply a new temporary database path");
        String path=args[0];var node=new NodeRecord("192.0.2.1",30303,30303,"ab".repeat(64));
        node.setLastSeen(Instant.parse("2025-12-31T00:00:00Z"));
        long heapBefore=Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory();
        try(var store=new SqliteNodeStore(path)){
            store.save(node);
            long dbBefore=Files.size(Path.of(path));
            long baseChanges=count(path,"change_events");
            long started=System.nanoTime();
            for(int i=0;i<1000;i++){
                String at=Instant.parse("2026-01-01T00:00:00Z").plusSeconds(i).toString();
                store.saveInspectionRun(node,String.format("%04d",i),at,at,"benchmark",fact(at,true),
                        null,null,null,List.of(),List.of());
            }
            long repeatMs=(System.nanoTime()-started)/1_000_000;
            long repeatedBytes=Files.size(Path.of(path));
            if(count(path,"inspection_runs")!=1000 || count(path,"inspection_evidence")!=1 ||
                    count(path,"change_events")!=baseChanges)throw new IllegalStateException("Repeated observations created false changes");
            started=System.nanoTime();
            for(int i=0;i<2;i++){
                String at=Instant.parse("2026-01-01T00:00:00Z").plusSeconds(1000+i).toString();
                store.saveInspectionRun(node,"transition-"+i,at,at,"benchmark",fact(at,i!=0),
                        null,null,null,List.of(),List.of());
            }
            long transitionMs=(System.nanoTime()-started)/1_000_000;
            long finalBytes=Files.size(Path.of(path));
            started=System.nanoTime();var changes=store.changeHistory(node.identity(),20,null);
            long boundedUs=(System.nanoTime()-started)/1_000;
            if(changes.stream().filter(e->e.changeType().equals("RPC_PROBE_OUTCOME_CHANGED")).count()!=2)
                throw new IllegalStateException("Two factual transitions not retained");
            started=System.nanoTime();var latest=store.changeHistory(node.identity(),1,null);
            long latestUs=(System.nanoTime()-started)/1_000;
            System.out.println("db_bytes_before="+dbBefore);
            System.out.println("db_bytes_after_repeated="+repeatedBytes);
            System.out.println("db_growth_repeated_bytes="+(repeatedBytes-dbBefore));
            System.out.println("db_bytes_after_transitions="+finalBytes);
            System.out.println("db_growth_transitions_bytes="+(finalBytes-repeatedBytes));
            System.out.println("write_1000_repeated_ms="+repeatMs);
            System.out.println("write_2_transitions_ms="+transitionMs);
            System.out.println("bounded_change_query_us="+boundedUs);
            System.out.println("latest_change_query_us="+latestUs);
            System.out.println("bounded_change_rows="+changes.size());
            System.out.println("latest_change_type="+latest.get(0).changeType());
            System.out.println("inspection_runs="+count(path,"inspection_runs"));
            System.out.println("inspection_evidence="+count(path,"inspection_evidence"));
            System.out.println("change_events="+count(path,"change_events"));
            System.out.println("change_events_before_inspections="+baseChanges);
        }
        long heapAfter=Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory();
        System.out.println("heap_before_bytes="+heapBefore);
        System.out.println("heap_after_bytes="+heapAfter);
    }
}
