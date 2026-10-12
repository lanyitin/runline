# 2 現況觀察

狀態：已核可（2026-10-11）。本文回答：runline 現行機制哪些可直接使用，哪些與需求衝突或缺口。

| 面向 | 現況（stable） | 對本案的影響 |
|---|---|---|
| webhook | 只當觸發訊號，請求本文不讀取、不影響參數；參數由 trigger 固定（ADR-005、README「Triggers」） | 衝突。需 ADR-036 與 WI-1400 |
| 去重與認證 | per-trigger 密鑰、`X-Runline-Delivery-Id` 去重 | 沿用，不變 |
| unsafe pipeline | 管理員逐版本允許（ADR-006）；digest-rss 以 unsafe 呼叫主機 Chrome（ADR-022） | 沿用模式 |
| 檔案範圍 | context 只有 pipeline 共享目錄與 run 私有目錄；`file` 資源只對應單一檔案 | 收件根目錄不在範圍內，故以 unsafe 與部署設定處理（ADR-037） |
| LLM | `openai-compatible` 資源：目錄式端點、對話含 JSON；音訊轉錄為多部分上傳，檔案部分可為位元組（ADR-019、WI-53）；並行與逾時由資源管理 | 可用；圖片輸入與請求大小上限須驗證（WI-1404） |
| 資料庫 | `jdbc-pool` 含 `sqlite` 種類（WI-1003）；資源角色與綁定（ADR-025） | 沿用 digest-rss 的資料保存方式 |
| 模型身分 | digest-rss 的 ADR-028 處理 Lemonade 以檔案路徑回報模型 | 沿用其做法，避免模型 id 比對錯誤 |
| 部署 | Docker／systemd；瀏覽器為選配覆蓋層（`deploy/browser.md`） | 以同樣方式新增選配的「轉錄執行環境」（WI-1402） |
| 保留 | run 與 log 預設保留 30 天 | 資料庫內容由 pipeline 自管保留 |
| 專案位置 | 先例：`runline-digest-rss` 為外部專案，composite build | 沿用 |

資源取得語意：Engine 在 run 開始前取得宣告的全部資源，到 run 結束才釋放（ADR-007）。Lemonade 資源容量 1，因此整個 run 期間獨佔。
