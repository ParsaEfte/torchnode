import java.nio.file.*;
import java.sql.*;

/** Synthetic in-memory probe of the actual runtime, never a file database. */
public final class RuntimeImageProbe {
    public static void main(String[] args) throws Exception {
        int uid = (Integer) Files.getAttribute(Path.of("/proc/self"), "unix:uid");
        try (var connection = DriverManager.getConnection("jdbc:sqlite::memory:");
             var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT sqlite_version()")) {
            if (!rows.next() || !rows.getString(1).equals("3.54.1") || uid != 10001)
                throw new AssertionError("Runtime identity/SQLite version mismatch");
            if (!System.getProperty("org.sqlite.lib.path", "").equals("/app/lib"))
                throw new AssertionError("Immutable JNI path required");
            System.out.println("{\"sqlite\":\"3.54.1\",\"uid\":10001,\"jni\":\"/app/lib\"}");
        }
    }
}
