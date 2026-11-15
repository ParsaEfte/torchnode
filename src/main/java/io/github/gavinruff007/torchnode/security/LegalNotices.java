package io.github.gavinruff007.torchnode.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Public, fixed legal resources only; never reads request-selected files or storage. */
public final class LegalNotices {
    private LegalNotices() {}
    private static final Map<String,String> TEXTS=Map.of(
        "/legal/gpl-3.0.txt","gpl-3.0.txt", "/legal/lgpl-3.0.txt","lgpl-3.0.txt",
        "/legal/lgpl-2.1.txt","lgpl-2.1.txt", "/legal/epl-2.0.txt","epl-2.0.txt",
        "/legal/mit-project.txt","mit-project.txt", "/legal/distribution-scope.txt","distribution-scope.txt",
        "/legal/third-party-notices.txt","third-party-notices.txt");
    private static String text(String name)throws IOException {
        try(var input=LegalNotices.class.getResourceAsStream("/legal/"+name)) {
            if(input==null)throw new IOException("Required legal resource missing");
            return new String(input.readAllBytes(),StandardCharsets.UTF_8);
        }
    }
    public static String summaryHtml() {
        return "<p class=\"tn-legal\">Copyright &copy; 2026 Parsa Eftekharmanesh. "
            +"Covered combination: GNU GPLv3; no warranty. "
            +"<a href=\"/legal\" style=\"color:inherit;text-decoration:underline\">Licenses, redistribution rights and source</a>.</p>";
    }
    public static String consoleSummary()throws IOException {
        return "Copyright (c) 2026 Parsa Eftekharmanesh. Covered application/helper combination: GNU GPLv3.\n"
            +"WITHOUT ANY WARRANTY. You may modify and redistribute under the applicable licenses.\n"
            +"License copies: legal/ in the JAR; HTTP /legal; DISTRIBUTION-LICENSE.md and matching source materials.\n"
            +text("lgpl-copyright.txt")+"Library GPL/LGPL license copies are linked from /legal and bundled in legal/.";
    }
    public static boolean serve(HttpServletRequest request,HttpServletResponse response)throws IOException {
        String path=request.getServletPath();
        if(!"/legal".equals(path) && !TEXTS.containsKey(path))return false;
        String method=request.getMethod();
        if(!"GET".equals(method) && !"HEAD".equals(method)) {
            response.setHeader("Allow","GET, HEAD");response.sendError(405);return true;
        }
        response.setContentType("/legal".equals(path)?"text/html;charset=UTF-8":"text/plain;charset=UTF-8");
        if("HEAD".equals(method))return true;
        if(TEXTS.containsKey(path)){response.getWriter().write(text(TEXTS.get(path)));return true;}
        response.getWriter().write("""
            <!doctype html><html lang="en"><head><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1">
            <title>Licenses and source · TorchNode Observatory</title>
            <style>body{background:#090f1a;color:#e9eff8;font:16px system-ui;margin:0}main{max-width:70rem;margin:auto;padding:24px;line-height:1.6}a{color:#76dfcc}a:focus-visible{outline:3px solid #f5bc65;outline-offset:3px}pre{white-space:pre-wrap;overflow-wrap:anywhere}li{margin:8px 0}</style></head>
            <body><main><h1>Licenses, rights and matching source</h1>
            <p>Copyright &copy; 2026 Parsa Eftekharmanesh. The covered application/geth-helper combination is conveyed under GNU GPL version 3. Existing MIT and component grants remain available for the files they cover.</p>
            <p>WITHOUT ANY WARRANTY, including merchantability or fitness, subject to the full licenses and applicable law. You may modify and redistribute the covered work under GPLv3. Your permitted library modification, relinking and reverse-engineering rights are preserved.</p>
            <p>Non-production/new-storage describes tested support, not a restriction on your license rights. Recovery is NOT VERIFIED.</p>
            <ul><li><a href="/legal/gpl-3.0.txt">GNU GPL version 3</a></li>
            <li><a href="/legal/lgpl-3.0.txt">GNU LGPL version 3</a> — Lanterna</li>
            <li><a href="/legal/lgpl-2.1.txt">GNU LGPL version 2.1</a> — JNR-POSIX alternative</li>
            <li><a href="/legal/epl-2.0.txt">EPL version 2.0</a> — separately supplied ECJ build materials</li>
            <li><a href="/legal/mit-project.txt">Project MIT grant</a></li>
            <li><a href="/legal/distribution-scope.txt">Distribution scope and source-delivery instructions</a></li>
            <li><a href="/legal/third-party-notices.txt">Complete bundled component copyright and license notices</a></li></ul>
            <h2>LGPL library copyright notices</h2><pre>"""+AuthFilter.html(text("lgpl-copyright.txt"))+"""
            </pre><p>Full original library notices and GPL/LGPL copies are available through the links above.</p>
            <h2>Source and library replacement</h2><p>Matching preferred sources, build/install scripts and the thin-JAR/separate-library replacement layout accompany the published release. They permit rebuilding and replacing interface-compatible libraries; no installation key is required by this release.</p>
            <p><a href="https://github.com/ParsaEfte/torchnode/releases/tag/v1.0.0">Versioned source, notices and replacement materials at publication</a>. Local prerelease operation does not assert that the public release already exists.</p>
            <p><a href="/">Return to the observatory</a></p></main></body></html>""");
        return true;
    }
}
