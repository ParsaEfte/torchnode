import io.github.gavinruff007.torchnode.model.NodeRecord;
import io.github.gavinruff007.torchnode.storage.SqliteNodeStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Synthetic append/query benchmark; run only against a new temporary path. */
class BenchmarkHistoricalObservations {
    public static void main(String[] args) throws Exception {
        if(args.length!=1 || Files.exists(Path.of(args[0])))
            throw new IllegalArgumentException("Supply a new temporary database path");
        var node=new NodeRecord("192.0.2.1",30303,30303,"ab".repeat(64));
        long before=Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory();
        long dbBytesBefore;
        try(var store=new SqliteNodeStore(args[0])) {
            store.save(node);
            dbBytesBefore=Files.size(Path.of(args[0]));
            System.out.println("db_bytes_before="+dbBytesBefore);
            var facts=Map.<String,Object>of("diagnostics",List.of(Map.of("name","P2P TCP","state","FAILED",
                    "reasonCode","TCP_CONNECTION_FAILED")),"endpointAttempts",List.of(),
                    "rpcProbeEndpoints",List.of(),"beaconProbeEndpoints",List.of());
            long started=System.nanoTime();
            for(int i=0;i<1000;i++){
                String at=Instant.parse("2026-01-01T00:00:00Z").plusSeconds(i).toString();
                store.saveInspectionRun(node,String.format("%04d",i),at,at,"benchmark",facts,null,null,null,List.of(),List.of());
            }
            System.out.println("write_1000_ms="+(System.nanoTime()-started)/1_000_000);
            started=System.nanoTime();
            var page=store.inspectionHistory(node.identity(),20,null);
            System.out.println("bounded_query_us="+(System.nanoTime()-started)/1_000);
            if(page.size()!=20 || !page.get(0).id().equals("0999") || !page.get(19).id().equals("0980"))
                throw new IllegalStateException("Bounded history order/size incorrect");
            System.out.println("bounded_query_rows="+page.size());
            System.out.println("bounded_query_first="+page.get(0).id());
            System.out.println("bounded_query_last="+page.get(19).id());
            System.out.println("next_page_first="+store.inspectionHistory(node.identity(),20,page.get(19).id()).get(0).id());
            System.out.println("recent_rows="+store.recentInspectionHistory(20).size());
            started=System.nanoTime();
            var latest=store.findAll();
            System.out.println("latest_query_us="+(System.nanoTime()-started)/1_000);
            if(latest.size()!=1 || !latest.get(0).getKey().equals(node.getKey()))
                throw new IllegalStateException("Latest projection incorrect");
            System.out.println("latest_projection_rows="+latest.size());
        }
        long after=Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory();
        System.out.println("heap_used_before_bytes="+before);System.out.println("heap_used_after_bytes="+after);
        long dbBytesAfter=Files.size(Path.of(args[0]));
        System.out.println("db_bytes_after="+dbBytesAfter);
        System.out.println("db_growth_bytes="+(dbBytesAfter-dbBytesBefore));
        try(var db=java.sql.DriverManager.getConnection("jdbc:sqlite:"+args[0]);var sql=db.createStatement()){
            for(String table:List.of("inspection_runs","inspection_evidence"))try(var rows=sql.executeQuery("SELECT COUNT(*) FROM "+table)){
                rows.next();System.out.println(table+"="+rows.getLong(1));
            }
            try(var rows=sql.executeQuery("SELECT COUNT(*),COUNT(DISTINCT evidence_hash) FROM inspection_runs")){
                rows.next();
                if(rows.getLong(1)!=1000 || rows.getLong(2)!=1)throw new IllegalStateException("Occurrence/reference mismatch");
                System.out.println("occurrences_referencing_payload="+rows.getLong(1));
                System.out.println("distinct_referenced_payloads="+rows.getLong(2));
            }
        }
    }
}
