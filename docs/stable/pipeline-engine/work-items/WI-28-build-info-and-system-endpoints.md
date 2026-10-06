# WI-28 建置資訊注入與版本端點

本文回答：Engine 如何在建置時得知自己的版本與 commit，並以 `GET /api/v1/info` 與 `GET /api/v1/system` 提供。狀態：已核可（2026-10-05）。相依：WI-18、WI-27（已驗收）。決策見 [ADR-016](../adr/ADR-016-engine-build-info-endpoint.md)，端點契約見 [08-api](../08-api.md)。

## 背景

Console 要求每頁顯示 Engine 的版本與 commit hash，並以 token 取得呼叫者身分。08-api 已記載兩個端點，在本項實作前，`ApiDocumentationTest` 會因「文件記載了不存在的路由」而失敗；本項完成後轉為通過。

## 行為與驗收條件

- 建置時由 git 取得完整 HEAD hash、HEAD 的 commit 時間、工作樹是否有變更，連同專案版本寫入 `engine.jar` 內的建置資訊資源。版本取自專案版本的單一來源，建置資訊不含時鐘、主機名稱、使用者名稱、絕對路徑或環境變數。
- 「有變更」：已追蹤檔案有未提交的修改，或有未被忽略的未追蹤檔案；被忽略的建置產物不算。
- 取不到 git 資訊時：`commitHash` 為 `unknown`、`dirty` 為 true、`buildTime` 為固定常數時間。
- 提供明確的發佈旗標（本機以 Gradle 屬性指定，名稱與用法寫入 README）：旗標啟用時，`unknown` 或 dirty 使建置失敗並說明原因；一般開發建置不受影響。其他工作項（WI-31、WI-32）沿用同一個旗標。
- `GET /api/v1/info`：免認證，只回 `version`、`commitHash`、`dirty`；不查詢資料庫、不因資料庫故障而失敗；回應不快取。
- `GET /api/v1/system`：Bearer（developer，管理員亦可）；回 `version`、`commitHash`、`dirty`、`buildTime`（commit 時間）、`jdk`、`startedAt`、`uptimeSeconds`、`allowListVersion`、`caller`（`name`、`role`）。無 token 回 401（沿用既有規則），無效 token 回 401。
- 兩個端點的回應都不含 token、環境變數、主機名稱、路徑、組態值。
- 建置資訊的產生不使增量建置每次都重打包：HEAD 與工作樹狀態未變時，下游視為未變。
- `ApiDocumentationTest`（不修改測試邏輯，只在需要時調整）通過：兩個端點與 08-api 一致；`/info` 的認證為「無」，`/system` 為「Bearer（developer）」。
- `packagedTest` 驗證打包後的 Engine：`/info` 的 `commitHash` 與建置當時的 HEAD 相同；乾淨與 dirty 兩種工作樹的 `dirty` 值正確（以暫時建立的真實 git 版本庫建置驗證）；發佈旗標在 dirty 與 `unknown` 時失敗。
- 建置、全部既有測試在 JDK 25 下通過，`ktfmtCheck` 通過；既有測試數量不減少。

## 架構約束

- 不新增執行期的 git 相依；hash 只來自建置時寫入的資源（不得由環境變數提供）。
- 公開端點遵守 [ADR-012](../adr/ADR-012-api-authentication.md) 的免認證清單，不得增加其他欄位。
- 測試使用真實 PostgreSQL（Testcontainers）與真實 git 版本庫，不使用 Stub 或 Mock。
- 不新增 git hook 或 CI；完成程式碼變更時依專案規則先以 ktfmt 格式化。
