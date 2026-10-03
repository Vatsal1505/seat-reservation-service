#!/usr/bin/env bash
# Fires the on-sale stampede at a running service and prints the outcome distribution and reconciliation.
# Usage: scripts/burst.sh [BASE_URL]   (default http://localhost:8080)
# Optional environment: SEATS (100), USERS (2000), REQUESTS_PER_USER (10), K6_IMAGE.
# Exits 0 only if every check passes.
# On Windows run it from Git Bash or WSL: bash scripts/burst.sh
set -uo pipefail

BASE_URL="${1:-http://localhost:8080}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
K6_IMAGE="${K6_IMAGE:-grafana/k6:latest}"
OUTPUT="$(mktemp)"
trap 'rm -f "$OUTPUT"' EXIT

# Inside the container localhost is the container itself, so point it at the host instead.
# host.docker.internal is built into Docker Desktop (Windows, Mac) and mapped explicitly for Linux.
K6_URL="${BASE_URL/localhost/host.docker.internal}"
K6_URL="${K6_URL/127.0.0.1/host.docker.internal}"

docker run --rm -i --add-host=host.docker.internal:host-gateway \
  -e BASE_URL="$K6_URL" \
  -e SEATS="${SEATS:-100}" \
  -e USERS="${USERS:-2000}" \
  -e REQUESTS_PER_USER="${REQUESTS_PER_USER:-10}" \
  "$K6_IMAGE" run - < "$SCRIPT_DIR/burst.js" | tee "$OUTPUT"
K6_STATUS="${PIPESTATUS[0]}"

if [ "$K6_STATUS" -ne 0 ]; then
  echo "k6 did not finish cleanly (exit $K6_STATUS)" >&2
  exit "$K6_STATUS"
fi
grep -q '^RESULT: PASS' "$OUTPUT"
