#!/bin/bash
# Checks (WI-1416) that the browser and the transcribe overlays can be used together, and each alone as
# before. It needs the `docker compose` CLI (to expand the templates; no daemon, nothing is built) and
# reads the real files of deploy/, so it holds what an operator gets:
#
#   - Compose: the three files compose.yaml + compose.browser.yaml + compose.transcribe.yaml +
#     compose.browser-transcribe.yaml expand to one image and one build target holding both, and each overlay
#     alone still selects its own stage and image;
#   - systemd: the two drop-ins set no variable, ExecStart or directory in conflict.
#
#   deploy/test/combined-image-test.sh
set -u
root="$(cd "$(dirname "$0")/../.." && pwd)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
failures=0

pass() { printf 'ok   %s\n' "$1"; }
fail() { printf 'FAIL %s\n' "$1"; failures=$((failures + 1)); }
expect() { # name, text, pattern
  if printf '%s\n' "$2" | grep -Eq -- "$3"; then pass "$1"; else fail "$1 (wanted /$3/ in:)"; printf '%s\n' "$2" | sed 's/^/     /'; fi
}
expect_not() { # name, text, pattern
  if printf '%s\n' "$2" | grep -Eq -- "$3"; then fail "$1 (did not want /$3/)"; else pass "$1"; fi
}

# A copy of the templates with the .env they need to expand.
mkdir -p "$work/deploy"
cp -R "$root/deploy/docker" "$work/deploy/docker"
cp "$work/deploy/docker/.env.example" "$work/deploy/docker/.env"
docker_dir="$work/deploy/docker"

render() { # overlay files...
  local args=(-f "$docker_dir/compose.yaml") f
  for f in "$@"; do args+=(-f "$docker_dir/$f"); done
  env -i PATH="$PATH" HOME="$HOME" TRANSCRIBE_INTAKE_HOST_DIR="$work" \
    TRANSCRIBE_VAD_HOST_DIR="$work" TRUSTED_CA_HOST_DIR="$work" \
    docker compose "${args[@]}" config 2>&1
}
engine_of() { printf '%s\n' "$1" | awk '/^  engine:/{on=1;next} /^  [a-z]/{on=0} on'; }
migrate_of() { printf '%s\n' "$1" | awk '/^  migrate:/{on=1;next} /^  [a-z]/{on=0} on'; }

# Each alone: unchanged.
plain="$(render)"
expect "no overlay: the plain image" "$(engine_of "$plain")" 'image: runline-engine:local'
expect "no overlay: the engine stage" "$(engine_of "$plain")" 'target: engine$'

browser="$(render compose.browser.yaml)"
expect "browser alone: its image" "$(engine_of "$browser")" 'image: runline-engine-browser:local'
expect "browser alone: its stage" "$(engine_of "$browser")" 'target: browser$'

transcribe="$(render compose.transcribe.yaml)"
expect "transcribe alone: its image" "$(engine_of "$transcribe")" 'image: runline-engine-transcribe:local'
expect "transcribe alone: its stage" "$(engine_of "$transcribe")" 'target: transcribe$'

# Only the intake root is writable (ADR-039); the other mounts stay read-only.
mount_of() { # compose output, target path: the whole volume entry with that target
  printf '%s\n' "$1" | awk -v t="target: $2" '
    function flush() { if (index(blk, t)) printf "%s", blk; blk = "" }
    /^      - type: / { flush() }
    { blk = blk $0 "\n" }
    END { flush() }'
}
transcribe_engine="$(engine_of "$transcribe")"
intake_mount="$(mount_of "$transcribe_engine" /var/lib/runline/intake)"
vad_mount="$(mount_of "$transcribe_engine" /opt/runline/vad)"
expect "transcribe alone: the intake mount is found" "$intake_mount" 'source:'
expect_not "transcribe alone: the intake mount is writable" "$intake_mount" 'read_only: true'
expect "transcribe alone: the VAD tool mount is read-only" "$vad_mount" 'read_only: true'

# Both.
both="$(render compose.browser.yaml compose.transcribe.yaml compose.browser-transcribe.yaml)"
for service in engine migrate; do
  if [ "$service" = engine ]; then part="$(engine_of "$both")"; else part="$(migrate_of "$both")"; fi
  expect "both: $service has the combined image" "$part" 'image: runline-engine-browser-transcribe:local'
  expect "both: $service builds the combined stage" "$part" 'target: browser-transcribe$'
  expect "both: $service is linux/amd64 (Chrome)" "$part" 'platform: linux/amd64'
done
engine_both="$(engine_of "$both")"
expect "both: init for the Chrome helpers" "$engine_both" 'init: true'
expect "both: trusted CA variable" "$engine_both" 'RUNLINE_TRUSTED_CA_DIR: /etc/runline/trusted-ca'
expect "both: intake root variable" "$engine_both" 'TRANSCRIBE_INTAKE_ROOT: /var/lib/runline/intake'
expect "both: trusted CA mount" "$engine_both" 'target: /etc/runline/trusted-ca'
expect "both: intake mount" "$engine_both" 'target: /var/lib/runline/intake'
expect "both: VAD tool mount" "$engine_both" 'target: /opt/runline/vad'
expect "both: the additional context of the scripts" "$both" 'deploy:'

# systemd: two drop-ins in one directory.
browser_conf="$root/deploy/systemd/runline-engine-browser.conf"
transcribe_conf="$root/deploy/systemd/runline-engine-transcribe.conf"
settings() { grep -Ev '^[[:space:]]*(#|$)' "$1"; }
names() { settings "$1" | sed -E 's/^(Environment=[A-Za-z_]+|[A-Za-z]+)=.*/\1/' | sort -u; }
shared="$(comm -12 <(names "$browser_conf") <(names "$transcribe_conf") | grep -Ev '^(\[Service\])$|^CacheDirectory$' || true)"
if [ -z "$shared" ]; then pass "systemd: the drop-ins set no setting in common (except the cumulative CacheDirectory)"
else fail "systemd: both drop-ins set: $shared"; fi
caches="$( { settings "$browser_conf"; settings "$transcribe_conf"; } | sed -n 's/^CacheDirectory=//p' | sort)"
if [ "$(printf '%s\n' "$caches" | wc -l)" -eq 2 ] && [ "$(printf '%s\n' "$caches" | sort -u | wc -l)" -eq 2 ]; then
  pass "systemd: two different CacheDirectory names"
else fail "systemd: CacheDirectory names are not two different ones: $caches"; fi
tmpdir_dir="$(sed -n 's/^Environment=JDK_JAVA_OPTIONS=-Djava.io.tmpdir=//p' "$browser_conf")"
work_dir="$(sed -n 's/^Environment=TRANSCRIBE_WORK_DIR=//p' "$transcribe_conf")"
if [ -n "$tmpdir_dir" ] && [ -n "$work_dir" ] && [ "$tmpdir_dir" != "$work_dir" ]; then
  pass "systemd: the JVM's temporary directory and the work directory are different"
else fail "systemd: '$tmpdir_dir' and '$work_dir' must be two directories"; fi
# The browser drop-in starts the Engine through the entrypoint, which finds its scripts beside itself;
# the PATH of the transcribe drop-in must keep what the entrypoint needs (java, Xvfb, curl, pkill).
path_line="$(sed -n 's/^Environment=PATH=//p' "$transcribe_conf")"
expect "systemd: PATH keeps /usr/bin" "$path_line" '(^|:)/usr/bin(:|$)'

[ "$failures" -eq 0 ] && echo "all combined-image checks passed" || { echo "$failures check(s) failed"; exit 1; }
