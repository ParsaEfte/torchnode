import io.github.gavinruff007.torchnode.discovery.*;
import io.github.gavinruff007.torchnode.storage.*;
import io.github.gavinruff007.torchnode.model.NodeRecord;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Generates signed fixture evidence locally. No network provider or retained data. */
class CreateSyntheticV6Recovery {
 public static void main(String[] args)throws Exception {
  Path root=Path.of(args[0]).toRealPath(),db=root.resolve("fixture-v6.sqlite");
  if(!root.startsWith(Path.of(System.getProperty("java.io.tmpdir")).toRealPath()) || Files.exists(db) ||
     !DiscoveryEvidenceBatch.JSON.readTree(Files.readString(root.resolve("V6-FIXTURE.json"))).path("synthetic").asBoolean())throw new IllegalArgumentException("Fresh marked temporary synthetic fixture required");
  try(var store=new SqliteNodeStore(db.toString())){store.save(new NodeRecord("192.0.2.1",0,0,"ab".repeat(64)));}
  var raw=DiscoveryEvidenceBatch.JSON.readTree(Files.readString(Path.of(args[1]))).path("trace");
  var run=new ObserverInstance.Run(UUID.fromString(raw.at("/observer/instanceId").asText()),UUID.fromString(raw.at("/observer/runId").asText()),Instant.parse(raw.path("startedAt").asText()).minusSeconds(1),raw.path("localIdentity").asText(),ObserverInstance.KeyLifecycle.EPHEMERAL_FIRST_SEEN);
  var capture=new Discv5EvidenceCapture(run,raw.path("localIdentity").asText());capture.ready(2,raw.path("localIdentity").asText());capture.accept(raw);
  try(var store=new DiscoveryEvidenceStore(db.toString())){
   var result=store.ingest(DiscoveryEvidenceBatch.v5(run,"00000000-0000-0000-0000-000000000003",capture.snapshot()).getFirst());
   if(result.relationshipsInserted()!=1)throw new IllegalStateException("Expected one synthetic attributable relationship");
  }
  System.out.println("Populated synthetic v6 fixture created without an authentication schema or collectors");
 }
}
