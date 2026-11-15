import io.github.gavinruff007.torchnode.discovery.DiscoveryEvidenceBatch;
import java.nio.file.*;
public final class VerifyCanonicalPayloads {
 public static void main(String[] args)throws Exception {
  int count=0;
  for(var fixture:DiscoveryEvidenceBatch.JSON.readTree(Files.readString(Path.of(args[0])))) {
   String actual=DiscoveryEvidenceBatch.canonicalJson(DiscoveryEvidenceBatch.JSON.readTree(fixture.path("input").asText()));
   if(!actual.equals(fixture.path("canonical").asText()) || !DiscoveryEvidenceBatch.hash(actual).equals(fixture.path("sha256").asText()))throw new IllegalStateException("Canonical compatibility mismatch");
   count++;
  }
  if(count!=3)throw new IllegalStateException("Incomplete canonical fixtures");
  System.out.println("Jackson "+DiscoveryEvidenceBatch.JSON.version()+": "+count+" canonical byte/hash fixtures PASS");
 }
}
