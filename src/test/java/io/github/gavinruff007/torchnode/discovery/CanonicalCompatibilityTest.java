package io.github.gavinruff007.torchnode.discovery;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class CanonicalCompatibilityTest {
 @Test void preservesHistoricalJackson2171BytesAndHashes() throws Exception {
  try(var in=getClass().getResourceAsStream("/canonical-jackson-2.17.1.json")) {
   for(var fixture:DiscoveryEvidenceBatch.JSON.readTree(in)) {
    String actual=DiscoveryEvidenceBatch.canonicalJson(DiscoveryEvidenceBatch.JSON.readTree(fixture.path("input").asText()));
    assertEquals(fixture.path("canonical").asText(),actual);
    assertEquals(fixture.path("sha256").asText(),DiscoveryEvidenceBatch.hash(actual));
   }
  }
  assertThrows(Exception.class,()->DiscoveryEvidenceBatch.JSON.readTree("{\"a\":1,\"a\":2}"));
 }
}
