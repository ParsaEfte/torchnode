import io.github.gavinruff007.torchnode.storage.SqliteNodeStore;
import java.sql.DriverManager;
import java.util.List;

/** Opens only a caller-supplied database COPY; never point this at a live database. */
class ValidateHistoricalObservations {
    public static void main(String[] args) throws Exception {
        if(args.length!=1)throw new IllegalArgumentException("Usage: ValidateHistoricalObservations <database-copy>");
        for(int pass=1;pass<=2;pass++) {
            long started=System.nanoTime();
            try(var store=new SqliteNodeStore(args[0])) {
                System.out.println("open_"+pass+"_ms="+(System.nanoTime()-started)/1_000_000);
                System.out.println("nodes="+store.count());
                long latestStart=System.nanoTime();
                var nodes=store.findAll();
                System.out.println("latest_load_ms="+(System.nanoTime()-latestStart)/1_000_000);
                if(!nodes.isEmpty()){
                    long queryStart=System.nanoTime();
                    var history=store.inspectionHistory(nodes.get(0).identity(),10,null);
                    System.out.println("sample_inspection_history="+history.size());
                    System.out.println("bounded_history_us="+(System.nanoTime()-queryStart)/1_000);
                    queryStart=System.nanoTime();
                    System.out.println("sample_endpoint_history="+store.endpointHistory(nodes.get(0).getIp(),10,0).size());
                    System.out.println("bounded_endpoint_us="+(System.nanoTime()-queryStart)/1_000);
                }
            }
        }
        try(var db=DriverManager.getConnection("jdbc:sqlite:"+args[0]);var statement=db.createStatement()) {
            for(String table:List.of("nodes","discovery_observations","discovery_endpoint_index","enr_observations",
                    "p2p_observations","network_enrichment","network_enrichment_lookups","inspection_runs",
                    "inspection_evidence","inspection_enrichment_context","schema_migrations"))
                try(var rows=statement.executeQuery("SELECT COUNT(*) FROM "+table)){
                    rows.next();System.out.println(table+"="+rows.getLong(1));
                }
            try(var rows=statement.executeQuery("PRAGMA integrity_check")){rows.next();System.out.println("integrity="+rows.getString(1));}
            try(var rows=statement.executeQuery("PRAGMA foreign_key_check")){System.out.println("foreign_key_violations="+(rows.next()?"present":"none"));}
        }
    }
}
