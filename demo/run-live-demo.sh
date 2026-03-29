#!/usr/bin/env bash
#
# Canton Conformance Kit — Live Demo Runner
#
# This script:
#   1. Starts the Canton validator network via Docker Compose
#   2. Waits for validator services to become healthy
#   3. Runs the Scala conformance harness
#   4. Generates JSON + Markdown reports
#
# Prerequisites:
#   - Docker Desktop (8+ GB RAM allocated)
#   - JDK 21+
#   - sbt
#   - bash 4+
#   - curl
#
# Usage:
#   ./demo/run-live-demo.sh
#
set -euo pipefail

# ─── Configuration ───────────────────────────────────────────────────────────

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
COMPOSE_FILE="${PROJECT_ROOT}/infra/compose.yaml"
COMPOSE_PROFILES="--profile app-provider --profile app-user --profile sv"

REPORT_DIR="${PROJECT_ROOT}/reports/latest"
SCENARIO_FILE="${PROJECT_ROOT}/demo/scenarios/localnet-readiness.yaml"

# Readiness endpoints to poll before running the harness
READINESS_ENDPOINTS=(
  "http://localhost:2903/api/validator/readyz"
  "http://localhost:3903/api/validator/readyz"
  "http://localhost:4903/api/validator/readyz"
)

MAX_WAIT_SECONDS=300
POLL_INTERVAL=10

# ─── Helpers ─────────────────────────────────────────────────────────────────

# Disable color output in CI environments or when NO_COLOR is set
# See https://no-color.org/
if [[ -n "${NO_COLOR:-}" ]] || [[ -n "${CI:-}" ]]; then
  RED=''
  GREEN=''
  YELLOW=''
  CYAN=''
  NC=''
else
  RED='\033[0;31m'
  GREEN='\033[0;32m'
  YELLOW='\033[1;33m'
  CYAN='\033[0;36m'
  NC='\033[0m'
fi

log()   { echo -e "${CYAN}[conformance]${NC} $*"; }
warn()  { echo -e "${YELLOW}[conformance]${NC} $*"; }
err()   { echo -e "${RED}[conformance]${NC} $*" >&2; }
ok()    { echo -e "${GREEN}[conformance]${NC} $*"; }

die() { err "$@"; exit 1; }

check_prereqs() {
  log "Checking prerequisites..."
  local missing=()
  command -v docker   >/dev/null 2>&1 || missing+=("docker")
  command -v java     >/dev/null 2>&1 || missing+=("java (JDK 21+)") || true
  command -v sbt      >/dev/null 2>&1 || missing+=("sbt")
  command -v curl     >/dev/null 2>&1 || missing+=("curl")

  if [ ${#missing[@]} -gt 0 ]; then
    die "Missing prerequisites: ${missing[*]}"
  fi

  if ! docker info >/dev/null 2>&1; then
    die "Docker daemon is not running. Please start Docker Desktop."
  fi

  ok "All prerequisites satisfied."
}

# ─── Step 1: Start the Environment ──────────────────────────────────────────

start_environment() {
  log "Starting Canton validator network..."

  # Check if environment appears to be already running
  local already_running=true
  for ep in "${READINESS_ENDPOINTS[@]}"; do
    if ! curl -sf --max-time 5 "$ep" >/dev/null 2>&1; then
      already_running=false
      break
    fi
  done

  if [ "$already_running" = true ]; then
    ok "Environment appears to already be running. Skipping start."
    return 0
  fi

  log "Starting Docker Compose services..."
  # shellcheck disable=SC2086
  docker compose -f "$COMPOSE_FILE" $COMPOSE_PROFILES up -d || {
    err "Failed to start environment."
    err ""
    err "Make sure Docker Desktop is running with 8+ GB RAM allocated."
    err "Check container logs with: docker compose -f infra/compose.yaml logs"
    exit 1
  }

  ok "Docker Compose services started."
}

# ─── Step 2: Wait for Readiness ──────────────────────────────────────────────

wait_for_readiness() {
  log "Waiting for validator services to become healthy..."
  log "  Timeout: ${MAX_WAIT_SECONDS}s, polling every ${POLL_INTERVAL}s"

  local start_time
  start_time=$(date +%s)

  while true; do
    local elapsed=$(( $(date +%s) - start_time ))
    if [ "$elapsed" -ge "$MAX_WAIT_SECONDS" ]; then
      err "Timed out after ${MAX_WAIT_SECONDS}s waiting for services."
      err "Endpoints that did not respond:"
      for ep in "${READINESS_ENDPOINTS[@]}"; do
        if ! curl -sf --max-time 5 "$ep" >/dev/null 2>&1; then
          err "  - $ep"
        fi
      done
      die "Environment not ready. Check Docker containers with 'docker ps' and logs with 'docker compose -f infra/compose.yaml logs'."
    fi

    local all_ready=true
    for ep in "${READINESS_ENDPOINTS[@]}"; do
      if ! curl -sf --max-time 5 "$ep" >/dev/null 2>&1; then
        all_ready=false
        break
      fi
    done

    if [ "$all_ready" = true ]; then
      ok "All validator endpoints are healthy! (after ${elapsed}s)"
      return 0
    fi

    log "  ... waiting (${elapsed}s elapsed)"
    sleep "$POLL_INTERVAL"
  done
}

# ─── Step 3: Run the Conformance Harness ─────────────────────────────────────

run_harness() {
  log "Building and running the Scala conformance harness..."

  cd "$PROJECT_ROOT"

  # Clean report directory
  rm -rf "$REPORT_DIR"
  mkdir -p "$REPORT_DIR"

  # Run via sbt
  sbt "run --scenario $SCENARIO_FILE --output $REPORT_DIR --retries 3 --retry-delay 5 --verbose"
  local exit_code=$?

  return $exit_code
}

# ─── Step 4: Summary ─────────────────────────────────────────────────────────

print_summary() {
  local exit_code=$1

  echo ""
  echo "================================================================"
  echo " Canton Conformance Kit — Live Demo Complete"
  echo "================================================================"
  echo ""

  if [ -f "$REPORT_DIR/conformance-report.json" ]; then
    ok "JSON report:     $REPORT_DIR/conformance-report.json"
  fi
  if [ -f "$REPORT_DIR/conformance-report.md" ]; then
    ok "Markdown report: $REPORT_DIR/conformance-report.md"
  fi

  echo ""

  if [ "$exit_code" -eq 0 ]; then
    ok "Demo completed successfully — all invariants passed."
  else
    err "Demo completed with failures — see reports for details."
  fi

  # Show Canton version from infra/.env
  local canton_version
  canton_version=$(grep '^CANTON_VERSION=' "$PROJECT_ROOT/infra/.env" | cut -d= -f2)
  echo ""
  echo "  Canton version: ${canton_version:-unknown}"
  echo ""

  return "$exit_code"
}

# ─── Main ────────────────────────────────────────────────────────────────────

main() {
  echo ""
  echo "================================================================"
  echo " Canton Conformance Kit — Live Demo"
  echo "================================================================"
  echo ""

  check_prereqs
  echo ""

  start_environment
  echo ""

  wait_for_readiness
  echo ""

  local harness_exit=0
  run_harness || harness_exit=$?
  echo ""

  print_summary "$harness_exit"
}

main "$@"
