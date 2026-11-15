if (document.getElementById('visual-analytics')) {
/* Dashboard v2 renders one bounded NetworkAnalytics report. No peer identifiers leave this page. */
const root = document.getElementById('visual-analytics');
const scopeSelect = document.getElementById('measurement-scope');
const refreshButton = document.getElementById('refresh-measurement');
const status = document.getElementById('measurement-status');
let requestController;
let requestSequence = 0;

const make = (tag, className, text) => {
    const element = document.createElement(tag);
    if (className) element.className = className;
    if (text !== undefined) element.textContent = String(text);
    return element;
};
const metric = (report, id) => report.metrics.find(item => item.id === id);
const count = (item, label) => item?.buckets.find(bucket => bucket.label === label)?.count ?? 0;
const number = value => Number(value ?? 0).toLocaleString();
const section = (title, description) => {
    const article = make('section', 'viz-panel');
    article.append(make('h3', '', title), make('p', 'viz-note', description));
    root.append(article);
    return article;
};
const empty = (parent, message) => parent.append(make('p', 'viz-empty', message));
const note = (parent, message) => parent.append(make('p', 'viz-note', message));

function bars(parent, rows, options = {}) {
    if (!rows.length) { empty(parent, options.empty || 'No eligible evidence in this window.'); return; }
    const list = make('div', 'bar-list');
    const maximum = Math.max(1, ...rows.map(row => row.count));
    for (const row of rows) {
        const line = make('div', 'bar-row');
        const label = make('span', 'bar-label', row.label ?? 'UNKNOWN');
        label.title = row.title || row.label || 'UNKNOWN';
        line.tabIndex = 0;
        line.title = `${row.label ?? 'UNKNOWN'}: ${number(row.count)} recorded memberships`;
        line.setAttribute('aria-label', line.title);
        const track = make('span', 'bar-track');
        const fill = make('span', 'bar-fill');
        fill.style.width = `${Math.max(0, row.count / maximum * 100)}%`;
        track.append(fill);
        line.append(label, track, make('strong', 'bar-count', number(row.count)));
        list.append(line);
    }
    parent.append(list);
}

function overview(report) {
    const panel = section('Observation coverage', 'Distinct cryptographic identities and recorded occurrences in the selected historical window.');
    const grid = make('div', 'overview-grid');
    for (const [id, label, bucket, explanation] of [
        ['observed-identities', 'Observed identities', 'observed', 'Deduplicated cryptographic identities with timestamped discovery, ENR or inspection evidence.'],
        ['discovery-occurrences', 'Discovery observations', 'receipts', 'Repeated source receipts remain separate occurrences.'],
        ['inspected-identities', 'Inspected identities', 'inspected', 'Identity subset with inspection runs in this window.'],
        ['inspections', 'Inspection runs', 'runs', 'Distinct inspection occurrences, including repeated payloads.']
    ]) {
        const item = metric(report, id);
        const card = make('div', 'overview-card');
        card.append(make('span', 'overview-label', label), make('strong', 'overview-value', number(count(item, bucket))));
        const detail = make('small', '', `${explanation} Unit: ${item?.countingUnit || 'identities'}. Denominator: ${number(item?.denominator)}; unknown ${number(item?.unknown)}.`); detail.title = item?.evidenceRule || explanation;
        card.append(detail); grid.append(card);
    }
    for(const [id,label,bucket] of [['distinct-endpoints','IPv4 typed endpoints','IPV4'],['distinct-endpoints','IPv6 typed endpoints','IPV6'],['discovery-providers','discv4 identities','discv4'],['discovery-providers','discv5 identities','discv5'],['rpc-attempts','RPC responses','PASS'],['beacon-attempts','Beacon responses','PASS'],['p2p-tcp','TCP PASS','PASS'],['p2p-tcp','TCP FAILED','FAILED']]) {
        const item=metric(report,id),card=make('div','overview-card');
        card.append(make('span','overview-label',label),make('strong','overview-value',number(count(item,bucket))),make('small','',`${item?.countingUnit||'records'} · denominator ${number(item?.denominator)} · unknown ${number(item?.unknown)}`));grid.append(card);
    }
    panel.append(grid);
    const enr = metric(report, 'enr-trust');
    note(panel, `Trusted ENR: ${number(count(enr, 'trusted'))} identity memberships. Trust requires structural validity, valid signature and identity match; diagnostic ENRs may overlap by identity.`);
}

function datasetSuffix(label) {
    const split = label.lastIndexOf(':');
    return split < 0 ? {dataset: '', value: label} : {dataset: label.slice(0, split), value: label.slice(split + 1)};
}

async function geography(report, signal) {
    const country = metric(report, 'country-distribution-top-100');
    const countryStatus = metric(report, 'country-lookup-status');
    const asn = metric(report, 'asn-distribution-top-100');
    const panel = section('Approximate endpoint geography', 'Country context from offline enrichment of normalized addresses. One identity may have endpoints in several countries.');
    const found = country?.denominator ?? 0;
    const unavailable = (countryStatus?.buckets || []).filter(bucket => bucket.label.endsWith(':DATASET_UNAVAILABLE'))
        .reduce((sum, bucket) => sum + bucket.count, 0);
    const statuses = new Map();
    for (const bucket of countryStatus?.buckets || []) {
        const kind = datasetSuffix(bucket.label).value;
        statuses.set(kind, (statuses.get(kind) || 0) + bucket.count);
    }
    note(panel, `${number(found)} FOUND address/dataset/country memberships; ${number(unavailable)} DATASET_UNAVAILABLE. Other lookup statuses: ${[...statuses].filter(([kind]) => kind !== 'FOUND' && kind !== 'DATASET_UNAVAILABLE').map(([kind, n]) => `${kind} ${number(n)}`).join(' · ') || 'none'}. Status memberships are address/dataset scoped and may overlap over time.`);
    const mapFrame = make('div', 'world-map');
    panel.append(mapFrame);
    if (found === 0) {
        empty(mapFrame, unavailable ? 'No country map can be drawn: the offline GeoIP dataset was unavailable for recorded lookups.' :
            'No country-level enrichment was recorded in this window.');
    } else {
        try {
            const response = await fetch('/assets/world-countries.svg', {signal, cache: 'force-cache'});
            if (!response.ok) throw new Error('Map asset unavailable');
            const documentSvg = new DOMParser().parseFromString(await response.text(), 'image/svg+xml');
            const svg = document.importNode(documentSvg.documentElement, true);
            const byCode = new Map();
            for (const bucket of country.buckets) {
                const code = datasetSuffix(bucket.label).value;
                byCode.set(code, (byCode.get(code) || 0) + bucket.count);
            }
            const maximum = Math.max(1, ...byCode.values());
            const outlined = new Set();
            for (const shape of svg.querySelectorAll('[data-country]')) {
                const code = shape.getAttribute('data-country');
                outlined.add(code);
                const n = byCode.get(code) || 0;
                if (n) {
                    shape.style.fill = `hsl(175 65% ${Math.round(24 + 24 * Math.sqrt(n / maximum))}%)`;
                    shape.classList.add('observed-country');
                }
                const title = shape.querySelector('title');
                if (title) title.textContent = `${shape.getAttribute('data-name')}: ${number(n)} address/dataset/country memberships`;
            }
            mapFrame.append(svg);
            const notOutlined = [...byCode].filter(([code]) => !outlined.has(code));
            if (notOutlined.length) note(panel, `${number(notOutlined.reduce((sum, [, n]) => sum + n, 0))} category memberships lack a matching country outline; ranked evidence remains listed below.`);
        } catch (error) {
            if (error.name !== 'AbortError') empty(mapFrame, 'The local map outline could not be loaded. Ranked country evidence remains available below.');
        }
    }
    note(panel, `Brighter cyan means more recorded address/dataset/country memberships; gray means none in the bounded top-100 country categories. ${country?.evidenceRule || ''} No city or canonical node coordinates are inferred.`);
    const columns = make('div', 'viz-columns'); panel.append(columns);
    const countryList = make('div'); columns.append(countryList);
    countryList.append(make('h4', '', 'Country contexts'));
    bars(countryList, (country?.buckets || []).slice(0, 8).map(bucket => {
        const item = datasetSuffix(bucket.label);
        return {label: item.value, title: bucket.label, count: bucket.count};
    }), {empty: 'No FOUND country contexts.'});
    note(countryList, `Showing up to 8 of ${number(country?.buckets.length)} returned categories. ${country?.evidenceRule || 'No country data.'}`);
    const asnList = make('div'); columns.append(asnList);
    asnList.append(make('h4', '', 'ASN registration contexts'));
    bars(asnList, (asn?.buckets || []).slice(0, 8).map(bucket => {
        const item = datasetSuffix(bucket.label);
        return {label: `AS${item.value}`, title: bucket.label, count: bucket.count};
    }), {empty: 'No FOUND ASN contexts.'});
    note(asnList, `Showing up to 8 of ${number(asn?.buckets.length)} returned categories. ${asn?.evidenceRule || 'No ASN data.'} ASN does not establish hosting provider.`);
}

function clients(report) {
    const item = metric(report, 'execution-clients');
    const versions = metric(report, 'execution-versions');
    const panel = section('Execution-client evidence', 'Usable authenticated Hello or responding RPC client evidence; no canonical client is written to a node.');
    const classified = (item?.buckets || []).reduce((sum, bucket) => sum + bucket.count, 0);
    note(panel, `${number(classified)} classified or conflicting identities among ${number(item?.denominator)} observed identities; ${number(item?.unknown)} lack usable client evidence. Classification buckets are mutually exclusive; unknown is outside this distribution.`);
    bars(panel, item?.buckets || [], {empty: 'No usable client evidence in this window.'});
    const versionTitle = make('h4', '', 'Observed exact versions'); panel.append(versionTitle);
    bars(panel, (versions?.buckets || []).slice(0, 8), {empty: 'No parseable or raw version evidence.'});
    note(panel, `Showing up to 8 of ${number(versions?.buckets.length)} returned version categories. ${versions?.evidenceRule || ''} UNKNOWN_VERSION and conflicting observations remain evidence, not guesses.`);
}

function discoveryAndFamilies(report) {
    const panel = section('Discovery and address families', 'These are overlapping identity observations, not slices of a single population.');
    const columns = make('div', 'viz-columns'); panel.append(columns);
    const providers = make('div'); columns.append(providers);
    providers.append(make('h4', '', 'Discovery sources'));
    bars(providers, metric(report, 'discovery-providers')?.buckets || []);
    note(providers, `${number(count(metric(report, 'discv4-discv5-overlap'), 'both'))} identities observed by both discv4 and discv5. ${number(metric(report, 'discovery-providers')?.unknown)} observed identities lack a timestamped discovery source in this window.`);
    const family = make('div'); columns.append(family);
    family.append(make('h4', '', 'Observed address families'));
    bars(family, metric(report, 'address-families')?.buckets || []);
    note(family, `${number(count(metric(report, 'dual-family'), 'both'))} identities observed with both families; ${number(metric(report, 'address-families')?.unknown)} lack typed endpoint-family evidence. IPv6 advertisement is not reachability; ${number(count(metric(report, 'ipv6-tcp-pass'), 'IPv6 TCP PASS'))} identities had an IPv6 TCP PASS among ${number(metric(report, 'ipv6-tcp-pass')?.denominator)} attempted identities.`);
}

function protocol(report) {
    const panel = section('P2P inspection funnel', 'Each stage uses its own actual attempted endpoint-stage denominator. Downstream NOT_TESTED is not failure.');
    for (const [id, title] of [['p2p-tcp', 'TCP'], ['p2p-rlpx', 'RLPx Auth'],
        ['p2p-hello', 'devp2p Hello'], ['p2p-status', 'ETH Status']]) {
        const item = metric(report, id);
        const row = make('div', 'funnel-row');
        row.append(make('strong', '', title), make('span', '', `${number(item?.denominator)} attempted · ${number(count(item, 'PASS'))} PASS`));
        const states = (item?.buckets || []).filter(bucket => bucket.label !== 'PASS')
            .map(bucket => `${bucket.label}: ${number(bucket.count)}`).join(' · ');
        row.append(make('small', '', states || 'No other recorded outcomes'));
        panel.append(row);
    }
    note(panel, 'Hello-advertised ETH capabilities do not count as completed ETH Status. Observer restrictions remain separate where identifiable.');
}

function sidePaths(report) {
    const panel = section('Independent API candidate probes', 'Each attempt is a candidate endpoint probe. Unsuccessful candidates do not establish service absence.');
    const columns = make('div', 'viz-columns'); panel.append(columns);
    for (const [id, title] of [['rpc-attempts', 'JSON-RPC'], ['beacon-attempts', 'Beacon API']]) {
        const item = metric(report, id);
        const column = make('div'); columns.append(column);
        column.append(make('h4', '', title));
        note(column, `${number(item?.denominator)} candidate attempts · ${number(count(item, 'PASS'))} responses`);
        bars(column, item?.buckets || [], {empty: 'No candidate attempts in this window.'});
        const latency=metric(report,id.replace('-attempts','-response-latency'));
        column.append(make('h4','','First usable response duration'));
        bars(column,latency?.buckets||[],{empty:'No timestamped measured response durations.'});
        note(column,`${number(latency?.denominator)} responding samples; ${number(latency?.unknown)} lack a valid duration. Stored first usable method responseMs; no per-method timing or service SLA is inferred.`);
    }
}

function changes(report) {
    const item = metric(report, 'change-activity');
    const panel = section('Observed evidence changes', 'Derived comparisons under recorded rule versions; event count is not an instability score.');
    bars(panel, item?.buckets || [], {empty: 'No derived ChangeEvents in this window.'});
    note(panel, `${number(item?.denominator)} events. Labels include derivation rule version and existing event taxonomy.`);
}

async function load() {
    if (requestController) requestController.abort();
    requestController = new AbortController();
    const sequence = ++requestSequence;
    const end = endInput.value ? new Date(endInput.value + 'Z') : new Date();
    const days = Number(scopeSelect.value);
    const start = startInput.value ? new Date(startInput.value + 'Z') : new Date(end.getTime() - days * 86400000);
    if (!Number.isFinite(start.getTime()) || !Number.isFinite(end.getTime()) || start >= end || end - start > 31 * 86400000) { status.textContent = 'Choose a positive UTC window of at most 31 days.'; return; }
    const params = new URLSearchParams({start: start.toISOString(), end: end.toISOString()});
    status.textContent = `Measuring ${days === 1 ? '24 hours' : `${days} days`} of recorded observations…`;
    root.replaceChildren(); refreshButton.disabled = true;
    try {
        const response = await fetch(`/analytics/compare.json?${params}`, {signal: requestController.signal, cache: 'no-store'});
        if (!response.ok) throw new Error(response.status === 413 ?
            'This scope exceeds the explicit analytics safety limit. Select a shorter window.' :
            `Analytics request failed (${response.status}).`);
        const comparison = await response.json();
        const report = comparison.current;
        if (sequence !== requestSequence) return;
        status.textContent = `Historical window: ${report.scope.startInclusive} to ${report.scope.endExclusive} UTC [start, end). Generated ${report.generatedAt}.`;
        const page = root.dataset.page || 'overview';
        overview(report);
        exportJson.href = `/analytics.json?${params}`;
        exportCsv.href = `/api/v1/exports/analytics.csv?${params}`;
        reportLink.href = `/reports/network?${params}&compare=true`;
        if (['overview','geography'].includes(page)) {
        await geography(report, requestController.signal);
        if (sequence !== requestSequence) return;
        }
        if (['overview','clients'].includes(page)) clients(report);
        if (['overview','discovery'].includes(page)) discoveryAndFamilies(report);
        if (['overview','p2p'].includes(page)) protocol(report);
        if (['overview','apis'].includes(page)) sidePaths(report);
        if (['overview','events'].includes(page)) changes(report);
        advanced(report, comparison.baseline, page);
        evidenceTable(report, page);
        await identityDrilldown(params, page, requestController.signal);
        note(root, 'Among identities observed by TorchNode under this scope. This observer-local sample is not the Ethereum population. Table below shows separate latest projections; its filters do not change these charts.');
    } catch (error) {
        if (error.name !== 'AbortError') {
            status.textContent = error.message;
            empty(root, 'No partial aggregate is shown. Adjust the window or refresh.');
        }
    } finally {
        if (sequence === requestSequence) refreshButton.disabled = false;
    }
}


async function identityDrilldown(params,page,signal) {
    const domain={discovery:'discovery',p2p:'inspection',apis:'inspection',clients:'inspection',events:'changes'}[page]||'all';
    const panel=section('Observed identities · evidence drill-down','Distinct cryptographic identities with timestamped evidence in this window. Up to 50 per page, sorted by identity.');
    const list=make('div','identity-links');panel.append(list);
    const more=make('button','secondary','Load more identities');more.type='button';panel.append(more);
    let cursor='';
    async function next(){
        more.disabled=true;
        try {
            const query=new URLSearchParams(params);query.set('domain',domain);if(cursor)query.set('after',cursor);
            const response=await fetch(`/analytics/identities.json?${query}`,{signal,cache:'no-store'});
            if(!response.ok)throw new Error('Identity drill-down unavailable');
            const data=await response.json();
            if(signal.aborted)return;
            for(const id of data.identities){const line=make('p','identity-link'),link=make('a','',id);link.href=`/explorer?q=${encodeURIComponent(id)}`;const evidence=make('a','','Stored evidence API');evidence.href=`/api/v1/identities/${id}`;line.append(link,document.createTextNode(' · '),evidence);list.append(line);}
            if(!data.identities.length && !cursor)empty(list,'No eligible identities in this measurement window.');
            cursor=data.nextCursor;more.hidden=!cursor;
        } catch(error){if(error.name!=='AbortError')empty(list,error.message);}
        finally{more.disabled=false;}
    }
    more.onclick=next;await next();
}
const controls = document.querySelector('.scope-controls');
const startInput = make('input'); startInput.type='datetime-local'; startInput.step='1'; startInput.setAttribute('aria-label','Window start UTC');
const endInput = make('input'); endInput.type='datetime-local'; endInput.step='1'; endInput.setAttribute('aria-label','Window end UTC');
const startLabel=make('label','','Start UTC');startLabel.append(startInput);
const endLabel=make('label','','End UTC');endLabel.append(endInput);controls.append(startLabel,endLabel);
const exportJson=make('a','button secondary','JSON');exportJson.download='torchnode-analytics.json';
const exportCsv=make('a','button secondary','CSV');
const reportLink=make('a','button secondary','Printable report');
controls.append(exportJson,reportLink);
if(root.dataset.admin!=='false')controls.append(exportCsv);
const palette=['#54dac6','#759dff','#f1b86b','#dc8abf','#9caec4','#af9bf2','#b9d77b','#ef8367','#7ad0ea','#d6a55b'];
function svgElement(tag, attrs={}) {
    const node=document.createElementNS('http://www.w3.org/2000/svg',tag);
    for(const [key,value] of Object.entries(attrs))node.setAttribute(key,String(value));
    return node;
}
function chart(parent, title) {
    const svg=svgElement('svg',{viewBox:'0 0 640 270',role:'img','aria-label':title});
    svg.classList.add('stat-chart');parent.append(svg);return svg;
}
function mark(svg,tag,attrs,description) {
    const element=svgElement(tag,{...attrs,tabindex:'0'}),title=svgElement('title');title.textContent=description;element.append(title);svg.append(element);return element;
}
function textAt(svg,x,y,value,anchor='start') {
    const label=svgElement('text',{x,y,fill:'#b7c9df','font-size':11,'text-anchor':anchor});label.textContent=value;svg.append(label);
}
function legend(parent, items) {
    const list=make('div','chart-legend');items.forEach((item,i)=>{const label=make('span','',item);label.style.borderLeft=`10px solid ${palette[i%palette.length]}`;list.append(label)});parent.append(list);
}
function grouped(parent,current,baseline) {
    const labels=[...new Set([...(current?.buckets||[]),...(baseline?.buckets||[])].map(b=>b.label))].slice(0,10);
    if(!labels.length){empty(parent,'No recorded categories in either window.');return;}
    const svg=chart(parent,'Current and previous window count comparison'),max=Math.max(1,...labels.flatMap(label=>[count(current,label),count(baseline,label)]));
    const group=560/labels.length;
    labels.forEach((label,i)=>{
        [current,baseline].forEach((item,j)=>{const value=count(item,label),height=value/max*185;
            mark(svg,'rect',{x:55+i*group+j*group*.35,y:210-height,width:group*.3,height,fill:palette[j]},`${label}: ${j?'previous':'current'} ${value} ${item?.countingUnit||''}`);
        });
        textAt(svg,55+i*group,235,label.length>12?label.slice(0,11)+'…':label);
    });
    textAt(svg,5,22,number(max));legend(parent,['Current window','Previous equal-duration window']);
    note(parent,`Showing up to 10 categories. Current denominator ${number(current?.denominator)}, unknown ${number(current?.unknown)}; previous denominator ${number(baseline?.denominator)}, unknown ${number(baseline?.unknown)}. ${current?.countingUnit||''}.`);
}
function stacked(parent,report) {
    const ids=['p2p-tcp','p2p-rlpx','p2p-hello','p2p-status'];
    const states=[...new Set(ids.flatMap(id=>(metric(report,id)?.buckets||[]).map(b=>b.label)))];
    if(!states.length){empty(parent,'No timestamped endpoint-stage evidence.');return;}
    const svg=chart(parent,'Stacked protocol stage records');
    const max=Math.max(1,...ids.map(id=>(metric(report,id)?.buckets||[]).reduce((n,b)=>n+b.count,0)));
    ids.forEach((id,i)=>{let x=110;const item=metric(report,id);textAt(svg,5,35+i*55,id.replace('p2p-','').toUpperCase());
        states.forEach((state,j)=>{const value=count(item,state),width=value/max*490;
            mark(svg,'rect',{x,y:15+i*55,width,height:28,fill:palette[j%palette.length]},`${id} / ${state}: ${value} stage records`);x+=width;
        });
    });legend(parent,states);note(parent,'Absolute recorded stage counts. NOT_TESTED and UNKNOWN remain separate from tested outcomes; stack totals include them.');
}
function timeline(parent,item) {
    if(!item?.buckets.length){empty(parent,'No timestamped activity recorded.');return;}
    const svg=chart(parent,item.id),max=Math.max(1,...item.buckets.map(b=>b.count));
    const points=[];
    const bucketTime=label=>Date.parse(label+(label.length===13?':00:00Z':'T00:00:00Z'));
    const first=bucketTime(item.buckets[0].label),last=bucketTime(item.buckets.at(-1).label);
    item.buckets.forEach((bucket,i)=>{
        const x=first===last?330:50+(bucketTime(bucket.label)-first)/(last-first)*560,y=210-bucket.count/max*185;
        points.push([x,y,bucket.label]);
        mark(svg,'line',{x1:x,x2:x,y1:210,y2:y,stroke:'#54dac6','stroke-width':2},`${bucket.label} UTC: ${bucket.count} ${item.countingUnit}`);
        mark(svg,'circle',{cx:x,cy:y,r:3,fill:'#759dff'},`${bucket.label} UTC: ${bucket.count} ${item.countingUnit}`);
    });
    for(let i=1;i<points.length;i++) {
        const previous=points[i-1],current=points[i];
        const gap=Date.parse(current[2]+(current[2].length===13?':00:00Z':'T00:00:00Z'))-Date.parse(previous[2]+(previous[2].length===13?':00:00Z':'T00:00:00Z'));
        if(gap===(current[2].length===13?3600000:86400000))svg.append(svgElement('line',{x1:previous[0],y1:previous[1],x2:current[0],y2:current[1],stroke:'#759dff','stroke-width':1}));
    }
    textAt(svg,5,22,number(max));textAt(svg,50,245,item.buckets[0].label+' UTC');textAt(svg,610,262,item.buckets.at(-1).label+' UTC','end');note(parent,item.evidenceRule+' · '+item.countingUnit);
}
function advanced(report,baseline,page) {
    if(['overview','p2p'].includes(page))stacked(section('Protocol outcomes · stacked bars','Recorded endpoint-stage outcomes, preserving every actual state.'),report);
    if(['overview','discovery','trends'].includes(page)) {
        timeline(section('Discovery identities over time','Distinct identities within each UTC bucket; the same identity may recur in later buckets.'),metric(report,'discovery-identities-time'));
        timeline(section('Discovery activity','Receipt occurrences, including repeated observations.'),metric(report,'discovery-events-time'));
    }
    if(['overview','trends','p2p'].includes(page))timeline(section('Inspection activity','Run starts; this does not imply a successful inspection.'),metric(report,'inspection-runs-time'));
    if(['events','trends'].includes(page))timeline(section('Changes over time','Persisted event clocks reflect detected evidence changes.'),metric(report,'changes-time'));
    const choices={overview:'execution-clients',clients:'execution-clients',discovery:'discovery-providers',p2p:'p2p-status',apis:'rpc-attempts',statistics:'distinct-endpoints',geography:'country-distribution-top-100',trends:'inspection-triggers',events:'change-activity'};
    if(['p2p','clients'].includes(page))bars(section('Advertised protocol capabilities','Distinct authenticated Hello identities per capability. Memberships overlap; advertisement is not protocol exercise.'),metric(report,'hello-capabilities')?.buckets||[]);
    if(['p2p','statistics'].includes(page))bars(section('IPv4 / IPv6 TCP outcomes','Endpoint-stage records by recorded family. NOT_TESTED is retained separately.'),metric(report,'family-tcp-outcomes')?.buckets||[]);
    const id=choices[page]||choices.overview;
    grouped(section('Historical window comparison','Vertical grouped bars. Compare with the immediately preceding window of equal duration. Activity and sampling can change.'),metric(report,id),metric(baseline,id));
    note(root,`Comparison baseline: ${baseline.scope.startInclusive} to ${baseline.scope.endExclusive} UTC. No relative growth estimate is inferred.`);
}
function evidenceTable(report,page) {
    const wanted={overview:['hello-capabilities','change-activity','distinct-endpoints'],discovery:['discovery-providers','discovery-by-source','repeat-discovery','distinct-endpoints'],p2p:['hello-capabilities','family-tcp-outcomes','network-verification','verification-comparisons','tcp-outcomes-time'],apis:['rpc-response-latency','beacon-response-latency','rpc-attempts','beacon-attempts','rpc-chainId','rpc-networkId','beacon-clients'],clients:['execution-clients','execution-versions','hello-capabilities'],geography:['country-lookup-status','asn-lookup-status','country-distribution-top-100','asn-distribution-top-100'],trends:['tcp-outcomes-time','inspection-triggers','repeat-discovery'],events:['change-activity','changes-time']};
    const items=report.metrics.filter(item=>!wanted[page]||wanted[page].includes(item.id));
    const panel=section('Evidence statistics & drill-down','Search the returned categories. Denominators and unknown values describe each metric independently.');
    const search=make('input');search.type='search';search.placeholder='Filter evidence categories';search.setAttribute('aria-label','Search evidence statistics');panel.append(search);
    const table=make('table');const head=make('thead'),header=make('tr');['Metric / counting unit','Category','Count','Denominator','Unknown'].forEach(name=>header.append(make('th','',name)));head.append(header);table.append(head);
    const body=make('tbody');table.append(body);const wrap=make('div','table-scroll');wrap.append(table);panel.append(wrap);
    for(const item of items) {
        if(!item.buckets.length){const row=make('tr'),cell=make('td','',item.id+' · No eligible evidence; denominator '+item.denominator+', unknown '+item.unknown);cell.colSpan=5;row.append(cell);body.append(row);}
        for(const bucket of item.buckets){const row=make('tr');row.title=item.evidenceRule+' · '+item.denominatorMeaning+' · '+item.bucketSemantics;
            [item.id+' / '+item.countingUnit,bucket.label,number(bucket.count),number(item.denominator),number(item.unknown)].forEach(value=>row.append(make('td','',value)));body.append(row);
        }
    }
    search.oninput=()=>{for(const row of body.rows)row.hidden=!row.textContent.toLowerCase().includes(search.value.toLowerCase())};
    const link=make('a','button secondary','Drill into observed nodes and historical evidence');link.href='/explorer';panel.append(link);
}
scopeSelect.addEventListener('change', () => { startInput.value=''; endInput.value=''; load(); });
refreshButton.addEventListener('click', load);
document.getElementById('clear-form')?.addEventListener('submit', () => {
    requestSequence++;
    requestController?.abort();
    root.replaceChildren();
    status.textContent = 'Clearing collected observations…';
});
window.addEventListener('pagehide', () => requestController?.abort());
load();

}
