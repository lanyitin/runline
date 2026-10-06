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
| `RUNLINE_ALLOW_LIST` | 用逗號分隔的白名單條目，會完全取代預設白名單：套件（`kotlin`）、套件後加 `:exact` 表示「僅此套件」（`kotlin:exact`）、`class:` 開頭表示完整類別（`class:java.io.PrintStream`，只放行該類別與其巢狀類別）；格式與 Engine 的 `ALLOWLIST_PACKAGES` 相同（由 analyzer 的 `AllowListText` 共用）；名稱不合法時啟動即失敗，已不再接受的尾端 `!` 形式也會失敗並提示改用 `:exact`；設為空字串表示空清單 | 專案的預設白名單（analyzer 模組的 `DefaultAllowList`，Engine 首次啟動也用它） |
| `RUNLINE_ALLOW_LIST_VERSION` | 設定了 `RUNLINE_ALLOW_LIST` 時，判定中顯示的白名單版本；預設清單有自己的版本，此變數對它無效 | `local`（清單為空時為 `local-empty`） |
| `RUNLINE_SHOW_ALLOW_LIST` | 設為 `true` 時列出所用清單的全部條目（格式同 `RUNLINE_ALLOW_LIST`） | 不列出 |
| `RUNLINE_DEV_WAIT_SECONDS` | 等待 run 結果的上限（秒） | 3600 |
| `RUNLINE_RECORD` | 設為 `true` 時開啟錄製（見「錄製 IO 與 metadata 提案」）；其他值啟動即失敗 | 不錄製 |
| `RUNLINE_RECORD_MAX_EVENTS` | 開啟錄製時，逐筆保存的事件上限（0 表示只保留彙總）；超過的部分改以聚合保存，提案不受影響 | 10000 |
| `RUNLINE_RECORD_DIR` | 開啟錄製時，輸出檔的根位置（其下以 run 識別碼分子目錄） | 專案內 `.runline/recordings` |

白名單由 Engine 的管理員維護，本機不會自動取得。開發入口預設使用專案的預設白名單（印出文字、讀取標準輸入的典型 Kotlin pipeline 在其下為 safe；直接使用檔案、網路或啟動行程者為 unsafe）。判定輸出會標明所用的是預設值還是 `RUNLINE_ALLOW_LIST` 覆寫的內容，並顯示版本與條目數量；這**不代表** Engine 現行的白名單版本，管理員修改後兩者可能不同。要看到與 Engine 一致的判定，請把 `RUNLINE_ALLOW_LIST` 設成與 Engine 相同的條目（以 `RUNLINE_SHOW_ALLOW_LIST=true` 可列出本機使用的全部條目，作為修改的起點）。

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
3. 「採用」：可直接貼上的三個 `@PipelineDefinition` 成員（`files`、`network`、`processes`），有 Kotlin 與 Java 兩種寫法。

採用步驟：

1. 檢視提案；過寬或過窄的地方可自行調整。
2. 把三個成員貼進 pipeline 的 `@PipelineDefinition`，取代原有的同名成員（沒有則新增）。`name`、`parameters`、`resources` 與 trigger 不在提案內，保持你原有的。
3. 重新編譯，關閉 `RUNLINE_RECORD`，以一般模式用相同輸入再執行，應不再因 metadata 限制而失敗。
4. 沒有使用過的類別，提案寫成「不允許」：`files = []`、`network = AccessLimit(allow = [])`、`processes = AccessLimit(allow = [])`，不是「不限制」。縮小網路與行程範圍後，原本因「未限制」被判 unsafe 的 pipeline，在同一份白名單下可能變成 safe（判定仍以分析輸出為準）。

開發入口不會自動修改你的 pipeline。

### 限度

- 只涵蓋這次執行走過的路徑；沒走到的分支用到的 IO 不在提案內，提案可能過窄。可用不同輸入多錄幾次，並自行合併。
- 只涵蓋經由 context 的 IO。直接使用 JDK 的 IO 類別（例如 `java.io.File`、`java.net.Socket`、`ProcessBuilder`）不會被錄製；這類 IO 只會在靜態分析的 unsafe 判定中出現。
- 提案不含共享資源、參數與 trigger。
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
