#!/usr/bin/env bash
#
# Canton Conformance Kit — Live Demo Runner
#
# This script:
#   1. Clones/updates the CN Quickstart reference environment
#   2. Configures and starts the local Canton network
#   3. Waits for validator services to become healthy
#   4. Runs the Scala conformance harness
#   5. Generates JSON + Markdown reports
#
# Prerequisites:
#   - Docker Desktop (8+ GB RAM allocated)
#   - direnv
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
QUICKSTART_DIR="${PROJECT_ROOT}/.quickstart"
QUICKSTART_REPO="https://github.com/digital-asset/cn-quickstart.git"
# Pin to a known tag/branch for reproducibility; update as needed
QUICKSTART_REF="main"

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

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

log()   { echo -e "${CYAN}[conformance]${NC} $*"; }
warn()  { echo -e "${YELLOW}[conformance]${NC} $*"; }
err()   { echo -e "${RED}[conformance]${NC} $*" >&2; }
ok()    { echo -e "${GREEN}[conformance]${NC} $*"; }

die() { err "$@"; exit 1; }

check_prereqs() {
  log "Checking prerequisites..."
  local missing=()
  command -v docker   >/dev/null 2>&1 || missing+=("docker")
  command -v java     >/dev/null 2>&1 || missing+=("java (JDK 21+)")
  command -v sbt      >/dev/null 2>&1 || missing+=("sbt")
  command -v curl     >/dev/null 2>&1 || missing+=("curl")
  command -v git      >/dev/null 2>&1 || missing+=("git")

  if [ ${#missing[@]} -gt 0 ]; then
    die "Missing prerequisites: ${missing[*]}"
  fi

  if ! docker info >/dev/null 2>&1; then
    die "Docker daemon is not running. Please start Docker Desktop."
  fi

  ok "All prerequisites satisfied."
}

# ─── Step 1: Clone / Update Quickstart ───────────────────────────────────────

setup_quickstart() {
  log "Setting up CN Quickstart reference environment..."

  if [ -d "$QUICKSTART_DIR/.git" ]; then
    log "Quickstart repo already cloned at $QUICKSTART_DIR"
    log "Updating to ref: $QUICKSTART_REF"
    (cd "$QUICKSTART_DIR" && git fetch origin && git checkout "$QUICKSTART_REF" && git pull origin "$QUICKSTART_REF" 2>/dev/null || true)
  else
    log "Cloning cn-quickstart ($QUICKSTART_REF)..."
    git clone --depth 1 --branch "$QUICKSTART_REF" "$QUICKSTART_REPO" "$QUICKSTART_DIR"
  fi

  ok "Quickstart repo ready at $QUICKSTART_DIR"
  log "  Commit: $(cd "$QUICKSTART_DIR" && git rev-parse --short HEAD)"
}

# ─── Step 2: Start the Environment ──────────────────────────────────────────

start_environment() {
  log "Starting CN Quickstart local environment..."
  log "  (This may take several minutes on first run)"
  log ""
  log "  The Quickstart environment uses 'make' commands."
  log "  If this is a fresh clone, you may need to run 'make setup' interactively first."
  log ""

  cd "$QUICKSTART_DIR"

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
    cd "$PROJECT_ROOT"
    return 0
  fi

  # Attempt to start — this assumes 'make setup' has been run at least once.
  # The Quickstart Makefile orchestrates Docker Compose under the hood.
  if [ -f Makefile ]; then
    log "Running 'make start' in Quickstart directory..."
    make start || {
      err "Failed to start environment."
      err ""
      err "If this is a first-time setup, you may need to run interactively:"
      err "  cd $QUICKSTART_DIR"
      err "  make setup    # (interactive configuration)"
      err "  make build"
      err "  make start"
      err ""
      err "Then re-run this demo script."
      exit 1
    }
  else
    die "No Makefile found in $QUICKSTART_DIR. The cn-quickstart repo may have changed structure."
  fi

  cd "$PROJECT_ROOT"
  ok "Environment start command issued."
}

# ─── Step 3: Wait for Readiness ──────────────────────────────────────────────

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
      die "Environment not ready. Check Docker containers with 'docker ps'."
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

# ─── Step 4: Run the Conformance Harness ─────────────────────────────────────

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

# ─── Step 5: Summary ─────────────────────────────────────────────────────────

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

  echo ""
  echo "  Upstream repo: $QUICKSTART_REPO"
  echo "  Upstream ref:  $QUICKSTART_REF"
  if [ -d "$QUICKSTART_DIR/.git" ]; then
    echo "  Upstream commit: $(cd "$QUICKSTART_DIR" && git rev-parse --short HEAD)"
  fi
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

  setup_quickstart
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
