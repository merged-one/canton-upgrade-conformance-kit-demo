# CI Strategy

## Overview

The Canton Conformance Kit uses a two-tier CI model that separates fast code-quality checks from slower live-network conformance tests. This mirrors how major blockchain protocols handle CI — every protocol we surveyed separates unit/lint CI (runs on PRs) from integration/conformance CI (runs nightly or on releases).

## Tier Model

| Tier | Workflow | Triggers | Runtime | What it validates |
|------|----------|----------|---------|-------------------|
| **Tier 1: Code Quality** | `ci.yml` | Push to `main`, PRs | ~2 min | Formatting, compilation (warnings-as-errors), 23 unit tests, fat JAR assembly |
| **Tier 2: Live Conformance** | `integration.yml` | Nightly, release tags (`v*`), manual dispatch | ~25 min | Canton network startup, validator readiness, 5 conformance invariants against live endpoints |

### Why two tiers?

**Tier 1 on every PR** gives developers fast feedback (under 2 minutes) on whether their code compiles, tests pass, and the JAR builds. This is the inner development loop.

**Tier 2 nightly + on releases** validates that the conformance kit works against a real Canton network. Running this on every PR would add 25+ minutes to the feedback loop and consume Docker resources unnecessarily. Nightly runs catch regressions from upstream cn-quickstart changes. Release-tag runs serve as a gate before publishing.

**Manual dispatch** allows ad-hoc testing against any cn-quickstart ref — useful for testing against upcoming Canton versions before they land on `main`.

## Protocol Precedents

This two-tier model is not arbitrary. It reflects the consensus approach across blockchain protocol CI:

| Protocol | Code Quality CI | Live Network CI | Key Tool |
|----------|----------------|-----------------|----------|
| **Ethereum** | Per-client unit tests on PRs | Hive conformance tests run **daily** across all clients | [ethereum/hive](https://github.com/ethereum/hive) |
| **Cosmos SDK / IBC** | Go unit tests on PRs | E2E tests with Docker-based chain orchestration on PRs + nightly | [strangelove-ventures/interchaintest](https://github.com/strangelove-ventures/interchaintest) |
| **Avalanche** | Unit + integration tests on PRs | E2E tests with binary version swaps; Antithesis simulation | [ava-labs/avalanche-network-runner](https://github.com/ava-labs/avalanche-network-runner) |
| **Hyperledger Fabric** | Chaincode unit tests on PRs (~3-4 min) | Performance/regression tests **daily/weekly** | [hyperledger-labs/fablo](https://github.com/hyperledger-labs/fablo) |
| **Canton (Digital Asset)** | sbt compile + test | Automated migration tests; staged DevNet, TestNet, MainNet | [digital-asset/cn-quickstart](https://github.com/digital-asset/cn-quickstart) |

### Key patterns adopted

- **Ethereum Hive**: Daily scheduled runs against live infrastructure, structured machine-readable reports, separate from per-PR unit tests. Our nightly `integration.yml` follows this pattern.
- **Cosmos interchaintest**: Docker-based chain orchestration in CI, parameterized by version ref. Our `workflow_dispatch` with `quickstart_ref` input follows this pattern.
- **Avalanche ANR**: E2E tests that restart nodes with different binary versions to validate upgrade compatibility. Our manual dispatch against different cn-quickstart refs enables the same workflow.
- **Hyperledger Fabric**: Docker Compose as the primary CI orchestration method, with fast chaincode tests on PRs and longer regression suites on schedules.

## Trigger Matrix

| Event | Tier 1 (ci.yml) | Tier 2 (integration.yml) |
|-------|-----------------|--------------------------|
| Push to `main` | Yes | No |
| Pull request to `main` | Yes | No |
| Nightly schedule (02:17 UTC) | No | Yes |
| Release tag (`v*`) | No | Yes |
| Manual dispatch | No | Yes (with configurable `quickstart_ref`) |

## Local Execution

Both tiers can be run locally using Make targets:

```bash
# Tier 1: Code quality (no Docker needed)
make ci

# Tier 2: Full integration (requires Docker with 8+ GB RAM)
make integration

# Tier 2 (harness only): Run against an already-running Canton network
make integration-harness
```

`make ci` runs: format check, compile with fatal warnings, unit tests, assembly.

`make integration` runs the full `demo/run-live-demo.sh` script: clones cn-quickstart, starts Docker Compose network, polls readiness, runs harness, generates reports.

`make integration-harness` skips network lifecycle and runs only the conformance harness — useful when you already have a Canton network running.

## What Each Tier Validates

### Tier 1: Code Quality

| Check | What it catches |
|-------|----------------|
| `make fmt-check` | Inconsistent formatting (Scalafmt) |
| `make compile` | Compiler errors, dead code, deprecation warnings (fatal in CI) |
| `make test` | Logic bugs in scenario loading, invariant evaluation, report generation (23 tests) |
| `make assembly` | Dependency conflicts, merge strategy issues in fat JAR |

### Tier 2: Live Network Conformance

| Check | What it catches |
|-------|----------------|
| Canton network startup | Docker Compose orchestration failures, image pull issues |
| Validator readiness polling | Nodes that fail to initialize within 300s |
| INV-001: All endpoints reachable | Network connectivity issues, port binding failures |
| INV-002: All readiness pass | Validators not fully initialized, service health issues |
| INV-003: Deterministic results | Harness producing duplicate or missing endpoint results |
| INV-004: Failures identify endpoint | Error reporting completeness |
| INV-005: Report reflects run | Report generation integrity |

## Failure Debugging

| Symptom | Where to look | Likely cause |
|---------|---------------|--------------|
| Tier 1 fails on format | `make fmt` to auto-fix | Unformatted code committed |
| Tier 1 fails on compile | Compiler output in Actions log | New warning introduced (fatal in CI) |
| Tier 2 times out on readiness | Docker logs artifact | Canton node failed to start; check container OOM, port conflicts |
| Tier 2 harness fails INV-001 | Conformance report artifact | Endpoint URL changed in cn-quickstart |
| Tier 2 harness fails INV-002 | Conformance report artifact | Validator not fully onboarded; may need longer retry |
| Nightly starts failing | Compare cn-quickstart commit in summary | Upstream breaking change in cn-quickstart |

## Artifacts

| Workflow | Artifact | Retention | Purpose |
|----------|----------|-----------|---------|
| ci.yml | `test-reports/` | Default | JUnit XML test results |
| ci.yml | `canton-conformance-kit.jar` | 14 days | Built fat JAR |
| integration.yml | `conformance-reports-{sha}/` | 30 days | JSON + Markdown conformance reports |
| integration.yml | `docker-logs-{sha}/` (on failure) | Default | Docker container logs for debugging |

## Conformance Attestation

On successful Tier 2 runs, the integration workflow produces a **signed conformance attestation** using the [in-toto test-result predicate](https://github.com/in-toto/attestation/blob/main/spec/predicates/test-result.md) and [GitHub artifact attestations](https://docs.github.com/en/actions/security-for-github-actions/using-artifact-attestations).

### How it works

1. The conformance harness runs and produces `conformance-report.json`
2. A predicate is generated from the report containing pass/fail results per invariant
3. `actions/attest@v4` signs the attestation using **keyless Sigstore signing** via GitHub OIDC
4. The attestation is recorded in a **public transparency log** and stored in GitHub's attestation API
5. Both the conformance report and the fat JAR are attested as subjects

### Security properties

- **SLSA Build Level 2**: Cryptographically signed provenance generated by the build system
- **Keyless**: No private keys to manage, rotate, or protect — ephemeral keys are minted per workflow run and destroyed immediately
- **Tamper-evident**: The attestation is bound to the specific repository, workflow, commit SHA, and run ID via GitHub OIDC claims
- **Transparency**: Signing events are recorded in Sigstore's public Rekor transparency log
- **Unforgeable**: Unlike self-signed certificates, CI-provenance attestations cannot be forged without compromising GitHub's OIDC infrastructure

### What is attested

| Subject | What it proves |
|---------|---------------|
| `conformance-report.json` | This specific report, at this SHA-256 hash, was produced by a CI run that passed all invariants |
| `canton-conformance-kit.jar` | This specific JAR was the tool used to produce the passing conformance results |

### Verification

```bash
gh attestation verify conformance-report.json \
  --repo merged-one/canton-upgrade-conformance-kit-demo \
  --predicate-type 'https://in-toto.io/attestation/test-result/v0.1'
```

### Why not self-signed?

A locally generated certificate with a project-managed key provides minimal assurance — whoever has the key can forge arbitrary results. CI-provenance attestation binds the certificate to a specific GitHub workflow run, making it verifiable by third parties without trusting the project maintainers.

## Future Enhancements

- **Version matrix**: Run integration against multiple cn-quickstart refs in parallel (e.g., `main`, `release/2.x`, `release/3.x`) to build a compatibility matrix
- **Docker image caching**: Use `docker/build-push-action` cache to reduce cold start time
- **GitHub Pages reports**: Publish conformance reports to GitHub Pages for historical tracking
- **Slack/webhook notifications**: Alert on nightly failures
- **Performance trending**: Track endpoint response times across runs to detect regressions
