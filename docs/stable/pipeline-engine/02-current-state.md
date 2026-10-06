# 現況觀察

本文回答：既有專案提供了什麼、哪些與本需求無關。

## 模組

`settings.gradle.kts` 有 `core`、`runner`、`analyzer`、`devkit`、`engine` 五個模組，各對應一個關注點。套件前綴為 `dev.lawlan.runline`。

| 模組 | 責任 | 依賴 |
|---|---|---|
| core | Pipeline 撰寫契約：宣告、metadata、context 的 IO 類別、限制行為與可選的錄製模式（[WI-01](work-items/WI-01-pipeline-sdk.md)、[WI-04](work-items/WI-04-recording.md)） | 僅 JDK 與 Kotlin 標準函式庫 |
| runner | Runner：獨立 class loader 執行、目錄準備與保留（[WI-02](work-items/WI-02-runner-core.md)、[WI-11](work-items/WI-11-workspace-directories.md)） | core |
| analyzer | 靜態分析與 safe／unsafe 判定、白名單模型、文字格式與預設白名單（[WI-16](work-items/WI-16-analyzer-module.md)） | 主程式僅依賴 JDK 與 Kotlin 標準函式庫；測試期依賴 core，用於編譯測試輸入的 pipeline |
| devkit | 開發入口：在本機執行與除錯 pipeline、顯示 unsafe 判定、錄製 IO 並產生 metadata 提案（[WI-03](work-items/WI-03-runner-dev-entry.md)、[WI-04](work-items/WI-04-recording.md)） | core、runner、analyzer |
| engine | 服務端：Bearer token 認證（[ADR-012](adr/ADR-012-api-authentication.md)）、上傳與探索、run 編排與排程、trigger、共享資源協調、白名單管理、run 與 log 的保留清理、run log 串流、API 文件、PostgreSQL、DI、監控、OpenTelemetry | core、runner、analyzer |

core、runner、analyzer 與 devkit 不依賴 Ktor 或 engine，可獨立於 engine 在 IDE 中使用。部署時 Engine 與 run 執行期目錄（Runner、core、Kotlin 標準函式庫）分開打包（見 [04](04-deployment.md)）。Engine 沒有任何開啟錄製的途徑。

## 外部測試專案

與儲存庫同層的 `runline-test` 是一個外部 pipeline 專案，以 Gradle composite build 取得 devkit 與其相依，含範例 pipeline 與 IDE 執行設定，用於實測開發入口（[WI-17](work-items/WI-17-external-test-project.md)）。它不屬於本儲存庫。

## 可直接利用

- PostgreSQL 與 Testcontainers：Engine 資料層與整合測試。
- OpenTelemetry：run 層級 trace 與 metric。
- Ktor WebSocket：run log 即時串流。
- Swagger：上傳與管理 API 文件。

## 開發環境

`.devcontainer/` 提供 devcontainer 與 compose。所有 JVM 模組與映像的 JDK 基準為 25。資料庫測試需要 Docker 相容的容器環境（見 [dev-environment](../dev-environment.md)）。測試等待皆有逾時保護（[WI-26](work-items/WI-26-test-timeouts.md)）。

## 已知待處理

- 系統為單實例；排程、run 佇列與共享資源狀態都假設單一行程（見 [07](07-nfr-risks.md)）。
- 既有 Engine 部署的白名單不會自動取得新的預設條目，需由管理員以白名單管理 API 新增（見 [WI-25](work-items/WI-25-allow-list-format-and-default-refinement.md)）。
- 白名單重判逐一分析所有已儲存的 jar，耗時與總大小成正比，期間持有白名單的互斥鎖（見 [07](07-nfr-risks.md)）。
- 錄製只涵蓋經由 context 的 IO，且只涵蓋走過的路徑；直接使用 JDK 的 IO 類別不被錄製（見 [ADR-004](adr/ADR-004-recording.md)）。
