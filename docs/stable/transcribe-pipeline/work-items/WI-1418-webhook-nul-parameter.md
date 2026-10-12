# WI-1418 呼叫端參數含 NUL 字元回 400（runline Engine）

狀態：已核可（2026-10-12），未開始。儲存庫：R。相依：WI-1400。本文回答：webhook 的呼叫端參數值含 NUL 字元時 Engine 如何回應，以及一項 log 警告的釐清。

## 驗收條件

1. webhook 呼叫端提供的參數值，以及手動 run 的參數值，含 U+0000（NUL）時，Engine 回 400（webhook 為既有的參數不合規錯誤碼，指出參數名稱，不回值）；不建立 run，不消耗 delivery 識別碼，不寫入觸發紀錄，Engine log 沒有 ERROR。
2. 其他控制字元的行為維持現狀（不為此項擴大規則）；若發現同類的資料庫拒絕字元，列入回報，不自行擴大。
3. 釐清並處理：每個 run 結束時（含成功）Engine 記 WARN `left threads running: [jdbc-statement-…]`。查出來源；若是 Engine 或 `sqlite` 種類存取端的缺陷，修正並以測試驗證不再出現；若是第三方函式庫的既定行為，在 `08-api.md` 或部署文件記錄其意義與無害的理由，不修改。
4. `ApiDocumentationTest` 與 trigger 相關測試通過；新行為有先紅燈的測試；文件（`08-api.md` 的 webhook 與 run 建立段）同步。

## 架構約束

不改變認證與其他錯誤的行為；只改本項範圍。工作樹中其他進行中的變更（pipeline-views 等）不得更動。
