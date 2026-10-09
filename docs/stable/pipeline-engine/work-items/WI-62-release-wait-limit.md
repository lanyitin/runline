# WI-62 釋放有上限，不卡住排程

本文回答：Run 結束時的釋放卡住或失敗時，Engine 如何保證排程不停擺、run 仍會結束，以及如何驗收。狀態：已核可（2026-10-08）；已實作（2026-10-08，見「實作結果」）。相依：WI-59。決策見 [ADR-007](../adr/ADR-007-shared-resources.md)「Run 終止時」一點與 [04](../04-deployment.md)「組態與密鑰」。

## 背景

WI-59 已讓 run 先釋放、再被記錄為已結束，但它的第 5 條驗收條件（釋放失敗或卡住時的處理）沒有完成。實測：`jdbc-pool` 連線的網路被凍結時，清理連線的讀取沒有逾時，排程執行緒因此卡住，其他 run 都無法開始或結束，被卡住的 run 也永遠不會被記錄為已結束（[WI-59](WI-59-run-end-ordering.md)「實作結果」）。另外，釋放時拋出的例外目前被默默吞掉，沒有 log 也沒有 metric。

## 行為與驗收條件

- **卡住**：以 `jdbc-pool` 連到真實 PostgreSQL（Testcontainers），中間經可凍結的網路轉送；run 在交易中持有連線，結束前凍結轉送。
  - 另一個 run 的開始與結束不受影響。
  - 被卡住的 run 在「釋放等待上限」之後被記錄為已結束。
  - 寫入 error log，metric 以「逾時」記錄一次。
  - 它的並行額度已歸還。
  - 它持有的資源容量在釋放實際完成前仍算占用，等待者繼續等。
  - 解除凍結或連線被型別逾時切斷後，容量歸還，等待者取得資源。
- **型別逾時**：`jdbc-pool` 歸還連線前的重置有自己的逾時。同一情境下，型別逾時短於釋放等待上限時，釋放在型別逾時內以失敗結束，不需要動用釋放等待上限。逾時的數值與是否可調，若需要新的組態，停下回報。
- **失敗**：釋放過程拋出例外時，run 照常被記錄為已結束，寫入 error log，metric 以「失敗」記錄一次；不再有被默默吞掉的例外。
- **組態**：「釋放等待上限」是 Engine 組態，預設 30 秒，格式錯誤時 Engine 啟動失敗並列出鍵名；與關閉寬限時間互不影響。04 與部署檔的組態說明同步更新。
- WI-59 已有的 5 個順序測試，以及原本因時間差失敗的 5 個測試仍然通過。
- WI-59 文件的狀態改為已實作，「實作結果」補上本項的結果。

## 架構約束

- 排程不得被任何單一 run 的釋放阻塞。
- 不改變 run 狀態種類、API 欄位與強制釋放語意。
- metric 名稱與標籤記載於 07；標籤只有固定值（例如失敗或逾時）與資源型別，不含資源設定或使用者輸入。
- 測試不使用 Stub 或 Mock；外部系統用真實容器或自製 Fake。

## 實作結果（2026-10-08）

**釋放在排程之外進行，有上限。** 新增 `RunRelease`（engine，`run` 套件）：run 結束時由專屬執行緒呼叫 gate 的釋放（先使存取端失效，再歸還容量），排程器不等待；釋放完成或超過「釋放等待上限」（先到者為準）之後，排程器才記錄 run 已結束、刪除 jar 副本並歸還並行額度。期間該 run 仍占並行額度（`runline.runs.active` 也計入），對它的取消回覆「已要求取消」，run 以原本的結束狀態結束。超過上限時寫 error log（含 run id）並以 `runline.runs.release.failures{outcome=timed_out,type=<型別>}` 記一次；卡住的釋放之後完成時照常歸還容量並喚醒等待者，只寫 log、不再計數。run 狀態種類、API 欄位與強制釋放語意未改變。

**不再吞掉例外。** `BoundResources.invalidate` 不再以 `runCatching` 包住 `close`：存取端照常失效、照常通知觀察者，失敗再拋出；`invalidateAll` 對每個資源都做完再拋出第一個失敗。`JdbcBinding.close` 歸還每一條連線後拋出第一個清理失敗；`JdbcConnectionPool.release` 關閉清理失敗的連線後拋出。`AccessorGate.release` 無論失效是否失敗都歸還容量，再拋出；排程器（`RunRelease`）寫 error log 並以 `outcome=failed` 記一次。強制釋放時清理失敗：資源照常從 run 移除，寫 error log（不計入此 metric，強制釋放不是 run 的結束）。從未開始的 run（被拒絕、等待中取消、關閉時仍在佇列）沒有用過存取端，仍在排程執行緒上同步釋放，失敗時同樣寫 error log 並以 `failed` 計數；開始失敗的 run 與已執行的 run 一樣在排程之外釋放。

**型別逾時（未新增組態）。** `jdbc-pool` 歸還連線前的清理（`rollback`、`DISCARD ALL`、開始敘述）以 JDBC 的網路逾時限制每次讀取：固定 5 秒（`JdbcPools.RESET_LIMIT_MILLIS`），不可調整，清理完成後還原原本的網路逾時。理由：清理只是幾個短敘述，這個值限制的是網路沒有回應的時間，不是工作量；與同一段清理中既有的 `isValid(5)` 一致。逾時或其他清理失敗時連線被關閉（不給下一個 run），失敗以 `JdbcFailureCause` 的形式拋出（只含類別、SQLState、例外類別與遮蔽密碼後的驅動訊息）。數值若需調整或改為可設定，需架構決定。

**組態。** `RUNLINE_RELEASE_WAIT_SECONDS`（`runs.releaseWaitSeconds`，正整數秒，預設 30），格式錯誤或小於 1 時啟動失敗並列出鍵名；與 `RUNLINE_SHUTDOWN_GRACE_SECONDS` 互不影響。已同步 `application.yaml`、README 的組態表、`deploy/docker/.env.example`、`deploy/systemd/runline.env.example` 與 04；metric 名稱與標籤記載於 07。

**測試（全部以真實元件，無 Stub 或 Mock）。** accessors 的測試支援新增 `FreezableForward`（真實 TCP 轉送，凍結時兩個方向都不傳任何資料、連線保持開啟，解凍後送出暫存的資料）。

- `JdbcResetLimitTest`（accessors，真實 PostgreSQL）：交易中持有連線、凍結轉送後使存取端失效。Red：舊程式 20 秒內沒有返回（`giving back the connection still hangs, past the limit of its reset`）。Green：約 5 秒內以失敗結束，失敗訊息不含密碼，連線未被留用，解凍後下一個 run 取得可用的新連線。
- `EngineConfigTest` 新增 3 個：預設 30 秒且不受關閉寬限時間影響、可設定、`0`／`-1`／`soon` 以鍵名拒絕。Red：預設 `PT0S`、未拒絕。
- `RunReleaseLimitTest`（engine，真實 PostgreSQL 經可凍結轉送、真實協調器、排程器、Runner、PKCS12 金鑰庫、OpenTelemetry 記憶體讀取器、log 擷取）：
  - 「卡住」：釋放等待上限 1 秒、並行上限 1。被卡住的 run 在約 1 秒後記錄為 `SUCCEEDED`（不早於上限），仍是 `db` 的持有者，等待者取得並行額度後進入 `WAITING_FOR_RESOURCES` 並繼續等，另一個 run 在唯一的並行額度上開始並成功結束；metric 為 `timed_out`／`jdbc-pool` 一次，error log 含 run id；解除凍結後容量歸還、等待者成功，metric 仍只有一次。Red：舊程式沒有 metric（`expected: <{(timed_out, jdbc-pool)=1}> but was: <{}>`）。
  - 「型別逾時與失敗」：釋放等待上限 30 秒、並行上限 2。被卡住的 run 在型別逾時內（遠早於 30 秒）記錄為已結束，已不是持有者；凍結期間另一個 run 開始並在它之前結束；metric 為 `failed`／`jdbc-pool` 一次，error log 含 run id，log 不含密碼。Red：舊程式中另一個 run 要等釋放結束後才結束（`the other run ended only after the release was over`）。
  - 「強制釋放」：凍結後強制釋放 `db`，回覆 `Released`、持有者清單不含該 run、寫 error log，解凍後等待者成功。Red（暫時拿掉 `AccessorGate` 的處理後執行）：`forceRelease` 丟出 `JdbcFailureCause`。
- 既有測試的調整：`JdbcInvalidationTest`「a connection whose reset fails is closed…」原本期待清理失敗被吞掉，改為期待失敗被拋出（含 SQLState `42883`），原有斷言全部保留。

**實測（卡住與失敗情境）。** 上述 `RunReleaseLimitTest` 即為實測：凍結時 `run-scheduler` 執行緒不再停住；卡住情境下 run 約 1 秒被記錄為已結束、容量到解凍（或型別逾時切斷，約 5 秒）才歸還；型別逾時情境下釋放約 5 秒以失敗結束。

**已知的保留點。** 關閉時寬限時間結束仍未停止的 run，排程器最多再等釋放等待上限讓它們釋放，然後記錄為中斷；最壞情況下關閉時間為寬限時間加釋放等待上限，超過 04 建議的平台停止等待（寬限時間加 15 秒）。實際上清理都有型別逾時，只有型別逾時也失效時才會發生。開發入口（devkit）在 run 結束時呼叫 `invalidateAll`，清理失敗現在會以例外拋出（不再吞掉），若 run 本身也拋出例外，會被釋放失敗的例外取代；devkit 的測試全部通過，未另行調整。

**驗證。** `./gradlew cleanTest :engine:cleanPackagedTest :engine:cleanConsoleTest :engine:cleanConsoleTypecheck :engine:cleanConsoleApiDocCheck check --continue` 1 次：accessors 350 個中 2 個失敗（`OpenAiBindingMultipartTest`「a slow upload…」，WI-60 已知；`OpenAiBindingStreamTest`「a stream that keeps producing…」量到恰好 1000 ms 而斷言要求大於 1000 ms，時間邊界，單獨執行通過，與本項變更的程式路徑無關），21 個跳過（同 WI-57）。其餘全部通過：analyzer 133、core 88、devkit 181、runner 58、engine 1123（1 個跳過，同 WI-57；含 WI-59 的 5 個順序測試與原本因時間差失敗的 5 個測試）、packagedTest 63，Console 測試、型別檢查與 API 文件檢查通過。
