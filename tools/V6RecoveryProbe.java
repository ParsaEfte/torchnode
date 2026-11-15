package io.github.gavinruff007.torchnode.dashboard;

import io.github.gavinruff007.torchnode.security.AuthStore;
import io.github.gavinruff007.torchnode.discovery.DiscoveryEvidenceBatch;
import java.nio.file.*;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.sql.*;

/** Rehearsal adapter only. Never invokes Main or writable observation initialization. */
public final class V6RecoveryProbe {
 public static void main(String[] args)throws Exception {
  Path root=Path.of(args[0]).toRealPath(),db=root.resolve("working-v6.sqlite");
  if(!Files.isRegularFile(root.resolve("V6-REHEARSAL")) || !root.startsWith(Path.of("/work")) && !root.startsWith(Path.of(System.getProperty("java.io.tmpdir")).toRealPath()))throw new IllegalArgumentException("Private marked rehearsal required");
  if(!Files.isRegularFile(db,LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(db))throw new IllegalArgumentException("Rehearsal file required");
  Instant anchor=Instant.parse("2026-11-13T12:00:00Z");
  try(var c=DriverManager.getConnection("jdbc:sqlite:"+db.toUri()+"?mode=ro&immutable=1");var s=c.createStatement()) {
   var versions=new ArrayList<Integer>();try(var r=s.executeQuery("SELECT version FROM schema_migrations ORDER BY version")){while(r.next())versions.add(r.getInt(1));}
   if(!versions.equals(List.of(1,2,3,4,5,6)))throw new IllegalStateException("Exact v6 required before startup");
   try(var r=s.executeQuery("SELECT MAX(observed_at) FROM discovery_exchange_evidence")){if(r.next()&&r.getString(1)!=null)anchor=Instant.parse(r.getString(1));}
  }
  String username="rehearsal-"+UUID.randomUUID(),password=UUID.randomUUID().toString();
  new AuthStore(db.toString()).create("isolated-rehearsal",username,password,"VIEWER","loopback");
  ScannerService disabled=new ScannerService(db.toString()) {
   @Override public synchronized void start(){throw new IllegalStateException("Collection disabled in rehearsal");}
   @Override public synchronized void stop(){}
  };
  int port;try(var socket=new java.net.ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))){port=socket.getLocalPort();}
  if(port==8080)throw new IllegalStateException("Production port forbidden");
  var server=new DashboardServer(port,db.toString(),"127.0.0.1",false,disabled,new io.github.gavinruff007.torchnode.inspection.InspectionService(db.toString()));
  try {
   server.start();String base="http://127.0.0.1:"+port;
   var client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();
   String page=client.send(HttpRequest.newBuilder(URI.create(base+"/login")).timeout(Duration.ofSeconds(8)).build(),HttpResponse.BodyHandlers.ofString()).body();
   var token=java.util.regex.Pattern.compile("name=\"csrf\" value=\"([^\"]+)\"").matcher(page);if(!token.find())throw new IllegalStateException("Missing login CSRF");
   String form="csrf="+token.group(1)+"&username="+URLEncoder.encode(username,java.nio.charset.StandardCharsets.UTF_8)+"&password="+URLEncoder.encode(password,java.nio.charset.StandardCharsets.UTF_8);
   var login=client.send(HttpRequest.newBuilder(URI.create(base+"/login")).timeout(Duration.ofSeconds(8)).header("Content-Type","application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(form)).build(),HttpResponse.BodyHandlers.discarding());
   if(login.statusCode()!=302)throw new IllegalStateException("Viewer login failed");
   String window="?start="+anchor.minusSeconds(86400)+"&end="+anchor.plusSeconds(1);
   for(String route:List.of("/healthz","/topology","/api/v1","/api/v1/topology/graph"+window,"/api/v1/topology/summary"+window,"/api/v1/topology/candidates"+window)) {
    var response=client.send(HttpRequest.newBuilder(URI.create(base+route)).timeout(Duration.ofSeconds(10)).build(),HttpResponse.BodyHandlers.ofString());
    if(response.statusCode()!=200)throw new IllegalStateException("Rehearsal route failed: "+route.split("\\?")[0]+" status="+response.statusCode());
    if(route.startsWith("/api/"))DiscoveryEvidenceBatch.JSON.readTree(response.body());
   }
   var denied=client.send(HttpRequest.newBuilder(URI.create(base+"/scanner/start")).timeout(Duration.ofSeconds(5)).POST(HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.discarding());
   if(denied.statusCode()!=403)throw new IllegalStateException("Viewer mutation not denied");
   if(args.length==2 && args[1].equals("--browser-fixture")) {
    if(!Files.isRegularFile(root.resolve("V6-FIXTURE.json")))throw new IllegalStateException("Browser holding mode requires synthetic fixture");
    Files.writeString(root.resolve("browser-session.json"),DiscoveryEvidenceBatch.json(Map.of("port",port,"username",username,"password",password)));
    long end=System.nanoTime()+120_000_000_000L;while(!Files.exists(root.resolve("BROWSER-DONE"))&&System.nanoTime()<end)Thread.sleep(100);
   }
   System.out.println("V6_REHEARSAL_HTTP_PASS port="+port+" collection=DISABLED");
  }finally{server.stop();}
 }
}
