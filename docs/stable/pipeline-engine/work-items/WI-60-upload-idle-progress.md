# WI-60 上傳的閒置判定以服務端實際收下的進度計時

本文回答：上傳期間的閒置逾時如何判定、誤差上限在哪裡記載，以及如何驗收。狀態：已核可（2026-10-08）；已實作（2026-10-08，見「實作結果」）。相依：WI-53。決策見[ADR-019](../adr/ADR-019-typed-shared-resources.md) 決策 4「閒置逾時」。

## 背景

上傳的閒置逾時以「HTTP 用戶端取走資料」計時。作業系統的送出緩衝會先收下一大段資料（實測最大 4 MiB），之後服務端雖然持續讀取，Engine 卻判定為 `IDLE_TIMEOUT`，違反「持續有進度的上傳不逾時」。`OpenAiBindingMultipartTest`「a slow upload that keeps going…」在開發容器中因此每次失敗（[WI-57](WI-57-test-environment-robustness.md)「實作結果」）。

## 行為與驗收條件

- 服務端以低速持續讀取上傳內容時，即使總時間遠超過 `idleMs`，只要兩次讀取之間的間隔小於 `idleMs`，上傳就不會因 `IDLE_TIMEOUT` 失敗。
  - 以自製 Fake 服務驗證。
  - 須在作業系統送出緩衝很大的環境（例如本開發容器）中通過。
  - 須涵蓋多部分表單與二進位本文兩種上傳。
- 服務端停止讀取超過 `idleMs` 時，上傳仍以 `IDLE_TIMEOUT` 失敗；從服務端停止讀取到判定逾時，所需時間不超過 `idleMs`，加上填滿 Engine 的送出緩衝與服務端接收緩衝所需的時間（服務端接收緩衝不歸 Engine 控制，08-api 註明）。
- 送出緩衝上限為 256 KiB（設定值），由 Engine 啟動時設定，開發入口相同；記載於 08-api，含它作用於整個 JVM 的 JDK HTTP 用戶端這一副作用（[ADR-019](../adr/ADR-019-typed-shared-resources.md) 決策 4）。
- 大檔上傳的吞吐沒有不合理的下降：以同一環境量測修正前後的上傳時間，並在回報中列出結果。
- `OpenAiBindingMultipartTest`「a slow upload that keeps going…」通過，斷言不放寬。
- 08-api 記載送出緩衝上限；[WI-53](WI-53-openai-compatible-multipart-and-binary.md)「實作結果」中「上傳的閒置逾時」的描述同步改寫。

## 架構約束

- 不改變 `idleMs` 的設定方式、預設值與錯誤類別，也不改變串流回應的閒置語意。
- 不新增管理員或 pipeline 可調整的設定。
- 除送出緩衝上限外，不以全域系統屬性改變 JVM 的 HTTP 行為。
- 測試不使用 Stub 或 Mock。

## 實作結果（2026-10-08）

**送出緩衝上限。** 新增 `UploadSendBuffer`（accessors，`openai` 套件）：`BYTES = 256 KiB`，`install()` 把系統屬性 `jdk.httpclient.sendBufferSize` 設為 262144，啟動參數另給的值會被取代。JDK HTTP 用戶端在每次建立連線時讀取這個屬性（JDK 25 的 `PlainHttpConnection` 經 `HttpClientImpl.getSendBufferSize()` 讀取，不在類別載入時固定），所以只要在第一個 run 開始前設定即可。Engine 以新的 Ktor 模組 `configureUploadSendBuffer`（`UploadSendBufferStartup.kt`，列在 `application.yaml` 的 `configureLogging` 之後）呼叫；開發入口在 `DevMain.run` 開始時呼叫。閒置計時本身（`Progress`：用戶端每次取走表單位元組重新計時）、`idleMs` 的設定方式、預設值、錯誤類別與串流回應的閒置語意都未改變；未新增任何組態。除這一個屬性外，沒有以系統屬性改變 JVM 的 HTTP 行為。

**文件。** 08-api「上傳」記載 256 KiB、它是 JVM 全域設定（同一 JVM 內所有 JDK HTTP 用戶端，包括允許 unsafe 執行的 pipeline 自行建立者）、只影響高延遲網路的上傳速度上限，以及服務端接收緩衝不歸 Engine 控制；WI-53「實作結果」中「上傳的閒置逾時」已改寫。

**測試（全部以真實元件：自製 Fake 服務在真實 socket 上讀取、真實 JDK HTTP 用戶端、真實檔案；無 Stub 或 Mock）。**

- `OpenAiBindingMultipartTest` 在類別初始化時呼叫 `UploadSendBuffer.install()`，與 Engine、開發入口啟動時相同；既有斷言未修改。
  - 「a slow upload that keeps going…」（範圍檔案，12 MiB，服務端每讀 8 KiB 等 3 ms，`idleMs` 300）。Red：本容器（`tcp_wmem` 上限 4 MiB）每次以 `IDLE_TIMEOUT` 失敗。Green：約 4.9 秒完成，連續 3 次通過。
  - 新增「a slow upload of bytes given in memory that keeps going is not idle either」（以位元組給的檔案部分，同上參數）。Red：`IDLE_TIMEOUT`。Green：約 4.9 秒完成。
  - 新增「an upload whose service stops reading part way is idle within the idle limit and the filling of the buffers」（20 MiB，服務端先慢速讀取 2 MiB 再停止讀取）：斷言為 `IDLE_TIMEOUT`，且從停止讀取到判定逾時小於 `idleMs` + 500 ms（迴環介面上填滿緩衝只需數毫秒，其餘為排程寬裕）。Red：服務端停止讀取之前，上傳就已被判為閒置。Green：實測從停止讀取到判定為 246 至 249 ms。
- `StartupTest`（engine，真實 PostgreSQL、真實 `application.yaml` 模組）新增：啟動前把屬性設為 65536，啟動後為 262144。Red：`expected: <262144> but was: <65536>`。
- `DevMainTest`（devkit）新增同樣的檢查。Red：同上。

**吞吐。** 同一容器、同一 JVM，以 Fake 服務（不延遲）經迴環介面上傳 100 MiB 範圍檔案，屬性未設定（修正前，由作業系統自動調整）與 262144（修正後）交替各 7 次（先各暖機 1 次）：修正前 449 至 517 ms（中位數 476 ms），修正後 469 至 551 ms（中位數 496 ms），約慢 4%，在量測波動範圍內。高延遲網路下的上限（約 256 KiB 除以往返延遲）未實測：本容器無法在不影響其他行程的情況下模擬往返延遲。

**已知的保留點。**

- 「多部分表單與二進位本文」：目前的端點目錄中，上傳只有多部分表單（`BodyKind` 為 `NONE`、`JSON`、`MULTIPART`），沒有以原始二進位為本文的上傳條目。本項以檔案部分的兩種來源（範圍檔案、記憶體中的位元組）涵蓋；日後若新增原始二進位本文的條目，它走同一個用戶端，受同一上限，但須另加測試。
- 閒置計時從用戶端最後一次取走資料起算，這個時點可能早於服務端最後一次讀取（用戶端等送出緩衝空出一部分才再取）。所以服務端停止讀取後，判定所需時間可能短於 `idleMs`（實測 246 至 249 ms，`idleMs` 300），仍在文件記載的上限之內；兩次讀取的間隔接近 `idleMs` 的服務，可能因這段差距被判為閒置，差距的大小約為服務端讀完半個送出緩衝所需的時間。

**驗證。** `./gradlew cleanTest :engine:cleanPackagedTest :engine:cleanConsoleTest :engine:cleanConsoleTypecheck :engine:cleanConsoleApiDocCheck check --continue` 1 次，全部通過：accessors 352（21 個跳過，同 WI-57）、analyzer 133、core 88、devkit 182、runner 58、engine 1124（1 個跳過，同 WI-57）、packagedTest 63，Console 1093 與 API 文件檢查 9 個。`OpenAiBindingStreamTest`「a stream that keeps producing…」這次通過（1.004 秒），未出現時間邊界失敗。
