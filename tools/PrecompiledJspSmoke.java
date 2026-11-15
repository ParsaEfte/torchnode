package io.github.gavinruff007.torchnode.dashboard;
import io.github.gavinruff007.torchnode.storage.*;
import io.github.gavinruff007.torchnode.model.*;
import io.github.gavinruff007.torchnode.security.AuthStore;
import java.nio.file.*;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
public final class PrecompiledJspSmoke {
 static void check(boolean ok,String message){if(!ok)throw new IllegalStateException(message);}
 static HttpClient client(){return HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).connectTimeout(Duration.ofSeconds(3)).build();}
 static HttpResponse<String> get(HttpClient c,String url)throws Exception{return c.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).build(),HttpResponse.BodyHandlers.ofString());}
 static void login(HttpClient c,String base,String user,String password)throws Exception {
  var match=java.util.regex.Pattern.compile("name=\"csrf\" value=\"([^\"]+)\"").matcher(get(c,base+"/login").body());check(match.find(),"CSRF missing");
  String form="csrf="+match.group(1)+"&username="+URLEncoder.encode(user,java.nio.charset.StandardCharsets.UTF_8)+"&password="+URLEncoder.encode(password,java.nio.charset.StandardCharsets.UTF_8);
  var response=c.send(HttpRequest.newBuilder(URI.create(base+"/login")).timeout(Duration.ofSeconds(10)).header("Content-Type","application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(form)).build(),HttpResponse.BodyHandlers.discarding());check(response.statusCode()==302,"Login failed");
 }
 public static void main(String[] args)throws Exception {
  Path root=Path.of(args[0]).toRealPath();check(root.startsWith(Path.of(System.getProperty("java.io.tmpdir")).toRealPath())&&Files.isRegularFile(root.resolve("SYNTHETIC-ONLY")),"Synthetic fixture required");Path db=root.resolve("precompiled-fixture.sqlite");check(!Files.exists(db),"No existing database allowed");
  check("true".equals(System.getenv("TORCHNODE_COLLECTION_DISABLED")),"Collection must be disabled");
  try{Class.forName("org.eclipse.jdt.internal.compiler.Compiler");throw new IllegalStateException("ECJ remains available");}catch(ClassNotFoundException expected){}
  check(PrecompiledViews.available(),"Precompiled registry missing");String key;
  try(var store=new SqliteNodeStore(db.toString())) {
   store.saveObservation(new DiscoveryObservation(new NodeIdentity("ab".repeat(64)),"discv4",List.of(new NodeEndpoint("192.0.2.1",NodeEndpoint.Transport.UDP,30303,NodeEndpoint.AddressFamily.IPV4,NodeEndpoint.Purpose.DISCOVERY)),Instant.parse("2026-01-01T00:00:00Z"),"synthetic precompilation fixture"));key=store.findAll().get(0).getKey();
  }
  String password=UUID.randomUUID().toString();var auth=new AuthStore(db.toString());auth.create("synthetic","synthetic-admin",password,"ADMIN","loopback");auth.create("synthetic","synthetic-viewer",password,"VIEWER","loopback");
  ScannerService disabled=new ScannerService(db.toString()){@Override public synchronized void start(){throw new IllegalStateException("Collection disabled");}@Override public synchronized void stop(){}};
  int port;try(var socket=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))){port=socket.getLocalPort();}check(port!=8080,"Production port forbidden");
  var server=new DashboardServer(port,db.toString(),"127.0.0.1",false,disabled,new io.github.gavinruff007.torchnode.inspection.InspectionService(db.toString()));
  try {
   server.start();String base="http://127.0.0.1:"+port;var c=client();check(get(c,base+"/api/v1").statusCode()==401,"Unauthenticated API exposed");login(c,base,"synthetic-admin",password);
   var rendered=new ArrayList<String>();
   for(String route:List.of("/","/topology","/discovery","/analytics","/reports/network","/admin/users","/node?key="+URLEncoder.encode(key,java.nio.charset.StandardCharsets.UTF_8))) {
    var response=get(c,base+route);check(response.statusCode()==200,"View failed: "+route+" status="+response.statusCode());check(response.body().contains("tn-navigation"),"Layout not rendered: "+route);rendered.add(route.split("\\?")[0]);
   }
   for(String route:List.of("/api/v1/topology/graph","/api/v1/topology/summary","/api/v1/topology/candidates"))check(get(c,base+route).statusCode()==200,"Topology API failed");
   var field=DashboardServer.class.getDeclaredField("tomcat");field.setAccessible(true);var tomcat=(org.apache.catalina.startup.Tomcat)field.get(server);var context=(org.apache.catalina.Context)tomcat.getHost().findChild("");check(context.findChild("jsp")==null&&context.findServletMapping("*.jsp")==null,"Runtime JSP compiler fallback exists");
   var viewer=client();login(viewer,base,"synthetic-viewer",password);check(get(viewer,base+"/admin/users").statusCode()==403,"Viewer admin boundary failed");var denied=viewer.send(HttpRequest.newBuilder(URI.create(base+"/scanner/start")).POST(HttpRequest.BodyPublishers.noBody()).timeout(Duration.ofSeconds(5)).build(),HttpResponse.BodyHandlers.discarding());check(denied.statusCode()==403,"Viewer mutation allowed");
   System.out.println("PRECOMPILED_JSP_SMOKE_PASS views=7+included-layout registry=8 ECJ=ABSENT dynamic-fallback=ABSENT auth=PASS viewer=PASS topology=PASS collection=DISABLED");
  }finally{server.stop();}
 }
}
