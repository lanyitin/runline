#!/usr/bin/env bash
# 本機一鍵執行 Engine + Console，附示範資料。用法見 dev/README.md 或 `dev/dev.sh help`。
#
# 這個腳本只是「把環境變數備齊再啟動」：Engine 的設定仍然只來自環境變數（12-factor），
# 這裡設定的值全是本機示範用，不是任何環境的設定。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEV="$ROOT/dev"
STATE="$DEV/.runline"            # 已被 .gitignore 的 .runline/ 規則涵蓋
DIST="$ROOT/engine/build/engine-dist"
SAMPLES_OUT="$DEV/sample-pipelines/build/pipelines"
PID_FILE="$STATE/engine.pid"
LOG_FILE="$STATE/engine.log"

# 都可以用環境變數覆寫。
CONTAINER="${RUNLINE_DEV_CONTAINER:-runline-dev-postgres}"
PG_PORT="${RUNLINE_DEV_PG_PORT:-55432}"
PORT="${RUNLINE_DEV_PORT:-8080}"
DEV_TOKEN="${RUNLINE_DEV_DEVELOPER_TOKEN:-demo-developer-token}"
ADMIN_TOKEN="${RUNLINE_DEV_ADMIN_TOKEN:-demo-admin-token}"
READY_TIMEOUT="${RUNLINE_DEV_READY_TIMEOUT:-120}"

say() { printf '%s\n' "$*"; }
die() { printf '錯誤：%s\n' "$*" >&2; exit 1; }

usage() {
  cat <<EOF
用法：dev/dev.sh <子命令>

  start [--detach] [--no-build]
        備齊並啟動：PostgreSQL 容器 -> 組裝 Engine（engineDistribution）-> 示範 jar -> 遷移 -> Engine。
        預設留在前景，Ctrl-C 即關閉並清理；--detach 在就緒後立刻回到 shell（之後用 stop 關閉）。
        --no-build 略過 Gradle（沿用現有的 engine.jar 與示範 jar）。
  stop  關閉 Engine，移除 PostgreSQL 容器（資料隨容器消失）。重複執行沒有副作用。
  status 顯示 Engine 與容器狀態。
  samples 只建置示範 pipeline jar，並印出路徑。
  logs  跟隨 Engine 的輸出（${LOG_FILE#"$ROOT"/}）。
  clean 同 stop，並刪除本機狀態目錄（${STATE#"$ROOT"/}：run 目錄、log、pid）。
  help  顯示這段說明。

可用環境變數覆寫：RUNLINE_DEV_PORT（${PORT}）、RUNLINE_DEV_PG_PORT（${PG_PORT}）、
RUNLINE_DEV_CONTAINER、RUNLINE_DEV_DEVELOPER_TOKEN、RUNLINE_DEV_ADMIN_TOKEN、RUNLINE_DEV_JAVA（JDK 25 的 java）。
Docker 位置（例如 colima 的 DOCKER_HOST）一律從目前的環境繼承。
EOF
}

# ---- 前置檢查 -------------------------------------------------------------------------------

require_docker() {
  command -v docker >/dev/null || die "找不到 docker 指令。請安裝 Docker 相容環境（例如 Docker Desktop 或 colima）。"
  docker info >/dev/null 2>&1 || die "連不上 Docker。若使用 colima，請先 colima start，並在目前 shell 設定 DOCKER_HOST（例如 export DOCKER_HOST=unix://\$HOME/.colima/default/docker.sock），詳見 docs/stable/dev-environment.md。"
}

java_major() { "$1" -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -n1; }

# Engine 以 JDK 25 建置，所以需要 25 以上。依序找：RUNLINE_DEV_JAVA、JAVA_HOME、PATH、Gradle 下載的 toolchain。
find_java() {
  local candidates=() c
  [ -n "${RUNLINE_DEV_JAVA:-}" ] && candidates+=("$RUNLINE_DEV_JAVA")
  [ -n "${JAVA_HOME:-}" ] && candidates+=("$JAVA_HOME/bin/java")
  command -v java >/dev/null && candidates+=("$(command -v java)")
  for c in "$HOME"/.gradle/jdks/*/*/Contents/Home/bin/java "$HOME"/.gradle/jdks/*/*/bin/java \
    "$HOME"/.gradle/jdks/*/Contents/Home/bin/java "$HOME"/.gradle/jdks/*/bin/java; do
    [ -x "$c" ] && candidates+=("$c")
  done
  for c in "${candidates[@]+"${candidates[@]}"}"; do
    [ -x "$c" ] || continue
    local major; major="$(java_major "$c")"
    if [ -n "$major" ] && [ "$major" -ge 25 ]; then JAVA_BIN="$c"; return 0; fi
  done
  die "找不到 JDK 25 以上。請設定 RUNLINE_DEV_JAVA 或 JAVA_HOME；或先執行一次 ./gradlew :engine:engineDistribution，讓 Gradle 下載 toolchain（~/.gradle/jdks）。"
}

port_in_use() { (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null; }

engine_pid() {
  [ -f "$PID_FILE" ] || return 1
  local pid; pid="$(cat "$PID_FILE")"
  kill -0 "$pid" 2>/dev/null && printf '%s' "$pid"
}

container_state() { docker inspect -f '{{.State.Status}}' "$CONTAINER" 2>/dev/null || true; }

# ---- 各步驟 ---------------------------------------------------------------------------------

start_postgres() {
  case "$(container_state)" in
    running) say "PostgreSQL：沿用執行中的容器 $CONTAINER" ;;
    "")
      port_in_use "$PG_PORT" && die "本機埠 $PG_PORT 已被占用。請設定 RUNLINE_DEV_PG_PORT 換一個埠。"
      say "PostgreSQL：啟動 postgres:17-alpine（容器 ${CONTAINER}，本機埠 ${PG_PORT}）"
      docker run -d --name "$CONTAINER" \
        -e POSTGRES_DB=runline -e POSTGRES_USER=runline -e POSTGRES_PASSWORD=runline \
        -p "127.0.0.1:$PG_PORT:5432" postgres:17-alpine >/dev/null ;;
    *)
      say "PostgreSQL：重新啟動既有容器 $CONTAINER"
      docker start "$CONTAINER" >/dev/null ;;
  esac
  local i
  for i in $(seq 1 60); do
    # 初始化期間 postgres 會短暫重啟，連續兩次成功才算就緒。
    if docker exec "$CONTAINER" pg_isready -U runline -d runline -q 2>/dev/null; then
      sleep 1
      docker exec "$CONTAINER" pg_isready -U runline -d runline -q 2>/dev/null && return 0
    fi
    sleep 1
  done
  die "PostgreSQL 在 60 秒內沒有就緒（docker logs $CONTAINER 可查看）。"
}

build_dist() {
  say "建置：./gradlew :engine:engineDistribution（含 Console；需要 .node-version 指定的 Node，見 README）"
  (cd "$ROOT" && ./gradlew --console=plain :engine:engineDistribution) || die "engineDistribution 失敗。"
}

build_samples() {
  say "建置示範 pipeline jar：./gradlew -p dev/sample-pipelines pipelineJars"
  (cd "$ROOT" && ./gradlew --console=plain -p dev/sample-pipelines pipelineJars) || die "示範 jar 建置失敗。"
}

print_samples() {
  say "示範 pipeline jar（在 Console 的上傳頁選取）："
  local f
  for f in "$SAMPLES_OUT"/demo-*.jar; do say "  $f"; done
}

# Engine 與遷移共用同一組設定（同一份環境變數）。
export_engine_env() {
  export PORT
  export POSTGRES_URL="jdbc:postgresql://localhost:$PG_PORT/runline"
  export POSTGRES_USER=runline POSTGRES_PASSWORD=runline
  export API_TOKENS="dev:developer:$DEV_TOKEN,admin:admin:$ADMIN_TOKEN"
  export RUNLINE_RUNTIME_DIR="$DIST/run-runtime"
  export RUNLINE_MAX_CONCURRENT_RUNS=2
  export RUNLINE_SHARED_ROOT="$STATE/shared" RUNLINE_RUN_ROOT="$STATE/runs"
  export RUNLINE_WORKSPACE_MAX_BYTES=104857600
  export RUNLINE_FAILED_RUN_RETENTION_SECONDS=3600
  export RUNLINE_SHUTDOWN_GRACE_SECONDS=10
  # 本機沒有 OTLP collector；不關掉的話，關閉時會等 exporter 逾時。
  export OTEL_TRACES_EXPORTER=none OTEL_LOGS_EXPORTER=none OTEL_METRICS_EXPORTER=none
  mkdir -p "$RUNLINE_SHARED_ROOT" "$RUNLINE_RUN_ROOT"
}

migrate() {
  say "遷移資料庫（與 Engine 同一份程式碼與設定）"
  "$JAVA_BIN" -cp "$DIST/engine.jar" dev.lawlan.runline.engine.db.MigrateKt >"$STATE/migrate.log" 2>&1 \
    || { tail -n 20 "$STATE/migrate.log" >&2; die "遷移失敗（完整輸出：$STATE/migrate.log）。"; }
}

start_engine() {
  port_in_use "$PORT" && die "本機埠 $PORT 已被占用。請設定 RUNLINE_DEV_PORT 換一個埠。"
  say "Engine：啟動 engine.jar（輸出寫入 ${LOG_FILE#"$ROOT"/}）"
  nohup "$JAVA_BIN" -jar "$DIST/engine.jar" >"$LOG_FILE" 2>&1 &
  echo $! >"$PID_FILE"
  local i pid
  pid="$(cat "$PID_FILE")"
  for i in $(seq 1 "$READY_TIMEOUT"); do
    kill -0 "$pid" 2>/dev/null || { tail -n 30 "$LOG_FILE" >&2; die "Engine 啟動後結束了（輸出：${LOG_FILE}）。"; }
    if [ "$(curl -s -o /dev/null -w '%{http_code}' "http://localhost:$PORT/api/v1/health/ready" || true)" = 200 ]; then
      return 0
    fi
    sleep 1
  done
  tail -n 30 "$LOG_FILE" >&2
  die "Engine 在 ${READY_TIMEOUT} 秒內沒有就緒（輸出：${LOG_FILE}）。"
}

stop_engine() {
  local pid
  if pid="$(engine_pid)"; then
    say "Engine：送出 SIGTERM（pid ${pid}），等待優雅關閉"
    kill -TERM "$pid" 2>/dev/null || true
    local i
    for i in $(seq 1 60); do kill -0 "$pid" 2>/dev/null || break; sleep 1; done
    if kill -0 "$pid" 2>/dev/null; then
      say "Engine：60 秒內沒有結束，強制終止"
      kill -KILL "$pid" 2>/dev/null || true
    fi
  fi
  rm -f "$PID_FILE"
}

stop_postgres() {
  if command -v docker >/dev/null && [ -n "$(container_state)" ]; then
    say "PostgreSQL：移除容器 $CONTAINER"
    docker rm -f "$CONTAINER" >/dev/null
  fi
}

# ---- 子命令 ---------------------------------------------------------------------------------

cmd_start() {
  local detach=0 build=1 arg
  for arg in "$@"; do
    case "$arg" in
      --detach|-d) detach=1 ;;
      --no-build) build=0 ;;
      *) die "start 不認得參數 $arg" ;;
    esac
  done
  engine_pid >/dev/null && die "Engine 已在執行（pid $(engine_pid)）。先執行 dev/dev.sh stop。"
  require_docker
  mkdir -p "$STATE"
  if [ "$build" = 1 ]; then build_dist; build_samples; fi
  [ -f "$DIST/engine.jar" ] || die "$DIST/engine.jar 不存在；請不帶 --no-build 再執行一次。"
  find_java
  export_engine_env
  trap 'say; say "收到中斷，關閉中……"; cmd_stop; exit 0' INT TERM
  # 啟動到一半失敗時也清掉，避免留下容器或行程。
  trap_fail() { local rc=$?; [ $rc -ne 0 ] && { cmd_stop >/dev/null 2>&1 || true; }; }
  trap trap_fail EXIT
  start_postgres
  migrate
  start_engine
  trap - EXIT
  cat <<EOF

================================================================
 Engine 就緒：  http://localhost:$PORT
 登入 token（貼到 Console 的登入頁）：
   developer  $DEV_TOKEN
   admin      $ADMIN_TOKEN
 PostgreSQL：   localhost:${PG_PORT}（資料庫 runline，使用者與密碼 runline）
 Engine 輸出：  $LOG_FILE
================================================================
EOF
  print_samples
  if [ "$detach" = 1 ]; then
    say "已在背景執行；用 dev/dev.sh stop 關閉。"
    return 0
  fi
  say "按 Ctrl-C 關閉並清理。"
  local pid; pid="$(cat "$PID_FILE")"
  # wait 無法等非子行程的情況不會發生：Engine 是這個 shell 的子行程。
  while kill -0 "$pid" 2>/dev/null; do sleep 1; done
  say "Engine 已結束（輸出：${LOG_FILE}）；清理中。"
  cmd_stop
}

cmd_stop() {
  stop_engine
  stop_postgres
  say "已停止。"
}

cmd_status() {
  local pid
  if pid="$(engine_pid)"; then say "Engine：執行中（pid ${pid}，http://localhost:${PORT}）"; else say "Engine：未執行"; fi
  if command -v docker >/dev/null && docker info >/dev/null 2>&1; then
    local st; st="$(container_state)"
    say "PostgreSQL 容器 ${CONTAINER}：${st:-不存在}"
  else
    say "PostgreSQL 容器：無法查詢（連不上 Docker）"
  fi
}

cmd_samples() { build_samples; print_samples; }

cmd_logs() { [ -f "$LOG_FILE" ] || die "還沒有 Engine 輸出（${LOG_FILE}）。"; exec tail -n 100 -f "$LOG_FILE"; }

cmd_clean() { cmd_stop; rm -rf "$STATE"; say "已刪除 $STATE"; }

case "${1:-help}" in
  start) shift; cmd_start "$@" ;;
  stop) cmd_stop ;;
  status) cmd_status ;;
  samples) cmd_samples ;;
  logs) cmd_logs ;;
  clean) cmd_clean ;;
  help|-h|--help) usage ;;
  *) usage >&2; exit 2 ;;
esac
