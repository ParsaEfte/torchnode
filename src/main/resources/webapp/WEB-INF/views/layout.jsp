<%@ page pageEncoding="UTF-8" %>
<%@ page import="io.github.gavinruff007.torchnode.security.AuthStore" %>
<% var layoutUser=(AuthStore.User)request.getAttribute("authUser"); boolean layoutAdmin=layoutUser!=null && "ADMIN".equals(layoutUser.role()); %>
<style>
:root{color-scheme:dark;--nav-width:224px}body{margin:0;background:#090f1a;color:#e5edf8;font-family:Inter,system-ui,sans-serif}.tn-top{height:62px;position:sticky;top:0;z-index:40;display:flex;align-items:center;gap:16px;padding:0 22px;border-bottom:1px solid #2a364b;background:#101827}.tn-brand{font-weight:800;color:#edf5ff;text-decoration:none}.tn-status{color:#9ec9c1;font-size:12px;margin-left:auto}.tn-profile{font-size:13px;color:#b6c8dd}.tn-top button{height:34px;padding:5px 10px;background:#1a2940;color:#e4edfa;border:1px solid #3b526e;border-radius:6px;cursor:pointer}.tn-top form{margin:0}.tn-sidebar{position:fixed;top:63px;bottom:0;left:0;width:var(--nav-width);padding:20px 10px;box-sizing:border-box;background:#101827;border-right:1px solid #28364b;overflow:auto;z-index:35;transition:width .15s}.tn-sidebar a{display:flex;gap:12px;align-items:center;padding:11px 12px;color:#b6c6dc;text-decoration:none;border-radius:7px;font-size:13px;white-space:nowrap}.tn-sidebar a:hover,.tn-sidebar a[aria-current=page]{background:#20394b;color:#61e6d1}.tn-sidebar .icon{width:18px;flex-shrink:0;text-align:center;color:#78b3cf}.shell{box-sizing:border-box!important;width:auto!important;max-width:1700px;margin-left:var(--nav-width)!important;margin-right:0!important;padding:28px!important}.tn-collapsed{--nav-width:64px}.tn-collapsed .tn-sidebar .nav-label{display:none}a:focus-visible,button:focus-visible,input:focus-visible,select:focus-visible{outline:3px solid #f5bd68;outline-offset:3px}@media(max-width:850px){.tn-sidebar{transform:translateX(-100%);visibility:hidden;width:224px}.tn-open .tn-sidebar{transform:translateX(0);visibility:visible}.shell{margin-left:0!important;padding:18px!important}.tn-profile{display:none}.tn-status{font-size:11px}.tn-top{padding:0 12px;gap:10px}}@media(max-width:450px){.tn-status{display:none}}@media(prefers-reduced-motion:reduce){*{transition:none!important}}@media print{.tn-top,.tn-sidebar{display:none}.shell{margin:0!important;padding:0!important}}
</style>
<header class="tn-top"><button id="tn-menu" type="button" aria-label="Toggle navigation" aria-controls="tn-navigation" aria-expanded="true">☰</button><a class="tn-brand" href="/">◈ TorchNode</a><span class="tn-status"><%= ((io.github.gavinruff007.torchnode.dashboard.ScannerService)request.getServletContext().getAttribute("scannerService")).isRunning()?"Discovery scan running":"Discovery scan stopped" %> · App online · UTC</span><span class="tn-profile"><%= io.github.gavinruff007.torchnode.security.AuthFilter.html(layoutUser==null?"":layoutUser.username()+" · "+layoutUser.role()) %></span><form action="/logout" method="post"><input type="hidden" name="csrf" value="<%= io.github.gavinruff007.torchnode.security.AuthFilter.html(request.getAttribute("csrfToken")) %>"><button type="submit">Log out</button></form></header>
<nav class="tn-sidebar" id="tn-navigation" aria-label="Main navigation">
<% String[][] layoutLinks={{"/","◈","Overview Dashboard"},{"/explorer","⌕","Node Explorer"},{"/topology","⌘","Network Topology"},{"/discovery","⌁","Discovery Analytics"},{"/p2p","⇄","P2P Inspection"},{"/apis","⇆","RPC & Beacon Monitoring"},{"/statistics","▥","Network Statistics"},{"/clients","▤","Client Distribution"},{"/geography","◎","Geographic Analytics"},{"/trends","↗","Historical Trends"},{"/events","⚑","Changes & Events"},{"/reports/network","▣","Reports"},{"/admin/users","♙","User Management"},{"/settings","⚙","Settings"}};
for(var link:layoutLinks){if(!layoutAdmin && (link[0].startsWith("/admin")||link[0].equals("/settings")))continue; %>
<a href="<%=link[0]%>" aria-label="<%=link[2]%>" title="<%=link[2]%>" <%=link[0].equals(request.getServletPath())?"aria-current=\"page\"":""%>><span class="icon" aria-hidden="true"><%=link[1]%></span><span class="nav-label"><%=link[2]%></span></a>
<% } %><a href="/legal" aria-label="Licenses, redistribution rights and source" title="Licenses and source"><span class="icon" aria-hidden="true">§</span><span class="nav-label">Licenses &amp; source</span></a></nav>
<script nonce="<%= request.getAttribute("cspNonce") %>">
(()=>{
    const button=document.getElementById('tn-menu');
    try {if(localStorage.getItem('tn-collapsed')==='true')document.body.classList.add('tn-collapsed');} catch {}
    const update=()=>button.setAttribute('aria-expanded',innerWidth<=850?document.body.classList.contains('tn-open'):!document.body.classList.contains('tn-collapsed'));
    button.onclick=()=>{
        if(innerWidth<=850)document.body.classList.toggle('tn-open');
        else {
            document.body.classList.toggle('tn-collapsed');
            try {localStorage.setItem('tn-collapsed',document.body.classList.contains('tn-collapsed'));} catch {}
        }
        update();
    };
    addEventListener('keydown',event=>{
        if(event.key==='Escape' && document.body.classList.contains('tn-open')) {
            document.body.classList.remove('tn-open');update();button.focus();
        }
    });
    update();addEventListener('resize',update);
})();
</script>
