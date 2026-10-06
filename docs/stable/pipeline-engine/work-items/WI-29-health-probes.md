# WI-29 存活與就緒探測端點

本文回答：Engine 如何提供 `GET /api/v1/health/live` 與 `GET /api/v1/health/ready`。狀態：已核可（2026-10-05）。相依：WI-08、WI-18。決策見 [ADR-018](../adr/ADR-018-liveness-readiness-probes.md)，契約見 [08-api](../08-api.md)。

## 背景

部署平台需要免認證的探測端點，且資料庫故障不得引發重啟。08-api 已記載兩個端點與關閉期間的例外；本項實作前，`ApiDocumentationTest` 會因文件記載了不存在的路由而失敗。

## 行為與驗收條件

- `live`：免認證，200 `{"status": "up"}`；不依賴資料庫、run 執行緒或連線池；資料庫停止時仍回 200；優雅關閉期間仍回 200。
- `ready`：免認證；全部通過回 200 `{"status": "ready"}`，任一項未通過回 503 `{"status": "not_ready", "checks": {...}}`，`checks` 含 `startup`、`database`、`runtime`、`shutdown`，值為 `ok`／`failed`（`startup` 另有保留值 `pending`），不含原因細節；原因寫入 log 與 metric。
  - `startup` 反映啟動階段（遷移確認、目錄檢查、進行中 run 的中斷標記、待處理觸發的啟動處理、排程器啟動）是否完成。維持現行啟動順序（使用者已決定，[ADR-018](../adr/ADR-018-liveness-readiness-probes.md)「啟動期間的行為」）：這些工作在連接埠綁定之前完成，所以經 HTTP 能收到回應時 `startup` 恆為 `ok`，`pending` 不會經 HTTP 出現；啟動期間端點沒有回應（連線被拒）。不得為了讓 `pending` 可見而把啟動工作移到連接埠綁定之後。
  - `database`：短逾時內可連線並完成輕量查詢；結果可短暫快取（秒級）；服務端逾時小於 3 秒；並發探測不放大成對資料庫的壓力，也不佔用 run 的資源或並行額度。
  - `runtime`：run 執行期目錄仍存在且必要部分齊全；只檢查存在。
  - `shutdown`：收到終止訊號的當下即為 `failed`，`status` 為 `shutting_down`，早於寬限時間開始。
- 兩個端點是 08-api「關閉中」503 規則的例外：關閉期間不回 `shutting_down` 錯誤本文、不關閉連線；其餘請求維持現行行為。
- 就緒為否時，進行中的 run 與排程不被停止，Engine 仍接受請求。
- 回應不快取、不含版本、組態、主機名稱、連線字串或例外原因。
- 沒有 token 即可存取；`ApiDocumentationTest` 通過（兩端點的認證為「無」）。
- 以真實資料庫容器的停止與恢復驗證：`database` 失敗時 `ready` 為 503 而 `live` 為 200，恢復後 `ready` 回 200。以暫時移除執行期目錄的必要部分驗證 `runtime`。`startup` 以單元層級驗證其判斷邏輯（各階段完成與否對應 `pending`／`ok`）；另以真實行程驗證：啟動完成後 `ready` 的 `startup` 為 `ok`，啟動卡住時 `ready` 沒有回應（不回 200 也不回 503）。限度：現行啟動順序下，`startup=pending` 無法經 HTTP 以真實行程觀察，因此不以真實行程驗證它。
- `packagedTest` 驗證打包後的 Engine：送出終止訊號後 `ready` 先轉 503（`shutting_down`）、`live` 仍為 200，進行中的請求照常完成，行程在寬限時間內結束。
- 建置、全部既有測試在 JDK 25 下通過，`ktfmtCheck` 通過；既有測試數量不減少。

## 架構約束

- 不改變既有端點的行為與認證語意；優雅關閉的既有保證（WI-18）不變。
- 免認證端點只限 [ADR-012](../adr/ADR-012-api-authentication.md) 所列。
- 測試使用真實 PostgreSQL（Testcontainers）與真實行程，不使用 Stub 或 Mock；時間由可控制的時鐘提供，不以睡眠換取通過（沿用 WI-26 的逾時保護）。
- 不新增 git hook 或 CI；完成程式碼變更時依專案規則先以 ktfmt 格式化。
