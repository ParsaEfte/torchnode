<%@ page contentType="text/html;charset=UTF-8" language="java" %>
<%@ page import="io.github.gavinruff007.torchnode.analysis.NetworkAnalytics" %>
<%!
 private String h(Object value) { return value==null?"":value.toString().replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&#39;"); }
%>
<% NetworkAnalytics.Report report=(NetworkAnalytics.Report)request.getAttribute("analytics"); %>
<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Network Measurement Report · TorchNode</title><style>
:root{color-scheme:dark}*{box-sizing:border-box}body{margin:0;background:#080b12;color:#eef3fb;font:14px/1.5 system-ui,sans-serif}.shell{max-width:1080px;margin:auto;padding:32px 20px 70px}a{color:#42d9d0}h1{font-size:clamp(28px,4vw,40px);line-height:1.15}h2{font-size:18px;margin:0 0 12px}h3{font-size:15px;margin:0 0 10px}.meta,.method,.metric{background:#111722;border:1px solid #263044;border-radius:12px;padding:18px;margin:14px 0}.nav{display:flex;gap:16px;flex-wrap:wrap}.nav button,.nav a{background:#142a31;border:1px solid #356772;color:#e8faf8;border-radius:6px;padding:8px 12px;font:inherit;text-decoration:none;cursor:pointer}.grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(min(100%,320px),1fr));gap:14px}.metric{margin:0;min-width:0}.metric p,.meta p{margin:8px 0;overflow-wrap:anywhere}table{border-collapse:collapse;width:100%;margin-top:12px}th,td{padding:6px 4px;border-top:1px solid #354255;text-align:left;overflow-wrap:anywhere}td:last-child,th:last-child{text-align:right;font-variant-numeric:tabular-nums}.muted{color:#bac5d2}.overlap{font-size:12px}a:focus-visible,button:focus-visible{outline:3px solid #f4bd62;outline-offset:2px}
@media print{:root{color-scheme:light}body{background:#fff;color:#111;font-size:10pt}.shell{max-width:none;padding:0}.nav{display:none}.meta,.method,.metric{background:#fff;color:#111;border-color:#999;box-shadow:none;break-inside:avoid}.grid{display:block}.metric{margin:8mm 0}.metric.long{break-inside:auto}h1,h2,h3{break-after:avoid}tr{break-inside:avoid}thead{display:table-header-group}.muted,.overlap{color:#333}th,td{border-color:#aaa}a{color:#111;text-decoration:none}@page{margin:16mm}}
</style></head><body><main class="shell">
<nav class="nav" aria-label="Report actions"><a href="/">Dashboard</a><a href="/analytics">Analytics</a><button type="button" onclick="window.print()">Print or Save as PDF</button></nav>
<header><p>TORCHNODE OBSERVATORY / RESEARCH REPORT</p><h1>Network Measurement Summary</h1></header>
<section class="meta" aria-labelledby="scope-heading"><h2 id="scope-heading">Measurement scope</h2>
<p>Generated at <strong><%=h(report.generatedAt())%></strong> UTC. Mode: <strong><%=h(report.scope().mode())%></strong>.</p>
<p>Requested window, UTC [start, end): <strong><%=h(report.scope().startInclusive())%></strong> to <strong><%=h(report.scope().endExclusive())%></strong>.</p>
<p><%=h(report.populationWarning())%></p>
<p>Untimed legacy runs excluded from run-time metrics: <%=report.excludedUntimedRuns()%>. Untimed endpoint attempts: <%=report.excludedUntimedEndpointAttempts()%>. RPC responses lacking response time excluded from client evidence: <%=report.excludedUntimedRpcResponses()%>.</p></section>
<form class="nav" action="/reports/network" method="get"><label>Start UTC <input name="start" value="<%=h(report.scope().startInclusive())%>"></label><label>End UTC <input name="end" value="<%=h(report.scope().endExclusive())%>"></label><button>Generate bounded report</button><a href="/reports/network?mode=all">All available evidence</a></form>
<section aria-labelledby="results-heading"><h2 id="results-heading">Measured evidence</h2><p class="muted">Each table names its counting unit and denominator. Buckets may overlap; use each metric's rule to interpret its counts.</p><div class="grid">
<% for(NetworkAnalytics.Metric metric:report.metrics()) { %><article class="metric<%=metric.buckets().size()>20?" long":""%>"><h3><%=h(metric.id().replace('-', ' '))%></h3>
<p><strong>Unit:</strong> <%=h(metric.countingUnit())%><br><strong>Denominator:</strong> <%=metric.denominator()%> <%=h(metric.denominatorMeaning())%><br><strong>Unknown:</strong> <%=metric.unknown()%></p>
<table><thead><tr><th scope="col">Evidence bucket</th><th scope="col">Count</th></tr></thead><tbody><% for(NetworkAnalytics.Bucket bucket:metric.buckets()) { %><tr><td><%=h(bucket.label())%></td><td><%=bucket.count()%></td></tr><% } %></tbody></table>
<p class="overlap"><%=h(metric.evidenceRule())%> <%=h(metric.bucketSemantics())%></p></article><% } %>
</div></section>
<section class="method" aria-labelledby="method-heading"><h2 id="method-heading">Methodology and limits</h2>
<p>TorchNode measures evidence from this observer's vantage. Observed cryptographic identities are not the Ethereum population. Endpoints are evidence associated with identities, not identities themselves. Repeated observations count as repeated receipts, not continuous presence. A gap does not establish disappearance.</p>
<p>Geography and ASN results apply to addresses under a named dataset and lookup time; they do not prove a node's physical location or hosting. Client distribution depends on usable source evidence, and unknowns remain explicit. A failed RPC or Beacon candidate does not establish service absence. IPv6 observed does not establish IPv6 reachability. ChangeEvents are derived comparisons of compatible source observations, not timestamps of physical change.</p>
<p>This report is generated from currently stored evidence. Re-running the same window after import or backfill may yield different counts. No durable report snapshot or observer identity is persisted. Measurement and derivation rules come from the installed TorchNode version; ChangeEvent rows carry their derivation version.</p>
<p>Safety bounds: bounded windows are at most 31 days; every scope has a 250,000 timestamp-eligible evidence-row limit. Oversized requests fail explicitly.</p></section>
</main></body></html>
