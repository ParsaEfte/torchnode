import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';

const source = fs.readFileSync('src/main/resources/webapp/assets/dashboard-v2.js', 'utf8');
class Element {
    constructor(tag = 'div') { this.tag = tag; this.children = []; this.textContent = ''; this.style = {}; this.listeners = {}; this.attributes = {}; this.classList = {add() {}}; }
    append(...children) { this.children.push(...children); }
    replaceChildren(...children) { this.children = [...children]; }
    addEventListener(type, callback) { this.listeners[type] = callback; }
    fire(type) { this.listeners[type]?.(); }
    setAttribute(name, value) { this.attributes[name] = value; }
    getAttribute(name) { return this.attributes[name]; }
    querySelectorAll() { return this.paths || []; }
    querySelector() { return this.titleElement; }
}
const elements = Object.fromEntries(['visual-analytics', 'measurement-scope', 'refresh-measurement',
    'measurement-status', 'clear-form'].map(id => [id, new Element()]));
elements['measurement-scope'].value = '1';
const bucket = (label, count) => ({label, count});
const metrics = [
    ['observed-identities', 2, 0, [bucket('observed', 2)]],
    ['discovery-occurrences', 1000, 0, [bucket('receipts', 1000)]],
    ['inspected-identities', 2, 1, [bucket('inspected', 1)]],
    ['inspections', 1, 0, [bucket('runs', 1)]],
    ['enr-trust', 1, 0, [bucket('trusted', 1)]],
    ['country-distribution-top-100', 1, 0, [bucket('fixture-dataset:US', 1)]],
    ['country-lookup-status', 2, 0, [bucket('fixture-dataset:FOUND', 1), bucket('missing-dataset:DATASET_UNAVAILABLE', 1)]],
    ['asn-distribution-top-100', 1, 0, [bucket('fixture-dataset:64500', 1)]],
    ['execution-clients', 2, 1, [bucket('CONFLICT', 1)]],
    ['execution-versions', 1, 0, [bucket('Geth/UNKNOWN_VERSION', 1)]],
    ['discovery-providers', 2, 0, [bucket('discv4', 2), bucket('discv5', 1)]],
    ['discv4-discv5-overlap', 2, 0, [bucket('both', 1)]],
    ['address-families', 2, 0, [bucket('IPV4', 2), bucket('IPV6', 1)]],
    ['dual-family', 2, 0, [bucket('both', 1)]],
    ['ipv6-tcp-pass', 0, 0, [bucket('IPv6 TCP PASS', 0)]],
    ['p2p-tcp', 1, 0, [bucket('PASS', 1)]],
    ['p2p-rlpx', 1, 0, [bucket('PASS', 1)]],
    ['p2p-hello', 0, 1, [bucket('NOT_TESTED', 1)]],
    ['p2p-status', 0, 1, [bucket('NOT_TESTED', 1)]],
    ['rpc-attempts', 1, 0, [bucket('NO_TCP_CONNECTION', 1)]],
    ['beacon-attempts', 1, 0, [bucket('PASS', 1)]],
    ['change-activity', 0, 0, []]
].map(([id, denominator, unknown, buckets]) => ({id, denominator, unknown, buckets, evidenceRule: 'fixture rule'}));
const report = {scope: {startInclusive: '2026-01-01T00:00:00Z', endExclusive: '2026-01-02T00:00:00Z'},
    generatedAt: '2026-01-02T00:00:00Z', metrics};
const map = new Element('svg');
const shape = new Element('path');
shape.setAttribute('data-country', 'US'); shape.setAttribute('data-name', 'United States');
shape.titleElement = new Element('title'); map.paths = [shape];
const context = vm.createContext({
    document: {getElementById: id => elements[id], createElement: tag => new Element(tag), importNode: value => value},
    window: {addEventListener() {}},
    DOMParser: class { parseFromString() { return {documentElement: map}; } },
    fetch: async url => url.includes('world-countries.svg') ? {ok: true, text: async () => '<svg/>'} :
        {ok: true, json: async () => report},
    AbortController, URLSearchParams, Date, Map, Number, Math, String, Error
});
vm.runInContext(source, context, {filename: 'dashboard-v2.js'});
await new Promise(resolve => setImmediate(resolve));
await new Promise(resolve => setImmediate(resolve));
const text = element => [element.textContent, ...element.children.map(text)].join(' ');
const rendered = text(elements['visual-analytics']);
for (const expected of ['Observed identities', 'Discovery observations', '1,000', 'CONFLICT',
    'DATASET_UNAVAILABLE', 'NOT_TESTED', 'No derived ChangeEvents', 'not the Ethereum population'])
    assert.ok(rendered.includes(expected), `missing ${expected}`);
assert.ok(rendered.includes('1 classified or conflicting identities among 2 observed identities'));
assert.ok(rendered.includes('IPv6 advertisement is not reachability'));
assert.ok(rendered.includes('Unsuccessful candidates do not establish service absence'));
assert.ok(rendered.includes('ASN does not establish hosting provider'));
assert.ok(!rendered.includes('192.0.2.1'));
assert.ok(shape.style.fill.startsWith('hsl('));
let releaseStale;
context.fetch = () => new Promise(resolve => { releaseStale = resolve; });
elements['measurement-scope'].fire('change');
elements['clear-form'].fire('submit');
releaseStale({ok: true, json: async () => report});
await new Promise(resolve => setImmediate(resolve));
assert.equal(elements['visual-analytics'].children.length, 0);
console.log('Dashboard v2 DOM rendering: units, unknowns, overlap, map aggregate, stale Clear response PASS');
