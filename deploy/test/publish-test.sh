#!/bin/bash
# Checks of the publishing entry (deploy/publish-image) and of the registry Compose template
# (deploy/docker/compose.registry.yaml) that need no Docker daemon: what the entry refuses before it
# builds anything, and what the template says once Compose has interpolated it. The checks that need a
# daemon (architecture, probe, registry round trip) are in the procedure of deploy/README.md.
#
#   deploy/test/publish-test.sh
set -u
root="$(cd "$(dirname "$0")/../.." && pwd)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
failures=0

check() { # name, expected exit status (0 = success, 1 = any failure), output must match, command...
  local name="$1" want="$2" pattern="$3"; shift 3
  local out status
  out="$("$@" 2>&1)"; status=$?
  if { [ "$want" = 0 ] && [ "$status" -ne 0 ]; } || { [ "$want" != 0 ] && [ "$status" -eq 0 ]; } \
     || ! printf '%s' "$out" | grep -Eq -- "$pattern"; then
    printf 'FAIL %s (exit %s, wanted /%s/)\n%s\n' "$name" "$status" "$pattern" "$out"
    failures=$((failures + 1))
  else
    printf 'ok   %s\n' "$name"
  fi
}

absent() { # name, pattern that must not appear in the output of the command, command...
  local name="$1" pattern="$2"; shift 2
  local out
  if ! out="$("$@" 2>&1)" || printf '%s' "$out" | grep -Eq -- "$pattern"; then
    printf 'FAIL %s (must succeed and not match /%s/)\n%s\n' "$name" "$pattern" "$out"
    failures=$((failures + 1))
  else
    printf 'ok   %s\n' "$name"
  fi
}

# A clean checkout of the committed state: what a maintainer has after committing.
git clone -q "$root" "$work/clean"
mkdir -p "$work/clean/deploy"
cp "$root/deploy/publish-image" "$work/clean/deploy/publish-image" 2>/dev/null
cp "$root/deploy/docker/compose.registry.yaml" "$work/clean/deploy/docker/" 2>/dev/null

check "refuses without RUNLINE_REGISTRY" 1 'RUNLINE_REGISTRY' \
  env -u RUNLINE_REGISTRY "$work/clean/deploy/publish-image"

# An uncommitted file in the checkout: that is the case.
echo change >"$work/clean/uncommitted-file"
check "refuses to push a tree with uncommitted changes" 1 'uncommitted' \
  env RUNLINE_REGISTRY=registry.invalid/runline "$work/clean/deploy/publish-image"

check "refuses a registry value carrying a tag or credentials" 1 'RUNLINE_REGISTRY' \
  env RUNLINE_REGISTRY=user:secret@registry.invalid/runline "$work/clean/deploy/publish-image"

# The version tag a release workflow adds beside the commit tag (WI-1101): refused before anything is built.
for bad in latest 'v1 2' '-v1' 'a:b' 'a/b'; do
  check "refuses the extra tag '$bad'" 1 'RUNLINE_EXTRA_TAG' \
    env RUNLINE_REGISTRY=registry.invalid/runline RUNLINE_EXTRA_TAG="$bad" "$work/clean/deploy/publish-image"
done

# The amd64 check on a daemon that must first download the base image (the case of a clean runner, where
# Docker prints its pull progress before the probe's own output). It needs a real daemon, so it is skipped
# loudly without one. The checkout is a copy whose Dockerfile names a base image that this test removes
# from the daemon first, and whose gradlew stops the entry right after the check: reaching it means the
# check passed.
pull_base=eclipse-temurin:21.0.5_11-jre-alpine
if docker info >/dev/null 2>&1; then
  git clone -q "$root" "$work/pulling"
  cp "$root/deploy/publish-image" "$work/pulling/deploy/publish-image"
  sed -i.bak "s|^FROM eclipse-temurin[^ ]* AS engine\$|FROM $pull_base AS engine|" "$work/pulling/deploy/docker/Dockerfile"
  printf '#!/bin/sh\necho check-passed-reached-gradle\nexit 1\n' >"$work/pulling/gradlew"
  docker rmi "$pull_base" >/dev/null 2>&1
  check "base image not local: the architecture check ignores the download messages" 1 'check-passed-reached-gradle' \
    env RUNLINE_REGISTRY=registry.invalid/runline "$work/pulling/deploy/publish-image" --no-push
  docker rmi "$pull_base" >/dev/null 2>&1
else
  echo "SKIP base image not local: no Docker daemon reachable"
fi

# The template as an operator has it: beside a .env (empty here; its values are given as variables).
mkdir "$work/host"
cp "$root/deploy/docker/compose.registry.yaml" "$work/host/"
: >"$work/host/.env"
compose="$work/host/compose.registry.yaml"
image=registry.invalid/runline/runline-engine-browser:0123456789ab
# An empty environment but the template's own required values, so that nothing of the host leaks in.
render() { env -i PATH="$PATH" HOME="$HOME" RUNLINE_DATA_DIR=/srv/runline TRUSTED_CA_FILE="$compose" \
  POSTGRES_DB=r POSTGRES_USER=r POSTGRES_PASSWORD=p "$@" docker compose -f "$compose" config; }

check "template needs an explicit image reference" 1 'RUNLINE_IMAGE' render
check "template renders with an image reference" 0 "image: $image" render RUNLINE_IMAGE=$image
absent "no service publishes a port" 'ports:' render RUNLINE_IMAGE=$image
absent "the template builds nothing" 'build:' render RUNLINE_IMAGE=$image
absent "the template names no floating tag" ':latest' render RUNLINE_IMAGE=$image
init_gate() { render RUNLINE_IMAGE=$image | grep -A1 '^      init:$'; }
check "the engine waits for the init container to succeed" 0 'condition: service_completed_successfully' init_gate
check "shared memory defaults to 1 GB" 0 'shm_size: "?1073741824"?' render RUNLINE_IMAGE=$image
check "shared memory is settable" 0 'shm_size: "?2147483648"?' render RUNLINE_IMAGE=$image ENGINE_SHM_SIZE=2gb
check "the stop wait exceeds the grace time" 0 'stop_grace_period: 45s' render RUNLINE_IMAGE=$image
check "resource limits are optional and settable" 0 'mem_limit: "?536870912"?' render RUNLINE_IMAGE=$image ENGINE_MEM_LIMIT=512m

[ "$failures" -eq 0 ] || { echo "$failures failed"; exit 1; }
echo "all passed"
