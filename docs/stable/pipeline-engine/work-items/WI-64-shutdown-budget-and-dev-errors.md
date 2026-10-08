# WI-64 關閉時間的總預算與開發入口的錯誤優先

本文回答：Engine 關閉時如何讓各階段共用一份時間預算，以及 run 與釋放同時失敗時如何呈現錯誤；以及如何驗收。狀態：已核可（2026-10-08）；已實作（2026-10-08，見「實作結果」）。相依：WI-62、WI-63。決策見 [04](../04-deployment.md)「優雅關閉」與 [ADR-007](../adr/ADR-007-shared-resources.md)「Run 終止時」一點。

## 背景

- 關閉時，等請求、停止 run 與釋放、關閉 OpenTelemetry 三段各自可等一個寬限時間，最壞超過平台停止等待（[WI-62](WI-62-release-wait-limit.md)、[WI-63](WI-63-metrics-otlp-export.md)「實作結果」）。
- 開發入口在 run 與釋放同時失敗時，以釋放的錯誤取代 run 本身的錯誤。

## 行為與驗收條件

**關閉的總預算**
- 從收到終止訊號到行程結束，總時間不超過關閉寬限時間加上固定的收尾時間；收尾時間的數值記載於 04，且小於 04 建議的平台停止等待餘裕（15 秒）。
- 以下三個最壞情境同時成立時，總時間仍在上述上限內：
  - 有一個進行中的請求不結束。
  - 有一個 run 的釋放卡住：以 WI-62 的可凍結網路轉送與真實 PostgreSQL 重現。
  - OpenTelemetry 收集器不回應：以 WI-63 的情境重現。
- 預算用完時，未停止的 run 被標記為「中斷」；重啟後該 run 的狀態正確，沒有殘留的資源持有。
- 預算充足時，既有的優雅關閉行為不變：請求完成、run 標記為中斷、遙測送出。WI-63 的指標送達測試仍然通過。
- 關閉時不套用釋放等待上限；一般 run 結束時的釋放等待上限行為不變，WI-62 的測試仍然通過。

**開發入口的錯誤優先**
- run 本身拋出錯誤、釋放也失敗時，開發入口呈現的主要錯誤是 run 本身的錯誤，釋放的失敗附在其上，兩者都看得到。以真實資源型別驗證，例如 WI-62 的 `jdbc-pool` 型別逾時情境。
- 只有釋放失敗時，照 WI-62 的行為呈現釋放的失敗。
- Engine 端同一情境下，run 的結果以 run 本身的失敗為主，不被釋放的失敗取代。

**文件**
- 04「優雅關閉」與 ADR-007 已由架構師改寫；README、部署範例檔中提到關閉時間的說明同步更新。完成後在本文件加「實作結果」。

## 架構約束

- 不新增組態；關閉寬限時間的名稱、預設值與意義（作為總預算）見 04。
- 不改變 run 狀態種類與 API。
- 測試不使用 Stub 或 Mock；外部系統用真實容器或自製 Fake。

## 實作結果（2026-10-08）

**一份預算。** 新增 `ShutdownBudget`（engine）：以關閉寬限時間建立，收到終止訊號時（`ApplicationStopPreparing`）開始，之後每一段只等「剩餘」的時間；先被詢問者即開始（排程器單獨關閉時亦然），重複開始不會加回時間。由 DI 提供，三段共用：
- 等進行中的請求：`configureGracefulShutdown` 以剩餘時間 drain（log 寫出實際等候的時間）。Netty 自己的停止本來就以同一個 `shutdownTimeout`（等於寬限時間）自它開始時起算，剩下的不足時只再等約 0.1 秒。
- 停止 run 與釋放：`RunScheduler.close` 以剩餘時間等 run 停止並完成釋放；預算用完時，仍在釋放的 run 照它結束的狀態記錄，未停止的 run 開始釋放後以剩餘時間（不是釋放等待上限）等候，再記錄為「中斷」。關閉一開始 `RunRelease` 即停止套用釋放等待上限：之後一個釋放的結果只由它實際結束決定，不再因上限而被記為 `timed_out`。記錄「中斷」屬於收尾，不從預算扣。`SchedulerConfig` 移除 `shutdownGrace`，改由建構子注入 `ShutdownBudget`；組態與其意義未變。
- 關閉 OpenTelemetry：`shutdownWithin(剩餘時間)`。發現：Ktor DI 在 `cleanup` 之後還會對每個 `AutoCloseable` 依賴呼叫 `close()`，SDK 的 `close()` 會同步等匯出器（收集器不回應時 10 秒以上）；剩餘時間為 0 時，我們的關閉執行緒還沒開始，DI 的 `close()` 先進入 SDK 的關閉而卡住。改為以 `install(DI) { onShutdown = ... }` 讓 DI 對 `OpenTelemetrySdk` 不再呼叫 `close()`（其他依賴照舊），SDK 只由 `cleanup` 在剩餘時間內關閉。WI-63 記錄的第二次關閉（及其 `INFO: Multiple shutdown calls` 一行）因此不再由 DI 觸發（未另行驗證該行）。

**收尾時間（04）。** 固定 10 秒（`ShutdownBudget.WRAP_UP`，不可調整），小於 04 建議的平台停止等待餘裕（15 秒）；已記載於 04「優雅關閉」。程式中只用作排程器等待「記錄中斷」完成的上限。實測最壞情境（下述）在寬限時間 5 秒下，自送出 SIGTERM 到行程結束為 5.56 秒（3 次：5.560、5.561、5.565 秒），即收尾約 0.6 秒。

**開發入口的錯誤優先。** `LocalResources.holding`：run 本身拋出錯誤時，釋放的失敗以 `addSuppressed` 附在 run 的錯誤上，拋出的是 run 的錯誤；run 沒有錯誤時照 WI-62 拋出釋放的失敗。`DevSession`：run 沒有成功（失敗、逾時無結果）時，以 run 的結束代碼作為主要結果（`Failure: ...` 已先印出），其後印出 `[resources] giving them back failed as well: <失敗>`，結束代碼仍是 run 的（1 或 3），不再以釋放的例外結束行程。Engine 端原本就以 run 本身的失敗記錄 run，釋放的失敗另寫 error log 與 `runline.runs.release.failures{outcome=failed}`，未改程式，以測試守住。

**文件。** README（組態表的 `RUNLINE_SHUTDOWN_GRACE_SECONDS`、`RUNLINE_RELEASE_WAIT_SECONDS` 與「Shutdown」說明、OpenTelemetry 段落）、`deploy/docker/.env.example`、`deploy/docker/compose.yaml`、`deploy/systemd/runline.env.example`、`deploy/systemd/runline-engine.service` 的關閉時間說明改為一份總預算加至多 10 秒收尾；建議的停止等待（寬限時間加 15 秒，預設 45 秒）不變。04 補上收尾時間的數值一段。

**測試（真實元件，無 Stub 或 Mock）。**
- `PackagedEngineTest`「a request that never ends, a run whose release hangs and a collector that never answers stop the Engine within the grace time and the wrap-up time」（打包後的 Engine 行程，SIGTERM）：寬限 5 秒；一個請求的內容以 120 秒慢慢送達（確認 log 有「still in flight」）；一個 run 經 `jdbc-pool`（真實 PostgreSQL 經 `FreezableForward`，PKCS12 金鑰庫中的密碼）在交易中持有連線且不理會中斷，SIGTERM 前凍結轉送；三種訊號經 OTLP/HTTP 送往 `StuckServer`（收下、不回應）。斷言：行程在寬限時間加收尾時間（15 秒）內結束；run 在資料庫中為 `INTERRUPTED`；解凍並重啟後 run 仍為 `INTERRUPTED`、`db` 沒有持有者、另一個使用 `db` 的 run 成功，且 PostgreSQL 上不再有該角色的 `idle in transaction` 連線。Red：舊程式 20.54 秒（請求 5 秒、run 5 秒、釋放約 5 秒、OpenTelemetry 約 5 秒）；第一次寫的版本請求在送出標頭前就停住、沒有被算為進行中（15.4 秒），改為小塊慢送後才重現完整最壞情境。Green：5.56 秒。
- `ShutdownBudgetTest`（4 個）：從開始起遞減、重複開始不加回、用完為 0 不為負、先詢問即開始。Red：骨架永遠回傳寬限時間。
- `RunReleaseLimitTest`「while the Engine shuts down, a release is waited for within the grace time, not the release wait」：釋放等待上限 1 秒、寬限 20 秒，交易中的 run、凍結轉送後關閉排程器。斷言關閉返回時 `db` 已不被持有、metric 為 `failed`／`jdbc-pool` 一次（不是 `timed_out`）、run 為 `INTERRUPTED`。Red：舊程式 1,024 ms 即返回，`db` 仍被持有。
- `RunReleaseLimitTest`「a run that fails and whose release fails too ends with its own failure」：run 拋出 `IllegalStateException`，釋放因凍結在型別逾時內失敗；run 記錄為 `FAILED`，失敗為 run 自己的類別與訊息，metric 為 `failed` 一次且有 error log。守護性測試：舊程式即通過，沒有 Red 階段。
- `DevReleaseFailureTest`（devkit，真實 PostgreSQL 經 `FreezableForward`）：「a run that fails and whose release fails too is shown with its own failure first, and the release's after it」——結束代碼 1，輸出先有 run 自己的失敗，其後有釋放的失敗（`CONNECTION_FAILED`）。Red：舊程式 `execute` 以 `JdbcFailureCause` 結束，取代 run 的失敗。「a release that fails after a run that succeeded is said as it is」——照 WI-62 拋出釋放的失敗；守護性測試，沒有 Red 階段。
- 既有測試的調整：`RunHarness` 改為傳入 `ShutdownBudget(shutdownGrace)`（參數與預設值不變）；`PackagedEngineTest` 的 `uploadSlowly` 加上可選的 `chunks` 參數（預設 10，既有呼叫不變）。斷言未修改。

**已知的保留點。** 收尾時間的上限沒有強制手段：Engine 自身的資料庫不回應時，記錄「中斷」的寫入與連線池關閉可能超過 10 秒（排程器只等「剩餘時間加 10 秒」後繼續，但其後的步驟沒有上限）。若要保證行程一定在寬限時間加收尾時間內結束（例如時間到即以非 0 代碼結束行程），需架構決定。

**驗證。** `./gradlew cleanTest :engine:cleanPackagedTest :engine:cleanConsoleTest :engine:cleanConsoleTypecheck :engine:cleanConsoleApiDocCheck check --continue` 1 次，全部通過（17 分 38 秒）：accessors 352（21 個跳過，同 WI-57）、analyzer 133、core 88、devkit 184、runner 58、engine 1134（1 個跳過，同 WI-57；含本項新增的 6 個與 WI-62、WI-63 的既有測試）、packagedTest 64；Console 測試、型別檢查與 API 文件檢查通過；`ktfmtCheck` 通過。這次沒有出現 `OpenAiBindingStreamTest`「a stream that keeps producing…」的偶發失敗。
