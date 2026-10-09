# WI-59 Run 被觀察為已結束時，資源已釋放

本文回答：Run 結束時，存取端失效、資源釋放與結束記錄三者的先後，以及如何驗收。狀態：已核可（2026-10-08）；已實作（2026-10-08，見「實作結果」；第 5 條驗收條件由 [WI-62](WI-62-release-wait-limit.md) 完成）。相依：WI-09、WI-43。決策見[ADR-007](../adr/ADR-007-shared-resources.md)「Run 終止時」一點。

## 背景

現行 Run 先被記錄為已結束，之後才停用存取端並釋放容量。在負載下，這段時間差使 `OpenAiResourceRunTest` 的三個取消測試、`ResourceRunIntegrationTest`「two runs…」、`EngineAccessorBehaviorTest`「once the run is over…」失敗（[WI-57](WI-57-test-environment-robustness.md)「實作結果」）。

## 行為與驗收條件

- 對每一種終止方式（成功、失敗、取消、逾時、被強制釋放後結束），只要能透過 API（`GET /api/v1/runs/{runId}`）、Console 或 metric 觀察到 run 已結束：
  - 資源的持有者清單已不含該 run；
  - 該 run 的存取端呼叫一律失敗；
  - 等待中的 run 已可取得被釋放的容量。
- 以「在結束流程的各步驟之間刻意加入延遲」的方式證明順序成立：加入延遲後，上述觀察仍成立。延遲只能透過測試支援注入，不得留在產品行為中。
- 存取端失效或釋放本身失敗或卡住時，run 仍會被記錄為已結束，並有 log 與 metric 記錄釋放失敗；以真實資源型別（至少 `jdbc-pool` 與 `openai-compatible` 其中之一）驗證。
- 原本因這段時間差而失敗的 5 個測試，在全量並行下通過，且沒有放寬斷言或加入等待釋放的邏輯。

## 架構約束

- 不改變 run 狀態的種類、API 欄位與強制釋放的語意。
- 跨 run 共享的狀態仍由 Engine 持有（ADR-007）。
- 測試不使用 Stub 或 Mock；外部系統使用真實容器或自製 Fake。

## 實作結果（2026-10-08）

**順序：已修正。** `RunScheduler.completed` 原本先 `progress.finish`（記錄結束、結束 metric、trace 結束）再 `gate.release`；現在先 `gate.release`（`AccessorGate.release` 內先使存取端失效，再由 `ResourceCoordinator` 釋放容量），之後才記錄結束。開始前失敗、被拒絕、等待中取消、關閉時的路徑原本就是先釋放再記錄，未修改。產品程式中沒有任何延遲或測試用分支。

**證明順序的延遲只在測試支援中。** `engine` 的測試支援新增 `SlowRunEnd`：包住真實的 gate，在釋放之前與之後各停一段時間，其餘呼叫原樣轉交；`RunHarness` 新增 `gateAround` 參數，預設不包。新增 `RunEndOrderingTest`（真實 PostgreSQL、協調器、排程器、Runner、真實檔案系統上的 `file` 資源），對成功、失敗、取消、逾時、被強制釋放其中一個資源後結束，各一個測試：run 持有 `file` 資源並留下一條稍後才呼叫存取端的執行緒，另一個 run 等待同一資源；釋放前後各停 400 ms，從透過 API 的儲存（`GET /api/v1/runs/{runId}` 讀的同一處）或 `runline.runs.ended` metric 第一次看到 run 結束的那一刻起：持有者清單不含該 run、存取端呼叫失敗為 `ENDED`、等待中的 run 取得資源並成功，且 run 的結束狀態是該測試要的那一種。

- Red：舊順序下 5 個測試全部失敗於 `seen ended, still holding ==> expected: <[]> but was: <[log]>`。
- Green：新順序下 5 個全部通過。
- 原本失敗的 5 個測試（`OpenAiResourceRunTest` 三個取消測試、`ResourceRunIntegrationTest`「two runs in different class loaders…」、`EngineAccessorBehaviorTest`「once the run is over…」）未修改。暫時把 `RunHarness` 的預設改為釋放前後各停 400 ms（實驗，未保留）：舊順序下 5 個全部失敗（`expected: <[]> but was: <[Holder(...)]>`、`expected: <ENDED> but was: <ok>`，與 WI-57 的實驗相同），新順序下 5 個全部通過。

**失效或釋放失敗或卡住：未實作，停下等待架構決定。**

- 需要一個時間界限：排程器等釋放最多多久，超過即記錄 run 已結束並以 log 與 metric 記錄釋放未完成。目前沒有語意相符的既有組態（`runs.shutdownGraceSeconds` 是關閉時等 run 停止的時間），所以界限要新組態或新數值，依指示停下。
- 實測確認卡住會發生（實驗，未保留）：`jdbc-pool` 資源經一個可凍結的 TCP 轉送連到真實 PostgreSQL，run 在交易中持有一條連線，結束前凍結轉送。存取端失效時連線池清理該連線（`rollback`、`DISCARD ALL`）讀取 socket 沒有逾時，`run-scheduler` 執行緒停在 `NioSocketImpl.read`；20 秒後 run 仍是 `RUNNING`，仍是持有者。舊順序下同一情況 run 會被記錄為已結束，但排程器同樣停住（其他 run 都不能開始或結束）。也就是說，排程器被卡住是原本就有的問題；改順序後差別在於這個 run 不會被記錄為已結束。
- 失敗（例外）的情況：真實型別在 `BoundResources.invalidate` 內已吞掉 `close` 的例外，`ResourceCoordinator.release` 只動記憶體，以真實型別做不出從 `gate.release` 丟出的例外；`RunScheduler` 仍以 `runCatching` 吞掉，沒有 log 也沒有 metric。釋放失敗的 log 與 metric 與卡住的界限一起設計，未先做。
- 建議的做法（待決定）：釋放改在排程器執行緒之外進行，排程器最多等界限時間；逾時則記錄 run 已結束、釋放其並行名額，並以 log（error）與 metric（例如 `runline.runs.release.failures`，標籤區分失敗或逾時）記錄；卡住的釋放之後若完成，照常歸還容量並喚醒等待者，期間容量仍被占用，等待者繼續等待，符合 ADR-007 允許的「已結束但釋放未完成」。需要決定的是界限的組態名稱與預設值（或沿用 `runs.shutdownGraceSeconds`）。

**驗證。** `./gradlew cleanTest :engine:cleanPackagedTest :engine:cleanConsoleTest :engine:cleanConsoleTypecheck :engine:cleanConsoleApiDocCheck check --continue` 1 次：只有 `OpenAiBindingMultipartTest`「a slow upload…」失敗（WI-60）。其餘全部通過：accessors 349 個（21 個跳過，同 WI-57）、analyzer 133、core 88、devkit 181、runner 58、engine 1117（1 個跳過，同 WI-57）、packagedTest 63，Console 1093 與 API 文件檢查 9 個。

**WI-62 完成後（2026-10-08）：失效或釋放失敗或卡住，已實作。** 架構決定為新的 Engine 組態「釋放等待上限」（`RUNLINE_RELEASE_WAIT_SECONDS`，預設 30 秒，與關閉寬限時間分開）與 `jdbc-pool` 重置的型別逾時。run 結束的釋放改在排程執行緒之外進行（`RunRelease`），排程器在釋放完成或超過上限時才記錄結束，期間該 run 仍占並行額度；超過上限時記錄結束、歸還額度、寫 error log 並以 `runline.runs.release.failures{outcome=timed_out}` 計數，卡住的容量在實際釋放完成前仍被占用。釋放拋出的例外不再被吞掉：`BoundResources.invalidate` 與 `JdbcBinding.close` 會在完成失效與歸還其餘連線後拋出第一個失敗，`AccessorGate.release` 無論如何都歸還容量再拋出，排程器以 error log 與 `outcome=failed` 記錄。以上述可凍結 TCP 轉送的同一情境驗證（`RunReleaseLimitTest`），細節見 WI-62「實作結果」。本項的 5 個順序測試與原本因時間差失敗的 5 個測試在 WI-62 之後仍全部通過。
