# mcpf-adapter

[![Latest Release](https://gitlab.com/nurazhar/mcpf-adapter/-/badges/release.svg)](https://gitlab.com/nurazhar/mcpf-adapter/-/releases)
[![license: MIT](https://img.shields.io/badge/license-MIT-blue)](./LICENSE)

Babashka CLI bridging **Singapore MyCareersFuture (MCF) v2 API** — scrape, emit, and cache job listings as structured JSONL for downstream analysis.

## Why this exists

- MCF v2 is the official Singapore government jobs backend (operated by Workforce Singapore / Ministry of Manpower); the only source of FCF-compliant, salary-transparent, government-vetted SG job listings.
- This adapter wraps the internal API into a lightweight zero-dependency CLI — no Python, no Playwright, no Node.js required.

## Files

| File | Purpose |
|---|---|
| `cli.bb` | Single-file Babashka CLI (test / scrape / emit / status / clear) |
| `config.edn` | Default queries, page size, sleep delay, User-Agent |
| `cache/raw/` | SHA-256-keyed raw JSON responses (gitignored) |
| `cache/processed-ids.txt` | Flat TSV of `jobPostId`s already emitted (gitignored) |
| `.gitignore` | Excludes `cache/` |

## Configuration (`config.edn`)

`cli.bb` loads `config.edn` at startup and merges it over the built-in defaults. Any missing key falls back to the default in `cli.bb`.

| Key | Type | Default | Used by | Description |
|---|---|---|---|---|
| `:queries` | vector of strings | see below | external scripts | Default search-query rotation for weekly scans. `cli.bb` does **not** iterate over this list automatically. |
| `:page-size` | int | `50` | `scrape`, `emit` | Listings requested per `/v2/search` page. MCF v2 honors up to ~50. |
| `:default-pages` | int | `5` | `scrape`, `emit` | Pages to walk when `--pages` is omitted. `50 × 5 = 250` listings per query. |
| `:sleep-ms` | int | `3000` | `scrape` | Delay between paginations. Unofficial endpoint — keep it conservative. |
| `:user-agent` | string | Chrome on Linux | `scrape`, `emit`, `test` | Browser-like `User-Agent` header. |
| `:salary-min-sgd` | int | `6000` | downstream only | Advisory monthly-salary floor. **Not enforced by `cli.bb`.** |
| `:postings-per-query-cap` | int | `1000` | downstream only | Advisory per-query cap. **Not enforced by `cli.bb`.** |

### Default query rotation

```edn
:queries
["compliance"
 "risk"
 "governance risk"
 "grc"
 "audit"
 "regulatory compliance"
 "internal control"
 "information security governance"
 "technology risk"
 "operational risk"
 "vendor risk"
 "third party risk"
 "data privacy"
 "data protection"
 "p d p a"
 "business continuity"
 "fraud"
 "sanctions"
 "identity access management"
 "security policy"
 "assurance"]
```

### Per-invocation overrides

Any config key can be overridden on the command line:

```bash
bb cli.bb scrape --query 'compliance' --pages 2 --sleep-ms 5000
```

`--pages` and `--sleep-ms` override `:default-pages` and `:sleep-ms` respectively. `--query` is always required for `scrape` / `emit`.

## Commands

```bash
# Live network probe — no cache write. First thing to run.
bb cli.bb test

# Fetch multiple pages for one query (writes to cache)
bb cli.bb scrape --query 'compliance' --pages 5 --sleep-ms 3000

# Emit JSONL to stdout (one record per line)
bb cli.bb emit --query 'compliance' --pages 5

# Inspect cache state
bb cli.bb status

# Wipe the cache (e.g. before a fresh re-scrape)
bb cli.bb clear
```

## Output schema

The `emit` subcommand prints a single JSON object on stdout with this wire shape:

```json
{
  "results": [
    {
      "title": "Senior Compliance Officer",
      "url":  "https://www.mycareersfuture.gov.sg/job/...",
      "company": "Acme Co",
      "location": "Central 123456",
      "salary_min": 8000,
      "salary_max": 12000,
      "salary_type": "Monthly",
      "salary_annualized_sgd": {"min": 96000, "max": 144000},
      "skills": "Risk Management, Regulatory Compliance",
      "posted_at": "2026-07-10T00:00:00Z",
      "job_post_id": "MCF-2026-XXXXXXX",
      "uuid": "...",
      "uen": "..."
    }
  ]
}
```

`title` and `url` are mandatory; all other fields are optional but preserved for downstream consumers.

## Idempotency / dedup

- Each successful API fetch is cached as `cache/raw/<sha256(query|page)>.json`.
- Each listing emitted to stdout is recorded in `cache/processed-ids.txt`.
- Re-running `scrape` + `emit` is safe: the same `jobPostId` never appears twice.

## Rate-limit discipline

- 1 concurrent in-flight request.
- `Thread/sleep` (default 3000 ms) between paginations.
- Browser-like `User-Agent` (configurable in `config.edn`).
- Network failures logged to stderr, **non-fatal** — the script returns whatever the cache has.

## Caveat: MCF v2 is unofficial

`api.mycareersfuture.gov.sg/v2` is the website's internal backend, not a
published third-party API. It is unauthenticated today, but may rate-limit,
geo-block, or change its schema at any time.

Mitigations baked into this adapter:
- Every successful response is cached.
- Failures degrade gracefully to "emit whatever is cached".
- Schema mapping lives in one function (`transform-listing`); a schema break
  is fixed in one place.

## Daily rhythm

| Time block | What | Where |
|---|---|---|
| Pre-launch smoke | `bb cli.bb test` | any time |
| Weekly refresh | `for q in compliance risk grc audit; do bb cli.bb scrape --query "$q" --pages 3; done` | 30 min run |
| Pipeline drop | `bb cli.bb emit --query 'compliance' --pages 3 > /tmp/mcpf-compliance.jsonl` | 5 min run |

## License

MIT.
