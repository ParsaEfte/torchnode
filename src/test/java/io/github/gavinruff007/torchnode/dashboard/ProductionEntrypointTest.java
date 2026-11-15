package io.github.gavinruff007.torchnode.dashboard;
import io.github.gavinruff007.torchnode.security.AuthStore;
import io.github.gavinruff007.torchnode.storage.SqliteNodeStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
class ProductionEntrypointTest {
 @TempDir Path root;
 @Test @org.junit.jupiter.api.Timeout(90)
 void mainSurvivesManagedRestartWithoutCollectors() throws Exception {
  String db=root.resolve("synthetic.sqlite").toString();
  try(var store=new SqliteNodeStore(db)) {}
  new AuthStore(db).provision("synthetic-admin","synthetic-admin-password-123");
  new AuthStore(db).create("fixture","viewer","synthetic-only-password-123","VIEWER","loopback");
  for(int round=0;round<2;round++) {
   int port;try(var socket=new java.net.ServerSocket(0,1,InetAddress.getLoopbackAddress())){port=socket.getLocalPort();}
   assertNotEquals(8080,port);
   var arguments=new java.util.ArrayList<String>();
   arguments.add(Path.of(System.getProperty("java.home"),"bin","java").toString());arguments.add("-Xmx256m");
   String packaged=System.getProperty("torchnode.test.packagedJar");
   String replacement=System.getProperty("torchnode.test.replacementClasspath");
   if(replacement!=null) {
    for(String entry:replacement.split(java.io.File.pathSeparator)) {
     Path artifact=Path.of(entry.endsWith("/*")?entry.substring(0,entry.length()-2):entry).toRealPath();
     assertTrue(artifact.startsWith(Path.of("/private/tmp")) || artifact.startsWith(Path.of(System.getProperty("java.io.tmpdir")).toRealPath()),"Only private temporary replacement libraries permitted");
    }
    arguments.addAll(java.util.List.of("-cp",replacement,"io.github.gavinruff007.torchnode.Main"));
   }
   else if(packaged==null)arguments.addAll(java.util.List.of("-cp",System.getProperty("java.class.path"),"io.github.gavinruff007.torchnode.Main"));
   else {
    Path jar=Path.of(packaged).toRealPath();
    assertTrue(jar.startsWith(Path.of("/private/tmp")) || jar.startsWith(Path.of(System.getProperty("java.io.tmpdir")).toRealPath()),"Only isolated temporary packaged candidates permitted");
    arguments.addAll(java.util.List.of("-jar",jar.toString()));
   }
   var command=new ProcessBuilder(arguments);
   // Do not inherit production credentials, helper paths, observer identity or JVM options.
   command.environment().clear();
   command.environment().putAll(java.util.Map.of("HOME",root.toString(),"TMPDIR",root.toString(),"TORCHNODE_DB",db,"TORCHNODE_BIND","127.0.0.1","TORCHNODE_PORT",""+port,"TORCHNODE_SECURE_COOKIES","false","TORCHNODE_COLLECTION_DISABLED","true"));
   command.directory(root.toFile());command.redirectErrorStream(true);command.redirectOutput(root.resolve("main-"+round+".log").toFile());
   var process=command.start();String base="http://127.0.0.1:"+port;
   var http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();
   try {
    long until=System.nanoTime()+30_000_000_000L;boolean ready=false;
    while(System.nanoTime()<until && process.isAlive()) {
     try {ready=http.send(HttpRequest.newBuilder(URI.create(base+"/healthz")).timeout(Duration.ofSeconds(2)).build(),HttpResponse.BodyHandlers.ofString()).statusCode()==200;if(ready)break;}catch(java.io.IOException ignored){}
     Thread.sleep(100);
    }
    assertTrue(ready,()->"Main not ready: "+root);
    assertEquals(401,http.send(HttpRequest.newBuilder(URI.create(base+"/api/v1")).build(),HttpResponse.BodyHandlers.discarding()).statusCode());
    String login=http.send(HttpRequest.newBuilder(URI.create(base+"/login")).build(),HttpResponse.BodyHandlers.ofString()).body();
    var csrf=java.util.regex.Pattern.compile("name=\"csrf\" value=\"([^\"]+)\"").matcher(login);assertTrue(csrf.find());
    var response=http.send(HttpRequest.newBuilder(URI.create(base+"/login")).header("Content-Type","application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString("csrf="+csrf.group(1)+"&username=viewer&password=synthetic-only-password-123")).build(),HttpResponse.BodyHandlers.ofString());assertEquals(302,response.statusCode());
    for(String route:java.util.List.of("/topology","/api/v1","/api/v1/topology/graph","/api/v1/topology/summary","/api/v1/topology/candidates"))assertEquals(200,http.send(HttpRequest.newBuilder(URI.create(base+route)).timeout(Duration.ofSeconds(8)).build(),HttpResponse.BodyHandlers.discarding()).statusCode(),route);
    assertEquals(403,http.send(HttpRequest.newBuilder(URI.create(base+"/scanner/start")).POST(HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.discarding()).statusCode());
    // An administrator with valid CSRF must also be unable to start collectors.
    http=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();
    login=http.send(HttpRequest.newBuilder(URI.create(base+"/login")).build(),HttpResponse.BodyHandlers.ofString()).body();
    csrf=java.util.regex.Pattern.compile("name=\"csrf\" value=\"([^\"]+)\"").matcher(login);assertTrue(csrf.find());
    assertEquals(302,http.send(HttpRequest.newBuilder(URI.create(base+"/login")).header("Content-Type","application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString("csrf="+csrf.group(1)+"&username=synthetic-admin&password=synthetic-admin-password-123")).build(),HttpResponse.BodyHandlers.discarding()).statusCode());
    String page=http.send(HttpRequest.newBuilder(URI.create(base+"/topology")).build(),HttpResponse.BodyHandlers.ofString()).body();
    csrf=java.util.regex.Pattern.compile("name=\"csrf\" value=\"([^\"]+)\"").matcher(page);assertTrue(csrf.find());
    var blocked=http.send(HttpRequest.newBuilder(URI.create(base+"/scanner/start")).header("Content-Type","application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString("csrf="+csrf.group(1))).build(),HttpResponse.BodyHandlers.ofString());
    assertEquals(302,blocked.statusCode());
    assertTrue(java.net.URLDecoder.decode(blocked.headers().firstValue("Location").orElseThrow(),java.nio.charset.StandardCharsets.UTF_8).contains("Collection disabled by operator configuration"));
    assertEquals(0,process.descendants().count(),"No collector/helper child process permitted");
    assertFalse(Files.exists(Path.of(db+".observer.json")));
   } finally { process.destroy();if(!process.waitFor(15,TimeUnit.SECONDS)){process.destroyForcibly();fail("Main did not terminate within 15 seconds");} }
   assertEquals(143,process.exitValue());
   String log=Files.readString(root.resolve("main-"+round+".log"));
   assertFalse(log.contains("synthetic-only-password-123"));
   assertFalse(log.contains("synthetic-admin-password-123"));
   assertFalse(log.contains("$argon2id$"));
   try(var connection=java.sql.DriverManager.getConnection("jdbc:sqlite:"+db);var statement=connection.createStatement()) {
    var versions=new java.util.ArrayList<Integer>();try(var rows=statement.executeQuery("SELECT version FROM schema_migrations ORDER BY version")){while(rows.next())versions.add(rows.getInt(1));}assertEquals(java.util.List.of(1,2,3,4,5,6),versions);
    try(var rows=statement.executeQuery("PRAGMA integrity_check")){assertEquals("ok",rows.getString(1));}
    try(var rows=statement.executeQuery("PRAGMA foreign_key_check")){assertFalse(rows.next());}
    try(var rows=statement.executeQuery("SELECT COUNT(*) FROM discovery_exchange_evidence")){assertEquals(0,rows.getInt(1));}
   }
  }
 }
}
