#!/bin/bash
# Checks the trial image of the aligner (WI-1451, docs/stable/transcribe-scenes/04-deployment.md):
# deploy/docker/Dockerfile.align-trial, built on the `transcribe` stage with the aligner of
# runline-transcribe (its align/ directory). It builds both images with the real Docker daemon and runs
# the real tool in them; nothing is stubbed.
#
#   deploy/test/align-trial-image-test.sh
#
#   RUNLINE_TRANSCRIBE_REPO   the runline-transcribe checkout (default ../runline-transcribe)
#   RUNLINE_TRIAL_PLATFORM    the platform to build and run (default: the daemon's own)
#
# Needs engine/build/engine-dist (./gradlew :engine:engineDistribution) and ffmpeg on this machine. It
# holds that:
#   - the production `transcribe` image has no aligner: the trial is built apart and changes nothing of it;
#   - the trial image has runline-align on the PATH, the model where RUNLINE_ALIGN_MODEL says, with the
#     pinned sha256;
#   - the tool aligns a clip as a uid that has no passwd entry, with no network and a read-only root file
#     system (only /tmp writable), and leaves nothing in /tmp;
#   - check-pins still passes.
# At the end it prints the size of both images and what the trial adds.
set -u
root="$(cd "$(dirname "$0")/../.." && pwd)"
transcribe_repo="${RUNLINE_TRANSCRIBE_REPO:-$root/../runline-transcribe}"
platform_args=()
[ -z "${RUNLINE_TRIAL_PLATFORM:-}" ] || platform_args=(--platform "$RUNLINE_TRIAL_PLATFORM")
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
failures=0
base=runline-engine-transcribe:align-trial-test-base
trial=runline-engine-transcribe-align-trial:test

check() { # name, expected exit status, output must match, command...
  local name="$1" want="$2" pattern="$3"; shift 3
  local out status
  out="$("$@" 2>&1)"; status=$?
  if [ "$status" -ne "$want" ] || { [ -n "$pattern" ] && ! printf '%s' "$out" | grep -Eq -- "$pattern"; }; then
    printf 'FAIL %s (exit %s, wanted %s and /%s/)\n%s\n' "$name" "$status" "$want" "$pattern" "$(printf '%s' "$out" | tail -n 20)"
    failures=$((failures + 1))
  else
    printf 'ok   %s\n' "$name"
  fi
}

[ -d "$root/engine/build/engine-dist" ] || { echo "no engine/build/engine-dist: run ./gradlew :engine:engineDistribution" >&2; exit 2; }
[ -x "$transcribe_repo/align/fetch" ] || { echo "no aligner at '$transcribe_repo/align': set RUNLINE_TRANSCRIBE_REPO" >&2; exit 2; }
command -v ffmpeg >/dev/null || { echo "ffmpeg is needed" >&2; exit 2; }

check "the transcribe image builds" 0 '' \
  docker build ${platform_args[@]+"${platform_args[@]}"} --target transcribe --build-context deploy="$root/deploy" \
    -f "$root/deploy/docker/Dockerfile" -t "$base" "$root/engine/build/engine-dist"
check "the trial image builds on it" 0 '' \
  docker build ${platform_args[@]+"${platform_args[@]}"} --build-arg BASE_IMAGE="$base" \
    --build-context align="$transcribe_repo/align" \
    -f "$root/deploy/docker/Dockerfile.align-trial" -t "$trial" "$root/deploy/docker"

in_image() { # image, command...
  local image="$1"; shift
  docker run --rm ${platform_args[@]+"${platform_args[@]}"} --entrypoint /bin/bash "$image" -c "$*"
}
check "the transcribe image has no aligner" 1 '' in_image "$base" 'command -v runline-align || test -e /opt/runline/align'
check "the trial image has runline-align on the PATH" 0 '/usr/local/bin/runline-align' in_image "$trial" 'command -v runline-align'
check "the model files are where RUNLINE_ALIGN_MODEL says, with the pinned sha256" 0 'model.safetensors: OK' \
  in_image "$trial" 'cd "$RUNLINE_ALIGN_MODEL" && sha256sum -c /opt/runline/align/model.sha256'

# A clip of 3 s and a text: the contract, not the accuracy (runline-transcribe's tests hold that).
# bfloat16 holds the weights in about 2.4 GB at the peak; float32 needs 4 GB to 6 GB, more than a
# small Docker VM gives, and is measured on the host itself (WI-1451's report).
mkdir -p "$work/in"
ffmpeg -v error -f lavfi -i "sine=frequency=220:duration=3" -ar 16000 -ac 1 -c:a pcm_s16le -y "$work/in/clip.wav" \
  || { echo "cannot make the sample" >&2; exit 2; }
printf 'こんにちは、元気ですか。' >"$work/in/clip.txt"
# The inputs go in on standard input (a tar), not by a bind mount: a Docker VM does not share every
# directory of the machine (colima shares only HOME), and this works with any daemon.
aligned() {
  COPYFILE_DISABLE=1 tar -C "$work/in" -cf - clip.wav clip.txt | docker run --rm -i ${platform_args[@]+"${platform_args[@]}"} --user 4321:4321 --network none \
    --read-only --tmpfs /tmp:mode=1777 --entrypoint /bin/bash "$trial" -c '
      mkdir /tmp/in && tar -xf - -C /tmp/in || exit
      runline-align --language Japanese --dtype bfloat16 --text /tmp/in/clip.txt --output /tmp/out.json /tmp/in/clip.wav || exit
      cat /tmp/out.json; echo; echo "left in /tmp: $(ls -A /tmp | grep -vx -e out.json -e in | wc -l)"'
}
# One run (the model takes a while to load), judged twice.
aligned >"$work/aligned.out" 2>&1; aligned_status=$?
aligned_run() { cat "$work/aligned.out"; return "$aligned_status"; }
check "the tool aligns as another uid, with no network and a read-only root" 0 \
  '"words": *\[\{"text": *"こんにちは", *"start": *[0-9.]+, *"end": *[0-9.]+\}' aligned_run
check "and leaves nothing in /tmp" 0 'left in /tmp: 0' aligned_run

check "check-pins still passes" 0 'pinned' "$root/deploy/check-pins"

size() { docker image inspect -f '{{.Size}}' "$1"; }
if base_size="$(size "$base")" && trial_size="$(size "$trial")"; then
  printf 'size: transcribe %s MB, trial %s MB, the aligner adds %s MB\n' \
    $((base_size / 1000000)) $((trial_size / 1000000)) $(((trial_size - base_size) / 1000000))
fi

[ "$failures" -eq 0 ] || { printf '%s check(s) failed\n' "$failures"; exit 1; }
