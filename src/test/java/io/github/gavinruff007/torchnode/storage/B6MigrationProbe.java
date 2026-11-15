package io.github.gavinruff007.torchnode.storage;

import io.github.gavinruff007.torchnode.dashboard.DashboardServer;
import io.github.gavinruff007.torchnode.security.AuthStore;
import java.nio.file.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/** Explicit offline probe, test classpath only. Never packaged into the deployment binary. */
public final class B6MigrationProbe {
    public static void main(String[] args)throws Exception {
        if(args.length!=2 || !Set.of("migrate","smoke").contains(args[0]))throw new IllegalArgumentException("Probe mode and disposable path required");
        Path path=Path.of(args[1]);
        if(!path.isAbsolute() || !path.equals(path.toRealPath()) || Files.isSymbolicLink(path)
                || !path.getFileName().toString().equals(args[0].equals("migrate")?"working.sqlite":"smoke.sqlite")
                || !Files.isRegularFile(path.getParent().resolve("B6B-WORK"))
                || Files.isSymbolicLink(path.getParent().resolve("B6B-WORK"))
                || ((Number)Files.getAttribute(path,"unix:nlink",LinkOption.NOFOLLOW_LINKS)).intValue()!=1)throw new IllegalArgumentException("Disposable marked working file required");
        try(var store=new SqliteNodeStore(path.toString())){}
        if(args[0].equals("migrate"))return;
        String username="offline-"+UUID.randomUUID(),password=UUID.randomUUID().toString();
        new AuthStore(path.toString()).create("offline-probe",username,password,"VIEWER","loopback");
        int port;try(var socket=new ServerSocket(0)){port=socket.getLocalPort();}
        if(port==8080)throw new IllegalStateException("Production port forbidden");
        var server=new DashboardServer(port,path.toString(),"127.0.0.1");
        try {
            server.start();
            var client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
                    .cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();
            String base="http://127.0.0.1:"+port;
            var page=client.send(HttpRequest.newBuilder(URI.create(base+"/login")).timeout(Duration.ofSeconds(5)).build(),HttpResponse.BodyHandlers.ofString());
            var csrf=java.util.regex.Pattern.compile("name=\"csrf\" value=\"([^\"]+)\"").matcher(page.body());
            if(page.statusCode()!=200 || !csrf.find())throw new IllegalStateException("Login page unavailable");
            String form="csrf="+csrf.group(1)+"&username="+URLEncoder.encode(username,StandardCharsets.UTF_8)+"&password="+URLEncoder.encode(password,StandardCharsets.UTF_8);
            var login=client.send(HttpRequest.newBuilder(URI.create(base+"/login")).timeout(Duration.ofSeconds(5))
                    .header("Content-Type","application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(form)).build(),HttpResponse.BodyHandlers.ofString());
            if(login.statusCode()!=302)throw new IllegalStateException("Authentication failed");
            for(String route:List.of("/topology","/api/v1/topology/graph?start=2026-01-01T00:00:00Z&end=2026-01-02T00:00:00Z")) {
                var result=client.send(HttpRequest.newBuilder(URI.create(base+route)).timeout(Duration.ofSeconds(8)).build(),HttpResponse.BodyHandlers.ofString());
                if(result.statusCode()!=200)throw new IllegalStateException("Post-migration HTTP smoke failed");
                if(route.startsWith("/api/")) {
                    var data=new com.fasterxml.jackson.databind.ObjectMapper().readTree(result.body()).path("data");
                    if(!data.path("edges").isArray() || data.path("edges").size()!=0 || !data.at("/coverage/queryComplete").asBoolean())throw new IllegalStateException("Legacy data promoted or incomplete graph");
                }
            }
            System.out.println("OFFLINE_STARTUP_AND_AUTHENTICATED_TOPOLOGY_PASS");
        }finally{server.stop();}
    }
}
