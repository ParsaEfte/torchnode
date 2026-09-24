package io.github.gavinruff007.torchnode.dashboard;

import org.apache.catalina.Context;
import org.apache.catalina.Wrapper;
import org.apache.catalina.startup.Tomcat;
import org.apache.jasper.servlet.JasperInitializer;
import org.apache.jasper.servlet.JspServlet;
import io.github.gavinruff007.torchnode.inspection.InspectionService;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

public class DashboardServer {
    private final int port;
    private final String databasePath;
    private final ScannerService scannerService;
    private final InspectionService inspectionService;
    private Tomcat tomcat;

    public DashboardServer(int port, String databasePath) {
        this.port = port;
        this.databasePath = databasePath;
        this.scannerService = new ScannerService(databasePath);
        this.inspectionService = new InspectionService(databasePath);
    }

    public void start() throws Exception {
        Path webRoot = createWebRoot();
        Path baseDir = Files.createTempDirectory("torchnode-tomcat-");
        webRoot.toFile().deleteOnExit();
        baseDir.toFile().deleteOnExit();

        tomcat = new Tomcat();
        tomcat.setBaseDir(baseDir.toString());
        tomcat.setPort(port);
        tomcat.getConnector();
        tomcat.getConnector().setProperty("address", "127.0.0.1");

        Context context = tomcat.addContext("", webRoot.toString());
        context.setParentClassLoader(DashboardServer.class.getClassLoader());
        context.addServletContainerInitializer(new JasperInitializer(), null);
        Wrapper jsp = Tomcat.addServlet(context, "jsp", new JspServlet());
        jsp.setLoadOnStartup(1);
        context.addServletMappingDecoded("*.jsp", "jsp");
        Tomcat.addServlet(context, "dashboard",
                new DashboardServlet(databasePath, scannerService, inspectionService));
        context.addServletMappingDecoded("/", "dashboard");

        tomcat.start();
        if (!tomcat.getConnector().getState().isAvailable()) {
            stop();
            throw new IllegalStateException("Dashboard could not bind to port " + port);
        }
    }

    public void await() {
        if (tomcat != null) {
            tomcat.getServer().await();
        }
    }

    public void stop() {
        scannerService.stop();
        inspectionService.close();
        if (tomcat == null) {
            return;
        }
        try {
            tomcat.stop();
            tomcat.destroy();
        } catch (Exception e) {
            System.err.println("[Dashboard] Failed to stop cleanly: " + e.getMessage());
        }
    }

    private Path createWebRoot() throws IOException {
        Path root = Files.createTempDirectory("torchnode-webapp-");
        copyView(root, "dashboard.jsp");
        copyView(root, "inspection.jsp");
        return root;
    }

    private void copyView(Path root, String name) throws IOException {
        Path view = root.resolve("WEB-INF/views/" + name);
        Files.createDirectories(view.getParent());
        try (InputStream source = DashboardServer.class.getResourceAsStream(
                "/webapp/WEB-INF/views/" + name)) {
            if (source == null) throw new IOException("JSP resource is missing: " + name);
            Files.copy(source, view);
        }
    }
}
