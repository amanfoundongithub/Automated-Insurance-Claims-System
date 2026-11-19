#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PIDS=()
SERVICE_NAMES=()

require_command() {
    if ! command -v "$1" >/dev/null 2>&1; then
        printf 'Required command not found: %s\n' "$1" >&2
        exit 1
    fi
}

python_for_service() {
    local service="$1"
    local python_bin

    if [[ -n "${PYTHON:-}" ]]; then
        python_bin="$(command -v "$PYTHON" || true)"
    elif [[ -x "$ROOT_DIR/$service/.venv/bin/python" ]]; then
        python_bin="$ROOT_DIR/$service/.venv/bin/python"
    else
        python_bin="$(command -v python3 || true)"
    fi

    if [[ -z "$python_bin" ]]; then
        printf 'Python was not found for %s. Set PYTHON or create its .venv.\n' "$service" >&2
        return 1
    fi

    if ! "$python_bin" -c 'import uvicorn' >/dev/null 2>&1; then
        printf 'uvicorn is missing for %s. Install its requirements.txt first.\n' "$service" >&2
        return 1
    fi

    printf '%s\n' "$python_bin"
}

start_service() {
    local name="$1"
    local pid
    shift

    (
        cd "$ROOT_DIR/$name"
        exec "$@"
    ) &

    pid="$!"
    PIDS+=("$pid")
    SERVICE_NAMES+=("$name")
    printf 'Started %-28s pid=%s\n' "$name" "$pid"
}

cleanup() {
    local exit_status=$?
    trap - EXIT INT TERM

    printf '\nStopping local services...\n'
    for pid in "${PIDS[@]}"; do
        kill -TERM "$pid" 2>/dev/null || true
    done
    for pid in "${PIDS[@]}"; do
        wait "$pid" 2>/dev/null || true
    done

    exit "$exit_status"
}

require_command java
require_command bash

RISK_PYTHON="$(python_for_service risk-scoring-service)"
VALUATION_PYTHON="$(python_for_service valuation-reserve-service)"

trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

printf 'Starting services. Press Ctrl+C to stop them all.\n'
printf 'Ensure MongoDB, Redis, and RabbitMQ are running and local configuration is set.\n\n'

start_service risk-scoring-service \
    "$RISK_PYTHON" -m uvicorn app.main:app --host 0.0.0.0 --port 8084
start_service valuation-reserve-service \
    "$VALUATION_PYTHON" -m uvicorn app.main:app --host 0.0.0.0 --port 8085
start_service settlement-payout-service ./gradlew --no-daemon bootRun
start_service claims-orchestrator ./gradlew --no-daemon bootRun
start_service policy-service ./gradlew --no-daemon bootRun
start_service claims-ingestion-service ./gradlew --no-daemon bootRun

while true; do
    for index in "${!PIDS[@]}"; do
        pid="${PIDS[$index]}"
        if ! kill -0 "$pid" 2>/dev/null; then
            if wait "$pid"; then
                exit_status=1
            else
                exit_status=$?
            fi
            printf 'Service %s exited unexpectedly (status %s).\n' \
                "${SERVICE_NAMES[$index]}" "$exit_status" >&2
            exit "$exit_status"
        fi
    done
    sleep 1
done
