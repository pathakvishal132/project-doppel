# Project Doppel – Task 1

Screens UPI payee handles (VPA + display name) for **brand impersonation** before a payment is made.
Each screening is scored against a registry of fictional brands and returns `ALLOW`, `REVIEW` or `BLOCK`.
`REVIEW`/`BLOCK` results create an alert for analysts.

Stack: Java 17+ · Spring Boot 3.5 · Maven · PostgreSQL · Spring Data JPA · Flyway · JUnit 5 · Mockito.
Out of scope for Task 1: Redis, React UI, Task 2 and Task 3.

## API endpoints

| Method | Path | Description |
|---|---|---|
| GET  | `/api/v1/brands` | Brand registry (id, name, matching tokens, official VPAs) |
| POST | `/api/v1/brands` | Add a brand `{name, tokens, officialVpas}`; 201, 400 on invalid input, 409 on duplicate name/VPA |
| POST | `/api/v1/payee-screenings` | Screen a payee (idempotent on `requestId`) |
| GET  | `/api/v1/alerts?status=OPEN&brandId=&page=0&size=20` | Alerts, newest first, filterable and paginated (`size` ≤ 100) |

### Add a brand
```bash
curl -X POST localhost:8080/api/v1/brands -H 'Content-Type: application/json' -d '{
  "name": "OrbitFlix Streaming", "tokens": ["orbitflix"], "officialVpas": ["orbitflix@icici"]
}'
# 201 {"id":100,"name":"OrbitFlix Streaming","matchingTokens":["orbitflix"],"officialVpas":["orbitflix@icici"]}
```
- `name` required (≤ 100), unique ignoring case → otherwise **409**.
- `tokens` 1–20 non-blank entries (≤ 64). Stored in exact form (`Vortexa-Tel` → `vortexatel`).
- `officialVpas` required list (may be empty), each must match the VPA regex. Stored lower-cased. A VPA already owned by
  another brand → **409**.
- The very next screening uses the new brand, with no restart (see *Brand cache*).

### Caching and staleness (Task 2)
Two caches, both keyed on a **registry version**:

| Cache | Where | Key | Holds |
|---|---|---|---|
| Brand list | in-process (`spring.cache.type: simple`) | `version` | the brand registry |
| Screening cache | **Redis** (`ScreeningCache` interface; `InMemoryScreeningCache` in unit tests) | `doppel:screen:v{version}:{sha256(lower(vpa) + "\n" + displayName)}`, TTL 24 h | matched brand, brand + lure reasons, base score |

**Why a cached result can never go stale:** `brand_registry_version` (one row in PostgreSQL, V5) is incremented in the
**same transaction** as the brand insert in `POST /api/v1/brands`. Every screening reads the current version first and
builds its cache keys from it. Once the insert commits, every older entry is unreachable (it simply expires through its
TTL), and the next screening is scored against the new registry. Because the version lives in the database and not in
one JVM, this also holds with several app instances. No scan-and-delete over Redis keys is needed.

Only the brand-matching part is cached. The campaign check, decision, persistence and alert creation run on every
request. Responses carry `"cached": true|false`, which is stored so a replayed `requestId` returns an identical body.
Redis errors in the screening cache count as a miss: screening still works, just without the cache.

### Campaign detection (CAMPAIGN_BURST)
A screening is *flagged* when its base score (brand + lure) reaches the REVIEW threshold. For each `registrantRef`
(HMAC of the account), the sorted set `doppel:campaign:{registrantRef}` holds member = lower-cased VPA,
score = request timestamp. `src/main/resources/redis/campaign_add_and_count.lua` runs **atomically** in Redis:
1. remove members older than `timestamp - 60 min`
2. add the VPA (a repeated VPA keeps its latest timestamp, so the distinct count is unchanged)
3. count members with score ≥ `timestamp - 60 min`
4. `PEXPIRE` the key by the window, so idle registrants leave no keys behind

If the count is ≥ 3, `CAMPAIGN_BURST:<n> distinct flagged VPAs in 60 min` is added and the score gets +30 (configurable),
which can move REVIEW to BLOCK. A replayed `requestId` returns the stored response before this step, so it never counts
twice. The count has no upper bound on the timestamp, so concurrent requests whose timestamps arrive out of order still
see every addition made before them. This is what makes "exactly 18 of 20" deterministic.

Settings (`application.yml`): `doppel.scores.campaign-burst: 30`, `doppel.campaign.window: 60m`,
`doppel.campaign.min-distinct-vpas: 3`, `doppel.screening-cache-ttl: 24h`, `doppel.store: redis|memory`.

### Screen a payee
The response contains `"cached": false|true` (see *Caching*).
```bash
curl -X POST localhost:8080/api/v1/payee-screenings -H 'Content-Type: application/json' -d '{
  "requestId": "req-001",
  "vpa": "zentrabank.refund@okaxis",
  "displayName": "Zentra Bank Refunds",
  "registrantAccount": "123456789012",
  "timestamp": "2026-11-14T12:00:00Z"
}'
```
```json
{"requestId":"req-001","vpa":"zentrabank.refund@okaxis","decision":"BLOCK","score":70,
 "matchedBrandId":1,"matchedBrandName":"Zentra Bank",
 "reasons":["BRAND_EXACT:zentrabank","LURE_KEYWORD:refund","LURE_KEYWORD:refunds"],
 "maskedAccount":"XXXXXXXX9012","screenedAt":"2026-11-14T07:09:03.879863Z"}
```

Validation (HTTP 400 with field-level errors; rejected values are never echoed back):
- `vpa`: `^[a-zA-Z0-9.\-_]{2,256}@[a-zA-Z]{2,64}$` (see *Assumptions*)
- `displayName`: 1–100 characters
- `registrantAccount`: 9–18 digits
- `requestId` (≤ 64 chars) and `timestamp` are required

```json
{"status":400,"error":"VALIDATION_FAILED","message":"Request validation failed",
 "fieldErrors":[{"field":"registrantAccount","message":"must be 9-18 digits"}]}
```

Repeating a `requestId` returns the **original** stored response (even if the payload differs) and never
creates a second screening or alert. This is enforced in code and by `UNIQUE(request_id)` constraints.

## Architecture

```
controller (HTTP only) → service → repository (Spring Data JPA) → PostgreSQL (Flyway schema)
```

| Package | Contents |
|---|---|
| `brand` | `Brand` entity (tokens + official VPAs as element collections), repository, service, controller |
| `matching` | `MatchingService` (scoring), `TextNormalizer`, `OsaDistance`, `Decision`, `MatchResult` |
| `screening` | `ScreeningController`, `ScreeningService` (idempotency, persistence, alert creation), DTOs, entity |
| `alert` | `Alert` entity, repository, service, controller, paged response DTO |
| `common` | `AccountProtector` (masking + HMAC-SHA256), `GlobalExceptionHandler` |
| `config` | `DoppelProperties` (scores, thresholds, lure words, HMAC secret from `application.yml`), `Clock` bean |

### Matching rules (`MatchingService`)
1. **Official VPA** of any brand (case-insensitive, NFKC) → `ALLOW`, score 0, reason `OFFICIAL_VPA`.
2. Text checked: the VPA handle (part before `@`) and the display name.
3. Normalization: NFKC → lowercase → remove special characters (exact matching).
   *Skeleton* for homoglyphs additionally folds Cyrillic/Greek look-alikes (upper-case folded before
   lower-casing), digits (`0→o 1→l 3→e 4→a 5→s 7→t 8→b 9→g`), `rn→m` and `vv→w`.
4. Per brand, only the strongest signal counts:
   - `BRAND_EXACT` (+50): compact text contains a brand token.
   - `HOMOGLYPH` (+60): only the skeleton contains the token.
   - `TYPOSQUAT` (+40): a word (also tried with lure words stripped, e.g. `zentrbankrefund` → `zentrbank`)
     is within OSA (Damerau-Levenshtein) distance of a token: < 6 chars none, 6–9 chars ≤ 1, 10+ chars ≤ 2.
5. Highest-scoring brand wins; ties → lowest brand id.
6. `LURE_KEYWORD` (+20, once) only if a brand signal matched.
7. Score ≥ 70 → `BLOCK`, ≥ 40 → `REVIEW`, else `ALLOW`.

Scores, thresholds and lure words are in `src/main/resources/application.yml` under `doppel:`.

### Sensitive data
- The full registrant account is **never stored or logged**. Only `XXXXXXXX9012` (last 4 digits) and an
  HMAC-SHA256 reference keyed by `DOPPEL_HMAC_SECRET` are persisted.
- `ScreeningRequest.toString()` redacts the account; error responses do not echo rejected values.
- A test asserts that the account is absent from API responses, every DB column and the captured logs.

## Database setup

Flyway migrations run on startup:
- `V1__create_tables.sql`: `brand`, `brand_token`, `brand_vpa`, `screening`, `alert` (unique `request_id`).
- `V2__seed_brands.sql`: 17 fictional brands, including the mandatory Zentra Bank, ShopKart, Electra Power.
- `V3__widen_vpa_columns.sql`: widens `vpa` columns to 321 chars, the maximum VPA length the regex allows.
- `V4__brand_id_sequence.sql`: `brand_id_seq` (starts at 100) for brands added via the API.
- `V5__brand_registry_version.sql`: `brand_registry_version`, bumped with every registry change.
- `V6__screening_cached_flag.sql`: `screening.cached`, so replays return an identical body.

```bash
createuser doppel --pwprompt
createdb -O doppel doppel
```

## How to run

```bash
docker compose up -d                 # PostgreSQL 16 (localhost:5432) + Redis 7 (localhost:6379)
cp .env.example .env                 # then edit values (never commit .env)
set -a; source .env; set +a
mvn spring-boot:run
# or: mvn clean package && java -jar target/project-doppel-0.0.1-SNAPSHOT.jar
```

| Env var | Default | Purpose |
|---|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5432/doppel` | JDBC URL |
| `DB_USERNAME` / `DB_PASSWORD` | `doppel` / *(empty)* | DB credentials (compose uses password `doppel`) |
| `DB_POOL_SIZE` | `20` | Hikari pool size |
| `REDIS_HOST` / `REDIS_PORT` | `localhost` / `6379` | Redis |
| `DOPPEL_STORE` | `redis` | `memory` runs without Redis (single instance, testing only) |
| `DOPPEL_HMAC_SECRET` | dev-only placeholder | **Must** be set in any real environment |
| `SERVER_PORT` | `8080` | HTTP port |

## How to test

```bash
mvn clean test
```
Tests run fully offline against in-memory **H2 in PostgreSQL mode** (same Flyway migrations) and the in-memory
cache/campaign implementations. Docker, PostgreSQL and Redis are not needed. `RedisIntegrationTest` uses a
Testcontainers Redis and is **skipped automatically when Docker is not running**. To run it against an existing Redis
instead: `DOPPEL_TEST_REDIS_HOST=localhost DOPPEL_TEST_REDIS_PORT=6379 mvn test -Dtest=RedisIntegrationTest`
(it deletes `doppel:*` keys, so point it at a disposable Redis).

| Test class | Covers |
|---|---|
| `MatchingServiceTest` | mandatory brands, official handles, exact, homoglyph (digits/Cyrillic/Greek/fullwidth/rn/vv), typo budgets, lure rules, per-brand max, tie-break, thresholds |
| `TextNormalizerTest`, `OsaDistanceTest` | normalization and edit distance |
| `AccountProtectorTest` | masking, HMAC-SHA256 (well-known test vector), keying |
| `ScreeningServiceTest` (Mockito) | alert only for REVIEW/BLOCK, duplicate requestId, unique-constraint race, no full account persisted |
| `ScreeningApiIntegrationTest` | REST endpoints, 400 field errors, idempotency (incl. 16 concurrent duplicate requests), alert filters/pagination/order, max-length VPA, offset timestamps, no account leakage (responses, DB, logs) |
| `BrandApiIntegrationTest` | POST brand: 201, normalisation, 400 field errors, 409 duplicate name/VPA, next screening uses new brand, screening served from cache |
| `ScreeningCacheAndCampaignTest` | in-memory store: `cached` false→true with same decision, `quickloan.help@ybl` ALLOW → POST brand → REVIEW + BRAND_EXACT, CAMPAIGN_BURST on 3rd distinct VPA, 60-min window driven by timestamps, replay does not count |
| `RedisIntegrationTest` | **`integration` profile, Testcontainers Redis**: 20 concurrent distinct flagged VPAs → exactly 18 CAMPAIGN_BURST, re-screen/replay keep distinct count 20, key TTL, window expiry, cache TTL 24 h, quickloan flow |
| `ScreeningRequestTest` | account redacted in `toString()` |
| `LabelledHandlesEvaluationTest` | runs `src/test/resources/labelled_handles.csv` and prints confusion matrix / precision / recall / per-category accuracy |

### Running the suite against real PostgreSQL (optional)
```bash
createdb doppel_test
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/doppel_test SPRING_DATASOURCE_USERNAME=doppel \
SPRING_DATASOURCE_PASSWORD=... SPRING_DATASOURCE_DRIVER_CLASS_NAME=org.postgresql.Driver mvn clean test
```
All tests also pass on PostgreSQL 14.

## Evaluation results

`labelled_handles.csv`: 137 synthetic rows in 8 categories. Positive = `SUSPICIOUS`, predicted positive = `REVIEW` or `BLOCK`.
Output of `LabelledHandlesEvaluationTest` (computed at test time, not hard-coded):

```
                      predicted SUSPICIOUS   predicted BENIGN
  actual SUSPICIOUS   TP = 75                FN = 0
  actual BENIGN       FP = 2                 TN = 60

Precision: 0.9740   Recall: 1.0000   F1: 0.9868   Accuracy: 0.9854

Per-category accuracy:
  BRAND_EXACT       22/22   1.0000
  HOMOGLYPH         20/20   1.0000
  LEGITIMATE        20/20   1.0000
  LURE_COMBO        15/15   1.0000
  LURE_ONLY         10/10   1.0000
  NEAR_MISS         14/16   0.8750
  OFFICIAL_HANDLE   16/16   1.0000
  TYPOSQUAT         18/18   1.0000

Misclassified rows (2):
  FP  medicare.clinic@ybl   Medicare Clinic   -> REVIEW 60 [TYPOSQUAT:medicore(distance=1), LURE_KEYWORD:care]
  FP  electra.sharma@ybl    Electra Sharma    -> REVIEW 50 [BRAND_EXACT:electra]
```
Acceptance: precision ≥ 0.90 ✅, recall ≥ 0.85 ✅.

**Caveat:** the dataset and the matcher were written by the same author, so these numbers are optimistic.
The two false positives show real weaknesses: short dictionary-like tokens (`electra`) and common words
that are one edit away from a brand (`medicare`/`medicore`).

### Additional testing (outside the CSV)
- **Benign corpus**: all 235,976 words in macOS `/usr/share/dict/words` and 1,308 proper names were screened
  as payees. 8 words (0.0034%) and 0 names were flagged: `electra*` (4 words), `electro`, `electropower`,
  `intrust`, `mediocre`. All are 1 edit from, or contain, a brand token.
- **Fresh evasion probes** (30 cases: separators, zero-width chars, fullwidth/circled/math-bold letters,
  accents, dotless i, mixed-script, leetspeak). All were detected except `f1ntrvst` and `qu1ckra1l`: `1` is folded
  to `l` only, so a `1`-for-`i` plus one more edit exceeds the 1-edit budget for 6–9 char tokens.
- **Coverage** (JaCoCo): 96.6% lines, 84.4% branches.

## Load test (Task 2)

`scripts/load.py` (Python 3, standard library only) adds 500 synthetic brands, then sends 2,000 screenings over
400 distinct (VPA, display name) pairs from 100 registrants at concurrency 20. It prints latency percentiles (overall and
split by cache hit/miss), decision counts, cache-hit rate and CAMPAIGN_BURST count.

```bash
docker compose up -d && mvn spring-boot:run      # in one terminal
python3 scripts/load.py                          # in another (--base-url http://localhost:8080)
```

**Results on my machine** (Apple M1, 8 cores, 8 GB; client, app, PostgreSQL and Redis all on the same laptop).
⚠️ Docker was not running on this machine, so these runs used **natively installed PostgreSQL 14 and Redis 8**
instead of the compose containers. Rerun with `docker compose` for the official numbers.

Run 1: fresh JVM, empty database (exactly the scenario the script describes):
```
Brands: 500/500 created in 0.7s (other statuses: {})
Screenings: 2000 sent, 2000 OK, errors 0 | 400 distinct pairs, 100 registrants, concurrency 20
Throughput: 909 req/s over 2.2s
Latency ms: p50=11.7  p95=58.0  p99=167.7  mean=21.9  max=466.8
  cache hits   n=1600  p50=9.7  p95=36.2  p99=90.6
  cache misses n=400   p50=31.2  p95=164.2  p99=459.3
Decisions: ALLOW=1337  REVIEW=61  BLOCK=602
Cache-hit rate: 80.0% (1600/2000)   CAMPAIGN_BURST: 465
```
Server-side (Tomcat access log): p50 11 ms, p95 55 ms, p99 165 ms. That matches the client, so the Python client
is not distorting the numbers.

Same app process, later runs (JVM warm; each run adds 500 more brands, so 1,017 then 1,517 brands):
```
Run 2:  p50=8.8  p95=59.9  p99=136.3   (1,270 req/s)
Run 3:  p50=6.1  p95=47.5  p99=133.9   (1,509 req/s)
```

**Target p95 < 50 ms: missed on a cold JVM (55–58 ms; 121 ms on the very first run, before the fixes below),
met once warm (47.5 ms).** Where the time goes:
- **Cold start dominates.** The first 400 requests are all cache misses on a JVM whose hot paths are not yet
  JIT-compiled. Their server-side p95 is **153 ms**; the remaining 1,600 requests have p95 **34 ms**. Cache hits
  overall: p50 ≈ 10 ms.
- **Cache misses** cost matching against ~517 brands. After the fixes below this is ≈ 0.65 ms of CPU when warm; the rest
  is the database round-trips every request makes anyway.
- **Every request** still does about 4–5 network round-trips: duplicate lookup, registry version, Redis GET,
  Lua EVAL for flagged payees, then INSERT screening (+ alert) in one transaction. With 20 concurrent clients on one
  laptop, CPU contention between client, JVM, PostgreSQL and Redis adds tail latency (p99).

Fixes made while measuring (first run p95 was **167 ms**):
1. Loading the brand list took **1,035 SQL queries** (one per brand collection). `hibernate.default_batch_fetch_size: 100`
   reduces that to ~13.
2. All 20 threads missed the brand-list cache at once and each loaded it. `@Cacheable(sync = true)` → one loader.
3. `MatchingService` normalised the texts once per brand. Now it does so once per request, with precompiled regexes.
   That cut a cache miss from **3.8 ms to 0.65 ms** at 517 brands.

Next steps if the cold p95 must be < 50 ms: pre-load the brand list and warm the matcher at startup, and keep the
registry version in memory with a short-lived check (trading one DB round-trip per request).

## Scaling the matcher to 10,000 brands
A cache miss is currently O(brands × tokens × segments): fine at 500–1,500 brands (< 1 ms warm), but by linear extrapolation roughly 12 ms per miss at
10,000. To scale: (1) precompute each brand's exact/folded token forms once per registry version instead of per
request; (2) find BRAND_EXACT/HOMOGLYPH candidates with an **Aho-Corasick automaton** over all tokens (one pass over the
text, independent of the brand count); (3) find TYPOSQUAT candidates with a **deletion index (SymSpell)** or a BK-tree
keyed by token length, so only tokens within 1–2 edits are compared; (4) rebuild these structures when the registry
version changes. The Redis screening cache already removes repeat work for popular handles.

## Known limitations
- Short tokens that are real words or names (`electra`) and words 1 edit from a token (`medicare`,
  `electro`, `mediocre`) produce false positives at REVIEW level.
- `1` is mapped to `l` only (see above).
- A whitespace-only `displayName` is accepted (it is 1–100 characters). Add `@NotBlank` if it should be rejected.
- The brand-list cache is per instance, but it is keyed by the shared DB registry version, so instances never serve
  an old registry. They only each load the list once per version.
- `doppel.store=memory` is for tests only: campaign counts and cache are per JVM.
- If Redis is down, the screening cache degrades to "always miss", but campaign detection fails the request (500)
  rather than silently skipping the check.
- A concurrent duplicate that loses the race can cause Hibernate to log a unique-constraint ERROR line (the account is never part of the SQL) before
  the original response is returned.

## Assumptions
- **VPA regex**: the assignment text was not available while implementing. The widely used UPI pattern
  `^[a-zA-Z0-9.\-_]{2,256}@[a-zA-Z]{2,64}$` is used (`ScreeningRequest.VPA_REGEX`). Because it is
  ASCII-only, Cyrillic/Greek homoglyphs can only arrive through the display name. VPAs can still carry
  digit and `rn`/`vv` tricks.
- The Cyrillic/Greek and digit substitution tables are common confusables (`TextNormalizer`). Adjust them if
  the assignment lists different ones. `1` maps to `l` only, so `quickra1l` is caught as a typosquat rather
  than a homoglyph.
- Duplicate `requestId` returns HTTP 200 with the original body.
- Alert `status` is optional. Without it, all statuses are returned.
