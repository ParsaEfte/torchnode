package io.github.gavinruff007.torchnode.dashboard;

import io.github.gavinruff007.torchnode.security.*;
import io.github.gavinruff007.torchnode.storage.SqliteNodeStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class LegalNoticesTest {
    @TempDir Path temp;
    private HttpResponse<String> request(HttpClient c,int port,String path,String method)throws Exception {
        return c.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(10))
            .method(method,HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.ofString());
    }
    @Test void fixedPublicLegalResourcesDoNotExposeAuthenticatedResources()throws Exception {
        String db=temp.resolve("legal.sqlite").toString();try(var ignored=new SqliteNodeStore(db)){}
        int port;try(var socket=new ServerSocket(0)){port=socket.getLocalPort();}assertNotEquals(8080,port);
        var server=new DashboardServer(port,db,"127.0.0.1",false);server.start();
        try {
            var client=HttpClient.newHttpClient();
            var page=request(client,port,"/legal","GET");assertEquals(200,page.statusCode());
            assertTrue(page.body().contains("WITHOUT ANY WARRANTY"));assertTrue(page.body().contains("modify and redistribute"));
            assertTrue(page.body().contains("not a restriction"));assertTrue(page.body().contains("LGPL library copyright notices"));
            assertTrue(page.headers().firstValue("Set-Cookie").isEmpty());assertEquals("nosniff",page.headers().firstValue("X-Content-Type-Options").orElseThrow());
            for(String name:List.of("gpl-3.0.txt","lgpl-3.0.txt","lgpl-2.1.txt","epl-2.0.txt","mit-project.txt","distribution-scope.txt","third-party-notices.txt")) {
                var response=request(client,port,"/legal/"+name,"GET");assertEquals(200,response.statusCode(),name);
                try(var input=LegalNotices.class.getResourceAsStream("/legal/"+name)) {assertNotNull(input);assertEquals(new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8),response.body());}
            }
            assertTrue(request(client,port,"/legal/gpl-3.0.txt","HEAD").body().isEmpty());
            assertEquals(405,request(client,port,"/legal","POST").statusCode());
            assertEquals(405,request(client,port,"/legal/gpl-3.0.txt","DELETE").statusCode());
            assertEquals(401,request(client,port,"/api/v1/topology/graph","GET").statusCode());
            assertNotEquals(200,request(client,port,"/legal/unknown.txt","GET").statusCode());
            assertNotEquals(200,request(client,port,"/legal/gpl-3.0.txt/extra","GET").statusCode());
            assertNotEquals(200,request(client,port,"/legal/../api/v1/topology/graph","GET").statusCode());
        } finally {server.stop();}
    }
    @Test void loginAuthenticatedMenuAndTerminalExposeLegalFeature()throws Exception {
        String db=temp.resolve("menu.sqlite").toString();try(var ignored=new SqliteNodeStore(db)){}
        String password=UUID.randomUUID().toString();new AuthStore(db).provision("legal-admin",password);
        int port;try(var socket=new ServerSocket(0)){port=socket.getLocalPort();}assertNotEquals(8080,port);
        var server=new DashboardServer(port,db,"127.0.0.1",false);server.start();
        try {
            var client=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();
            assertTrue(request(client,port,"/login","GET").body().contains("href=\"/legal\""));
            assertEquals(302,AuthTestSupport.login(client,port,"legal-admin",password).statusCode());
            assertTrue(request(client,port,"/","GET").body().contains("Licenses &amp; source"));
            String terminal=LegalNotices.consoleSummary();assertTrue(terminal.contains("WITHOUT ANY WARRANTY"));
            assertTrue(terminal.contains("Copyright"));assertTrue(terminal.contains("GPL/LGPL"));
        } finally {server.stop();}
    }
}
