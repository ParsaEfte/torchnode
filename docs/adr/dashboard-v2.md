# Dashboard v2 visual analytics

## Decision and information architecture

The main page presents a measurement story: scanner controls; explicit historical window; observation coverage; endpoint-scoped geography; execution-client evidence; discovery/address families; protocol funnel; independent RPC/Beacon candidates; derived change activity; then a separately labelled latest-projection inventory linked to Deep Inspection. The full Network Analytics methodology page remains available. The table's filters affect only that latest inventory, never the historical charts.

The existing server-rendered JSP and servlet remain. One deferred, locally served JavaScript file requests **one** `/analytics.json` report for the selected window and renders all visual sections from its typed metrics. No chart independently queries history; no JavaScript reimplements metric eligibility. The HTML and latest table load before the bounded report. There is no analytics worker, cache, polling loop or dashboard-specific persisted truth.

## Scope and visual semantics

The selector offers last 24 hours, 7 days and 30 days. Each request fixes an end instant and passes explicit UTC `[start,end)` parameters to NetworkAnalytics. Manual refresh obtains a new end instant. The UI shows the returned exact window and generation time. The latest projection cards/table are labelled separately. Explicit all-available analysis stays on `/analytics` because it can scan all retained history and may hit the established safety limit. A limit rejection displays an error, never a partial chart.

Charts use bars and text because identity-provider, family, capability, version, geographic and verification categories can overlap. No pies, implied 100% stacks or population percentages are used. Every panel states its unit/eligibility in concise text. Overview cards distinguish distinct cryptographic identities, discovery receipts, inspected identities and inspection runs. Client classification reports usable/CONFLICT identities and the unknown observed-identity population; exact-version bars show a top subset and the analytics omitted-tail rule. The funnel shows each stage's attempted denominator, PASS and separately named other outcomes. RPC and Beacon remain independent candidate-probe views; unsuccessful candidates are not service-absence claims. ChangeEvent bars retain actual type and rule version and are not instability scores.

Unknown and partial evidence remain visible: no-client, no-inspection, zero-event, no-country, DATASET_UNAVAILABLE, NOT_APPLICABLE, LOOKUP_FAILED, CONFLICT, UNKNOWN_VERSION, NOT_TESTED and top-N omissions are described where relevant. Empty panels explain the absent eligible evidence rather than showing an invalid zero-percent distribution. An observed IPv6 address is separate from an IPv6 TCP PASS.

## Geography and privacy

The map uses country outlines only. [Natural Earth](https://www.naturalearthdata.com/downloads/) 1:110m Admin 0 boundaries were converted from the [public-domain GeoJSON distribution](https://github.com/datasets/geo-countries) input (SHA-256 `45f41865adec4f86602c2cd05c0e29cd8b437614bf2f5b5a863d12463202cae4`) by `tools/build-dashboard-map.py` into a 96,686-byte local SVG. [Natural Earth's license](https://github.com/nvkelso/natural-earth-vector/blob/master/LICENSE.md) places its vector data in the public domain; the conversion repository uses the Open Data Commons Public Domain Dedication. The local asset contains only static boundary paths, country codes and country names. It has no script, telemetry, tile request or peer identifier. The frontend requests it only from TorchNode's localhost server.

Map color represents bounded top-100 **normalized-address/dataset/country memberships** from NetworkAnalytics, summed by country code for display. The panel states its unit, top-N tail and dataset context; ranked country and ASN bars retain dataset-specific labels in their tooltips. One identity can contribute multiple addresses, countries or ASNs. Neither map nor table assigns a canonical node location. Country is approximate network context; ASN is registration context, never hosting proof. An unavailable offline dataset yields an explicit coverage message instead of an empty map implying zero locations. No online GeoIP lookup or external peer-IP disclosure was added.

## Frontend, refresh and safety

The frontend uses browser DOM APIs and local assets, with no new runtime library, CDN, package manager, telemetry or CSP exception for third parties. Evidence-derived strings enter DOM through `textContent` or escaped JSP output, not `innerHTML`. The map parser imports a fixed locally served SVG; country counts set only local styles/title text. The page uses semantic sections, labelled controls, readable text alongside bars, keyboard focus outlines, a text alternative for the map and responsive one/two-column layouts; the latest table scrolls horizontally on narrow screens. Motion is limited to the pre-existing loading skeleton, which respects reduced-motion.

Refresh is manual or triggered by selector change. Each new request aborts the prior one and has a sequence guard. The Clear form aborts and clears the current visual report immediately; servlet analytics responses and Clear retain their existing scanner lock ordering. The new page does not cache an old report across navigation. Server responses remain `no-store`; the static map outline may be cached without observation data.

## Frozen-model and milestone boundaries

Schema remains version 5; there is no migration 6. Identity, endpoint multiplicity, ENR trust, historical occurrences, ChangeEvents, protocol outcomes and analytics denominators are unchanged. All new UI state is discardable and recomputable. The existing CSV and Deep Inspection evidence model remain. The latest table now says “selected latest endpoint” and “response in projection” to avoid treating its representative row as canonical endpoint or failed candidate probes as service absence.

A bounded time series was evaluated but deferred: the existing typed report has no time buckets, and normalized timestamp predicates currently scan retained history. Adding a distinct series query solely for presentation would expand the query surface before measured need. History UI / Reports is the next milestone. Full timeline exploration, public API, richer exports, deployment redesign, alerts and new observation domains remain outside this milestone.
