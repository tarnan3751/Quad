#!/usr/bin/env bash
# One command for local development: start Postgres, seed demo data, run the server in the foreground.
#
#   scripts/dev.sh            # db up, seed (idempotent), run server on :8080
#   scripts/dev.sh --reset    # wipe and re-seed first
#   scripts/dev.sh --no-seed  # skip seeding
set -euo pipefail

cd "$(dirname "$0")/.."

RESET=""
SEED=1
for arg in "$@"; do
  case "$arg" in
    --reset) RESET="--reset" ;;
    --no-seed) SEED=0 ;;
    -h|--help) sed -n '2,8p' "$0"; exit 0 ;;
    *) echo "unknown option: $arg" >&2; exit 2 ;;
  esac
done

if [ ! -f local.properties ] && [ -d "$HOME/Library/Android/sdk" ]; then
  echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties
fi

echo "==> Starting Postgres (docker compose up -d --wait db)"
docker compose up -d --wait db

if [ "$SEED" = 1 ]; then
  echo "==> Seeding demo data"
  if [ -n "$RESET" ]; then
    ./gradlew :server:seed --args="--reset" --console=plain -q
  else
    ./gradlew :server:seed --console=plain -q
  fi
fi

echo "==> Running server on http://localhost:${PORT:-8080} (Ctrl-C to stop)"
exec ./gradlew :server:run --console=plain
