# WI-1011 端到端驗證與上線手冊

本文回答：如何證明整條 pipeline 在 Engine 上與來源行為等價，以及管理員如何上線。狀態：已核可（2026-10-09）；真實環境的首次執行在 [WI-1017](WI-1017-first-real-run.md)。

目標：證明整條 pipeline 在 Engine 上與來源行為等價、資料庫能回答彙整問題，並留下管理員可照做的上線手冊。工作儲存庫：`runline-digest-rss`，手冊放 runline 的文件。真實環境（真實 FreshRSS、Lemonade、rqbit、streamexa、目標站點與目標主機）的執行不在本項，見 WI-1017。

## 驗收條件

1. 以 Fake 的 FreshRSS、rqbit、streamexa、OpenAI 相容服務與本機測試頁，在打包的真實 Engine（含 PostgreSQL）上，經 cron trigger 完整執行至少兩次 run，得到 digest、正確的標記集合與計數；包含資源角色綁定到實際資源（模型、SQLite 資料庫、憑證檔）、管理員允許 unsafe 與白名單設定的完整上線流程。
2. 32 個標註案例在新讀者上以重放方式與來源的判定一致，差異逐案列出並說明（已由 WI-1007 的預設測試涵蓋，本項確認它們在 Engine 上的整合路徑）。
3. 失敗路徑各驗證一次：模型不可用、模型中途消失、FreshRSS 取不到、單篇擷取失敗、下載失敗、標記失敗、run 被取消（Chrome 結束）、標記開關關閉、資料庫寫入失敗、憑證檔缺條目。
4. 彙整驗證：連續多次 run 後，以 SQL 回答下列問題並與預期一致：每次 run 抓到哪些未讀；每篇的圖文判定與引文；哪些文章被派給 rqbit 或 streamexa 及結果；各站點 profile 的失敗率；各規則版本的保留比例。這些查詢記入手冊作為範例。
5. 上線期間確認 pipeline 沒有寫任何檔案（Chrome 的專屬使用者資料目錄除外，它在 run 結束時被刪除）。
6. 上線手冊（draft，核可後進 stable）涵蓋：
   - 白名單與 unsafe：analyzer 對上傳 jar 回報的原因清單（以實際輸出為準）、允許 unsafe 的步驟。
   - 資源與綁定：三個角色各自該選什麼資源、`llm` 資源的 `defaults`（含取樣參數建議值）與已啟用的端點（`chat.completions`、`models.list`）、SQLite 資源設定與備份（連同 `-wal` 檔）、憑證檔格式與三個服務的條目（缺 `rqbit` 或 `streamexa` 條目時，第一次出現保留文章的 run 會失敗）。
   - trigger 參數表（WI-1002）與危險組合警告：`condition=none` 加 `markRead=on` 會標記所有可讀文章並對全部啟動下載。
   - Engine 主機：Chrome、虛擬顯示、`/dev/dri`、根憑證、init、暫存目錄、Engine 必要的環境變數（含 `RUNLINE_WORKSPACE_MAX_BYTES`、`RUNLINE_FAILED_RUN_RETENTION_SECONDS`）、JDK 25。
   - 營運前提：同一個 SQLite 資料庫同一時間只有一個 digest-rss run（以語言模型資源容量 1 序列化；不要把兩個 pipeline 版本的 `llm` 角色綁到不同資源而同時對同一資料庫執行）。
   - 遺留行程處理、保留清除、排錯對照表。
7. 驗證 pipeline 的第三方相依在 Engine 的 run 中的提供方式：pipeline 為薄 jar，只用 JDK、core 與 Kotlin 標準函式庫；在真實 Engine 上傳並執行驗證（不只在 devkit）。
8. 文件一致性核對：本目錄文件與實作一致，不一致處列出。

## 架構約束

- 預設測試不得依賴真實的外部服務；真實環境的測試任務（`realFreshRssTest`、`realReaderTest`、`realDownloadsTest`、`realSitesTest`）沿用既有任務，不在本項執行。
- pi 側舊檔的移除由使用者自行處理，不在本項。
