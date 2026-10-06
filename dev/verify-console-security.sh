#!/usr/bin/env bash
# The local security check of the Console (WI-37, ADR-015, ADR-017). Not part of Gradle `check`
# and not run by any CI (there is none): it needs a browser, Docker and the network.
#
# It runs, in this order, and stops at the first failure:
#   1. the static rule (no string is inserted as markup; no list of exceptions) and the other
#      unit tests of the Console's tools;
#   2. the dependency audit (`npm audit --audit-level=high`: no known vulnerability of high or
#      critical severity in the locked dependencies);
#   3. a real Engine (dev/dev.sh: packaged jar with the Console, PostgreSQL 17 in a container) and
#      the real browser check, `console/e2e/security.e2e.ts`, with the adversarial jars of
#      dev/sample-pipelines;
#   4. package-lock.json is the file it was at the start (the build went through `npm ci`).
# The Engine and its database are stopped at the end, whatever the result.
#
# Usage: dev/verify-console-security.sh [--no-build]
#   --no-build  use the Engine and the jars that are already built (dev.sh start --no-build)
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CONSOLE="$ROOT/console"
PORT="${RUNLINE_DEV_PORT:-8080}"
DEV_TOKEN="${RUNLINE_DEV_DEVELOPER_TOKEN:-demo-developer-token}"
ADMIN_TOKEN="${RUNLINE_DEV_ADMIN_TOKEN:-demo-admin-token}"

say() { printf '\n== %s\n' "$*"; }
die() { printf 'FAILED: %s\n' "$*" >&2; exit 1; }

build_flag=()
case "${1:-}" in
  "") ;;
  --no-build) build_flag=(--no-build) ;;
  *) die "unknown argument $1 (only --no-build)" ;;
esac

[ -x "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" ] || [ -n "${CHROME_PATH:-}" ] \
  || die "no Chrome at the default path; set CHROME_PATH to the browser to use"

lock_before="$(shasum -a 256 "$CONSOLE/package-lock.json" | cut -d' ' -f1)"

say "1/4 static rule and tool tests"
(cd "$CONSOLE" && npx vitest run tools)

say "2/4 dependency audit (high and above)"
(cd "$CONSOLE" && npm run --silent audit)

say "3/4 real Engine and real browser"
started=0
cleanup() { if [ "$started" = 1 ]; then "$ROOT/dev/dev.sh" stop || true; fi; }
trap cleanup EXIT
"$ROOT/dev/dev.sh" start --detach ${build_flag[@]+"${build_flag[@]}"}
started=1
# dev.sh builds the sample jars with `start`; --no-build leaves what is there, so make sure the
# adversarial ones exist.
JARS="$ROOT/dev/sample-pipelines/build/pipelines"
for jar in adversarial adversarial-name adversarial-unreadable; do
  [ -f "$JARS/$jar.jar" ] || "$ROOT/dev/dev.sh" samples >/dev/null
  [ -f "$JARS/$jar.jar" ] || die "$JARS/$jar.jar was not built"
done
(
  cd "$CONSOLE"
  E2E_ENGINE_URL="http://localhost:$PORT" \
  E2E_TOKENS="dev:developer:$DEV_TOKEN,admin:admin:$ADMIN_TOKEN" \
  E2E_JARS="$JARS" \
  npm run e2e:security
)

say "4/4 the lock file"
lock_after="$(shasum -a 256 "$CONSOLE/package-lock.json" | cut -d' ' -f1)"
[ "$lock_before" = "$lock_after" ] || die "console/package-lock.json changed during the check"
echo "package-lock.json unchanged ($lock_after)"

say "Console security check passed"
