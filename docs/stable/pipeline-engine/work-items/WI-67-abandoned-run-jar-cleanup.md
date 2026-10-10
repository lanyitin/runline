# WI-67 被放棄 run 的 jar 暫存檔清除

本文回答：Engine 關閉時被放棄的 run，其 jar 暫存檔如何清除，以及如何驗收。狀態：已核可（2026-10-10）；已實作（2026-10-10，見「實作結果」）。相依：WI-64。決策見 [04](../04-deployment.md)「優雅關閉」。

## 背景

WI-57 發現：關閉時在預算內停不下來而被標記為「中斷」的 run，它的 jar 暫存檔不會被刪除，其他結束方式都會刪除。每次這樣關閉就留下一個，跨重啟累積。

## 行為與驗收條件

- 被放棄的 run，其 jar 暫存檔在關閉時盡力刪除；以 WI-64 的「run 不理會中斷」情境，在打包的 Engine 與真實停止訊號下驗證。
- 關閉時刪不掉的 run 暫存檔，在 Engine 下次啟動時被清除。驗證方式：預先放入一個由先前行程留下的 run 暫存檔，啟動後它已不存在。
- 啟動時的清除只刪除可確認屬於本 Engine 的 run 暫存檔；同一暫存位置中其他程式的檔案，以及名稱相似但不屬於 Engine 的檔案，都不受影響（有測試）。
- 清除失敗時 Engine 照常啟動，並寫入 log。
- 全量 `./gradlew check` 結束後，暫存位置不再留下 run 的 jar（WI-57 的量測方式）。
- 不增加關閉時間：WI-64 的最壞情境仍在「寬限時間加收尾時間」內。

## 架構約束

- 不新增組態。
- 不改變 run 狀態與 API。
- 測試不使用 Stub 或 Mock。

## 實作結果（2026-10-10）

**關閉時刪除。** `RunScheduler.abandonRemaining`：寬限時間用完仍未停止的 run，記錄為「中斷」之後即刪除它的 jar 暫存檔；刪除失敗時寫 warn log（「留給下次啟動」），不影響其他 run 的記錄。只多一次檔案刪除，不另外等待，不改關閉流程的時間預算。Linux 上 run 的 class loader 仍開著該檔時刪除照樣成功（已開啟的檔案仍可讀）；刪不掉的情況（例如其他平台上檔案仍被占用）由下次啟動處理。

**如何辨識屬於本 Engine 的 run 暫存檔。** jar 暫存檔改名為 `run-<run id>-<隨機數字>.jar`（`RunJarFiles`，仍以 `Files.createTempFile` 建立，權限與建立方式不變）。啟動時的清除（`LeftoverRunJars`）只刪除同時符合以下全部條件者：
- 名稱完全符合 `run-<小寫的標準 UUID>-<數字>.jar`；
- 是一般檔案（不是目錄，也不是符號連結；不跟隨連結）；
- 該 UUID 是本 Engine 資料庫中的 run，且狀態已結束（終止狀態）。

所以其他程式的檔案、名稱相似但不屬於 Engine 的檔案（不在本 Engine 資料庫中的 run id、舊命名 `run-<數字>.jar`、大小寫或副檔名不同者）、資料庫中尚未結束的 run 的 jar 都不受影響。清除在啟動時 `RunRecovery` 把先前行程未結束的 run 標記為「中斷」之後執行，所以先前行程的 run 都已是終止狀態。位置仍為 JVM 的暫存位置（`java.io.tmpdir`），未新增組態。

**清除失敗。** 暫存位置無法列出（例如不存在）時寫 warn log（含位置）並略過；個別檔案刪不掉時寫 warn log（含檔名）並繼續處理其他檔案；任何情況都不拋出，Engine 照常啟動。

**測試（真實元件，無 Stub 或 Mock；暫存目錄沿用 WI-57 的 `TestDirectories`）。**
- `PackagedEngineTest`「a run that does not stop when the Engine is told to leaves no jar behind, within the grace time and the wrap-up time」：打包的 Engine 行程（`-Djava.io.tmpdir` 指向測試自己的目錄）、寬限 3 秒、run 吞掉中斷不停止、SIGTERM。斷言：log 有「did not stop within the grace time」（確實走到放棄路徑）、run 在資料庫中為 `INTERRUPTED`、暫存位置沒有 run 的 jar、行程在寬限時間加收尾時間內結束。Red（暫時移除關閉時的刪除）：`expected: <[]> but was: <[run-7c4c8bfe-…-9756677233809113588.jar]>`。
- `PackagedEngineTest`「the jar an earlier Engine process left for a run is removed at the next start, and nothing else beside it」：第一個 Engine 行程完成一個 run 後停止；依 Engine 的命名放入該 run 的 jar，並放入另一個不屬於本 Engine 的 run id 的同名格式檔、`run-<run id>.jar`、舊命名 `run-<數字>.jar`、`notes.txt`；重新啟動後前者已不存在，其餘都在。Red（尚未接上啟動清除）：該檔仍存在。
- `PackagedEngineTest`「the Engine starts when it cannot look for jars of an earlier process, and says so」：暫存位置不存在時 Engine 照常啟動、就緒為 200，log 有含該位置的 WARN。Red：沒有該 log。
- `LeftoverRunJarsTest`（5 個，真實 PostgreSQL）：已結束的 run（`INTERRUPTED`、`SUCCEEDED`）的 jar 被刪除；其他程式與名稱相似者（不在資料庫的 run id 兩種、舊命名、`run-<id>.jar`、`.jar.part`、前綴不同、隨機部分非數字、大寫、同名的目錄、同名的符號連結〔其目標檔不受影響〕、`notes.txt`、`upload-*.jar`）全部不受影響；未結束的 run（`RUNNING`、`QUEUED`）的 jar 不受影響；目錄唯讀而刪不掉時寫 WARN（含檔名）且不拋出（在 `PermissionsEnforced` 中執行，root 下同樣有效，並先確認權限生效）；目錄不存在時寫 WARN 且不拋出。Red：骨架回傳 0（`expected: <2> but was: <0>`）；只以前綴判斷時「名稱相似者」與「未結束」兩個測試失敗（`expected: <0> but was: <7>`、`<2>`）；未處理失敗時兩個失敗測試分別拋出 `AccessDeniedException`、`NoSuchFileException`。
- `RunServiceTest`「a run that does not stop within the grace time of a shutdown leaves no copy of its jar」（排程器層級，寬限 0.5 秒）：Red `expected: <[]> but was: <[…/run-jars/run-16395496568470382068.jar]>`。「the copy of the jar made for a run is named for that run」：Red `expected: <[<run id>]> but was: <[null]>`。

**關閉時間。** WI-64 的最壞情境測試（`PackagedEngineTest`「a request that never ends, a run whose release hangs and a collector that never answers…」，寬限 5 秒，斷言在寬限時間加收尾時間內結束）未修改、通過；本項新增的關閉測試也斷言同一上限並通過。本次未另行記錄秒數。

**量測（WI-57 的方式）。** 全量 check 前 `/tmp` 有 108 個項目，其中 `run-*.jar` 3 個（`run-10955386095713935906.jar`、`run-6233326115724267127.jar`、`run-8298843296306708515.jar`，2026-10-09、10 先前各次執行留下的舊命名檔）；執行後 105 個項目，`run-*.jar` 仍是同樣 3 個，沒有新增。這 3 個是舊命名，無法確認屬於哪個 Engine，啟動清除依設計不會刪除，也未手動清除。

**驗證。** `./gradlew cleanTest :engine:cleanPackagedTest :engine:cleanConsoleTest :engine:cleanConsoleTypecheck :engine:cleanConsoleApiDocCheck check ktfmtCheck --continue` 1 次（JDK 25，以 root 執行，27 分鐘）全部通過：accessors 356（21 個跳過，同 WI-57）、analyzer 133、core 89、devkit 184、runner 61、engine 1145（1 個跳過，同 WI-57；含本項新增 7 個）、packagedTest 115（含本項新增 3 個）；Console 單元測試 1093、API 文件檢查 9 個、型別檢查與 `ktfmtCheck` 通過。

**已知的限度。** 資料庫中已被保留期限清理刪除的 run，其 jar 若仍留在暫存位置，因無法確認歸屬而不刪除（正常情況下 jar 在關閉當下或下次啟動時就已處理，早於保留期限）。WI-66 留下的執行緒模型與 `RunExecution.residualThreads` 待決事項未觸及。

