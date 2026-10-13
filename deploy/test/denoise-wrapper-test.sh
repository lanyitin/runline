#!/bin/bash
# Checks of deploy/bin/runline-denoise (WI-1440, WI-1444) against the real tool: the pinned deep-filter
# and real audio made by ffmpeg. Meant to run where the tool will run: the Engine container (mawk,
# uutils sha256sum) as well as a development machine.
#
#   RUNLINE_DENOISE_BINARY=<pinned deep-filter> deploy/test/denoise-wrapper-test.sh
#
# Without RUNLINE_DENOISE_BINARY it looks at ../runline-transcribe/build/denoise/deep-filter. Needs
# ffmpeg and ffprobe on the PATH. No stub of deep-filter exists: the cases that refuse a binary use
# files that are really not the pinned one.
set -u
root="$(cd "$(dirname "$0")/../.." && pwd)"
tool="$root/deploy/bin/runline-denoise"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
failures=0

binary="${RUNLINE_DENOISE_BINARY:-$root/../runline-transcribe/build/denoise/deep-filter}"
[ -x "$binary" ] || { echo "no deep-filter at '$binary': set RUNLINE_DENOISE_BINARY to the pinned executable" >&2; exit 2; }
command -v ffmpeg >/dev/null && command -v ffprobe >/dev/null || { echo "ffmpeg and ffprobe are needed" >&2; exit 2; }

check() { # name, expected exit status, output must match, command...
  local name="$1" want="$2" pattern="$3"; shift 3
  local out status
  out="$("$@" 2>&1)"; status=$?
  if [ "$status" -ne "$want" ] || { [ -n "$pattern" ] && ! printf '%s' "$out" | grep -Eq -- "$pattern"; }; then
    printf 'FAIL %s (exit %s, wanted %s and /%s/)\n%s\n' "$name" "$status" "$want" "$pattern" "$out"
    failures=$((failures + 1))
  else
    printf 'ok   %s\n' "$name"
  fi
}
samples() { ffprobe -v error -select_streams a:0 -show_entries stream=duration_ts -of csv=p=0 "$1"; }
within_lookahead() { # input, output: the output is at most 1440 samples (30 ms) shorter and never longer
  local a b; a="$(samples "$1")"; b="$(samples "$2")"
  [ "$b" -le "$a" ] && [ $((a - b)) -le 1440 ]
}

in="$work/in.wav"
ffmpeg -v error -f lavfi -i "sine=frequency=440:duration=2:sample_rate=48000" -ac 1 -c:a pcm_s16le -y "$in" || { echo "cannot make the sample" >&2; exit 2; }
run=(env -i "PATH=$PATH" "TMPDIR=$work" "RUNLINE_DENOISE_BINARY=$binary" "$tool")

check "wrong arguments are exit 2" 2 'usage' "${run[@]}" --output "$work/o.wav" "$in"
check "a limit that is not a number is exit 2" 2 'usage' "${run[@]}" --atten-lim abc --output "$work/o.wav" "$in"
check "denoising succeeds and says the tool, its version and the limit" 0 'deep-filter 0\.5\.6 sha256=[0-9a-f]{64} atten-lim=24dB' \
  "${run[@]}" --atten-lim 24 --output "$work/out.wav" "$in"
check "the output is 48 kHz mono" 0 '^48000,1$' ffprobe -v error -select_streams a:0 -show_entries stream=sample_rate,channels -of csv=p=0:s=, "$work/out.wav"
check "the output is as long as the input but for the lookahead" 0 '' within_lookahead "$in" "$work/out.wav"
"${run[@]}" --atten-lim 24 --output "$work/again.wav" "$in" 2>/dev/null
check "the same input and limit give the same bytes" 0 '' cmp "$work/out.wav" "$work/again.wav"
"${run[@]}" --atten-lim 0 --output "$work/zero.wav" "$in" 2>/dev/null
check "a limit of 0 copies the input" 0 '' cmp "$in" "$work/zero.wav"
check "a limit of 100 works" 0 '' "${run[@]}" --atten-lim 100 --output "$work/full.wav" "$in"
check "nothing is left in TMPDIR" 0 '^0$' bash -c "ls -A '$work' | grep -c '^runline-denoise\.' || true"

check "no binary configured is refused naming the variable" 1 'RUNLINE_DENOISE_BINARY is not set' \
  env -i "PATH=$PATH" "$tool" --atten-lim 24 --output "$work/o1.wav" "$in"
check "a missing binary is refused" 1 'RUNLINE_DENOISE_BINARY is not an executable file' \
  env -i "PATH=$PATH" "RUNLINE_DENOISE_BINARY=$work/absent" "$tool" --atten-lim 24 --output "$work/o2.wav" "$in"
cp "$(command -v ls)" "$work/not-deep-filter"
check "a binary that is not the pinned one is refused" 1 'sha256 of RUNLINE_DENOISE_BINARY is not the pinned' \
  env -i "PATH=$PATH" "RUNLINE_DENOISE_BINARY=$work/not-deep-filter" "$tool" --atten-lim 24 --output "$work/o3.wav" "$in"
printf 'not audio' >"$work/text.wav"
check "an input that is not audio fails and writes no output" 1 'deep-filter failed' \
  "${run[@]}" --atten-lim 24 --output "$work/o4.wav" "$work/text.wav"
check "the failed run wrote no output" 0 '^0$' bash -c "ls '$work/o4.wav' 2>/dev/null | wc -l | tr -d ' '"

[ "$failures" -eq 0 ] || { printf '%s check(s) failed\n' "$failures"; exit 1; }
