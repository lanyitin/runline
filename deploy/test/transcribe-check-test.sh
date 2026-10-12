#!/bin/bash
# Checks of deploy/bin/runline-transcribe-check (WI-1402): each case runs the real script with a real
# ffmpeg/ffprobe, real directories with real permissions, and, where a VAD tool is needed, a small
# executable that follows the output contract (probabilities on stdout, diagnostics on stderr, exit
# status). The tool itself is WI-1407 and is not part of this repository; these executables only stand
# in for it in this test, never ship. Needs ffmpeg and ffprobe on the PATH and must not run as root.
#
#   deploy/test/transcribe-check-test.sh
set -u
root="$(cd "$(dirname "$0")/../.." && pwd)"
check_script="$root/deploy/bin/runline-transcribe-check"
work="$(mktemp -d)"
trap 'chmod -R u+rwx "$work" 2>/dev/null; rm -rf "$work"' EXIT
failures=0

[ "$(id -u)" -ne 0 ] || { echo "run this test as an unprivileged user" >&2; exit 2; }

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

# A PATH holding only the named commands (to make one of them "not installed").
path_without() { # directory name, commands left out...
  local dir="$work/path-$1"; shift
  mkdir -p "$dir"
  for c in bash env dirname basename df awk grep sed mktemp rm date id head wc tr cat sort ffmpeg ffprobe sha256sum shasum; do
    case " $* " in *" $c "*) continue ;; esac
    p="$(command -v "$c" 2>/dev/null)" && ln -sf "$p" "$dir/$c"
  done
  printf '%s' "$dir"
}

intake="$work/intake"; mkdir -p "$intake"; : >"$intake/a.mp4"
readonly_dir="$work/readonly"; mkdir -p "$readonly_dir"; chmod 0555 "$readonly_dir"
scratch="$work/scratch"; mkdir -p "$scratch"
tooldir="$work/tool"; mkdir -p "$tooldir"
model="$tooldir/silero_vad.onnx"; printf 'model-bytes' >"$model"
model_sha="$( (sha256sum "$model" 2>/dev/null || shasum -a 256 "$model") | cut -d' ' -f1)"

# Tools following the contract (05-ipc.md): --model <file> <wav>; one probability per line.
good="$tooldir/good-vad"
# shellcheck disable=SC2016 # the generated scripts expand their own variables
printf '#!/bin/bash\n[ "$1" = --model ] && [ -f "$2" ] && [ -f "$3" ] || { echo "usage: --model M WAV" >&2; exit 64; }\nprintf "0.02\\n0.91\\n0.5\\n"\n' >"$good"
bad_range="$tooldir/bad-range-vad"; printf '#!/bin/bash\nprintf "0.2\\n1.7\\n"\n' >"$bad_range"
not_numbers="$tooldir/not-numbers-vad"; printf '#!/bin/bash\necho hello\n' >"$not_numbers"
empty="$tooldir/empty-vad"; printf '#!/bin/bash\nexit 0\n' >"$empty"
failing="$tooldir/failing-vad"; printf '#!/bin/bash\necho "cannot load model: broken" >&2\nexit 3\n' >"$failing"
chmod 0755 "$good" "$bad_range" "$not_numbers" "$empty" "$failing"

base=(env -i "HOME=$work" "PATH=$PATH" "TRANSCRIBE_INTAKE_ROOT=$intake" "TRANSCRIBE_WORK_DIR=$scratch")
vad=("TRANSCRIBE_VAD_COMMAND=$good" "RUNLINE_VAD_MODEL=$model")

check "everything in place passes" 0 'ffmpeg.*ok|all checks passed' \
  "${base[@]}" "${vad[@]}" "$check_script"
check "ffmpeg version is reported" 0 'ffmpeg version' "${base[@]}" "${vad[@]}" "$check_script"
check "a matching model hash passes" 0 'sha256' \
  "${base[@]}" "${vad[@]}" "RUNLINE_VAD_MODEL_SHA256=$model_sha" "$check_script"
check "a different model hash fails" 1 'model.*sha256.*(differs|mismatch)' \
  "${base[@]}" "${vad[@]}" "RUNLINE_VAD_MODEL_SHA256=$(printf '%064d' 0)" "$check_script"

printf '%s  silero_vad.onnx\n' "$(printf '%064d' 0)" >"$model.sha256"
check "a sidecar .sha256 next to the model is used when no hash is given" 1 'model.*sha256.*differs' "${base[@]}" "${vad[@]}" "$check_script"
printf '%s  silero_vad.onnx\n' "$model_sha" >"$model.sha256"
check "a matching sidecar passes" 0 'VAD model sha256' "${base[@]}" "${vad[@]}" "$check_script"
rm -f "$model.sha256"

check "no ffmpeg fails and names it" 1 'ffmpeg.*not found' \
  env -i "HOME=$work" "PATH=$(path_without no-ffmpeg ffmpeg)" "TRANSCRIBE_INTAKE_ROOT=$intake" "${vad[@]}" "$check_script"
check "no ffprobe fails and names it" 1 'ffprobe.*not found' \
  env -i "HOME=$work" "PATH=$(path_without no-ffprobe ffprobe)" "TRANSCRIBE_INTAKE_ROOT=$intake" "${vad[@]}" "$check_script"

check "intake root unset fails and names the variable" 1 'TRANSCRIBE_INTAKE_ROOT is not set' \
  env -i "HOME=$work" "PATH=$PATH" "${vad[@]}" "$check_script"
check "intake root missing fails" 1 'intake root.*does not exist' \
  env -i "HOME=$work" "PATH=$PATH" "TRANSCRIBE_INTAKE_ROOT=$work/nowhere" "${vad[@]}" "$check_script"
check "a writable intake root passes" 0 "intake root '$intake' is readable and writable" \
  "${base[@]}" "${vad[@]}" "$check_script"
check "a read-only intake root fails and says what is missing" 1 'intake root.*not writable.*writes the subtitle files' \
  env -i "HOME=$work" "PATH=$PATH" "TRANSCRIBE_INTAKE_ROOT=$readonly_dir" "${vad[@]}" "$check_script"
check "the write probe leaves nothing behind" 0 '^1$' bash -c "ls -A '$intake' | wc -l | tr -d ' '"
closed="$work/closed"; mkdir -p "$closed"; chmod 0000 "$closed"
check "an unreadable intake root fails" 1 'intake root.*not readable' \
  env -i "HOME=$work" "PATH=$PATH" "TRANSCRIBE_INTAKE_ROOT=$closed" "${vad[@]}" "$check_script"

check "a work directory that cannot be written fails" 1 'work directory.*not writable' \
  env -i "HOME=$work" "PATH=$PATH" "TRANSCRIBE_INTAKE_ROOT=$intake" "TRANSCRIBE_WORK_DIR=$readonly_dir" "${vad[@]}" "$check_script"
check "TRANSCRIBE_WORK_DIR, when set, is the directory checked" 0 "work directory '$scratch' is writable" \
  "${base[@]}" "TMPDIR=$work" "${vad[@]}" "$check_script"
check "without TRANSCRIBE_WORK_DIR the JVM default /tmp is checked, not TMPDIR" 0 "work directory '/tmp' is writable" \
  env -i "HOME=$work" "PATH=$PATH" "TRANSCRIBE_INTAKE_ROOT=$intake" "TMPDIR=$scratch" "${vad[@]}" "$check_script"
check "an empty TRANSCRIBE_WORK_DIR counts as not set" 0 "work directory '/tmp' is writable" \
  env -i "HOME=$work" "PATH=$PATH" "TRANSCRIBE_INTAKE_ROOT=$intake" "TRANSCRIBE_WORK_DIR=" "TMPDIR=$scratch" "${vad[@]}" "$check_script"
check "free space in the work directory is reported" 0 'free' "${base[@]}" "${vad[@]}" "$check_script"
check "too little free space fails" 1 'work directory.*free.*less than' \
  "${base[@]}" "${vad[@]}" "RUNLINE_TRANSCRIBE_MIN_FREE_MB=999999999" "$check_script"

check "VAD tool not installed is said so" 1 'VAD tool is not installed' \
  "${base[@]}" "TRANSCRIBE_VAD_COMMAND=$work/no-such-vad" "RUNLINE_VAD_MODEL=$model" "$check_script"
check "default VAD command name is looked up on the PATH" 1 'VAD tool is not installed.*runline-vad' \
  "${base[@]}" "RUNLINE_VAD_MODEL=$model" "$check_script"
check "VAD model not installed is said so" 1 'VAD model is not installed' \
  "${base[@]}" "TRANSCRIBE_VAD_COMMAND=$good" "RUNLINE_VAD_MODEL=$work/no-model.onnx" "$check_script"
check "both missing are both said" 1 'VAD model is not installed' \
  "${base[@]}" "TRANSCRIBE_VAD_COMMAND=$work/no-such-vad" "RUNLINE_VAD_MODEL=$work/no-model.onnx" "$check_script"
check "a tool that fails shows its reason" 1 'exit 3.*cannot load model: broken' \
  "${base[@]}" "TRANSCRIBE_VAD_COMMAND=$failing" "RUNLINE_VAD_MODEL=$model" "$check_script"
check "probabilities outside 0..1 fail" 1 'not a probability' \
  "${base[@]}" "TRANSCRIBE_VAD_COMMAND=$bad_range" "RUNLINE_VAD_MODEL=$model" "$check_script"
check "output that is not numbers fails" 1 'not a probability' \
  "${base[@]}" "TRANSCRIBE_VAD_COMMAND=$not_numbers" "RUNLINE_VAD_MODEL=$model" "$check_script"
check "no output fails" 1 'no probabilities' \
  "${base[@]}" "TRANSCRIBE_VAD_COMMAND=$empty" "RUNLINE_VAD_MODEL=$model" "$check_script"
check "the range of the probabilities is reported" 0 'min 0.02.*max 0.91' "${base[@]}" "${vad[@]}" "$check_script"

check "--skip-vad leaves the tool out and says so" 0 'VAD.*skipped' \
  "${base[@]}" "TRANSCRIBE_VAD_COMMAND=$work/no-such-vad" "$check_script" --skip-vad
check "an unknown option is refused" 1 'usage' "${base[@]}" "${vad[@]}" "$check_script" --nope

[ "$failures" -eq 0 ] || { printf '%s check(s) failed\n' "$failures"; exit 1; }
