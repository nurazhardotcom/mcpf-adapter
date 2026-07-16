# Changelog

All notable changes to **mcpf-adapter** will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [1.0.0] - 2026-07-16

### Added
- `LICENSE` file (verbatim MIT) crediting Nur Azhar 2026
- `VERSION` file as single source of truth for semver
- `CHANGELOG.md` documenting release history for hiring managers and recruiters
- Production-release badge in `README.md` linking to GitLab Releases page
- Production-release stamp line crediting Nur Azhar

## [0.1.0] - 2025 (pre-tagged snapshot)

### Origin
- Single-file Babashka CLI (`cli.bb`) bridging `api.mycareersfuture.gov.sg/v2` with vendored `data-toolkit` AI job-search toolkit
- Five subcommands: `test | scrape | emit | status | clear`
- Cache-first HTTP, SHA-256 dedup, processing-ID TSV
- `config.edn` with default queries, page-size, sleep-ms, browser User-Agent
- `portals.snippet.yml` for data-toolkit wire-in (gitignored upstream)
- Offline `run-self-test` regression guard built into `cli.bb`
- Upstream-isolation guarantee: never writes to tracked files in `/home/nurazhar/Buffy/data-toolkit/`

[Unreleased]: https://gitlab.com/nurazhar/mcpf-adapter/-/compare/v1.0.0...main
[1.0.0]: https://gitlab.com/nurazhar/mcpf-adapter/-/releases/v1.0.0
[0.1.0]: https://gitlab.com/nurazhar/mcpf-adapter/-/releases/permalink?inline=true
