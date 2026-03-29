# Canton Upgrade & Conformance Kit — Live Demo

A Scala + sbt native toolkit for **upgrade safety, version compatibility testing, and reproducible conformance reporting** for Canton deployments and reference applications.

This repository is a working proof-of-execution demo that runs live environment qualification against a real Canton validator network using pre-built Docker images.

## What This Demo Proves

This demo executes a real conformance scenario against live Canton services — not mocks, not fakes, not placeholder output. Specifically, it:

1. **Starts a real Canton validator network** (Docker Compose with pre-built Canton/Splice images)
2. **Runs a Scala CLI harness** that loads a YAML scenario, makes real HTTP requests to validator readiness/admin endpoints, and evaluates five invariants
3. **Generates real conformance reports** (JSON + Markdown) reflecting the actual observed run
4. **Exits nonzero on failure**, with reports identifying exactly which endpoints/checks failed

## Why the Demo Is Real

- The conformance harness makes actual HTTP requests to live services
- Endpoint results include real HTTP status codes, response times, and error messages
- Reports are generated from observed data — there are no static templates or hardcoded success messages
- The full live demo starts real Canton validator nodes via Docker Compose using pre-built images from `ghcr.io/digital-asset/decentralized-canton-sync`
- Unit tests validate that the harness correctly detects and reports failures (every invariant has pass and fail counter cases)

## Canton Infrastructure

The conformance kit includes its own minimal Docker Compose setup (`infra/`) that pulls pre-built Canton images directly — no external dependencies to clone or build.

| Image | Registry | Version |
|-------|----------|---------|
| `canton` | `ghcr.io/digital-asset/decentralized-canton-sync/docker/` | `0.5.3` |
| `splice-app` | `ghcr.io/digital-asset/decentralized-canton-sync/docker/` | `0.5.3` |
| `postgres` | Docker Hub | `14` |

Version is pinned in `infra/.env` and overridable via CI manual dispatch for version compatibility testing.

## Prerequisites

### For the full live demo

| Requirement | Notes |
|-------------|-------|
| **Docker Desktop** | 8+ GB RAM allocated to Docker |
| **JDK 21+** | Eclipse Temurin recommended |
| **sbt** | 1.9+ |
| **curl** | For health polling |
| **bash 4+** | Default on Linux; install via Homebrew on macOS |
| ~5 GB disk | For Docker images on first run |

### For compile + test only

| Requirement | Notes |
|-------------|-------|
| **JDK 21+** | Eclipse Temurin recommended |
| **sbt** | 1.9+ |

## Quick Start

### Compile and test (no Docker needed)

```bash
sbt compile
sbt test
```

### Full live demo

```bash
./demo/run-live-demo.sh
```

This single command will:

1. Start the Canton validator network (Docker Compose)
2. Wait for all validator endpoints to become healthy
3. Run the Scala conformance harness
4. Generate JSON + Markdown reports to `reports/latest/`
5. Print a summary and exit 0 (pass) or nonzero (fail)

### Run the harness directly (against an already-running environment)

```bash
sbt "run --scenario demo/scenarios/localnet-readiness.yaml --output reports/latest --verbose"
```

## Expected Runtime

| Phase | Time |
|-------|------|
| sbt compile (cold) | ~60s |
| Docker image pull (first run) | 3–8 min |
| Canton network start (warm) | ~30s |
| Readiness polling | 30s – 2 min |
| Conformance harness execution | ~10s |
| **Total (warm)** | **~2–3 min** |
| **Total (cold, first run)** | **~5–10 min** |

## Output

Reports are written to `reports/latest/`:

```
reports/latest/
  conformance-report.json    # Machine-readable structured report
  conformance-report.md      # Human-readable Markdown report
```

### Example console output

```
============================================================
 Canton Conformance Kit — Live Environment Qualification
============================================================

[1/4] Loading scenario: demo/scenarios/localnet-readiness.yaml
  Scenario: localnet-readiness-qualification
  Endpoints: 5
  Invariants: 5

[2/4] Executing endpoint checks...
  [✓] validator-1-readiness: 200 (45ms, attempt 1)
  [✓] validator-2-readiness: 200 (38ms, attempt 1)
  [✓] validator-3-readiness: 200 (41ms, attempt 1)
  [✓] validator-1-admin: 200 (52ms, attempt 1)
  [✓] validator-2-admin: 200 (48ms, attempt 1)

[3/4] Evaluating invariants...
  [PASS] INV-001: All required validator/admin endpoints must be reachable
  [PASS] INV-002: All target validator readiness checks must pass
  [PASS] INV-003: Scenario execution must emit deterministic machine-readable results
  [PASS] INV-004: Failures must identify which endpoint/check failed
  [PASS] INV-005: Report generation must reflect the actual observed run

[4/4] Generating reports to reports/latest/

============================================================
 CONFORMANCE RESULT: PASS (5/5 invariants)
============================================================
```

## Invariant Catalog

| ID | Check | Description |
|----|-------|-------------|
| INV-001 | `all_endpoints_reachable` | All required endpoints must respond to HTTP requests |
| INV-002 | `all_readiness_pass` | All readiness endpoints must return their expected status code |
| INV-003 | `deterministic_results` | Each endpoint has exactly one timestamped result |
| INV-004 | `failures_identify_endpoint` | Any failure identifies the endpoint name, URL, and error |
| INV-005 | `report_reflects_run` | Reports generated from actual observed data, not templates |

## CI Pipeline

The project uses a two-tier CI model following blockchain protocol best practices (see [docs/ci-strategy.md](docs/ci-strategy.md) for full justification with protocol precedents).

| Tier | What | When | Runtime | Local command |
|------|------|------|---------|---------------|
| **Tier 1: Code Quality** | Format, compile (warnings-as-errors), unit tests, assembly | Every PR and push to `main` | ~2 min | `make ci` |
| **Tier 2: Live Conformance** | Spin up Canton network, run conformance harness, validate 5 invariants | Nightly, release tags, manual dispatch | ~25 min | `make integration` |

This mirrors how Ethereum (Hive), Cosmos (interchaintest), Avalanche (ANR), and Hyperledger Fabric separate fast code-quality CI from slower live-network conformance testing.

```bash
# Tier 1: Code quality checks (no Docker needed)
make ci

# Tier 2: Full integration with live Canton network (requires Docker)
make integration

# Tier 2: Harness only (Canton network already running)
make integration-harness
```

The integration workflow supports testing against any Canton version via manual dispatch with a configurable `canton_version` input — enabling version compatibility validation similar to Avalanche's binary-swap E2E tests.

### Conformance Attestation

On successful Tier 2 runs, the workflow produces a **signed conformance attestation** (SLSA Build Level 2) using the [in-toto test-result predicate](https://github.com/in-toto/attestation/blob/main/spec/predicates/test-result.md) and GitHub's keyless Sigstore signing. Both the conformance report and the fat JAR are attested as subjects.

- **No keys to manage** — ephemeral keys are minted per workflow run via GitHub OIDC and destroyed immediately
- **Tamper-evident** — attestations are bound to the specific repo, workflow, commit, and run ID
- **Publicly verifiable** — signing events are recorded in Sigstore's transparency log

```bash
# Verify a conformance report attestation
gh attestation verify conformance-report.json \
  --repo merged-one/canton-upgrade-conformance-kit-demo \
  --predicate-type 'https://in-toto.io/attestation/test-result/v0.1'
```

See [docs/ci-strategy.md](docs/ci-strategy.md) for full details on the attestation approach and security properties.

## Testing

```bash
sbt test
```

23 unit tests across 3 suites:

| Suite | Tests | Coverage |
|-------|-------|----------|
| `ScenarioLoaderSpec` | 5 | YAML parsing, endpoint/invariant extraction, error handling, defaults |
| `InvariantEvaluatorSpec` | 16 | Every invariant (INV-001 through INV-005) tested with both pass and fail inputs |
| `ReportWriterSpec` | 2 | JSON validity and Markdown structure |

Every invariant has counter-case tests that verify the harness correctly detects and reports failures — duplicate endpoints, unreachable services, missing readiness endpoints, unidentified failures, and empty result sets.

## Repository Structure

```
.
├── .github/workflows/
│   ├── ci.yml                         # Tier 1: code quality (PRs, push to main)
│   └── integration.yml                # Tier 2: live network conformance (nightly, releases, manual)
├── .scalafmt.conf                     # Scalafmt configuration
├── Makefile                           # Build targets (ci, integration, fmt, test, assembly, etc.)
├── build.sbt                          # Scala/sbt project definition
├── infra/
│   ├── .env                           # Canton version pin and network config
│   ├── compose.yaml                   # Minimal Docker Compose (postgres, canton, splice)
│   └── conf/                          # Canton/Splice HOCON configs and health checks
├── project/
│   ├── build.properties               # sbt version pin
│   └── plugins.sbt                    # sbt-assembly + sbt-scalafmt plugins
├── src/
│   ├── main/scala/com/digitalasset/conformance/
│   │   ├── Main.scala                 # CLI entrypoint
│   │   ├── Models.scala               # Data types
│   │   ├── ScenarioLoader.scala       # YAML scenario parser
│   │   ├── EndpointChecker.scala      # HTTP checks with retry
│   │   ├── InvariantEvaluator.scala   # Invariant evaluation logic
│   │   └── ReportWriter.scala         # JSON + Markdown report generation
│   └── test/scala/com/digitalasset/conformance/
│       ├── ScenarioLoaderSpec.scala    # Scenario loading tests
│       ├── InvariantEvaluatorSpec.scala # Invariant logic tests
│       └── ReportWriterSpec.scala      # Report generation tests
├── demo/
│   ├── run-live-demo.sh               # One-command live demo orchestrator
│   └── scenarios/
│       └── localnet-readiness.yaml    # Scenario definition
├── reports/                            # Generated reports (gitignored)
├── docs/
│   ├── architecture.md                # Architecture documentation
│   └── ci-strategy.md                 # CI strategy with protocol precedents
├── .gitignore
├── LICENSE                             # Apache 2.0
└── README.md
```

## Current Scope and Non-Goals

### In scope (this demo)

- Live readiness/health qualification of a running Canton environment
- YAML-driven scenario definitions
- HTTP-based endpoint checking with retry/timeout
- Five concrete invariants with pass/fail evaluation
- Machine-readable (JSON) and human-readable (Markdown) report generation
- One-command live demo script

### Non-goals (future work)

- Daml script execution or ledger API interaction
- Multi-version upgrade testing
- Version compatibility matrix evaluation
- Daml model compilation or deployment
- Web UI or dashboard
- Long-running monitoring or alerting

## Mapping to the Canton Upgrade & Conformance Kit Proposal

This demo de-risks the following milestones:

| Proposal Milestone | What This Demo Proves |
|--------------------|----------------------|
| **M1: Foundation** | Scala + sbt project structure, CLI entrypoint, scenario-driven architecture, report generation pipeline — all implemented and working |
| **M2: Core Testing** | Live HTTP-based environment qualification, real endpoint checks against Canton services, invariant evaluation framework with five concrete checks |
| **M3: Reporting** | JSON + Markdown report generation from actual observed execution data, not templates |

The demo deliberately stays small to prove the architecture is sound and the execution is real, before scaling up to more ambitious scenarios (multi-version upgrades, Daml model testing, compatibility matrices).

## License

Apache 2.0 — see [LICENSE](LICENSE).
