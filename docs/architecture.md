# Architecture

## Overview

The Canton Conformance Kit is a Scala + sbt CLI tool that performs live environment qualification against Canton Network deployments. It loads scenario definitions, executes HTTP-based health and readiness checks, evaluates a set of invariants, and produces machine-readable (JSON) and human-readable (Markdown) conformance reports.

## Components

```
┌──────────────────────────────────────────────────────────┐
│                      CLI Entrypoint                       │
│                       (Main.scala)                        │
│  Parses arguments, orchestrates the pipeline              │
└──────────────┬───────────────────────────────┬────────────┘
               │                               │
               ▼                               ▼
┌──────────────────────────┐   ┌──────────────────────────────┐
│     ScenarioLoader       │   │      EndpointChecker          │
│  Reads YAML scenario     │   │  HTTP checks with retry       │
│  definitions             │   │  and timeout handling          │
└──────────────────────────┘   └──────────────┬───────────────┘
                                              │
                                              ▼
                               ┌──────────────────────────────┐
                               │    InvariantEvaluator         │
                               │  Evaluates pass/fail for      │
                               │  each declared invariant       │
                               └──────────────┬───────────────┘
                                              │
                                              ▼
                               ┌──────────────────────────────┐
                               │       ReportWriter            │
                               │  JSON + Markdown output        │
                               └──────────────────────────────┘
```

## Pipeline

1. **Load** — `ScenarioLoader` reads a YAML scenario file containing endpoint definitions and invariant declarations.

2. **Check** — `EndpointChecker` makes real HTTP requests to each declared endpoint, with configurable timeout and retry logic. Each check produces an `EndpointResult` with timing, status, and error information.

3. **Evaluate** — `InvariantEvaluator` applies the scenario's invariants against the collected results. Each invariant has a named check type (e.g., `all_endpoints_reachable`, `all_readiness_pass`) that maps to specific evaluation logic.

4. **Report** — `ReportWriter` serializes the full results into both a structured JSON document and a human-readable Markdown document.

## Invariant Catalog

| ID | Check | Description |
|----|-------|-------------|
| INV-001 | `all_endpoints_reachable` | All required endpoints must respond to HTTP requests |
| INV-002 | `all_readiness_pass` | All readiness-category endpoints must return their expected status code |
| INV-003 | `deterministic_results` | Each endpoint produces exactly one result with a timestamp |
| INV-004 | `failures_identify_endpoint` | Any failures include the endpoint name, URL, and error detail |
| INV-005 | `report_reflects_run` | Reports are generated from actual observed results, not templates |

## Dependencies

| Library | Purpose |
|---------|---------|
| scopt | CLI argument parsing |
| sttp client3 | HTTP client for endpoint checks |
| circe | JSON serialization for reports |
| SnakeYAML | YAML parsing for scenario files |
| ScalaTest | Unit testing |

## Live Demo Infrastructure

The `demo/run-live-demo.sh` script orchestrates the full live execution:

1. Clones `digital-asset/cn-quickstart` (pinned to a ref)
2. Starts the local Canton network via the Quickstart's Make workflow
3. Polls readiness endpoints until healthy
4. Invokes the Scala harness via `sbt run`
5. Writes reports to `reports/latest/`

This integrates with the real CN Quickstart Docker Compose environment, which runs validator nodes, participant nodes, and supporting services.

## Design Principles

- **No fakes**: every check hits a real HTTP endpoint
- **Minimal scope**: five invariants, not fifty
- **Observable**: all results are captured with timing and error detail
- **Reproducible**: pinned upstream refs, deterministic scenario files
- **Extensible**: new invariant types and endpoint categories can be added by extending the evaluator
