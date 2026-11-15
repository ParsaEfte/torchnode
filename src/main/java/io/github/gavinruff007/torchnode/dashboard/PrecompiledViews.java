package io.github.gavinruff007.torchnode.dashboard;

import jakarta.servlet.http.HttpServlet;
import org.apache.catalina.Context;
import org.apache.catalina.startup.Tomcat;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Optional immutable build-time JSP mappings. A malformed registry fails closed. */
final class PrecompiledViews {
    static final String RESOURCE="/torchnode-precompiled-jsp.properties";
    static final Set<String> VIEWS=Set.of("dashboard.jsp","inspection.jsp","analytics.jsp",
            "report.jsp","layout.jsp","admin.jsp","workspace.jsp","topology.jsp");
    static boolean available(){return PrecompiledViews.class.getResource(RESOURCE)!=null;}
    static Map<String,String> read(InputStream input)throws IOException {
        Map<String,String> result=new TreeMap<>();
        try(var reader=new BufferedReader(new InputStreamReader(input,StandardCharsets.UTF_8))) {
            for(String line;(line=reader.readLine())!=null;) {
                if(line.isBlank()||line.startsWith("#"))continue;
                String[] pair=line.split("=",-1);
                if(pair.length!=2 || !pair[0].startsWith("/WEB-INF/views/") ||
                   !VIEWS.contains(pair[0].substring("/WEB-INF/views/".length())) ||
                   !pair[1].matches("org\\.apache\\.jsp\\.(?:[A-Za-z0-9_]+\\.)*[A-Za-z0-9_]+_jsp") ||
                   result.putIfAbsent(pair[0],pair[1])!=null)
                    throw new IOException("Invalid precompiled view registry");
            }
        }
        if(result.size()!=VIEWS.size())throw new IOException("Incomplete precompiled view registry");
        return Map.copyOf(result);
    }
    static boolean install(Context context)throws Exception {
        try(InputStream input=PrecompiledViews.class.getResourceAsStream(RESOURCE)) {
            if(input==null)return false;
            for(var mapping:read(input).entrySet()) {
                var type=Class.forName(mapping.getValue()).asSubclass(HttpServlet.class);
                String name="precompiled:"+mapping.getKey();
                Tomcat.addServlet(context,name,type.getDeclaredConstructor().newInstance()).setLoadOnStartup(1);
                context.addServletMappingDecoded(mapping.getKey(),name);
            }
            return true;
        }
    }
}
