#!/usr/bin/env bash
#
# VaaniLedger — one command for everything.
#
#   ./run.sh          tests, accuracy eval, and the end-to-end demo
#   ./run.sh serve    the above, then the API server on :8000
#   ./run.sh quick    demo only, no tests
#
# The default path needs no install and no network. Only `serve` touches pip,
# and it builds an isolated venv rather than your system Python.

set -euo pipefail

cd "$(dirname "$0")/backend"

# Colour only when attached to a terminal, so piping to a file stays clean.
if [ -t 1 ]; then
  B=$'\033[1m'; DIM=$'\033[2m'; G=$'\033[32m'; R=$'\033[31m'; C=$'\033[36m'; X=$'\033[0m'
else
  B=""; DIM=""; G=""; R=""; C=""; X=""
fi

PY=python3
VENV=.venv
FAILED=0

step() { printf '\n%s┌─ %s %s%s\n' "$C" "$B" "$1" "$X"; }
ok()   { printf '%s└─ ✓ %s%s\n' "$G" "$1" "$X"; }
bad()  { printf '%s└─ ✗ %s%s\n' "$R" "$1" "$X"; FAILED=1; }

MODE="${1:-all}"

# --------------------------------------------------------------------------
# Tests
# --------------------------------------------------------------------------
run_tests() {
  step "Tests"
  if OUT=$($PY -m unittest discover -s tests -t . 2>&1); then
    ok "$(printf '%s' "$OUT" | grep -Eo 'Ran [0-9]+ tests[^$]*' | head -1) — all passing"
  else
    printf '%s\n' "$OUT"
    bad "tests failed"
  fi
}

# --------------------------------------------------------------------------
# Accuracy
# --------------------------------------------------------------------------
run_eval() {
  step "Extraction accuracy"
  if $PY -m eval.run; then :; else bad "eval failed"; fi
}

# --------------------------------------------------------------------------
# Demo
# --------------------------------------------------------------------------
run_demo() {
  step "End-to-end demo"
  if $PY demo.py; then :; else bad "demo failed"; fi
}

# --------------------------------------------------------------------------
# Server — the only path that installs anything
# --------------------------------------------------------------------------
run_serve() {
  step "API server"

  if ! $PY -c 'import fastapi, uvicorn' 2>/dev/null; then
    if [ ! -d "$VENV" ]; then
      printf '%s   fastapi/uvicorn missing — building %s …%s\n' "$DIM" "$VENV" "$X"
      $PY -m venv "$VENV"
    fi
    printf '%s   installing dependencies …%s\n' "$DIM" "$X"
    "$VENV/bin/pip" install --quiet --disable-pip-version-check -r requirements.txt
    PY="$VENV/bin/python"
  fi

  printf '\n   %sAPI%s      http://localhost:8000\n' "$B" "$X"
  printf '   %sdocs%s     http://localhost:8000/docs   %s← the contract, browsable%s\n' "$B" "$X" "$DIM" "$X"
  printf '\n   %sTry it from another terminal:%s\n\n' "$DIM" "$X"
  cat <<'EOF'
     curl -s localhost:8000/extract -H 'content-type: application/json' \
       -d '{"text":"2 kilo chawal, dhai sau ka udhaar","sender_name":"Rahul Verma"}' \
       | python3 -m json.tool
EOF
  printf '\n   %sCtrl+C to stop.%s\n\n' "$DIM" "$X"

  exec "$PY" -m uvicorn app.main:app --reload --port 8000
}

# --------------------------------------------------------------------------

printf '\n%s  VaaniLedger%s  %s· %s%s\n' "$B" "$X" "$DIM" "$(pwd)" "$X"

case "$MODE" in
  quick) run_demo ;;
  serve) run_tests; run_eval; run_demo; run_serve ;;
  dashboard) run_serve ;;   # just the server: http://localhost:8000
  all)   run_tests; run_eval; run_demo ;;
  *)     printf '%sUnknown mode: %s%s\n\nUsage: ./run.sh [all|quick|serve|dashboard]\n' "$R" "$MODE" "$X"; exit 2 ;;
esac

if [ "$FAILED" -ne 0 ]; then
  printf '\n%s  Something failed above.%s\n\n' "$R" "$X"
  exit 1
fi

printf '\n%s  Everything green.%s  %sRun ./run.sh serve to start the API.%s\n\n' "$G" "$X" "$DIM" "$X"
