<%@ page contentType="text/html;charset=UTF-8" language="java" %>
<%@ page import="io.github.gavinruff007.torchnode.analysis.NetworkAnalytics" %>
<%!
 private String h(Object value) { return value==null?"":value.toString().replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;"); }
%>
<% NetworkAnalytics.Report report=(NetworkAnalytics.Report)request.getAttribute("analytics"); %>
<% NetworkAnalytics.Snapshot snapshot=(NetworkAnalytics.Snapshot)request.getAttribute("analyticsSnapshot"); %>
<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Network Analytics · TorchNode</title><style>
body{background:#080b12;color:#eef3fb;font:14px/1.5 system-ui,sans-serif;margin:0}.shell{max-width:1080px;margin:auto;padding:32px 20px 70px}
a{color:#42d9d0}h1{font-size:32px}p,small{color:#aeb9c9}.grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(300px,1fr));gap:14px}
article{background:#111722;border:1px solid #263044;border-radius:12px;padding:18px}h2{font-size:17px;margin:0 0 10px}table{width:100%;border-collapse:collapse}
td{padding:5px 0;border-top:1px solid #263044;overflow-wrap:anywhere}td:last-child{text-align:right;font-variant-numeric:tabular-nums}code{color:#42d9d0}
</style></head><body><main class="shell"><a href="/">← Dashboard</a><h1>Network Analytics</h1>
<p><%=h(report.populationWarning())%></p><p>Scope: <%=h(report.scope().mode())%>. Measurement window (UTC, start inclusive; end exclusive): <code><%=h(report.scope().startInclusive())%></code> → <code><%=h(report.scope().endExclusive())%></code></p>
<p><a href="/analytics?mode=all">All available compatible evidence</a>. Every scope has a 250,000 timestamp-eligible evidence-row safety limit; a scope beyond it returns an explicit error. Narrow the window to measure a larger retained database.</p>
<p>Separate latest projection snapshot: <%=snapshot.distinctProjectionIdentities()%> identities across <%=snapshot.latestProjectionRows()%> rows. This is current latest evidence, not the historical window below.</p>
<form action="/analytics" method="get"><label>Start UTC <input name="start" value="<%=h(report.scope().startInclusive())%>" size="32"></label>
<label>End UTC <input name="end" value="<%=h(report.scope().endExclusive())%>" size="32"></label><button>Measure</button></form>
<p>Generated <%=h(report.generatedAt())%>. Untimed legacy runs excluded from run-time metrics: <%=report.excludedUntimedRuns()%>; untimed endpoint attempts: <%=report.excludedUntimedEndpointAttempts()%>; RPC responses lacking a response timestamp excluded from client evidence: <%=report.excludedUntimedRpcResponses()%>. Unknown is insufficient evidence, not a negative result.</p>
<div class="grid"><% for(NetworkAnalytics.Metric m:report.metrics()) { %><article><h2><%=h(m.id())%></h2>
<small>Unit: <%=h(m.countingUnit())%> · Denominator: <%=m.denominator()%> (<%=h(m.denominatorMeaning())%>) · Unknown: <%=m.unknown()%></small>
<table><% for(NetworkAnalytics.Bucket b:m.buckets()) { %><tr><td><%=h(b.label())%></td><td><%=b.count()%></td></tr><% } %></table>
<p><%=h(m.evidenceRule())%> <%=h(m.bucketSemantics())%></p></article><% } %></div></main></body></html>
