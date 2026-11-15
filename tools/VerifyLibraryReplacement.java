/** Test-only proof of classpath replacement on the actual image JRE. */
public final class VerifyLibraryReplacement {
    public static void main(String[] args) throws Exception {
        Class<?> size = Class.forName("com.googlecode.lanterna.TerminalSize");
        String origin = size.getProtectionDomain().getCodeSource().getLocation().toString();
        if (!origin.endsWith("/replacement/lanterna-rebuilt.jar")) throw new AssertionError(origin);
        Object value = size.getConstructor(int.class,int.class).newInstance(80,24);
        if (!size.getMethod("getColumns").invoke(value).equals(80)) throw new AssertionError("Replacement API failed");
        Class<?> posix = Class.forName("jnr.posix.POSIX");
        if (!posix.getProtectionDomain().getCodeSource().getLocation().toString().endsWith("/lib/jnr-posix-3.1.15.jar")) throw new AssertionError("Separate JNR library required");
        if (!System.getProperty("java.version").startsWith("21.")) throw new AssertionError("JRE21 required");
        System.out.println("PASS: source-built Lanterna replacement and separate JNR library on JRE21");
    }
}
