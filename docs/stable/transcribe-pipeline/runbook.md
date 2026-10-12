# transcribe 上線手冊

本文回答：管理員如何把 transcribe pipeline 在 Engine 上線、第一次怎麼安全地執行、之後怎麼呼叫與查結果、降級與中斷怎麼處置、出了問題怎麼查，以及哪些事還要使用者在有 Lemonade 的環境驗證。狀態：已核可（2026-10-12）。

驗證範圍：本文的上傳、analyzer 判定、允許 unsafe、資源、綁定、trigger、webhook、整條 11 階段流程、查詢、負向案例、取消、Engine 停止與強制結束、資源等候與逾時、降級、各設定與工具缺漏的失敗訊息，都在**打包的真實 Engine**（`engineDistribution` 產物，JDK 25，PostgreSQL 17 容器）上，以真實的 ffmpeg、真實的 VAD 工具 jar 與 silero-vad 模型、真實的 SQLite（`jdbc-pool`）實際執行；Docker 覆蓋層（單獨與合用瀏覽器）也跑過完整流程。**模型服務是 `runline-transcribe` 的 `FakeOpenAiServer`（真的在 port 上監聽的 OpenAI 相容服務），不是 Lemonade**：它的轉錄文字是預先給定的、它的「核對」「摘要」「翻譯」是依格式回答的，所以本文對模型行為（遵守率、thinking、圖片理解、簡體字）與 LLM 階段耗時**沒有任何結論**，那些在第 17 節列為使用者要驗證的項目。錯誤訊息與 log 是實際輸出，不是推測。其中最後一格畫面、`TRANSCRIBE_WORK_DIR`、設定類錯誤與模型清單檢查、VAD 暫存目錄、媒體失敗的分類這五項（附錄 A 的 1、2、4、5、6）是以 devkit 的 JVM（真實 ffmpeg、真實 VAD 工具、真實 SQLite、`FakeOpenAiServer`）驗證，沒有在打包的 Engine 上重跑；參數值含 U+0000 的處理與 `jdbc-statement` 執行緒警告的修正（附錄 A 的 3、7）以真實 PostgreSQL 與真實 SQLite 檔的 Engine 測試驗證。已驗證與未驗證的完整清單見第 16 節。

> **三件事先知道。**
> 1. **一次 run 很長。** 一小時影片的 LLM 階段需要數小時（容量估算見第 12 節），上限 6 小時的影片可達數十小時，期間獨佔 Lemonade 資源。中斷不續跑，要重做就重送。第一次一律用約 1 分鐘的樣本。
> 2. **模型 id 打錯會讓 run 失敗，但時間點不同。** 資源能列出模型（啟用 `models.list`）時，`llmModel` 與已設的 `polishModel`、`verifyModel`、`translateModel` 在轉錄之後、潤飾開始前就被確認，不在清單內的 run 以 `CONFIGURATION` 失敗（訊息 `llmModel names a model that the llm resource does not list`，只指出參數名稱）。列不出時（端點未啟用、服務拒絕、清單是空的）略過檢查並在 log 記一行 `POLISH NOTE the list of models is not available; the models are not checked`，改由第一個請求的 404 發現：服務回 401、403、404 都是設定類錯誤，run 立即以 `CONFIGURATION` 失敗，不重試也不降級。`llmModel` 打錯仍是在**轉錄完成之後**才發現，長影片要等轉錄的數小時；**所以第一次執行仍應先跑短樣本，並在資源設定 `allowedModels`**（`asrModel` 打錯在 `transcription` 的第一個請求就失敗；見第 5.1 節）。
> 3. **Engine 的 `SUCCEEDED` 不代表沒有降級。** 降級的 run 的 Engine 狀態也是 `SUCCEEDED`；要看 log 的 `OUTCOME` 與資料庫 `run.result`（第 9、11 節）。

## 1. 它是什麼：一次 run 做什麼

pipeline 名稱 `transcribe`，一個薄 jar（`runline-transcribe.jar`，只用 JDK、core 與 Kotlin 標準函式庫），unsafe：它在 Engine 主機上啟動 ffmpeg、ffprobe 與 VAD 工具（[ADR-037](adr/ADR-037-unsafe-pipeline-and-intake-root.md)）。輸入是收件根目錄下的一個影片檔名；輸出全部寫進 SQLite：來源語言 SRT、繁體中文 SRT、影片摘要、來源語言代碼，以及每個階段的文字、模型對話與計數。不保存音訊與圖片。

| 階段 | 做什麼 | 外部系統 |
|---|---|---|
| 1 `cleanup` | 清除已結束行程遺留的工作目錄，建立本 run 的 | 檔案系統 |
| 2 `store` | 開啟紀錄資料庫、建立或升級結構、清除過期的 run、記錄本 run | `database` |
| 3 `intake` | 檢查收件根目錄、檔名、VAD 設定與全部參數 | 檔案系統 |
| 4 `audio` | ffprobe 檢查（有音訊、長度不超過 `maxMediaSeconds`），ffmpeg 擷取單聲道 16 kHz WAV | ffmpeg |
| 5 `vad` | VAD 工具對整份 WAV 輸出每 32 毫秒一個語音機率 | VAD 工具 |
| 6 `segmentation` | 判定 VAD-A、於最低能量點切開過長者、判定 VAD-B（字幕條目） | 無 |
| 7 `transcription` | 每個條目的音訊依序送 qwen-asr | `llm`（`audio.transcriptions`） |
| 8 `polish` | 每個 VAD-A 組以 SRT 送 gemma4 潤飾 | `llm`（`chat.completions`） |
| 9 `verify` | 每個條目連同其時間區間的畫面（最多 6 張）送 gemma4 核對，得核對文字與一句畫面觀察 | ffmpeg、`llm` |
| 10 `summary` | 先每組、再全片的階層式摘要，並判定來源語言 | `llm` |
| 11 `translate` | 每個 VAD-A 組連同全片摘要送 gemma4 翻成繁體中文 | `llm` |

Lemonade 的並行是 1，所有請求逐一送出；階段依模型分批（轉錄全部完成才開始 gemma4 階段），減少模型切換。各階段的細節（提示、回答格式、驗證與退回規則）見 [`runline-transcribe` 的 README](../../../../runline-transcribe/README.md)；設計見 [入口文件](README.md)。

## 2. 主機需求與覆蓋層

### 2.1 需求

| 項目 | 內容 |
|---|---|
| JDK | **25**（Engine 與 VAD 工具都用主機或映像的 JRE） |
| ffmpeg 與 ffprobe | 在 Engine 行程的 `PATH` 上。映像內是固定版本與雜湊的靜態建置（7.0.2）；本項在主機上用的是 9.0.2，兩者都通過 |
| VAD 工具 | `runline-vad.jar`（含 ONNX Runtime，由 `runline-transcribe` 的 `./gradlew :vad:jar` 建置）與 `silero_vad.onnx`（v5.1.2，雜湊固定）。命令 `runline-vad`（`deploy/bin/runline-vad`）以 JRE 執行 `RUNLINE_VAD_JAR`。不需要 Python |
| 收件根目錄 | 一個 Engine 帳號**可讀、不可寫**的目錄；影片放進去的人是呼叫端，不是 Engine |
| 工作目錄 | `TRANSCRIBE_WORK_DIR`，未設定時 JVM 的 `java.io.tmpdir`：可寫、可執行（不是 `noexec`；VAD 工具的暫存檔也在其下），容量見 2.3 |
| SQLite 檔 | 在 `RUNLINE_RESOURCE_ROOT` 之下；**這個目錄要備份** |
| 網路 | Engine 到 Lemonade 可達 |

### 2.2 選哪個覆蓋層

細節（檔案、版本固定、更新固定的輸入、檢查腳本的每一項）都在 [deploy/transcribe.md](../../../deploy/transcribe.md)，本文不重複，只列選擇與指令。覆蓋層是選配：不使用時 Engine 與以前完全相同。

| 情境 | Docker | systemd |
|---|---|---|
| 只跑 transcribe | `compose.yaml` + `compose.transcribe.yaml`（`transcribe` 映像，arm64 與 amd64 皆可） | 安裝 `runline-engine-transcribe.conf` 為 drop-in |
| 與 digest-rss 同一個 Engine（要 Chrome） | 依序 `compose.yaml`、`compose.browser.yaml`、`compose.transcribe.yaml`、**最後**`compose.browser-transcribe.yaml`（單一映像 `browser-transcribe`，只有 linux/amd64） | 兩個 drop-in（`browser.conf`、`transcribe.conf`）並存，檔名不同，沒有共同設定項 |

Docker 的啟動需要 `TRANSCRIBE_INTAKE_HOST_DIR`（宿主上放影片的目錄，唯讀掛載為 `/var/lib/runline/intake`）；VAD 工具 jar 放在 `TRANSCRIBE_VAD_HOST_DIR`（預設 `./transcribe-vad`）並唯讀掛載到 `/opt/runline/vad`，換 jar 不需要重建映像。宿主上的影片要對 other 可讀（檔案 `o+r`、目錄 `o+rx`），否則容器內的 uid 10001 讀不到——這種檔案在 run 以 `UNREADABLE` 失敗（本項在容器內以權限 000 的檔案驗證）。

**檢查**（上線前、每次改部署後）：在 Engine 的環境與帳號下執行 `runline-transcribe-check`（Docker：`docker compose <同一組 -f> exec engine runline-transcribe-check`）。它逐項印 `ok`、`skip` 或 `FAIL`：ffmpeg、ffprobe、非 root、收件根目錄可讀且**真的寫不進去**、工作目錄可寫與空間、VAD 模型雜湊、VAD 工具載入模型並輸出 0 至 1 的機率。本項在 `transcribe` 映像與 `browser-transcribe` 映像內都得到 `all checks passed`。

### 2.3 工作目錄與容量

每個 run 在 `TRANSCRIBE_WORK_DIR`（未設定或空白時是 JVM 的 `java.io.tmpdir`）下建立專屬目錄 `runline-transcribe-<pid>-<行程啟動時間>-<n>`，放 `audio.wav` 與 VAD 工具的暫存目錄 `vad-tmp`，run 結束、取消、逾時時刪除。實測 1934 秒的影片產生 61.8 MB 的 `audio.wav`（約 115 MB／小時，與 [04](04-deployment.md) 相符）。畫面只在記憶體（ffmpeg 的 pipe），不寫檔。可用空間 ≥ 同時進行的 run 數 × 0.115 GB × 影片小時數；因 `llm` 資源容量 1，轉錄 run 之間不會同時進行，但被強制結束後尚未被清理的遺留目錄要算進去（第 13 節）。

> **`TRANSCRIBE_WORK_DIR` 就是 pipeline 使用的目錄。** 它須是絕對路徑、存在、可寫的目錄，否則 run 在第一個階段 `cleanup` 以 `CONFIGURATION` 失敗，訊息 `TRANSCRIBE_WORK_DIR is not an existing, writable directory`，不含路徑。遺留目錄的清理（第 13 節）、取消與結束時的刪除都作用於它，所以 [04](04-deployment.md) 與 `deploy/` 的 systemd drop-in（`CacheDirectory`）、`runline-transcribe-check` 檢查的空間就是實際使用的空間。未設定時 pipeline 用 JVM 的 `java.io.tmpdir`（Linux 上是 `/tmp`，不看環境變數 `TMPDIR`），檢查腳本在這種情況同樣檢查 `/tmp`；要換位置請設 `TRANSCRIBE_WORK_DIR`。SQLite 的原生程式庫在 JVM 的 `java.io.tmpdir` 解開，不在工作目錄。

## 3. 設定（環境變數）

設定只來自環境變數（Docker 的 `.env` 與疊加檔，systemd 的 `runline.env`）。Engine 的完整清單見 [pipeline-engine 部署文件](../pipeline-engine/04-deployment.md)；下表只列與 transcribe 直接相關的。

| 變數 | 必填 | 說明 |
|---|---|---|
| `TRANSCRIBE_INTAKE_ROOT` | 是 | 收件根目錄的絕對路徑。**不是 pipeline 參數**，webhook 與手動 run 都不能改變它。缺少或不是可讀的目錄時，run 在 `intake` 失敗（`CONFIGURATION`，訊息 `TRANSCRIBE_INTAKE_ROOT is not set` 或 `… is not an existing, readable directory`，不含路徑）。Docker 疊加檔設為 `/var/lib/runline/intake` |
| `RUNLINE_VAD_MODEL` | 是 | `silero_vad.onnx` 的絕對路徑。缺少或不可讀時 run 在 `intake` 失敗（`RUNLINE_VAD_MODEL is not set` 或 `… is not an existing, readable file`）。映像內預設 `/opt/runline/models/silero_vad.onnx` |
| `RUNLINE_VAD_JAR` | 否 | 只被 `runline-vad` 包裝腳本讀：VAD 工具 jar。預設 `/opt/runline/vad/runline-vad.jar` |
| `TRANSCRIBE_VAD_COMMAND` | 否 | VAD 工具的命令；未設定用 `PATH` 上的 `runline-vad` |
| `TRANSCRIBE_WORK_DIR` | 否 | 工作目錄的上層，須是絕對路徑、存在、可寫的目錄；未設定或空白用 JVM 的 `java.io.tmpdir`。不合法時 run 在 `cleanup` 以 `CONFIGURATION` 失敗，訊息指出這個變數，不含路徑（第 2.3 節）。Docker 疊加檔未設定；systemd drop-in 設 `/var/cache/runline-transcribe` |
| `RUNLINE_RUN_TIMEOUT_SECONDS` | 否 | **不要設，或設得遠大於最長的 run**。預設不設（無限制）。逾時會中斷 run，而且不續跑 |
| `RUNLINE_RESOURCE_WAIT_TIMEOUT_SECONDS` | 否 | run 等待它宣告的資源最久多久，預設 **3600 秒**，逾時 run 失敗為 `ResourceWaitTimeout`（實測訊息：`等待資源逾時：等待 lemonade、transcribe-sqlite 超過 15 秒 仍未取得，run 未開始。`）。與 digest-rss 共用 Lemonade 時必須調整（第 14 節） |
| `RUNLINE_SHUTDOWN_GRACE_SECONDS` | 否 | 預設 30。到期時進行中的 run 被中斷；transcribe 在收到中斷後立即結束（實測 Engine 在 1 秒內停止） |
| `RUNLINE_WEBHOOK_MAX_BODY_BYTES` | 否 | webhook 本文大小上限，預設 16384。超過回 400 `invalid_body` |
| `RUNLINE_WEBHOOK_DEDUP_WINDOW_SECONDS` | 否 | delivery 識別碼的記憶期限，預設 7 天（下限 24 小時）；期限之後同一識別碼再次被接受，產生新的 run |
| `RUNLINE_RUN_RETENTION_SECONDS` | 否 | Engine 的 run 與其 log 的保留，預設 30 天。**這與 SQLite 內紀錄的保留（參數 `retentionDays`，預設 90 天）是兩回事** |
| `RUNLINE_MAX_CONCURRENT_RUNS` | 是 | 同時執行的 run 數。transcribe 的 run 因 `llm` 資源容量 1 一次只有一個 |
| `RUNLINE_WORKSPACE_MAX_BYTES`、`RUNLINE_FAILED_RUN_RETENTION_SECONDS` | 是 | Engine 為每個 run 建立私有目錄；transcribe 不寫入它（實測目錄保持空），任何合理值都可以 |
| `PATH` | — | 必須含 ffmpeg 與 ffprobe；缺少時 run 在 `audio` 失敗（`ffprobe could not be started; it must be installed and found on PATH`） |

## 4. 上傳與允許 unsafe

| 步驟 | 呼叫 | 預期 |
|---|---|---|
| 上傳 | `POST /api/v1/artifacts`，本文是 jar 的位元組，`Content-Type: application/octet-stream`，admin token | 201，記下 `contentHash`。同一個擁有者重複上傳相同內容回 200 |
| 檢視判定 | 回應的 `pipelines[0]` | `name` 為 `transcribe`、`className` 為 `transcribe.TranscribePipeline`、`verdict` 為 `UNSAFE`、`allowUnsafeExecution` 為 `false` |
| 允許 | `PUT /api/v1/definitions/{hash}/transcribe/unsafe-execution`，本文 `{"allow": true}` | 200 |

允許之前建立 run（含 webhook 觸發）被拒絕：手動 run 回 409 `unsafe_not_allowed`；webhook 仍回 202，但觸發紀錄的 `outcome` 為 `refused`、`reason` 為 `unsafe_not_allowed`（實測）。**允許是逐版本的，新版本不繼承。**

**analyzer 的回報**（實測 23 項，沒有任何第三方函式庫）：

| 類別 | 項目 |
|---|---|
| `UNRESTRICTED_ACCESS` | `PROCESSES`（1 項：pipeline 宣告行程不設限）。網路**不是**不設限：pipeline 不直接連網，模型走 Engine 資源 |
| `IO_SENSITIVE_MEMBER` | `java.lang.ProcessBuilder.start()`（啟動 ffmpeg、ffprobe、VAD 工具） |
| `NOT_ALLOW_LISTED` | 21 個 JDK 類別：`java.io.ByteArrayOutputStream`、`java.io.File`、`java.io.IOException`、`java.io.InputStream`、`java.nio.ByteBuffer`、`java.nio.ByteOrder`、`java.nio.channels.FileChannel`、`java.nio.file.*` 中的 `DirectoryStream`、`FileVisitResult`、`FileVisitor`、`Files`、`InvalidPathException`、`LinkOption`、`NoSuchFileException`、`NotDirectoryException`、`OpenOption`、`Path`、`SimpleFileVisitor`、`StandardOpenOption`，以及 `java.nio.file.attribute.BasicFileAttributes`、`FileAttribute` |

原因全是檔案與行程的 JDK 類別；新版本若多出清單之外的原因（尤其是第三方函式庫的類別），先問開發者，不要直接允許。白名單不需要新增條目，也不應為了它放行 `java.nio.file` 之類的套件（行程不設限不是白名單能改變的）。

## 5. 資源與綁定

pipeline 宣告兩個角色（程式只認角色名稱）；管理員建立資源，再把角色綁定到資源。**兩個角色在 Engine 上都必須綁定**，否則建立 run 回 409 `resources_unavailable`（`problems[]` 的 `problem` 為 `unbound`，實測）。

| 角色 | 型別 | 該選什麼 |
|---|---|---|
| `llm` | `openai-compatible` | 指向 Lemonade；容量 1 |
| `database` | `jdbc-pool` | `sqlite` 種類，指向資源根目錄下的檔案；容量 1 |

### 5.1 `llm`：openai-compatible 資源

`POST /api/v1/resources`，本文的重點：

| 欄位 | 值 |
|---|---|
| `name`、`type`、`capacity` | 例如 `lemonade`、`openai-compatible`、`1` |
| `settings.baseUrl` | Lemonade 的 OpenAI 相容根位址（例如 `http://lemonade.example:8000/api/v1`） |
| `settings.endpoints` | **必須含 `audio.transcriptions`、`chat.completions`**；`models.list` 供資源檢查與潤飾開始前的模型清單檢查（沒有它，檢查被略過）。`audio.transcriptions` 預設**不啟用**，漏掉時 run 在 `transcription` 以 `CONFIGURATION` 失敗（`the llm resource cannot be used: ENDPOINT_NOT_ENABLED`，實測） |
| `settings.allowedModels` | **強烈建議**，含 `asrModel` 與 `llmModel`（以及用到的各階段覆寫）。不在清單的模型在該階段的第一個請求以 `MODEL_NOT_ALLOWED` 讓 run 失敗（實測：`asrModel` 打錯在 `transcription`，`llmModel` 打錯在 `polish`）；沒有 `allowedModels` 時，`llmModel` 打錯由 `models.list` 的清單檢查在潤飾開始前發現（`models.list` 已列在上一列，資源建議啟用），清單取不到時由第一個請求的 404 讓 run 以 `CONFIGURATION` 失敗（本文開頭第 2 點）；有 `allowedModels` 時資源先於服務拒絕 |
| `settings.maxRequestBytes` | 預設 32 MiB。核對請求含最多 6 張縮圖，轉錄請求含一段最長 25 秒的 WAV（800 KB）；32 MiB 綽綽有餘。調得太小時核對請求被擋，pipeline 會逐步縮小縮圖邊長，仍過大則該條目降級 |
| `settings.defaults` | 取樣參數（`top_p`、`top_k`、`max_tokens` 等）**由這裡提供，pipeline 只送 `model` 與 `temperature`**；thinking 開時推理與答案共用 `max_tokens`，太小會截斷答案。正式值待第 17 節驗證 |
| `settings.timeouts` | 預設的 `firstByteMs`（15 分鐘）、`idleMs`（5 分鐘）夠用；thinking 開的非串流長生成是否撐得過未驗證 |
| `secretAlias` | Lemonade 要金鑰時放金鑰庫並以別名引用（見 [deploy/README.md](../../../deploy/README.md)）；不要金鑰就省略 |

建好後 `POST /api/v1/resources/{name}/check`，預期 `"ok": true`。**容量保持 1，不要調高。**

### 5.2 `database`：SQLite 資源

`POST /api/v1/resources`，`type` 為 `jdbc-pool`、`capacity` 為 1、`settings` 為 `{"kind": "sqlite", "path": "transcribe.db"}`。檔案是 `$RUNLINE_RESOURCE_ROOT/transcribe.db`，第一次需要時建立，目錄須存在且可寫（SQLite 在旁邊建 `-wal`、`-shm`）。結構由 pipeline 在每個 run 的 `store` 階段建立或升級（前向、冪等、記錄版本），不需預先建表。同時間只有一個寫入者；資料庫版本比 pipeline 新、或有不屬於本 pipeline 的表時，run 在 `store` 失敗並說明版本，不寫入任何東西。

### 5.3 綁定

`PUT /api/v1/definitions/{hash}/transcribe/resource-bindings/{role}`，本文 `{"resource": "<資源名稱>"}`，對 `llm` 與 `database` 各一次；或在 Console 的 pipeline 詳情頁選擇。`GET` 同一路徑檢視：兩個角色的 `problem` 應為 `null`、`isDefault` 為 `false`。綁定以「上傳者加 pipeline 名稱」為單位，同一位上傳者的新版本自動沿用。

## 6. webhook trigger 與參數

### 6.1 參數

只有 `file` 由呼叫端（webhook 本文）提供；其餘固定在 trigger，或手動 run 時填寫。值都是字串。完整的 32 個參數與預設值以 [`runline-transcribe` 的 README](../../../../runline-transcribe/README.md)「參數」為準；日常要決定的是：

| 參數 | 預設 | 說明 |
|---|---|---|
| `file` | 必填 | 收件根目錄下的相對路徑。**呼叫端提供** |
| `asrModel` | 必填 | 轉錄模型 id（qwen-asr）。以 Lemonade 的 `GET /models` 為準 |
| `llmModel` | 必填 | 潤飾、核對、摘要、翻譯用的模型 id（gemma4） |
| `maxMediaSeconds` | `21600`（6 小時） | 影片長度上限（秒）；長度恰等於上限者接受，超過者在 `audio` 階段被拒絕。**這是控制單次 run 時間的主要旋鈕**（第 14 節） |
| `retentionDays` | `90` | SQLite 內紀錄的保留天數，每個 run 開始時清除過期者 |
| `asrLanguage` | 空（自動） | 轉錄的語言提示，如 `en`、`zh-TW`；不合法在 `intake` 被拒絕 |
| `llmThinking` | `off` | gemma4 各階段的 thinking；`polishThinking`、`verifyThinking`、`translateThinking` 覆寫（對真實 Lemonade 是否生效未驗證） |
| `llmTemperature` | `0.3` | gemma4 各階段取樣溫度；各階段另有覆寫 |
| `modelRetries`、`modelRetryBackoffSeconds` | `2`、`5` | 單次模型呼叫失敗後的重試次數與固定退避 |
| `maxFramesPerRequest`、`frameMaxEdgePixels` | `6`、`512` | 核對請求的圖片上限與縮圖長邊 |
| `vadA*`、`vadB*`、`maxSegmentSeconds`、`refineThresholdSeconds`、`cutSearchWindowSeconds`、`minPieceSeconds` | 見 README | VAD 與分段；組合有限制（B 的最小靜音須短於 A 等），不合法在 `intake` 以訊息指出參數名稱 |

參數不合法時 run 以 `CONFIGURATION` 失敗，訊息指出參數名稱與規則，不回顯值（實測：`vadAThreshold must be a number above 0 up to 1`、`maxMediaSeconds must be a positive whole number of seconds`、`asrLanguage must be a language code such as en or zh-TW, or empty`）。缺少必填參數（`asrModel`、`llmModel`）時 Engine 在建立 run 或 trigger 就回 422 `invalid_parameters`，`problems[]` 指出名稱。參數值含 U+0000 時 Engine 回 400（第 7 節）。

### 6.2 建立 webhook trigger

`POST /api/v1/triggers`，admin token（Console 的 trigger 表單也可設定，在每個宣告的參數旁勾選「由呼叫端提供」）：

| 欄位 | 值 |
|---|---|
| `name` | 例如 `transcribe-intake`（字母、數字、`.`、`_`、`-`） |
| `kind` | `webhook` |
| `contentHash`、`pipeline` | 上傳的版本與 `transcribe` |
| `parameters` | 固定的參數：至少 `asrModel`、`llmModel`；可加 `maxMediaSeconds` 等 |
| `callerParameters` | `["file"]` |

回應 201，**`secret` 只在這個回應出現一次，立即保存**（遺失時 `POST /api/v1/triggers/{name}/rotate-secret` 換發，舊密鑰立即失效）。`webhookPath` 是 `/api/v1/webhooks/{name}`。檢查規則（[ADR-036](adr/ADR-036-webhook-caller-parameters.md)，實測）：

| 情況 | 回應 |
|---|---|
| `callerParameters` 的名稱不是 pipeline 宣告的參數 | 422 `invalid_parameters`，`problem` 為 `undeclared` |
| 必填而沒有預設值的參數（`file`、`asrModel`、`llmModel`）既不在 `parameters` 也不在 `callerParameters` | 422，`problem` 為 `missing`（訊息：`缺少必填參數 file`） |
| 一個參數同時是固定值與呼叫端提供 | 422，`problem` 為 `fixed_and_caller` |

trigger 綁定在建立時指定的版本上，**不會**隨新版本移動；升級新版本時要 `PATCH /api/v1/triggers/{name}` 帶新的 `contentHash`（第 13 節）。一個 trigger 服務任意多個檔案；要用不同的固定參數（例如較小的 `maxMediaSeconds`）就建立第二個 trigger。

## 7. webhook 呼叫格式

```
curl -X POST $ENGINE/api/v1/webhooks/transcribe-intake \
  -H "X-Runline-Webhook-Secret: $SECRET" \
  -H "X-Runline-Delivery-Id: video-2026-10-12-0001" \
  -H "Content-Type: application/json" \
  -d '{"file": "lectures/week3.mp4"}'
```

| 項目 | 規則 |
|---|---|
| 密鑰 | 標頭 `X-Runline-Webhook-Secret`（不使用 Bearer token） |
| delivery 識別碼 | 標頭 `X-Runline-Delivery-Id`，1 至 200 個可見 ASCII 字元，**必填**；呼叫端為每個影片產生一個唯一值 |
| 本文 | JSON 物件，只有一個鍵 `file`，值是文字，**收件根目錄下的相對路徑**（可含子目錄與空白、中文，不可含 U+0000）。大小上限預設 16384 位元組 |
| 檔案 | 呼叫時必須已完整寫入（呼叫端負責）。呼叫端提供的值是不可信輸入，不得放機密；它會出現在 run 的參數與 SQLite 的 `run.file` |

回應（實測）：

| 回應 | 意義與呼叫端的處置 |
|---|---|
| 202 `{"status": "accepted"}` | 通過驗證。**不代表 run 已建立或會成功**：run 被拒絕建立（例如未允許 unsafe、資源未綁定）時仍是 202，原因在觸發紀錄（`GET /api/v1/triggers/{name}/firings`：`outcome` 為 `run_created` 或 `refused`，`reason`，`runId`）。檔名不合法也是 202，run 隨後失敗（第 15.2 節） |
| 202（重複的識別碼） | 同一識別碼在去重視窗內（預設 7 天）只有第一次生效，之後回相同的 202，本文被忽略；實測三次重送（含同時兩個）只產生一個 run |
| 401 `unauthorized` | 密鑰缺少、錯誤、trigger 已停用、不是 webhook 或不存在，一律同一個固定回應 |
| 400 `invalid_delivery_id` | 通過驗證，但識別碼缺少或格式不符 |
| 400 `invalid_body` | 本文不是 JSON 物件（`not_a_json_object`）或超過上限（`too_large`） |
| 400 `invalid_caller_parameters` | 清單外的鍵（`undeclared`）、缺少 `file`（`missing`）、值不是文字（`not_text`）、值含 U+0000（`contains_nul`）；`problems[]` 只指出名稱，不回值 |
| 500 `internal_error` | 未預期的失敗；以相同識別碼重送是安全的 |

兩個 400（`invalid_body`、`invalid_caller_parameters`）**不消耗識別碼，也不寫觸發紀錄**：呼叫端修正本文後可用同一個識別碼重送並成功（實測：以 `bad-1` 送非物件本文得 400，再以同一識別碼送正確本文得 202 並建立 run）。401 與 400 不留任何紀錄，只有 Engine 的 log 與指標 `runline.triggers.webhook.rejected`。

## 8. 第一次執行的順序

前一步不通過，不做下一步。每一步的失敗訊息與處置在第 15 節。

1. **主機檢查。** 部署覆蓋層，`runline-transcribe-check` 得到 `all checks passed`。
2. **上傳、允許、資源、綁定。** 第 4、5 節；兩個資源的 `check` 為 `"ok": true`；綁定的 `problem` 為 `null`。資源設定 `allowedModels`。
3. **放一個約 1 分鐘、有人聲的影片**到收件目錄（用 `ffmpeg` 剪一段真實素材即可）。
4. **手動 run**：`POST /api/v1/runs`，本文含 `contentHash`、`pipeline`、`parameters`（`file`、`asrModel`、`llmModel`）。預期 `SUCCEEDED`，log 的最後兩行是 `RUN SUMMARY …` 與 `OUTCOME success`。`OUTCOME success-degraded` 或 `failed` 時停下，依第 10、13 節處理；降級數很高通常是模型 id 或請求格式不對。
5. **用第 9 節的查詢取回結果**：兩份 SRT 的條目數相同、時間相同；摘要與來源語言非空。
6. **完成第 17 節對真實 Lemonade 的驗證**（至少第 1 至 5 項）。
7. **建立 webhook trigger**（第 6.2 節），以 webhook 重做第 4 與第 5 步（同一個樣本，新的識別碼）。再做一次負向檢查：送不存在的檔名，run 以 `NOT_FOUND` 失敗且原因明確。
8. **5 至 10 分鐘的影片**，確認每階段耗時與降級數合理。
9. **長影片前先限制**：trigger 的 `maxMediaSeconds` 設成你願意等的上限（第 14 節），再放長影片。第一次長影片時**不要同時**讓 digest-rss 的 cron 觸發（第 14 節）。

## 9. 查詢結果

結果在 SQLite 檔（`$RUNLINE_RESOURCE_ROOT/transcribe.db`），唯讀查詢即可，Engine 運作時可同時讀（WAL）；不要用工具寫入它。Docker 部署的檔案在 `resources` volume 內，映像沒有 `sqlite3`：把檔案複製出來查（`docker compose cp engine:/var/lib/runline/resources/transcribe.db <宿主目錄的絕對路徑>`，實測可行；`-wal` 在 run 都結束後為空，不存在時不複製）。Docker 的複製出來的檔案是某一時間點的快照。

| 問題 | 查詢（描述） |
|---|---|
| 最近的 run 與結果 | 表 `run`：`id`、`file`、`started_at`、`finished_at`、`result`（`success`、`success-degraded`、`failed`、`stopped`；**未結束或被強制結束的 run 為 NULL**）、`result_note`（失敗的分類）、`degraded`。依 `id` 倒序 |
| 某個 run 的參數 | 表 `run_parameter`（`name`、`value`），依 `run_id`；不含機密 |
| 各階段計數與降級 | 表 `stage`，依 `run_id`、`position` 排序：`name`、`status`（`done`／`failed`）、`input_count`、`output_count`、`failed_count`、`degraded_count`、`failure_reason`。**只有 `segmentation`（6）至 `translate`（11）寫入 `stage`**；`cleanup`、`store`、`intake`、`audio`、`vad` 的計數只在 log |
| 兩份 SRT | 表 `result`（依 `run_id`）：`source_srt`、`chinese_srt` 是完整的 SRT 文字，以 `sqlite3 -noheader <db> "select source_srt from result where run_id=…"` 導出到檔案即可播放（實測：兩份都能被 ffprobe 解析為 12 個字幕封包，條目數與時間相同） |
| 摘要與來源語言 | 表 `result`：`summary`、`source_language`（模型判定的語言代碼，原樣）；**NULL 表示摘要缺少（降級 1）** |
| 哪些組或條目降級 | 表 `exchange`，`outcome = 'fallback'`（退回上一階段文字）或 `'failed'`（讓 run 失敗的呼叫），依 `run_id`；`stage`、`target`（`group`／`entry`／`run`）、`target_seq` 指出是哪一個 |
| 模型呼叫次數與結果 | 表 `exchange` 依 `run_id` 對 `stage`、`outcome` 分組計數；`outcome` 為 `success`、`retry`（後面還有重試）、`fallback`、`failed`；`duration_ms`、`model`（實際回報）、`prompt`、`response`（原文） |
| 某個條目各階段的文字 | 表 `entry_text`（`entry`、`stage` 為 `transcribe`／`polish`／`verify`／`translate`、`text`、核對的 `observation`）連 `segment_b`（`seq` 對 `entry`，起訖秒 `start_seconds`、`end_seconds`、所屬 `group_seq`） |
| 核對用了哪幾秒的畫面 | 表 `entry_frame`（只有時間點，不存圖片） |
| 對回 Engine 的 run | pipeline 的執行環境不提供 Engine 的 run 識別，所以以 `started_at` 與 `file` 比對 Engine 的 run 的 `startedAt` 與 `parameters.file` |

SQLite 裡的 `run.result` 與 Engine 的 `state` 的對應（實測）：

| 情況 | Engine `state` | `run.result` | log 的最後一行 |
|---|---|---|---|
| 全部完成，沒有降級 | `SUCCEEDED` | `success` | `OUTCOME success` |
| 完成，有降級 | `SUCCEEDED` | `success-degraded` | `OUTCOME success-degraded degraded=N (…)` |
| 任一階段失敗 | `FAILED` | `failed`（`result_note` 為分類） | `OUTCOME failed: <分類>` |
| 取消 | `CANCELLED` | `stopped` | `OUTCOME stopped` |
| Engine 停止時進行中 | `INTERRUPTED` | `stopped`（pipeline 收到中斷時盡力寫入） | `OUTCOME stopped` |
| Engine 被強制結束（SIGKILL、斷電） | 重啟後 `INTERRUPTED` | **NULL**，`finished_at` 也是 NULL | 無 |

## 10. 降級的意義與退回規則

**降級**：某一個單位的 LLM 處理在重試用盡（或回答不符契約）後，該單位採用**上一階段的文字**，run 仍然完成，降級數加一。字幕的條目數與時間永遠不變（[ADR-038](adr/ADR-038-segment-model-and-stage-invariants.md)），降級只影響文字的品質。降級總數等於各階段降級數之和，寫在 log 的 `OUTCOME`、`RUN SUMMARY`、`run.degraded`，各階段的在 `stage.degraded_count`。**降級數不是 0 的 run 是可用的，但某些文字是未經該階段處理的。**

| 階段 | 降級的單位 | 退回到 | 例外（不降級，run 失敗） |
|---|---|---|---|
| 轉錄 | **無降級** | — | 某個條目重試用盡：整個 run 以 `TRANSCRIPTION_FAILED` 失敗（沒有文字無法繼續）；已完成的條目保留 |
| 潤飾 | 一個 VAD-A 組 | 該組各條目的轉錄文字 | 資源組態問題（`CONFIGURATION`）、模型身分不符（`MODEL_IDENTITY`） |
| 核對 | 一個條目 | 該條目的潤飾文字，沒有畫面觀察；畫面取不出來時不送請求直接降級 | 同上 |
| 摘要 | 全片摘要缺少算 1 | `result.summary` 與 `source_language` 為 NULL；之後不再送任何摘要請求 | 同上 |
| 翻譯 | 一個 VAD-A 組 | 該組各條目的核對後文字（也就是未翻譯的來源語言文字） | 同上 |

**什麼算「回答不符契約」而重試**：潤飾與翻譯的回答條目數、順序、序號、時間行與送出的不同、有條目為空、不是 SRT（`NOT_SRT`、`COUNT_CHANGED`、`NUMBER_CHANGED`、`TIME_CHANGED`、`EMPTY_TEXT`）；翻譯的回答含簡體字（`SIMPLIFIED_CHARACTERS`）；核對的回答不是恰好 `TEXT:`、`OBSERVATION:` 兩行；摘要缺標記、超過長度、語言代碼不合。連線錯誤、逾時、429、5xx、其他非 2xx 也一樣重試。原因的名稱出現在 log。**來源語言已是繁體中文**（代碼 `zh-TW`、`zh-HK`、`zh-MO`、`zh-Hant`）的組不送翻譯請求，直接採用核對後文字，**不算降級**。

怎麼判讀：

| 看到 | 意義 | 處置 |
|---|---|---|
| 降級數是 0 | 全部由模型處理 | — |
| 少量降級（約低於組數的 10%） | 模型偶爾違反格式 | 可用；要改善調 `llmTemperature`、`modelRetries` |
| 某一階段全部降級，且每次都是同一個原因 | 模型不照格式回答，不是模型偶發 | `NOT_SRT`＝模型不照格式回答；先修設定（模型、`llmThinking`、提示），重送。模型 id 不存在（404）、金鑰被拒（401、403）不會降級，是 `CONFIGURATION` 失敗（第 15.2 節） |
| 核對降級，原因 `no pictures: TOOL_FAILED` | 影片沒有影像，或取樣時間超過影片長度（落在影片長度內但晚於最後一格畫面的時間點，取最後一秒的畫面，不會降級） | 該條目退回潤飾文字；影片沒有影像時每個條目都如此 |

## 11. log 的讀法

Engine 的 `GET /api/v1/runs/{runId}/log?limit=2000`（需要時用 `after` 分頁；先到先寫，進行中也可讀）。每個階段有固定格式：

```
STAGE 7/11 transcription: send each segment, one after the other, to the recognizer
RESULT 7/11 transcription: segments transcribed; entries=389 speech=389 noSpeech=0 retries=0 modelNotReported=389; 336 ms
```

| 行的開頭 | 意義 |
|---|---|
| `STAGE n/11 名稱` | 階段開始 |
| `RESULT n/11 名稱: …; 計數; N ms` | 階段成功結束，附計數與耗時。各階段的計數：`audio` 的 `seconds`、`streams`；`vad` 的 `windows`（每視窗 32 ms）；`segmentation` 的 `a`、`b`、`keptFromA`、`noLowEnergyCuts`；`transcription` 的 `entries`、`speech`、`noSpeech`、`retries`；`polish`／`verify`／`translate` 的 `groups`／`entries`、`sent`、`degraded`、`retries`；`verify` 另有 `shrinks`、`frames`；`summary` 的 `groups`、`calls`、`batches` |
| `FAILED n/11 名稱: 例外型別: 訊息` | 階段失敗，run 失敗。訊息只含固定用語，不含路徑與機密 |
| `STOPPED n/11 名稱` | 階段被取消或逾時打斷（不是失敗） |
| `TRANSCRIBE\|POLISH\|VERIFY\|SUMMARY\|TRANSLATE RETRY …` | 單次嘗試失敗，附原因，稍後重試 |
| `… DEGRADED … kept …` | 一個單位重試用盡而降級，附最後一次的原因 |
| `… NOTE …` | 提示，例如 `temperature is locked by the resource`（管理員鎖定了 `temperature`，pipeline 改為不送，由資源的 `defaults` 決定） |
| `VERIFY SHRINK …` | 請求過大，縮小縮圖邊長重送（不算模型嘗試） |
| `… PROGRESS 25/389` | 每完成 25 個單位一行 |
| `STORE WARN …` | 結束的紀錄寫不進去；不改變 run 的結果 |
| `RUN SUMMARY entries=… degraded=N (polish=… verify=… summary=… translate=…) modelCalls=… elapsed=… ms` | 所有階段完成後一行（失敗與停止的 run 沒有）。`modelCalls` 是對 `llm` 資源送出的請求總數，含每次重試 |
| `OUTCOME …` | 最後一行（第 9 節的表） |

`OUTCOME failed:` 後的分類：`CONFIGURATION`（設定或參數）、`INVALID_NAME`、`NOT_FOUND`、`UNREADABLE`（檔名）、`NO_SPEECH`、`TRANSCRIPTION_FAILED`、`MODEL_IDENTITY`、`VAD_TOOL_MISSING`、`VAD_TOOL_FAILED`、`VAD_TIMEOUT`、`VAD_BAD_OUTPUT`；媒體失敗的分類帶原因：`MEDIA_NO_AUDIO`（無音訊串流）、`MEDIA_CORRUPT`（損毀）、`MEDIA_TOO_LONG`（超過 `maxMediaSeconds`）、`MEDIA_TOOL_MISSING`（ffmpeg 或 ffprobe 不在 `PATH`）、`MEDIA_TOOL_FAILED`（工具失敗）、`MEDIA_TIMEOUT`（逾時），訊息在前一行 `FAILED` 裡。Engine 的 run 的 `failure.type` 是例外類別、`failure.message` 是同一句訊息。

## 12. 容量與耗時

下表是本項的實測，**只含非 LLM 階段**；LLM 階段的耗時沒有參考價值（模型服務是 `FakeOpenAiServer`，一次呼叫約 1 至 10 毫秒），一律待量測。影片是 macOS 的 `say` 合成的語音（12 句，句間 0.8 至 1.5 秒停頓）接上合成畫面（320×240、10 fps），短樣本 64 秒、長樣本由同一段語音串接 31 次共 1934 秒（32.2 分鐘）。

**macOS arm64（Apple M2，8 核，Engine 以主機行程執行，ffmpeg 9.0.2）：**

| 階段 | 短（64 s） | 長（1934 s） | 說明 |
|---|---|---|---|
| `audio` | 82 ms | 541 ms | ffprobe 加擷取 WAV；約 3600 倍即時 |
| `vad` | 1541 ms | 7907 ms | 含 JVM 啟動與載入模型（約 1.3 s 固定成本）；約 245 倍即時；60377 個視窗 |
| `segmentation` | 3 ms | 18 ms | VAD-A 342、VAD-B 389 |
| `verify` 中的畫面擷取 | ≈ 2.3 s | ≈ 76.6 s | `verify` 階段耗時 80.4 s 扣掉模型呼叫 3.8 s；1692 張，每張約 45 ms（每張一個 ffmpeg 行程）。**只對這個 320×240 的合成畫面成立**；真實 1080p 影片的單張成本待量測 |
| 其餘非 LLM（`cleanup`、`store`、`intake`） | ≤ 20 ms | ≤ 10 ms | |
| 工作目錄 | — | 61.8 MB | 約 115 MB／小時 |

**Docker（Colima 的 linux/arm64 VM，2 核 4 GiB，`transcribe` 映像，ffmpeg 7.0.2）**：長樣本 `audio` 642 ms、`vad` 6746 ms、`segmentation` 34 ms、`verify`（畫面擷取為主）67.8 s，整個 run 77.7 s。合用瀏覽器的映像（linux/amd64，在 Apple 晶片上以 Rosetta 模擬）慢約 3 至 4 倍：`vad` 22.0 s、`verify` 311 s，整個 run 340 s；**這是模擬的代價，不代表原生 amd64**。

**每個 run 的模型呼叫次數**（兩個樣本的實測，與計數一致）：

| 樣本 | 條目 N | VAD-A 組 A | 轉錄 | 潤飾 | 核對 | 摘要 | 翻譯 | 合計 | 降級 |
|---|---|---|---|---|---|---|---|---|---|
| 短 64 s | 12 | 12 | 12 | 12 | 12 | 13 | 12 | 61 | 0 |
| 長 1934 s | 389 | 342 | 389 | 342 | 389 | 343 | 342 | 1805 | 0 |

呼叫次數 ＝ 2N（轉錄、核對）＋ 3A（潤飾、摘要的組摘要、翻譯）＋ 1（全片摘要）；摘要的組數多到輸入超過預算（12000 字元）時另有分批的呼叫（本樣本沒有觸發）。

**與 [07](07-nfr-risks.md) 容量估算的對照：**

- 07 假設一小時影片、語音佔 60%、VAD-B 平均 3 至 5 秒，約 400 至 700 段。本樣本每小時約 725 個條目，平均 3.85 秒（1.1 至 5.4 秒），落在同一個量級。
- 07 的呼叫次數公式是 2N＋3A＋1，A 與 N 的比例取決於停頓長短。**本樣本 A／N ＝ 0.88**：語句之間的停頓都長於 A 的最小靜音（500 ms）時，每一組只有一個條目，組數接近 N；連續說話、組內有許多條目時 A 遠小於 N。N ＝ 600 時約 1,400 至 3,000 次。需要依實際素材的停頓長度判斷。
- 每次呼叫 5 至 20 秒的假設**待量測**（需要真實 Lemonade，第 17 節）；因此「一小時影片需約 3 至 12 小時」「接近上限的影片約 18 至 72 小時」維持待量測。
- 磁碟：WAV 約 115 MB／小時與 07 相符。

## 13. 取消與中斷後的處置

**取消**：`POST /api/v1/runs/{runId}/cancel`。實測行為（主機與容器相同）：

| 取消時 pipeline 在 | 結果 |
|---|---|
| `vad`（VAD 工具行程執行中） | 回 202；工具行程（`java -jar runline-vad.jar`）立即終止；log `STOPPED 5/11 vad`、`OUTCOME stopped`；工作目錄刪除；沒有遺留的 ffmpeg、ffprobe、VAD 行程；Engine 狀態 `CANCELLED`，`run.result` 為 `stopped` |
| `polish` | 同上；已完成的組保留（實測 117 組的潤飾文字在資料庫），轉錄全部保留 |
| `verify`（含 ffmpeg 擷取畫面） | 同上；已核對的條目保留（實測取消時 50 條目的核對文字與 218 個畫面時間點保留），`result.source_srt` 與 `chinese_srt` **不寫**（兩份 SRT 在階段結束時才寫入） |

**Engine 停止（SIGTERM，例如 `systemctl restart`、`docker compose stop`）**：進行中的 run 立即被中斷，Engine 狀態 `INTERRUPTED`，`run.result` 為 `stopped`（pipeline 盡力寫入），工作目錄刪除；Engine 在 1 秒內停止（實測）。`RUNLINE_SHUTDOWN_GRACE_SECONDS` 到期才強制。

**Engine 被強制結束（SIGKILL、OOM、斷電）**：pipeline 沒有機會清理。實測：工作目錄留在 `java.io.tmpdir`；`run.result` 與 `finished_at` 為 NULL；重啟後 Engine 把該 run 標為 `INTERRUPTED`；**下一個 transcribe run 的 `cleanup` 階段移除遺留目錄**（log `work directory ready; removed=1`，目錄名稱帶有擁有者的 pid 與行程啟動時間，擁有者已不存在才會被移除）。外部行程在 Engine 被強制結束時是否繼續執行**未驗證**：本項的一次 SIGKILL 發生在核對階段，事後沒有看到遺留的 ffmpeg 行程，但沒有特別在 VAD 工具行程執行當下測試。

**不續跑、不自動重跑**（[01](01-requirements.md) 假設 7）。要重做：用**新的 delivery 識別碼**重送 webhook（同一識別碼在 7 天內會被當作重複而忽略）。重做是一個全新的 run，產生新的 `run.id`；被中斷的 run 的部分資料留在資料庫，依 `retentionDays` 被清除，不影響新的 run。查看部分資料見第 9 節（`entry_text` 依 `stage` 看做到哪裡）。

**升級 pipeline 新版本**：上傳新 jar、**重新允許 unsafe**（不繼承）、綁定自動沿用、**把 webhook trigger 的 `contentHash` 改到新版本**（`PATCH /api/v1/triggers/{name}`，trigger 不會隨版本移動）；進行中的 run 不受影響。資料庫結構只增不改，新版本啟動時自動升級；資料庫版本比 pipeline 新時 run 在 `store` 失敗。

**備份**：`$RUNLINE_RESOURCE_ROOT` 之下的 `transcribe.db`（連同 `-wal`，或用 SQLite 的備份指令）。

## 14. 與 digest-rss 共用 Lemonade 的排程建議

**機制**（實測）：`llm` 資源容量 1。一個 run 在開始時取得它宣告的全部資源（`llm` 與 `database`）；後來的 run 的狀態是 `QUEUED` 然後 `WAITING_FOR_RESOURCES`，依先進先出，等第一個結束才開始。實測：transcribe 的長 run 進行時，第二個 transcribe run 在 `WAITING_FOR_RESOURCES`，第一個取消後立即開始並完成；資源的 `holders` 與 `waiters` 可在 `GET /api/v1/resources/{name}` 看到。

**三個取捨：**

1. **等待逾時。** `RUNLINE_RESOURCE_WAIT_TIMEOUT_SECONDS` 預設 3600 秒；排在長 transcribe run 後面的 digest-rss run（或第二個 transcribe run）等超過它就以 `ResourceWaitTimeout` 失敗，**run 未開始，也不會自動重試**（實測以 15 秒驗證訊息）。transcribe 一次 run 可達數小時至數十小時，所以：(a) 把該值設成大於你容許的最長等待（例如 digest-rss 每天一次，可設到 86400 以上）；或 (b) 讓 digest-rss 的 cron 避開 transcribe 的時段。**調高它會同時放寬所有 pipeline 的等待上限**，這是 Engine 的全域設定。
2. **digest-rss 的 cron 間隔須大於一個 run 的時間**，否則下一個到點的 run 在後面排隊。transcribe 的長 run 會把整段時間的 digest-rss 觸發都擋在後面，而擋住的 run 在逾時後失敗，下一次 cron 才再試。建議：長影片只在 digest-rss 的 cron 空窗期放入收件目錄並呼叫 webhook，或讓 webhook 呼叫端依 digest-rss 的排程決定何時呼叫。
3. **`maxMediaSeconds` 的預設 6 小時（21600）是容許度，不是建議值。** 接近上限的影片，單次 run 可達數十小時（07 的估算，待量測），期間 digest-rss 完全等待、中斷即全部重跑。管理員在 trigger 以較小的 `maxMediaSeconds` 限制（例如 1 至 2 小時＝3600 至 7200）：超過者在 `audio` 階段被拒絕：run 仍要先取得 `llm` 與 `database`（排在佇列後面時要等），取得後只持有資源數百毫秒、不呼叫模型。需要處理長影片的人，用第二個 trigger（較大的上限）和較少的呼叫端密鑰，並在 digest-rss 空窗期使用。

另：transcribe 的 run 以「階段依模型分批」降低 Lemonade 的模型切換，但與 digest-rss 的 run 交錯時，每次換手都會切換模型；run 之間不會交錯執行（資源容量 1），所以每個 run 內的切換次數不受影響。

## 15. 排錯

### 15.1 建立 run 或 webhook 被拒絕（沒有 run）

| 現象 | 原因 | 處置 |
|---|---|---|
| 手動 run 409 `unsafe_not_allowed`；webhook 202 但觸發紀錄 `refused`／`unsafe_not_allowed` | 版本尚未允許 unsafe | 第 4 節；新版本要重新允許 |
| 409 `resources_unavailable`，`problem` `unbound` | 角色未綁定 | 第 5.3 節；也可能是綁定的資源被停用或刪除（`unknown`／`disabled`） |
| 422 `invalid_parameters`，`problem` `missing` | 缺少 `asrModel` 或 `llmModel` | 補在 trigger 的 `parameters` |
| 手動 run 400 `invalid_parameters`，`problem` `contains_nul` | 參數值含 U+0000 | 移除該字元 |
| webhook 401 | 密鑰錯、trigger 停用或不存在 | 換發密鑰（`rotate-secret`），確認 trigger 的 `enabled` |
| webhook 400 | 見第 7 節（含 `contains_nul`） | 修正後以同一識別碼重送 |
| webhook 500 | 未預期的失敗 | 以相同識別碼重送；持續發生時看 Engine 的 log（`TriggerFirer` 的 ERROR） |

### 15.2 run 失敗（Engine `state` 為 `FAILED`；看 `failure.message` 與 log 的 `FAILED` 行）

| `OUTCOME` 的分類 | 訊息（實測） | 原因與處置 |
|---|---|---|
| `INVALID_NAME` | `file rejected: the name must not contain a parent segment (..)`；`… must be relative to the intake root`；`… resolves outside the intake root`；`… is not a regular file`；`… is empty` | 檔名不合法：含 `..`、絕對路徑、解析符號連結後在根目錄外、是目錄或根目錄本身、空。訊息**不含**根目錄或解析後的路徑，也不回顯呼叫端給的名稱。呼叫端修正檔名 |
| `NOT_FOUND` | `file rejected: no such file in the intake root` | 檔案不存在（含指向不存在目標的符號連結）。確認影片已放進收件目錄、檔名大小寫與子目錄；Docker 確認宿主目錄已掛載 |
| `UNREADABLE` | `file rejected: the file cannot be read` | Engine 帳號讀不到：宿主上檔案要對 other 可讀（`o+r`，目錄 `o+rx`） |
| `CONFIGURATION` | `TRANSCRIBE_INTAKE_ROOT is not set`／`… is not an existing, readable directory`；`RUNLINE_VAD_MODEL is not set`／`… is not an existing, readable file`；`<參數名稱> must be …`；`TRANSCRIBE_WORK_DIR is not an existing, writable directory`（在 `cleanup`）；`llmModel names a model that the llm resource does not list`（`polishModel`、`verifyModel`、`translateModel` 同，在 `polish`）；`the llm resource cannot be used: ENDPOINT_NOT_ENABLED`／`MODEL_NOT_ALLOWED`／`DENIED`／`REQUEST_REJECTED (HTTP 404)`／`REQUEST_TOO_LARGE`／`PARAMETER_LOCKED`／`SECRET_UNAVAILABLE`… | 第 3 節（環境變數）、第 6.1 節（參數）、第 5.1 節（資源）。資源本身的問題與服務回的 401、403、404（金鑰被拒、模型或端點不存在）不重試也不降級，run 立即失敗，轉錄與各對話階段一致；失敗發生的階段是第一次用到該能力的階段（`ENDPOINT_NOT_ENABLED` 在 `transcription`，`MODEL_NOT_ALLOWED` 與 404 在使用該模型的第一個階段）。模型不在服務的清單內由潤飾開始前的檢查發現，訊息只有參數名稱：對照 `GET <baseUrl>/models`，修正該參數 |
| `MEDIA_NO_AUDIO`（`audio`） | `the file has no audio stream` | 影片沒有音訊串流 |
| `MEDIA_CORRUPT`（`audio`） | `ffprobe exited with exit code 1: … moov atom not found / <hidden>: Invalid data found when processing input` | 檔案損毀或不是影片；路徑與檔名被移除（`<hidden>`）。可能是檔案尚未完整寫入——呼叫端須在寫完後才呼叫 |
| `MEDIA_TOO_LONG`（`audio`） | `the file is 62.324 s long, longer than the limit of 62.000 s` | 超過 `maxMediaSeconds`，訊息含影片長度與上限（三位小數）。調高 trigger 的上限，或拒絕該檔 |
| `MEDIA_TOOL_MISSING`（`audio`） | `ffprobe could not be started; it must be installed and found on PATH` | ffmpeg 或 ffprobe 不在 Engine 的 `PATH`。第 2 節。工具失敗與逾時的分類是 `MEDIA_TOOL_FAILED`、`MEDIA_TIMEOUT` |
| `VAD_TOOL_MISSING` | `<命令> could not be started; the VAD tool must be installed` | `TRANSCRIBE_VAD_COMMAND` 指向的命令不存在，或 `runline-vad` 不在 `PATH` |
| `VAD_TOOL_FAILED` | `<runline-vad> exited with exit code 127: VAD tool is not installed: <jar> does not exist (see deploy/transcribe.md)` | `RUNLINE_VAD_JAR` 的 jar 不存在；其他非 0 結束碼時訊息含工具的錯誤輸出尾端（模型檔雜湊不符、WAV 格式不對等） |
| `VAD_TIMEOUT`、`VAD_BAD_OUTPUT` | — | 工具逾時（30 分鐘）或輸出行數不等於 `ceil(取樣數／512)`；工具與模型版本不符時看 [vad/README.md](../../../../runline-transcribe/vad/README.md) |
| `NO_SPEECH` | `there is no speech to transcribe` | 整段音訊沒有語音（或 VAD 參數太嚴）。確認影片有人聲；調 `vadAThreshold` |
| `TRANSCRIPTION_FAILED` | `entry 1 (0.002 s to 3.166 s) could not be transcribed after 2 tries: CONNECTION_FAILED` | 條目重試用盡；類別與 HTTP 狀態在後面（`CONNECTION_FAILED`＝Lemonade 連不上；5xx 與逾時同樣重試）。模型 id 不存在（404）與金鑰被拒（401、403）不在這裡：它們是 `CONFIGURATION`，不重試。先檢查資源（`POST /api/v1/resources/{name}/check`）與 Lemonade。已完成的條目保留 |
| `MODEL_IDENTITY` | 列出請求值、回報值、條目或組號 | 服務回報的模型與請求的不同（被替換）。確認 Lemonade 載入的模型；回報值以 `.gguf` 路徑出現時以檔名比對 |
| 例外型別名稱 | 例如 `SchemaTooNewException` | `store` 階段的資料庫問題：版本比 pipeline 新（回退了 pipeline 版本）或有不屬於本 pipeline 的表；訊息說明版本。不要手改資料庫 |

### 15.3 run 成功，但結果不如預期

| 現象 | 原因與處置 |
|---|---|
| `success-degraded`，降級數高 | 第 10 節的表；先看 log 的 `… DEGRADED` 行的原因是否一致 |
| 摘要與來源語言為 NULL | 摘要階段降級（`SUMMARY DEGRADED`）；字幕與翻譯不受影響（翻譯的摘要處寫 `(none)`） |
| 兩份 SRT 的條目比影片的句子少 | 無語音的條目（轉錄為空）不輸出；VAD 參數決定切分，`segmentation` 的 `a`／`b` 與 `transcription` 的 `noSpeech` 說明 |
| 翻譯文字與來源文字相同 | 來源語言已是繁體中文（不送翻譯，不降級），或該組降級（`translate` 的 `exchange.outcome` 為 `fallback`） |
| 時間不準 | 字幕條目的時間是 VAD 判定的語音區間，不是模型給的；VAD 參數見 README |

### 15.4 資源與主機

| 現象 | 處置 |
|---|---|
| run 長時間 `WAITING_FOR_RESOURCES` | 另一個 run 持有 `llm` 或 `database`（第 14 節）；`GET /api/v1/resources/{name}` 看 `holders`。**這是資源先進先出的正常行為**；必要時取消持有者，或 `POST /api/v1/resources/{name}/holders/{runId}/release`（不停止 run，慎用） |
| `ResourceWaitTimeout` | 第 14 節第 1 點 |
| run 看起來停住 | 看 log 的 `PROGRESS` 與最後一行；轉錄與核對是逐個請求，Lemonade 慢時一個條目數十秒是正常的；log 的 `RETRY` 行表示在重試 |
| Engine log 在 run 結束時有 `left threads running: [jdbc-statement-…]` 的 WARN | 這是舊版 Engine 的缺陷（`jdbc-pool` 存取端的執行緒繼承了 run 的 class loader），WI-1418 之後的 Engine 不再出現；不影響結果。若看到 `[openai-timers…]`，是 `openai-compatible` 存取端的同一類模式，尚未處理，同樣不影響結果 |
| 暫存區有 `onnxruntime-java*` 目錄 | VAD 工具的暫存檔在 run 的工作目錄之下，隨工作目錄刪除，系統暫存區不再累積。被強制結束的 Engine 留下的工作目錄由下一個 run 的 `cleanup` 移除（第 13 節） |

## 16. 已驗證與未驗證

### 16.1 已驗證（真實 Engine；模型服務是 `FakeOpenAiServer`）

環境：Engine 取自 runline 儲存庫 `./gradlew :engine:engineDistribution` 的現有產物（`engine/build/engine-dist`，2026-10-12 00:09 建置；以檔案時間檢查，當時工作樹內沒有比它更新的原始碼檔，所以對應當時的工作樹，含 WI-1400 的 webhook 呼叫端參數，PostgreSQL 遷移到 v14）；Engine 與 VAD 工具用 JDK 25.0.2；PostgreSQL 為 `postgres:17-alpine` 容器；ffmpeg 9.0.2；macOS arm64（Apple M2）；Docker 為 Colima（linux/arm64 VM，Docker 29）。

| 項目 | 結果 |
|---|---|
| 上傳、analyzer 判定（UNSAFE，23 項）、允許前 409、允許、資源與 check、綁定、trigger 的三種 422 | 通過 |
| webhook → 完整 11 階段 → 從 SQLite 取回兩份 SRT、摘要、來源語言（`en`） | 通過；SRT 條目數與時間相同，可被 ffprobe 解析 |
| 短（64 s）與長（1934 s）影片各一的每階段耗時、呼叫次數、降級數 | 第 12 節 |
| ADR-036：401（錯密鑰、無密鑰、不存在的 trigger）；400（缺識別碼、本文非物件、非 JSON、清單外的鍵、缺鍵、值非文字、本文過大）不消耗識別碼且無觸發紀錄；重複識別碼（含同時）只一個 run；已拒絕的 run 建立仍回 202 | 通過 |
| 負向案例：`..`、絕對路徑、符號連結逃逸（檔案與目錄）、不存在、懸空符號連結、目錄、`.`、空、權限 000、無音訊、損毀、超過 `maxMediaSeconds` | 全部被拒絕或失敗，原因明確且不含路徑（容器內另驗證指向 `/etc/hostname` 的絕對符號連結） |
| 取消：VAD 階段、潤飾階段、核對階段；行程與工作目錄被清理；已完成階段資料保留 | 通過（主機與容器） |
| Engine SIGTERM 中斷、SIGKILL 後下一個 run 清理遺留目錄 | 通過 |
| 資源先進先出、`WAITING_FOR_RESOURCES`、等待逾時訊息 | 通過 |
| 降級：模型回答不符契約（`NOT_SRT`）；`success-degraded` 與資料庫對應 | 通過 |
| 設定類錯誤與模型清單檢查、最後一格畫面、`TRANSCRIBE_WORK_DIR`（含不合法值）、VAD 暫存目錄、媒體失敗的 `OUTCOME` 分類 | 以 devkit 的 JVM 驗證，不是打包的 Engine：401、403、404 在轉錄與四個對話階段各自立即 `CONFIGURATION` 失敗且不重試，5xx 仍重試與降級；`llmModel`、`polishModel`、`verifyModel`、`translateModel` 不在清單內於潤飾開始前失敗，清單取不到（500、403、404、端點未啟用、空清單）略過；清單以檔案路徑回報的模型（ADR-028）通過；語音延伸到結尾的樣本（每秒 1 格、語音到檔案結尾）不降級；`TRANSCRIBE_WORK_DIR` 的建立、清理、不存在、是檔案、相對路徑、不可寫；VAD 工具的暫存目錄在工作目錄內，系統暫存區沒有新的 `onnxruntime-java*`；六種媒體失敗的 `OUTCOME` 分類（無音訊、損毀、過長、工具缺少以 run 驗證，工具失敗與逾時以分類的單元測試驗證） |
| 參數值含 U+0000：webhook 與手動 run 回 400、不建立 run、不消耗識別碼、無 ERROR log；`jdbc-statement` 執行緒不再屬於 run 的 class loader | 以 Engine 的測試驗證（真實 PostgreSQL，真實 SQLite 檔），未在打包的 Engine 上重跑 |
| Console 的 trigger 表單（呼叫端提供的參數）：單元、契約（真實 Engine）、e2e（真實 Engine 與 Chrome） | 通過；`dev/verify-console-security.sh` 通過 |
| 設定缺漏：VAD 命令、VAD jar、模型檔、收件根目錄、`PATH` 無 ffmpeg；資源缺端點、模型不在 `allowedModels`、模型服務連不上；參數不合法 | 失敗訊息如第 15 節 |
| Docker：`transcribe` 映像與 `browser-transcribe` 合用映像，`runline-transcribe-check` 通過、完整 run、容器內的負向案例與取消 | 通過；合用映像只跑短與長兩個 run |
| 部署檔：檢查腳本的工作目錄判定、`runline-vad` 包裝腳本傳遞 `JDK_JAVA_OPTIONS` | 腳本層測試通過（含突變驗證）；未在真實映像與 systemd 下實跑 |

### 16.2 未驗證

| 項目 | 原因 |
|---|---|
| **真實 Lemonade 的全部行為** | 沒有可連的服務；見第 17 節 |
| LLM 階段的耗時、容量估算（07）中依賴真實 Lemonade 的數字 | 同上，維持待量測 |
| 真實素材的畫面擷取成本、請求大小（真實 1080p 影片 6 張 512 px 縮圖） | 樣本是 320×240 的合成畫面 |
| 6 小時上限附近的影片、數十小時的 run、記憶體與磁碟壓力 | 時間；樣本最長 32 分鐘 |
| systemd 部署（drop-in 啟動 Engine、`ReadOnlyPaths=`、與瀏覽器 drop-in 並存） | 只有語法檢查與設定不衝突的檢查，本項沒有 systemd 主機 |
| 原生 linux/amd64 | 本機是 arm64；amd64 映像以 Rosetta 模擬執行 |
| 與 digest-rss 的真實交錯（兩個 pipeline 同時排隊） | 只驗證 transcribe 對 transcribe 的等候；機制相同（同一資源、同一等候佇列） |
| `RUNLINE_RUN_TIMEOUT_SECONDS` 的行為 | 未設定；文件所述 |
| 收件目錄在宿主被替換檔案的競態（檢查時間與使用時間的間隙） | README 已記為限制 |
| 上列 devkit 驗證的修復在打包 Engine 與真實 Lemonade 上的行為（尤其是真實 Lemonade 的 `models.list` 回報格式、404 與 401 的實際狀態碼、`JDK_JAVA_OPTIONS` 在 Docker 與 systemd 的 `runline-vad` 包裝腳本下對 VAD 工具 JVM 的生效） | 沒有重跑打包的 Engine 與 Lemonade；見第 17 節的第 1 項 |

## 17. 使用者在有 Lemonade 的環境要驗證的項目

依序執行，前一項不通過不做下一項；結果寫回本文第 16 節與 [WI-1404 的可行性報告](../../../../runline-transcribe/docs/feasibility-report.md)。「通過」的判定在「看哪裡」欄。

| # | 項目 | 做法 | 看哪裡與判定 |
|---|---|---|---|
| 1 | **模型 id** | `GET <Lemonade baseUrl>/models`（或資源的 `check`）列出模型；把轉錄模型填 `asrModel`、gemma4 填 `llmModel`；資源 `allowedModels` 設為這兩個 | 兩個 id 都在清單；一個約 1 分鐘的 run 沒有 `MODEL_NOT_ALLOWED`、`REQUEST_REJECTED (HTTP 404)` |
| 2 | **WI-1404 的量測** | 在專案目錄執行 `./gradlew runFeasibility -Pasr=<轉錄模型> -Pllm=<語言模型>`（需 `espeak-ng` 或 macOS `say`）；報告在 `build/feasibility/feasibility-report.md` | 四項（音訊轉錄、圖片輸入、SRT 遵守率、耗時與切換）有結果；任何一項「失敗」就停下回報（全案依賴的假設 3） |
| 3 | **轉錄回應是否帶 `model`** | 第一次完整 run 後，看 log `transcription` 的 `modelNotReported=` 與 `exchange.model` | `modelNotReported` 等於條目數：回應不帶 `model`，無從比對（視為相符，屬已知限度）；為 0：帶了，且與 `asrModel` 相符（否則 run 以 `MODEL_IDENTITY` 失敗）。記錄是哪一種 |
| 4 | **圖片輸入** | 第一次完整 run 的 `verify` 階段；有明確畫面內容的素材（如畫面上有字） | log `verify` 的 `degraded=` 為 0 或很低；`entry_text.observation` 的文字與畫面相符；`exchange.response` 是 `TEXT:`／`OBSERVATION:` 兩行 |
| 5 | **thinking 預填** | 同一個 1 分鐘樣本以 `llmThinking=off` 與 `on` 各跑一次 | `exchange.duration_ms` 與 `response`：`off` 時回答前沒有推理內容、耗時明顯較短；`on` 時推理出現在 `content` 的 `<think>` 區塊（pipeline 會移除）還是另一個欄位（如 `reasoning_content`，pipeline 只讀 `content`）；非串流的長生成是否撐過 `firstByteMs`。預填無效時（`off` 仍推理）回報，這是已知風險 |
| 6 | **SRT 遵守率** | 至少 20 組的素材（約 10 至 20 分鐘有連續說話的影片）；看潤飾與翻譯 | RUN SUMMARY 的 `polish=`／`translate=` 降級數相對於組數；log 的重試原因分布（`NOT_SRT`、`COUNT_CHANGED`、`TIME_CHANGED`）。WI-1404 以遵守率 ≥ 90% 為通過 |
| 7 | **簡體字檢查對真實輸出** | 取一批真實翻譯回答：`exchange` 表 `stage = 'translate'` 的 `response`；用 `TraditionalChinese.simplifiedCharacters` 逐字列出命中者，並人工找出字集漏掉的簡體字、檢查誤判（日文漢字、專有名詞） | 漏判與誤判的清單；補字集是新的工作項 |
| 8 | **請求大小上限** | 用真實 1080p 影片的一個 6 秒以上的條目；Lemonade 前若有代理，確認其本文上限 | log 的 `VERIFY SHRINK`（`shrinks=`）；`shrinks=0` 表示 6 張 512 px 縮圖在 `maxRequestBytes` 內且服務接受；服務回 413 時 pipeline 縮小邊長，記錄最後可行的邊長 |
| 9 | **第一次完整 run 與耗時** | 第 8 節的第 4 與第 8 步，用真實 Lemonade | 第 12 節缺的數字：`select stage, count(*), avg(duration_ms), max(duration_ms) from exchange where run_id=… group by stage`；轉錄結束到潤飾開始之間的模型切換耗時（相鄰兩個 `exchange` 的差）；據此更新 07 的容量估算 |
| 10 | **真實畫面的擷取成本** | 同第 9 項，比較 `verify` 階段耗時減去 `exchange.duration_ms` 總和 | 每張畫面的毫秒數；超過約 200 ms 時列為效能問題（新的工作項） |

## 附錄 A：端到端驗證發現的小落差與狀態

| # | 儲存庫 | 現象 | 影響 | 狀態 |
|---|---|---|---|---|
| 1 | T | 核對階段的取樣時間 `起點 + k 秒` 落在影片最後一格畫面之後時，ffmpeg 不輸出畫面，該條目以 `no pictures: TOOL_FAILED` 降級 | 影片最後一個條目的畫面觀察缺少；降級數 +1 | 已修正（WI-1417）：落在影片長度內但晚於最後一格的時間點取最後一秒的畫面，不降級 |
| 2 | T 與 R | `TRANSCRIBE_WORK_DIR` 在 04-deployment 與 deploy 中定義，pipeline 原本不讀 | systemd drop-in 的 `CacheDirectory` 與檢查腳本檢查的不是實際使用的目錄 | 已修正（WI-1417、WI-1419）：pipeline 讀它（第 2.3 節）；檢查腳本在未設定時檢查 `/tmp` |
| 3 | R | 呼叫端提供的值或手動 run 的參數含 U+0000 時，Engine 原本回 500 | 持密鑰者可製造 500 與 ERROR log | 已修正（WI-1418）：webhook 回 400 `invalid_caller_parameters`（`contains_nul`），手動 run 回 400 `invalid_parameters`（`contains_nul`） |
| 4 | T | `llmModel` 在資源沒有 `allowedModels` 時打錯，原本在轉錄完成後所有 LLM 階段逐一降級 | 長影片損失數小時才發現 | 已修正（WI-1417）：潤飾開始前的模型清單檢查，401、403、404 立即 `CONFIGURATION` 失敗；仍建議 `allowedModels` 加短樣本 |
| 5 | T | VAD 工具每次呼叫原本在 `java.io.tmpdir` 留下一個空的 `onnxruntime-java*` 目錄 | 目錄數量長期累積 | 已修正（WI-1417）：呼叫端給工具的 JVM 一個在工作目錄內的暫存目錄 |
| 6 | T | 媒體失敗的 `OUTCOME` 原本只顯示 `MediaException` | 無法以 `OUTCOME` 篩選失敗分類 | 已修正（WI-1417）：`OUTCOME failed: MEDIA_<原因>` |
| 7 | R | 每個 run 結束時 Engine 原本記 WARN `left threads running: [jdbc-statement-…]` | log 噪音，不影響結果 | 已修正（WI-1418）：`jdbc-pool` 存取端的執行緒不再繼承 run 的 class loader。`openai-compatible` 存取端有同類模式（`openai-timers`），尚未處理 |
| 8 | 文件 | 07 的容量估算原本假設潤飾與翻譯各約 N／10 組 | 呼叫次數估算偏低 | 已修正：07 改為 2N＋3A＋1，A 與 N 的比例取決於停頓長短 |
