package io.github.gavinruff007.torchnode.discovery;

import io.github.gavinruff007.torchnode.dashboard.DashboardServer;
import io.github.gavinruff007.torchnode.daemon.ScanDaemon;
import io.github.gavinruff007.torchnode.security.AuthStore;
import io.github.gavinruff007.torchnode.storage.SqliteNodeStore;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Test-only child application. No public bootstraps, production path or production helper. */
public final class B6IsolatedApp {
    private static String envelope(String mode)throws Exception {
        String root=System.getenv("TORCHNODE_B6_TRACE_ROOT");
        if(root!=null)return Files.readString(Path.of(root+"."+mode));
        try(var input=B6IsolatedApp.class.getResourceAsStream("/discv5-trace-v2-"+mode+".json")){return new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);}
    }
    public static void main(String[] args)throws Exception {
        Path root=Path.of(args[0]).toRealPath();
        if(!Files.exists(root.resolve("B6-ISOLATED")))throw new IllegalArgumentException("Isolated fixture marker required");
        String db=root.resolve("acceptance.db").toString();
        try(var schema=new SqliteNodeStore(db)){}
        if(!Files.exists(root.resolve("provisioned"))){new AuthStore(db).provision("b6-fixture","b6-fixture-password");Files.writeString(root.resolve("provisioned"),"");}
        ScanDaemon daemon=null;
        if(args[2].equals("ingest")) {
            var raw=DiscoveryEvidenceBatch.JSON.readTree(envelope("valid")).path("trace");
            var run=new ObserverInstance.Run(UUID.fromString(raw.at("/observer/instanceId").asText()),UUID.fromString(raw.at("/observer/runId").asText()),Instant.parse(raw.path("startedAt").asText()).minusSeconds(1),raw.path("localIdentity").asText(),ObserverInstance.KeyLifecycle.EPHEMERAL_FIRST_SEEN);
            var events=new ArrayList<String>();events.add(DiscoveryEvidenceBatch.json(Map.of("type","ready","version",2,"nodeId",run.localDiscoveryIdentity())));
            for(String mode:List.of("invalid-enr","partial","wrong-request","empty","valid","multi"))events.add(envelope(mode).trim());
            var truncated=raw.deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)truncated).put("traceId","ffffffffffffffffffffffffffffffff");((com.fasterxml.jackson.databind.node.ObjectNode)truncated).put("omittedPackets",1);
            events.add(DiscoveryEvidenceBatch.json(Map.of("type","discoveryTrace","trace",truncated)));
            Files.write(root.resolve("events.jsonl"),events);
            Path helper=root.resolve("controlled-helper.sh");
            Files.writeString(helper,"#!/bin/sh\nexec "+quote(Path.of(System.getProperty("java.home"),"bin/java").toString())+" -cp "+quote(System.getProperty("java.class.path"))+" "+quote(ControlledTraceHelper.class.getName())+" "+quote(root.toString())+"\n");helper.toFile().setExecutable(true,true);
            var identity=new LocalNodeIdentity(org.web3j.crypto.ECKeyPair.create(java.math.BigInteger.ONE));
            var provider=new Discv5DiscoveryProvider(identity,helper,List.of("controlled-fixture-no-network"),run);
            daemon=new ScanDaemon(db);daemon.start(new CompositeDiscoveryProvider(List.of(provider)));
        }
        int port=Integer.parseInt(args[1]);var server=new DashboardServer(port,db,"127.0.0.1");server.start();
        Files.writeString(root.resolve("ready"),Integer.toString(port));
        try {long until=System.nanoTime()+java.util.concurrent.TimeUnit.MINUTES.toNanos(12);while(!Files.exists(root.resolve("STOP")) && System.nanoTime()<until)Thread.sleep(100);}
        finally {server.stop();if(daemon!=null){daemon.stop();daemon.awaitStopped();}}
    }
    private static String quote(String value) {return "'"+value.replace("'","'\"'\"'")+"'";}
    /** Same delayed JSON-line fixture protocol, with no Python installation dependency. */
    public static final class ControlledTraceHelper {
        public static void main(String[] args)throws Exception {
            Path root=Path.of(args[0]);
            if(!Files.exists(root.resolve("B6-ISOLATED")))throw new IllegalArgumentException("Synthetic marker required");
            var config=DiscoveryEvidenceBatch.JSON.readTree(System.in);
            if(!config.at("/observer/runId").asText().equals("00000000-0000-0000-0000-000000000002"))throw new IllegalArgumentException("Fixture run required");
            long until=System.nanoTime()+Duration.ofSeconds(90).toNanos();
            while(!Files.exists(root.resolve("RELEASE-EVENTS"))){if(System.nanoTime()>until)throw new IllegalStateException("Fixture release timeout");Thread.sleep(50);}
            for(String line:Files.readAllLines(root.resolve("events.jsonl"))){System.out.println(line);System.out.flush();Thread.sleep(120);}
            while(System.nanoTime()<until)Thread.sleep(1000);
        }
    }
}
