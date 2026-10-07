# 本機一鍵執行 Engine + Console（附示範資料）

`dev/dev.sh` 在你的機器上啟動 PostgreSQL（容器）、Engine 與 Console，並建置幾個示範 pipeline jar，讓你用瀏覽器或 curl 直接操作。僅供本機試用：token、密碼都是示範值，不要用在任何共用環境。

## 前置需求

| 項目 | 說明 |
|---|---|
| Docker 相容環境 | Docker Desktop 或 colima。腳本只繼承目前 shell 的環境，所以用 colima 時，要先 `colima start`，並讓 `DOCKER_HOST` 在這個 shell 生效（例如 `export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock`，見 `docs/stable/dev-environment.md`）。`docker info` 能成功即可 |
| JDK 25 | 腳本依序找 `RUNLINE_DEV_JAVA`、`JAVA_HOME`、`PATH` 上的 `java`、Gradle 下載的 toolchain（`~/.gradle/jdks`），找到 25 以上就用；都沒有時先跑一次 `./gradlew :engine:engineDistribution` 讓 Gradle 下載 |
| Node | Console 的建置需要 `.node-version` 指定的精確版本（見根目錄 README 的 Node toolchain）。第一次建置會 `npm ci`，需要網路 |
| 網路 | 第一次需要下載 Gradle 相依、npm 套件與 `postgres:17-alpine` 映像檔（已在本機則不需要） |
| 本機埠 | 8080（Engine）與 55432（PostgreSQL，只綁 127.0.0.1）；被占用時用 `RUNLINE_DEV_PORT`、`RUNLINE_DEV_PG_PORT` 換 |

## 啟動

```bash
dev/dev.sh start
```

它依序做：啟動 `postgres:17-alpine` 容器 -> `./gradlew :engine:engineDistribution`（Engine 與 Console）-> 建置示範 jar -> 資料庫遷移 -> 啟動 `engine.jar` -> 等 `/api/v1/health/ready` 回 200。第一次建置要幾分鐘，之後約十秒內就緒。就緒後會印出：

```
 Engine 就緒：  http://localhost:8080
 登入 token（貼到 Console 的登入頁）：
   developer  demo-developer-token
   admin      demo-admin-token
```

腳本停在前景，**Ctrl-C 即關閉 Engine 並移除容器**。想把終端機還給自己：`dev/dev.sh start --detach`，之後用 `dev/dev.sh stop`。其他子命令：`status`、`logs`（跟隨 Engine 輸出）、`samples`（只重建示範 jar）、`clean`（stop 並刪除 `dev/.runline/`）。`start --no-build` 略過 Gradle（沿用現有產物）。

Engine 的設定全由環境變數給（腳本把它們備齊；全是本機示範值）：`PORT`、`POSTGRES_URL`／`POSTGRES_USER`／`POSTGRES_PASSWORD`、`API_TOKENS`、`RUNLINE_RUNTIME_DIR`（`engine/build/engine-dist/run-runtime`）、`RUNLINE_MAX_CONCURRENT_RUNS=2` 等，詳見腳本的 `export_engine_env` 與根目錄 README。run 的目錄與 Engine 輸出在 `dev/.runline/`（已被 `.gitignore`）。

## 登入

瀏覽器開 <http://localhost:8080>，貼上 token：

- `demo-developer-token`：開發人員，只看得到自己上傳的版本與自己建立的 run。
- `demo-admin-token`：管理員，看得到全部，並能設定 trigger、白名單、共享資源、unsafe 執行。

換 token：啟動前設 `RUNLINE_DEV_DEVELOPER_TOKEN`、`RUNLINE_DEV_ADMIN_TOKEN`。

## 示範 pipeline

`dev/dev.sh start`（或 `dev/dev.sh samples`）會在 `dev/sample-pipelines/build/pipelines/` 產生四個 jar（不夾帶 core，上傳不會被 `core_classes_bundled` 拒絕）。原始碼在 `dev/sample-pipelines/src/main/kotlin/samples/`，是獨立的 Gradle build（以 composite build 取得 core，不影響根專案的建置）。

| jar | pipeline | 預期的判定與行為 | 用它觀察什麼 |
|---|---|---|---|
| `demo-slow.jar` | `demo-slow` | SAFE。參數 `label`（預設 `demo`）、`steps`（預設 30）、`delayMillis`（預設 1000），預設約 30 秒、輸出 30 多行 log（含 stderr） | QUEUED -> RUNNING -> SUCCEEDED、即時 log、**取消**（跑到一半取消，得 CANCELLED）、參數面板（試試未宣告的參數會得 422 `invalid_parameters`） |
| `demo-failing.jar` | `demo-failing` | SAFE。約 2 秒後丟 `IllegalStateException`，得 FAILED。參數 `reason` 可改訊息 | failure 面板（型別、訊息、堆疊） |
| `demo-unsafe.jar` | `demo-unsafe` | **UNSAFE**：原因為網路與行程未設限（`UNRESTRICTED_ACCESS`）與 `java.io.File` 不在白名單（`NOT_ALLOW_LISTED`） | 判定原因；以開發人員建 run 得 409 `unsafe_not_allowed`；用 admin token 在 unsafe 設定允許後，run 才能建立並成功（只印出暫存目錄是否存在） |
| `demo-resource.jar` | `demo-resource` | SAFE，宣告共享資源 `demo-printer`，持有約 20 秒 | 上傳後有警告「尚未定義」；建 run 得 409 `resources_unavailable`；admin 在共享資源頁定義 `demo-printer`（容量 1）後可建；連建兩個 run，第二個為 WAITING_FOR_RESOURCES；停用資源則等待中的 run 失敗、新 run 再得 409 |

建議流程：

1. 用 developer token 登入，上傳四個 jar（上傳頁或 curl，見下）。
2. `demo-slow`：建 run，看 log，約 10 秒時取消。再建一次讓它跑完。
3. `demo-failing`：建 run，看 failure 面板。
4. `demo-unsafe`：看判定原因，建 run 被拒；改用 admin token 允許 unsafe，再建 run。
5. `demo-resource`：先建 run 被拒；admin 定義 `demo-printer`；連建兩個 run。
6. admin：把 `demo-slow` 綁一個 cron trigger（例如 `* * * * *`，每分鐘），或白名單頁調整條目，觀察 `demo-unsafe` 的判定如何變化。

## 若 Console 的某個頁面尚未完成：用 curl

Console 是分階段實作的；頁面還是占位時，可用 API 完成同樣操作（`docs/stable/pipeline-engine/08-api.md` 有全部端點）。`J` 為 jar 目錄：

```bash
B=http://localhost:8080; D="Authorization: Bearer demo-developer-token"; A="Authorization: Bearer demo-admin-token"
J=dev/sample-pipelines/build/pipelines
# 上傳（回傳含 contentHash 與判定）
curl -s -X POST -H "$D" -H 'Content-Type: application/octet-stream' --data-binary @$J/demo-slow.jar $B/api/v1/artifacts
# 建 run（把 <hash> 換成上傳回傳的 contentHash）
curl -s -X POST -H "$D" -H 'Content-Type: application/json' \
  -d '{"contentHash":"<hash>","pipeline":"demo-slow","parameters":{"steps":"20"}}' $B/api/v1/runs
curl -s -H "$D" $B/api/v1/runs/<runId>              # 狀態與 failure
curl -s -H "$D" "$B/api/v1/runs/<runId>/log"        # log
curl -s -X POST -H "$D" $B/api/v1/runs/<runId>/cancel
# 管理員：允許 unsafe、定義共享資源
curl -s -X PUT -H "$A" -H 'Content-Type: application/json' -d '{"allow":true}' $B/api/v1/definitions/<hash>/demo-unsafe/unsafe-execution
curl -s -X POST -H "$A" -H 'Content-Type: application/json' -d '{"name":"demo-printer","capacity":1}' $B/api/v1/resources
```

另有免認證的 `GET /api/v1/info`（版本與 commit）與 `GET /api/v1/health/ready`。

## 停止與清理

- 前景執行：Ctrl-C。
- 背景執行（`--detach`）或任何殘留：`dev/dev.sh stop`。它對 Engine 送 SIGTERM 等待優雅關閉（最久 60 秒，之後強制終止），並移除 PostgreSQL 容器；重複執行沒有副作用。
- 資料（上傳的 jar、run、trigger、共享資源、白名單）存在容器內，**stop 後就沒了**，下次 start 是全新的資料庫。
- `dev/dev.sh clean`：stop，並刪除 `dev/.runline/`（run 目錄、Engine 輸出）。示範 jar 在 `dev/sample-pipelines/build/`，可留著。
- 確認乾淨：`docker ps -a --filter name=runline-dev` 應為空；`pgrep -fl engine.jar` 應無輸出。

## 常見問題

| 現象 | 處理 |
|---|---|
| `連不上 Docker` | 用 colima：`colima start`，並在**執行腳本的這個 shell** 設好 `DOCKER_HOST` |
| `找不到 JDK 25 以上` | 設 `RUNLINE_DEV_JAVA=/path/to/jdk-25/bin/java`，或先跑一次 `./gradlew :engine:engineDistribution` |
| `verifyNode` 失敗 | Node 版本必須等於 `.node-version`；安裝該版本並確認 `node --version`（見根目錄 README 的 Node toolchain） |
| `埠 8080（或 55432）已被占用` | `RUNLINE_DEV_PORT=8081 dev/dev.sh start`；PostgreSQL 用 `RUNLINE_DEV_PG_PORT` |
| Engine 啟動後立刻結束 | 看腳本印出的最後 30 行，或 `dev/.runline/engine.log`；最常見是設定缺漏（Engine 會明說哪個設定有問題） |
| 就緒等很久 | 逾時預設 120 秒（`RUNLINE_DEV_READY_TIMEOUT`）；第一次建置 Console 的時間不計入此處 |
| Gradle 卡在等鎖 | 另一個 Gradle 在跑（例如 IDE 或另一個終端機），等它結束再試，不要刪鎖檔 |
| 上傳 422 `core_classes_bundled` | 自己的 jar 夾帶了 core；示範 jar 不會有這個問題 |
| 上一次異常中斷留下容器 | `dev/dev.sh stop`；下次 `start` 也會沿用或重啟同名容器 |
