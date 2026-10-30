# WI-68 core 只保留撰寫契約

本文回答：core 應剩下什麼、執行期實作移到哪裡、如何驗收。狀態：提案中（2026-10-30）。相依：無。派工前須先確認待確認問題 Q1（[01](../01-requirements.md)）。決策見 [ADR-024](../adr/ADR-024-core-contract-only.md)。

## 背景

core 公開了只有 runner 使用的執行期實作，擴大了受信任面（[02](../02-current-state.md) 發現三）。

## 行為與驗收條件

**core 的公開面**
- core 的公開面只含 ADR-024 第 1 點所列的撰寫契約。限制 context、錄製 context、IO 錄製器、以反射讀取 metadata、存取端在 run 側的轉接，都不再是 core 的公開或內部內容。回報中列出 core 搬移前後的公開類別清單。
- 執行期實作位於 runner 中隨 run class loader 載入的部分；run-runtime 的組成（runner、core、Kotlin 標準函式庫的 jar）不變，`:engine:packagedTest` 的隔離驗證通過。

**判定**
- 只使用撰寫契約的 pipeline，判定與現況相同（既有 analyzer 與上傳測試通過）。
- 以真實編譯的測試 pipeline 驗證：參照 runner 中任一執行期類別的 pipeline 被判 unsafe，原因指出該參照。

**行為不變**
- run 的限制、錄製、存取端、metadata 讀取行為不變；Engine 仍沒有開啟錄製的途徑；開發入口的錄製與提案不變。
- 符合[總覽全域約束](README.md#全域約束適用本總覽所有項目)的全部條件；WI-65 的模組方向檢查通過（core 不依賴任何專案模組）。

## 架構約束

- 每次 run 使用獨立 root class loader，其上層僅為 JDK（[ADR-001](../../../stable/pipeline-engine/adr/ADR-001-isolated-classloader.md)）；Engine 與 run 邊界只傳 JDK 內建型別。
- core 仍只依賴 JDK 與 Kotlin 標準函式庫；仍以套件名稱被分析器視為受信任的葉節點（[ADR-002](../../../stable/pipeline-engine/adr/ADR-002-context-and-unsafe.md)）。
- 預設白名單不得因本項加入 runner 的套件。
