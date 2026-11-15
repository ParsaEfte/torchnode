package io.github.gavinruff007.torchnode.dashboard;

import io.github.gavinruff007.torchnode.security.AuthStore;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

final class AuthTestSupport {
    static HttpClient client(String database,int port) throws Exception {
        String password=UUID.randomUUID().toString();
        new AuthStore(database).provision("test-admin",password);
        var client=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();
        login(client,port,"test-admin",password);
        return client;
    }
    static String token(String body) {
        var m=java.util.regex.Pattern.compile("name=\"csrf\" value=\"([^\"]+)\"").matcher(body);
        if(!m.find())throw new AssertionError("CSRF token missing: "+body.substring(0,Math.min(200,body.length())));
        return m.group(1);
    }
    static HttpResponse<String> login(HttpClient client,int port,String user,String password) throws Exception {
        var page=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/login")).build(),HttpResponse.BodyHandlers.ofString());
        return post(client,port,"/login","csrf="+token(page.body())+"&username="+encode(user)+"&password="+encode(password));
    }
    static String encode(String value){return URLEncoder.encode(value,StandardCharsets.UTF_8);}
    static HttpResponse<String> post(HttpClient client,int port,String path,String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).header("Content-Type","application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
}
