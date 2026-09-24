<%@ page contentType="text/html;charset=UTF-8" language="java" %>
<%@ page import="io.github.gavinruff007.torchnode.model.NodeRecord" %>
<%@ page import="io.github.gavinruff007.torchnode.model.NodeType" %>
<%@ page import="io.github.gavinruff007.torchnode.dashboard.DashboardServlet" %>
<%@ page import="java.net.URLEncoder" %>
<%@ page import="java.nio.charset.StandardCharsets" %>
<%@ page import="java.time.ZoneId" %>
<%@ page import="java.time.format.DateTimeFormatter" %>
<%@ page import="java.util.List" %>
<%!
    private String h(Object value) {
        if (value == null) return "—";
        return value.toString().replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }

    private String shortId(String value) {
        if (value == null || value.isBlank()) return "—";
        return value.length() <= 18 ? h("0x" + value)
                : h("0x" + value.substring(0, 10) + "…" + value.substring(value.length() - 6));
    }

    private String pageUrl(String query, String type, int size, int page, String sort) {
        return "/?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8)
                + "&amp;type=" + URLEncoder.encode(type, StandardCharsets.UTF_8)
                + "&amp;size=" + size + "&amp;page=" + page
                + "&amp;sort=" + URLEncoder.encode(sort, StandardCharsets.UTF_8);
    }
%>
<%
    List<NodeRecord> nodes = (List<NodeRecord>) request.getAttribute("nodes");
    List<NodeType> nodeTypes = (List<NodeType>) request.getAttribute("nodeTypes");
    String query = (String) request.getAttribute("query");
    String selectedType = (String) request.getAttribute("selectedType");
    String sort = (String) request.getAttribute("sort");
    String csrfToken = (String) request.getAttribute("csrfToken");
    String message = (String) request.getAttribute("message");
    String error = (String) request.getAttribute("error");
    boolean scannerRunning = Boolean.TRUE.equals(request.getAttribute("scannerRunning"));
    int currentPage = (Integer) request.getAttribute("page");
    int pageSize = (Integer) request.getAttribute("pageSize");
    int totalPages = (Integer) request.getAttribute("totalPages");
    int totalResults = (Integer) request.getAttribute("totalResults");
    DateTimeFormatter timeFormat = DateTimeFormatter.ofPattern("MMM d, HH:mm:ss")
            .withZone(ZoneId.systemDefault());
%>
<!doctype html>
<html lang="en">
<head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>TorchNode Observatory</title>
    <style>
        :root { color-scheme: dark; --bg:#080b12; --panel:#111722; --line:#263044; --muted:#8792a7; --text:#eef3fb; --cyan:#42d9d0; --green:#74e39a; --amber:#f4bd62; }
        * { box-sizing:border-box; }
        body { margin:0; min-height:100vh; background:radial-gradient(circle at 85% -10%,#123c49 0,transparent 32%),var(--bg); color:var(--text); font:14px/1.5 Inter,ui-sans-serif,system-ui,sans-serif; }
        .shell { width:min(1440px,calc(100% - 40px)); margin:auto; padding:36px 0 60px; }
        header { display:flex; justify-content:space-between; gap:24px; align-items:end; margin-bottom:28px; }
        .eyebrow { color:var(--cyan); font-size:11px; font-weight:800; letter-spacing:.18em; text-transform:uppercase; }
        h1 { margin:4px 0 0; font-size:clamp(28px,4vw,48px); letter-spacing:-.045em; line-height:1; }
        .header-actions,.live { display:flex; gap:10px; align-items:center; }
        .live { color:var(--muted); margin-right:8px; }
        .pulse { width:8px; height:8px; border-radius:50%; background:currentColor; box-shadow:0 0 0 6px currentColor; }
        .stats { display:grid; grid-template-columns:repeat(5,1fr); gap:12px; margin-bottom:22px; }
        .card,.table-wrap { border:1px solid var(--line); background:#111722cc; backdrop-filter:blur(12px); border-radius:14px; }
        .card { padding:18px; }
        .label { color:var(--muted); font-size:11px; letter-spacing:.1em; text-transform:uppercase; }
        .value { margin-top:5px; font-size:28px; font-weight:750; letter-spacing:-.04em; }
        .unit { color:var(--muted); font-size:14px; font-weight:500; }
        .filters { display:flex; gap:10px; margin:0 0 14px; }
        .action-form { display:inline-flex; margin:0; }
        input,select,button { height:42px; border-radius:9px; border:1px solid var(--line); background:#0d121c; color:var(--text); padding:0 13px; font:inherit; }
        input { width:min(420px,100%); } select { min-width:170px; } button,.button { cursor:pointer; background:var(--cyan); border:1px solid var(--cyan); border-radius:9px; color:#061011; font-weight:750; text-decoration:none; height:42px; padding:0 13px; display:inline-flex; align-items:center; }
        .button.secondary,button.secondary { background:#151d2a; border-color:var(--line); color:var(--text); }
        button.danger { background:#2d1920; border-color:#60303c; color:#ff9eaa; }
        button.small { height:30px; padding:0 10px; font-size:12px; }
        .notice { margin:0 0 14px; padding:11px 14px; border:1px solid #28583b; border-radius:9px; background:#10271a; color:var(--green); }
        .notice.error { border-color:#60303c; background:#2d1920; color:#ff9eaa; }
        .table-wrap { overflow:hidden; }
        .table-head { display:flex; justify-content:space-between; padding:15px 18px; border-bottom:1px solid var(--line); color:var(--muted); }
        table { width:100%; border-collapse:collapse; }
        th { padding:12px 16px; text-align:left; color:var(--muted); font-size:10px; letter-spacing:.1em; text-transform:uppercase; }
        td { padding:14px 16px; border-top:1px solid #20293a; white-space:nowrap; }
        tbody tr:hover { background:#18202e; }
        .mono { font-family:ui-monospace,SFMono-Regular,Menlo,monospace; font-size:12px; }
        .muted { color:var(--muted); } .good { color:var(--green); } .warn { color:var(--amber); }
        .badge { display:inline-flex; border:1px solid #334057; border-radius:999px; padding:3px 8px; font-size:11px; }
        .dot { display:inline-block; width:7px; height:7px; margin-right:6px; border-radius:50%; background:currentColor; }
        .empty { padding:70px 20px; text-align:center; color:var(--muted); }
        .pagination { display:flex; justify-content:space-between; align-items:center; gap:12px; padding:14px 18px; border-top:1px solid var(--line); color:var(--muted); }
        .page-links { display:flex; align-items:center; gap:8px; }
        .page-link { padding:6px 10px; border:1px solid var(--line); border-radius:7px; color:var(--text); text-decoration:none; }
        .page-link.disabled { opacity:.35; pointer-events:none; }
        .table-head strong { color:var(--text); font-weight:650; }
        .table-wrap { position:relative; }
        table { min-width:1180px; }
        th,td { vertical-align:middle; }
        td { padding-top:11px; padding-bottom:11px; }
        td.client-cell { overflow:hidden; text-overflow:ellipsis; max-width:230px; }
        td.actions { text-align:right; }
        .service { display:inline-flex; align-items:center; margin-right:10px; font-size:12px; }
        .sort-link { display:inline-flex; align-items:center; gap:6px; color:inherit; text-decoration:none; white-space:nowrap; }
        .sort-link:hover,.sort-link.active { color:var(--cyan); }
        .sort-arrow { font-size:13px; line-height:1; opacity:.5; }
        .sort-link.active .sort-arrow { opacity:1; }
        .button.small { height:32px; font-size:12px; border-color:#36616c; color:var(--cyan); }
        button:disabled,select:disabled,input:disabled { cursor:wait; opacity:.65; }
        .loading-hint { display:none; color:var(--cyan); font-size:12px; align-items:center; gap:7px; }
        body.pending .loading-hint { display:inline-flex; }
        body.pending .table-wrap table,body.pending .pagination,body.pending .empty { opacity:.14; }
        .table-skeleton { display:none; position:absolute; inset:50px 0 0; padding:12px 18px; background:#111722d9; pointer-events:none; }
        body.pending .table-skeleton { display:block; }
        .skeleton-row { display:grid; grid-template-columns:1.3fr 1.1fr .6fr 1.4fr .8fr .5fr .8fr .9fr .6fr; gap:18px; align-items:center; height:51px; border-bottom:1px solid #20293a; }
        .skeleton { height:12px; border-radius:5px; background:linear-gradient(90deg,#1d2837 20%,#2b3b4b 45%,#1d2837 70%); background-size:220% 100%; animation:shimmer 1.5s linear infinite; }
        .skeleton-row span:nth-child(3n) { width:65%; }
        @keyframes shimmer { to { background-position-x:-220%; } }
        @media (prefers-reduced-motion:reduce) { .skeleton { animation:none; } }
        @media (max-width:980px) { .stats { grid-template-columns:repeat(2,1fr); } .table-wrap { overflow-x:auto; } }
        @media (max-width:620px) { .shell { width:min(100% - 24px,1440px); padding-top:24px; } header { align-items:start; flex-direction:column; } .header-actions { flex-wrap:wrap; } .stats { grid-template-columns:1fr 1fr; } .filters { flex-wrap:wrap; } input { flex:1 1 100%; } select,.filters button { flex:1; } }
    </style>
</head>
<body>
<main class="shell">
    <header>
        <div><div class="eyebrow">Ethereum network intelligence</div><h1>TorchNode Observatory</h1></div>
        <div class="header-actions">
            <div class="live <%= scannerRunning ? "good" : "muted" %>"><span class="pulse"></span><%= scannerRunning ? "Scanner running" : "Scanner stopped" %></div>
            <a class="button secondary" href="/export.csv">Export CSV</a>
            <form class="action-form" method="post" action="/scanner/<%= scannerRunning ? "stop" : "start" %>">
                <input type="hidden" name="csrf" value="<%= h(csrfToken) %>">
                <button class="<%= scannerRunning ? "danger" : "" %>" type="submit"><%= scannerRunning ? "Stop scan" : "Start scan" %></button>
            </form>
        </div>
    </header>

    <% if (message != null) { %><div class="notice"><%= h(message) %></div><% } %>
    <% if (error != null) { %><div class="notice error"><%= h(error) %></div><% } %>

    <section class="stats">
        <div class="card"><div class="label">Discovered</div><div class="value"><%= request.getAttribute("totalNodes") %></div></div>
        <div class="card"><div class="label">Active 15m</div><div class="value"><%= request.getAttribute("activeNodes") %></div></div>
        <div class="card"><div class="label">RPC online</div><div class="value"><%= request.getAttribute("rpcNodes") %></div></div>
        <div class="card"><div class="label">Beacon API</div><div class="value"><%= request.getAttribute("beaconNodes") %></div></div>
        <div class="card"><div class="label">Avg P2P TCP connect</div><div class="value"><%= request.getAttribute("averageLatency") == null ? "—" : String.format("%.0f", request.getAttribute("averageLatency")) %><span class="unit"><%= request.getAttribute("averageLatency") == null ? "" : " ms" %></span></div></div>
    </section>

    <form class="filters" method="get" id="filters">
        <input type="hidden" name="sort" value="<%= h(sort) %>">
        <input name="q" value="<%= h(query) %>" placeholder="Search IP, country, client or node ID" aria-label="Search nodes">
        <select name="type" aria-label="Filter by node type">
            <option value="">All node types</option>
            <% for (NodeType type : nodeTypes) { %>
                <option value="<%= h(type.name()) %>" <%= type.name().equalsIgnoreCase(selectedType) ? "selected" : "" %>><%= h(type.name()) %></option>
            <% } %>
        </select>
        <select name="size" aria-label="Rows per page">
            <% for (int size : new int[]{10, 25, 50, 100}) { %>
                <option value="<%= size %>" <%= size == pageSize ? "selected" : "" %>><%= size %> rows</option>
            <% } %>
        </select>
        <button type="submit">Apply filter</button>
    </form>

    <section class="table-wrap">
        <div class="table-head"><span><strong>Node inventory</strong> · <%= "seen_desc".equals(sort) ? "newest first" : "seen_asc".equals(sort) ? "oldest first" : "most detailed first" %> <span class="loading-hint" role="status">Refreshing results…</span></span><span><%= request.getAttribute("resultFrom") %>–<%= request.getAttribute("resultTo") %> of <%= totalResults %></span></div>
        <% if (nodes.isEmpty()) { %>
            <div class="empty">No matching nodes yet. Use <strong>Start scan</strong> to begin discovery.</div>
        <% } else { %>
        <table>
            <thead><tr><th>Discovery endpoint</th><th>Node ID</th><th>Type</th><th>Client</th><th>Services</th><th>Details</th><th>P2P TCP connect</th><th aria-sort="<%= "seen_desc".equals(sort) ? "descending" : "seen_asc".equals(sort) ? "ascending" : "none" %>"><a class="sort-link <%= sort.startsWith("seen_") ? "active" : "" %>" href="<%= pageUrl(query, selectedType, pageSize, 1, "seen_asc".equals(sort) ? "seen_desc" : "seen_asc") %>" aria-label="Sort by Last Seen, <%= "seen_asc".equals(sort) ? "newest first" : "oldest first" %>">Last seen <span class="sort-arrow" aria-hidden="true"><%= "seen_desc".equals(sort) ? "↓" : "seen_asc".equals(sort) ? "↑" : "↕" %></span></a></th><th></th></tr></thead>
            <tbody>
            <% for (NodeRecord node : nodes) { %>
                <tr>
                    <td class="mono"><%= h(node.getIp()) %>:<%= node.getUdpPort() %></td>
                    <td class="mono muted" title="<%= h(node.getNodeId()) %>"><%= shortId(node.getNodeId()) %></td>
                    <td><span class="badge"><%= h(node.getNodeType()) %></span></td>
                    <td class="client-cell" title="<%= h(node.getClientVersion()) %>"><%= h(node.getClientVersion()) %></td>
                    <td>
                        <span class="service <%= node.isRpcAvailable() ? "good" : "muted" %>"><span class="dot"></span>RPC</span>
                        <span class="service <%= node.isBeaconAvailable() ? "good" : "muted" %>"><span class="dot"></span>Beacon</span>
                    </td>
                    <td><span class="badge"><%= DashboardServlet.detailScore(node) %>/18</span></td>
                    <td class="<%= node.getP2pConnectMs() == null ? "muted" : "" %>"><%= node.getP2pConnectMs() == null ? "—" : node.getP2pConnectMs() + " ms" %></td>
                    <td class="muted" title="<%= h(node.getLastSeen()) %>"><%= node.getLastSeen() == null ? "—" : timeFormat.format(node.getLastSeen()) %></td>
                    <td class="actions"><a class="button secondary small inspect-link" target="_blank" rel="noopener"
                           href="/node?key=<%= URLEncoder.encode(node.getKey(), StandardCharsets.UTF_8) %>">Inspect</a></td>
                </tr>
            <% } %>
            </tbody>
        </table>
        <% } %>
        <div class="table-skeleton" aria-hidden="true"><% for (int i = 0; i < Math.min(pageSize, 10); i++) { %><div class="skeleton-row"><% for (int j = 0; j < 9; j++) { %><span class="skeleton"></span><% } %></div><% } %></div>
        <div class="pagination">
            <span>Page <%= currentPage %> of <%= totalPages %></span>
            <div class="page-links">
                <a class="page-link <%= currentPage <= 1 ? "disabled" : "" %>" href="<%= pageUrl(query, selectedType, pageSize, currentPage - 1, sort) %>">Previous</a>
                <a class="page-link <%= currentPage >= totalPages ? "disabled" : "" %>" href="<%= pageUrl(query, selectedType, pageSize, currentPage + 1, sort) %>">Next</a>
            </div>
        </div>
    </section>
</main>
<script>
const filterForm = document.getElementById('filters');
function pending() {
    document.body.classList.add('pending');
    document.querySelector('.table-wrap').setAttribute('aria-busy', 'true');
    filterForm.querySelectorAll('input,select,button').forEach(control => { control.disabled = true; });
    document.querySelectorAll('.page-link,.sort-link').forEach(link => link.setAttribute('aria-disabled', 'true'));
}
filterForm.addEventListener('submit', event => {
    event.preventDefault();
    const params = new URLSearchParams(new FormData(filterForm));
    pending();
    requestAnimationFrame(() => location.assign('/?' + params));
});
document.querySelectorAll('.page-link:not(.disabled),.sort-link').forEach(link => {
    link.addEventListener('click', event => {
        if (document.body.classList.contains('pending')) { event.preventDefault(); return; }
        event.preventDefault(); pending();
        requestAnimationFrame(() => location.assign(link.href));
    });
});
document.querySelectorAll('.action-form').forEach(form => form.addEventListener('submit', () => {
    const button = form.querySelector('button');
    button.dataset.originalLabel = button.textContent;
    button.disabled = true;
    button.textContent = 'Working…';
}));
document.querySelectorAll('.inspect-link').forEach(link => link.addEventListener('click', () => {
    link.textContent = 'Opening…'; link.setAttribute('aria-disabled', 'true');
    link.style.pointerEvents = 'none';
    setTimeout(() => { link.textContent = 'Inspect'; link.removeAttribute('aria-disabled'); link.style.pointerEvents = ''; }, 2500);
}));
window.addEventListener('pageshow', () => {
    document.body.classList.remove('pending');
    document.querySelector('.table-wrap').removeAttribute('aria-busy');
    filterForm.querySelectorAll('input,select,button').forEach(control => { control.disabled = false; });
    document.querySelectorAll('.page-link,.sort-link').forEach(link => link.removeAttribute('aria-disabled'));
    document.querySelectorAll('.action-form button').forEach(button => {
        button.disabled = false;
        if (button.dataset.originalLabel) button.textContent = button.dataset.originalLabel;
    });
});
setInterval(() => {
    if (document.hidden || document.body.classList.contains('pending') || filterForm.contains(document.activeElement)) return;
    pending(); setTimeout(() => location.reload(), 80);
}, 30000);
</script>
</body>
</html>
