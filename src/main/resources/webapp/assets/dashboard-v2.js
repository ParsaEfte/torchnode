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
        const track = make('span', 'bar-track');
        const fill = make('span', 'bar-fill');
        fill.style.width = `${Math.max(1, row.count / maximum * 100)}%`;
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
        const detail = make('small', '', explanation); detail.title = item?.evidenceRule || explanation;
        card.append(detail); grid.append(card);
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
    const end = new Date();
    const days = Number(scopeSelect.value);
    const start = new Date(end.getTime() - days * 86400000);
    const params = new URLSearchParams({start: start.toISOString(), end: end.toISOString()});
    status.textContent = `Measuring ${days === 1 ? '24 hours' : `${days} days`} of recorded observations…`;
    root.replaceChildren(); refreshButton.disabled = true;
    try {
        const response = await fetch(`/analytics.json?${params}`, {signal: requestController.signal, cache: 'no-store'});
        if (!response.ok) throw new Error(response.status === 413 ?
            'This scope exceeds the explicit analytics safety limit. Select a shorter window.' :
            `Analytics request failed (${response.status}).`);
        const report = await response.json();
        if (sequence !== requestSequence) return;
        status.textContent = `Historical window: ${report.scope.startInclusive} to ${report.scope.endExclusive} UTC [start, end). Generated ${report.generatedAt}.`;
        overview(report);
        await geography(report, requestController.signal);
        if (sequence !== requestSequence) return;
        clients(report); discoveryAndFamilies(report); protocol(report); sidePaths(report); changes(report);
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

scopeSelect.addEventListener('change', load);
refreshButton.addEventListener('click', load);
document.getElementById('clear-form').addEventListener('submit', () => {
    requestSequence++;
    requestController?.abort();
    root.replaceChildren();
    status.textContent = 'Clearing collected observations…';
});
window.addEventListener('pagehide', () => requestController?.abort());
load();
