# digest-rss 上線手冊

本文回答：管理員如何把 digest-rss pipeline 在 Engine 上線、第一次怎麼安全地執行、之後怎麼查結果與維運、出了問題怎麼查。狀態：已核可（2026-10-09）。

驗證範圍：本文的上傳、允許、資源、綁定、trigger、查詢、備份、保留、新版本升級、各失敗路徑與其訊息，都在打包的真實 Engine（JDK 25、PostgreSQL 17、真實 Chrome）上以 `runline-digest-rss` 的 `./gradlew endToEndTest` 執行過，外部服務是專案內的 Fake（真的在 port 上監聽）；錯誤訊息與 log 是那次實際輸出，不是推測。引自既有文件而沒有在端到端重做的地方（Docker 與 systemd 的設定、GPU、環境變數的逾時行為）會標明「文件所述」。**真實的 FreshRSS、Lemonade、rqbit、streamexa、目標站點與目標主機（Linux、Intel iGPU）沒有在本項驗證**，由使用者依 [WI-1017](work-items/WI-1017-first-real-run.md) 執行；那裡發現的差異會回到本文。驗證與未驗證的完整清單見附錄 B。

> **危險組合：`condition=none` 加 `markRead=on`。** `condition=none` 讓讀者把**所有擷取成功的文章**記為保留（不做任何主題過濾）。加上 `markRead=on`，這些文章**全部在 FreshRSS 標為已讀（不可逆）**，並且**全部啟動下載**（有 magnet 或 torrent 的送 rqbit，其餘以文章網址送 streamexa）。端到端測試確認了這一點：`condition=none` 的 run 保留了每一篇可讀的文章，並對每一篇派發下載（有給 `readerModel` 時摘要由模型寫，沒給時 digest 只有事實，兩者都不改變保留、標記與下載）。`condition` 沒有預設，漏填是呼叫錯誤，所以不會因疏忽落入這個組合；要用 `condition=none`，請保持 `markRead=off`。
>
> **第一次真實執行一律 `markRead=off`。** 開啟 `markRead=on` 之前必須完成 WI-1017 的第 5、6 步（見第 7 節）。

## 1. 先讀這些：它是什麼、一次 run 做什麼

pipeline 名稱 `digest-rss`，一個 jar（`runline-digest-rss.jar`，薄 jar：只用 JDK、core 與 Kotlin 標準函式庫，沒有第三方函式庫）。一次 run 依序做七個階段，每個階段在 run log 有 `STAGE i/7 名稱` 與 `RESULT i/7 名稱` 兩行：

| 階段 | 做什麼 | 讀的資源 |
|---|---|---|
| 1 `collect` | 向 FreshRSS 取最多 `cap` 篇未讀，排除隔離中的文章，寫入資料庫 | `credentials`（`freshrss` 條目）、`database` |
| 2 `model-check` | 對模型服務呼叫一次 `models.list`，不通就整個 run 以成功結束（不開瀏覽器、不標記） | `llm` |
| 3 `capture` | 用本機有畫面的 Chrome 擷取每篇的封面與可見文字 | 本機 Chrome |
| 4 `judge` | 對每篇每個條件呼叫一次模型，判定並驗證引文 | `llm` |
| 5 `download` | 保留的文章：feed 內有 torrent 或 magnet 送 rqbit，其餘以文章網址送 streamexa | `credentials`（`rqbit`、`streamexa` 條目） |
| 6 `digest` | 事實（程式從資料庫算）加主題摘要（模型，thinking 關）串成 digest，檢查後存入資料庫 | `llm`、`database` |
| 7 `mark` | 閘門全部通過才在 FreshRSS 一次標記可標的文章為已讀 | `credentials`（`freshrss`） |

所有寫入只進 `database` 角色的 SQLite；pipeline 不寫任何檔案（端到端測試前後比對 Engine 的 run 目錄、共享目錄與資源根目錄，沒有差異；Chrome 的使用者資料目錄在 run 結束時被刪除）。完整行為見 [`runline-digest-rss` 的 README](../../../../runline-digest-rss/README.md)。

## 2. Engine 主機

### 2.1 需求

| 項目 | 內容 |
|---|---|
| JDK | **25**。Engine 以 JDK 21 啟動會立刻 `UnsupportedClassVersionError`（引自 [console/e2e/README.md](../../../console/e2e/README.md)，本項未重做）。Docker 映像已含，systemd 主機的 `ExecStart` 預設 `/usr/bin/java`（不同再改） |
| Engine 的建置 | `./gradlew :engine:engineDistribution`（輸出 `engine/build/engine-dist/` 的 `engine.jar` 與 `run-runtime/`，兩者不可合併）。建置 Console 要 `.node-version` 的 Node 24：在 runline 目錄執行，Gradle daemon 看到的 `PATH` 不對時先 `./gradlew --stop`。`dev/verify-console-security.sh` 同樣需要 node 24 在 `PATH` 前面。部署用發佈建置（`-Prunline.release=true`、乾淨的工作樹） |
| Chrome | 真實的 Google Chrome（目前驗證的是 155，linux/amd64；非 Chromium 衍生版，非 headless）。`CHROME_BIN` 指向它 |
| 顯示 | Linux 要有畫面：Docker 映像由 Xvfb 提供，`DISPLAY` 告知位置；systemd 主機裝 `xvfb` 或指向實體顯示。macOS 開發機不需要 |
| 硬體渲染 | 可選。`/dev/dri` 交給 Engine 容器並讓行程有權限使用 render node；沒有 GPU 時是軟體渲染（啟動 log 有警告）。要強制硬體渲染設 `CHROME_REQUIRE_HARDWARE_RENDERING=true`：渲染器是軟體時 run 在擷取前就失敗 |
| 根憑證 | FreshRSS、rqbit、streamexa 若用私有 CA 的 HTTPS，由 `RUNLINE_TRUSTED_CA_DIR` 提供 PEM，整個 JVM 信任。信任是 JVM 的設定，不是 pipeline 的 |
| init | 容器要有 init 行程回收殭屍行程（Docker 範例組態 `init: true`）；否則取消後的 Chrome 輔助行程成為殭屍 |
| 暫存目錄 | JVM 的 `java.io.tmpdir` 要可寫、可執行（不是 `noexec`）：SQLite 的原生程式庫與 Chrome 的使用者資料目錄都在這裡。`noexec` 時以 `-Dorg.sqlite.tmpdir` 指定專用目錄 |
| 網路 | Engine 到 Lemonade、三個 MCP 服務與被擷取網站可達 |

Docker 與 systemd 的設定、GPU、根憑證的驗證步驟、SQLite 原生程式庫與暫存目錄、在目標主機要做的驗證，全部在 [deploy/browser.md](../../../deploy/browser.md)；本文不重複，只列與 digest-rss 直接相關的環境變數。

### 2.2 Engine 的環境變數

設定只來自環境變數（Docker 的 `.env`、systemd 的 `runline.env`）。缺漏必填項時 Engine 列出全部缺少的項目並拒絕啟動。

| 變數 | 必填 | 說明 |
|---|---|---|
| `PORT` | 否 | HTTP 埠，預設 8080（Console 與 API 同一個埠） |
| `POSTGRES_URL`、`POSTGRES_USER`、`POSTGRES_PASSWORD` | 是 | Engine 自己的資料庫（PostgreSQL 17）。遷移是另外的一次性行程：`java -cp engine.jar dev.lawlan.runline.engine.db.MigrateKt`（Docker 的 `migrate` 服務、systemd 的 `runline-migrate.service`） |
| `API_TOKENS` | 是 | `名稱:角色:token,...`，角色 `admin` 或 `developer`；每個名稱只能出現一次（名稱是上傳的擁有者）。本文的 `$ADMIN_TOKEN` 是 admin 的那個 |
| `RUNLINE_RUNTIME_DIR` | 是 | `run-runtime/` 的位置 |
| `RUNLINE_SHARED_ROOT`、`RUNLINE_RUN_ROOT` | 是 | pipeline 的共享目錄與每個 run 的私有目錄。digest-rss 不使用它們寫檔（見上），但 Engine 仍為每個 run 建一個空的私有目錄 |
| `RUNLINE_RESOURCE_ROOT` | 是 | `file` 與 `sqlite` 資源的檔案所在（憑證檔、`digest.db`）。不可與上兩者互相包含。**這個目錄要備份** |
| `RUNLINE_MAX_CONCURRENT_RUNS` | 是 | 同時執行的 run 數；digest-rss 的 run 因模型資源容量 1 一次只有一個（第 6 節），這個值只影響別的 pipeline |
| `RUNLINE_WORKSPACE_MAX_BYTES` | 是 | 每個共享或私有目錄的容量上限（位元組）。digest-rss 不寫這些目錄，任何合理值都可以；端到端測試用 104857600（100 MiB） |
| `RUNLINE_FAILED_RUN_RETENTION_SECONDS` | 是 | 失敗、被取消或被中斷的 run 的私有目錄保留多久（秒）後被清除。端到端測試用 3600。digest-rss 的私有目錄是空的 |
| `RUNLINE_KEYSTORE_PATH`、`RUNLINE_KEYSTORE_PASSWORD_FILE` | 用金鑰時 | PKCS12 金鑰庫（模型服務的金鑰放這裡）與其密碼檔（第一行）。見 [deploy/README.md](../../../deploy/README.md) |
| `RUNLINE_RUN_TIMEOUT_SECONDS` | 否 | 對 pipeline 本體的協作式時限，預設不限。一個 run 的時間隨 `cap` 與模型速度線性成長，不要設得比最長的 run 短（文件所述，未在端到端驗證） |
| `RUNLINE_RESOURCE_WAIT_TIMEOUT_SECONDS` | 否 | run 等待它宣告的資源最久多久，預設 3600 秒，逾時 run 失敗為「等待資源逾時」。cron 的間隔要比 run 的時間長（第 6 節；文件所述，逾時本身未在端到端驗證） |
| `RUNLINE_SHUTDOWN_GRACE_SECONDS` | 否 | 優雅關閉的寬限，預設 30 秒；平台的停止逾時要比它多 15 秒 |
| `RUNLINE_RUN_RETENTION_SECONDS` | 否 | Engine 保存 run 紀錄與 log 的時間，預設 30 天。與 digest-rss 自己資料庫的保留（第 9.2 節）是兩回事 |
| `OTEL_TRACES_EXPORTER`、`OTEL_LOGS_EXPORTER`、`OTEL_METRICS_EXPORTER` | 否 | 沒有 OTLP 收集器時三個都設 `none`，否則關閉時要等匯出逾時 |
| `DISPLAY`、`CHROME_BIN`、`CHROME_ARGS`、`CHROME_REQUIRE_HARDWARE_RENDERING`、`RUNLINE_VIRTUAL_DISPLAY`、`RUNLINE_TRUSTED_CA_DIR`、`JDK_JAVA_OPTIONS` | 瀏覽器部署 | 見 [deploy/browser.md](../../../deploy/browser.md) 的環境變數表 |

端到端測試驗證了兩項主機設定確實傳到 Chrome：`CHROME_BIN` 指向不存在的檔案時 run 以 `CONFIG ERROR: the browser is not set up: CHROME_BIN is not an executable file: <路徑>` 失敗；`CHROME_REQUIRE_HARDWARE_RENDERING=true` 而 Chrome 是軟體渲染時 run 以 `BROWSER REFUSED: hardware rendering is required (CHROME_REQUIRE_HARDWARE_RENDERING=true) but the browser renders in software: <渲染器>` 失敗，兩者都在擷取任何頁面之前、也沒有標記任何文章。

啟動後確認：`curl -s $ENGINE/api/v1/health/ready` 回 `{"status":"ready"}`；Chrome 不可用不影響就緒（只有使用它的 run 失敗）。

## 3. 上傳與允許 unsafe

### 3.1 上傳

```
curl -s -X POST -H "Authorization: Bearer $ADMIN_TOKEN" -H 'Content-Type: application/octet-stream' \
  --data-binary @runline-digest-rss.jar $ENGINE/api/v1/artifacts
```

回應 201，記下 `contentHash`（`HASH`；它也是資料庫 `run.pipeline_version` 的值）。同一個擁有者重複上傳相同內容回 200 與既有的版本。

### 3.2 analyzer 回報的原因

回應的 `pipelines[0].verdict` 是 `UNSAFE`，`allowUnsafeExecution` 是 `false`。`reasons` 在端到端測試中的實際輸出共 37 項，是這三類，且**沒有任何第三方函式庫**：

1. `UNRESTRICTED_ACCESS`：`NETWORK` 與 `PROCESSES`（pipeline 宣告網路與行程不設限：呼叫外部服務、啟動 Chrome）。
2. `IO_SENSITIVE_MEMBER`：`java.lang.ProcessBuilder.start()Ljava/lang/Process;`（啟動 Chrome）。
3. `NOT_ALLOW_LISTED`：34 個 JDK 與 Kotlin 標準函式庫的類別，來自四件事：自己的 jar 雜湊（`MessageDigest`、`CodeSource`、`ProtectionDomain`、`URL`、`InputStream`、`ByteStreamsKt`）、資料庫紀錄（`Serializable`）、外部服務的 HTTP 與 TLS（`java.net.http.*`、`URI`、`SSLException`、`IOException`）、Chrome 的 DevTools 協定與它自己的使用者資料目錄（`java.net.http.WebSocket*`、`ByteArrayOutputStream`、`java.io.File`、`java.nio.file.*`、`kotlin.io.FilesKt`）。

完整的類別清單（`RunbookReasonsTest` 比對它與 analyzer 對打包 jar 的輸出，兩者不一致時測試失敗）：

```text
-- list: unsafe-classes
java.io.ByteArrayOutputStream
java.io.File
java.io.IOException
java.io.InputStream
java.io.Serializable
java.net.URI
java.net.URISyntaxException
java.net.URL
java.net.http.HttpClient
java.net.http.HttpClient$Builder
java.net.http.HttpClient$Version
java.net.http.HttpHeaders
java.net.http.HttpRequest
java.net.http.HttpRequest$BodyPublisher
java.net.http.HttpRequest$BodyPublishers
java.net.http.HttpRequest$Builder
java.net.http.HttpResponse
java.net.http.HttpResponse$BodyHandler
java.net.http.HttpResponse$BodyHandlers
java.net.http.HttpTimeoutException
java.net.http.WebSocket
java.net.http.WebSocket$Builder
java.net.http.WebSocket$Listener
java.nio.file.DirectoryStream
java.nio.file.Files
java.nio.file.LinkOption
java.nio.file.Path
java.nio.file.attribute.FileAttribute
java.security.CodeSource
java.security.MessageDigest
java.security.ProtectionDomain
javax.net.ssl.SSLException
kotlin.io.ByteStreamsKt
kotlin.io.FilesKt
```

```text
-- list: unsafe-members
java.lang.ProcessBuilder.start()Ljava/lang/Process;
```

這份清單隨 pipeline 的版本變動；新版本多出清單以外的原因（尤其是第三方函式庫的類別，或不在上表用途內的 JDK 類別）時，不要直接允許，先問開發者。

### 3.3 白名單：不需要新增條目

白名單是管理員的信任決定（被放行的類別內部不再被檢查）。digest-rss 沒有第三方函式庫，所以**不需要新增任何條目**，也不應為了它放行 `java.net.http` 之類的 JDK 套件：端到端測試以預覽驗證，加上 `java.net.http` 套件的條目後 `becameSafe` 仍是 0——pipeline 宣告的網路與行程不設限不是白名單能改變的，它永遠是 unsafe。檢視目前的白名單：`curl -s -H "Authorization: Bearer $ADMIN_TOKEN" $ENGINE/api/v1/allowlist`（首次啟動的預設內容 44 個條目）；預覽一個變更：`POST /api/v1/allowlist/entries?preview=true`，不改變任何東西。

### 3.4 允許這個版本以 unsafe 執行

在允許之前，建立 run 回 `409 unsafe_not_allowed`。允許是逐版本的，**新版本不繼承**（第 9.4 節）：

```
curl -s -X PUT -H "Authorization: Bearer $ADMIN_TOKEN" -H 'Content-Type: application/json' \
  -d '{"allow": true}' $ENGINE/api/v1/definitions/$HASH/digest-rss/unsafe-execution
```

回應 200 `{contentHash, uploader, pipeline, allow: true, setBy, setAt}`。同一內容有多位上傳者的版本時要加查詢參數 `?uploader=名稱`（否則 409 `ambiguous_version`）。

## 4. 資源與綁定

pipeline 宣告三個**角色**（程式只認角色名稱）；管理員建立資源，再把角色綁定到資源。資源的名稱與角色名稱是兩回事（下例用 `lemonade`、`digest-sqlite`、`service-credentials`）。**三個角色在 Engine 上都必須綁定（或有同名資源），包括 `condition=none` 的 run**：`llm` 沒有解析到資源時，連 `condition=none` 的 run 也被拒絕為 `409 resources_unavailable`，`problem` 為 `unbound`（端到端測試驗證；devkit 的本機定義可以省略 `llm`，Engine 不行）。

| 角色 | 型別 | 該選什麼資源 |
|---|---|---|
| `llm` | `openai-compatible` | 指向 Lemonade 的資源；模型須支援圖片輸入與對話；容量 1 |
| `database` | `jdbc-pool` | `sqlite` 種類，指向資源根目錄下的 `digest.db`；需可寫入 |
| `credentials` | `file` | 指向資源根目錄下的憑證檔；pipeline 只讀取 |

### 4.1 `llm`：openai-compatible 資源

```
curl -s -X POST -H "Authorization: Bearer $ADMIN_TOKEN" -H 'Content-Type: application/json' \
  $ENGINE/api/v1/resources -d '{
    "name": "lemonade", "type": "openai-compatible", "capacity": 1, "secretAlias": "llm-key",
    "settings": {
      "baseUrl": "http://lemonade.example:8000/api/v1",
      "endpoints": ["chat.completions", "models.list"],
      "allowedModels": ["<模型 id>"],
      "defaults": {"temperature": 0.6, "top_p": 0.9, "top_k": 64, "min_p": 0.05, "max_tokens": 8192}
    }}'
```

- **必須啟用的端點：`chat.completions` 與 `models.list`**，而且只需要這兩個。缺其中任何一個，run 以 `ModelConfigurationException` 失敗，log 有 `ENDPOINT_NOT_ENABLED`，且不標記任何文章（第 10.2 節）。
- **`defaults`**：取樣參數**不由 pipeline 送，由這裡提供**。要與來源行為等價，用來源 `models.json` 的值：`temperature` 0.6、`top_p` 0.9、`top_k` 64、`min_p` 0.05、`max_tokens` 8192（thinking 開時推理與答案共用這個預算；被截斷的回答不算判定）。模型另有 `repeat_penalty` 時一併設。`defaults.model` 不會被 pipeline 讀到：模型 id 由 trigger 的參數 `readerModel` 給。
- **`allowedModels`**（建議）：限定只能用這個模型，`readerModel` 不在清單內時 run 以 `CONFIG ERROR: the llm resource cannot be used: MODEL_NOT_ALLOWED` 失敗。
- **容量 1**：目標服務同一時間只做一個生成；它同時是 digest-rss 的 run 一次只有一個的原因（第 6 節）。
- 逾時：`firstByteMs`（預設 15 分鐘）、`idleMs`（預設 5 分鐘）、`totalMs`（預設不設）足夠 thinking 開的長時間生成，不必改。
- **金鑰**：服務要金鑰時放在金鑰庫，資源以 `secretAlias` 引用，不進資料庫、API 或 log：
  ```
  printf '%s\n' "$LLM_KEY" | keytool -importpass -alias llm-key -keystore "$KEYSTORE" -storetype PKCS12 -storepass:file "$STOREPASS"
  curl -s -X POST -H "Authorization: Bearer $ADMIN_TOKEN" $ENGINE/api/v1/secrets/reload
  ```
  金鑰庫的建立、更新與原子替換見 [deploy/README.md](../../../deploy/README.md)。服務不要金鑰就省略 `secretAlias`。`baseUrl` 是 https 且憑證是私有 CA 時，沒有 `trustAliases` 就用 JVM 的信任（第 2.1 節的根憑證）。
- 建好後**檢查**：`curl -s -X POST -H "Authorization: Bearer $ADMIN_TOKEN" $ENGINE/api/v1/resources/lemonade/check`，預期 `"ok":true`。

### 4.2 `database`：SQLite 資源

```
curl -s -X POST -H "Authorization: Bearer $ADMIN_TOKEN" -H 'Content-Type: application/json' \
  $ENGINE/api/v1/resources -d '{"name": "digest-sqlite", "type": "jdbc-pool", "capacity": 1,
    "settings": {"kind": "sqlite", "path": "digest.db"}}'
```

- 檔案是 `$RUNLINE_RESOURCE_ROOT/digest.db`，第一次需要時建立（檔案所在的目錄須存在且可寫入，因為 SQLite 在旁邊另建 `-wal`、`-shm`）。日誌模式（WAL）與外鍵由 Engine 固定，不能設定。
- pipeline 在每個 run 開始時建立或升級結構（前向、冪等、記錄版本）；不需要預先建表。
- 單寫入者：其他寫入者（含你用工具開的連線）等待鎖，等到語句上限（預設 300 秒）。
- 外部工具可在 Engine 運作時讀取同一個檔案（第 8 節）。**備份見第 9.1 節：連同 `-wal` 檔，或用 SQLite 的備份指令**。

### 4.3 `credentials`：憑證檔

一個 JSON 檔，放在資源根目錄之下（下例 `$RUNLINE_RESOURCE_ROOT/credentials.json`），由管理員放置；pipeline 只讀取，不寫入。擁有者設為 Engine 的帳號、權限 `0600`。

```json
{
  "freshrss":  { "url": "https://freshrss-mcp.example/mcp", "token": "..." },
  "rqbit":     { "url": "https://rqbit.example/mcp",        "token": "..." },
  "streamexa": { "url": "https://streamexa.example/mcp",    "token": "..." }
}
```

- 三個鍵固定：`freshrss`、`rqbit`、`streamexa`。每個條目是物件，`url`（`http` 或 `https` 的 MCP 端點）與 `token`（非空字串，作為 Bearer）**兩個欄位都必填**。**位址也在這個檔裡**，不在程式或 trigger 參數。
- 只讀這次需要的條目：`collect` 與 `mark` 只讀 `freshrss`；`download` 只讀這次真的要派發的服務。
  - **缺 `freshrss` 條目：每個 run 一開始就失敗**（`CONFIG ERROR: the credentials file has no entry "freshrss"`）。
  - **缺 `rqbit` 或 `streamexa` 條目：第一次出現保留文章（需要派發給該服務）的 run 會失敗**，失敗發生在 `download` 階段、任何呼叫之前：該 run 沒有 digest、不標記，文章在 FreshRSS 仍是未讀（`CONFIG ERROR: the credentials file has no entry "rqbit"`，streamexa 同）。沒有保留文章的 run（例如全部被過濾），缺這兩個條目不影響，run 成功結束。所以**三個條目請在第一次 run 之前就備齊**，不要等到第一次保留文章時才發現。
  - 檔案不存在、讀不到、不是 JSON、不是物件、`url` 或 `token` 缺漏或格式錯誤，一律是組態錯誤，run 失敗。訊息只說缺什麼，不含檔案內容、路徑與 token。
- 憑證檔是偏離「機密只在金鑰庫」的決定（ADR-023）：因為 pipeline 是 unsafe，這個做法不保護對抗惡意 pipeline，只確保 token 不進資料庫、API、log。改檔後下一個 run 就用新值（不需重載）。
- 建立資源：
  ```
  curl -s -X POST -H "Authorization: Bearer $ADMIN_TOKEN" -H 'Content-Type: application/json' \
    $ENGINE/api/v1/resources -d '{"name": "service-credentials", "type": "file", "capacity": 1,
      "settings": {"path": "credentials.json"}}'
  ```
  並對三個資源各做一次 `POST /api/v1/resources/{name}/check`。

### 4.4 綁定角色

可以在 Console 的 pipeline 詳情頁用型別過濾的選單選擇（WI-1013），或用 API：

```
for pair in llm=lemonade database=digest-sqlite credentials=service-credentials; do
  curl -s -X PUT -H "Authorization: Bearer $ADMIN_TOKEN" -H 'Content-Type: application/json' \
    -d "{\"resource\": \"${pair#*=}\"}" $ENGINE/api/v1/definitions/$HASH/digest-rss/resource-bindings/${pair%%=*}
done
curl -s -H "Authorization: Bearer $ADMIN_TOKEN" $ENGINE/api/v1/definitions/$HASH/digest-rss/resource-bindings
```

檢視中每個角色的 `problem` 應為 `null`、`isDefault` 為 `false`（明確綁定）。綁定以「上傳者加 pipeline 名稱」為單位，同一個上傳者的各版本共用——**升級新版本時綁定自動沿用**（第 9.4 節）。

## 5. trigger 與參數

### 5.1 參數表

trigger 的 `parameters` 與手動 run 的 `parameters` 相同；值都是字串。

| 參數 | 預設 | 規則 | 備註 |
|---|---|---|---|
| `condition` | **必填，無預設** | 條件 id：英數字開頭，英數字與 `.` `_` `-`，最長 64。目前可用 `creampie-busty`；`none` 是明確的值（不做主題過濾、全部保留） | 缺漏、空、格式不合是呼叫錯誤（Engine 以 422 `invalid_parameters` 拒絕建立）；id 不在 jar 內的清單是呼叫錯誤，訊息列出可用的 id |
| `readerModel` | 無 | 請求讀者與摘要所用的模型 id（用來與回應的模型比對）；`condition` 不是 `none` 時**必填**；`condition=none` 時選填，有給只用於摘要。有給時（含 `none`）一行、1 至 100 字元、不含控制字元；空字串視為沒給 | 缺漏或不合法是呼叫錯誤；回應的模型與它不符，run 立即失敗（不信任被替換的模型）；回報為 `.gguf` 檔案路徑時以檔名比對，見第 10 節排錯表。若 `llm` 資源有 `allowedModels`，要含這個 id。`condition=none` 沒給時不呼叫模型，digest 只有事實 |
| `markRead` | `off` | **只有字面 `on` 才開**；其他任何值（`ON`、`true`、`1`、空）都視為關 | 標已讀不可逆。第一次一律 `off` |
| `cap` | `100` | 正整數 | FreshRSS 單次最多列 100，超過時實際取 100（log 有 `CAP requested=… effective=100`）。隔離中的文章另外多要，不佔 `cap` |
| `retentionDays` | `30` | 正整數 | 頁面文字、封面影像、模型對話保留天數（第 9.2 節） |
| `quarantineAfter` | `3` | 正整數 | 連續幾次 run 未得出判定就隔離（擷取失敗、文字不足、模型沒給判定都算） |
| `quarantineDays` | `7` | 正整數 | 隔離幾天；隔離中的文章不處理、不標記，在 FreshRSS 仍未讀 |
| `captureMinChars` | `40` | 正整數 | 擷取的文字少於這個字數視為過薄，不進判定 |
| `captureRequireText` | 空 | 一行、最長 100 字元 | 非空時，文字中沒有這個字串的擷取不進判定 |

參數不合法時 run 以「呼叫錯誤」失敗並一次列出所有問題（不回顯值），不開始任何階段、不寫任何紀錄。

### 5.2 建立 cron trigger

```
curl -s -X POST -H "Authorization: Bearer $ADMIN_TOKEN" -H 'Content-Type: application/json' \
  $ENGINE/api/v1/triggers -d '{
    "name": "digest-rss-daily", "kind": "cron", "contentHash": "'$HASH'", "pipeline": "digest-rss",
    "cron": "0 6 * * *", "timeZone": "Asia/Taipei", "enabled": true,
    "parameters": {"condition": "creampie-busty", "readerModel": "<模型 id>", "markRead": "off", "cap": "3"}}'
```

- `cron` 是標準五欄表達式，最小粒度一分鐘；`timeZone` 是 IANA 時區，預設 UTC。端到端測試用 `* * * * *` 觸發了兩次 run。
- **cron 的間隔要比一個 run 的時間長。** 上一個 run 還沒結束時，下一個到點的 run 會排在後面等模型資源（狀態 `WAITING_FOR_RESOURCES`，第 6 節），等太久（`RUNLINE_RESOURCE_WAIT_TIMEOUT_SECONDS`）會失敗。
- 觸發紀錄：`GET /api/v1/triggers/{name}/firings`。暫停：`PATCH /api/v1/triggers/{name}` 帶 `{"enabled": false}`。
- trigger 綁在建立時指定的版本上，**不會**隨新版本移動（第 9.4 節）。

## 6. 營運前提：run 序列化，同一個資料庫只能有一個 digest-rss run

digest-rss 假設同一時間只有一個 run 對同一個 SQLite 資料庫執行（run 開始時會把「上次崩潰而沒結束」的紀錄標為中斷，兩個 run 同時存在時這個判斷會誤標另一個進行中的 run）。這個序列化由 **`llm` 資源的容量 1** 達成：Engine 在 run 開始時取得它宣告的全部資源，容量 1 的資源同一時間只有一個持有者。端到端測試驗證：第一個 run 進行時，第二個 run（甚至是不呼叫模型的 `condition=none` 且沒給 `readerModel`）的狀態是 `QUEUED` 然後 `WAITING_FOR_RESOURCES`，等第一個結束才開始，資料庫中第二個 run 的 `started_at` 不早於第一個的 `finished_at`。

- **不要把兩個 pipeline 版本（或兩份 pipeline）的 `llm` 角色綁到不同資源，而同時對同一個資料庫執行**：它們不共用容量，會同時寫同一個資料庫。同一位上傳者的各版本共用綁定，所以正常升級不會發生；多位上傳者上傳同名 pipeline 時綁定各自獨立，要自己留意。
- **不要把 `llm` 資源的容量調高。**
- **不要讓兩個 Engine 指向同一個資料庫檔**（SQLite 不支援網路檔案系統上的鎖）。
- 手動執行（`POST /api/v1/runs`）與 cron 一樣受這個序列化約束。

## 7. 第一次執行與開啟標記

依 [WI-1017](work-items/WI-1017-first-real-run.md) 的順序（前一步不通過不做下一步）：只讀的契約檢查（`realFreshRssTest`、`realDownloadsTest`、`realReaderTest`、`realSitesTest`）→ 摘要 thinking 關的預填 → 32 個標註案例 → 目標主機驗證（[deploy/browser.md](../../../deploy/browser.md) 的「在目標主機上要做的驗證」）→ **`markRead=off`、`cap=3`、明確的 `condition` 與 `readerModel` 的一次完整 run** → 用第 8 節的查詢核對 → 使用者決定開 `markRead=on`，第一次仍用小的 `cap`，事後核對 FreshRSS 與資料庫的標記一致。

手動做那次完整 run：

```
curl -s -X POST -H "Authorization: Bearer $ADMIN_TOKEN" -H 'Content-Type: application/json' $ENGINE/api/v1/runs \
  -d '{"contentHash": "'$HASH'", "pipeline": "digest-rss",
       "parameters": {"condition": "creampie-busty", "readerModel": "<模型 id>", "markRead": "off", "cap": "3"}}'
curl -s -H "Authorization: Bearer $ADMIN_TOKEN" $ENGINE/api/v1/runs/$RUN_ID            # state
curl -s -H "Authorization: Bearer $ADMIN_TOKEN" "$ENGINE/api/v1/runs/$RUN_ID/log?limit=2000"
```

run 的 `state` 是 `SUCCEEDED` 不等於「做了事」：看 log 的 `COUNTS` 與 `OUTCOME` 兩行（第 10 節）。`markRead=off` 時 log 有 `GATE CLOSED marking: marking is off: markRead is not on (it must be exactly "on")`，資料庫 `run.mark_closed_reason` 記同一句，FreshRSS 沒有收到任何標記呼叫。

**`condition=none` 的摘要。** `condition=none` 本身不呼叫讀者、不做模型可用性檢查，`readerModel` 選填：

- 有給 `readerModel`：有保留文章時，摘要照常以該模型產生（與條件判定時的摘要完全相同：thinking 關、串流、模型身分比對，回應的模型與它不符時 run 失敗）；沒有保留文章時不呼叫模型（`summary_status` 為 `none_kept`）。`digest.summary_status` 為 `model`，`run.requested_model` 記下該模型。
- 沒給（或給空字串）：不呼叫模型，digest 只有事實部分，`digest.summary_status` 為 `not_made`（未產生，不是失敗），`summary_note` 為 `no readerModel was given, so no model was asked`，run 成功。
- `readerModel` 有給時一律驗證格式（一行、1 至 100 字元、不含控制字元），即使 `condition=none`；不合法是呼叫錯誤。
- `llm` 角色在 Engine 上仍須綁定（第 4 節、第 4.4 節）；這不因是否給 `readerModel` 而改變。

## 8. 查看結果：彙整查詢

資料庫檔是 `$RUNLINE_RESOURCE_ROOT/digest.db`。Engine 運作時可以讀取（日誌模式，讀取不被寫入擋住）。用 SQLite 命令列工具時：

- **用 `sqlite3 -cmd "PRAGMA query_only = ON" -header digest.db < 查詢.sql`，不要用 `sqlite3 -readonly`。** 端到端測試發現：沒有 run 在進行（沒有任何連線開著）時，`-readonly` 因為不能建立 WAL 模式需要的 `-wal`、`-shm` 檔而失敗（`unable to open database file`）。
- **以 Engine 的帳號執行**。沒有 run 進行時，`sqlite3` 開啟資料庫會在旁邊建立 `-wal`、`-shm` 檔；以別的帳號（例如 root）建立的檔案，Engine 的帳號可能無法使用（這一點是依 SQLite 的行為推論，沒有在端到端驗證）。
- 長時間的分析請對備份副本（第 9.1 節）進行，不要在正式檔上開長交易。
- 查詢數量大時加上 `WHERE` 或 `LIMIT`（例如 `WHERE r.id >= 100`）。

以下查詢由 `RunbookQueriesTest` 在 pipeline 自己建立的結構上執行、由端到端測試在真實資料上執行（含用 `sqlite3` 命令列工具），所以複製即可用。

### 8.1 WI-1011 的五個問題

**每次 run 抓到哪些未讀**（`outcome` 是這次的處置：`kept`、`filtered`、`refused`，或未處理的原因 `capture_failed`、`text_insufficient`、`whole_page_screenshot`、`quarantine`、`not_read`）：

```sql
-- query: fetched
SELECT r.id AS run, r.started_at, rp.position, p.freshrss_id, p.title,
       COALESCE(rp.disposition, rp.unprocessed_reason, 'unprocessed') AS outcome
FROM run r
JOIN run_post rp ON rp.run_id = r.id
JOIN post p ON p.id = rp.post_id
ORDER BY r.id, rp.position;
```

**每篇的圖文判定與引文**（`evidence` 是判定依據：`cover_visual` 封面影像、`cover_text` 封面上的字、`page_text` 頁面文字、`none`；`quote_verified` 為 1 表示引文確實在擷取的頁面文字裡，0 表示被拒絕〔`refused_reason` 說明〕，空表示沒有引文要驗證）：

```sql
-- query: verdicts
SELECT v.run_id, p.freshrss_id, p.title, v.condition_id, v.answer, v.evidence, v.rule,
       v.raw_grade, v.quote, v.quote_verified, v.refused_reason
FROM verdict v
JOIN post p ON p.id = v.post_id
ORDER BY v.run_id, p.freshrss_id, v.condition_id;
```

**哪些文章被派給 rqbit 或 streamexa，結果如何**（`status` 為 `ok`、`failed`，或停在 `pending` 表示問了但不知道結果；`attempts` 是這篇文章跨 run 的第幾次派發；`link` 欄不選，因為連結可能帶金鑰）：

```sql
-- query: downloads
SELECT d.run_id, p.freshrss_id, d.service, d.kind, d.link_origin, d.status, d.attempts, d.error
FROM download d
JOIN post p ON p.id = d.post_id
ORDER BY d.id;
```

**各站點 profile 的失敗率**（失敗＝這次擷取不是 `readable`：`capture_failed`、`text_insufficient`、`whole_page_screenshot`；沒有選到 profile 的失敗〔例如伺服器回 5xx 的頁面〕歸在 `(none)`）：

```sql
-- query: profile-failures
SELECT COALESCE(c.profile, '(none)') AS profile,
       COUNT(*) AS captured,
       SUM(c.outcome <> 'readable') AS failed,
       ROUND(100.0 * SUM(c.outcome <> 'readable') / COUNT(*), 1) AS failure_percent
FROM capture c
GROUP BY COALESCE(c.profile, '(none)')
ORDER BY failure_percent DESC, profile;
```

失敗原因的分布：

```sql
-- query: capture-failures
SELECT COALESCE(c.profile, '(none)') AS profile, c.outcome, COALESCE(c.error_kind, '') AS error_kind, COUNT(*) AS n
FROM capture c
WHERE c.outcome <> 'readable'
GROUP BY COALESCE(c.profile, '(none)'), c.outcome, COALESCE(c.error_kind, '')
ORDER BY n DESC;
```

**各規則版本的保留比例**（`rules_version` 是條件定義與全部 rubric 文字的雜湊 `條件 id@12 碼`，條件 `none` 為 `none`；`filtered` 含引文被拒絕的文章；只算有判定的文章。同一篇文章在不同 run 重讀會各算一次）：

```sql
-- query: rules-kept
SELECT r.rules_version,
       COUNT(*) AS articles_judged,
       SUM(rp.disposition = 'kept') AS kept,
       SUM(rp.disposition IN ('filtered', 'refused')) AS filtered,
       ROUND(100.0 * SUM(rp.disposition = 'kept') / COUNT(*), 1) AS kept_percent
FROM run r
JOIN run_post rp ON rp.run_id = r.id
WHERE rp.disposition IS NOT NULL
GROUP BY r.rules_version
ORDER BY MAX(r.id) DESC;
```

### 8.2 日常使用

**最近的 run 與結果**（`result` 是 `success`、`failed`、`stopped`〔被取消或逾時〕、`interrupted`〔上次崩潰沒結束〕；`result_note` 說明成功但沒做事的原因，例如 `no listing: …`、`model unavailable: …`；`mark_closed_reason` 說明為何沒標記）：

```sql
-- query: runs
SELECT id, started_at, finished_at, result, result_note, param_condition, param_mark_read,
       requested_model, responded_model, collected, kept, filtered, untouched, refused, mark_closed_reason
FROM run
ORDER BY id DESC
LIMIT 20;
```

**取出最近一份 digest**（`summary_status`：`model` 模型寫了主題、`none_kept` 沒有保留的文章、`failed` 模型沒能給出可用的答案、`not_made` 沒給 `readerModel` 所以沒問模型；要某個 run 的改成 `WHERE run_id = 5`；run 結束的 log 有 `DIGEST location=table digest run_id=5 query="…"`）：

```sql
-- query: digest-latest
SELECT run_id, generated_at, summary_status, check_passed, digest
FROM digest
ORDER BY run_id DESC
LIMIT 1;
```

`check_passed` 為 0 時，`check_problems` 說明 digest 與紀錄哪裡不符。檢查只抓得到「自相矛盾與被改寫的文件」，**不判斷判定本身是否正確**。

**標記的結果**（`failed` 的文章仍是未讀，下次 run 會再列出）：

```sql
-- query: marks
SELECT m.run_id, p.freshrss_id, p.title, m.status, m.error
FROM mark m
JOIN post p ON p.id = m.post_id
ORDER BY m.run_id DESC, p.freshrss_id
LIMIT 100;
```

**被隔離的文章**：

```sql
-- query: quarantine
SELECT p.freshrss_id, p.title, q.consecutive_unjudged, q.last_unjudged_at, q.quarantined_until
FROM quarantine q
JOIN post p ON p.id = q.post_id
ORDER BY q.quarantined_until DESC;
```

**各階段耗時**：

```sql
-- query: stages
SELECT run_id, position, name, status, duration_ms, result
FROM stage
ORDER BY run_id DESC, position
LIMIT 70;
```

取出封面影像（保留期內）：`sqlite3 digest.db "select writefile('cover.png', cover_image) from capture where run_id = 5 and post_id = 12"`；以 `cover_sha256` 核對。

### 8.3 Console 的 view

Console 的 pipeline 詳情頁的「資料」區顯示 digest-rss 宣告的四個 view，不需要 `sqlite3` 或 Engine 帳號。view 是唯讀的表格，所有 view 都不顯示下載連結（連結可能帶金鑰）。宣告與查詢文字見 [`runline-digest-rss` 的 README](../../../../runline-digest-rss/README.md) 的「Console 的 view（WI-1303）」，機制見 [pipeline view](../pipeline-views/README.md)。

| view（id） | 回答的問題 |
|---|---|
| 下載清單（`downloads`） | 哪些文章被派發下載、派發到哪個服務、結果是 `succeeded`、`failed` 或 `unknown`，嘗試了幾次 |
| 未下載與原因（`not-downloaded`） | 哪些文章沒有任何一次派發成功，以及為什麼；可用參數 `reason` 依原因類別篩選 |
| run 清單（`runs`） | 每次 run 的起訖、結果與抓到、保留、過濾、下載成功、下載失敗、結果未知的篇數；點 run 編號鑽取到下一個 view |
| 某次 run 的文章（`run-articles`） | 參數 `run`；那次 run 列出的每篇文章的處置（或未處理原因）與下載結果 |

這四個 view 沒有涵蓋的問題（引文與判定依據、各站點失敗率、各規則版本的保留比例、digest、標記、隔離清單、階段耗時）仍用第 8.1、8.2 節的查詢。

**未下載的原因類別。** `not-downloaded` 的「原因類別」欄是下表的值之一；名稱以 digest-rss 儲存庫的宣告（`DigestViews`）為準，說明欄另有每一列的具體原因。

| 類別 | 意思 |
|---|---|
| `filtered` | 不符條件；說明列出答 `no` 的條件 |
| `refused` | 引文驗證未通過；說明含拒絕原因 |
| `capture_failed`、`text_insufficient`、`whole_page_screenshot` | 擷取失敗、文字不足、只有整頁截圖；說明是擷取時記下的原因 |
| `quarantine` | 隔離中（連續未得出判定，刻意暫停），隔離期滿後恢復 |
| `not_read` | 頁面可讀，但讀者沒有處理這篇 |
| `no_link` | 保留的文章沒有 torrent／magnet（feed 與頁面都沒有），改用文章網址派發給 streamexa 仍失敗 |
| `download_failed` | 保留，torrent／magnet（或 feed／頁面的連結）已派發，服務回失敗 |
| `download_unknown` | 已派發，但結果未知（只留下 `pending`） |
| `not_dispatched` | 保留，但最近一次 run 沒有這篇的派發紀錄（例如 run 在下載階段失敗、被停止）；說明帶該 run 的結果 |
| `unprocessed` | 最近一次 run 沒有記下處置也沒有原因（例如 run 還在進行） |

**前提：角色 `database` 已綁定（第 4.4 節）。** view 讀的是 `database` 角色綁定的資源。角色沒有綁定、也沒有同名資源時，「資料」區的 view 狀態顯示「未綁定」，點進去說明該角色沒有共享資源，並提供選擇資源的入口；處置是依第 4.4 節綁定，不需要重新上傳。資源已停用、已被刪除、型別不符時同樣顯示在狀態欄，處置同第 10.1 節的對應列。這個綁定是建立 run 所需要的，所以依第 4 節完成上線的 pipeline 不會遇到未綁定。

**時間欄。** 所有 view 的時間欄都以帶 UTC 偏移的格式輸出，Console 才會依使用者的語言與時區顯示；複製到別處的原始值是 UTC。

**並行讀取上限。** 同一個資源同時進行的 view 讀取有上限，超過的讀取得到「資料庫忙碌中」，稍候再試即可。管理員要調整上限時，環境變數的名稱與預設值見 [pipeline-engine 的部署文件](../pipeline-engine/04-deployment.md)；原因見 [ADR-035](../pipeline-views/adr/ADR-035-view-read-limits.md)。

## 9. 維運

### 9.1 備份

資料庫檔連同 `-wal` 檔納入備份，**或**用 SQLite 的備份指令（建議；端到端測試在 Engine 運作中取了一份，`PRAGMA integrity_check` 為 `ok`、列數與正式檔相同）：

```
sqlite3 "$RUNLINE_RESOURCE_ROOT/digest.db" ".backup '/backup/digest-$(date +%F).db'"
```

只複製主檔而漏了 `-wal` 可能缺少最近的提交；要用檔案複製就在 Engine 停止時複製（含 `-wal` 與 `-shm`）。憑證檔（`credentials.json`）和金鑰庫另外備份，內容含 token 與金鑰，權限同原檔。還原時 Engine 停止，換回檔案後啟動；資料庫的結構版本比 pipeline 新時 pipeline 拒絕寫入（第 10 節），所以**不要用舊版 pipeline 面對較新版寫過的資料庫**。還原結構版本低於 8 的備份時，索引在下一個 run 開始時補上（第 9.4 節）。

### 9.2 保留清除

- **pipeline 自己的清除**：每個 run 開始時，把「開始時間早於 `retentionDays` 天」的 run 的體積大的欄位設為 NULL：`capture.text`（頁面文字）、`capture.cover_image`（封面影像）、`reader_exchange.request_text` 與 `response_text`（模型對話）。字數、位元組數、雜湊（`text_sha256`、`cover_sha256`、`request_sha256`…）都保留。**判定、下載、標記、digest 長期保留，不清除**（它們是彙整資料）。清除失敗只在 log 留 `STORE WARN`，不使 run 失敗。端到端測試把兩個 run 的時間改成 40 天前，下一個 run 開始後它們的文字、影像與對話為 NULL，其餘不變，新的 run 不受影響。
- **檔案不會自己變小**：清除只是置 NULL。要縮小檔案，在沒有 run 進行時：`sqlite3 digest.db "PRAGMA wal_checkpoint(TRUNCATE); VACUUM; PRAGMA wal_checkpoint(TRUNCATE);"`（端到端測試做過，`integrity_check` 為 `ok`）。`digest` 表與判定會持續增長（每個 run 數 KB 到數十 KB），要裁掉舊資料是你自己的資料庫維運。
- **Engine 的清除**（與上面無關）：run 紀錄與 log 保留 `RUNLINE_RUN_RETENTION_SECONDS`（預設 30 天）；失敗、被取消或被中斷的 run 的私有目錄保留 `RUNLINE_FAILED_RUN_RETENTION_SECONDS`；Chrome 的使用者資料目錄在 run 結束時就被刪除。

### 9.3 遺留的 Chrome 行程

正常的 run 結束、失敗、被取消時 Chrome 的全部行程與使用者資料目錄都被結束（端到端測試的取消案例：取消後沒有 Chrome 行程、`runline-chrome-*` 目錄也不在了）。只有 Engine 本身被強制結束（SIGKILL、OOM）時才會遺留：

- **Docker**：容器的 PID 1 是 init，主行程結束時容器停止，其中所有行程隨容器結束；`restart: unless-stopped` 起新容器，沒有遺留。**systemd**：單元停止時同一個 control group 的 Chrome 一併結束（`KillMode=control-group`）。這兩項在 [deploy/browser.md](../../../deploy/browser.md) 的「遺留的 Chrome 行程與暫存檔」說明，端到端測試沒有在 Linux 上驗證。
- **Engine 還活著、卻有沒清乾淨的 Chrome**，或在沒有隨容器結束的環境（開發機）：**不需要手動處理**——pipeline 在**每個 run 開始時**先清理屬於它且失去擁有者的 Chrome。Chrome 的使用者資料目錄名稱帶擁有者（`runline-chrome-<pid>-<開始時間>-<隨機數>`），擁有者行程已不存在才結束它們並刪目錄；進行中的 run 的、別的程式的 Chrome 都不動。端到端測試（macOS）：以 SIGKILL 結束 Engine 時一個 run 正在擷取，遺留了 12 個 Chrome 行程與 1 個使用者資料目錄；Engine 重啟後該 run 的狀態是 `INTERRUPTED`，下一個 run 的 log 有 `BROWSER cleanup: ended 12 leftover process(es), removed 1 leftover directory(ies)`，之後沒有遺留；被中斷的 run 在資料庫的 `result` 於下一個 run 開始時標為 `interrupted`。
- 手動檢查：`pgrep -af 'user-data-dir=.*runline-chrome-'`（Docker：`docker compose exec engine pgrep -af 'google-chrome|chrome_crashpad'`）。Engine 沒有進行中的瀏覽器 run 卻還有 Chrome 時是遺留，等下一個 run 的清理，或 `pkill -f 'user-data-dir=.*runline-chrome-'`（先確認沒有 run 在用；殭屍行程在 init 回收之前存在，不是遺留）。
- systemd 主機的暫存目錄 `/var/cache/runline` 會留下 `runline-chrome-*` 與 `sqlite-*`：Engine 停止時 `find /var/cache/runline -maxdepth 1 \( -name 'runline-chrome-*' -o -name 'sqlite-*' \) -mtime +1 -exec rm -rf {} +`。

### 9.4 升級到新版本的 pipeline

1. 上傳新 jar（第 3.1 節），取得新的 `contentHash`。
2. 檢查原因清單（第 3.2 節）：與上一版比較，多出的類別要有理由。
3. 允許新版本以 unsafe 執行（第 3.4 節）：**新版本不繼承允許**；沒有允許前建立 run 回 `409 unsafe_not_allowed`（端到端測試驗證）。
4. 綁定**自動沿用**（綁定以上傳者加 pipeline 名稱為單位）；用第 4.4 節的檢視確認每個角色 `problem` 為 `null`（端到端測試驗證新版本的三個角色已綁定到原來的資源）。
5. 用手動 run 試跑（`markRead=off`），再把 trigger 移到新版本：`PATCH /api/v1/triggers/{name}` 帶 `{"contentHash": "<新>"}`。**trigger 不會自己移動**，也不會因此被啟用或停用（端到端測試驗證）。
6. 舊版本：沒有 trigger 與 run 引用它時才可 `DELETE /api/v1/artifacts/{hash}`（否則 409 `in_use`）。
7. 資料庫結構由新版 pipeline 在第一個 run 開始時前向升級；升級後舊版 pipeline 不能再寫這個資料庫。結構版本 8 新增一個索引（`run_post_by_post`），也在這時套用：在第一個 run 開始之前，Console 的 view（第 8.3 節）仍可讀取，只是 `not-downloaded` 較慢，資料量大時可能逾時；第一個 run 之後恢復。

### 9.5 停止與重啟

SIGTERM 讓 Engine 優雅關閉（`docker compose stop`、`systemctl stop`）：不再接受新請求、等進行中的請求、再停止進行中的 run（標為中斷）、釋放連線池，結束代碼 0（端到端測試驗證閒置時的結束代碼 0 與沒有遺留的 Chrome；進行中 run 的關閉沒有在端到端驗證）。進行中的 run 被中斷時 FreshRSS 沒有被標記（標記是 run 最後一個階段的一次呼叫）。重啟後狀態為 `INTERRUPTED` 的 run 不會自動重跑，下一次排程會重新列出同樣的未讀。

### 9.6 SQLite 原生程式庫與暫存目錄

驅動隨 Engine 發佈，原生程式庫第一次使用時解到 `java.io.tmpdir`。該目錄是 `noexec` 時 `sqlite` 資源的檢查失敗，Engine log 有 `UnsatisfiedLinkError … failed to map segment from shared object`；改 `-Dorg.sqlite.tmpdir=<可執行的目錄>`（`JDK_JAVA_OPTIONS`）。細節在 [deploy/browser.md](../../../deploy/browser.md)。

## 10. 排錯

先看 run 的 `state`，再看 log。`SUCCEEDED` 加 `COUNTS collected=0` 或 `untouched` 很高，代表「成功但沒做事」，原因在 `RESULT` 行、`run.result_note` 與 `DIGEST location=none (…)`。以下訊息都是端到端測試實際遇到的，關鍵字與 log 的原文相同。

### 10.1 建立 run 時被拒絕（HTTP 4xx，沒有 run）

| 現象 | 原因與處理 |
|---|---|
| `409 unsafe_not_allowed` | 這個版本還沒被允許（或剛升級到新版本）。第 3.4 節 |
| `409 resources_unavailable`，`problems[].problem` 為 `unbound` | 該角色沒有綁定，也沒有同名資源。**包括 `llm`，即使 `condition=none`**。第 4.4 節 |
| `409 resources_unavailable`，`unknown`／`disabled`／`type_mismatch` | 綁定的資源已被刪除／已停用／型別不是角色宣告的型別。重新綁定或啟用資源 |
| `422 invalid_parameters`，`problems` 有 `{name: "condition", problem: "missing"}` | 沒給 `condition`。`undeclared` 表示參數名稱寫錯（例如 `maxArticles`） |
| `409 ambiguous_version` | 同一內容有多位上傳者的版本。加 `uploader` |
| `413 too_large` | jar 超過 `UPLOAD_MAX_BYTES`（預設 50 MiB）。薄 jar 遠小於此 |

### 10.2 run 失敗（`state` 為 `FAILED`，`failure.type` 看例外類別）

| log 或訊息 | 原因與處理 |
|---|---|
| `CALL ERROR: readerModel is required unless condition is none`（`InvalidCallException`） | `condition` 不是 `none` 卻沒給 `readerModel`。`CALL ERROR: readerModel must be one line of 1 to 100 printable characters`：`readerModel` 有給但格式不合（`condition=none` 也一樣）。其他呼叫錯誤：`condition names no criterion this pipeline carries; available: creampie-busty (or none)`、`cap must be a positive integer`。呼叫錯誤不開始任何階段，也不寫任何紀錄 |
| `CONFIG ERROR: the credentials file has no entry "rqbit"`（`ConfigurationException`；`streamexa`、`freshrss` 同） | 憑證檔缺條目。第 4.3 節：缺 `rqbit`／`streamexa` 只在這次有保留文章要派發給它時失敗；缺 `freshrss` 每個 run 都失敗。該 run 不標記，文章仍未讀 |
| `CREDENTIALS REJECTED: the service does not find its tools: this is how it answers a missing or wrong token`（`CredentialsRejectedException`） | FreshRSS 的 token 缺或錯：這個服務在 token 不對時**不回認證錯誤，而是回「找不到工具」**。HTTP 401／403 也同類。改憑證檔的 `freshrss.token`。這是要人修的問題，所以 run 失敗而不是靜默成功 |
| `MODEL MISMATCH asked fake-model and another-model answered: the service substituted a model for one it does not serve; do not keep the numbers of this run`（`ModelIdentityException`） | 回應的模型與 `readerModel` 不符（模型被替換，或 `readerModel` 寫的不是服務稱呼它的 id；串流事件沒有 `model` 欄位時也無法驗證而中止）。**相符的條件**（判定與摘要共用，[ADR-028](adr/ADR-028-model-identity-file-path.md)）：回報值取最後一段（以 `/` 或 `\` 分隔）、去掉結尾的 `.gguf`、不分大小寫後，與 `readerModel` 相同。因此 Lemonade（llama.cpp）把模型回報為檔案路徑，例如 `readerModel` 為 `Foo-Q4_K_M` 時回報 `/root/.cache/huggingface/hub/.../Foo-Q4_K_M.gguf`，視為同一個模型；只回報 `Foo-Q4_K_M.gguf` 也相符。檔名不同（含量化版本不同，如 `Foo-Q8_0.gguf`）不符，仍使 run 失敗。只信任檔名、不信任目錄。`readerModel` 要填檔名去掉 `.gguf` 的部分（不必也不能填整條路徑，長度上限 100）。run 立即失敗、不標記；`run.requested_model` 與 `responded_model` 記下兩者。校正 `readerModel`，或查服務為何換了模型 |
| `CONFIG ERROR: the llm resource cannot be used: MODEL_NOT_ALLOWED` | `readerModel` 不在 `llm` 資源的 `allowedModels` 內。其他 `llm` 資源的組態問題同形：金鑰被拒（401／403）、參數被鎖定 |
| `CONFIG ERROR: the llm resource cannot be used: ENDPOINT_NOT_ENABLED`（第 4 階段 `judge`） | `llm` 資源沒啟用 `chat.completions` |
| `FAILED 2/7 model-check: ModelConfigurationException`，前一行 `… failed: ENDPOINT_NOT_ENABLED`（沒有 `CONFIG ERROR` 行） | `llm` 資源沒啟用 `models.list`。補上端點：`PATCH /api/v1/resources/lemonade` 帶完整的 `settings`（整份取代，所以要連 `baseUrl`、`defaults` 一併帶） |
| `CONFIG ERROR: the browser is not set up: CHROME_BIN is not an executable file: /…` | `CHROME_BIN` 指向不存在的檔案，或找不到 Chrome。第 2 節 |
| `BROWSER REFUSED: hardware rendering is required (CHROME_REQUIRE_HARDWARE_RENDERING=true) but the browser renders in software: <渲染器>`（`HardwareRenderingRequiredException`） | 要求硬體渲染而 Chrome 是軟體渲染（或讀不到渲染器）。接上 `/dev/dri`，或接受軟體渲染而把設定改回 `false`（WI-1000 的判定規則）。在擷取任何頁面之前失敗，不標記 |
| `SchemaTooNewException`：`the database schema is version 99, newer than the 8 this pipeline knows` | 資料庫被較新版的 pipeline 寫過。改用較新的 pipeline 版本；不要降版。run 不開始任何階段、不寫任何東西 |
| 狀態 `CANCELLED`，log 有 `FAILED 3/7 capture: InterruptedException`，`run.result` 為 `stopped`（`result_note` 為 `asked to stop`） | 有人取消了 run（`POST /api/v1/runs/{id}/cancel`，回 202）。沒有 digest、不標記、Chrome 隨之結束 |
| 狀態 `INTERRUPTED` | Engine 在 run 進行中停止或重啟。下一個 run 開始時 `run.result` 標為 `interrupted`。第 9.3、9.5 節 |
| `STAGE` 行還沒出現、狀態一直是 `WAITING_FOR_RESOURCES` | 上一個 run 還在持有模型資源（容量 1）。等它結束；異常久時看是否有 run 卡住，必要時取消它或 `POST /api/v1/resources/{name}/holders/{runId}/release`。第 6 節 |

### 10.3 run 成功，但沒有做完整件事

| log 或訊息 | 原因與處理 |
|---|---|
| `no listing: the service answered HTTP 500`（`run.result_note`；`COUNTS collected=0`） | FreshRSS 取不到清單（連不上、逾時、HTTP 錯誤、回應格式不對）：本次無事可做，不擷取、不標記，下次排程再來。持續發生就查 FreshRSS 與網路 |
| `no listing: the TLS handshake failed (is the certificate authority of the service trusted by the JVM?)` | JVM 不信任服務憑證的簽發者。把根憑證放進 `RUNLINE_TRUSTED_CA_DIR` 後重啟 Engine（第 2.1 節）。這是 JVM 的設定，不在 pipeline |
| `model unavailable: CONNECTION_FAILED after 3 attempt(s)`（`run.result_note`）與 `DIGEST location=none (the model was not answering, so nothing was read)` | `model-check` 連不上模型服務（每 5 秒重試，共 3 次）。整個 run 成功結束：不擷取、不標記、不計入隔離；文章 `unprocessed_reason` 為 `not_read`，仍是未讀。恢復服務後下次排程處理 |
| `READ 2/3 id=… the model is not answering: …` 與 `READ retry: the model stopped answering; asking again for the 2 article(s) not yet judged` | 模型在 run 中途消失。只對尚未判定者重試一次，再失敗就放棄：已判定的保留並照常標記（`MARK marked 1`），其餘 `not_read` 仍未讀；摘要多半也失敗（`SUMMARY failed: …`），digest 只有事實 |
| `CAPTURE 5/5 id=… capture_failed: HTTP 502: the server answered but failed; not a page to read (status 502 alone: nothing on the page confirms a challenge page)` | 這一篇擷取失敗（5xx 一律是失敗，不當作頁面）。只影響這一篇：不判定、不標記、仍未讀，連續失敗 `quarantineAfter` 次後被隔離。`capture.error_kind` 為 `server-error`；為 `challenge-page` 時表示頁面特徵確認是檢查頁（Cloudflare 之類），`error_basis` 記判定依據 |
| `CAPTURE … text_insufficient: 11 characters of text, fewer than the 40 wanted` | 文字過薄（`captureMinChars`），或缺 `captureRequireText`。同上，只影響這一篇 |
| `DOWNLOAD … rqbit magnet failed attempt=1: the service answered with an error sentence`；`DOWNLOAD … streamexa page failed attempt=1: the service answered HTTP 500` | 下載派發失敗：只記錄（`download.status = failed`，`error`），**不影響 digest 與標記**，run 成功。下次 run 若這篇還出現（沒被標記）會再派發（`attempts` 加一）；已成功的不重派。rqbit 把失敗當一般內容回 `Error …` 句子，這被視為失敗 |
| `MARK FAILED 2 stay unread: the service answered HTTP 500` | FreshRSS 標記失敗：只記錄（`mark.status = failed`），文章仍是未讀，下個 run 重新列出、重新判定、再標記（端到端測試驗證下個 run 標記成功）。不重試同一次 |
| `GATE CLOSED marking: marking is off: markRead is not on (it must be exactly "on")` | `markRead` 不是精確的 `on`（`true`、`ON`、`1` 都不算）。FreshRSS 連 session 都沒開 |
| `STORE WARN storing the digest: SQL_ERROR sqlState=23000; GATE CLOSED marking: no digest was stored for this run (the digest could not be stored)` | 資料庫寫不進 digest：沒有 digest 就不標記（run 仍成功、`DIGEST location=none`）。查資料庫（磁碟、權限、你自己建立的觸發器） |
| `MARK NOT ATTEMPTED 2: the record of it could not be written` | 標記的意圖寫不進資料庫：寧可不標，也不能標了卻沒紀錄。FreshRSS 沒收到呼叫。查資料庫 |
| `STORE WARN <什麼>: <類別>` | 某筆紀錄寫入失敗（階段結果、保留清除…），不使 run 失敗，只有「會影響標記的紀錄」會讓閘門關閉 |
| `QUARANTINE counted 2, forgotten 0, quarantined 0` | 本次有 2 篇「未得出判定」被累計；累計達 `quarantineAfter` 才隔離（`quarantined`）。得出判定就忘記該篇 |
| 同一篇文章每次 run 都出現又都沒標記 | `capture_failed`／`text_insufficient`／`not_read`／隔離。用第 8.1 節的 `fetched` 查詢看它的 `outcome` |

### 10.4 資源與主機

| 現象 | 原因與處理 |
|---|---|
| 資源檢查 `"ok":false`，`failure` 為 `trust_failed`（`hostname_mismatch`、`handshake_failed`…） | 服務憑證不被信任。第 2.1 節的根憑證；`openai-compatible` 的 `trustAliases` 只信任金鑰庫指定的憑證 |
| 資源檢查 `failure` 為 `rejected` | 服務拒絕金鑰（401／403）。更新金鑰庫後 `POST /api/v1/secrets/reload` |
| 資源檢查 `failure` 為 `alias_missing` | `secretAlias` 在金鑰庫找不到，或 Engine 沒設金鑰庫 |
| `sqlite` 資源檢查 `unexpected_response`，Engine log 有 `UnsatisfiedLinkError … failed to map segment` | 暫存目錄是 `noexec`。第 9.6 節 |
| `sqlite3` 開資料庫 `unable to open database file` | 用了 `-readonly`，或帳號不能讀寫資料庫所在的目錄。第 8 節 |
| 查詢或寫入等很久後 `TOTAL_TIMEOUT` | 別的連線（你開的 `sqlite3` 交易）持有寫入鎖。關掉它；pipeline 的寫入會等到語句上限（預設 300 秒） |

## 附錄 A：已知的小落差

- `llm` 資源沒啟用 `models.list` 時，`model-check` 階段只以例外類別結束（`FAILED 2/7 model-check: ModelConfigurationException`），不像其他階段的組態錯誤有一行 `CONFIG ERROR: …`；原因要看前一行 Engine 的資源觀測訊息（`ENDPOINT_NOT_ENABLED`）。這是 log 的一致性問題，不影響行為（run 失敗、不擷取、不標記）。
- 用 `sqlite3 -readonly` 在沒有 run 進行時開啟資料庫會失敗（第 8 節）；這是 SQLite 對 WAL 模式的限制，不是 pipeline 的問題。

## 附錄 B：驗證範圍

**端到端測試（`./gradlew endToEndTest`，打包的真實 Engine）已驗證：** 上傳、analyzer 的原因、unsafe 的允許、白名單預覽、三種資源的建立與檢查、綁定、`llm` 未綁定時的拒絕、cron trigger 觸發的兩次完整 run（digest、標記集合、下載、計數）、32 個標註案例經 Engine 重放、`condition=none`（含沒給與有給 `readerModel` 的各一次 run）、本手冊的全部查詢（含 `sqlite3` 命令列）、Console 的四個 view（Engine 在上傳時接受宣告，`database` 已綁定時四個都是可讀取狀態，並透過 Engine 的 API 實際讀取，筆數與資料庫相符，第 8.3 節）、保留清除、備份與 VACUUM、模型不可用／中途消失／身分不符（含以檔案路徑回報的模型：相符者繼續、檔名不同者失敗）／不在允許清單、FreshRSS 取不到（含 TLS 不信任與 token 被拒）、單篇擷取失敗（含檢查頁）、下載失敗、標記失敗與下一個 run 的補標、標記開關關閉、資料庫寫入失敗（digest 與標記意圖）、憑證檔缺條目、run 被取消、呼叫錯誤、資料庫比 pipeline 新、缺端點、Chrome 主機設定、run 序列化、新版本的升級、Engine 被強制結束後的遺留 Chrome 與下一個 run 的清理、SIGTERM、pipeline 沒有寫任何檔案、機密不在任何 log／回應／資料庫。

**未驗證：** 真實的 FreshRSS、Lemonade（含 thinking 關的預填、串流事件是否帶 `model`、`models.list`）、rqbit、streamexa、目標站點的 profile、目標主機（Linux、Intel iGPU、虛擬顯示的硬體 GL）、Docker 與 systemd 上的 digest-rss（deploy/browser.md 記錄各自的實測範圍）、Console 的綁定畫面（WI-1013 的測試涵蓋，本項用 API）、Console 的「資料」區畫面（WI-1302 的測試涵蓋，本項用 API 讀 view）、`RUNLINE_RUN_TIMEOUT_SECONDS` 與 `RUNLINE_RESOURCE_WAIT_TIMEOUT_SECONDS` 的逾時行為、進行中 run 時的優雅關閉。這些由 WI-1017 與使用者的目標環境驗證。
