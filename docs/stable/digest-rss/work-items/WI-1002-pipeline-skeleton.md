# WI-1002 digest-rss pipeline 骨架

本文回答：digest-rss pipeline 專案的骨架與參數要具備什麼。狀態：已核可（2026-10-09）；骨架已實作，參數表於同日修訂（`condition` 必填、新增 `readerModel`、補齊後續項目新增的參數），修訂的落實見 [WI-1018](WI-1018-parameter-revision.md)。

目標：建立可上傳、可由 devkit 除錯、可由 cron 觸發的 pipeline 專案。它是獨立於 runline 儲存庫的外部專案 `runline-digest-rss`（與 runline 同層的目錄），以 Gradle composite build 指向本機 runline 取得 devkit、core、runner 與 analyzer，做法同 [WI-17](../../pipeline-engine/work-items/WI-17-external-test-project.md)；產出自己的 jar；建置與執行使用 JDK 25。

## 驗收條件

1. 宣告一個名為 digest-rss 的 pipeline，參數：

   | 參數 | 規則 | 預設 |
   |---|---|---|
   | `cap` | 正整數（超過 FreshRSS 單次上限時取上限，見 WI-1005） | 100 |
   | `condition` | **必填**，無預設。條件 id：字母、數字、`.`、`_`、`-`，以字母或數字開頭，最長 64 字元。`none` 是明確的值，代表不做主題過濾、全部保留；不填則 run 以呼叫錯誤失敗 | 無 |
   | `readerModel` | 請求讀者與摘要所用的模型 id（用來與回應的模型比對，WI-1007）；`condition` 不是 `none` 時必填；為 `none` 時選填：有給則用於摘要，沒給則 digest 只有事實（不呼叫模型）；有給時一律須非空、不含控制字元、最長 100 字元 | 無 |
   | `markRead` | 只有精確的 `on` 才開啟標已讀；其他任何值（含空、大小寫不同、`1`、`true`）都視為關 | `off` |
   | `retentionDays` | 原始資料保留天數，正整數 | 30 |
   | `quarantineAfter` | 連續未判定幾次後隔離，正整數（WI-1008） | 3 |
   | `quarantineDays` | 隔離天數，正整數（WI-1008） | 7 |
   | `captureMinChars` | 擷取文字少於幾字視為過薄，正整數（WI-1006） | 40 |
   | `captureRequireText` | 擷取結果必須含有的欄位文字，選填（WI-1006） | 空 |

2. 參數不合法或必填的缺漏時，run 以「呼叫錯誤」結束並列出所有問題（不回顯值），不開始任何階段。
3. 資源需求以資源角色宣告，內容與用途註記見 WI-1014。
4. 每個階段在 run log 輸出一行宣告與一行結果；結束時輸出計數（collected、kept、filtered、untouched、refused）與摘要在資料庫中的位置。「零篇」「標記關閉」「模型不可用」是成功結束的正常結果並寫明原因；模型不可用與 FreshRSS 取不到清單同為成功結束；只有呼叫錯誤、組態錯誤與服務憑證被拒為失敗。
5. 取消與逾時協作式生效，並在各長時間階段之間檢查。
6. 上傳後 analyzer 判為 unsafe，並回報原因；在管理員允許前不可執行。
7. 可在 devkit 的 IDE 除錯路徑執行（與 Engine 同一 Runner 路徑）。IntelliJ 的實際操作由使用者實測後回報。

## 架構約束

- 一條專屬平台 thread、無 coroutine 於 pipeline 本體（ADR-008）。
- 專案是消費端：只透過 devkit 的公開入口使用 runline，不複製 runline 的程式碼；需要 runline 儲存庫配合時停下來回報。
- 專案是不同的版本庫，不自行 git commit，除非使用者要求；不放入機密或寫死的個人路徑。
- `condition=none` 加 `markRead=on` 會標記所有可讀文章，加上下載派發會對所有保留文章啟動下載；上線手冊（WI-1011）必須以顯著位置說明，且第一次真實執行標記關閉（WI-1017）。
