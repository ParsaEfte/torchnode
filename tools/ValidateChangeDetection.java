import io.github.gavinruff007.torchnode.model.NodeIdentity;
import io.github.gavinruff007.torchnode.storage.SqliteNodeStore;

import java.sql.DriverManager;
import java.util.List;

/** Upgrade and inspect only a caller-supplied SQLite copy, never the live database. */
class ValidateChangeDetection {
    private static void counts(String path,String prefix) throws Exception {
        try(var db=DriverManager.getConnection("jdbc:sqlite:"+path);var sql=db.createStatement()){
            for(String table:List.of("nodes","discovery_observations","enr_observations","p2p_observations",
                    "network_enrichment","network_enrichment_lookups","discovery_endpoint_index",
                    "inspection_runs","inspection_evidence","inspection_enrichment_context","schema_migrations"))
                try(var rows=sql.executeQuery("SELECT COUNT(*) FROM "+table)){
                    rows.next();System.out.println(prefix+table+"="+rows.getLong(1));
                }
            try(var rows=sql.executeQuery("SELECT MAX(version) FROM schema_migrations")){
                rows.next();System.out.println(prefix+"schema_version="+rows.getInt(1));
            }
        }
    }
    public static void main(String[] args) throws Exception {
        if((args.length!=1 && args.length!=2) || !java.nio.file.Files.isRegularFile(java.nio.file.Path.of(args[0])))
            throw new IllegalArgumentException("Supply an existing isolated database copy and optional canonical identity to rebuild");
        String path=args[0];counts(path,"before_");
        String sampleId=args.length==2?args[1]:null;
        try(var db=DriverManager.getConnection("jdbc:sqlite:"+path);var sql=db.createStatement();
            var rows=sql.executeQuery("SELECT node_id FROM inspection_runs ORDER BY started_at DESC,id DESC LIMIT 1")){
            if(rows.next() && sampleId==null)sampleId=rows.getString(1);
        }
        for(int pass=1;pass<=2;pass++){
            long started=System.nanoTime();
            try(var store=new SqliteNodeStore(path)){
                System.out.println("open_"+pass+"_ms="+(System.nanoTime()-started)/1_000_000);
                if(sampleId!=null){
                    var id=new NodeIdentity(sampleId);
                    if(pass==1 && args.length==2)store.rebuildChanges(id);
                    System.out.println("sample_inspection_rows="+store.inspectionHistory(id,20,null).size());
                    System.out.println("sample_change_rows="+store.changeHistory(id,20,null).size());
                    for(var event:store.changeHistory(id,20,null))System.out.println("sample_change="+event.changeType()+"|"+
                            event.previousObservationId()+"|"+event.currentObservationId());
                }
            }
        }
        counts(path,"after_");
        try(var db=DriverManager.getConnection("jdbc:sqlite:"+path);var sql=db.createStatement()){
            try(var rows=sql.executeQuery("SELECT COUNT(*) FROM change_events")){
                rows.next();System.out.println("change_events="+rows.getLong(1));
            }
            try(var rows=sql.executeQuery("PRAGMA integrity_check")){
                rows.next();System.out.println("integrity="+rows.getString(1));
            }
            try(var rows=sql.executeQuery("PRAGMA foreign_key_check")){
                System.out.println("foreign_key_violations="+(rows.next()?"present":"none"));
            }
        }
    }
}
