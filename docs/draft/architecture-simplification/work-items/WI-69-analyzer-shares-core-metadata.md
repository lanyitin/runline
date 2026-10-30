# WI-69 analyzer 共用 core 的 metadata 模型

本文回答：如何讓 pipeline metadata 模型只有一份，同時維持位元組讀取的單一來源，如何驗收。狀態：提案中（2026-10-30）。相依：WI-68。決策見 [ADR-025](../adr/ADR-025-analyzer-shares-core-metadata.md)。

## 背景

core 與 analyzer 各有一份 metadata 模型，以對照測試保持一致（[02](../02-current-state.md) 發現四）。

## 行為與驗收條件

- metadata 模型（名稱、參數、檔案範圍與模式、網路與行程限制、資源宣告與型別）只在 core 定義一次；analyzer 的公開結果使用 core 的模型。預設值只在 core 定義一次。
- 位元組層級的 metadata 讀取仍只在 analyzer，不載入、不執行 pipeline 類別，也不觸發其靜態初始化（[ADR-003](../../../stable/pipeline-engine/adr/ADR-003-jar-and-discovery.md)）。
- 一致性驗證：對同一個真實編譯的 pipeline，analyzer 以位元組讀出的 metadata 與 run 期讀出的 metadata 相等；涵蓋 [WI-19](../../../stable/pipeline-engine/work-items/WI-19-unified-metadata-reading.md) 列出的每一種宣告方式與預設值。被取代的兩模型對照測試在回報中列出。
- engine 與 devkit 只使用一種 metadata 型別；上傳探索、儲存內容、API 回應、devkit 的判定與提案輸出不變。
- WI-65 的模組方向檢查更新為「analyzer 只依賴 core」，並通過。
- 符合[總覽全域約束](README.md#全域約束適用本總覽所有項目)的全部條件。

## 架構約束

- analyzer 主程式只可依賴 core，且只使用 metadata 模型、宣告註解與預設值；不依賴 runner、accessors、engine、Ktor、OpenTelemetry、資料庫。
- 分析與判定的行為不變；core 仍以套件名稱被識別為受信任的葉節點。
- 儲存格式與 API 回應不變，不需要資料遷移。
