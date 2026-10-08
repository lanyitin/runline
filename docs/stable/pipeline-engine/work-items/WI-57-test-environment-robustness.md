# WI-57 測試在開發容器中穩定通過，真實瀏覽器腳本的前置條件文件化

本文回答：`./gradlew check` 在開發容器中有哪些既有失敗、要達到什麼狀態，以及手動腳本的前置條件記在哪裡。狀態：已核可（2026-10-08）。相依：WI-26。

## 背景

開發容器以 root 執行，`./gradlew check` 有以下既有失敗：
- **以 root 執行**：檔案權限的限制對 root 不生效，`FileProbeTest` 2 個、`ResourceCheckerTest` 1 個失敗；`InfoRoutesTest` 失敗，因為呼叫者名稱 `root` 與系統使用者名稱相同。
- **負載下的計時**：`OpenAiBindingMultipartTest` 的慢速上傳、`TriggerStartupTest` 在全量並行時失敗，單獨執行通過。Console 的 `CreateRunPage` 測試也有一個在全量並行時偶發失敗。

時間精度造成的失敗由 WI-56 處理，不在本項範圍。另外，真實瀏覽器腳本 `resources.e2e.ts` 需要金鑰庫中有受信任憑證 `corporate-ca`，README 沒有記載。

## 行為與驗收條件

- 在開發容器中（以 root 執行），`./gradlew check` 連續執行 3 次全部通過；以一般使用者執行同樣通過。
- 不得以跳過、停用、隔離測試或放寬斷言達成：
  - 權限相關的行為（檔案不可讀寫時的檢查結果）在 root 下仍須被驗證，驗證方式在 root 與一般使用者下都有效。
  - `InfoRoutesTest` 驗證的「回應不洩漏系統使用者名稱」語意保留，且不因呼叫者名稱恰好等於系統使用者名稱而誤判。
- 計時相關的測試，失敗原因須以實際分析說明（例如固定的等待時間與負載下的排程），修正後在全量並行下穩定。不得只靠拉長逾時，除非說明為何該逾時本身就是被測行為的一部分。
- 真實瀏覽器腳本的前置條件（金鑰庫中需要的項目與其類型、需要的 Fake 服務行程、資料庫是否須為全新）在同一處文件完整列出，其他文件以連結引用；以一個只依文件從零準備的環境執行全部腳本並通過，作為驗證。
- 回報中列出每個原本失敗的測試：原因、修正方式、修正後的結果。

## 架構約束

- 不改變任何產品行為；只改測試、測試支援與文件。若分析發現失敗其實反映產品缺陷，停下回報，不在本項內修正產品程式。
- 測試不使用 Stub 或 Mock；沿用 WI-23、WI-26 的測試穩定性與逾時原則。

## 實作結果（2026-10-08）

**以 root 執行：已修正（只改測試與測試支援）。**

- `FileProbeTest` 2 個與 `ResourceCheckerTest`「each way the file cannot be used…」：root 的 `CAP_DAC_OVERRIDE` 等能力使 `chmod` 設下的權限不生效。accessors 的 test fixtures 新增 `PermissionsEnforced`：在一條自己的執行緒上以 C 函式庫的 `capset` 放棄 `CAP_DAC_OVERRIDE`、`CAP_DAC_READ_SEARCH`、`CAP_FOWNER`（有效與允許集合；Linux 的能力屬於執行緒，只影響這條執行緒與它啟動的執行緒，例如檢查器的執行緒），再執行測試本體。一般使用者本來就沒有這些能力，放棄的是空集合，所以同一個驗證在 root 與一般使用者下都有效；權限測試仍先確認權限確實生效，不生效即失敗，不跳過。`PermissionsEnforcedTest` 驗證它本身（只開執行緒、不放棄能力時，root 下 2 個失敗）。
- 同一原因使 `KeystoreSecretStoreTest`「a read only file in a read only directory…」在 root 下通過卻什麼也沒驗證（以寫入模式開檔的實作也會通過）：加上權限生效的前置確認（root 下失敗），並在 `PermissionsEnforced` 中執行。
- `InfoRoutesTest`「neither answer holds…」：測試 token 的名稱 `root` 與系統使用者名稱相同，字串搜尋誤判。`caller` 改以完全相等驗證（token 的名稱與角色），其餘內容照舊搜尋；系統使用者名稱仍不得出現在回應的其他任何地方。
- `TriggerStartupTest`「the cron scheduler runs…」：前一個失敗的 `InfoRoutesTest` 在 `testApplication` 內丟出例外，該 Engine 的 `cron-scheduler` 執行緒留在同一個測試 JVM，使「測試前沒有排程器」失敗。以修正前的 `InfoRoutesTest` 與 `TriggerStartupTest` 一起執行可重現（前者之後留下一條排程器執行緒，後者失敗）；`InfoRoutesTest` 修正後不再發生，`TriggerStartupTest` 未修改。
- `CreateRunPage.test.ts`「is two choices, each saying whose…」（`ConsoleBuildTest` 會在內部執行它）：兩個版本先後建立，時間相同或差 1 毫秒取決於時鐘是否剛好跳動，頁面依時間由新到舊排序。改為給定兩個上傳時間，預期依頁面的規則由新到舊。

**疑似產品缺陷：停下回報，未修改產品程式，也未修改測試。**

1. Run 結束時先記錄結束狀態，之後才停用存取端並釋放容量（`RunScheduler.completed`：`progress.finish` 在 `gate.release` 之前）。這段時間內 API 已顯示 run 結束，它卻仍是持有者，它的存取端也仍可使用。`OpenAiResourceRunTest` 的三個取消測試、`ResourceRunIntegrationTest`「two runs in different class loaders…」、`EngineAccessorBehaviorTest`「once the run is over its accessor stops working」都在看到 run 結束後立刻檢查，負載下失敗。在兩步之間暫時加入 500 ms 的實驗中，這五個測試全部失敗（`expected: <[]> but was: <[Holder(...)]>`、`expected: <ENDED> but was: <ok>`）。測試若改成等待釋放，等於放寬「結束即釋放」的斷言。順序要改，或在文件中接受這段時間，需要架構決定。
2. 上傳的閒置逾時，是從用戶端（JDK HttpClient）每次取走表單位元組起算。核心的 socket 送出緩衝會先收下大量資料（本容器自動調到 4 MiB；`tcp_wmem` 上限 4 MiB、`tcp_rmem` 上限 32 MiB），所以服務端持續讀取時，用戶端仍可能超過 `idleMs` 都沒有取資料。實測：前 4 MiB 在 76 ms 內取走，之後 300 ms 沒有再取，結果是 `IDLE_TIMEOUT`。同一測試加上 `-Djdk.httpclient.sendBufferSize=65536` 執行則通過。08-api 寫「持續有進度的慢速上傳不因整體時間逾時」，在大緩衝的環境不成立；預設的 `idleMs` 為 5 分鐘時，門檻約為每秒 7 KB，管理員把 `idleMs` 調短時才容易遇到。`OpenAiBindingMultipartTest`「a slow upload that keeps going…」在本容器中單獨執行也每次失敗，與負載無關。可選的方向：接受並寫明精度（測試參數照它推導），或改變產品的量測方式（例如限制送出緩衝），需要架構決定。
3. 每個 Engine 應用程式都會啟動 Dropwizard 的 `Slf4jReporter`（`Monitoring.kt`，每 10 秒輸出約 80 行），應用程式停止時沒有停止它。正式環境一個行程只有一個應用程式，影響不大；但測試 JVM 會累積數百個（engine 的測試結束時約 300 個，每秒約 2,400 行 log，經遮罩轉換器與 Gradle 的輸出擷取），是測試期間負載的主要來源之一，會放大第 1 項的時間差。

**未能重現。** `OpenAiBindingStreamTest`「a stream that keeps producing…」：每塊間隔 250 ms，閒置上限 400 ms。之前只有一次整體執行（另一個工作階段）失敗，當時的失敗訊息與同時進行的工作已不可考。本項的所有整體執行都沒有再發生；在 4 核心上加 8 個忙碌迴圈各執行 5 次，量到兩次讀取之間最長 252 ms，負載沒有吃掉這 150 ms 的餘裕。沒有證據可以說明原因，所以沒有修改。

**驗證。**

- Red 的證據：以 root 執行時，修正前的 `FileProbeTest` 2 個、`ResourceCheckerTest` 1 個失敗於「permissions are not enforced」，`InfoRoutesTest` 失敗於 `'root' appears in {..."caller":{"name":"root","role":"admin"}}`；`PermissionsEnforcedTest` 在只開執行緒、不放棄能力時 2 個失敗；`KeystoreSecretStoreTest` 加上前置確認後失敗；`CreateRunPage.test.ts` 給定兩個不同時間後失敗，差異與觀察到的偶發失敗相同（bob 在 ada 之前）。
- 開發容器以 root 執行 `./gradlew cleanTest :engine:cleanPackagedTest :engine:cleanConsoleTest :engine:cleanConsoleTypecheck :engine:cleanConsoleApiDocCheck check` 連續 3 次：每次都只有 `OpenAiBindingMultipartTest`「a slow upload…」失敗（上述第 2 項）。其餘全部通過：accessors 349 個（21 個跳過：真實服務契約 18 個依設計不執行；`BlackHole` 3 個，因為本容器的網路對 TEST-NET-1 立即回應）、analyzer 133、core 88、devkit 181、runner 58、engine 1110（1 個跳過，同為 `BlackHole`）、packagedTest 63，Console 1093 與 API 文件檢查 9 個。
- 以一般使用者（uid 30033，加入 docker 群組，使用獨立的 clone 與 Gradle 使用者目錄）執行同一指令 1 次，結果相同；權限相關的測試在兩種身分下都通過。
- 真實瀏覽器腳本：照 [console/e2e/README.md](../../../../console/e2e/README.md) 的指令原文從零準備（新的資料庫與金鑰庫、兩個 Fake 服務行程、Engine），9 個腳本共 90 個測試全部通過。
