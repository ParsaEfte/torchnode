package io.github.gavinruff007.torchnode.dashboard;

import io.github.gavinruff007.torchnode.security.*;
import io.github.gavinruff007.torchnode.storage.SqliteNodeStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AuthenticationTest {
    @TempDir Path temp;
    private static HttpClient browser(){return HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();}
    private static HttpResponse<String> get(HttpClient c,int port,String path)throws Exception{return c.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).build(),HttpResponse.BodyHandlers.ofString());}
    private static int port()throws Exception{try(var s=new ServerSocket(0)){return s.getLocalPort();}}
    @Test void loginRbacCsrfRevocationNavigationAndLogout()throws Exception {
        String db=temp.resolve("auth.db").toString();try(var ignored=new SqliteNodeStore(db)){}
        var store=new AuthStore(db);String password=UUID.randomUUID().toString();store.provision("admin",password);
        String viewerPassword=UUID.randomUUID().toString();store.create("admin","viewer",viewerPassword,"VIEWER","test");
        assertThrows(IllegalStateException.class,()->store.provision("other",password));
        int port=port();var server=new DashboardServer(port,db,"127.0.0.1",false);server.start();
        try {
            var anonymous=browser();assertEquals(302,get(anonymous,port,"/").statusCode());assertEquals(401,get(anonymous,port,"/api/v1").statusCode());
            assertEquals(200,get(anonymous,port,"/healthz").statusCode());
            assertEquals(403,AuthTestSupport.post(anonymous,port,"/login","username=admin&password=bad").statusCode());
            var failed=AuthTestSupport.login(anonymous,port,"admin","wrong");assertEquals(401,failed.statusCode());assertTrue(failed.body().contains("Invalid username or password"));
            var admin=browser();String originalCookie=get(admin,port,"/login").headers().firstValue("Set-Cookie").orElseThrow();var login=AuthTestSupport.login(admin,port,"admin",password);assertEquals(302,login.statusCode());
            assertNotEquals(originalCookie,login.headers().firstValue("Set-Cookie").orElseThrow());
            String cookie=login.headers().allValues("Set-Cookie").toString();assertTrue(cookie.contains("HttpOnly"));assertTrue(cookie.contains("SameSite=Strict"));
            var home=get(admin,port,"/");assertEquals(200,home.statusCode());String csrf=AuthTestSupport.token(home.body());
            for(String path:List.of("/explorer","/discovery","/p2p","/apis","/statistics","/clients","/geography","/trends","/events","/reports/network","/admin/users","/settings")) {
                var page=get(admin,port,path);assertEquals(200,page.statusCode(),path+" "+page.body());assertTrue(page.body().contains("tn-navigation"),path);
            }
            assertEquals(403,AuthTestSupport.post(admin,port,"/scanner/stop","csrf=invalid").statusCode());
            assertEquals(302,AuthTestSupport.post(admin,port,"/scanner/stop","csrf="+csrf).statusCode());
            assertEquals(405,get(admin,port,"/logout").statusCode());
            var json=new com.fasterxml.jackson.databind.ObjectMapper();
            var comparison=get(admin,port,"/analytics/compare.json?start=2026-01-02T00:00:00Z&end=2026-01-03T00:00:00Z");
            assertEquals(200,comparison.statusCode());assertEquals("2026-01-01T00:00:00Z",json.readTree(comparison.body()).at("/baseline/scope/startInclusive").asText());
            assertEquals(400,get(admin,port,"/analytics/compare.json?mode=all").statusCode());
            assertEquals(400,get(admin,port,"/analytics/compare.json?start=2026-01-03T00:00:00Z&end=2026-01-02T00:00:00Z").statusCode());
            assertEquals(200,get(admin,port,"/api/v1/analytics/compare").statusCode());
            assertEquals(200,get(admin,port,"/reports/network?compare=true").statusCode());
            var identities=get(admin,port,"/analytics/identities.json");assertEquals(200,identities.statusCode());assertTrue(json.readTree(identities.body()).path("identities").isEmpty());
            assertEquals(400,get(admin,port,"/analytics/identities.json?domain=invalid").statusCode());
            assertEquals(400,get(admin,port,"/analytics/identities.json?after=invalid").statusCode());
            var viewer=browser();assertEquals(302,AuthTestSupport.login(viewer,port,"viewer",viewerPassword).statusCode());
            assertEquals(200,get(viewer,port,"/api/v1/identities").statusCode());assertEquals(200,get(viewer,port,"/reports/network").statusCode());
            for(String query:List.of("q=%27%20OR%201%3D1--","protocol=discv5%27","observer=invalid","after=invalid","limit=-1","highWatermark=-1","start=not-a-clock")) {
                var rejected=get(viewer,port,"/api/v1/topology/graph?"+query);
                assertEquals(400,rejected.statusCode(),query);
                assertFalse(rejected.body().contains(db));
                assertFalse(rejected.body().contains("org.sqlite"));
            }
            assertEquals(405,AuthTestSupport.post(viewer,port,"/api/v1/topology/graph","").statusCode());
            assertEquals(403,get(viewer,port,"/admin/users").statusCode());assertEquals(403,get(viewer,port,"/settings").statusCode());assertEquals(403,get(viewer,port,"/api/v1/exports/analytics.csv").statusCode());assertEquals(403,get(viewer,port,"/api/v1/%65xports/analytics.csv").statusCode());
            String viewerCsrf=AuthTestSupport.token(get(viewer,port,"/").body());
            for(String action:List.of("/scanner/start","/scanner/stop","/inspection/start","/data/clear"))assertEquals(403,AuthTestSupport.post(viewer,port,action,"csrf="+viewerCsrf).statusCode());
            store.update("admin","viewer","",false,"test");assertEquals(401,get(viewer,port,"/api/v1").statusCode());
            assertEquals(302,AuthTestSupport.post(admin,port,"/logout","csrf="+csrf).statusCode());assertEquals(401,get(admin,port,"/api/v1").statusCode());
            for(int i=0;i<5;i++)assertEquals(401,AuthTestSupport.login(anonymous,port,"absent","wrong").statusCode());
            assertEquals(429,AuthTestSupport.login(anonymous,port,"absent","wrong").statusCode());
            assertTrue(store.events().stream().anyMatch(e->e.get("event").equals("LOGIN_RATE_LIMITED")));
            assertTrue(store.events().stream().anyMatch(e->e.get("event").equals("LOGOUT")));
            for(var event:store.events()) {
                String audit=event.toString();
                assertFalse(audit.contains(password));assertFalse(audit.contains(viewerPassword));assertFalse(audit.contains("$argon2id$"));
            }
        } finally {server.stop();}
    }
    @Test void secureCookieDefaultAndPasswordHashes()throws Exception {
        String password=UUID.randomUUID().toString(),hash=Passwords.hash(password);
        assertTrue(hash.startsWith("$argon2id$"));assertNotEquals(hash,Passwords.hash(password));
        assertTrue(Passwords.verify(password,hash));assertFalse(Passwords.verify("wrong",hash));assertFalse(Passwords.verify(password,"broken"));assertThrows(IllegalArgumentException.class,()->Passwords.hash("short"));
        String db=temp.resolve("secure.db").toString();try(var ignored=new SqliteNodeStore(db)){}
        int port=port();var server=new DashboardServer(port,db,"127.0.0.1",true);server.start();
        try {var response=get(browser(),port,"/login");assertEquals(200,response.statusCode());assertTrue(response.headers().allValues("Set-Cookie").toString().contains("Secure"));assertEquals("DENY",response.headers().firstValue("X-Frame-Options").orElseThrow());}
        finally {server.stop();}
        try(var c=java.sql.DriverManager.getConnection("jdbc:sqlite:"+db);var s=c.createStatement()) {
            try(var r=s.executeQuery("SELECT MAX(version) FROM schema_migrations")){assertTrue(r.next());assertEquals(6,r.getInt(1));}
            try(var r=s.executeQuery("PRAGMA integrity_check")){assertTrue(r.next());assertEquals("ok",r.getString(1));}
        }
        new AuthStore(db); // idempotent additive security migration
    }
    @Test void localRecoveryResetsOnlyExistingAdministratorsAndRevokesSessions()throws Exception {
        var store=new AuthStore(temp.resolve("recovery.db").toString());
        String original=UUID.randomUUID().toString(),replacement=UUID.randomUUID().toString();store.provision("admin",original);
        store.create("admin","viewer",original,"VIEWER","test");int version=store.find("admin").version();
        assertThrows(IllegalArgumentException.class,()->store.resetAdministrator("missing",replacement));
        assertThrows(IllegalArgumentException.class,()->store.resetAdministrator("viewer",replacement));
        store.resetAdministrator("admin",replacement);
        assertNull(store.authenticate("admin",original));assertNotNull(store.authenticate("admin",replacement));
        assertTrue(store.find("admin").version()>version);
        assertTrue(store.events().stream().anyMatch(e->e.get("event").equals("LOCAL_ADMIN_PASSWORD_RESET")));
    }
    @Test void absoluteSessionExpirationInvalidatesServerSession()throws Exception {
        String db=temp.resolve("expired.db").toString();var store=new AuthStore(db);store.provision("admin",UUID.randomUUID().toString());
        var state=new HashMap<String,Object>();state.put("user",store.find("admin"));state.put("issuedAt",0L);state.put("lastActivity",8*3600000L);
        boolean[] invalidated={false},chained={false};int[] status={0};
        var session=(jakarta.servlet.http.HttpSession)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{jakarta.servlet.http.HttpSession.class},(p,m,a)->switch(m.getName()){case "getAttribute"->state.get(a[0]);case "invalidate"->{invalidated[0]=true;yield null;}default->null;});
        var request=(jakarta.servlet.http.HttpServletRequest)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{jakarta.servlet.http.HttpServletRequest.class},(p,m,a)->switch(m.getName()){case "getSession"->session;case "getMethod"->"GET";case "getServletPath"->"/api/v1";case "getRemoteAddr"->"127.0.0.1";default->null;});
        var response=(jakarta.servlet.http.HttpServletResponse)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{jakarta.servlet.http.HttpServletResponse.class},(p,m,a)->{if(m.getName().equals("sendError") || m.getName().equals("setStatus"))status[0]=(int)a[0];if(m.getName().equals("getWriter"))return new java.io.PrintWriter(new java.io.StringWriter());return null;});
        new AuthFilter(store,false,Clock.fixed(Instant.ofEpochSecond(8*3600),ZoneOffset.UTC)).doFilter(request,response,(a,b)->chained[0]=true);
        assertTrue(invalidated[0]);assertFalse(chained[0]);assertEquals(401,status[0]);
        invalidated[0]=false;state.put("lastActivity",0L);
        new AuthFilter(store,false,Clock.fixed(Instant.ofEpochSecond(30*60),ZoneOffset.UTC)).doFilter(request,response,(a,b)->chained[0]=true);
        assertTrue(invalidated[0]);assertFalse(chained[0]);assertEquals(401,status[0]);
    }
}
