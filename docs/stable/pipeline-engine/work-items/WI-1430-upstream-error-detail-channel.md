# WI-1430 openai-compatible 的 4xx 錯誤說明通道

本文回答：Engine 與存取端如何把模型服務 4xx 的錯誤說明（`error.type`、`error.message`）有限度地交給 pipeline（[ADR-043](../adr/ADR-043-upstream-error-detail.md)）。狀態：已核可（2026-10-13）。相依：無。儲存庫：runline。

## 行為與驗收條件

1. `openai-compatible` 資源收到 4xx 時，pipeline 取得的 `ResourceAccessException` 帶有 `error.type` 與 `error.message` 兩個字串（沒有或無法解析時為空），與既有的 `status` 並列。
2. 取值規則依 ADR-043 決策 1 至 4：只在 4xx；讀取有上限的前段；只取這兩個欄位；長度上限（`error.message` 300 字元、`error.type` 64 字元）、截斷標示、只留可列印文字；套用既有的機密遮蔽。
3. 5xx、逾時、連線失敗、2xx、其他資源型別的行為與訊息完全不變；`ResourceAccessException` 的既有訊息文字不變（說明作為欄位，不拼入固定訊息）。
4. 欄位跨越 Engine 與 run 之間的 IPC；舊 run 與新 Engine、新 run 與舊 Engine 互相相容（缺漏視為空）。`devkit` 的資源有相同行為。
5. 完整本文不傳給 pipeline、不寫入 run log、不改變 Engine log 的既有內容。
6. 以真實的假 OpenAI 服務驗證：4xx 帶標準錯誤格式、4xx 帶非 JSON 本文、4xx 本文過長、4xx 說明含已知機密值、4xx 說明含控制字元與換行、5xx；每種的結果明確且不同；既有測試維持通過。
7. 文件：ADR-019 第 181 行與 05-ipc 第 50 行的相關描述改為指向 ADR-043（本項一併修改），08-api 與 devkit 指南若描述存取端錯誤，同步更新；同一資訊只寫一處。

## 架構約束

- `core` 只允許 JDK 型別跨越 `ResourceLink`。
- 不新增資源設定欄位、不新增 pipeline 參數。
- 嚴格 TDD；測試不使用 Stub 或 Mock，使用真實 HTTP 服務的自製簡易實作。
