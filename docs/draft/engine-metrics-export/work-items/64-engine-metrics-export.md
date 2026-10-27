# #64 Engine 指標匯出與 trace-export 佇列滿警告進 Engine log

本文回答：要達成什麼行為、如何驗收。狀態：提案中（2026-10-27）。Issue：#64。背景見 [README](../README.md)，上游決策見 [ADR-46](../../../stable/trace-export/adr/ADR-46-trace-export-resource.md)。相依：無（#50 至 #53 已完成）。

## 行為

1. 部署者可用 OpenTelemetry 的標準環境變數（指標匯出器、端點、協定）讓 Engine 匯出指標；Engine 不再以程式碼覆寫此設定。
2. 沒有設定指標匯出器時，Engine 不匯出指標，與現行行為相同；沒有收集器的既有部署不受影響，關閉時不因指標匯出而等待。
3. 開放匯出後，既有指標（含 `runline.trace_export.*` 與 `runline.secrets.reloads`）都能在接收端看到，標籤與名稱不變。
4. trace-export 的 `queue_full` 警告寫入 Engine 的 log（維運可見）；仍保留限速，避免洗版。
5. 限速的「先前未記錄的警告個數」以該警告所屬的限速區間為單位計算，不跨 run 累積成誤導的大數字。
6. 警告內容維持只含資源名稱與 span 個數，不含屬性內容與金鑰。
7. 根目錄 README、07 與 08-api 中關於指標與警告位置的描述與實作一致。

## 驗收條件

- 設定指標匯出器與端點後，打包的 Engine 向真實 OTLP 接收端送出指標，接收端看得到 `runline.trace_export.*` 的各項計數。
- 沒有設定時，Engine 不送出任何指標請求，且關閉時間不受影響。
- 佇列滿（接收端掛住並大量提交）時，Engine 的 log 出現 `queue_full` 警告，且同一時間窗內被限速抑制的個數正確；run 不被阻塞也不失敗。
- 警告、指標與 log 不含屬性內容與金鑰（以標記字串驗證，沿用 #53 的做法）。
- 既有指標與既有測試行為不變。
- 文件與實作一致。

## 架構約束

- 不改變 trace-export 的資料路徑、緩衝與丟棄語意（[ADR-46](../../../stable/trace-export/adr/ADR-46-trace-export-resource.md) 決策 4）。
- 機密只在金鑰庫；不新增任何會外洩金鑰或屬性內容的輸出。
- 預設行為不變（不匯出指標）。
- 測試的外部系統用真實或簡易真實實作，不用 Stub 或 Mock。
