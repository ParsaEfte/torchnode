package io.github.gavinruff007.torchnode.security;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.time.*;
import java.util.*;
import java.util.concurrent.Semaphore;

/** One gate for dashboard, assets and API. Sessions are opaque, server-side and never URL-rewritten. */
public final class AuthFilter implements Filter {
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON=new com.fasterxml.jackson.databind.ObjectMapper();
    private final AuthStore store;
    private final boolean secureCookies;
    private final Clock clock;
    private final Map<String,Limit> limits=new HashMap<>();
    private final Semaphore hashing=new Semaphore(2);
    private record Limit(long until,int count) {}
    public AuthFilter(AuthStore store,boolean secureCookies) { this(store,secureCookies,Clock.systemUTC()); }
    public AuthFilter(AuthStore store,boolean secureCookies,Clock clock) { this.store=store;this.secureCookies=secureCookies;this.clock=clock; }
    public static String csrf(HttpServletRequest req) {
        var session=req.getSession();
        if(session.getAttribute("csrfToken")==null)session.setAttribute("csrfToken",UUID.randomUUID().toString());
        return session.getAttribute("csrfToken").toString();
    }
    public static boolean validCsrf(HttpServletRequest req) {
        var s=req.getSession(false);
        String value=req.getParameter("csrf");
        if(value==null)value=req.getHeader("X-CSRF-Token");
        return s!=null && value!=null && value.equals(s.getAttribute("csrfToken"));
    }
    private synchronized boolean allow(String key,int maximum) {
        long now=clock.millis();
        limits.entrySet().removeIf(e->e.getValue().until<=now);
        Limit previous=limits.get(key);
        if(previous==null && limits.size()>=10000)return false;
        Limit next=previous==null?new Limit(now+900000,1):new Limit(previous.until,previous.count+1);
        limits.put(key,next);return next.count<=maximum;
    }
    @Override public void doFilter(ServletRequest input,ServletResponse output,FilterChain chain) throws IOException,ServletException {
        var req=(HttpServletRequest)input;var res=(HttpServletResponse)output;
        res.setHeader("Cache-Control","no-store");res.setHeader("X-Content-Type-Options","nosniff");
        res.setHeader("X-Frame-Options","DENY");res.setHeader("Referrer-Policy","no-referrer");
        res.setHeader("Permissions-Policy","camera=(), microphone=(), geolocation=()");
        String nonce=UUID.randomUUID().toString();req.setAttribute("cspNonce",nonce);
        res.setHeader("Content-Security-Policy","default-src 'self'; script-src 'self' 'nonce-"+nonce+"'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; object-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'");
        if(secureCookies)res.setHeader("Strict-Transport-Security","max-age=31536000");
        String path=req.getServletPath(),method=req.getMethod();
        String routePath=path+(req.getPathInfo()==null?"":req.getPathInfo());
        if("/healthz".equals(path) && "GET".equals(method)){res.setContentType("text/plain");res.getWriter().write("ok");return;}
        if(LegalNotices.serve(req,res))return;
        try {
            if("/login".equals(path)) {
                if("GET".equals(method)){loginPage(req,res,null);return;}
                if(!"POST".equals(method)){res.sendError(405);return;}
                if(!validCsrf(req)){store.audit("anonymous","LOGIN_CSRF_REJECTED",req.getRemoteAddr());res.sendError(403,"Invalid form token");return;}
                String name=req.getParameter("username");
                String normalized=name==null?"":name.trim().toLowerCase(Locale.ROOT);
                if(normalized.length()>128)normalized=normalized.substring(0,128);
                boolean ip=allow("ip:"+req.getRemoteAddr(),20),account=allow("user:"+normalized,5);
                if(!ip || !account || !hashing.tryAcquire()) {
                    store.audit(normalized,"LOGIN_RATE_LIMITED",req.getRemoteAddr());
                    res.setHeader("Retry-After","900");res.setStatus(429);loginPage(req,res,"Too many attempts. Try again in 15 minutes.");return;
                }
                AuthStore.User user;
                try { user=store.authenticate(name,req.getParameter("password")); } finally { hashing.release(); }
                store.audit(normalized,user==null?"LOGIN_FAILED":"LOGIN_SUCCESS",req.getRemoteAddr());
                if(user==null){res.setStatus(401);loginPage(req,res,"Invalid username or password.");return;}
                req.getSession().invalidate();var session=req.getSession(true);
                session.setMaxInactiveInterval(1800);session.setAttribute("user",user);session.setAttribute("issuedAt",clock.millis());session.setAttribute("lastActivity",clock.millis());csrf(req);
                res.sendRedirect("/");return;
            }
            var session=req.getSession(false);
            var user=session==null?null:(AuthStore.User)session.getAttribute("user");
            if(user!=null) {
                var current=store.find(user.username());
                if(current==null || !current.enabled() || current.version()!=user.version() ||
                        clock.millis()-(Long)session.getAttribute("issuedAt")>=Duration.ofHours(8).toMillis() ||
                        clock.millis()-(Long)session.getAttribute("lastActivity")>=Duration.ofMinutes(30).toMillis()) {
                    store.audit(user.username(),"SESSION_EXPIRED_OR_REVOKED",req.getRemoteAddr());
                    session.invalidate();user=null;
                }
            }
            if(user==null) {
                if(path.startsWith("/api/") || path.endsWith(".json") || path.startsWith("/inspection/") || !"GET".equals(method)){deny(req,res,401,"authentication_required","Authentication required");}
                else res.sendRedirect("/login");return;
            }
            session.setAttribute("lastActivity",clock.millis());
            req.setAttribute("authUser",user);req.setAttribute("isAdmin","ADMIN".equals(user.role()));req.setAttribute("csrfToken",csrf(req));
            if("/logout".equals(path)) {
                if(!"POST".equals(method)){res.sendError(405);return;}
                if(!validCsrf(req)){res.sendError(403);return;}
                store.audit(user.username(),"LOGOUT",req.getRemoteAddr());session.invalidate();
                res.addHeader("Set-Cookie","TNSESSION=; Path=/; Max-Age=0; HttpOnly; SameSite=Strict"+(secureCookies?"; Secure":""));
                res.sendRedirect("/login");return;
            }
            if(path.startsWith("/api/") && !Set.of("GET","HEAD").contains(method)) {
                res.setHeader("Allow","GET, HEAD");deny(req,res,405,"method_not_allowed","API v1 is read-only");return;
            }
            boolean write=!Set.of("GET","HEAD","OPTIONS").contains(method);
            boolean restricted=path.startsWith("/admin") || path.equals("/settings") || routePath.contains("/exports/");
            if((write || restricted) && !"ADMIN".equals(user.role())){store.audit(user.username(),"ACCESS_DENIED:"+routePath.substring(0,Math.min(256,routePath.length())),req.getRemoteAddr());deny(req,res,403,"forbidden","Administrator role required");return;}
            if(write && !validCsrf(req)){res.sendError(403,"Invalid form token");return;}
            if(path.startsWith("/admin") || path.equals("/settings")) { administration(req,res,user);return; }
            if(write)store.audit(user.username(),"ACTION:"+path,req.getRemoteAddr());
            chain.doFilter(req,res);
        } catch(java.sql.SQLException e) { throw new ServletException("Authentication storage unavailable",e); }
    }
    private static void deny(HttpServletRequest req,HttpServletResponse res,int status,String code,String message) throws IOException {
        if(req.getServletPath().startsWith("/api/") || req.getServletPath().startsWith("/inspection/")) {
            res.setStatus(status);res.setContentType("application/json;charset=UTF-8");
            JSON.writeValue(res.getWriter(),Map.of("apiVersion","v1","error",Map.of("code",code,"message",message)));
        } else res.sendError(status,message);
    }
    public static String html(Object value) {
        return String.valueOf(value==null?"":value).replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&#39;");
    }
    private void loginPage(HttpServletRequest req,HttpServletResponse res,String error) throws IOException {
        res.setContentType("text/html;charset=UTF-8");
        res.getWriter().write(("""
            <!doctype html><html lang="en"><head><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Sign in · TorchNode Observatory</title>
            <style>body{margin:0;background:#090f1a;color:#e9eff8;font:16px system-ui;display:grid;place-items:center;min-height:100vh}main{box-sizing:border-box;width:min(440px,94vw);padding:36px;background:#111b2b;border:1px solid #2b3c53;border-radius:18px;box-shadow:0 25px 90px #0008}h1{font-size:26px}p{color:#aebed2;line-height:1.6}label{display:block;margin:20px 0 8px}input,button{box-sizing:border-box;width:100%;padding:13px;border-radius:8px;border:1px solid #405571;background:#0b1320;color:#edf4ff;font:inherit}button{margin-top:20px;background:#42d3bd;color:#07151a;font-weight:700;cursor:pointer}button.secondary{background:transparent;color:#c7d9eb;margin-top:8px}a:focus-visible,input:focus-visible,button:focus-visible{outline:3px solid #f5bc65;outline-offset:3px}.error{color:#ffb2b2}small{color:#91a9c2}</style></head><body><main>
            <small>TORCHNODE / OBSERVATORY</small><h1>Sign in to your observatory</h1><p>Ethereum discovery and protocol evidence from your observer.</p>
            """+ (error==null?"":"<p class=\"error\" role=\"alert\">"+html(error)+"</p>")+"""
            """+LegalNotices.summaryHtml()+"""
            <form method="post" action="/login"><input type="hidden" name="csrf" value="""+'"'+html(csrf(req))+'"'+"""
            ><label for="username">Username or email</label><input id="username" name="username" autocomplete="username" maxlength="128" required autofocus>
            <label for="password">Password</label><input id="password" name="password" type="password" autocomplete="current-password" maxlength="256" required>
            <button class="secondary" type="button" id="toggle" aria-controls="password" aria-pressed="false">Show password</button><button id="submit" type="submit">Sign in</button><p id="status" role="status"></p></form>
            <script nonce="NONCE_VALUE">document.getElementById('toggle').onclick=function(){const p=document.getElementById('password');const show=p.type==='password';p.type=show?'text':'password';this.textContent=show?'Hide password':'Show password';this.setAttribute('aria-pressed',show)};document.querySelector('form').onsubmit=function(){document.getElementById('submit').disabled=true;document.getElementById('status').textContent='Signing in…'};</script></main></body></html>
            """).replace("NONCE_VALUE",html(req.getAttribute("cspNonce"))));
    }
    private void administration(HttpServletRequest req,HttpServletResponse res,AuthStore.User user) throws IOException,java.sql.SQLException,ServletException {
        String message="";
        if("POST".equals(req.getMethod())) {
            try {
                if("create".equals(req.getParameter("action")))store.create(user.username(),req.getParameter("username"),req.getParameter("password"),req.getParameter("role"),req.getRemoteAddr());
                else if("update".equals(req.getParameter("action")))store.update(user.username(),req.getParameter("username"),req.getParameter("password"),"true".equals(req.getParameter("enabled")),req.getParameter("role"),req.getRemoteAddr());
                else throw new IllegalArgumentException("Unknown action");
                res.sendRedirect("/admin/users");return;
            } catch(IllegalArgumentException | java.sql.SQLException e) { res.setStatus(400);message="Unable to save user. Check username, password length and duplicate names. "+(e instanceof IllegalArgumentException?e.getMessage():""); }
        }
        req.setAttribute("adminMessage",message);req.setAttribute("users",store.users());req.setAttribute("auditEvents",store.events());
        req.getRequestDispatcher("/WEB-INF/views/admin.jsp").forward(req,res);
    }
}
