# 開發入口操作指南：在 IDE 中執行與除錯 pipeline

本文回答：開發人員如何在自己的專案中，用 IntelliJ IDEA 執行並除錯 pipeline。對應 [WI-03](pipeline-engine/work-items/WI-03-runner-dev-entry.md)。

## 實測狀態

| 項目 | 狀態 |
|---|---|
| 開發入口的行為（分析判定顯示、獨立 class loader、專屬 thread、目錄、等待上限、參數、結束碼） | 已由自動化測試驗證，並以 JDK 25 直接啟動主程式做過一次實際執行 |
| 錄製 IO 與 metadata 提案（開關、輸出檔、提案內容、採用後以一般模式執行、邊界仍拒絕、事件量上限） | 已由自動化測試以真實編譯的 pipeline、本機真實伺服器與真實行程驗證 |
| 在 IntelliJ IDEA 設定 `RUNLINE_RECORD` 並執行 | **未實測** |
| 在 IntelliJ IDEA 建立下列執行／除錯設定 | **未實測** |
| 中斷點、單步、檢視變數在 pipeline 程式碼中有效 | **未實測**，需由使用者依「待使用者實測的步驟」驗證並回填 |
| 從專案外部取得 devkit 相依（發佈到儲存庫） | 專案尚未提供發佈設定，**未提供也未實測** |

以下 IDE 步驟是依據 IntelliJ IDEA 一般的 Application 執行設定寫成，尚未在本專案實際操作過；實測後請修正與本文不符之處，並把「未實測」改為實測日期與版本。

## 它做什麼

開發入口在同一個 JVM 內，用與 Engine 相同的 Runner 執行 pipeline jar：

- 每次執行建立獨立的 class loader（上層只有 JDK 平台 class loader），pipeline 看不到開發入口本身。
- pipeline 在 Runner 建立的單一專屬平台 thread 上線性執行，thread 以 run 識別碼命名（`dev-年月日-時分秒-四位十六進位`）。單步時不會在 thread 間跳動。
- 執行前先用 analyzer 的同一份分析輸出判定：safe 或 unsafe、白名單版本、每項原因與依賴路徑、限度說明。含 JVM 結束呼叫的 pipeline 會多一段說明：被允許的 unsafe pipeline 可以終止整個程序。
- 套用與 Engine 相同的 metadata 限制（檔案範圍、網路、行程）；開啟錄製時除外（見「錄製 IO 與 metadata 提案」）。unsafe 的 pipeline 在本機仍會執行，判定只是顯示。
- 不需要 Engine、資料庫，也不依賴 Engine 模組。

## 準備

1. JDK 25（Gradle toolchain 會自動下載，IDE 的專案 SDK 也要選 25）。
2. 你的 pipeline 專案要能編譯出 pipeline jar，並在執行期類別路徑上有 `devkit`（devkit 會帶入 core、runner、analyzer）。
   - 本儲存庫內：在你的模組宣告 `implementation(project(":devkit"))`，pipeline 針對 core 撰寫。
   - 本儲存庫外：專案目前沒有發佈設定，尚無現成的取得方式；可考慮 Gradle composite build（`includeBuild`），此作法**未實測**。
3. 在你的專案根目錄的 `.gitignore` 加入 `.runline/`，預設的共享與私有目錄會建在那裡。

## 在 IDE 中建立執行／除錯設定（未實測）

建立一個 Application 設定，之後同一個設定可用 Run 或 Debug 啟動：

| 欄位 | 內容 |
|---|---|
| Main class | `dev.lawlan.runline.devkit.DevMainKt` |
| Use classpath of module | 你的 pipeline 模組（其執行期類別路徑含 devkit） |
| JRE | 25 |
| Working directory | 你的專案根目錄 |
| Program arguments | `<pipeline jar 路徑> <pipeline 類別完整名稱> [名稱=值 ...]`，例如 `build/libs/my-pipelines.jar com.example.Hello who=world` |
| Environment variables | 見下表，皆可省略 |
| Before launch | 加入 Gradle 工作 `jar`（確保執行的是最新編譯結果） |

環境變數：

| 變數 | 用途 | 預設 |
|---|---|---|
| `RUNLINE_SHARED_ROOT` | pipeline 共享目錄的根位置（多次執行間保留） | 專案內 `.runline/shared` |
| `RUNLINE_RUN_ROOT` | run 私有目錄的根位置（每次執行全新） | 專案內 `.runline/runs` |
| `RUNLINE_RESOURCE_ROOT` | `file` 型別共享資源的檔案所在的根位置（[ADR-019](pipeline-engine/adr/ADR-019-typed-shared-resources.md)） | 專案內 `.runline/resources` |
| `RUNLINE_RESOURCES` | 本機的共享資源定義（開發入口不連 Engine）：以逗號分隔的 `名稱=型別[:路徑]`，`file` 的路徑相對於根位置，`openai-compatible` 的路徑是專案內的設定檔（見「型別化共享資源（`openai-compatible`）」），`counter` 沒有路徑，例如 `audit=file:logs/out.txt,lemon=openai-compatible:openai/lemon.json,gate=counter`；格式不合法時啟動即失敗。pipeline 以型別宣告的資源（`typedResources`）必須在此定義且型別相符，否則不啟動 | 無 |
| `RUNLINE_SECRET_<別名>` | `openai-compatible` 資源的金鑰（本機沒有 Engine 的金鑰庫，金鑰由環境提供）：別名轉大寫、非英數字元換成 `_`，例如別名 `lemon-key` 對應 `RUNLINE_SECRET_LEMON_KEY`；值限可列印 ASCII，與 Engine 的機密字元集一致，否則視為取不到。金鑰不寫進設定檔、不被任何輸出顯示 | 無 |
| `RUNLINE_ALLOW_LIST` | 用逗號分隔的白名單條目，會完全取代預設白名單：套件（`kotlin`）、套件後加 `:exact` 表示「僅此套件」（`kotlin:exact`）、`class:` 開頭表示完整類別（`class:java.io.PrintStream`，只放行該類別與其巢狀類別）；格式與 Engine 的 `ALLOWLIST_PACKAGES` 相同（由 analyzer 的 `AllowListText` 共用）；名稱不合法時啟動即失敗，已不再接受的尾端 `!` 形式也會失敗並提示改用 `:exact`；設為空字串表示空清單 | 專案的預設白名單（analyzer 模組的 `DefaultAllowList`，Engine 首次啟動也用它） |
| `RUNLINE_ALLOW_LIST_VERSION` | 設定了 `RUNLINE_ALLOW_LIST` 時，判定中顯示的白名單版本；預設清單有自己的版本，此變數對它無效 | `local`（清單為空時為 `local-empty`） |
| `RUNLINE_SHOW_ALLOW_LIST` | 設為 `true` 時列出所用清單的全部條目（格式同 `RUNLINE_ALLOW_LIST`） | 不列出 |
| `RUNLINE_DEV_WAIT_SECONDS` | 等待 run 結果的上限（秒） | 3600 |
| `RUNLINE_RECORD` | 設為 `true` 時開啟錄製（見「錄製 IO 與 metadata 提案」）；其他值啟動即失敗 | 不錄製 |
| `RUNLINE_RECORD_MAX_EVENTS` | 開啟錄製時，逐筆保存的事件上限（0 表示只保留彙總）；超過的部分改以聚合保存，提案不受影響 | 10000 |
| `RUNLINE_RECORD_DIR` | 開啟錄製時，輸出檔的根位置（其下以 run 識別碼分子目錄） | 專案內 `.runline/recordings` |

白名單由 Engine 的管理員維護，本機不會自動取得。開發入口預設使用專案的預設白名單（印出文字、讀取標準輸入的典型 Kotlin pipeline 在其下為 safe；直接使用檔案、網路或啟動行程者為 unsafe）。判定輸出會標明所用的是預設值還是 `RUNLINE_ALLOW_LIST` 覆寫的內容，並顯示版本與條目數量；這**不代表** Engine 現行的白名單版本，管理員修改後兩者可能不同。要看到與 Engine 一致的判定，請把 `RUNLINE_ALLOW_LIST` 設成與 Engine 相同的條目（以 `RUNLINE_SHOW_ALLOW_LIST=true` 可列出本機使用的全部條目，作為修改的起點）。

## 型別化共享資源（`file`）

pipeline 在 `@PipelineDefinition` 以 `typedResources = [TypedResource(name = "audit", type = ResourceTypes.FILE)]` 宣告需要某個 `file` 資源後，在 `run` 中以 `context.accessors.file("audit")` 取得存取端：`readText`/`readBytes`、`writeText`/`writeBytes`（覆寫）、`appendText`/`appendBytes`。存取端不暴露路徑，只能碰那一個檔案；單次讀取有大小上限（預設 10 MiB，超過得到 `TOO_LARGE`）。取用沒有宣告型別的資源、只宣告名稱的資源或型別不符者，得到 `ResourceAccessException`（`failure` 為 `NOT_DECLARED`、`NO_TYPE_DECLARED`、`TYPE_MISMATCH`），沒有「執行中取得資源」的操作。run 結束後存取端失效，之後的操作失敗（`ENDED`）。錯誤只含類別（`NOT_FOUND`、`PATH_REJECTED`、`TOO_LARGE`、`FAILED` 與 errorId），不含路徑。

開發入口以 `RUNLINE_RESOURCES` 與 `RUNLINE_RESOURCE_ROOT` 在本機提供同一份契約：初始化立即成功（沒有競爭），行為與 Engine 以同一組驗收測試驗證。容量 N 的資源在 Engine 中只限制同時持有的 run 數，**不提供檔案內容層級的協調**。使用存取端不改變 safe 或 unsafe 的判定。

## 型別化共享資源（`openai-compatible`）

pipeline 以 `typedResources = [TypedResource(name = "lemon", type = ResourceTypes.OPENAI_COMPATIBLE)]` 宣告後，在 `run` 中以 `context.accessors.openAiCompatible("lemon")` 取得存取端，用 `call(OpenAiRequest(...))` 呼叫 Engine 管理員為這個資源啟用的端點目錄條目，取得完整回應（`OpenAiResponse`：`status`、`headers`、`body`）。對應 [WI-46](pipeline-engine/work-items/WI-46-openai-compatible-resource.md)，規則與 Engine 相同（行為以同一組驗收測試驗證）。

```kotlin
val lemon = context.accessors.openAiCompatible("lemon")
val answer = lemon.call(
    OpenAiRequest("chat.completions", """{"messages":[{"role":"user","content":"hi"}]}"""),
)
println(answer.body)
```

- `OpenAiRequest(endpoint, body, pathParameters, query, timeouts)`：`endpoint` 是目錄條目的名稱（`chat.completions`、`completions`、`embeddings`、`models.list`、`models.retrieve` 預設啟用，其他由管理員逐條啟用；完整目錄見 [08-api](pipeline-engine/08-api.md) 的「`openai-compatible` 型別」）；`body` 是該條目的 JSON 本文，資源的預設（模型、取樣與長度參數）在先，你提供的同名值蓋過它；`pathParameters`、`query` 只填條目列出者。你不能指定主機、路徑、方法或標頭；金鑰、organization、project 與額外標頭由資源注入，你提供的標頭不存在這條路。
- 管理員可以鎖定參數、限制可用的模型、給數值參數上限；違反時得到 `ResourceAccessException`，`failure` 為 `PARAMETER_LOCKED`、`MODEL_NOT_ALLOWED`、`VALUE_ABOVE_LIMIT`，且不送出請求。`stream` 由資源管理：`call` 的本文帶 `stream` 得到 `STREAM_NOT_SUPPORTED`，要串流請用 `stream(...)`（見下）。
- 錯誤只含類別與（服務有回應時的）HTTP 狀態碼 `e.status`，沒有服務回的訊息或本文：`DENIED`（401、403）、`RATE_LIMITED`（429）、`SERVER_ERROR`（5xx）、`REQUEST_REJECTED`（其他 4xx）、`CONNECTION_FAILED`、`REDIRECT_BLOCKED`、`RESPONSE_TOO_LARGE`、`CANCELLED`、`SECRET_UNAVAILABLE`，以及五種逾時各自的類別（`CONNECT_TIMEOUT`、`FIRST_BYTE_TIMEOUT`、`IDLE_TIMEOUT`、`TOTAL_TIMEOUT`、`QUOTA_WAIT_TIMEOUT`）。**不會自動重試**，要不要重試由你決定。
- `OpenAiTimeouts(connect, firstByte, idle, total, quotaWait)` 可以縮短任何一種逾時，不能放寬。預設的首位元組逾時很長（15 分鐘）：非串流呼叫在生成結束前收不到任何位元組，它實質上就是整體生成上限。
- 同一個 run 同時進行的請求數受資源的 `requestsPerRun`（預設 1）限制；用盡時 `call` 等待額度（受 `quotaWait` 與 run 的取消約束）。用多條 thread 呼叫時，超過額度的呼叫會排隊，不會同時送出。串流進行中時，同一條 thread 在拉取串流時再發請求會等不到額度，須先結束或關閉串流。
- 串流（[WI-47](pipeline-engine/work-items/WI-47-openai-compatible-streaming.md)）：`stream(OpenAiRequest(...))` 對可串流的條目（`chat.completions`、`completions`、`responses.create`）回傳 `OpenAiStream`，以 `next()` 逐個事件拉取（每次是一個事件的 `data` 文字，結束時為 `null`），用完以 `close()` 或 `use` 關閉。你不設 `stream`，由資源設定；要用量就在本文帶 `"stream_options":{"include_usage":true}`。
  ```kotlin
  lemon.stream(OpenAiRequest("chat.completions", """{"messages":[{"role":"user","content":"hi"}]}""")).use { s ->
      while (true) println(s.next() ?: break)
  }
  ```
  首位元組逾時涵蓋到第一個事件，之後 `idle` 涵蓋兩個事件之間，持續送出資料的串流不因整體時間逾時（`total` 選填）；斷線為 `CONNECTION_FAILED`，已拉到的事件在你手上，之後的拉取得到同一個類別。串流佔用額度直到結束或關閉。
- 回應標頭已剝除憑證（名稱含 `auth`、`key`、`token`、`secret`、`cookie`），值中的金鑰以 `***` 取代；回應本文不被處理。

本機設定（開發入口不連 Engine，也沒有金鑰庫）：`RUNLINE_RESOURCES` 的 `lemon=openai-compatible:openai/lemon.json` 指向專案內的 JSON 檔，內容是管理員給 Engine 的同一份 `settings`（欄位見 08-api），另可加 `"secretAlias": "lemon-key"`；金鑰從環境變數 `RUNLINE_SECRET_LEMON_KEY` 讀取。設定檔不合規、讀不到或別名不合規時啟動即失敗，只說類別，不顯示值；別名設定了但環境沒有金鑰時，呼叫得到 `SECRET_UNAVAILABLE`，不會送出請求。開發入口可以連真實服務。使用存取端不改變 safe 或 unsafe 的判定，也不需要在 `network` 宣告服務的主機。錄製時只記錄資源名稱、型別與動作類別，不記錄端點、本文或位址。

## 錄製 IO 與 metadata 提案

手寫完整的限制不容易。錄製模式讓你執行一次 pipeline，就得到一份可對照著寫進 `@PipelineDefinition` 的提案。對應 [WI-04](pipeline-engine/work-items/WI-04-recording.md)、[ADR-004](pipeline-engine/adr/ADR-004-recording.md)。

### 如何開啟

在同一個執行／除錯設定的環境變數加上 `RUNLINE_RECORD=true`（預設關閉）。只有開發入口能錄製；Engine 沒有任何開啟錄製的途徑，Engine 的 run 行為不變。

### 錄製模式與一般模式的差異

| 項目 | 一般模式 | 錄製模式 |
|---|---|---|
| 檔案範圍與讀寫（共享、私有） | 依宣告限制 | 兩種目錄都可讀寫，不受宣告限制，全部記錄 |
| 網路 | 依宣告的主機限制 | 不受限制，全部記錄 |
| 外部行程 | 依宣告的指令限制 | 不受限制，全部記錄 |
| 目錄邊界：絕對路徑、相對路徑跳出範圍、符號連結逃逸 | 拒絕 | **仍然拒絕**；拒絕的嘗試也記錄，但不納入提案 |
| 磁碟用量上限 | 生效 | **仍然生效** |
| pipeline 的行為與結果 | 受宣告限制 | 與不受限制時相同（相同輸入） |

執行開始前，輸出會有一行 `[recording]` 說明限制已放寬，此次執行不代表一般模式下的結果。靜態分析的判定照常顯示，不受錄製影響。

### 輸出在哪裡

run 結束後（失敗也有，但提案只反映失敗之前的 IO），在 `RUNLINE_RECORD_DIR`（預設 `.runline/recordings`）下的 `<run 識別碼>/`：

- `events.txt`：事件逐筆列出，每筆有發生順序、類別、目標與讀寫性質。檔案記錄「共享或私有目錄」與相對位置（讀取、列出、查詢存在為讀；寫入、刪除為寫）；網路記錄主機與埠號；外部行程只記錄指令（第一個元素）。**不含**檔案內容、傳輸內容、行程引數與輸出，也不含主機上的絕對路徑（被拒絕的絕對路徑只寫「(absolute path)」）。被拒絕的邊界違規標示「被拒絕」。
- `proposal.md`：metadata 提案，同一份內容也印在主控台。

事件量有上限（`RUNLINE_RECORD_MAX_EVENTS`，預設 10000）：只逐筆保存前面的事件，其後只計入彙總（檔案以目錄與讀寫為單位，網路以主機為單位，行程以指令為單位），所以大量重複或大量不同路徑的動作不會耗盡記憶體，而提案仍涵蓋整次執行用過的所有範圍。`events.txt` 開頭會註明逐筆保存了多少筆。

### 如何採用提案

`proposal.md` 的內容：

1. 涵蓋限度與錄製模式的說明。
2. 「依據」表：每一項用到的檔案目錄與讀寫、主機、指令，以及次數。
3. 「採用」：可直接貼上的 `@PipelineDefinition` 成員（`files`、`network`、`processes`，用過型別化資源時多一個 `typedResources`），有 Kotlin 與 Java 兩種寫法。

採用步驟：

1. 檢視提案；過寬或過窄的地方可自行調整。
2. 把三個成員貼進 pipeline 的 `@PipelineDefinition`，取代原有的同名成員（沒有則新增）。`name`、`parameters`、`resources` 與 trigger 不在提案內，保持你原有的。
3. 重新編譯，關閉 `RUNLINE_RECORD`，以一般模式用相同輸入再執行，應不再因 metadata 限制而失敗。
4. 沒有使用過的類別，提案寫成「不允許」：`files = []`、`network = AccessLimit(allow = [])`、`processes = AccessLimit(allow = [])`，不是「不限制」。縮小網路與行程範圍後，原本因「未限制」被判 unsafe 的 pipeline，在同一份白名單下可能變成 safe（判定仍以分析輸出為準）。

開發入口不會自動修改你的 pipeline。

### 限度

- 只涵蓋這次執行走過的路徑；沒走到的分支用到的 IO 不在提案內，提案可能過窄。可用不同輸入多錄幾次，並自行合併。
- 只涵蓋經由 context 的 IO。直接使用 JDK 的 IO 類別（例如 `java.io.File`、`java.net.Socket`、`ProcessBuilder`）不會被錄製；這類 IO 只會在靜態分析的 unsafe 判定中出現。
- 提案含用過的型別化資源（名稱與型別，`typedResources`；只記錄名稱、型別與讀或寫，不含路徑與內容），不含只宣告名稱的資源、容量、參數與 trigger。
- 外部行程記錄的是 pipeline 傳給 context 的指令（第一個元素），原樣照錄；若寫的是路徑，提案裡就是該路徑。
- 錄製資料只用來產生提案，不用於重放或 mock。
- 在 IntelliJ IDEA 中加環境變數執行錄製，尚未實測。

## 除錯步驟（未實測）

1. 在 pipeline 原始碼設中斷點。
2. 用上述設定按 Debug。
3. 先看主控台前幾行的判定，再看 `[status]`、`[stdout]`、`[stderr]` 行（pipeline 的標準輸出與錯誤輸出會被 Runner 導入 run 的 log，並以這些前綴顯示）。
4. 停在中斷點時，用單步（Step Over／Into）與變數面板檢視；執行緒面板應只看到一條以 run 識別碼命名的 thread 在執行 pipeline。

## 行為細節

- 結束碼：0 成功；1 run 未成功（失敗、被取消）；2 沒有執行（參數、設定、jar 或類別有問題）；3 等待上限內沒有結果。
- 失敗時會印出例外類型、訊息與堆疊。
- 等待上限：Runner 已知在某些非預期例外下結果可能不完成（將由 WI-08 補強）。開發入口因此只等待設定的秒數；逾時會印出訊息、最後已知狀態，並要求 run 停止（協作式，不理會的 pipeline 仍會繼續）。除錯時停在中斷點的時間也計入等待，若會停很久，請調高 `RUNLINE_DEV_WAIT_SECONDS`。
- 共享資源：pipeline 宣告的資源在本機視為立即取得，並於 run 結束（無論成功、失敗或取消）時釋放，主控台以 `[resources]` 行顯示；本機不會競爭，也不會對照 Engine 的資源定義，未被管理員定義的名稱同樣成功。沒有宣告資源的 pipeline 不會多出任何輸出。
- 不套用 pipeline 的逾時，避免停在中斷點時觸發逾時中斷。
- 每次執行的 run 識別碼都是新的，所以私有目錄每次都是全新的；成功的 run 其私有目錄會被刪除，失敗的保留一天供檢查，下次啟動時清除過期者。共享目錄在多次執行間保留，要重來請刪除對應的目錄。

## 限度

- context 的限制是協作式：不經 context 的 IO 不受控。
- 分析不涵蓋反射與動態載入。
- unsafe pipeline 在本機也會執行；若它呼叫 JVM 結束，你的除錯程序會一併結束。開發入口不攔截。
- jar 必須是編譯後的 pipeline jar；開發入口不直接執行類別目錄。

## 待使用者實測的步驟

（錄製）在 IDEA 的執行設定加入環境變數 `RUNLINE_RECORD=true`，執行一個會讀寫檔案或啟動行程的 pipeline，確認主控台出現 `[recording]` 說明、提案，且 `.runline/recordings/<run 識別碼>/` 下有 `events.txt` 與 `proposal.md`；依提案修改後關閉錄製再執行，確認成功。


在 IntelliJ IDEA 中，依序確認並記錄 IDEA 版本、日期與結果：

1. 依上表建立 Application 設定，按 Run，確認主控台出現判定與 `[status] SUCCEEDED`。
2. 在 pipeline 的 `run` 內設中斷點，按 Debug，確認停在中斷點。
3. 單步執行數行，確認每一步都在同一條 thread，且 thread 名稱為 `dev-…`。
4. 在變數面板檢視區域變數與 `context.parameters`。
5. 在 pipeline 呼叫的另一個類別（例如輔助類別）也設中斷點，確認同樣有效。
6. 修改 pipeline 後重新 Debug，確認 Gradle `jar` 先執行、停在新程式碼。
7. 若有不符之處（例如中斷點未命中、原始碼對不上），記錄並回報。
