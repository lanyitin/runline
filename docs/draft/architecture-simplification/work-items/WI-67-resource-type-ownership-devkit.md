# WI-67 devkit 使用同一份資源型別定義

本文回答：開發入口如何以 accessors 的型別定義提供本機資源，並保證與 engine 的結果一致，如何驗收。狀態：提案中（2026-10-30）。相依：WI-66。決策見 [ADR-022](../adr/ADR-022-resource-type-ownership.md) 第 4 點。

## 背景

devkit 依型別分支組裝本機資源，自行把本機組態接到各型別的設定解析（[02](../02-current-state.md) 發現一）。

## 行為與驗收條件

- devkit 主程式中沒有依型別名稱分支的設定解析、驗證或實體建立；本機資源一律透過 accessors 的封閉型別清單建立。
- 一致性：以同一組資源設定案例（每個型別至少涵蓋有效設定、每一種問題類別、機密別名缺少）分別交給 devkit 的本機組態與 engine 的資源建立，兩者對有效與否的判斷、問題類別一致；此驗證在 accessors 或可同時看到兩者的測試範圍內，不使 devkit 依賴 engine。
- 本機的機密與憑證以 devkit 現行的本機來源提供，行為不變；本機組態格式與環境變數不變。
- 開發入口的對外行為不變：[devkit 指南](../../../stable/devkit-guide.md)描述的指令、輸出、錄製與 metadata 提案、判定顯示全部維持；既有 devkit 測試全部通過。
- 符合[總覽全域約束](README.md#全域約束適用本總覽所有項目)的全部條件。

## 架構約束

- devkit 不依賴 engine、Ktor、資料庫存取層（沿用 [WI-03](../../../stable/pipeline-engine/work-items/WI-03-runner-dev-entry.md)）。
- 錄製只在開發入口可開啟（[ADR-004](../../../stable/pipeline-engine/adr/ADR-004-recording.md)）。
