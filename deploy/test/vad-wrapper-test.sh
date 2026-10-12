#!/bin/bash
# Checks that deploy/bin/runline-vad hands JDK_JAVA_OPTIONS to java (WI-1419): the pipeline gives the
# VAD tool its temporary directory with JDK_JAVA_OPTIONS=-Djava.io.tmpdir=<dir>. A real jar, built here
# with the JDK, prints java.io.tmpdir and its arguments; the wrapper runs it with a real java. Also checks
# that nothing in the transcribe deployment files sets or clears JDK_JAVA_OPTIONS for the Engine's
# environment (the VAD tool inherits the Engine's, the pipeline then sets its own). Needs java, javac, jar.
#
#   deploy/test/vad-wrapper-test.sh
set -u
root="$(cd "$(dirname "$0")/../.." && pwd)"
vad="$root/deploy/bin/runline-vad"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
failures=0

check() { # name, output pattern, status wanted, command...
  local name="$1" pattern="$2" want="$3"; shift 3
  local out status
  out="$("$@" 2>&1)"; status=$?
  if [ "$status" -ne "$want" ] || ! printf '%s' "$out" | grep -Eq -- "$pattern"; then
    printf 'FAIL %s (exit %s, wanted %s and /%s/)\n%s\n' "$name" "$status" "$want" "$pattern" "$out"
    failures=$((failures + 1))
  else
    printf 'ok   %s\n' "$name"
  fi
}

mkdir -p "$work/src" "$work/tmp-from-pipeline"
cat >"$work/src/Probe.java" <<'JAVA'
public class Probe {
  public static void main(String[] args) {
    System.out.println("tmpdir=" + System.getProperty("java.io.tmpdir"));
    System.out.println("args=" + String.join("|", args));
  }
}
JAVA
printf 'Main-Class: Probe\n' >"$work/manifest.txt"
if ! { javac -d "$work/classes" "$work/src/Probe.java" \
  && jar --create --file "$work/probe.jar" --manifest "$work/manifest.txt" -C "$work/classes" .; }; then
  echo "cannot build the probe jar (needs javac and jar)" >&2; exit 2
fi

check "JDK_JAVA_OPTIONS reaches the JVM the wrapper starts" "tmpdir=$work/tmp-from-pipeline\$" 0 \
  env "RUNLINE_VAD_JAR=$work/probe.jar" "JDK_JAVA_OPTIONS=-Djava.io.tmpdir=$work/tmp-from-pipeline" "$vad" --model m.onnx a.wav
check "the arguments reach the tool unchanged" 'args=--model\|m.onnx\|a.wav$' 0 \
  env "RUNLINE_VAD_JAR=$work/probe.jar" "$vad" --model m.onnx a.wav
check "a missing jar says so and exits 127" 'VAD tool is not installed' 127 \
  env "RUNLINE_VAD_JAR=$work/none.jar" "$vad"

# The files of the transcribe deployment leave the variable alone.
for f in deploy/systemd/runline-engine-transcribe.conf deploy/docker/compose.transcribe.yaml \
         deploy/docker/compose.browser-transcribe.yaml; do
  if grep -v '^[[:space:]]*#' "$root/$f" | grep -q 'JDK_JAVA_OPTIONS'; then
    printf 'FAIL %s sets or clears JDK_JAVA_OPTIONS\n' "$f"; failures=$((failures + 1))
  else
    printf 'ok   %s leaves JDK_JAVA_OPTIONS alone\n' "$f"
  fi
done
# The Dockerfile: no ENV or unset of it, and no wrapper that is not exec'd with the inherited environment.
if grep -E '^[[:space:]]*(ENV|ARG|RUN).*JDK_JAVA_OPTIONS|^[[:space:]]+JDK_JAVA_OPTIONS' "$root/deploy/docker/Dockerfile" | grep -q .; then
  printf 'FAIL Dockerfile sets or clears JDK_JAVA_OPTIONS\n'; failures=$((failures + 1))
else
  printf 'ok   Dockerfile leaves JDK_JAVA_OPTIONS alone\n'
fi
if grep -Eq 'env -i|unset JDK_JAVA_OPTIONS' "$vad"; then
  printf 'FAIL runline-vad clears the environment\n'; failures=$((failures + 1))
else
  printf 'ok   runline-vad does not clear the environment\n'
fi

[ "$failures" -eq 0 ] || { printf '%s check(s) failed\n' "$failures"; exit 1; }
