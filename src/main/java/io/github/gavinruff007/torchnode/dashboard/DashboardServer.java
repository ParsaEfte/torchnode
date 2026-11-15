package io.github.gavinruff007.torchnode.dashboard;

import org.apache.catalina.Context;
import io.github.gavinruff007.torchnode.security.AuthFilter;
import io.github.gavinruff007.torchnode.security.AuthStore;
import org.apache.tomcat.util.descriptor.web.FilterDef;
import org.apache.tomcat.util.descriptor.web.FilterMap;
import org.apache.tomcat.util.http.Rfc6265CookieProcessor;
import jakarta.servlet.SessionTrackingMode;
import java.util.Set;
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
    private final String bindAddress;
    private final ScannerService scannerService;
    private final InspectionService inspectionService;
    private Tomcat tomcat;
    private final boolean secureCookies;

    public DashboardServer(int port, String databasePath) {
        this(port, databasePath, "127.0.0.1");
    }

    public DashboardServer(int port, String databasePath, String bindAddress) {
        this(port,databasePath,bindAddress,!"false".equals(System.getenv("TORCHNODE_SECURE_COOKIES")));
    }

    DashboardServer(int port,String databasePath,String bindAddress,boolean secureCookies) {
        this(port,databasePath,bindAddress,secureCookies,new ScannerService(databasePath),new InspectionService(databasePath));
    }

    DashboardServer(int port,String databasePath,String bindAddress,boolean secureCookies,
                    ScannerService scannerService,InspectionService inspectionService) {
        this.secureCookies=secureCookies;
        this.port = port;
        this.databasePath = databasePath;
        this.bindAddress = bindAddress;
        this.scannerService = scannerService;
        this.inspectionService = inspectionService;
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
        tomcat.getConnector().setProperty("address", bindAddress);
        tomcat.getConnector().setMaxPostSize(8192);
        tomcat.getConnector().setMaxParameterCount(40);

        var errors=new org.apache.catalina.valves.ErrorReportValve();
        errors.setShowReport(false);errors.setShowServerInfo(false);tomcat.getHost().getPipeline().addValve(errors);
        Context context = tomcat.addContext("", webRoot.toString());
        var authStore=new AuthStore(databasePath);
        context.addServletContainerInitializer((classes,servletContext)->{
            servletContext.setAttribute("scannerService",scannerService);
            servletContext.setSessionTrackingModes(Set.of(SessionTrackingMode.COOKIE));
            servletContext.getSessionCookieConfig().setName("TNSESSION");
            servletContext.getSessionCookieConfig().setPath("/");
            servletContext.getSessionCookieConfig().setHttpOnly(true);
            servletContext.getSessionCookieConfig().setSecure(secureCookies);
        },null);
        var cookies=new Rfc6265CookieProcessor();cookies.setSameSiteCookies("strict");context.setCookieProcessor(cookies);
        context.setSessionTimeout(30);
        var sessions=new org.apache.catalina.session.StandardManager();sessions.setPathname(null);sessions.setMaxActiveSessions(10000);context.setManager(sessions);
        var filter=new FilterDef();filter.setFilterName("authentication");filter.setFilter(new AuthFilter(authStore,secureCookies));
        context.addFilterDef(filter);
        var mapping=new FilterMap();mapping.setFilterName("authentication");mapping.addURLPattern("/*");mapping.setDispatcher("REQUEST");context.addFilterMapBefore(mapping);
        context.setParentClassLoader(DashboardServer.class.getClassLoader());
        context.addServletContainerInitializer(new JasperInitializer(), null);
        if (!PrecompiledViews.install(context)) {
            Wrapper jsp = Tomcat.addServlet(context, "jsp", new JspServlet());
            jsp.setLoadOnStartup(1);
            context.addServletMappingDecoded("*.jsp", "jsp");
        }
        Tomcat.addServlet(context, "dashboard",
                new DashboardServlet(databasePath, scannerService, inspectionService));
        Tomcat.addServlet(context, "public-api", new PublicApiServlet(databasePath, scannerService));
        context.addServletMappingDecoded("/api/v1/*", "public-api");
        Tomcat.addServlet(context,"topology-api",new TopologyServlet(databasePath,scannerService::isRunning));
        context.addServletMappingDecoded("/api/v1/topology/*","topology-api");
        context.addServletMappingDecoded("/api/v1/topology","topology-api");
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
        if (PrecompiledViews.available()) return root;
        copyView(root, "dashboard.jsp");
        copyView(root, "inspection.jsp");
        copyView(root, "analytics.jsp");
        copyView(root, "report.jsp");
        copyView(root, "layout.jsp");
        copyView(root, "admin.jsp");
        copyView(root, "workspace.jsp");
        copyView(root, "topology.jsp");
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
