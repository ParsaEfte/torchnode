<%@ page contentType="text/html;charset=UTF-8" language="java" %>
<%@ page import="io.github.gavinruff007.torchnode.model.NodeRecord" %>
<%!
    private String h(Object value) {
        if (value == null) return "Unavailable";
        return value.toString().replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }
%>
<%
    NodeRecord node = (NodeRecord) request.getAttribute("node");
    String inspectionId = (String) request.getAttribute("inspectionId");
%>
<!doctype html>
<html lang="en">
<head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width,initial-scale=1">
    <title><%= h(node.getIp()) %> · Node inspection</title>
    <style>
        :root{color-scheme:dark;--bg:#080b12;--panel:#111722;--line:#263044;--muted:#8792a7;--text:#eef3fb;--cyan:#42d9d0;--green:#74e39a;--red:#ff7e8c;--amber:#f4bd62}
        *{box-sizing:border-box} body{margin:0;min-height:100vh;background:radial-gradient(circle at 85% -10%,#123c49 0,transparent 30%),var(--bg);color:var(--text);font:14px/1.5 Inter,ui-sans-serif,system-ui,sans-serif}
        .shell{width:min(1280px,calc(100% - 40px));margin:auto;padding:32px 0 60px}.top{display:flex;justify-content:space-between;gap:20px;align-items:start;margin-bottom:20px}
        .eyebrow,.source{color:var(--cyan);font-size:10px;font-weight:800;letter-spacing:.14em;text-transform:uppercase}h1{font:700 clamp(27px,4vw,44px)/1.05 ui-monospace,SFMono-Regular,Menlo,monospace;margin:5px 0 10px;letter-spacing:-.05em}
        .status{display:flex;align-items:center;gap:9px;color:var(--muted)}.dot{width:8px;height:8px;border-radius:50%;background:currentColor}.back{color:var(--muted);text-decoration:none;border:1px solid var(--line);padding:8px 11px;border-radius:8px}
        .summary,.card{border:1px solid var(--line);background:#111722d9;backdrop-filter:blur(12px);border-radius:14px}.summary{padding:18px;display:flex;flex-wrap:wrap;gap:22px;margin-bottom:14px}.metric{min-width:125px}.label{color:var(--muted);font-size:10px;letter-spacing:.1em;text-transform:uppercase}.metric strong{display:block;margin-top:3px;font-size:15px}
        .badges{display:flex;gap:8px;flex-wrap:wrap;margin:0 0 22px}.badge,.source,.state{display:inline-flex;align-items:center;border:1px solid #334057;border-radius:999px;padding:3px 8px;color:var(--muted)}.badge.confirmed,.pass{color:var(--green);border-color:#2d6541;background:#11291b}.failed,.timeout{color:var(--red);border-color:#66323b;background:#2b171d}.checking{color:var(--cyan)}
        .grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:14px}.card{padding:18px;min-width:0}.wide{grid-column:1/-1}.card h2{font-size:15px;margin:0 0 15px}.rows{display:grid;gap:0}.row{display:grid;grid-template-columns:minmax(120px,.65fr) minmax(0,1.5fr) auto;gap:12px;align-items:start;padding:10px 0;border-top:1px solid #20293a}.row:first-child{border-top:0}.value{min-width:0;overflow-wrap:anywhere}.muted{color:var(--muted)}.mono{font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:12px}.copy{border:1px solid var(--line);background:#151d2a;color:var(--muted);border-radius:6px;padding:3px 7px;cursor:pointer}
        .diagnostic{display:grid;grid-template-columns:minmax(140px,1fr) auto minmax(100px,1.4fr);gap:12px;padding:11px 0;border-top:1px solid #20293a}.diagnostic:first-child{border-top:0}.state{font-size:10px;font-weight:800;letter-spacing:.08em}.state.warning{color:var(--amber);border-color:#5c4b2c}.reason{color:var(--muted);font-size:12px}.timeline{position:relative;margin-left:7px;padding-left:20px;border-left:1px solid var(--line)}.event{position:relative;padding:0 0 16px}.event:before{content:"";position:absolute;left:-25px;top:6px;width:8px;height:8px;border-radius:50%;background:var(--cyan)}.event time{color:var(--muted);font:11px ui-monospace,monospace;margin-right:10px}.notice{padding:13px;border:1px solid #5c4b2c;background:#261f13;color:var(--amber);border-radius:9px;margin-bottom:14px}
        .summary{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:15px}.metric{min-width:0}.metric strong{overflow-wrap:anywhere}
        .grid{align-items:start}.rows{grid-template-columns:repeat(2,minmax(0,1fr));gap:9px;align-content:start}
        #identity,#stack,#network-verification{grid-template-columns:repeat(3,minmax(0,1fr))}
        .row{display:flex;flex-direction:column;gap:6px;min-width:0;min-height:86px;padding:11px 12px;border:1px solid #202d40;border-radius:9px;background:#0d131e}
        .row:first-child{border-top:1px solid #202d40}.row .muted{font-size:11px;letter-spacing:.02em}.row .value{font-size:13px;font-weight:600;line-height:1.45;overflow:hidden;text-overflow:ellipsis}
        .row.long{grid-column:span 2}.row.expanded{grid-column:1/-1}.row.expanded .value{overflow-wrap:anywhere;white-space:normal}
        .row-tools{display:flex;align-items:center;gap:6px;flex-wrap:wrap;margin-top:auto}.source{letter-spacing:.06em;font-size:9px}.copy{font-size:10px;line-height:1.4}
        .state.not_tested,.state.unavailable,.state.observed{color:var(--muted);background:#17202c}.state.verified,.state.match,.state.pass{color:var(--green);border-color:#2d6541;background:#11291b}.state.failed,.state.timeout,.state.mismatch{color:var(--red);border-color:#66323b;background:#2b171d}.state.checking{color:var(--cyan);border-color:#36616c;background:#10262c}
        #diagnostics{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:9px}.diagnostic{display:flex;flex-wrap:wrap;align-content:start;gap:6px 10px;min-width:0;min-height:82px;padding:11px 12px;border:1px solid #202d40;border-radius:9px;background:#0d131e}.diagnostic:first-child{border-top:1px solid #202d40}.diagnostic strong{flex:1;font-size:12px}.diagnostic .reason{flex-basis:100%;overflow-wrap:anywhere}
        .p2p-pipeline{display:flex;flex-wrap:wrap;align-items:center;gap:7px;margin-bottom:13px}.p2p-step{display:inline-flex;align-items:center;gap:6px;padding:5px 8px;border:1px solid var(--line);border-radius:7px;font-size:11px}.p2p-step:not(:last-child):after{content:'→';color:var(--muted);margin-left:8px}.p2p-step .state{font-size:9px;padding:2px 5px}
        .row .reason{overflow-wrap:anywhere}.skeleton-tile{min-height:86px;padding:13px;border:1px solid #202d40;border-radius:9px;background:#0d131e}.skeleton-line{height:11px;margin-bottom:11px;border-radius:5px;background:linear-gradient(90deg,#1d2837 20%,#2b3b4b 45%,#1d2837 70%);background-size:220% 100%;animation:shimmer 1.5s linear infinite}.skeleton-line:last-child{width:65%;margin-bottom:0;height:17px}.skeleton-tile:nth-child(3n) .skeleton-line:last-child{width:82%}@keyframes shimmer{to{background-position-x:-220%}}
        .metric strong.pending-value{display:block;width:min(100%,140px);height:18px;margin-top:8px;color:transparent!important;border-radius:5px;background:linear-gradient(90deg,#1d2837 20%,#2b3b4b 45%,#1d2837 70%);background-size:220% 100%;animation:shimmer 1.5s linear infinite}
        @media(prefers-reduced-motion:reduce){.skeleton-line,.metric strong.pending-value{animation:none}}
        @media(max-width:1050px){.summary{grid-template-columns:repeat(3,minmax(0,1fr))}#identity,#stack,#network-verification,#diagnostics{grid-template-columns:repeat(2,minmax(0,1fr))}}
        @media(max-width:760px){.shell{width:calc(100% - 24px);padding-top:22px}.grid{grid-template-columns:1fr}.wide{grid-column:auto}.top{flex-direction:column}.row{grid-template-columns:105px minmax(0,1fr)}.row .copy{grid-column:2}.diagnostic{grid-template-columns:1fr auto}.diagnostic .reason{grid-column:1/-1}}
        @media(max-width:520px){.summary{grid-template-columns:repeat(2,minmax(0,1fr))}.rows,#identity,#stack,#network-verification,#diagnostics{grid-template-columns:1fr}.row.long{grid-column:auto}.p2p-pipeline{flex-direction:column;align-items:stretch}.p2p-step:not(:last-child):after{content:'↓';margin-left:auto}}
    </style>
</head>
<body>
<main class="shell">
    <div class="top">
        <div><div class="eyebrow">Deep node inspection</div><h1><%= h(node.getIp()) %></h1><div id="connection" class="status checking"><span class="dot"></span>Inspecting from scanner…</div></div>
        <a class="back" href="/">← Observatory</a>
    </div>
    <div id="warning" class="notice" hidden></div>
    <section class="summary">
        <div class="metric"><div class="label">Last known node type</div><strong id="summary-type"><%= h(node.getNodeType()) %></strong></div>
        <div class="metric"><div class="label">Detected client</div><strong id="summary-client"><%= h(node.getClientVersion()) %></strong></div>
        <div class="metric"><div class="label">Ethereum network</div><strong id="summary-network" class="pending-value">Checking…</strong></div>
        <div class="metric"><div class="label">P2P TCP</div><strong id="summary-p2p" class="pending-value">Checking…</strong></div>
        <div class="metric"><div class="label">JSON-RPC</div><strong id="summary-rpc" class="pending-value">Checking…</strong></div>
        <div class="metric"><div class="label">Beacon API</div><strong id="summary-beacon" class="pending-value">Checking…</strong></div>
        <div class="metric"><div class="label">Last seen</div><strong id="summary-seen">Unavailable</strong></div>
        <div class="metric"><div class="label">Inspection</div><strong id="summary-inspected" class="pending-value">Starting…</strong></div>
    </section>
    <div class="badges"><span id="badge-discovery" class="badge confirmed">● Discovery</span><span id="badge-p2p" class="badge">● P2P TCP</span><span id="badge-rlpx" class="badge">● RLPx</span><span id="badge-eth" class="badge">● ETH protocol</span><span id="badge-rpc" class="badge">● RPC</span><span id="badge-beacon" class="badge">● Beacon</span></div>

    <div class="grid">
        <section id="stack-card" class="card wide" hidden><h2>Ethereum Node Stack</h2><div id="stack" class="rows"></div></section>
        <section class="card wide"><h2>Node identity</h2><div id="identity" class="rows"></div></section>
        <section class="card"><h2>Client information</h2><div id="client" class="rows"><div class="muted">Waiting for client identification…</div></div></section>
        <section class="card"><h2>Ethereum P2P</h2><div id="p2p" class="rows"></div></section>
        <section class="card wide"><h2>Network Verification</h2><div id="network-verification" class="rows"></div></section>
        <section class="card"><h2>JSON-RPC</h2><div id="rpc" class="rows"><div class="muted">Checking supported RPC endpoints…</div></div></section>
        <section class="card"><h2>Beacon API</h2><div id="beacon" class="rows"><div class="muted">Checking supported Beacon endpoints…</div></div></section>
        <section class="card"><h2>Inspection timeline</h2><div id="timeline" class="timeline"></div></section>
        <section class="card wide"><h2>Connectivity & diagnostics</h2><div id="p2p-pipeline" class="p2p-pipeline"></div><div id="diagnostics"></div></section>
    </div>
</main>
<script>
const inspectionId = '<%= h(inspectionId) %>';
const el = id => document.getElementById(id);
const available = value => value !== null && value !== undefined && value !== '';
const show = value => available(value) ? String(value) : 'Unavailable';
const yesNo = value => value === true ? 'Yes' : value === false ? 'No' : 'Unavailable';
const time = value => available(value) ? new Date(value).toLocaleString() : 'Unavailable';
const expandedRows = new Set();
function skeleton(container, count) {
    container.replaceChildren();
    for (let i = 0; i < count; i++) {
        const tile = document.createElement('div'); tile.className = 'skeleton-tile';
        tile.innerHTML = '<div class="skeleton-line"></div><div class="skeleton-line"></div>';
        container.append(tile);
    }
}
const initialTiles = {identity:13, client:5, p2p:10, 'network-verification':3, rpc:4, beacon:4, diagnostics:7, timeline:2};
Object.entries(initialTiles).forEach(([id, count]) => skeleton(el(id), count));

function row(container, label, value, source, copy = false, shorten = false) {
    const item = document.createElement('div'); item.className = 'row';
    const key = document.createElement('div'); key.className = 'muted'; key.textContent = label;
    const val = document.createElement('div'); val.className = 'value';
    const full = available(value) ? String(value) : null;
    const truncate = full && (shorten && full.length > 26 || full.length > 56);
    const rowId = container.id + ':' + label;
    const expanded = expandedRows.has(rowId);
    if (truncate) item.classList.add('long');
    if (expanded) item.classList.add('expanded');
    val.textContent = truncate && !expanded ? full.slice(0, 22) + '…' + full.slice(-12) : show(value);
    if (truncate) val.title = full;
    item.append(key, val);
    const tools = document.createElement('div'); tools.className = 'row-tools';
    if (source) { const tag = document.createElement('span'); tag.className = 'source'; tag.textContent = source; tools.append(tag); }
    if (truncate) {
        const button = document.createElement('button'); button.className = 'copy'; button.textContent = expanded ? 'Collapse' : 'View full';
        button.onclick = () => { const showFull = !expandedRows.has(rowId); if (showFull) expandedRows.add(rowId); else expandedRows.delete(rowId); val.textContent = showFull ? full : full.slice(0, 22) + '…' + full.slice(-12); button.textContent = showFull ? 'Collapse' : 'View full'; item.classList.toggle('expanded', showFull); };
        tools.append(button);
    }
    if (copy && available(value)) { const button = document.createElement('button'); button.className = 'copy'; button.textContent = 'Copy'; button.onclick = () => navigator.clipboard.writeText(String(value)); tools.append(button); }
    item.append(tools); container.append(item); return item;
}

function diagnosticByName(data, name) { return data.diagnostics.find(item => item.name === name); }
function setBadge(id, confirmed) { el(id).classList.toggle('confirmed', confirmed); }
function verificationRow(container, check) {
    const item = row(container, check.label, check.value, check.source,
        available(check.value) && String(check.value).length > 56);
    item.querySelector('.value').classList.add('mono');
    const status = document.createElement('span'); status.className = 'state ' + check.status.toLowerCase(); status.textContent = check.status.replace('_', ' ');
    item.querySelector('.row-tools').append(status);
    if (check.note) { const note = document.createElement('div'); note.className = 'reason'; note.textContent = check.note; item.append(note); }
}
function serviceTiming(diagnostic) {
    if (!diagnostic || diagnostic.state === 'CHECKING') return 'Checking…';
    if (diagnostic.state === 'PASS') return available(diagnostic.durationMs) ? diagnostic.durationMs + ' ms' : 'Reachable';
    return diagnostic.state === 'TIMEOUT' ? 'Timeout' : diagnostic.state.replace('_', ' ');
}

function render(data) {
    const node = data.node, tcp = diagnosticByName(data, 'P2P TCP');
    const rpcCheck = diagnosticByName(data, 'JSON-RPC'), beaconCheck = diagnosticByName(data, 'Beacon API');
    const connection = el('connection');
    const partial = tcp.state !== 'PASS' && (!!data.rpc || !!data.beacon);
    connection.className = 'status ' + (tcp.state === 'PASS' || partial ? 'pass' : tcp.state === 'CHECKING' ? 'checking' : 'failed');
    connection.innerHTML = '<span class="dot"></span>' + (tcp.state === 'PASS' ? 'P2P TCP reachable from scanner' : partial ? 'Partially reachable from scanner' : tcp.state === 'CHECKING' ? 'Inspecting from scanner…' : 'Unreachable from scanner');
    el('warning').hidden = tcp.state === 'PASS' || tcp.state === 'CHECKING';
    if (!el('warning').hidden) el('warning').textContent = partial
        ? 'The advertised P2P TCP endpoint was not reachable, but at least one supported API responded successfully.'
        : 'This node was previously discovered, but the current inspection could not establish a TCP connection. This does not prove the node is offline.';
    el('summary-type').textContent = show(node.nodeType);
    el('summary-client').textContent = data.client ? [data.client.name, data.client.version].filter(Boolean).join(' ') : 'Unavailable';
    el('summary-network').textContent = data.networkVerification.warning ? 'Conflicting reports' : show(data.networkVerification.network);
    el('summary-p2p').textContent = serviceTiming(tcp);
    el('summary-rpc').textContent = serviceTiming(rpcCheck);
    el('summary-beacon').textContent = serviceTiming(beaconCheck);
    el('summary-seen').textContent = time(node.lastSeen);
    el('summary-inspected').textContent = time(data.startedAt);
    el('summary-p2p').classList.toggle('pending-value', tcp.state === 'CHECKING');
    el('summary-rpc').classList.toggle('pending-value', rpcCheck.state === 'CHECKING');
    el('summary-beacon').classList.toggle('pending-value', beaconCheck.state === 'CHECKING');
    el('summary-network').classList.toggle('pending-value', !data.complete && !data.rpc && !data.beacon && !data.p2p);
    el('summary-inspected').classList.remove('pending-value');
    setBadge('badge-p2p', tcp.state === 'PASS'); setBadge('badge-rlpx', diagnosticByName(data, 'RLPx Auth').state === 'PASS');
    setBadge('badge-eth', diagnosticByName(data, 'ETH Status').state === 'PASS');
    setBadge('badge-rpc', !!data.rpc); setBadge('badge-beacon', !!data.beacon);

    el('stack-card').hidden = !data.nodeStack;
    if (data.nodeStack) {
        const stack = el('stack'); stack.replaceChildren();
        const execution = data.nodeStack.execution, consensus = data.nodeStack.consensus;
        row(stack, 'Execution', [execution.name, execution.version].filter(Boolean).join(' '), data.rpc ? 'RPC' : 'RLPx Hello');
        row(stack, 'Execution platform', [execution.platform, execution.runtime].filter(Boolean).join(' / '), 'Derived');
        row(stack, 'Consensus', [consensus.name, consensus.version].filter(Boolean).join(' '), 'Beacon');
        row(stack, 'Consensus platform', [consensus.platform, consensus.runtime].filter(Boolean).join(' / '), 'Derived');
        row(stack, 'Network', data.nodeStack.network, data.nodeStack.network ? 'Verified' : null);
    }

    const identity = el('identity'); identity.replaceChildren();
    const ethStatus = data.p2p && data.p2p.status;
    row(identity, 'Node ID', node.nodeId, 'Discovery', true, true); row(identity, 'ENR', null, null);
    row(identity, 'enode URL', node.enode, node.enode ? 'Derived' : null, true, true); row(identity, 'IP address', node.ip, 'Discovery', true);
    row(identity, 'Discovery UDP', node.discoveryEndpoint, 'Discovery', true);
    row(identity, 'P2P TCP', node.p2pEndpoint, 'Discovery', true);
    row(identity, 'Confirmed JSON-RPC', node.rpcEndpoint, data.rpc ? 'RPC' : null, true);
    row(identity, 'Confirmed Beacon API', node.beaconEndpoint, data.beacon ? 'Beacon' : null, true);
    row(identity, 'TCP port', node.tcpPort, 'Discovery'); row(identity, 'UDP port', node.udpPort, 'Discovery');
    row(identity, 'Discovery protocol', node.discovery, 'Derived'); row(identity, 'ENR sequence', null, null);
    row(identity, 'Fork ID', ethStatus ? ethStatus.forkHash + ' / ' + ethStatus.forkNext : null,
        ethStatus ? 'ETH Status' : null, !!ethStatus);

    const client = el('client'); client.replaceChildren();
    if (data.client) { row(client, data.client.kind, data.client.name, data.client.source); row(client, 'Version', data.client.version, 'Derived'); row(client, 'Platform', data.client.platform, 'Derived'); row(client, 'Runtime', data.client.runtime, 'Derived'); row(client, 'Raw client string', data.client.raw, data.client.source, true); }
    else if (!data.complete) skeleton(client, 4);
    else { const empty = document.createElement('div'); empty.className = 'muted'; empty.textContent = 'No client identity was returned.'; client.append(empty); }

    const p2p = el('p2p'); p2p.replaceChildren();
    const hello = data.p2p && data.p2p.hello, eth = data.p2p && data.p2p.status;
    row(p2p, 'Discovery', node.discovery, 'Discovery'); row(p2p, 'P2P TCP endpoint', node.p2pEndpoint, 'Discovery', true);
    row(p2p, 'RLPx Auth', diagnosticByName(data, 'RLPx Auth').state.replace('_', ' '), 'RLPx');
    row(p2p, 'RLPx Hello', diagnosticByName(data, 'RLPx Hello').state.replace('_', ' '), 'RLPx');
    row(p2p, 'ETH Status', diagnosticByName(data, 'ETH Status').state.replace('_', ' '), 'ETH');
    const trace = data.p2p && data.p2p.helloTrace;
    if (trace) {
        row(p2p, 'Local Hello sent', trace.localHelloSent ? 'Yes' : 'No', 'RLPx');
        row(p2p, 'Decoded devp2p frames', trace.framesDecoded, 'RLPx');
        if (available(trace.lastMessageCode)) row(p2p, 'Last message code', trace.lastMessageCode, 'devp2p');
        if (trace.disconnect) row(p2p, 'Peer Disconnect', trace.disconnect.code + ' / ' + trace.disconnect.name + ' — ' + trace.disconnect.description, 'devp2p');
    }
    if (hello) {
        row(p2p, 'Client ID', hello.clientId, 'RLPx Hello', true);
        row(p2p, 'devp2p version', hello.devp2pVersion, 'RLPx Hello');
        row(p2p, 'Advertised listen port', hello.listenPort, 'RLPx Hello');
        row(p2p, 'Capabilities', (hello.capabilities || []).map(cap => cap.name + '/' + cap.version).join(', '), 'RLPx Hello', true);
        row(p2p, 'Offered ETH versions', (hello.supportedEthVersions || []).join(', '), 'RLPx Hello');
        row(p2p, 'Negotiated ETH version', hello.negotiatedEthVersion || null, 'Derived');
        row(p2p, 'SNAP support', hello.snapSupport ? 'Advertised snap/1' : 'Not advertised', 'RLPx Hello');
    }
    if (eth) {
        row(p2p, 'P2P network ID', eth.networkId, 'ETH Status');
        row(p2p, 'Genesis hash', eth.genesisHash, 'ETH Status', true);
        row(p2p, 'Fork hash', eth.forkHash, 'ETH Status', true);
        row(p2p, 'Next fork', eth.forkNext, 'ETH Status');
        row(p2p, 'Earliest available block', eth.earliestBlock, 'ETH Status');
        row(p2p, 'Latest available block', eth.latestBlock, 'ETH Status');
        row(p2p, 'Latest available block hash', eth.latestBlockHash, 'ETH Status', true);
    }
    const statusTrace = data.p2p && data.p2p.statusTrace;
    if (statusTrace) {
        row(p2p, 'Local ETH Status sent', statusTrace.localStatusSent ? 'Yes' : 'No', 'RLPx');
        row(p2p, 'Remote ETH Status decoded', statusTrace.remoteStatusReceived ? 'Yes' : 'No', 'ETH Status');
        if (statusTrace.disconnect) row(p2p, 'Peer Disconnect during Status',
            statusTrace.disconnect.code + ' / ' + statusTrace.disconnect.name + ' — ' + statusTrace.disconnect.description, 'devp2p');
    }

    const verification = el('network-verification'); verification.replaceChildren();
    if (!data.complete && !data.rpc && !data.beacon && !data.p2p) skeleton(verification, 3);
    else data.networkVerification.rows.forEach(check => verificationRow(verification, check));

    const rpc = el('rpc'); rpc.replaceChildren();
    if (data.rpc) { row(rpc, 'Status', 'Reachable', 'RPC'); row(rpc, 'Confirmed endpoint', data.rpc.endpoint, 'RPC', true); row(rpc, 'Response time', available(data.rpc.responseMs) ? data.rpc.responseMs + ' ms' : null, 'RPC'); row(rpc, 'Chain ID', data.rpc.chainId, 'RPC'); row(rpc, 'Network ID', data.rpc.networkId, 'RPC'); row(rpc, 'Latest block', data.rpc.blockNumber, 'RPC'); row(rpc, 'Peer count', data.rpc.peerCount, 'RPC'); row(rpc, 'Syncing', yesNo(data.rpc.syncing), 'RPC'); Object.entries(data.rpc.methodStatus || {}).forEach(([name,state]) => row(rpc, name, state, 'RPC')); }
    else if (rpcCheck.state === 'CHECKING') skeleton(rpc, 4);
    else { row(rpc, 'Status', 'Not detected (' + rpcCheck.state.replace('_',' ') + ')', 'RPC'); if ((data.rpcProbeEndpoints || []).length) row(rpc, 'Candidate endpoints probed', data.rpcProbeEndpoints.join(', '), 'Scanner'); if (rpcCheck.reason) row(rpc, 'Reason', rpcCheck.reason, null); }

    const beacon = el('beacon'); beacon.replaceChildren();
    if (data.beacon) { row(beacon, 'Status', 'Reachable', 'Beacon'); row(beacon, 'Confirmed endpoint', node.beaconEndpoint, 'Beacon', true); row(beacon, 'Response time', available(data.beacon.responseMs) ? data.beacon.responseMs + ' ms' : null, 'Beacon'); row(beacon, 'Client', data.beacon.version, 'Beacon'); row(beacon, 'Head slot', data.beacon.headSlot, 'Beacon'); row(beacon, 'Sync distance', data.beacon.syncDistance, 'Beacon'); row(beacon, 'Syncing', yesNo(data.beacon.syncing), 'Beacon'); row(beacon, 'Optimistic', yesNo(data.beacon.optimistic), 'Beacon'); row(beacon, 'Execution offline', yesNo(data.beacon.executionOffline), 'Beacon'); row(beacon, 'Genesis time', data.beacon.genesisTime, 'Beacon'); row(beacon, 'Genesis validators root', data.beacon.genesisValidatorsRoot, 'Beacon', true, true); }
    else if (beaconCheck.state === 'CHECKING') skeleton(beacon, 4);
    else { row(beacon, 'Status', 'Not detected (' + beaconCheck.state.replace('_',' ') + ')', 'Beacon'); if ((data.beaconProbeEndpoints || []).length) row(beacon, 'Candidate endpoints probed', data.beaconProbeEndpoints.join(', '), 'Scanner'); if (beaconCheck.reason) row(beacon, 'Reason', beaconCheck.reason, null); }

    const diagnostics = el('diagnostics'); diagnostics.replaceChildren();
    const pipeline = el('p2p-pipeline'); pipeline.replaceChildren();
    ['Discovery', 'P2P TCP', 'RLPx Auth', 'RLPx Hello', 'ETH Status'].forEach(name => {
        const check = diagnosticByName(data, name), step = document.createElement('span');
        step.className = 'p2p-step'; step.textContent = name === 'RLPx Hello' ? 'Hello' : name;
        const state = document.createElement('span'); state.className = 'state ' + check.state.toLowerCase();
        state.textContent = check.state.replace('_', ' '); step.append(state); pipeline.append(step);
    });
    data.diagnostics.forEach(d => { const item = document.createElement('div'); item.className = 'diagnostic'; const name = document.createElement('strong'); name.textContent = d.name; const state = document.createElement('span'); state.className = 'state ' + d.state.toLowerCase(); state.textContent = d.state.replace('_',' '); const reason = document.createElement('div'); reason.className = 'reason'; reason.textContent = [d.durationMs !== null ? d.durationMs + ' ms' : null, d.reason, d.source ? '[' + d.source + ']' : null].filter(Boolean).join(' · '); item.append(name,state,reason); diagnostics.append(item); });
    const timeline = el('timeline'); timeline.replaceChildren(); data.timeline.forEach(event => { const item = document.createElement('div'); item.className = 'event'; const stamp = document.createElement('time'); stamp.textContent = new Date(event.timestamp).toLocaleTimeString(); const label = document.createElement('strong'); label.textContent = event.label; item.append(stamp,label); if (event.detail) { const detail = document.createElement('div'); detail.className = 'muted mono'; detail.textContent = event.detail; item.append(detail); } timeline.append(item); });
}

async function poll() {
    try { const response = await fetch('/inspection/status?id=' + encodeURIComponent(inspectionId), {cache:'no-store'}); if (!response.ok) throw new Error('Inspection status unavailable'); const data = await response.json(); render(data); if (!data.complete) setTimeout(poll, 750); }
    catch (error) { el('warning').hidden = false; el('warning').textContent = error.message; setTimeout(poll, 2000); }
}
poll();
</script>
</body>
</html>
