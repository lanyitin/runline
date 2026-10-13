#!/bin/bash
# Checks of deploy/bin/runline-transcribe-fetch (WI-1402, ADR-032): the pinned downloads are refused
# when their content is not the pinned content, and nothing is left in the destination then. The
# "upstream" is a real HTTP server (python3 -m http.server) serving files that are not the pinned ones.
# No network beyond the machine itself.
#
#   deploy/test/transcribe-fetch-test.sh
set -u
root="$(cd "$(dirname "$0")/../.." && pwd)"
fetch="$root/deploy/bin/runline-transcribe-fetch"
work="$(mktemp -d)"
server_pid=""
trap '[ -z "$server_pid" ] || kill "$server_pid" 2>/dev/null; rm -rf "$work"' EXIT
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

mkdir -p "$work/site/ffmpeg" "$work/site/silero"
for a in amd64 arm64; do printf 'not the pinned ffmpeg' >"$work/site/ffmpeg/ffmpeg-7.0.2-$a-static.tar.xz"; done
printf 'not the pinned model' >"$work/site/silero/silero_vad.onnx"
printf 'not the pinned deep-filter' >"$work/site/deep-filter"
port="$(python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1",0)); print(s.getsockname()[1])')"
(cd "$work/site" && exec python3 -m http.server "$port" --bind 127.0.0.1 >/dev/null 2>&1) &
server_pid=$!; disown
for _ in $(seq 1 50); do curl -fs "http://127.0.0.1:$port/silero/silero_vad.onnx" >/dev/null 2>&1 && break; sleep 0.1; done

mirror=("RUNLINE_FFMPEG_BASE_URL=http://127.0.0.1:$port/ffmpeg" "RUNLINE_SILERO_VAD_URL=http://127.0.0.1:$port/silero/silero_vad.onnx" "RUNLINE_DEEP_FILTER_URL=http://127.0.0.1:$port/deep-filter")

check "no destination is a usage error" 1 'usage' "$fetch"
check "an unsupported architecture is refused" 1 'architecture.*riscv64' env "${mirror[@]}" "$fetch" "$work/d1" riscv64
check "ffmpeg with other content is refused, naming the hash" 1 'ffmpeg.*sha256' env "${mirror[@]}" "$fetch" "$work/d2" arm64
check "nothing is left in the destination after a refusal" 0 '^0$' bash -c "ls -A '$work/d2' 2>/dev/null | wc -l | tr -d ' '"
check "deep-filter with other content is refused, naming the hash" 1 'deep-filter.*sha256.*70775e25' env "${mirror[@]}" "$fetch" "$work/d4" amd64
check "nothing is left in the destination after a deep-filter refusal" 0 '^0$' bash -c "ls -A '$work/d4' 2>/dev/null | wc -l | tr -d ' '"
check "an unreachable deep-filter source fails naming it" 1 'deep-filter.*cannot download' env "${mirror[@]}" "RUNLINE_DEEP_FILTER_URL=http://127.0.0.1:1/x" "$fetch" "$work/d5" amd64

# One definition of the pinned hash is in this script, the wrapper pins the same one to verify at run time.
pin_of() { grep -Eo '[0-9a-f]{64}' | head -n 1; }
fetch_pin="$(grep '^deep_filter_sha256_amd64=' "$fetch" | pin_of)"
wrapper_pin="$(grep 'Linux-x86_64)' "$root/deploy/bin/runline-denoise" | pin_of)"
fetch_version="$(sed -n 's/^deep_filter_version=//p' "$fetch")"
wrapper_version="$(sed -n 's/^VERSION="\(.*\)"/\1/p' "$root/deploy/bin/runline-denoise")"
check "the hash pinned in the wrapper is the one pinned here" 0 "^$fetch_pin\$" echo "$wrapper_pin"
check "the version pinned in the wrapper is the one pinned here" 0 "^$fetch_version\$" echo "$wrapper_version"
check "the pinned hash is not empty" 0 '^[0-9a-f]{64}$' echo "$fetch_pin"
check "an unreachable source fails" 1 'ffmpeg' env "RUNLINE_FFMPEG_BASE_URL=http://127.0.0.1:1/x" "$fetch" "$work/d3" amd64

[ "$failures" -eq 0 ] || { printf '%s check(s) failed\n' "$failures"; exit 1; }
