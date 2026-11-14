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

### Brand cache
Screening reads the registry through `BrandService.findAll()`, which is `@Cacheable("brands")`. It uses Spring's built-in
in-memory cache (`spring-boot-starter-cache` → `ConcurrentMapCacheManager`, no Redis), so the database is read once,
not on every screening. `POST /api/v1/brands` evicts the cache **after** its insert commits, so the next screening
reloads the registry. Limits: the cache is per application instance (with several instances, others would keep a
stale copy until restarted), and rows inserted directly in SQL are not seen until the cache is cleared.

### Screen a payee
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

```bash
createuser doppel --pwprompt
createdb -O doppel doppel
```

## How to run

```bash
cp .env.example .env             # then edit values (never commit .env)
set -a; source .env; set +a
mvn spring-boot:run
# or: mvn clean package && java -jar target/project-doppel-0.0.1-SNAPSHOT.jar
```

| Env var | Default | Purpose |
|---|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5432/doppel` | JDBC URL |
| `DB_USERNAME` | `doppel` | DB user |
| `DB_PASSWORD` | *(empty)* | DB password |
| `DOPPEL_HMAC_SECRET` | dev-only placeholder | **Must** be set in any real environment |
| `SERVER_PORT` | `8080` | HTTP port |

## How to test

```bash
mvn clean test
```
Tests run fully offline against in-memory **H2 in PostgreSQL mode**, using the same Flyway migrations.
Docker and PostgreSQL are not needed.

| Test class | Covers |
|---|---|
| `MatchingServiceTest` | mandatory brands, official handles, exact, homoglyph (digits/Cyrillic/Greek/fullwidth/rn/vv), typo budgets, lure rules, per-brand max, tie-break, thresholds |
| `TextNormalizerTest`, `OsaDistanceTest` | normalization and edit distance |
| `AccountProtectorTest` | masking, HMAC-SHA256 (well-known test vector), keying |
| `ScreeningServiceTest` (Mockito) | alert only for REVIEW/BLOCK, duplicate requestId, unique-constraint race, no full account persisted |
| `ScreeningApiIntegrationTest` | REST endpoints, 400 field errors, idempotency (incl. 16 concurrent duplicate requests), alert filters/pagination/order, max-length VPA, offset timestamps, no account leakage (responses, DB, logs) |
| `BrandApiIntegrationTest` | POST brand: 201, normalisation, 400 field errors, 409 duplicate name/VPA, next screening uses new brand, screening served from cache |
| `ScreeningRequestTest` | account redacted in `toString()` |
| `LabelledHandlesEvaluationTest` | runs `src/test/resources/labelled_handles.csv` and prints confusion matrix / precision / recall / per-category accuracy |

### Running the suite against real PostgreSQL (optional)
```bash
createdb doppel_test
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/doppel_test SPRING_DATASOURCE_USERNAME=doppel \
SPRING_DATASOURCE_PASSWORD=... SPRING_DATASOURCE_DRIVER_CLASS_NAME=org.postgresql.Driver mvn clean test
```
All 76 tests also pass on PostgreSQL 14.

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

## Known limitations
- Short tokens that are real words or names (`electra`) and words 1 edit from a token (`medicare`,
  `electro`, `mediocre`) produce false positives at REVIEW level.
- `1` is mapped to `l` only (see above).
- A whitespace-only `displayName` is accepted (it is 1–100 characters). Add `@NotBlank` if it should be rejected.
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
