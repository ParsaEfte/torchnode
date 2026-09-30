import io.github.gavinruff007.torchnode.enrichment.PublicAddress;
import io.github.gavinruff007.torchnode.inspection.NodeInspector;
import io.github.gavinruff007.torchnode.storage.SqliteNodeStore;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Bounded two-inspection check against one public identity in a database COPY. */
class ValidateHistoricalPublic {
    public static void main(String[] args) throws Exception {
        if(args.length!=1)throw new IllegalArgumentException("Usage: ValidateHistoricalPublic <database-copy>");
        var path=args[0];
        io.github.gavinruff007.torchnode.model.NodeRecord node;
        try(var store=new SqliteNodeStore(path)) {
            node=store.findAll().stream().filter(n->n.identity().available() && PublicAddress.exclusion(n.getIp())==null)
                    .filter(n->n.getP2pEndpoint().port()>0).findFirst().orElseThrow();
            System.out.println("identity="+node.identity().nodeId());System.out.println("target="+node.getP2pEndpoint().hostPort());
        }
        try(var inspection=new NodeInspector()) {
            for(int i=0;i<2;i++) {
                var id=UUID.randomUUID().toString();
                var measurement=inspection.inspectMeasurement(node,System.nanoTime()+TimeUnit.SECONDS.toNanos(10));
                try(var store=new SqliteNodeStore(path)) {
                    store.saveInspectionRun(node,id,measurement.startedAt(),measurement.completedAt(),"bounded-public-validation",
                            measurement.evidence(),null,null,null,List.of(),List.of());
                }
                System.out.println("run_"+(i+1)+"="+id);
                System.out.println("diagnostics_"+(i+1)+"="+measurement.evidence().get("diagnostics"));
                try(var store=new SqliteNodeStore(path)){
                    System.out.println("stored_runs="+store.inspectionHistory(node.identity(),10,null).size());
                }
            }
        }
    }
}
