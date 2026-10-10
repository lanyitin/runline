# WI-66 共用背景執行緒不持有 run 的 class loader

本文回答：Engine 跨 run 共用的背景執行緒如何保證不持有任何 run 的 class loader，以及如何驗收。狀態：已核可（2026-10-10）；已實作（2026-10-10，見「實作結果」）。相依：WI-46。決策見 [ADR-001](../adr/ADR-001-isolated-classloader.md)「共用背景執行緒」一點。

## 背景

WI-51 發現：`openai-compatible` 的共用計時執行緒，是在第一個使用它的 run 的執行緒上建立的，因此繼承了那個 run 的 class loader，使它永遠無法回收。WI-51 的測試「after the same races every class loader of a run is reclaimed」因此失敗。

## 行為與驗收條件

- 第一個使用 `openai-compatible` 資源的 run 結束後，它的 class loader 可被回收。以真實打包的 Engine 與 Fake 服務驗證，涵蓋一般呼叫與串流。
- 盤點 Engine 中所有跨 run 共用的背景執行緒與執行緒池（計時、排程、連線池維護、遙測、HTTP 用戶端等），每一個都不持有任何 run 的 class loader：它們的建立不受當下 run 的執行緒影響。盤點結果列在回報與「實作結果」中。
- 對盤點出的每一類共用背景工作，各有一個「第一個觸發它的 run 結束後，class loader 可被回收」的測試；其中以 run 的執行緒首次觸發者，須先在修正前以失敗證明測試有效。
- WI-51 原本失敗的上述測試不修改即通過；WI-61 的背景執行緒測試仍然通過。

## 架構約束

- 不改變任何資源型別的行為、逾時與錯誤類別。
- 每個 run 使用獨立 root class loader 的模型不變（ADR-001）。
- 測試不使用 Stub 或 Mock。

## 實作結果（2026-10-10）

**原因與修正。** 一個新執行緒會從建立它的執行緒取得三樣東西：context class loader、執行緒群組與 inheritable thread-local 的值。共用背景執行緒由第一個需要它的執行緒建立，那可能是 run 的執行緒，也可能是 pipeline 自己開的執行緒；三樣中任何一樣屬於該 run，就會讓該 run 的 class loader 在 Engine 整個生命期都無法回收。盤點出兩個會在 run 的執行緒上首次建立的共用執行緒，兩者都是這個缺陷：

- `openai-timers`（`OpenAiBinding` 的 `TIMERS`）：任何 run 第一次呼叫 `openai-compatible`（一般呼叫、串流）時建立。WI-51 的缺陷 2。
- `runner-timers`（`Runner` 的 run 逾時計時器）：設定了 `RUNLINE_RUN_TIMEOUT_SECONDS` 時，第一個 run 開始執行 pipeline 時在 run 的執行緒上建立。本項盤點時新發現，WI-51 的競態測試沒有設定 run 逾時，因此沒有觸發。

兩者改由新的 `SharedThreads.factory(name)`（runner 模組，Engine 端）建立執行緒：群組固定為 JVM 的根群組，不繼承 inheritable thread-local；不繼承時 JDK 給新執行緒系統 class loader（Engine 的）作為 context class loader，而不是建立者的。其餘屬性不變（名稱、daemon、單一執行緒、`removeOnCancelPolicy`）；沒有改變任何資源型別的行為、逾時與錯誤類別，每個 run 獨立 root class loader 的模型不變。

**盤點**（Engine 行程內跨 run 共用的背景執行緒與執行緒池）

| 類別 | 執行緒（名稱） | 首次由誰建立 | 結論 | 測試（`PackagedSharedThreadsTest`） |
|---|---|---|---|---|
| 計時：呼叫逾時 | `openai-timers`（accessors） | run 的執行緒或 pipeline 自己的執行緒（第一次呼叫） | 缺陷，已修正 | 一般呼叫、串流、pipeline 自己的執行緒（自己的群組與 inheritable 值） |
| 計時：run 逾時 | `runner-timers`（runner） | run 的執行緒（開始執行 pipeline 時） | 缺陷，已修正 | 第一個有時間限制的 run |
| 排程：run 的排程、釋放與等待 | `run-scheduler`、`run-release`（`RunRelease`）、`resource-wait-timer`（`ResourceCoordinator`）、JDK 共用池的 `ForkJoinPool.commonPool-delayScheduler`（`RunRelease` 的釋放等待上限） | `run-scheduler` 由 API 或 cron 的執行緒；其餘由 `run-scheduler` | 不受 run 影響 | 第一個等待資源的 run 與它等待的 run |
| 排程：Engine 啟動時開始的定期工作 | `cron-scheduler`、`retention-sweeper`、`workspace-sweeper` | Engine 啟動 | 不受 run 影響 | 第一個由 cron trigger 啟動的 run |
| run 的執行緒池 | `runner-idle`（`Runner`） | 呼叫 `Runner.start` 的執行緒（Engine 為 `run-scheduler`） | 建立不受 run 影響；run 期間 context class loader 為該 run 的，結束時清為 null。另見下方「需要架構決定」第 1 點 | 每個測試都經過 |
| 連線池 | `jdbc-pool` 的連線池（`JdbcPools`，沒有自己的執行緒；連線跨 run 共用，在 run 建立的 `jdbc-statement-*` 執行緒上開啟）；PostgreSQL 驅動的 `PostgreSQL-JDBC-Cleaner`（在 JDK 共用池上執行，驅動提交前把 context class loader 設為 null；共用池的工作執行緒用系統 class loader 與自己的群組） | run 的執行緒（間接） | 不受 run 影響 | 第一個使用 `jdbc-pool` 的 run（連線池在 run 之後仍保留它的連線），之後再檢查同一資源 |
| 資源檢查 | `ResourceChecker` 的檢查執行緒；檢查 `jdbc-pool` 時驅動的 `PostgreSQL-JDBC-SharedTimer`（驅動以自己的 class loader 建立） | API 的執行緒 | 不受 run 影響 | 同上（檢查在 run 之後） |
| 遙測 | OpenTelemetry SDK 的 span 批次處理、metric 週期讀取與 OTLP 匯出的執行緒 | Engine 啟動、SDK 自己的執行緒 | 不受 run 影響 | 匯出 trace 與 metric 時的第一個 run |
| HTTP 伺服器 | Netty 的 event loop、Ktor 與 kotlinx.coroutines 的 `DefaultDispatcher-worker-*` | Engine 啟動、API 的執行緒 | 不受 run 影響（run 的路徑不使用 coroutine） | 每個測試都經由 API |

不是跨 run 共用、隨 run 或存取端結束的（列出以完整）：`openai-compatible` 每個存取端自己的 JDK HTTP 用戶端（存取端關閉時 `shutdownNow`）、`jdbc-statement-*` 與 `jdbc-statement-timer-*`（存取端關閉時 `shutdownNow`）、取消語句的一次性執行緒、Engine 停止時的 `opentelemetry-shutdown`。

**新增的測試**（全部以真實元件：打包後的 `engine.jar` 行程、Testcontainers 的 PostgreSQL 17、`keytool` 做的 PKCS12 金鑰庫、真實編譯的 pipeline jar、自製 Fake OpenAI 相容服務〔真實 socket〕、真實 OpenTelemetry Collector 容器；沒有 Stub 或 Mock；暫存目錄沿用 WI-57 的 `TestDirectories`）：

- `engine` 的 `packaged/PackagedSharedThreadsTest`（packagedTest，8 個）：每個測試啟動新的 Engine 行程，使該 run 確實是第一個；run 結束後以 `jcmd <pid> GC.class_histogram`（先做完整回收再計數）反覆確認 `RunClassLoader` 的實例數歸零。失敗時列出剩下的數量、Engine 記錄的 `left threads running` 與 Engine 的所有執行緒名稱。
- `runner` 的 `SharedThreadsTest`（3 個）：由 `SharedThreads` 建立的執行緒，分別不保留建立者的 context class loader、執行緒群組、inheritable thread-local 的值（以弱參照確認建立者的 class loader 被回收）。

**Red 的證據**

- 「the first run that calls an openai-compatible resource is reclaimed」：修正前 `left: 1`，Engine 記錄 `left threads running: [openai-timers]`。
- 「the first run that streams from an openai-compatible resource is reclaimed」：寫在修正之後，暫時移除修正後失敗，同為 `left: 1`、`[openai-timers]`；還原後通過。
- 「the first run whose time is limited is reclaimed」：修正前 `left: 1`、`[runner-timers]`。
- 「… from a thread of its own, in a group of its own and with a value that threads inherit, is reclaimed」：只修正 context class loader 時失敗（`left: 1`；因為 context class loader 已不是 run 的，Engine 的殘留執行緒檢查不再列出它），加上群組與不繼承 inheritable 值後通過。
- `SharedThreadsTest`：分別移除群組、不繼承 inheritable 值時對應的測試失敗；移除不繼承時 context class loader 的測試也失敗（JDK 改為繼承建立者的）。
- 以 Engine 的執行緒首次觸發的類別（排程、定期工作、檢查、遙測），測試第一次執行即通過，符合盤點的結論。`jdbc-pool` 的測試由 run 的執行緒間接觸發但不需要修正，以突變確認測試有效：暫時讓 `JdbcBinding` 的語句執行緒池為固定大小且不關閉時，測試以 `left: 1`、`[jdbc-statement-db]` 失敗，還原後通過。

**WI-51 與 WI-61 的測試（未修改）。** `PackagedResourceRaceTest`「after the same races every class loader of a run is reclaimed」通過（同一類別 4 個全部通過）；WI-61 的 `MetricsOutputTest`「an Engine started and stopped many times leaves no background threads behind」等 3 個通過。

**需要架構決定（本項未修正）**

1. **run 的執行緒池以 thread-local 保留 run 的 class loader。** `runner-idle` 是跨 run 重用的執行緒；pipeline 在 run 的執行緒上設定 `ThreadLocal`（或 `InheritableThreadLocal`）而沒有移除時，值留在該執行緒上，使那個 run 的 class loader 無法回收（直到 JDK 在同一執行緒的後續 thread-local 操作中清掉過期項目，時間不定）。本機以打包 Engine 實測（未提交）：pipeline 只執行 `new ThreadLocal<Object>() {}.set(new Object() {});`，run 結束後 60 秒仍 `left: 1`。這不是在 run 的執行緒上建立共用執行緒，而是 run 的執行緒本身被重用，修正需要改變 Runner 的執行緒模型（例如每個 run 一條新的平台執行緒，並發上限另行控制；ADR-008 的「每個 run 一條專屬平台 thread」仍成立），超出本項範圍。
2. **JDK 共用池的延遲排程執行緒可能由 pipeline 建立。** JDK 25 的 `ForkJoinPool.commonPool-delayScheduler`（`CompletableFuture.delayedExecutor`、`orTimeout` 等使用）在第一次被使用時建立，且繼承建立者的 context class loader（已在 JDK 25.0.4 實測）。Engine 自己只在 `run-scheduler` 上使用它（`RunRelease`），但 pipeline 的程式若比 Engine 先用到它，該 run 的 class loader 就一直被它保留。本機以打包 Engine 實測（未提交）：第一個 run 的 pipeline 執行 `CompletableFuture.delayedExecutor(1, MILLISECONDS).execute(() -> {})`，run 結束後 `left: 1`、`left threads running: [ForkJoinPool.commonPool-delayScheduler]`。可能的處置：Engine 啟動時先使用一次使它由 Engine 建立（需確認該執行緒在 JDK 中不會閒置結束後重建），或 `RunRelease` 改用自己的計時執行緒並把它視為 pipeline 留下的執行緒（與 pipeline 自己開的執行緒同等看待），或列為接受的限度。
3. 觀察：run 結束時 Engine 記錄的殘留執行緒（`RunExecution.residualThreads`）只比對 context class loader 與執行緒的類別，經由群組或 inheritable 值保留 run 的執行緒不會被列出。本項未改。

**驗證。** `./gradlew cleanTest :engine:cleanPackagedTest :engine:cleanConsoleTest :engine:cleanConsoleTypecheck :engine:cleanConsoleApiDocCheck check ktfmtCheck --continue` 1 次（JDK 25，以 root 執行，28 分鐘）全部通過：accessors 356（21 個跳過，同 WI-57）、analyzer 133、core 89、devkit 184、runner 61、engine 1138（1 個跳過，同 WI-57）、packagedTest 112；Console 單元測試 1093、API 文件檢查 9 個、型別檢查與 `ktfmtCheck` 通過。
