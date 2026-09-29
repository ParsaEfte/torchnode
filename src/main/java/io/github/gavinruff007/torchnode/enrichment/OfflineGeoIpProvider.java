package io.github.gavinruff007.torchnode.enrichment;

import com.maxmind.db.Reader;
import io.github.gavinruff007.torchnode.model.EndpointAddress;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.io.InputStream;
import static io.github.gavinruff007.torchnode.enrichment.NetworkEnrichment.*;

/** Optional GeoLite2/GeoIP2 Country and GeoLite2 ASN files. Never downloads or opens a socket. */
public final class OfflineGeoIpProvider implements NetworkEnrichmentProvider {
    private record Dataset(Reader reader,String source,String version,String error) {}
    private final Dataset country,asn;
    private boolean closed;
    public OfflineGeoIpProvider(Path countryFile,Path asnFile) {
        country=open(countryFile,false);asn=open(asnFile,true);
    }
    public static OfflineGeoIpProvider configured() {
        return new OfflineGeoIpProvider(path(System.getenv("TORCHNODE_GEOIP_COUNTRY_DB")),path(System.getenv("TORCHNODE_GEOIP_ASN_DB")));
    }
    private static Path path(String value) { try{return value==null || value.isBlank()?null:Path.of(value);}catch(InvalidPathException e){return Path.of("__invalid_optional_geoip_dataset__");} }
    private static Dataset open(Path path,boolean asn) {
        String source=asn?"MaxMind ASN":"MaxMind Country";
        if(path==null)return new Dataset(null,source,"unconfigured","Optional dataset not configured");
        Reader reader=null;
        try {
            // Explicit size limit bounds file hashing and reader configuration; no secrets/paths in output.
            if(!Files.isRegularFile(path) || Files.size(path)>512L*1024*1024)throw new java.io.IOException("Unavailable dataset");
            var hash=MessageDigest.getInstance("SHA-256");
            try(InputStream input=Files.newInputStream(path)){byte[] buffer=new byte[65536];int n;while((n=input.read(buffer))!=-1)hash.update(buffer,0,n);}
            String version="sha256:"+HexFormat.of().formatHex(hash.digest());
            reader=new Reader(path.toFile(),Reader.FileMode.MEMORY_MAPPED);
            String type=reader.getMetadata().getDatabaseType();
            if(asn?!type.equals("GeoLite2-ASN"):!List.of("GeoLite2-Country","GeoIP2-Country").contains(type))throw new java.io.IOException("Wrong database type");
            return new Dataset(reader,type,version+";build:"+reader.getMetadata().getBuildDate().toInstant(),null);
        } catch(Exception e) {
            if(reader!=null)try{reader.close();}catch(Exception ignored){}
            return new Dataset(null,source,"unavailable","Dataset missing, unreadable, invalid, wrong type or exceeds 512 MiB");
        }
    }
    @Override public String datasetKey() { return country.source()+"/"+country.version()+"|"+asn.source()+"/"+asn.version(); }
    @Override public synchronized NetworkEnrichment lookup(String address) {
        String reason=PublicAddress.exclusion(address);
        if(reason!=null)return NetworkEnrichment.state(address,datasetKey(),Status.NOT_APPLICABLE,reason);
        if(closed)return NetworkEnrichment.state(address,datasetKey(),Status.LOOKUP_FAILED,"Provider closed");
        return new NetworkEnrichment(address,EndpointAddress.family(address),datasetKey(),lookup(country,address,false),lookup(asn,address,true),"NOT_AVAILABLE");
    }
    private static Lookup lookup(Dataset dataset,String address,boolean asn) {
        if(dataset.reader()==null)return state(Status.DATASET_UNAVAILABLE,dataset.error(),dataset.source(),dataset.version());
        try {
            var record=dataset.reader().getRecord(EndpointAddress.parse(address),Map.class);
            var data=record.getData();
            if(data==null)return state(Status.NOT_FOUND,"No matching dataset record",dataset.source(),dataset.version());
            String code=null,name=null,organization=null;Long number=null;
            if(asn) {
                if(data.get("autonomous_system_number") instanceof Number n)number=n.longValue();
                if(data.get("autonomous_system_organization") instanceof String s)organization=s;
            } else if(data.get("country") instanceof Map<?,?> value) {
                if(value.get("iso_code") instanceof String s)code=s;
                if(value.get("names") instanceof Map<?,?> names && names.get("en") instanceof String s)name=s;
            }
            if(asn?number==null:code==null)return state(Status.NOT_FOUND,"Matching record lacks requested field",dataset.source(),dataset.version());
            return new Lookup(Status.FOUND,null,code,name,number,organization,record.getNetwork().toString(),Instant.now().toString(),dataset.source(),dataset.version(),"Local MMDB lookup; country is approximate IP-network location; ASN organization does not classify hosting");
        } catch(Exception e) { return state(Status.LOOKUP_FAILED,"Local dataset record could not be read",dataset.source(),dataset.version()); }
    }
    @Override public synchronized void close() {
        if(closed)return;closed=true;
        for(var dataset:List.of(country,asn))if(dataset.reader()!=null)try{dataset.reader().close();}catch(Exception ignored){}
    }
}
