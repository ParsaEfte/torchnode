package io.github.gavinruff007.torchnode.enrichment;

import io.github.gavinruff007.torchnode.model.*;
import java.time.Instant;

/** Address/dataset evidence, never a property of cryptographic identity. */
public record NetworkEnrichment(String address, NodeEndpoint.AddressFamily addressFamily,
        String datasetKey, Lookup country, Lookup asn, String hostingClassification) {
    public enum Status { FOUND, NOT_FOUND, NOT_APPLICABLE, LOOKUP_FAILED, DATASET_UNAVAILABLE }
    public record Lookup(Status status, String reason, String countryCode, String countryName,
            Long asn, String organization, String networkPrefix, String lookedUpAt,
            String dataSource, String dataSourceVersion, String provenance) {}
    public NetworkEnrichment {
        address=EndpointAddress.parse(address).getHostAddress();
        if(EndpointAddress.family(address)!=addressFamily)throw new IllegalArgumentException("Wrong address family");
        if(!"NOT_AVAILABLE".equals(hostingClassification))throw new IllegalArgumentException("Hosting classification has no supporting source");
    }
    public static Lookup state(Status status,String reason,String source,String version) {
        return new Lookup(status,reason,null,null,null,null,null,Instant.now().toString(),source,version,"Local offline lookup; dataset state at lookup time, not historical endpoint location");
    }
    public static NetworkEnrichment state(String address,String key,Status status,String reason) {
        return new NetworkEnrichment(address,EndpointAddress.family(address),key,state(status,reason,"Country",key),state(status,reason,"ASN",key),"NOT_AVAILABLE");
    }
}
