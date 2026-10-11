#!/bin/bash
# Checks of deploy/check-pins (WI-1102, ADR-029): it passes on the repository as it is, and it fails,
# naming the file, on each kind of reference that is not pinned by hash. Each failing case is a copy of
# the repository files with one pinned reference put back to a movable one. No Docker daemon needed.
#
#   deploy/test/pins-test.sh
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

# A copy of the files the check reads; `mutate` edits the copy.
copy() {
  local dir="$work/$1"
  mkdir -p "$dir/.forgejo/workflows" "$dir/deploy/docker"
  cp "$root"/.forgejo/workflows/*.yaml "$dir/.forgejo/workflows/"
  cp "$root"/deploy/docker/Dockerfile "$root"/deploy/docker/compose*.yaml "$dir/deploy/docker/"
  printf '%s' "$dir"
}
mutate() { # case name, file in the copy, sed expression
  local dir; dir="$(copy "$1")"
  sed -i.bak -E "$3" "$dir/$2" && rm "$dir/$2.bak"
  printf '%s' "$dir"
}

check "the repository is pinned" 0 'pinned' "$root/deploy/check-pins"

check "an action by tag fails" 1 'publish-image.yaml.*checkout@v4' \
  "$root/deploy/check-pins" "$(mutate action-tag .forgejo/workflows/publish-image.yaml 's|(actions/checkout)@[0-9a-f]{40}|\1@v4|')"
check "an action by branch fails" 1 'publish-image.yaml.*setup-java@main' \
  "$root/deploy/check-pins" "$(mutate action-branch .forgejo/workflows/publish-image.yaml 's|(actions/setup-java)@[0-9a-f]{40}|\1@main|')"
check "the base image by tag fails" 1 'Dockerfile.*eclipse-temurin:25-jre' \
  "$root/deploy/check-pins" "$(mutate base-tag deploy/docker/Dockerfile 's|^(FROM eclipse-temurin:25-jre)@sha256:[0-9a-f]{64}|\1|')"
check "PostgreSQL by tag in the registry template fails" 1 'compose.registry.yaml.*postgres:17-alpine' \
  "$root/deploy/check-pins" "$(mutate pg-tag deploy/docker/compose.registry.yaml 's|(image: postgres:17-alpine)@sha256:[0-9a-f]{64}|\1|')"
check "PostgreSQL by tag in compose.yaml fails" 1 'compose.yaml.*postgres:17-alpine' \
  "$root/deploy/check-pins" "$(mutate pg-tag2 deploy/docker/compose.yaml 's|(image: postgres:17-alpine)@sha256:[0-9a-f]{64}|\1|')"
check "a short digest fails" 1 'compose.yaml' \
  "$root/deploy/check-pins" "$(mutate short deploy/docker/compose.yaml 's|(image: postgres:17-alpine@sha256:)[0-9a-f]{64}|\1abc123|')"
check "two different PostgreSQL digests fail" 1 'different' \
  "$root/deploy/check-pins" "$(mutate pg-differ deploy/docker/compose.yaml 's|(image: postgres:17-alpine@sha256:)[0-9a-f]{4}|\10000|')"

[ "$failures" -eq 0 ] || { printf '%s check(s) failed\n' "$failures"; exit 1; }
