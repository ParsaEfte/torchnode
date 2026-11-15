package io.github.gavinruff007.torchnode.dashboard;
import java.nio.file.*;import java.net.*;import java.time.*;import java.util.*;
public final class HumanAccessibilityFixture {
 public static void main(String[] args)throws Exception {
  Path root=Path.of(args[0]).toRealPath();int seconds=Integer.parseInt(args[1]);
  if(seconds<2||seconds>600||!root.startsWith(Path.of(System.getProperty("java.io.tmpdir")).toRealPath())||!Files.isRegularFile(root.resolve("SYNTHETIC-ONLY")))throw new IllegalArgumentException("Bounded private synthetic fixture required");
  if(!"true".equals(System.getenv("TORCHNODE_COLLECTION_DISABLED")))throw new IllegalStateException("Collection must be disabled");
  Path db=root.resolve("accessibility.sqlite");if(Files.exists(db))throw new IllegalStateException("Existing database forbidden");
  try(var ignored=new io.github.gavinruff007.torchnode.storage.SqliteNodeStore(db.toString())){}
  String username="synthetic-viewer",password=UUID.randomUUID().toString();new io.github.gavinruff007.torchnode.security.AuthStore(db.toString()).create("synthetic",username,password,"VIEWER","loopback");
  int port;try(var s=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))){port=s.getLocalPort();}if(port==8080)throw new IllegalStateException("Production port forbidden");
  ScannerService disabled=new ScannerService(db.toString()){@Override public synchronized void start(){throw new IllegalStateException("Collection disabled");}@Override public synchronized void stop(){}};
  var server=new DashboardServer(port,db.toString(),"127.0.0.1",false,disabled,new io.github.gavinruff007.torchnode.inspection.InspectionService(db.toString()));
  var closed=new java.util.concurrent.atomic.AtomicBoolean();Runnable stop=()->{if(closed.compareAndSet(false,true))server.stop();};Runtime.getRuntime().addShutdownHook(new Thread(stop));
  try {
   server.start();Files.writeString(root.resolve("session.json"),io.github.gavinruff007.torchnode.discovery.DiscoveryEvidenceBatch.json(Map.of("syntheticOnly",true,"url","http://127.0.0.1:"+port+"/login","username",username,"password",password,"expiresInSeconds",seconds)));
   long end=System.nanoTime()+seconds*1_000_000_000L;while(System.nanoTime()<end&&!Files.exists(root.resolve("STOP")))Thread.sleep(200);
  }finally{stop.run();}
 }
}
