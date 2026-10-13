#!/bin/bash
# Checks (WI-1103, ADR-041) that the Engine images run as any uid:gid the deployer names with `user:`, and
# as uid 10001 when nobody names one. Everything is real: the images are built from deploy/docker/Dockerfile,
# the containers run on the Docker daemon, PostgreSQL is a postgres container, the minimal run is a pipeline
# compiled here and uploaded to the Engine over its HTTP API. Nothing is stubbed.
#
# Needs: a Docker daemon and the `docker compose` CLI, javac on the PATH, curl, the artifact of
# `./gradlew :engine:engineDistribution` in engine/build/engine-dist, and network for the image builds.
#
#   deploy/test/arbitrary-uid-test.sh
#
#   UID_TEST_TARGETS   the Dockerfile stages to check, default "engine transcribe". The browser stages
#                      (browser, browser-transcribe) are linux/amd64 only and download Chrome; add them where
#                      the Docker daemon runs amd64 images: UID_TEST_TARGETS="engine transcribe browser browser-transcribe"
#   UID_TEST_KEEP      "true" leaves the built images (uidtest-*) in place for another run
#
# The volumes the containers use are named Docker volumes, not host directories: the owner and the mode
# of a directory then are what the Linux kernel of the Docker daemon says, on every host.
# No `set -u`: the bash 3.2 of macOS treats an empty array as unset.
root="$(cd "$(dirname "$0")/../.." && pwd)"
dist="$root/engine/build/engine-dist"
targets="${UID_TEST_TARGETS:-engine transcribe}"
run_id="uidtest$$"
work="$(mktemp -d)"
failures=0
declared_images=()
declared_containers=()
declared_volumes=()
declared_networks=()

pass() { printf 'ok   %s\n' "$1"; }
fail() { printf 'FAIL %s\n' "$1"; failures=$((failures + 1)); }
expect() { # name, text, pattern
  if printf '%s\n' "$2" | grep -Eq -- "$3"; then pass "$1"; else fail "$1 (wanted /$3/ in:)"; printf '%s\n' "$2" | sed 's/^/     /'; fi
}
expect_not() { # name, text, pattern
  if printf '%s\n' "$2" | grep -Eq -- "$3"; then fail "$1 (did not want /$3/ in:)"; printf '%s\n' "$2" | sed 's/^/     /'; else pass "$1"; fi
}
expect_equal() { # name, got, want
  if [ "$2" = "$3" ]; then pass "$1"; else fail "$1 (got '$2', wanted '$3')"; fi
}

cleanup() {
  local c
  for c in "${declared_containers[@]:-}"; do [ -n "$c" ] && docker rm -f -v "$c" >/dev/null 2>&1; done
  for c in "${declared_networks[@]:-}"; do [ -n "$c" ] && docker network rm "$c" >/dev/null 2>&1; done
  for c in "${declared_volumes[@]:-}"; do [ -n "$c" ] && docker volume rm -f "$c" >/dev/null 2>&1; done
  if [ "${UID_TEST_KEEP:-false}" != true ]; then
    for c in "${declared_images[@]:-}"; do [ -n "$c" ] && docker rmi -f "$c" >/dev/null 2>&1; done
  fi
  rm -rf "$work"
}
trap cleanup EXIT

[ -d "$dist" ] && [ -f "$dist/engine.jar" ] || { echo "no $dist/engine.jar: run ./gradlew :engine:engineDistribution first" >&2; exit 2; }

new_volume() { local v="$run_id-$1"; docker volume create "$v" >/dev/null; declared_volumes+=("$v"); printf '%s' "$v"; }

# Gives a volume to uid:gid with the mode (as root, in a throw-away container of the image). A file is put
# in it: Docker gives an empty volume the owner and mode of the image's directory it is mounted on.
prepare_volume() { # volume, owner uid:gid, mode, image
  docker run --rm --pull never -u root --entrypoint sh -v "$1:/d" "$4" -c "chown $2 /d && chmod $3 /d && : > /d/.volume-in-use"
}

build_image() { # target
  local target="$1" tag="uidtest-$1" platform=()
  case "$target" in browser | browser-transcribe) platform=(--platform linux/amd64) ;; esac
  declared_images+=("$tag")
  docker build -q "${platform[@]}" --target "$target" --build-context "deploy=$root/deploy" \
    -f "$root/deploy/docker/Dockerfile" -t "$tag" "$dist" >"$work/build-$target.log" 2>&1
}

platform_of() { case "$1" in browser | browser-transcribe) printf -- '--platform=linux/amd64' ;; *) printf -- '--platform=%s' "$(docker version -f '{{.Server.Os}}/{{.Server.Arch}}')" ;; esac; }

# --- A minimal pipeline: a row in a SQLite resource (the JDBC driver unpacks its native library into
# java.io.tmpdir) and a file in the pipeline's shared directory.
build_pipeline() {
  mkdir -p "$work/src/demo" "$work/classes"
  cat >"$work/src/demo/UidProbe.java" <<'JAVA'
package demo;
import dev.lawlan.runline.core.*;

@PipelineDefinition(name = "uid-probe",
    files = {@FileAccess(scope = FileScope.PIPELINE_SHARED, mode = FileMode.READ_WRITE),
             @FileAccess(scope = FileScope.RUN_PRIVATE, mode = FileMode.READ_WRITE)},
    network = @AccessLimit(allow = {}), processes = @AccessLimit(allow = {}),
    typedResources = {@TypedResource(name = "db", type = "jdbc-pool")})
public class UidProbe implements Pipeline {
  @Override
  public void run(PipelineContext context) throws RuntimeException {
    JdbcAccessor db = context.getAccessors().jdbcPool("db");
    db.update("CREATE TABLE IF NOT EXISTS t (n INTEGER)");
    db.update("INSERT INTO t VALUES (1)");
    context.getFiles().writeText(FileScope.RUN_PRIVATE, "scratch", "written");
    context.getFiles().writeText(FileScope.PIPELINE_SHARED, "outcome", "done");
  }
}
JAVA
  local cp
  cp="$(printf '%s:' "$dist"/run-runtime/*.jar)"
  javac -proc:none -classpath "$cp" -d "$work/classes" "$work/src/demo/UidProbe.java" >"$work/javac.log" 2>&1 \
    && (cd "$work/classes" && jar --create --file "$work/uid-probe.jar" --date 2026-01-01T00:00:00Z demo) >>"$work/javac.log" 2>&1
}

api() { # method, path, token, port, [file, content-type]
  local method="$1" path="$2" token="$3" port="$4" file="${5:-}" type="${6:-application/json}"
  if [ -n "$file" ]; then
    curl -sS -X "$method" -H "Authorization: Bearer $token" -H "Content-Type: $type" --data-binary "@$file" "http://127.0.0.1:$port$path"
  else
    curl -sS -X "$method" -H "Authorization: Bearer $token" -H "Content-Type: $type" "http://127.0.0.1:$port$path"
  fi
}

# --- B: the Engine itself, with PostgreSQL, as a given user; ready, and a run to its end.
engine_run() { # target, uid:gid, label
  local target="$1" ug="$2" label="$3" image="uidtest-$1"
  local tag="$run_id-$label" net pg eng port
  net="$tag-net"; pg="$tag-pg"; eng="$tag-engine"
  docker network create "$net" >/dev/null && declared_networks+=("$net")
  declared_containers+=("$pg" "$eng" "$tag-migrate")
  local shared resources runs intake
  shared="$(new_volume "$label-shared")"; resources="$(new_volume "$label-resources")"; runs="$(new_volume "$label-runs")"
  intake="$(new_volume "$label-intake")"
  # The 1000:1000 Engine gets a run root volume owned by it (what arr mounts there); the other one uses the
  # run root that is in the image.
  local runs_mount=(-v "$runs:/var/lib/runline/runs")
  case "$ug" in 1000:1000) ;; *) runs_mount=() ;; esac
  local v
  for v in "$shared" "$resources" "$runs" "$intake"; do prepare_volume "$v" "$ug" 0755 "$image" || { fail "$label: could not prepare volume $v"; return; }; done

  docker run -d --pull never --name "$pg" --network "$net" --network-alias postgres \
    -e POSTGRES_DB=runline -e POSTGRES_USER=runline -e POSTGRES_PASSWORD=uid-test \
    postgres:17-alpine@sha256:b0f9560a2de083e2cc7382e75f808c7381a32852a7ec49117deedb300e552b24 >/dev/null
  local i
  for i in $(seq 1 60); do docker exec "$pg" pg_isready -U runline -d runline >/dev/null 2>&1 && break; sleep 1; done

  cat >"$work/$label.env" <<ENV
POSTGRES_URL=jdbc:postgresql://postgres:5432/runline
POSTGRES_USER=runline
POSTGRES_PASSWORD=uid-test
API_TOKENS=dev:developer:tok-dev-uid,ops:admin:tok-admin-uid
RUNLINE_MAX_CONCURRENT_RUNS=2
RUNLINE_WORKSPACE_MAX_BYTES=1073741824
RUNLINE_FAILED_RUN_RETENTION_SECONDS=3600
RUNLINE_SHUTDOWN_GRACE_SECONDS=5
OTEL_TRACES_EXPORTER=none
OTEL_LOGS_EXPORTER=none
OTEL_METRICS_EXPORTER=none
ENV
  local common=(--pull never "$(platform_of "$target")" --user "$ug" --network "$net" --env-file "$work/$label.env"
    -v "$shared:/var/lib/runline/shared" -v "$resources:/var/lib/runline/resources" "${runs_mount[@]}"
    -v "$intake:/var/lib/runline/intake")
  local extra=()
  case "$target" in browser*) extra=(--init) ;; esac

  if docker run --name "$tag-migrate" "${common[@]}" --entrypoint java "$image" -cp /app/engine.jar dev.lawlan.runline.engine.db.MigrateKt >"$work/$label-migrate.log" 2>&1; then
    pass "$label: the migration runs"
  else
    fail "$label: the migration fails"; tail -n 15 "$work/$label-migrate.log" | sed 's/^/     /'; return
  fi
  docker run -d --name "$eng" "${common[@]}" "${extra[@]}" -e TRANSCRIBE_INTAKE_ROOT=/var/lib/runline/intake -p 127.0.0.1::8080 "$image" >/dev/null
  port="$(docker port "$eng" 8080/tcp | head -n 1 | sed 's/.*://')"
  local ready=false
  for i in $(seq 1 90); do
    if docker exec "$eng" java -cp /app/engine.jar dev.lawlan.runline.engine.HealthCheckKt ready >/dev/null 2>&1; then ready=true; break; fi
    [ "$(docker inspect -f '{{.State.Running}}' "$eng")" = true ] || break
    sleep 2
  done
  if [ "$ready" != true ]; then
    fail "$label: the Engine does not become ready"; docker logs "$eng" 2>&1 | tail -n 25 | sed 's/^/     /'; return
  fi
  pass "$label: the Engine starts and the readiness probe passes"
  expect_equal "$label: the Engine runs as $ug" "$(docker exec "$eng" sh -c 'echo $(id -u):$(id -g)')" "$ug"

  api POST /api/v1/resources tok-admin-uid "$port" /dev/stdin >"$work/$label-define.json" 2>/dev/null <<<'{"name":"db","capacity":1,"type":"jdbc-pool","settings":{"kind":"sqlite","path":"uid.db"}}'
  local up hash started run_id_ state="no answer"
  up="$(api POST /api/v1/artifacts tok-dev-uid "$port" "$work/uid-probe.jar" application/octet-stream)"
  hash="$(printf '%s' "$up" | sed -n 's/.*"contentHash" *: *"\([^"]*\)".*/\1/p')"
  if [ -z "$hash" ]; then fail "$label: the pipeline was not accepted: $up"; return; fi
  printf '{"contentHash":"%s","pipeline":"uid-probe"}' "$hash" >"$work/$label-run.json"
  started="$(api POST /api/v1/runs tok-dev-uid "$port" "$work/$label-run.json")"
  run_id_="$(printf '%s' "$started" | sed -n 's/.*"runId" *: *"\([^"]*\)".*/\1/p')"
  if [ -z "$run_id_" ]; then fail "$label: the run did not start: $started"; return; fi
  for i in $(seq 1 100); do
    state="$(api GET "/api/v1/runs/$run_id_" tok-dev-uid "$port" | sed -n 's/.*"state" *: *"\([A-Z_]*\)".*/\1/p')"
    case "$state" in SUCCEEDED | FAILED | CANCELLED | TIMED_OUT | INTERRUPTED) break ;; esac
    sleep 0.5
  done
  expect_equal "$label: a minimal run (SQLite, shared and private files) succeeds" "$state" "SUCCEEDED"
  [ "$state" = SUCCEEDED ] || docker logs "$eng" 2>&1 | tail -n 25 | sed 's/^/     /'
  case "$target" in browser*) expect "$label: the virtual display comes up" "$(docker logs "$eng" 2>&1)" 'virtual display :99 is up' ;; esac
  expect_not "$label: the log has no permission error" "$(docker logs "$eng" 2>&1)" 'Permission denied|AccessDenied|Read-only file system'
  docker stop -t 10 "$eng" >/dev/null 2>&1
  expect_equal "$label: the Engine stops on SIGTERM with status 0" "$(docker inspect -f '{{.State.ExitCode}}' "$eng")" "0"
}

# --- A: the places of the image, as a given user (no Engine started).
places_script='
w() { if (mkdir -p "$1" && : > "$1/.probe" && rm -f "$1/.probe") 2>/dev/null; then echo "W $1 yes"; else echo "W $1 no"; fi; }
home="${HOME:-/}"
echo "HOME $home"
w "$home"
w "$home/.pki/nssdb"
w "$home/.config"
w "$home/.cache"
w "${RUNLINE_RUN_ROOT:-/nonexistent}"
w /tmp
w /tmp/.X11-unix
props="$(java -XshowSettings:properties -version 2>&1)"
td="$(printf "%s\n" "$props" | sed -n "s/^ *java.io.tmpdir = //p")"
echo "JAVA java.io.tmpdir $td"
w "$td"
echo "ID $(id -u):$(id -g)"
for ro in /app /app/run-runtime /opt/runline /opt/runline/models /opt/runline/vad; do
  [ -d "$ro" ] || continue
  if : > "$ro/.probe" 2>/dev/null; then rm -f "$ro/.probe"; echo "RO $ro writable"; else echo "RO $ro readonly"; fi
  echo "OWN $ro $(stat -c "%u %a" "$ro")"
done
if : >> /app/engine.jar 2>/dev/null; then echo "RO /app/engine.jar writable"; else echo "RO /app/engine.jar readonly"; fi
'

check_places() { # target, uid:gid ("" = the image default)
  local target="$1" ug="$2" label="$1 as ${2:-default}" out userflag=()
  [ -n "$ug" ] && userflag=(--user "$ug")
  out="$(docker run --rm --pull never "$(platform_of "$target")" "${userflag[@]}" --entrypoint sh "uidtest-$target" -c "$places_script" 2>&1)"
  local home
  home="$(printf '%s\n' "$out" | sed -n 's/^HOME //p')"
  expect "$label: HOME ($home) can be written" "$out" "^W $home yes\$"
  # The JVM of a uid with no passwd entry takes "/" as user.home; the Engine is started with -Duser.home.
  local command uhome
  command="$(docker image inspect -f '{{json .Config.Entrypoint}} {{json .Config.Cmd}}' "uidtest-$target")"
  expect "$label: the Engine's JVM is started with -Duser.home=HOME" "$command" "-Duser.home=$home\""
  uhome="$(printf '%s' "$command" | sed -n 's/.*-Duser.home=\([^"]*\)".*/\1/p')"
  expect "$label: and that directory ($uhome) can be written" "$out" "^W $uhome yes\$"
  expect "$label: java.io.tmpdir can be written" "$out" "^W $(printf '%s\n' "$out" | sed -n 's/^JAVA java.io.tmpdir //p') yes\$"
  expect "$label: the run root can be written" "$out" '^W /var/lib/runline/runs yes$'
  expect "$label: /tmp can be written" "$out" '^W /tmp yes$'
  case "$target" in
    browser*)
      expect "$label: Chrome's certificate database place (~/.pki/nssdb) can be written" "$out" "^W $home/.pki/nssdb yes\$"
      expect "$label: Chrome's ~/.config can be written" "$out" "^W $home/.config yes\$"
      expect "$label: Chrome's ~/.cache can be written" "$out" "^W $home/.cache yes\$"
      expect "$label: the Xvfb directory /tmp/.X11-unix can be used" "$out" '^W /tmp/.X11-unix yes$'
      ;;
  esac
  expect_not "$label: no read-only place is writable" "$out" '^RO .* writable$'
  expect_not "$label: the read-only places are owned by root" "$out" '^OWN [^ ]+ [1-9][0-9]* '
  expect_not "$label: no read-only place is writable by group or other" "$out" '^OWN [^ ]+ [0-9]+ [0-7]*([2367][0-7]|[0-7][2367])$'
  [ -z "$ug" ] && expect_equal "$label: the image default is uid:gid 10001:999" "$(printf '%s\n' "$out" | sed -n 's/^ID //p')" "10001:999"
}

# --- Start.
echo "== building: $targets"
for t in $targets; do
  if build_image "$t"; then pass "build target $t"; else fail "build target $t"; tail -n 15 "$work/build-$t.log" | sed 's/^/     /'; fi
done
if ! build_pipeline; then fail "the test pipeline does not compile"; cat "$work/javac.log"; exit 1; fi

for t in $targets; do
  docker image inspect "uidtest-$t" >/dev/null 2>&1 || continue
  echo "== $t"
  check_places "$t" ""
  for ug in 1000:1000 2345:2345; do
    check_places "$t" "$ug"
    engine_run "$t" "$ug" "$t-${ug%%:*}"
  done
done

# --- Condition 3 of read-only places, also for a file that the account could replace through its
# directory: /app is root's, so the jar cannot be removed or renamed either.
if docker image inspect uidtest-engine >/dev/null 2>&1; then
  out="$(docker run --rm --pull never --user 1000:1000 --entrypoint sh uidtest-engine -c 'rm -f /app/engine.jar 2>&1; mv /app/engine.jar /app/x 2>&1; echo done' 2>&1)"
  expect "a uid that is not the account cannot remove or rename the jar" "$out" 'Permission denied'
  expect_equal "the jar is still there" "$(docker run --rm --pull never --entrypoint sh uidtest-engine -c 'test -f /app/engine.jar && echo yes')" "yes"
fi

# --- Condition 6: the intake directory (transcribe stages): its owner decides who can write.
for t in $targets; do
  case "$t" in transcribe | browser-transcribe) ;; *) continue ;; esac
  docker image inspect "uidtest-$t" >/dev/null 2>&1 || continue
  echo "== intake directory ($t)"
  vol="$(new_volume "$t-intake-owner")"
  prepare_volume "$vol" 1000:1000 0755 "uidtest-$t"
  check_as() { # uid:gid
    docker run --rm --pull never "$(platform_of "$t")" --user "$1" -v "$vol:/var/lib/runline/intake:rw" \
      -e TRANSCRIBE_INTAKE_ROOT=/var/lib/runline/intake --entrypoint runline-transcribe-check "uidtest-$t" --skip-vad 2>&1
  }
  out="$(check_as 1000:1000)"; status=$?
  expect "$t: the owner 1000:1000 passes runline-transcribe-check" "$out" "intake root '/var/lib/runline/intake' is readable and writable"
  expect "$t: and every check passes" "$out" 'all checks passed'
  out="$(check_as 2345:2345)"
  expect "$t: another uid is refused and told what is missing" "$out" "FAIL +intake root '/var/lib/runline/intake' is not writable by uid 2345"
  out="$(check_as 10001:999)"
  expect "$t: the default account is refused on a directory of 1000:1000" "$out" "FAIL +intake root '/var/lib/runline/intake' is not writable by uid 10001"
  docker run --rm --pull never "$(platform_of "$t")" --user 10001:999 -v "$vol:/var/lib/runline/intake" -e TRANSCRIBE_INTAKE_ROOT=/var/lib/runline/intake \
    --entrypoint runline-transcribe-check "uidtest-$t" --skip-vad >/dev/null 2>&1
  expect_equal "$t: and its exit status is not 0" "$([ $? -ne 0 ] && echo failed)" "failed"
  # A work directory under a uid with no passwd entry.
  out="$(docker run --rm --pull never "$(platform_of "$t")" --user 2345:2345 -v "$vol:/var/lib/runline/intake" -e TRANSCRIBE_INTAKE_ROOT=/var/lib/runline/intake --entrypoint runline-transcribe-check "uidtest-$t" --skip-vad 2>&1)"
  expect "$t: the work directory is fine for a uid without passwd entry" "$out" "work directory '/tmp' is writable"
done

# --- The browser probe as an arbitrary uid (browser stages).
for t in $targets; do
  case "$t" in browser | browser-transcribe) ;; *) continue ;; esac
  docker image inspect "uidtest-$t" >/dev/null 2>&1 || continue
  for ug in 1000:1000 2345:2345; do
    out="$(docker run --rm --pull never --platform linux/amd64 --init --user "$ug" "uidtest-$t" runline-browser-probe 2>&1)"
    status=$?
    expect_equal "$t as $ug: runline-browser-probe exits 0" "$status" "0"
    expect_not "$t as $ug: Chrome starts and answers" "$out" 'could not read the renderer'
    expect "$t as $ug: a renderer is reported" "$out" "renderer='"
  done
done

# --- Condition 5: the init container of compose.registry.yaml gives shared and resources to the user in
# RUNLINE_USER (default 10001:10001) and the Engine and the migration run as that user.
echo "== compose.registry.yaml"
reg="$work/registry"; mkdir -p "$reg"
cp "$root/deploy/docker/compose.registry.yaml" "$reg/compose.registry.yaml"
cp "$root/deploy/docker/.env.example" "$reg/.env"
data_volume="$(new_volume registry-data)"
cat >"$reg/override.yaml" <<YAML
services:
  init:
    platform: !reset null
    volumes: !override
      - type: volume
        source: data
        target: /data
volumes:
  data:
    external: true
    name: $data_volume
YAML
reg_compose() { # extra env..., then compose args after --
  local envs=()
  while [ "$1" != "--" ]; do envs+=("$1"); shift; done; shift
  env -i PATH="$PATH" HOME="$HOME" "RUNLINE_IMAGE=uidtest-engine" "RUNLINE_DATA_DIR=/unused" "TRUSTED_CA_FILE=$reg/ca.crt" "${envs[@]}" \
    docker compose -p "$run_id-reg" -f "$reg/compose.registry.yaml" -f "$reg/override.yaml" "$@"
}
: >"$reg/ca.crt"
if docker image inspect uidtest-engine >/dev/null 2>&1; then
  owner_of() { docker run --rm --pull never -v "$data_volume:/data:ro" --entrypoint sh uidtest-engine -c 'stat -c "%u:%g" /data/shared /data/resources' | tr '\n' ' '; }
  clear_data() { docker run --rm --pull never -u root -v "$data_volume:/data" --entrypoint sh uidtest-engine -c 'rm -rf /data/shared /data/resources'; }

  cfg="$(reg_compose -- config 2>&1)"
  expect_not "default: the Engine is given no user" "$(printf '%s\n' "$cfg" | awk '/^  engine:/{on=1;next} /^  [a-z]/{on=0} on')" '^    user:'
  reg_compose -- run --rm --no-deps init >"$work/init-default.log" 2>&1 || { fail "default: init fails"; sed 's/^/     /' "$work/init-default.log"; }
  expect_equal "default: shared and resources belong to 10001:10001" "$(owner_of)" "10001:10001 10001:10001 "
  clear_data
  cfg="$(reg_compose RUNLINE_USER=1000:1000 -- config 2>&1)"
  expect "RUNLINE_USER=1000:1000: the Engine runs as it" "$(printf '%s\n' "$cfg" | awk '/^  engine:/{on=1;next} /^  [a-z]/{on=0} on')" '^    user: ["]?1000:1000'
  expect "RUNLINE_USER=1000:1000: the migration runs as it" "$(printf '%s\n' "$cfg" | awk '/^  migrate:/{on=1;next} /^  [a-z]/{on=0} on')" '^    user: ["]?1000:1000'
  reg_compose RUNLINE_USER=1000:1000 -- run --rm --no-deps init >"$work/init-1000.log" 2>&1 || { fail "1000:1000: init fails"; sed 's/^/     /' "$work/init-1000.log"; }
  expect_equal "RUNLINE_USER=1000:1000: shared and resources belong to 1000:1000" "$(owner_of)" "1000:1000 1000:1000 "
  clear_data
  reg_compose RUNLINE_USER=2345:2345 -- run --rm --no-deps init >"$work/init-2345.log" 2>&1 || fail "2345:2345: init fails"
  expect_equal "RUNLINE_USER=2345:2345: shared and resources belong to 2345:2345" "$(owner_of)" "2345:2345 2345:2345 "
fi

# --- The comments of the keystore and OIDC overlays speak of the uid the Engine runs as.
for f in compose.keystore.yaml compose.oidc.yaml; do
  expect_not "$f: no fixed uid 10001 in the text" "$(grep -n 'uid 10001' "$root/deploy/docker/$f")" 'uid 10001'
done

echo
[ "$failures" -eq 0 ] && echo "all arbitrary-uid checks passed" || { echo "$failures check(s) failed"; exit 1; }
