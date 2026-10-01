import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';

const page = fs.readFileSync('src/main/resources/webapp/WEB-INF/views/inspection.jsp', 'utf8');
class Element {
    constructor(tag = 'div') { this.tag = tag; this.children = []; this.textContent = ''; }
    append(...children) { this.children.push(...children); }
    replaceChildren(...children) { this.children = [...children]; }
}
const timeline = new Element();
const context = vm.createContext({
    document: {createElement: tag => new Element(tag), getElementById: () => timeline},
    el: () => timeline, available: value => value !== null && value !== undefined && value !== '',
    show: value => value === null || value === undefined ? 'Unavailable' : String(value),
    Map, JSON, Date
});
const historyFunctions = page.slice(page.indexOf('function evidenceRow('), page.indexOf('async function loadObservations('));
const timelineFunction = page.slice(page.indexOf('function renderHistoricalTimeline()'), page.indexOf('async function loadHistory()'));
assert.ok(historyFunctions.startsWith('function evidenceRow('));
assert.ok(timelineFunction.startsWith('function renderHistoricalTimeline()'));
vm.runInContext(historyFunctions + '\n' + timelineFunction, context);
const stamp = n => `2026-01-01T00:00:0${n}Z`;
const endpoint = (addressFamily, purpose) => ({address: addressFamily === 'IPV4' ? '192.0.2.1' : '2001:db8::1',
    addressFamily, transport: 'UDP', purpose, port: 30303});
const receipt = (id, source, family, purpose) => ({id, observation: {source, provenance: 'fixture',
    observedAt: stamp(id), endpoints: [endpoint(family, purpose)]}});
const entries = [receipt(4,'discv4','IPV4','DISCOVERY'),receipt(3,'discv4','IPV4','DISCOVERY'),
    receipt(2,'discv5','IPV4','DISCOVERY'),receipt(1,'discv4','IPV6','DISCOVERY'),
    receipt(0,'discv4','IPV4','P2P')];
const list = new Element();
context.renderDiscoveryPage(list,entries);
assert.equal(list.children.length,4,'incompatible source, family and purpose remain separate');
assert.match(list.children[0].children[0].textContent,/2 occurrences on this page/);
assert.match(list.children[0].children[0].textContent,/00:00:03Z to 2026-01-01T00:00:04Z/);
assert.equal(list.children[0].children.filter(item => item.className === 'occurrence' &&
    item.children[0].textContent.startsWith('Source observation')).length,2);
const hostile = new Element();
context.evidenceRow(hostile,'Remote','<img src=x onerror=alert(1)>');
assert.equal(hostile.children[0].children[1].textContent,'<img src=x onerror=alert(1)>');
assert.equal(hostile.children[0].children.length,2,'remote content is a text node, not parsed markup');
context.timelinePages = {discovery: entries.slice(0,2),enr: [],enrichment: [],runs: [
    {id:'run-a',startedAt:stamp(4),discoverySource:'discv4'},
    {id:'run-b',startedAt:stamp(4),discoverySource:'discv4'}],changes: []};
context.renderHistoricalTimeline();
assert.equal(timeline.children.length,3,'compatible receipts group, distinct runs remain separate');
const text = element => [element.textContent,...element.children.map(text)].join(' ');
assert.match(text(timeline.children[0]),/observed 2 times between/);
console.log('History DOM: exact page counts, compatible grouping, run distinction, text-node escaping PASS');
