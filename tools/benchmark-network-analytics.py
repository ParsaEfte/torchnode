#!/usr/bin/env python3
"""Read-only representative plans plus controlled identity/receipt count timing."""
import sqlite3
import sys
import time


def timed(db, sql, parameters=(), repetitions=5):
    values = []
    for _ in range(repetitions):
        start = time.perf_counter()
        result = db.execute(sql, parameters).fetchall()
        values.append((time.perf_counter() - start) * 1000)
    return result, round(min(values), 3), round(max(values), 3)


def controlled(diverse):
    db = sqlite3.connect(":memory:")
    db.execute("CREATE TABLE discovery_observations(node_id TEXT, source TEXT, observed_at TEXT)")
    db.executemany("INSERT INTO discovery_observations VALUES(?,?,?)", [
        (f"{i:0128x}" if diverse else "a" * 128, "discv4", "2026-01-01T00:00:00Z")
        for i in range(1000)
    ])
    identities, low, high = timed(db, "SELECT COUNT(DISTINCT node_id) FROM discovery_observations")
    receipts = db.execute("SELECT COUNT(*) FROM discovery_observations").fetchone()[0]
    assert identities[0][0] == (1000 if diverse else 1) and receipts == 1000
    print(f"controlled diverse={diverse} identities={identities[0][0]} receipts={receipts} identity_query_ms={low}..{high}")


def representative(path):
    db = sqlite3.connect(f"file:{path}?mode=ro", uri=True)
    def norm(column):
        return (f"substr({column},1,19) || CASE WHEN substr({column},20,1)='.' THEN "
                f"substr(substr({column},21,instr(substr({column},21),'Z')-1) || '000000000',1,9) "
                "ELSE '000000000' END")

    def window(column):
        return f"{column} IS NOT NULL AND {norm(column)}>=? AND {norm(column)}<?"

    d, x, r, e, l, c = (window(column) for column in (
        "d.observed_at", "x.observed_at", "r.started_at", "e.observed_at",
        "l.looked_up_at", "c.current_observed_at"))
    queries = {
        "identity": f"SELECT COUNT(DISTINCT d.node_id) FROM discovery_observations d WHERE {d}",
        "provider": f"SELECT d.source,COUNT(DISTINCT d.node_id) FROM discovery_observations d WHERE {d} GROUP BY d.source",
        "family": f"SELECT x.address_family,COUNT(DISTINCT x.node_id) FROM discovery_endpoint_index x WHERE {x} GROUP BY x.address_family",
        "client": f"SELECT COUNT(DISTINCT r.node_id) FROM inspection_runs r JOIN inspection_evidence v ON v.hash=r.evidence_hash WHERE {r} AND json_extract(v.evidence_json,'$.rpc.clientVersion') IS NOT NULL",
        "capability": f"SELECT CASE WHEN cap.type='text' THEN cap.value ELSE json_extract(cap.value,'$.name') END,COUNT(DISTINCT r.node_id) FROM inspection_runs r JOIN inspection_evidence v ON v.hash=r.evidence_hash JOIN json_each(v.evidence_json,'$.endpointAttempts') a JOIN json_each(a.value,'$.p2p.hello.capabilities') cap WHERE {r} GROUP BY 1",
        "p2p": f"SELECT json_extract(s.value,'$.state'),COUNT(*) FROM inspection_runs r JOIN inspection_evidence v ON v.hash=r.evidence_hash JOIN json_each(v.evidence_json,'$.endpointAttempts') a JOIN json_each(a.value,'$.diagnostics') s WHERE {r} AND json_extract(s.value,'$.name')='P2P TCP' GROUP BY 1",
        "rpc": f"SELECT json_extract(a.value,'$.rpcReachable'),COUNT(*) FROM inspection_runs r JOIN inspection_evidence v ON v.hash=r.evidence_hash JOIN json_each(v.evidence_json,'$.rpcAttempts') a WHERE {r} GROUP BY 1",
        "beacon": f"SELECT json_extract(a.value,'$.beaconReachable'),COUNT(*) FROM inspection_runs r JOIN inspection_evidence v ON v.hash=r.evidence_hash JOIN json_each(v.evidence_json,'$.beaconAttempts') a WHERE {r} GROUP BY 1",
        "enr": f"SELECT e.signature_validation,e.identity_comparison,COUNT(DISTINCT e.node_id) FROM enr_observations e WHERE {e} GROUP BY 1,2",
        "country": f"SELECT json_extract(l.evidence_json,'$.country.status'),COUNT(DISTINCT l.address) FROM network_enrichment_lookups l WHERE {l} GROUP BY 1",
        "asn": f"SELECT json_extract(l.evidence_json,'$.asn.status'),COUNT(DISTINCT l.address) FROM network_enrichment_lookups l WHERE {l} GROUP BY 1",
        "changes": f"SELECT c.derivation_version,c.change_type,COUNT(*) FROM change_events c WHERE {c} GROUP BY 1,2",
        "snapshot": "SELECT COUNT(DISTINCT CASE WHEN length(node_id)=128 AND node_id NOT GLOB '*[^0-9a-fA-F]*' THEN lower(node_id) END) FROM nodes",
    }
    bounds = ("2026-09-29T00:00:00000000000", "2026-10-01T00:00:00000000000")
    for name, sql in queries.items():
        params = () if name == "snapshot" else bounds
        result, low, high = timed(db, sql, params)
        plan = [row[3] for row in db.execute("EXPLAIN QUERY PLAN " + sql, params)]
        print(f"{name}: rows={len(result)} ms={low}..{high} plan={plan}")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("usage: benchmark-network-analytics.py representative-db-copy")
    controlled(False)
    controlled(True)
    representative(sys.argv[1])
