package io.github.gavinruff007.torchnode.security;

import java.sql.*;
import java.util.*;

/** Additive security schema, independent of the observation schema version. No evidence is modified. */
public final class AuthStore {
    public record User(String username, String role, boolean enabled, int version) {}
    private final String path;
    private final String dummy = Passwords.hash(UUID.randomUUID().toString());
    public AuthStore(String path) throws SQLException {
        this.path = path;
        try (var db = open()) {
            db.setAutoCommit(false);
            try (var s = db.createStatement()) {
                s.execute("CREATE TABLE IF NOT EXISTS auth_schema(version INTEGER PRIMARY KEY)");
                try(var version=s.executeQuery("SELECT COALESCE(MAX(version),0) FROM auth_schema")) {
                    if(version.next() && version.getInt(1)>1)throw new SQLException("Unsupported security schema version");
                }
                s.execute("CREATE TABLE IF NOT EXISTS auth_users(username TEXT PRIMARY KEY, password_hash TEXT NOT NULL, role TEXT NOT NULL CHECK(role IN ('ADMIN','VIEWER')), enabled INTEGER NOT NULL DEFAULT 1, version INTEGER NOT NULL DEFAULT 1)");
                s.execute("CREATE TABLE IF NOT EXISTS auth_audit(id INTEGER PRIMARY KEY, occurred_at TEXT NOT NULL, actor TEXT NOT NULL, event TEXT NOT NULL, remote_address TEXT NOT NULL)");
                s.execute("INSERT OR IGNORE INTO auth_schema VALUES(1)");
                db.commit();
            } catch (SQLException e) { db.rollback(); throw e; }
        }
    }
    private Connection open() throws SQLException {
        var db = DriverManager.getConnection("jdbc:sqlite:" + path);
        try (var s = db.createStatement()) { s.execute("PRAGMA busy_timeout=5000"); }
        return db;
    }
    public static String username(String name) {
        if (name == null || !name.trim().matches("[A-Za-z0-9@._+\\-]{1,128}"))
            throw new IllegalArgumentException("Use a username or email containing letters, digits, @ . _ + -");
        return name.trim().toLowerCase(Locale.ROOT);
    }
    public synchronized void provision(String name, String password) throws SQLException {
        String normalized = username(name), hash = Passwords.hash(password);
        try (var db = open(); var q = db.prepareStatement("INSERT INTO auth_users(username,password_hash,role) SELECT ?,?,'ADMIN' WHERE NOT EXISTS(SELECT 1 FROM auth_users)")) {
            q.setString(1, normalized); q.setString(2, hash);
            if (q.executeUpdate() != 1) throw new IllegalStateException("Administrator already provisioned");
        }
        audit(normalized,"ADMIN_PROVISIONED","local");
    }
    /** Local console recovery only; never exposed as an HTTP route. */
    public synchronized void resetAdministrator(String name,String password) throws SQLException {
        name=username(name);
        var existing=find(name);
        if(existing==null || !"ADMIN".equals(existing.role()))throw new IllegalArgumentException("Existing administrator not found");
        String hash=Passwords.hash(password);
        try(var db=open();var q=db.prepareStatement("UPDATE auth_users SET password_hash=?,enabled=1,version=version+1 WHERE username=? AND role='ADMIN'")) {
            q.setString(1,hash);q.setString(2,name);
            if(q.executeUpdate()!=1)throw new IllegalArgumentException("Existing administrator not found");
        }
        audit(name,"LOCAL_ADMIN_PASSWORD_RESET","local-console");
    }
    public User find(String name) throws SQLException {
        try (var db = open(); var q = db.prepareStatement("SELECT username,role,enabled,version FROM auth_users WHERE username=?")) {
            q.setString(1, name);
            try (var r = q.executeQuery()) { return r.next() ? new User(r.getString(1),r.getString(2),r.getBoolean(3),r.getInt(4)) : null; }
        }
    }
    public User authenticate(String name, String password) throws SQLException {
        String hash = dummy; User user = null;
        try { name = username(name); } catch (IllegalArgumentException e) { name = ""; }
        try (var db = open(); var q = db.prepareStatement("SELECT password_hash FROM auth_users WHERE username=?")) {
            q.setString(1,name);
            try (var r = q.executeQuery()) { if (r.next()) { hash=r.getString(1); user=find(name); } }
        }
        boolean valid=Passwords.verify(password,hash);
        return valid && user != null && user.enabled() ? user : null;
    }
    public synchronized void create(String actor,String name,String password,String role,String remote) throws SQLException {
        name=username(name);
        if ((role==null || !Set.of("ADMIN","VIEWER").contains(role))) throw new IllegalArgumentException("Invalid role");
        String hash=Passwords.hash(password);
        try (var db=open(); var q=db.prepareStatement("INSERT INTO auth_users(username,password_hash,role) VALUES(?,?,?)")) {
            q.setString(1,name); q.setString(2,hash); q.setString(3,role); q.executeUpdate();
        }
        audit(actor,"USER_CREATED:"+name+":"+role,remote);
    }
    public synchronized void update(String actor,String name,String password,boolean enabled,String remote) throws SQLException {
        var previous=find(username(name));
        if(previous==null)throw new IllegalArgumentException("Unknown user");
        update(actor,name,password,enabled,previous.role(),remote);
    }
    public synchronized void update(String actor,String name,String password,boolean enabled,String role,String remote) throws SQLException {
        name=username(name);
        if((role==null || !Set.of("ADMIN","VIEWER").contains(role)))throw new IllegalArgumentException("Invalid role");
        if (name.equals(actor) && (!enabled || !"ADMIN".equals(role))) throw new IllegalArgumentException("You cannot disable or demote your own account");
        String hash=password==null || password.isEmpty()?null:Passwords.hash(password);
        try (var db=open(); var q=db.prepareStatement("UPDATE auth_users SET enabled=?,password_hash=COALESCE(?,password_hash),role=?,version=version+1 WHERE username=?")) {
            q.setBoolean(1,enabled); q.setString(2,hash); q.setString(3,role);q.setString(4,name);
            if(q.executeUpdate()!=1) throw new IllegalArgumentException("Unknown user");
        }
        audit(actor,"USER_UPDATED:"+name,remote);
    }
    public List<User> users() throws SQLException {
        var users=new ArrayList<User>();
        try(var db=open();var s=db.createStatement();var r=s.executeQuery("SELECT username,role,enabled,version FROM auth_users ORDER BY username")) {
            while(r.next())users.add(new User(r.getString(1),r.getString(2),r.getBoolean(3),r.getInt(4)));
        }
        return users;
    }
    public void audit(String actor,String event,String remote) throws SQLException {
        try(var db=open();var q=db.prepareStatement("INSERT INTO auth_audit(occurred_at,actor,event,remote_address) VALUES(?,?,?,?)")) {
            q.setString(1,java.time.Instant.now().toString()); q.setString(2,actor==null?"anonymous":actor.substring(0,Math.min(128,actor.length())));
            q.setString(3,event);q.setString(4,remote);q.executeUpdate();
        }
    }
    public List<Map<String,Object>> events() throws SQLException {
        var rows=new ArrayList<Map<String,Object>>();
        try(var db=open();var s=db.createStatement();var r=s.executeQuery("SELECT * FROM auth_audit ORDER BY id DESC LIMIT 100")) {
            while(r.next())rows.add(Map.of("timestamp",r.getString("occurred_at"),"actor",r.getString("actor"),"event",r.getString("event"),"remote",r.getString("remote_address")));
        }
        return rows;
    }
}
