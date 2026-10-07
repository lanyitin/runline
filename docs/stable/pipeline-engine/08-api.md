# Engine API

本文回答：Engine 對外提供哪些 HTTP 端點、誰能呼叫、傳入與回傳什麼、錯誤代碼代表什麼。狀態：已核可（2026-10-04，WI-18）。認證與角色的決策見 [ADR-012](adr/ADR-012-api-authentication.md)，各功能的規則見對應的工作項。

本文與實作的一致性由測試保證（`engine/src/test/kotlin/docs/ApiDocumentationTest.kt`）：每個實際註冊的路由都必須在本文有一個 `### \`方法 路徑\`` 標題，反之亦然；標題下的「認證」行必須與路由實際要求的認證與角色相符；程式碼中的錯誤代碼都必須出現在本文。新增或修改路由時，測試會在本文漏改時失敗。測試不驗證欄位層級的說明（回傳的每個欄位、各狀態碼的完整對應），那部分需要作者在變更時一併更新。

## 通則

**認證。** 標示「Bearer」的端點需要請求標頭 `Authorization: Bearer <token>`。Token 與其名稱、角色由部署的環境變數 `API_TOKENS` 提供（`名稱:角色:token`，逗號分隔）。角色有兩種：`developer`（開發人員）與 `admin`（管理員）；管理員包含開發人員的所有權限。

| 情況 | 回應 |
|---|---|
| 沒有 token、token 無效、或不是 Bearer 格式 | 401，帶 `WWW-Authenticate: Bearer realm="runline"`，沒有 JSON 本文 |
| token 有效但角色不足 | 403 `forbidden` |

**可見範圍。** 開發人員只看得到、也只能對自己上傳的版本建立 run；管理員看得到全部。開發人員查詢不屬於自己的版本或 run，得到的回應與「不存在」相同（404）。

**錯誤本文。** 除特別註明外，錯誤回應是 JSON：`{"error": "<代碼>", "message": "<說明>"}`。`error` 是穩定的代碼，程式應依它判斷；`message` 供人閱讀，文字可能調整。

**未預期的伺服器錯誤。** 任何未預期的失敗回應 500，本文為 `{"error": "internal_error", "message": "...", "errorId": "<識別碼>"}`，不含例外的原因、堆疊、SQL 或設定值。完整原因寫在 log 中，以 `errorId` 可查到對應的那一筆（log 訊息為 `Unexpected error <errorId> while ...`，附例外）。回報問題時請提供 `errorId`。Run 記錄的 `failure`（見 `GET /api/v1/runs/{runId}`）是 pipeline 自己的失敗內容，不屬於這一類。Webhook 入口的 500 另有固定本文，見該端點。

**關閉中。** Engine 收到終止訊號後不再接受新請求：新的請求回應 503 `shutting_down`（並關閉連線），進行中的請求在寬限時間（`RUNLINE_SHUTDOWN_GRACE_SECONDS`，預設 30 秒）內完成；超過寬限時間仍未完成的請求被中止。已開啟的 WebSocket 不計入進行中的請求，關閉時一併中斷。呼叫端遇到 503 `shutting_down` 時，稍後重試即可。兩個探測端點（`GET /api/v1/health/live`、`GET /api/v1/health/ready`）是例外：關閉期間 `live` 仍回 200，`ready` 回 503 且 `status` 為 `shutting_down`，兩者都不關閉連線（[ADR-018](adr/ADR-018-liveness-readiness-probes.md)）。

**路徑範圍。** `/api` 之下不存在的路徑一律回 404，不回傳 Console 的入口頁（[ADR-015](adr/ADR-015-console-frontend.md)）。

**版本資訊與登入。** `GET /api/v1/info` 免認證；`GET /api/v1/system` 需要 Bearer，Console 以它驗證 token 並取得呼叫者的名稱與角色（[ADR-016](adr/ADR-016-engine-build-info-endpoint.md)）。

**時間。** 一律為 ISO-8601 UTC 字串。

## 版本資訊與探測

### `GET /api/v1/info`

認證：無

Engine 的版本資訊，供 Console 的登入頁與每頁的 Engine 標示使用。只含最小集合，不查詢資料庫，回應不快取。本文：

| 欄位 | 說明 |
|---|---|
| `version` | Engine 版本 |
| `commitHash` | 建置所依據的完整 40 字元 commit hash；取不到時為 `unknown` |
| `dirty` | 建置時工作樹是否有未提交變更（布林）；取不到 git 資訊時為 true |

### `GET /api/v1/system`

認證：Bearer（developer）

Engine 的詳細資訊與呼叫者身分。Console 以它驗證 token：401 即 token 無效。本文：

| 欄位 | 說明 |
|---|---|
| `version`、`commitHash`、`dirty` | 同 `GET /api/v1/info` |
| `buildTime` | 建置所依據的 commit 的時間（ISO-8601 UTC），不是建置當下的時鐘；同一 commit 的值固定 |
| `jdk` | 執行環境的 JDK 版本 |
| `startedAt`、`uptimeSeconds` | Engine 啟動的時間與已運行的秒數 |
| `allowListVersion` | 目前生效的白名單版本（文字） |
| `caller` | `{name, role}`：token 對應的名稱與角色（`developer` 或 `admin`） |

### `GET /api/v1/health/live`

認證：無

存活探測，供部署平台使用（[ADR-018](adr/ADR-018-liveness-readiness-probes.md)）。Engine 的行程能回應請求即為 200，本文 `{"status": "up"}`。不檢查資料庫、目錄或 run，不因資料庫故障而失敗；優雅關閉期間仍回 200。回應不快取，不含版本與組態。

### `GET /api/v1/health/ready`

認證：無

就緒探測，供部署平台決定是否導流。全部檢查通過回 200 `{"status": "ready"}`；任何一項未通過回 503，本文 `{"status": "not_ready", "checks": {...}}`。`checks` 的鍵固定為 `startup`、`database`、`runtime`、`shutdown`，值為 `ok` 或 `failed`（`startup` 的 `pending` 為保留值，現行啟動順序下不會出現），不含原因細節。Engine 在啟動完成後才開始接受連線，啟動期間本端點沒有回應（連線被拒或逾時），呼叫端應以啟動寬限容忍，不應期待啟動期間回 503。

| 檢查 | 通過條件 |
|---|---|
| `startup` | 啟動階段已完成（遷移確認、執行期目錄檢查、進行中 run 的中斷標記、待處理觸發的啟動處理、排程器啟動）；能收到回應時恆為 `ok`，保留給日後把啟動工作移到連接埠綁定之後的情況 |
| `database` | PostgreSQL 在短逾時內可連線並完成輕量查詢（結果可短暫快取）|
| `runtime` | run 執行期目錄仍存在且必要的部分齊全 |
| `shutdown` | Engine 未處於關閉流程中 |

收到終止訊號後立即轉為 503，`status` 為 `shutting_down`，早於寬限時間開始。就緒只影響導流，不停止 run 或排程。

## 上傳與查詢

### `POST /api/v1/artifacts`

認證：Bearer（developer）

上傳一個 jar，本文是 jar 的原始位元組（`Content-Type: application/octet-stream`）。Engine 不執行 pipeline 即探索其中的 pipeline、判定 safe 或 unsafe、讀出 metadata，並將 artifact 與其 definition 於同一交易儲存；任何拒絕都不留下資料。上傳者記錄為 token 對應的名稱。新版本的 unsafe 執行設定一律為不允許。相同內容重複上傳回傳同一版本。

| 狀態 | 意義 |
|---|---|
| 201 | 新版本。本文為 artifact |
| 200 | 相同內容先前已上傳，回傳既有版本，不產生新紀錄 |
| 413 `too_large` | 上傳的檔案超過 `UPLOAD_MAX_BYTES`（預設 50 MiB） |
| 422 | 拒絕上傳，`error` 為下列之一 |

422 的 `error`：

| 代碼 | 意義 |
|---|---|
| `not_a_jar` | 不是有效的壓縮檔 |
| `jar_too_many_entries` | jar 內的項目數量超過 `UPLOAD_MAX_ENTRIES`（預設 20000） |
| `jar_entry_too_large` | 某個項目解壓後超過 `UPLOAD_MAX_ENTRY_BYTES`（預設 64 MiB）；`message` 指出項目名稱 |
| `jar_expanded_too_large` | 所有項目解壓後的總大小超過 `UPLOAD_MAX_EXPANDED_BYTES`（預設 256 MiB） |
| `core_classes_bundled` | jar 內含 `dev.lawlan.runline.core` 套件的類別；Run 一律使用 Runner 提供的 core，請以不夾帶 core 的方式重新打包 |
| `metadata_unreadable` | 某個 pipeline 的 metadata 或類別檔無法解析；`message` 指出類別 |
| `no_pipeline_found` | 沒有任何類別以 `@PipelineDefinition` 宣告為 pipeline |
| `duplicate_pipeline_name` | 同一個 jar 內有多個 pipeline 使用相同名稱 |
| `invalid_pipeline_name` | 有 pipeline 的名稱不符規則（只允許字母、數字、`.`、`_`、`-`，且不可為 `.` 或 `..`）；同一 jar 中任一個不合規即整個拒絕，`message` 指出是哪些 pipeline |

解壓上限在 jar 的內容被分析前檢查：Engine 實際解壓計數（不採信 jar 自己宣告的大小），超過任一上限即停止，不展開超量資料。

本文（200、201）：

| 欄位 | 說明 |
|---|---|
| `contentHash` | 內容的 SHA-256（十六進位），版本的識別 |
| `sizeBytes`、`uploadedBy`、`uploadedAt` | 大小、上傳者名稱、上傳時間 |
| `pipelines[]` | 找到的 pipeline：`className`、`name`、`metadata`（`parameters[]`、`files[]`、`network`、`processes`、`resources[]`、`resourceTypes`）、`verdict`（`SAFE` 或 `UNSAFE`）、`reasons[]`（`kind`、`category`、`className`、`member`、`path[]`、`detail`）、`allowListVersion`（判定所用的白名單版本，見「白名單」）、`allowUnsafeExecution`、`warnings[]`（例如宣告了尚未定義的共享資源；見下） |
| `limitations` | 判定未涵蓋的範圍 |

`metadata.resources[]` 是 pipeline 宣告的全部共享資源名稱；`metadata.resourceTypes` 是名稱到「期望型別」的對照，只含另外宣告了型別的名稱（型別取自 `counter`、`file`、`jdbc-pool`、`openai-compatible`，即使該型別尚未實作也可宣告），只宣告名稱者不在其中，沒有任何型別宣告時為 `{}`。型別宣告不影響 safe 或 unsafe 判定。

`warnings[].kind` 的值（只是警告，不影響判定與上傳結果；資源之後被定義或改變時，查詢時重新計算）：`resource_unknown`（宣告的資源尚未定義）、`resource_disabled`（已停用）、`resource_type_mismatch`（資源的型別與宣告的型別不同）、`resource_type_unknown`（宣告的型別不在 `counter`、`file`、`jdbc-pool`、`openai-compatible` 之內；建立 run 時視為型別不符）、`network_host_has_resource`（`network` 宣告的主機與某個 `openai-compatible` 資源的根位址主機相同，不分大小寫；提示應改經由資源存取，`resource` 為該資源；使用資源不需要在 `network` 宣告其主機，也不使 `network` 變成不限制）。每項含 `kind`、`resource`、`message`。

`reasons[].kind` 的值：`UNRESTRICTED_ACCESS`（網路或行程未設限，`category`）、`NOT_ALLOW_LISTED`（白名單外的類別，`className` 與 `path[]`）、`JVM_EXIT`（參照 JVM 結束成員，`member` 與 `path[]`）、`IO_SENSITIVE_MEMBER`（參照 IO 敏感成員：啟動外部行程、載入原生程式碼，或在基礎套件內可直接開啟檔案或網路的成員，`member` 為含描述子的成員名稱，`path[]` 為從 pipeline 到該參照的路徑，不受套件白名單豁免，[ADR-013](adr/ADR-013-io-sensitive-members.md)）、`UNREADABLE_CLASS`（類別檔無法解析，`className`、`path[]`、`detail`）、`LIMIT_EXCEEDED`（分析超出時間或大小預算，`detail`）。新增 `IO_SENSITIVE_MEMBER` 之前已儲存的判定仍照原樣讀取與顯示，不會被自動重判。

### `GET /api/v1/artifacts/{contentHash}`

認證：Bearer（developer）

查詢一個版本；本文同上傳的回傳。開發人員只能查自己上傳的，管理員可查全部。404 `not_found`：不存在，或呼叫者不得查看。

### `GET /api/v1/definitions`

認證：Bearer（developer）

列出 pipeline 定義。開發人員只看到自己上傳的，管理員看到全部。本文 `{"definitions": [...], "limitations": "..."}`，每筆含所屬版本的 `contentHash`、`uploadedBy`、`uploadedAt`，以及與上傳回傳相同的 pipeline 欄位。

### `DELETE /api/v1/artifacts/{contentHash}`

認證：Bearer（admin）

刪除一個版本。204 成功；404 `not_found`；409 `in_use`：仍被 trigger 或 run 引用，不能刪除。

## Run

### `POST /api/v1/runs`

認證：Bearer（developer）

建立一個 run。本文 `{"contentHash": "...", "pipeline": "...", "parameters": {"名稱": "值"}}`，`parameters` 選填。Engine 檢查參數、unsafe 設定與 pipeline 宣告的共享資源，通過才建立；被拒絕時不留下任何 run。開發人員只能對自己上傳的版本建立 run。

| 狀態 | 意義 |
|---|---|
| 201 | 已建立（排入佇列），`Location` 為 `/api/v1/runs/{runId}`，本文為 run |
| 400 `bad_request` | 本文不是預期的 JSON |
| 404 `definition_not_found` | 沒有這個版本與 pipeline，或呼叫者不得使用 |
| 409 `unsafe_not_allowed` | pipeline 被判為 unsafe，且管理員尚未允許它以 unsafe 執行 |
| 409 `resources_unavailable` | pipeline 宣告的共享資源無法使用；本文多一個 `problems[]`，每項 `{resource, problem}`，`problem` 為 `unknown`（未定義，含已被刪除）、`disabled`（已停用）或 `type_mismatch`（pipeline 宣告了型別，而資源的型別不同，或宣告的型別不在封閉集合內）；多個問題一併回報，每個資源只報一項（`unknown`、`disabled` 優先於 `type_mismatch`） |
| 422 `invalid_parameters` | 參數與宣告不符；本文多一個 `problems[]`，每項 `{name, problem}`，`problem` 為 `missing`（缺必填）或 `undeclared`（未宣告） |

Run 的欄位：`runId`、`state`、`contentHash`、`pipeline`、`className`、`source`（`{kind: "MANUAL"|"TRIGGER", name}`）、`parameters`（含套用預設值後的結果）、`createdAt`、`startedAt`、`finishedAt`、`failure`（`{type, message, trace}`，失敗時）、`unsafeExecution`（`{setBy, setAt}`，以 unsafe 執行時）。`state` 的值：`QUEUED`、`WAITING_FOR_RESOURCES`、`INITIALIZING`、`RUNNING`、`TIMED_OUT_UNFINISHED`，終止狀態 `SUCCEEDED`、`FAILED`、`CANCELLED`、`INTERRUPTED`（Engine 停止或重啟時仍在進行的 run）、`TIMED_OUT`。

### `GET /api/v1/runs`

認證：Bearer（developer）

列出 run，新的在前。開發人員只看到自己建立的，管理員看到全部。查詢參數：`pipeline`（只列這個 pipeline 的 run）、`limit`（預設 50，上限 200）。本文 `{"runs": [...]}`。

### `GET /api/v1/runs/{runId}`

認證：Bearer（developer）

查詢一個 run。404 `run_not_found`：不存在、不是 UUID，或呼叫者不得查看。已結束的 run 在結束後超過保留期限（預設 30 天，見 README 的組態）即被清理，之後同樣回 404；未結束的 run 不被清理。

### `POST /api/v1/runs/{runId}/cancel`

認證：Bearer（developer）

取消一個 run。本文 `{"runId", "state", "cancellation"}`。

| 狀態 | 意義 |
|---|---|
| 200 | 尚未開始，已直接結束為 `CANCELLED`（`cancellation` 為 `cancelled`） |
| 202 | 已要求停止；停止是協作式的，run 於 pipeline 回應後結束（`cancellation` 為 `requested`） |
| 404 `run_not_found` | 不存在或不得查看 |
| 409 `already_finished` | 已結束，不能取消 |

### `GET /api/v1/runs/{runId}/log`

認證：Bearer（developer）

查詢已儲存的 log。查詢參數：`after`（只回傳序號大於它的項目，預設 0）、`limit`（預設 500，上限 2000）。本文 `{"entries": [{seq, at, stream, line}], "last": <序號>}`，`stream` 為 `STDOUT` 或 `STDERR`；`last` 是繼續查詢時可用的 `after`。404 `run_not_found`。log 的保留期限可短於 run 紀錄（預設相同）：log 被清理後 run 仍在，`entries` 為空。

### `GET /api/v1/runs/{runId}/log/stream`

認證：Bearer（developer）

WebSocket，供非瀏覽器的客戶端使用。瀏覽器的 WebSocket 不能設定標頭，Console 不使用此端點，改以 `GET /api/v1/runs/{runId}/log?after=` 輪詢（[ADR-017](adr/ADR-017-console-websocket-and-token.md)）；本端點的契約不變。先以一般 HTTP 升級（需要 Bearer 標頭；不可見的 run 在升級前就回 404 `run_not_found`），之後每個 log 項目是一個 JSON 文字框 `{seq, at, stream, line}`，直到 run 結束且所有項目都送出，Engine 以正常代碼（1000，原因 `run ended`）關閉連線。查詢參數 `after` 指定從哪個序號之後開始。串流途中發生未預期的錯誤時，以 1011 關閉，原因為 `internal error <errorId>`，不含內部原因。串流途中該 run 因超過保留期限被清理時，已送出的項目維持完整、連續，連線以正常代碼關閉，不以錯誤收場；之後該 run 回 404 `run_not_found`。

### `PUT /api/v1/definitions/{contentHash}/{pipeline}/unsafe-execution`

認證：Bearer（admin）

設定某個版本中某個 pipeline 是否允許以 unsafe 執行（每個版本各自設定，預設不允許，不繼承）。本文 `{"allow": true}`。200 回傳 `{contentHash, pipeline, allow, setBy, setAt}`（`setBy` 為管理員的 token 名稱）；404 `definition_not_found`；400 `bad_request`。

## 共享資源（管理員）

資源由管理員定義，pipeline 在 metadata 中宣告需要的資源名稱，另可宣告期望的型別；規則見 [ADR-007](adr/ADR-007-shared-resources.md) 與 [ADR-019](adr/ADR-019-typed-shared-resources.md)。名稱 1 至 100 個字元，字母、數字、`.`、`_`、`-`，以字母或數字開頭。

資源有型別，取自封閉集合：`counter`、`file`、`jdbc-pool`、`openai-compatible`；沒有外掛或註冊型別的方式。型別與名稱在建立後不可修改（要換型別就刪除後重新建立）。目前可建立的型別是 `counter`（只有名稱與容量，也就是資源原本的語意）、`file`（Engine 主機上資源根目錄之下的一個檔案）與 `openai-compatible`（一個 OpenAI 相容服務，見下方「`openai-compatible` 型別」）；`jdbc-pool` 在其工作項完成前建立時被拒絕（`invalid_resource`，`problem` 為 `unsupported_type`）。機密的清單與重載端點見下一節「機密（管理員）」；資源以別名引用機密，別名的狀態見下方資源欄位的 `secretStatus`。

資源的欄位（建立、查詢、列表與修改的回傳相同）：`name`、`type`、`capacity`、`enabled`、`settings`（型別專屬的非機密設定，物件；`counter` 為 `{}`）、`secretAlias`（機密在金鑰庫中的別名，一律是小寫的正規化形式，沒有時為 `null`；機密值不會出現在任何回應）、`secretStatus`（別名對金鑰庫的狀態：`not_set` 未設定別名、`found` 金鑰庫有這個機密項目且可使用、`missing` 設定了別名但金鑰庫沒有該別名或 Engine 沒有組態金鑰庫、`invalid_secret` 別名存在但機密值含非可列印 ASCII 而不被使用；永遠不含機密值，金鑰庫重載後隨之變化）、`concurrencyLimit`（Engine 推導的「整體並行上限」：容量乘以每 run 同時請求數，`openai-compatible` 才有，其他型別為 `null`）、`usage`（型別專屬的使用量：`openai-compatible` 為 `{"inFlightRequests": n}`，目前進行中的請求數；其他型別為 `null`）、`lastCheck`（最近一次實體檢查的結果，見下；從未檢查、或設定或別名在檢查後被修改時為 `null`）、`createdBy`、`createdAt`、`updatedBy`、`updatedAt`、`holders[]`（`runId`、`pipeline`、`heldSince`、`heldSeconds`）、`waiters[]`（依服務順序；`runId`、`pipeline`、`waitingFor[]`、`waitingSince`、`waitedSeconds`），以及 `declaredBy`：宣告了這個資源的 pipeline 定義，`count`（定義數）、`triggers`（綁在這些定義上的 trigger 數）、`definitions[]`（每項 `contentHash`、`pipeline`、`declaredType`（該定義宣告的型別，只宣告名稱時為 `null`）、`triggers`）。

`invalid_resource`（422）的本文多一個 `problem`，說明原因類別：

| `problem` | 意義 |
|---|---|
| `name` | 名稱不合規 |
| `capacity` | 容量小於 1 |
| `nothing_to_change` | 修改沒有要改的欄位 |
| `unknown_type` | 型別不在封閉集合內 |
| `unsupported_type` | 型別在集合內，但尚未實作，還不能建立（目前只有 `jdbc-pool`） |
| `invalid_settings` | 這個型別沒有這些設定欄位（`counter` 沒有任何設定），或缺少必要欄位（`file` 只有 `path`，必填，非空字串；`openai-compatible` 必填 `baseUrl`，且不接受清單以外的欄位，包括任何形式的金鑰、路徑與標頭） |
| `invalid_base_url` | `openai-compatible` 的 `baseUrl` 不是自成一格的 `http` 或 `https` 位址：含使用者資訊、查詢、片段、`.` 或 `..` 片段，或不可列印字元 |
| `invalid_header` | `openai-compatible` 的額外標頭（`headers`）、`organization` 或 `project` 不合規：名稱含 `auth`、`key`、`token`、`secret`、`cookie`（不分大小寫），是 Engine 自己設定或決定請求框架的標頭（`Host`、`Content-Type`、`Content-Length`、`Accept`、`Connection`、`Transfer-Encoding`、`OpenAI-Organization`、`OpenAI-Project` 等），名稱不是標頭名稱，或值含換行與其他控制字元 |
| `invalid_endpoint` | `endpoints` 含不在端點目錄內的名稱、這個版本尚未提供的條目（目前沒有：目錄的每個條目都已提供），或為空 |
| `invalid_request_defaults` | `defaults`、`allowedModels`、`lockedParameters` 或 `maxValues` 不合規：只有模型與取樣、長度相關的參數可以設定，值須是該參數的型別，預設不得違反同一資源的模型清單與上限 |
| `invalid_timeout` | `timeouts` 含不認得的項目，或值不是正整數毫秒（`totalMs` 可為 `null` 表示不設） |
| `invalid_limit` | `requestsPerRun`、`maxRequestBytes`、`maxResponseBytes` 或 `maxDownloadBytes` 超出允許範圍 |
| `path_outside_root` | `file` 的路徑不在資源根目錄內：絕對路徑、`..` 跳出根目錄，或路徑上的符號連結解析後跳出根目錄 |
| `path_unusable` | `file` 的路徑目前不可用：資源根目錄不可用、檔案所在的目錄不存在也無法建立，或檔案不可讀寫 |
| `invalid_secret_alias` | 這個型別沒有機密別名（`counter`、`file` 沒有），或別名不合規（格式同資源名稱：1 至 100 個字元，字母、數字、`.`、`_`、`-`，以字母或數字開頭） |
| `immutable_name` | 修改嘗試帶了 `name`；名稱不可修改 |
| `immutable_type` | 修改嘗試帶了 `type`；型別不可修改，值相同也一樣被拒絕 |

### `openai-compatible` 型別

一個 OpenAI 相容服務（例如本機的 lemonade）。位址、金鑰、標頭、可用的端點、逾時與大小上限是管理員固定的，pipeline 不能覆寫，也不能自選主機、路徑、方法或標頭；pipeline 只能以端點目錄的條目名稱呼叫，並在管理員允許的範圍內覆寫模型與取樣參數（[ADR-019](adr/ADR-019-typed-shared-resources.md) 第 4 點、[WI-46](work-items/WI-46-openai-compatible-resource.md)）。金鑰是機密，只以 `secretAlias` 指向金鑰庫的別名（`counter` 與 `file` 帶別名仍被拒絕）；金鑰庫重載後取得資源的 run 用新金鑰，已持有者繼續用取得當下的金鑰與設定。

`settings` 的欄位（只有這些；其他欄位為 `invalid_settings`）：

| 欄位 | 意義 | 預設 |
|---|---|---|
| `baseUrl` | 必填。根位址，含根路徑（例如 `http://localhost:8000/api/v1`）；只允許 `http` 與 `https`；尾端的 `/` 被去掉；用 `https` 時使用 JVM 預設信任，沒有關閉主機名稱或憑證驗證的途徑 | 無 |
| `organization`、`project` | 選填，作為 `OpenAI-Organization`、`OpenAI-Project` 標頭送出（非機密） | 不送 |
| `headers` | 選填，額外標頭（名稱到值，非機密，最多 32 個）；名稱不得含 `auth`、`key`、`token`、`secret`、`cookie`，不得是 Engine 管理的標頭（`invalid_header`）；機密不放這裡 | 無 |
| `endpoints` | 啟用的端點條目名稱（見下方目錄）；只能是目錄內且這個版本已提供的條目，至少一個；以條目為單位，不依群組 | `chat.completions`、`completions`、`embeddings`、`models.list`、`models.retrieve` |
| `timeouts` | 五種逾時，毫秒：`connectMs`（建立連線）、`firstByteMs`（送出請求到回應的第一個位元組；串流呼叫涵蓋到第一個事件（包含 prefill），非串流呼叫在生成結束前沒有任何資料，所以它實質上就是整體生成上限）、`idleMs`（串流中兩次讀取之間，第一個位元組之後；串流適用，非串流的讀取也受它約束）、`totalMs`（選填，單次呼叫的整體上限，`null` 或省略為不設）、`quotaWaitMs`（等待每 run 同時請求額度的上限，不計入生成時間） | 10000、900000（15 分鐘）、300000（5 分鐘）、不設、60000 |
| `requestsPerRun` | 每個 run 同時進行的請求數上限；整體並行上限是容量乘以它 | 1 |
| `maxRequestBytes`、`maxResponseBytes`、`maxDownloadBytes` | 請求（含多部分表單）的大小上限、記憶體內回應的大小上限、寫入檔案的二進位回應的總上限（最大 16 GiB）；pipeline 每次呼叫只能收緊 | 32 MiB、8 MiB、256 MiB |
| `defaults` | 請求參數的預設，pipeline 呼叫時提供的同名值蓋過它；只能是 `model`、`temperature`、`top_p`、`top_k`、`min_p`、`max_tokens`、`max_completion_tokens`、`max_output_tokens`、`stop`、`seed`、`response_format`、`presence_penalty`、`frequency_penalty`、`repeat_penalty`、`n`、`reasoning_effort`；`model` 套用到有模型的條目，其餘只套用到對話、補全與 responses 的建立 | 無 |
| `allowedModels` | 允許的模型清單，空為不限；清單不空時，沒有模型（含預設）的請求也被拒絕 | 不限 |
| `lockedParameters` | 鎖定的參數（同上清單）：pipeline 提供它（無論值為何）就被拒絕 | 無 |
| `maxValues` | 數值參數的上限（例如 `{"max_tokens": 4096}`）：pipeline 提供的值超過就被拒絕 | 無 |

寫入的是正規化形式：省略的項目以有效的預設值寫出，`endpoints` 依目錄順序明列。因此新版 Engine 新增的條目與改變的預設值不影響既有資源；新增的條目對既有資源預設不啟用。

**端點目錄**（條目名稱、方法與路徑、預設是否啟用；路徑在根位址之下）：`chat.completions`（`POST /chat/completions`，預設啟用）、`completions`（`POST /completions`，預設啟用）、`embeddings`（`POST /embeddings`，預設啟用）、`models.list`（`GET /models`，預設啟用）、`models.retrieve`（`GET /models/{model}`，預設啟用）、`responses.create`（`POST /responses`）、`responses.retrieve`（`GET /responses/{id}`）、`responses.delete`（`DELETE /responses/{id}`）、`responses.cancel`（`POST /responses/{id}/cancel`）、`responses.input_items`（`GET /responses/{id}/input_items`）、`moderations`（`POST /moderations`）、`rerank`（`POST /rerank`）、`reranking`（`POST /reranking`；兩者各為一個條目，哪個有效以對目標服務的實測決定）、`images.generations`（`POST /images/generations`）、`files.list`（`GET /files`）、`files.retrieve`（`GET /files/{id}`）、`files.delete`（`DELETE /files/{id}`）、`batches.create`（`POST /batches`）、`batches.list`（`GET /batches`）、`batches.retrieve`（`GET /batches/{id}`）、`batches.cancel`（`POST /batches/{id}/cancel`）。未標預設啟用者（尤其有狀態的刪除與取消條目）由管理員逐條啟用。另有多部分上傳與二進位回應的條目（WI-53），同樣由管理員逐條啟用：`images.edits`（`POST /images/edits`）、`images.variations`（`POST /images/variations`）、`audio.speech`（`POST /audio/speech`，二進位回應）、`audio.transcriptions`（`POST /audio/transcriptions`）、`audio.translations`（`POST /audio/translations`）、`files.create`（`POST /files`）、`files.content`（`GET /files/{id}/content`，二進位回應）；事件串流（`chat.completions`、`completions`、`responses.create`）由存取端的 `stream(request)` 提供（見下方「串流」），`audio.speech` 的音訊由 `streamBytes(request)` 以位元組塊拉取；`call` 請求 `stream` 仍被拒絕。路徑參數（`{model}`、`{id}`）只接受 `A-Za-z0-9._:-`、1 至 256 個字元，且不是 `.` 或 `..`（因此含 `/` 的模型識別碼不能用 `models.retrieve`）；查詢參數只接受條目列出者。

**請求規則與錯誤類別**（pipeline 看到的，只有類別與 HTTP 狀態碼，沒有服務回的本文或訊息）：資源的 `defaults` 在先，pipeline 的值蓋過同名項目，其餘本文由 pipeline 完全提供；被鎖定的參數（`PARAMETER_LOCKED`）、不在允許清單的模型（`MODEL_NOT_ALLOWED`）、超過上限的數值（`VALUE_ABOVE_LIMIT`）、`call` 的本文帶 `stream` 或 `stream_options`，或對不能串流的條目呼叫 `stream`（`STREAM_NOT_SUPPORTED`）、過大的請求（`REQUEST_TOO_LARGE`）被拒絕且不送出請求；未啟用的條目（`ENDPOINT_NOT_ENABLED`）、目錄沒有的名稱（`UNKNOWN_ENDPOINT`）、不合規的路徑參數、查詢參數與本文（`INVALID_ARGUMENT`）同樣不送出。送出後：401 與 403 為 `DENIED`、429 為 `RATE_LIMITED`、5xx 為 `SERVER_ERROR`、其他非 2xx 為 `REQUEST_REJECTED`（都附狀態碼）、連不上為 `CONNECTION_FAILED`、離開根位址的重新導向為 `REDIRECT_BLOCKED`（不跟隨；根位址之內的重新導向會被跟隨，最多 5 次）、回應過大為 `RESPONSE_TOO_LARGE`、被取消為 `CANCELLED`、金鑰庫給不出金鑰為 `SECRET_UNAVAILABLE`；逾時各有專屬類別：`CONNECT_TIMEOUT`、`FIRST_BYTE_TIMEOUT`、`IDLE_TIMEOUT`、`TOTAL_TIMEOUT`、`QUOTA_WAIT_TIMEOUT`。Engine 不自動重試。pipeline 呼叫時可以縮短任何一種逾時，不能放寬。回傳給 pipeline 的回應標頭剝除名稱含 `auth`、`key`、`token`、`secret`、`cookie` 者（含 `Set-Cookie`、`WWW-Authenticate`、`Proxy-Authenticate`），其餘標頭值中出現金鑰處以 `***` 取代；回應本文 Engine 不處理（服務若把金鑰回射到成功的本文，pipeline 會拿到它，這是 ADR-019 接受的限度；Engine 自己不把本文寫進 log、trace 或錯誤，pipeline 若把它寫進 run 的 log 或讓 run 以它失敗，盡力而為的字串遮蔽會把金鑰換成 `***`）。

**串流**（`stream(request)`，不新增 HTTP 路由）：對可串流的條目（`chat.completions`、`completions`、`responses.create`）送出請求，服務開始回應後回傳 `OpenAiStream`，pipeline 以 `next()` 逐個事件拉取（事件的 `data` 文字，只含 JDK 內建型別；服務送出 `[DONE]` 或乾淨地關閉連線時回傳 `null`），以 `close()` 或 try-with-resources 隨時關閉。`stream` 欄位由本型別設定，pipeline 的本文帶 `stream` 為 `INVALID_ARGUMENT`；`stream_options`（例如 `{"include_usage": true}`）原樣送出。串流佔用每 run 的請求額度直到串流結束、被關閉、失敗或 run 終止，因此每 run 上限為 1 時，同一個 run 在串流未結束前再發請求會等待額度，受 `quotaWaitMs` 約束並得到 `QUOTA_WAIT_TIMEOUT`，作者須先讀完或關閉串流。逾時：`firstByteMs` 涵蓋送出請求到第一個位元組（含 prefill），之後 `idleMs` 涵蓋兩次讀取之間（`IDLE_TIMEOUT`），選填的 `totalMs` 涵蓋整個串流（`TOTAL_TIMEOUT`）；持續送出資料的串流不因整體時間逾時。run 取消、被強制釋放或終止時連線立即中斷並歸還額度，拉取中的 pipeline 得到 `CANCELLED`；串流中途斷線為 `CONNECTION_FAILED`，已拉到的事件留在 pipeline，之後每次拉取得到同一個類別，Engine 不重試。單一事件大於 `maxResponseBytes` 為 `RESPONSE_TOO_LARGE`。事件中出現金鑰處以 `***` 取代。串流的 `status` 與 `headers`（剝除授權相關者）在開啟時給出，非 2xx 的回應在開啟時以類別失敗。可觀測：metric `runline.resources.openai.stream.first_chunk.duration`（首塊時間）與 `runline.resources.openai.stream.max_gap.duration`（等待下一個事件的最長間隔）、既有的 `generation.duration`（首位元組到串流結束）與 `tokens`（取自串流結尾的 `usage`，需 pipeline 帶 `stream_options.include_usage`；沒有就不計），標籤同前；run 的 trace 每個串流一個 span（`runline.resource.openai.stream`，帶 `openai.quota_wait_ms`、`openai.first_byte_ms`、`openai.max_chunk_gap_ms`、`openai.generation_ms`），每次拉取不產生 span；不記錄串流內容。資源的使用量 `inFlightRequests` 包含進行中的串流。

**多部分上傳、二進位回應與檔案**（WI-53；不新增 HTTP 路由）：
- 上傳：`call(OpenAiRequest(..., fields, files, sizes))` 對多部分條目送出一份由 Engine 組裝的表單。pipeline 只給文字欄位（`fields`，名稱必須是條目列出者）與檔案部分（`files`，欄位名稱必須是條目列出者、各最多一次、必要者必須有）。條目的欄位：`files.create` 為 `purpose` 與檔案 `file`；`images.edits` 為 `model`、`prompt`、`n`、`size`、`response_format`、`user`、`quality`、`background` 與檔案 `image`（必要）、`mask`；`images.variations` 為 `model`、`n`、`size`、`response_format`、`user` 與 `image`；`audio.transcriptions` 為 `model`、`language`、`prompt`、`response_format`、`temperature` 與 `file`；`audio.translations` 為 `model`、`prompt`、`response_format`、`temperature` 與 `file`。其餘欄位、欄位名稱含換行或引號者、JSON 本文與多部分並用，都是 `INVALID_ARGUMENT` 且不送出請求。檔案部分的來源二選一：位元組（`OpenAiUpload.bytes(field, filename, bytes)`），或 pipeline 共享目錄或 run 私有目錄的檔案（`OpenAiUpload.file(field, OpenAiFile(scope, 相對路徑))`，Engine 側串流讀取，不整份載入記憶體；範圍須已在 metadata 宣告，唯讀即可）。送出的檔名只接受 `A-Za-z0-9._ -`、1 至 128 個字元、不是 `.` 或 `..`（否則 `INVALID_ARGUMENT`，不做編碼），省略時用檔案的名稱；每個檔案部分的 Content-Type 一律是 `application/octet-stream`，由 Engine 決定，pipeline 不能設標頭、Content-Type 或分隔字串（分隔字串是每個請求新產生的 128 位元隨機值）。表單的 `model` 欄位套用資源的預設模型、允許清單與鎖定；`maxValues` 的數值欄位（例如 `temperature`）套用上限。表單與檔案超過請求上限為 `REQUEST_TOO_LARGE`，在送出任何位元組之前判定。上傳進行中的進度停滯超過 `idleMs` 為 `IDLE_TIMEOUT`；持續有進度的慢速上傳不因整體時間逾時。
- 二進位回應：`download(request)` 以 `OpenAiBinaryResponse`（`status`、`headers`、`body`）回傳位元組，超過記憶體上限（`maxResponseBytes`）為 `RESPONSE_TOO_LARGE`，宣告長度超過者不讀本文；`downloadTo(request, OpenAiFile(scope, 相對路徑))` 把回應寫入檔案並只回傳 `OpenAiStoredResponse`（`status`、`headers`、`scope`、`path`、`size`）。寫入先進同一目錄內的暫存檔，完成才取代目標，所以失敗時沒有任何部分檔案，原有檔案不變；超過總上限（`maxDownloadBytes`）為 `RESPONSE_TOO_LARGE`，讀到上限即中止，不多讀；寫入使目錄超過每個範圍的用量上限（`RUNLINE_WORKSPACE_MAX_BYTES`，替換既有檔案只計差額）為 `SCOPE_FULL`。範圍須已在 metadata 宣告為可寫；目標路徑在送出請求之前檢查。`call` 不能用於回應為二進位的條目，`download` 不能用於回應為 JSON 的條目（`INVALID_ARGUMENT`）。
- 範圍與路徑：pipeline 只給範圍名稱（`PIPELINE_SHARED`、`RUN_PRIVATE`）與相對路徑，目錄的位置由 Runner 在 run 的目錄備妥後告知 Engine 側（`ResourceHost.workspaceReady`，另帶 pipeline 宣告的範圍與可寫性），呼叫帶的任何目錄、上限或模式資訊一律不被採信，由 Engine 側覆蓋。`..`、絕對路徑、解析後離開範圍的符號連結為 `PATH_REJECTED`，與 `file` 資源同一套規則（ADR-009）；不存在為 `NOT_FOUND`，目錄或不可讀為 `FAILED`；沒有宣告的範圍、或對唯讀範圍寫入為 `PATH_REJECTED`；未知的範圍名稱為 `INVALID_ARGUMENT`。
- 語音串流：`streamBytes(request)` 對 `audio.speech` 回傳 `OpenAiByteStream`（`status`、`headers`、`next()` 回傳位元組塊、結束為 `null`、`close()`），規則與事件串流相同（佔用額度直到結束或關閉、首位元組與閒置逾時、取消即中斷）；本文由 pipeline 完全提供（例如 `stream_format`），Engine 不加 `stream`；對文字串流呼叫 `stream` 或對其他條目呼叫 `streamBytes` 為 `STREAM_NOT_SUPPORTED`。
- 每次呼叫 pipeline 可以收緊大小：`sizes`（`request`、`response`、`download`），不能放寬。回應標頭剝除授權相關者並遮蔽金鑰，與其他條目相同；二進位回應本文與上傳內容 Engine 不處理、不寫入 log 與 trace，也不進入 metric 標籤（檔名與位元組不是標籤）。二進位回應（記憶體、寫入檔案、位元組串流三條路徑）逐段掃描金鑰的位元組，含跨段切開的情形；找到時整份回應被拒絕為 `SECRET_IN_RESPONSE`，不改寫（改寫會損壞音訊與檔案），pipeline 拿不到任何部分（串流把每段結尾可能是金鑰開頭的位元組留到下一段才交出），寫到一半的檔案連同暫存檔刪除。限度：JSON 本文不掃描（見上，ADR-019 接受的限度）。
- 取消、強制釋放與終止：進行中的上傳與下載立即中斷（服務端觀察到連線中斷），額度歸還，暫存檔移除。

**整體並行上限**：容量是同時持有的 run 數，每個 run 同時進行的請求數受 `requestsPerRun` 限制，因此服務端的同時請求數不超過容量乘以 `requestsPerRun`；資源的 `concurrencyLimit` 回報這個乘積，每 run 上限維持 1 時，它就是容量（目標服務最高並行為 1 時，容量設 1）。限度：降低容量不從持有者手上收回資源，降低後到持有者結束之前，服務端的並行請求數可以暫時高於新的上限（`concurrencyLimit` 已是新值）；run 取消或被強制釋放時 Engine 立即關閉連線並歸還額度，但服務端是否隨連線中斷而停止生成取決於服務，不停止時服務端的真實並行可能短暫高於 Engine 的計數；容量 1 時整個服務同一時間只有一個 run 持有，其他 run 在初始化階段排隊，即使持有者暫時沒有請求。

### `POST /api/v1/resources`

認證：Bearer（admin）

定義資源。本文 `{"name": "...", "capacity": 1}`，另可帶 `type`（省略視為 `counter`，所以只送名稱與容量的呼叫維持有效）、`settings`、`secretAlias`。`type` 為 `file` 時 `settings` 為 `{"path": "相對於資源根目錄的路徑"}`，例如 `{"type": "file", "settings": {"path": "logs/out.txt"}}`；絕對路徑、跳出根目錄的路徑與解析後跳出根目錄的符號連結被拒絕，檔案所在的目錄不必先存在（只要可建立）。`type` 為 `openai-compatible` 時 `settings` 見下方「`openai-compatible` 型別」，`secretAlias` 選填（本機服務可能不需要金鑰）；回傳的 `settings` 是寫出每個有效值的正規化形式（省略的項目以預設值寫出），`secretAlias` 是小寫的正規化形式。201（帶 `Location`）回傳資源；400 `bad_request`；409 `resource_exists`；422 `invalid_resource`（見上表：名稱、容量、型別不明或尚未支援、設定不合規、路徑不在資源根目錄內或不可用、`counter` 帶有設定或機密別名）。

### `GET /api/v1/resources`

認證：Bearer（admin）

列出資源及其持有者、等待者與宣告者。本文 `{"resources": [...]}`，每個資源的欄位見上。

### `GET /api/v1/resources/{name}`

認證：Bearer（admin）

查詢一個資源，本文同上。404 `resource_not_found`。

### `PATCH /api/v1/resources/{name}`

認證：Bearer（admin）

修改容量、啟用狀態、型別專屬的設定或機密別名。本文 `{"capacity": 2, "enabled": false, "settings": {...}, "secretAlias": "..."}`，至少一項；`settings` 整份取代（`file` 為 `{"path": "..."}`，`openai-compatible` 見下方，規則同建立，且同樣寫成正規化形式），`secretAlias` 換成另一個別名（規則同建立；沒有清除別名的修改，要去掉金鑰須刪除後重建）；修改的設定或別名使最近一次檢查結果（`lastCheck`）清除，只改容量或啟用狀態不影響它。只改容量或啟用時不重新檢查設定。降低容量不會從持有者手上收回資源；停用會讓正在等待它的 run 失敗，不影響持有者；修改設定不影響已持有者：持有者的存取端綁定取得當下的設定與機密，之後取得的 run 才用新設定。本文帶 `name` 或 `type`（無論值為何）即為嘗試修改不可修改的欄位，被拒絕。200 回傳資源；400 `bad_request`；404 `resource_not_found`；422 `invalid_resource`（容量小於 1、沒有要修改的欄位、嘗試修改名稱或型別、設定不合規、路徑不在資源根目錄內或不可用、`counter` 帶有設定或機密別名）。

### `DELETE /api/v1/resources/{name}`

認證：Bearer（admin）

刪除資源。沒有持有者與等待者時刪除，仍有 pipeline 定義或 trigger 宣告它不阻擋刪除：之後這些 pipeline 建立 run 被拒絕為 `unknown`（與資源尚未定義相同）。刪除與 run 取得資源是互斥的決定：刪除成立後沒有 run 取得這個資源，有 run 持有或等待時不會刪除。刪除後可重新建立同名資源（可為不同型別）。刪除記錄管理員名稱於 log。

查詢參數 `preview=true`（預設 false，沿用白名單的預覽慣例）：不改變任何東西，回應 200，本文 `{resource, definitions, triggers, holders, waiters, inUse}`：`definitions` 為宣告它的定義數，`triggers` 為綁在這些定義上的 trigger 數，`holders` 與 `waiters` 為目前的持有者與等待者數，`inUse` 表示目前會被拒絕刪除。使用中時預覽不失敗，由實際刪除回 `resource_in_use`。

| 狀態 | 意義 |
|---|---|
| 204 | 已刪除 |
| 200 | `preview=true`：預覽，什麼都沒改變 |
| 400 `bad_request` | `preview` 不是 true 或 false |
| 404 `resource_not_found` | 沒有這個資源（預覽也一樣） |
| 409 `resource_in_use` | 有持有者或等待者，什麼都沒改變；本文多 `holders` 與 `waiters`（數量）。需等待結束、先停用資源讓等待者失敗，或強制釋放持有者 |

### `POST /api/v1/resources/{name}/check`

認證：Bearer（admin）

主動檢查資源的實體是否可用，不取得容量，不影響進行中的 run、持有者與等待者；停用的資源也可檢查。檢查內容由型別決定：`counter` 沒有實體，一律通過；`file` 驗證根目錄可用、路徑沒有跳出根目錄、檔案所在的目錄存在或可建立、既有的檔案可讀寫（會真的開啟它，不建立也不截斷任何東西）。檢查有整體時間上限（`RUNLINE_RESOURCE_CHECK_TIMEOUT_SECONDS`，預設 10 秒），逾時是一種失敗類別。同一資源同時被檢查時共用同一次檢查與同一個答案；逾時而仍卡住的檢查未結束前，再次檢查立即回 `timeout`，不會再開新的檢查。

200 回傳 `{ok, failure, checkedAt}`：`ok` 為是否通過，`failure` 只在失敗時有值（通過時為 `null`），不含原因說明、路徑、位址或機密；原因與例外寫在 Engine 的 log。`failure` 的值：`root_unavailable`（資源根目錄不存在或不可讀寫）、`parent_not_creatable`（檔案所在的目錄不存在也無法建立）、`not_readable_writable`（檔案不可讀寫）、`path_outside_root`（路徑解析後不在根目錄內，例如目錄被換成指向根目錄外的符號連結）、`connection_failed`（`openai-compatible`：服務連不上）、`rejected`（服務拒絕了金鑰，401 或 403）、`server_error`（服務回 5xx）、`unexpected_response`（服務有回應，但不是檢查要的：模型列表回了 2xx 以外的狀態，或回應過大）、`redirect_blocked`（服務把檢查導向根位址之外）、`alias_missing`（資源的金鑰別名不在金鑰庫，或 Engine 沒有組態金鑰庫；此時不送出任何請求，與 `secretStatus` 為 `missing` 一致）、`alias_invalid`（別名在金鑰庫，但機密不可使用，即 `secretStatus` 為 `invalid_secret`）、`timeout`（逾時）、`error`（檢查本身出錯，細節在 log）。`openai-compatible` 的檢查只做連線與一個輕量讀取：啟用了模型列表（`models.list`）時讀取它，否則只對根位址發一個 GET，任何不是拒絕或失敗的回應都算通過；它用自己的短逾時（上述 `RUNLINE_RESOURCE_CHECK_TIMEOUT_SECONDS`，連線、首位元組與閒置都以它為限），不用資源自己的逾時，不取得容量與每 run 的請求額度，也不產生任何內容。服務正在生成而來不及回應時結果是 `timeout`，不一定代表服務故障（目標服務並行為 1 時尤其如此）。結果與時間保存為資源的 `lastCheck`（`{ok, failure, checkedAt}`，同上）；資源的設定被修改時清除，其他修改不影響它。404 `resource_not_found`；開發人員得到 403，沒有 token 得到 401。檢查記錄管理員名稱與結果類別於 log，並計入 metric（標籤只有資源名稱與型別）。

### `POST /api/v1/resources/{name}/holders/{runId}/release`

認證：Bearer（admin）

強制某個持有者放開這個資源（記錄於 log，含管理員名稱）；run 本身不會被停止。對有存取端的型別（`file`、`openai-compatible`），該持有者的存取端在容量釋放之前同時失效：之後的操作失敗並註明原因為強制釋放，且不會對實體產生任何效果，所以下一位取得者不會與它同時使用同一個檔案；`openai-compatible` 進行中的請求同時被取消（連線關閉，請求額度歸還，該呼叫得到 `CANCELLED`），但服務端是否隨連線中斷而停止生成取決於服務。200 回傳 `{resource, runId, pipeline, heldSince}`；404 `resource_not_found` 或 `not_a_holder`。

## 機密（管理員）

機密保存在 Engine 唯讀開啟的 PKCS12 金鑰庫，由維運以 JDK 的金鑰庫工具管理；API 只認別名，**不接受也不回傳任何機密值**，也不回傳項目內容與金鑰庫密碼（[ADR-019](adr/ADR-019-typed-shared-resources.md) 第 6 點、[WI-41](work-items/WI-41-keystore-secrets.md)）。別名一律以小寫（`Locale.ROOT`）正規化：請求與回應中的別名都是正規化後的形式，資源引用別名時大小寫不同視為同一個。

別名的狀態（`status`）：

| `status` | 意義 |
|---|---|
| `found` | 別名存在且可使用 |
| `invalid_secret` | 機密項目的值含非可列印 ASCII 字元（已損毀，或違反機密字元集），Engine 拒絕使用它且不輸出值；請維運刪除後重新匯入 |

項目類型（`type`）：`secret`（機密）、`trusted_certificate`（受信任憑證）、`private_key`（私鑰與憑證鏈）。後兩種的資源使用由 [WI-52](work-items/WI-52-tls-trust-and-mtls.md) 驗證。其他類型的項目被忽略（記錄於 log），不出現在清單。

### `GET /api/v1/secrets`

認證：Bearer（admin）

列出金鑰庫的別名。200 本文 `{"secrets": [...]}`，每項 `{alias, type, status, usedBy}`：`usedBy` 為引用該別名的資源名稱（排序；沒有時為 `[]`）。409 `secret_store_not_configured`：Engine 沒有組態金鑰庫。開發人員得到 403，沒有 token 得到 401。

### `POST /api/v1/secrets/reload`

認證：Bearer（admin）

整體重新讀取金鑰庫檔案（不在檔案變動時自動重載，生效時點由管理員決定）。200 本文 `{"aliases": 3, "changed": [{"alias": "...", "usedBy": [...]}]}`：`aliases` 為重載後的別名數；`changed` 為新增、移除或內容有變更的別名（依別名排序）與引用它們的資源，內容是否有變更以內部指紋判斷，不輸出任何值。重載後，內容有變的別名其引用資源之後被取得的 run 使用新機密，進行中的 run 不受影響。

| 狀態 | 意義 |
|---|---|
| 200 | 已重載 |
| 409 `secret_store_not_configured` | Engine 沒有組態金鑰庫 |
| 422 `secret_store_unreadable` | 金鑰庫無法讀取，記憶體中的內容維持原狀；本文多一個 `problem`，為失敗類別：`file_missing`（檔案缺失）、`wrong_password`（密碼錯誤）、`corrupt`（檔案毀損或截斷）、`wrong_format`（格式不符：不是 PKCS12，例如 JKS、JCEKS 或其他檔案）、`unreadable`（其餘無法開啟）。不含路徑、密碼與例外內容 |

重載記錄管理員名稱與結果類別於 log，並計入 metric `runline.secrets.reloads`（標籤只有 `result`：`ok` 或失敗類別）。

## 白名單（管理員）

白名單決定哪些類別參照算「受信任」，因此直接決定 pipeline 是 safe 或 unsafe（[ADR-002](adr/ADR-002-context-and-unsafe.md)、[ADR-013](adr/ADR-013-io-sensitive-members.md)、[ADR-014](adr/ADR-014-class-level-allow-list-entries.md)）。只有管理員能讀寫，開發人員一律得到 403。條目有兩種，由 `kind` 明確區分，不依名稱猜測：

| `kind` | 意義 | `exactOnly` |
|---|---|---|
| `package` | 套件與其子套件；`exactOnly` 為 true 表示「僅此套件」，不含子套件 | 有意義 |
| `class` | 完整類別名稱，只放行該類別與其巢狀類別；類別內提供 IO 的成員仍判為 unsafe（成員層級規則不受豁免） | 不適用，回應中為 `null`；請求中設為 true 被拒絕 |

名稱格式：以 `.` 分隔的 Java 識別字；類別名稱必須含套件。加入白名單條目是管理員的信任決定：被放行的套件或類別其內部不再被檢查。

**版本與重判。** 白名單整份有版本，從 1 開始，每次條目變更（新增、修改、刪除）產生新版本，記錄變更者（token 對應的名稱，從不是 token）與時間。條目變更與它觸發的重判在同一個資料庫交易完成：成功即代表所有既有 pipeline 定義已用新版本重判；任何失敗都使白名單與判定維持原狀。每個 pipeline 的判定在 `allowListVersion` 顯示它是用哪個版本判定的（白名單由資料庫管理之前儲存的判定顯示 `config`，在下一次重判之前維持原樣可讀）。

重判使用 analyzer 對資料庫中儲存的 jar 重新分析，更新每個定義的 safe／unsafe、原因與版本，不更新 metadata。因重判而新變成 unsafe 的定義，其「允許以 unsafe 執行」被設為不允許（該設定的設定者與時間記為這次變更者與時間）；原本就 unsafe 或變成 safe 的定義，其設定保持不變。重判不影響進行中的 run。重新分析時 jar 或其宣告已無法讀取的定義，無法確認安全，判為 unsafe（原因 `UNREADABLE_CLASS`）並計入 `impact.unreadable`。

**預覽。** 所有會改變內容的操作都接受查詢參數 `preview=true`（預設 false）：回應 200，說明這次操作會使哪些定義從 safe 變 unsafe 或反之，不改變任何東西、不持有鎖。預覽是呼叫當下的快照，套用時以套用當下的內容重判，兩者之間若有他人變更，結果可能不同。

**規模與限度。** 每次變更與預覽都逐一讀取、分析所有已儲存的 jar，耗時與總大小成正比，請求在重判完成前不會回應。條目變更與手動重判在整個重判期間持有白名單的互斥鎖並佔用一個資料庫交易：其他白名單變更會等待；上傳的分析不受阻，但上傳要寫入判定的最後一步會等到變更結束，並在版本已改變時以新版本重新判定。查詢與建立 run 不受阻（讀到變更提交前的內容）；對正在重判的定義設定 unsafe 執行、或刪除版本，會等到變更結束。預覽不鎖定任何東西。

回應都含 `limitations`：判定未涵蓋的範圍（反射與動態載入、白名單內類別的內部呼叫等）。

### `GET /api/v1/allowlist`

認證：Bearer（admin）

目前生效的白名單。本文：`version`（目前版本，文字）、`changedBy`、`changedAt`（這個版本的變更者與時間；首次啟動的初始內容為 `system`）、`entries[]`、`limitations`。每個條目：`kind`、`name`、`exactOnly`、`createdBy`、`createdAt`、`updatedBy`、`updatedAt`，依加入順序。

首次啟動時的初始內容是專案的預設白名單（analyzer 模組的 `DefaultAllowList`，與開發入口共用同一份），或環境變數 `ALLOWLIST_PACKAGES` 指定的內容；之後以本 API 的內容為準，啟動不會覆寫。預設白名單只在首次啟動時作為初始內容：已有白名單的資料庫不會因為新版預設內容而改變（例如 `default-2` 新增的 `class:kotlin.io.CloseableKt` 與 `class:java.io.Closeable`），既有部署如需這些條目，由管理員以本 API 新增；沒有「套用新預設」的管理操作。`ALLOWLIST_PACKAGES` 的文字格式與開發入口的 `RUNLINE_ALLOW_LIST` 相同：逗號分隔，條目為 `套件名`、`套件名:exact`（僅此套件）或 `class:完整類別名稱`。

### `GET /api/v1/allowlist/versions`

認證：Bearer（admin）

版本歷史，新的在前。查詢參數 `limit`（預設 50，上限 200）。本文 `{"versions": [...]}`，每筆含 `version`、`changedBy`、`changedAt`、`action`（`INITIAL`、`ENTRY_ADDED`、`ENTRY_CHANGED`、`ENTRY_REMOVED`）、`detail`（文字說明）、`rejudgedDefinitions`、`becameUnsafe`、`becameSafe`（這個版本重判了幾個定義、幾個變成 unsafe、幾個變成 safe）。

### `POST /api/v1/allowlist/entries`

認證：Bearer（admin）

新增條目。本文 `{"kind": "package"|"class", "name": "...", "exactOnly": false}`，`exactOnly` 選填，只用於 package。接受 `preview=true`。

| 狀態 | 意義 |
|---|---|
| 201 | 已新增並重判。`Location` 為該條目的網址，本文為變更結果（見下） |
| 200 | `preview=true`：預覽，本文為預覽結果，什麼都沒改變 |
| 400 `bad_request` | 本文不是預期的 JSON、`kind` 不是 `package` 或 `class`，或 `preview` 不是 true／false |
| 409 `entry_exists` | 已有同種類同名稱的條目（要改變它請用 PATCH）；本文多一個 `existing`（該條目） |
| 409 `entry_covered` | 現有的某個條目已涵蓋它（例如要加的類別已被其套件條目涵蓋）；本文多一個 `coveredBy`（該條目） |
| 422 `invalid_entry` | 名稱不合規或組合不合理；本文多一個 `problem`：`name`、`exact_only_on_class`、`nothing_to_change` |

變更結果：`preview`（布林）、`version`（操作之後生效的版本；預覽時為目前版本）、`entry`（變更後的條目；刪除與預覽時為 `null`）、`impact`、`redundantEntries[]`（新條目使它們變得不必要的其他條目；不阻擋，仍保留）、`limitations`。`impact`：`examinedArtifacts`、`examinedDefinitions`、`becameUnsafe`、`becameSafe`、`unreadable`，以及只含判定有變動的定義 `changes[]`：`contentHash`、`pipeline`、`className`、`from`、`to`（`SAFE` 或 `UNSAFE`）、`allowUnsafeExecution`（變動前的設定）、`unsafeExecutionRevoked`（因這次變動而收回「允許以 unsafe 執行」）。

### `GET /api/v1/allowlist/entries/{kind}/{name}`

認證：Bearer（admin）

查詢一個條目；`kind` 為 `package` 或 `class`。本文為條目。400 `bad_request`：`kind` 不合規；404 `entry_not_found`。

### `PATCH /api/v1/allowlist/entries/{kind}/{name}`

認證：Bearer（admin）

修改條目：本文 `{"name": "...", "exactOnly": true}`，至少一項。改名時與新增相同的重複與涵蓋檢查；`exactOnly` 只用於 package。接受 `preview=true`。200 回傳變更結果；400 `bad_request`；404 `entry_not_found`；409 `entry_exists`、`entry_covered`；422 `invalid_entry`（包含沒有要修改的內容 `nothing_to_change`）。

### `DELETE /api/v1/allowlist/entries/{kind}/{name}`

認證：Bearer（admin）

刪除條目並重判。接受 `preview=true`。200 回傳變更結果（`entry` 為 `null`，因為要說明影響）；400 `bad_request`；404 `entry_not_found`。

### `POST /api/v1/allowlist/recheck`

認證：Bearer（admin）

手動重判所有既有定義，使用目前的白名單與目前的分析規則（分析規則更新後用來更新既有判定）。不改變條目，也不產生新版本；遵守與條目變更相同的規則（收回新變成 unsafe 者的 unsafe 執行設定、同一交易、不影響進行中的 run）。接受 `preview=true`。200 回傳變更結果；400 `bad_request`。

## Trigger（管理員）

Trigger 將 cron 排程或 webhook 綁定到某個版本的某個 pipeline，規則見 [ADR-005](adr/ADR-005-trigger-binding.md)。名稱 1 至 100 個字元，字母、數字、`.`、`_`、`-`，以字母或數字開頭。Trigger 以其來源（`source.kind` 為 `TRIGGER`）建立 run，與手動 run 受相同檢查。

### `POST /api/v1/triggers`

認證：Bearer（admin）

建立 trigger。本文 `{"name", "kind": "cron"|"webhook", "contentHash", "pipeline", "parameters"?, "cron"?, "timeZone"?, "enabled"?}`。`cron` 是標準五欄表達式（cron trigger 必填），`timeZone` 是 IANA 時區（預設 UTC），`enabled` 預設 true；webhook trigger 不可設定 `cron` 與 `timeZone`。

201（帶 `Location`）回傳 `{"trigger": {...}, "secret": "..."}`，`secret` 只有 webhook trigger 有，且只在這個回應出現這一次。錯誤：400 `bad_request`；404 `definition_not_found`；409 `trigger_exists`；422 `invalid_parameters`（同建立 run，`problems[]`）；422 `invalid_trigger`，`problem` 指出哪一部分：`name`、`cron_required`、`cron_expression`、`time_zone`、`schedule_not_allowed`、`nothing_to_change`。

Trigger 的欄位：`name`、`kind`、`contentHash`、`pipeline`、`parameters`（管理員給的）、`effectiveParameters`（套用預設值後每個 run 實際得到的）、`enabled`、`cron`、`timeZone`、`webhookPath`、`secretConfigured`、`secretRotatedAt`、`createdBy`、`createdAt`、`updatedBy`、`updatedAt`。任何回應都不含 webhook 密鑰或其雜湊。

### `GET /api/v1/triggers`

認證：Bearer（admin）

列出 trigger。本文 `{"triggers": [...]}`。

### `GET /api/v1/triggers/{name}`

認證：Bearer（admin）

查詢一個 trigger。404 `trigger_not_found`。

### `PATCH /api/v1/triggers/{name}`

認證：Bearer（admin）

修改綁定、參數、排程、啟用狀態。本文可含 `contentHash`、`pipeline`、`parameters`、`enabled`、`cron`、`timeZone`，至少一項。不通過驗證的修改不改變任何東西。200 回傳 trigger；錯誤同建立（400、404 `trigger_not_found` 或 `definition_not_found`、422）。

### `DELETE /api/v1/triggers/{name}`

認證：Bearer（admin）

解除綁定；它的觸發紀錄一併移除。204；404 `trigger_not_found`。

### `POST /api/v1/triggers/{name}/rotate-secret`

認證：Bearer（admin）

換發 webhook 密鑰，舊密鑰立即失效。200 回傳 `{"trigger", "secret"}`（密鑰只顯示這一次）；404 `trigger_not_found`；409 `not_a_webhook`。

### `GET /api/v1/triggers/{name}/firings`

認證：Bearer（admin）

最近的觸發紀錄，新的在前。查詢參數 `limit`（預設 50，上限 200）。本文 `{"firings": [...]}`，每筆含 `firedAt`、`scheduledFor`（cron）、`deliveryId`（webhook）、`outcome`（`run_created`、`refused`、`failed`、`interrupted`、`pending`）、`reason` 與 `detail`（被拒絕時）、`runId`（其 run 被清理後為 `null`）。觸發紀錄依觸發時間清理：webhook 的紀錄預設保留 7 天（即去重視窗），cron 的預設 30 天；處於 `pending` 的紀錄不被清理。404 `trigger_not_found`。

## Webhook 入口

### `POST /api/v1/webhooks/{name}`

認證：專用標頭 `X-Runline-Webhook-Secret`（不使用 Bearer token）

外部系統以 trigger 自己的密鑰觸發 webhook trigger，不使用 API token。

| 標頭 | 意義 |
|---|---|
| `X-Runline-Webhook-Secret` | 建立 trigger 或換發密鑰時顯示的密鑰；Engine 只存其 SHA-256，以固定時間比對 |
| `X-Runline-Delivery-Id` | 呼叫端對這次投遞的識別，1 至 200 個可見 ASCII 字元，必填；同一 trigger 內在去重視窗內唯一，重送（即使同時）不會產生第二個 run。去重視窗等於 webhook 觸發紀錄的保留期限（預設 7 天，下限 24 小時）；視窗之後同一識別可再次被接受，產生新的 run |

請求本文不會被讀取，也不影響 run 的參數。

| 狀態 | 意義 |
|---|---|
| 202 `{"status": "accepted"}` | 通過驗證。不論 run 之後如何（被拒絕建立由管理員在觸發紀錄查看），以及投遞已收過（與第一次相同的回應），都是這個回應 |
| 401 `unauthorized` | 密鑰缺少或錯誤、trigger 已停用、不是 webhook、或不存在，一律同一個固定回應 |
| 400 `invalid_delivery_id` | 已通過驗證，但投遞識別缺少或格式不符 |
| 500 `internal_error` | 發生未預期的失敗；本文固定為請以相同投遞識別重送的說明，不含 `errorId`，原因寫在 log。重送同一投遞是安全的 |

## 不屬於 API 契約的端點

這些端點不要求 Bearer token，維持現狀（[ADR-012](adr/ADR-012-api-authentication.md)），也不保證穩定。

### `GET /openapi`

認證：無

Swagger UI。

### `GET /openapi/documentation.yaml`

認證：無

Swagger UI 讀取的 OpenAPI 文件。目前是範本留下的空文件，並未描述上述端點；契約以本文為準。

### `GET /openapi/oauth2-redirect.html`

認證：無

Swagger UI 附帶的頁面。

### Console 的靜態檔與入口頁

Engine 在 `/` 提供 Console（Svelte 靜態 SPA，隨 `engine.jar` 發佈，[ADR-015](adr/ADR-015-console-frontend.md)）。這些路由免認證（不含機密，資料一律經 Bearer API 取得），不保證穩定。規則：

- `GET /` 回傳入口頁；雜湊命名的資源檔在固定前綴下，可長期快取；入口頁不快取。
- `GET` 請求對應到實際存在的資源檔時回傳該檔；對應不到、不在 `/api` 與 `/openapi` 之下、且不像資源檔時，回傳入口頁（200），由前端路由處理。
- `/api/**` 與 `/openapi/**` 先於靜態檔與 fallback；`/api` 下不存在的路徑回 404。非 GET 請求不適用 fallback。
- 回應帶限制為同源的內容安全政策、禁止被嵌入框架與 `nosniff` 標頭。Engine 不啟用 CORS。
- 前端建置被略過的本機建置中，`/` 回 404，API 不受影響。

**掛載點。** 整組 Console 路由只有一個實際註冊的路由，下面的 `GET /{...}`（接住所有其他路由都不處理的 `GET`），在 `ApiDocumentationTest` 視為一組：它是除 `/api` 與 `/openapi` 之外唯一允許的路由，`/api` 下的路由仍須逐一記錄（[WI-31](work-items/WI-31-frontend-build-and-static-serving.md)）。

回應標頭（Console 的檔案與入口頁）：`Content-Security-Policy: default-src 'self'; script-src 'self'; style-src 'self'; font-src 'self'; img-src 'self' data:; connect-src 'self'; object-src 'none'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'`、`X-Content-Type-Options: nosniff`、`X-Frame-Options: DENY`、`Referrer-Policy: no-referrer`；`/assets/` 下的檔案 `Cache-Control: public, max-age=31536000, immutable`，其餘 `no-cache`。

### `GET /{...}`

認證：無

Console 的掛載點，規則見上。

Engine 沒有 HTTP 關閉端點：關閉只由終止訊號觸發（[04](04-deployment.md)、[ADR-012](adr/ADR-012-api-authentication.md)）。範本遺留的回聲 WebSocket、範例 JSON 與關閉端點已移除，對這些路徑的請求得到 404。
