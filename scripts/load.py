#!/usr/bin/env python3
"""
Project Doppel load test (Python 3 standard library only).

1. Adds 500 synthetic brands via POST /api/v1/brands.
2. Sends 2,000 screenings over 400 distinct (VPA, display name) pairs from 100 registrants at concurrency 20.
3. Prints p50/p95/p99 latency, decision counts and cache-hit rate.

Usage:  python3 scripts/load.py [--base-url http://localhost:8080] [--seed 42]
"""
import argparse
import http.client
import json
import random
import statistics
import string
import threading
import time
import uuid
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
from urllib.parse import urlparse

SEED_TOKENS = ["zentrabank", "shopkart", "electrapower", "novapay", "quickrail", "fintrust", "medicore",
               "skyvista", "urbanfeast", "bluewave", "pixelmart", "swiftcab"]
LURES = ["kyc", "refund", "support", "care", "cashback", "reward", "verify", "offer", "helpline"]
FIRST = ["rahul", "priya", "amit", "neha", "vikram", "anita", "suresh", "kavya", "arjun", "meena", "deepak", "pooja"]
LAST = ["sharma", "verma", "nair", "reddy", "gupta", "patel", "singh", "rao", "iyer", "das"]

_local = threading.local()


def connection(base):
    conn = getattr(_local, "conn", None)
    if conn is None:
        u = urlparse(base)
        conn = http.client.HTTPConnection(u.hostname, u.port or 80, timeout=30)
        _local.conn = conn
    return conn


def post(base, path, payload):
    body = json.dumps(payload).encode()
    for attempt in range(2):  # one retry on a dropped keep-alive connection
        conn = connection(base)
        try:
            start = time.perf_counter()
            conn.request("POST", path, body=body, headers={"Content-Type": "application/json"})
            resp = conn.getresponse()
            data = resp.read()
            return resp.status, (time.perf_counter() - start) * 1000.0, data
        except (http.client.HTTPException, ConnectionError, OSError):
            conn.close()
            _local.conn = None
            if attempt:
                raise


def synthetic_token(rng, used):
    while True:
        syllables = ["".join(rng.choice(c) for c in ("bcdfgklmnprstvz", "aeiou")) for _ in range(rng.randint(3, 5))]
        token = "".join(syllables) + rng.choice(string.ascii_lowercase)
        if token not in used and "quickloan" not in token:
            used.add(token)
            return token


def homoglyph(token):
    return token.replace("o", "0", 1) if "o" in token else token.replace("a", "4", 1)


def typo(rng, token):
    i = rng.randrange(1, len(token) - 1)
    return token[:i] + token[i + 1] + token[i] + token[i + 2:]  # adjacent transposition


def build_pairs(rng, tokens, count):
    pairs = set()
    while len(pairs) < count:
        token = rng.choice(tokens)
        kind = rng.random()
        n = rng.randint(1, 999)
        if kind < 0.25:
            pairs.add((f"{token}.{rng.choice(LURES)}{n}@ybl", f"{token.title()} {rng.choice(LURES).upper()}"))
        elif kind < 0.40:
            pairs.add((f"{typo(rng, token)}{n}@okaxis", "Customer Desk"))
        elif kind < 0.50:
            pairs.add((f"{homoglyph(token)}.pay{n}@ybl", "Payments"))
        else:
            f, l = rng.choice(FIRST), rng.choice(LAST)
            pairs.add((f"{f}.{l}{n}@okhdfc", f"{f.title()} {l.title()}"))
    return list(pairs)


def percentile(sorted_values, p):
    k = (len(sorted_values) - 1) * p / 100.0
    lo, hi = int(k), min(int(k) + 1, len(sorted_values) - 1)
    return sorted_values[lo] + (sorted_values[hi] - sorted_values[lo]) * (k - lo)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base-url", default="http://localhost:8080")
    ap.add_argument("--brands", type=int, default=500)
    ap.add_argument("--requests", type=int, default=2000)
    ap.add_argument("--pairs", type=int, default=400)
    ap.add_argument("--registrants", type=int, default=100)
    ap.add_argument("--concurrency", type=int, default=20)
    ap.add_argument("--seed", type=int, default=42)
    args = ap.parse_args()

    rng = random.Random(args.seed)
    run = uuid.uuid4().hex[:8]  # brand names and requestIds are unique per run
    base = args.base_url.rstrip("/")

    # ---- 1. synthetic brands -------------------------------------------------------------------------------
    used = set(SEED_TOKENS)
    brands = []
    for i in range(args.brands):
        token = synthetic_token(rng, used)
        brands.append({"name": f"Synthetic {run} {i:03d}", "tokens": [token], "officialVpas": [f"{token}@synth"]})

    t0 = time.perf_counter()
    with ThreadPoolExecutor(args.concurrency) as pool:
        brand_results = list(pool.map(lambda b: post(base, "/api/v1/brands", b), brands))
    created = sum(1 for s, _, _ in brand_results if s == 201)
    print(f"Brands: {created}/{args.brands} created in {time.perf_counter() - t0:.1f}s "
          f"(other statuses: {dict(Counter(s for s, _, _ in brand_results if s != 201))})")

    # ---- 2. screenings -------------------------------------------------------------------------------------
    tokens = SEED_TOKENS + [b["tokens"][0] for b in brands]
    pairs = build_pairs(rng, tokens, args.pairs)
    accounts = [str(rng.randrange(10**11, 10**12)) for _ in range(args.registrants)]
    jobs = []
    for i in range(args.requests):
        vpa, name = pairs[i % len(pairs)] if i < len(pairs) else rng.choice(pairs)
        jobs.append({"requestId": f"load-{run}-{i}", "vpa": vpa, "displayName": name,
                     "registrantAccount": rng.choice(accounts),
                     "timestamp": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")})

    t0 = time.perf_counter()
    with ThreadPoolExecutor(args.concurrency) as pool:
        results = list(pool.map(lambda j: post(base, "/api/v1/payee-screenings", j), jobs))
    wall = time.perf_counter() - t0

    latencies = sorted(ms for _, ms, _ in results)
    ok = [json.loads(d) for s, _, d in results if s == 200]
    hit_ms = sorted(ms for (s, ms, d) in results if s == 200 and json.loads(d).get("cached"))
    miss_ms = sorted(ms for (s, ms, d) in results if s == 200 and not json.loads(d).get("cached"))
    decisions = Counter(r["decision"] for r in ok)
    hits = sum(1 for r in ok if r.get("cached"))
    bursts = sum(1 for r in ok if any(x.startswith("CAMPAIGN_BURST") for x in r["reasons"]))

    print(f"Screenings: {len(results)} sent, {len(ok)} OK, errors {len(results) - len(ok)} "
          f"| {len(pairs)} distinct pairs, {args.registrants} registrants, concurrency {args.concurrency}")
    print(f"Throughput: {len(results) / wall:.0f} req/s over {wall:.1f}s")
    print(f"Latency ms: p50={percentile(latencies, 50):.1f}  p95={percentile(latencies, 95):.1f}  "
          f"p99={percentile(latencies, 99):.1f}  mean={statistics.mean(latencies):.1f}  max={latencies[-1]:.1f}")
    for label, values in (("cache hits", hit_ms), ("cache misses", miss_ms)):
        if values:
            print(f"  {label:<12} n={len(values):<5} p50={percentile(values, 50):.1f}  p95={percentile(values, 95):.1f}  "
                  f"p99={percentile(values, 99):.1f}")
    print("Decisions: " + "  ".join(f"{d}={decisions.get(d, 0)}" for d in ("ALLOW", "REVIEW", "BLOCK")))
    print(f"Cache-hit rate: {hits / max(len(ok), 1):.1%} ({hits}/{len(ok)})   CAMPAIGN_BURST: {bursts}")


if __name__ == "__main__":
    main()
