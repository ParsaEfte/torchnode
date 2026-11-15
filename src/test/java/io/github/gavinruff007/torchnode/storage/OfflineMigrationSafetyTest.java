package io.github.gavinruff007.torchnode.storage;

import io.github.gavinruff007.torchnode.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class OfflineMigrationSafetyTest {
    @TempDir Path temporary;
    Path root,source;
    private final com.fasterxml.jackson.databind.ObjectMapper json=new com.fasterxml.jackson.databind.ObjectMapper();
    @BeforeEach void fixture()throws Exception {
        root=temporary.toRealPath();source=root.resolve("backup-v5.sqlite");
        try(var store=new SqliteNodeStore(source.toString(),false,5)) {
            store.saveObservation(new DiscoveryObservation(new NodeIdentity("ab".repeat(64)),"discv4",List.of(new NodeEndpoint("192.0.2.1",NodeEndpoint.Transport.UDP,30303,NodeEndpoint.AddressFamily.IPV4,NodeEndpoint.Purpose.DISCOVERY)),Instant.parse("2026-01-01T00:00:00Z"),"offline historical fixture"));
        }
        manifest();
    }
    private String hash()throws Exception{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(source)));}
    private void manifest()throws Exception {Files.writeString(root.resolve("B6B-OFFLINE.json"),json.writeValueAsString(Map.of("kind","fixture","source","backup-v5.sqlite","offline",true,"sha256",hash())));}
    private record Result(int code,String output){}
    private Result run(String command,String...extra)throws Exception {
        var args=new ArrayList<String>(List.of("python3",Path.of("scripts/topology-migration.py").toAbsolutePath().toString(),command,"--workspace",root.toString()));
        args.addAll(Arrays.asList(extra));var process=new ProcessBuilder(args).redirectErrorStream(true).start();String output=new String(process.getInputStream().readAllBytes());assertTrue(process.waitFor(90,java.util.concurrent.TimeUnit.SECONDS));return new Result(process.exitValue(),output);
    }
    private void sql(String sql)throws Exception {try(var db=DriverManager.getConnection("jdbc:sqlite:"+source);var statement=db.createStatement()){statement.execute(sql);}}
    @Test void validPreflightAndDryRunPreserveOriginalAndPrepareRollback()throws Exception {
        String original=hash();assertEquals(0,run("preflight").code());
        var result=run("dry-run","--java",Path.of(System.getProperty("java.home"),"bin/java").toRealPath().toString(),"--classpath",System.getProperty("java.class.path"));assertEquals(0,result.code(),result.output());
        var report=json.readTree(result.output().trim());assertEquals("passed",report.path("status").asText());assertTrue(report.path("sourceUnchanged").asBoolean());assertTrue(report.path("historicalPreserved").asBoolean());assertTrue(report.path("rollbackPrepared").asBoolean());assertEquals("PENDING",report.path("realBackupVerification").asText());assertEquals(original,hash());assertFalse(report.path("deploymentReady").asBoolean(true));
        var again=run("dry-run","--java",Path.of(System.getProperty("java.home"),"bin/java").toRealPath().toString(),"--classpath",System.getProperty("java.class.path"));assertEquals(0,again.code(),again.output());assertNotEquals(report.path("reportDirectory").asText(),json.readTree(again.output().trim()).path("reportDirectory").asText());
    }
    @Test void onlineWrapperVerifiesActualV5FixtureWithoutMigration()throws Exception {
        String original=hash();
        Path onlineSource=root.resolve("fixture-v5.sqlite");Files.copy(source,onlineSource);
        Files.writeString(root.resolve("B6B-FIXTURE.json"),json.writeValueAsString(Map.of("kind","synthetic-fixture","source","fixture-v5.sqlite")));
        Files.setPosixFilePermissions(root.resolve("B6B-FIXTURE.json"),java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        String stamp=java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC).format(Instant.now());
        Path destination=root.resolve("b6b-real-v5-"+stamp+"-"+UUID.randomUUID().toString().replace("-",""));
        var process=new ProcessBuilder("python3",Path.of("scripts/topology-online-backup.py").toAbsolutePath().toString(),
                "fixture","--source",onlineSource.toString(),"--workspace",destination.toString(),
                "--fixture-root",root.toString(),"--approval-reference","java-synthetic-fixture-only",
                "--minimum-free-bytes","1048576","--supervisor-seconds","90").redirectErrorStream(true).start();
        assertTrue(process.waitFor(100,java.util.concurrent.TimeUnit.SECONDS));
        String output=new String(process.getInputStream().readAllBytes());assertEquals(0,process.exitValue(),output);
        assertTrue(Files.isRegularFile(destination.resolve("B6B-OFFLINE.json")));
        assertEquals(original,hash());assertEquals(original,HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(onlineSource))));
        var preflight=new ProcessBuilder("python3",Path.of("scripts/topology-migration.py").toAbsolutePath().toString(),
                "preflight","--workspace",destination.toString()).redirectErrorStream(true).start();
        assertTrue(preflight.waitFor(90,java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(0,preflight.exitValue(),new String(preflight.getInputStream().readAllBytes()));
        assertEquals("fixture",json.readTree(Files.readString(destination.resolve("B6B-OFFLINE.json"))).path("kind").asText());
    }
    @Test void corruptUnexpectedPartialAndMissingSchemasAreRejected()throws Exception {
        sql("INSERT INTO schema_migrations VALUES(7)");manifest();assertNotEquals(0,run("preflight").code());
        sql("DELETE FROM schema_migrations WHERE version=7");sql("DROP TABLE change_events");manifest();assertNotEquals(0,run("preflight").code());
        Files.writeString(source,"corrupt fixture");manifest();assertNotEquals(0,run("preflight").code());
    }
    @Test void sourceHashSidecarsAndAmbiguousPathsFailBeforeMigration()throws Exception {
        Files.writeString(root.resolve("B6B-OFFLINE.json"),json.writeValueAsString(Map.of("kind","fixture","source","backup-v5.sqlite","offline",true,"sha256","bad")));assertNotEquals(0,run("preflight").code());
        manifest();Files.writeString(Path.of(source+"-wal"),"not an offline source");assertNotEquals(0,run("preflight").code());Files.delete(Path.of(source+"-wal"));
        Path saved=root.resolve("saved.sqlite");Files.move(source,saved);Files.createSymbolicLink(source,saved);assertNotEquals(0,run("preflight").code());Files.delete(source);Files.move(saved,source);
        Files.writeString(root.resolve("B6B-OFFLINE.json"),json.writeValueAsString(Map.of("kind","fixture","source","torchnode.db","offline",true,"sha256","unused")));assertNotEquals(0,run("preflight").code());
    }
    @Test void onlineFixtureSnapshotIncludesWalAndRefusesCollision()throws Exception {
        try(var db=DriverManager.getConnection("jdbc:sqlite:"+source);var statement=db.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");statement.execute("PRAGMA wal_autocheckpoint=0");statement.execute("INSERT INTO p2p_observations VALUES('wal-only',1,'{}','{}')");
            assertTrue(Files.size(Path.of(source+"-wal"))>0);
            Files.writeString(root.resolve("B6B-OFFLINE.json"),json.writeValueAsString(Map.of("kind","fixture","source","backup-v5.sqlite","offline",false,"sha256","active-fixture-not-used-for-online-backup")));
            var result=run("snapshot-fixture","--destination","checkpoint.sqlite");assertEquals(0,result.code(),result.output());
            try(var backup=DriverManager.getConnection("jdbc:sqlite:"+root.resolve("checkpoint.sqlite"));var query=backup.createStatement();var rows=query.executeQuery("SELECT COUNT(*) FROM p2p_observations WHERE node_key='wal-only'")){assertEquals(1,rows.getInt(1));}
            byte[] before=Files.readAllBytes(root.resolve("checkpoint.sqlite"));assertNotEquals(0,run("snapshot-fixture","--destination","checkpoint.sqlite").code());assertArrayEquals(before,Files.readAllBytes(root.resolve("checkpoint.sqlite")));
        }
    }
    @Test void migrationFailureRetainsV5AndReportsFailureWithoutChangingBackup()throws Exception {
        sql("CREATE TRIGGER injected_failure BEFORE INSERT ON schema_migrations WHEN NEW.version=6 BEGIN SELECT RAISE(ABORT,'fixture injection'); END");manifest();String original=hash();
        var result=run("dry-run","--java",Path.of(System.getProperty("java.home"),"bin/java").toRealPath().toString(),"--classpath",System.getProperty("java.class.path"));assertNotEquals(0,result.code());assertEquals(original,hash());
        try(var files=Files.list(root)){Path run=files.filter(p->p.getFileName().toString().startsWith("run-")).findFirst().orElseThrow();assertEquals("failed",json.readTree(Files.readString(run.resolve("report.json"))).path("status").asText());
            try(var db=DriverManager.getConnection("jdbc:sqlite:"+run.resolve("working.sqlite"));var query=db.createStatement();var rows=query.executeQuery("SELECT MAX(version) FROM schema_migrations")){assertEquals(5,rows.getInt(1));}
        }
    }
    @Test void foreignKeyFailureRejectsPreflight()throws Exception {
        sql("CREATE TABLE broken_reference(id INTEGER REFERENCES nodes(key))");sql("INSERT INTO broken_reference VALUES(999)");manifest();assertNotEquals(0,run("preflight").code());
    }
    @Test void startupFailureLeavesVerifiedBackupAndMigrationArtifactIntact()throws Exception {
        // Invalid security schema is intentionally independent of observation schema v5.
        sql("CREATE TABLE auth_schema(version INTEGER PRIMARY KEY)");sql("INSERT INTO auth_schema VALUES(2)");manifest();String original=hash();
        var result=run("dry-run","--java",Path.of(System.getProperty("java.home"),"bin/java").toRealPath().toString(),"--classpath",System.getProperty("java.class.path"));assertNotEquals(0,result.code());assertEquals(original,hash());
        try(var files=Files.list(root)){Path directory=files.filter(p->p.getFileName().toString().startsWith("run-")).findFirst().orElseThrow();assertEquals("failed",json.readTree(Files.readString(directory.resolve("report.json"))).path("status").asText());assertTrue(Files.exists(directory.resolve("after.json")));}
    }
    @Test void bundledSqliteContainsWalResetFix()throws Exception {
        try(var db=DriverManager.getConnection("jdbc:sqlite::memory:");var statement=db.createStatement();var row=statement.executeQuery("SELECT sqlite_version()")) {
            assertTrue(row.next());String[] parts=row.getString(1).split("\\.");int number=Integer.parseInt(parts[0])*1000000+Integer.parseInt(parts[1])*1000+Integer.parseInt(parts[2]);assertTrue(number>=3051003,"SQLite WAL-reset fix required");
        }
    }
    @Test void futureSchemaIsRejectedBeforeWritableInitialization()throws Exception {
        sql("INSERT INTO schema_migrations VALUES(7)");String before=hash();assertThrows(SQLException.class,()->new SqliteNodeStore(source.toString()));assertEquals(before,hash());
    }
}
