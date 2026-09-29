import { readFileSync } from 'node:fs';
import vm from 'node:vm';
import assert from 'node:assert/strict';
// Execute the actual enrichment renderer, with DOM/row recording only; no lookup or fetch.
const source = readFileSync('src/main/resources/webapp/WEB-INF/views/inspection.jsp', 'utf8');
const start = source.indexOf('function renderNetworkEnrichment(entries) {');
const end = source.indexOf('function render(data) {', start);
assert.ok(start >= 0 && end > start);
const rows = [];
const element = () => ({append(){}, replaceChildren(){rows.length = 0;}});
const context = vm.createContext({document:{createElement:element},el:element,row:(_,label,value)=>rows.push({label,value})});
vm.runInContext(source.slice(start,end),context);
const absent = {status:'DATASET_UNAVAILABLE', reason:'Optional dataset not configured',dataSource:'fixture source',dataSourceVersion:'unconfigured',lookedUpAt:'2026-09-29T00:00:00Z',provenance:'offline fixture'};
const endpoint = {address:'2606:4700:0:0:0:0:0:1111',addressFamily:'IPV6',transport:'TCP',port:30303,purpose:'P2P'};
const entry = {endpoint,source:'fixture discv5',observedAt:'2026-09-28T00:00:00Z',provenance:'source preserved',enrichment:{country:absent,asn:absent,hostingClassification:'NOT_AVAILABLE'}};
context.renderNetworkEnrichment([entry]);
assert.ok(rows.find(r=>r.label==='Country').value.startsWith('DATASET_UNAVAILABLE'));
assert.ok(rows.find(r=>r.label==='ASN').value.startsWith('DATASET_UNAVAILABLE'));
assert.ok(rows.find(r=>r.label==='Hosting classification').value.startsWith('NOT_AVAILABLE'));
assert.ok(rows.find(r=>r.label==='IPV6').value===endpoint.address);
assert.ok(rows.some(r=>String(r.value).includes('source preserved')));
assert.ok(rows.some(r=>String(r.value).includes('unconfigured')));
for(const status of ['NOT_FOUND','NOT_APPLICABLE','LOOKUP_FAILED']) {
    context.renderNetworkEnrichment([{...entry,enrichment:{country:{...absent,status},asn:{...absent,status},hostingClassification:'NOT_AVAILABLE'}}]);
    assert.ok(rows.find(r=>r.label==='Country').value.startsWith(status));
}
context.renderNetworkEnrichment([{...entry,enrichment:{country:{...absent,status:'FOUND',countryCode:'GB',countryName:'Fixture Country'},asn:{...absent,status:'FOUND',asn:64500,organization:'Fixture Cloud'},hostingClassification:'NOT_AVAILABLE'}}]);
assert.equal(rows.find(r=>r.label==='Country').value,'GB — Fixture Country');
assert.equal(rows.find(r=>r.label==='ASN').value,'AS64500 — Fixture Cloud');
assert.ok(rows.find(r=>r.label==='Hosting classification').value.startsWith('NOT_AVAILABLE'));
console.log('Network enrichment renderer: unavailable/miss/non-public/failure/found, family and provenance PASS; no provider/fetch');
