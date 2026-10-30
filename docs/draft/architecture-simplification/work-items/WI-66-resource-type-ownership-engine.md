# WI-66 資源型別知識集中到 accessors（engine 側）

本文回答：資源型別的規則如何由 accessors 唯一擁有，engine 只保留與型別無關的協調，如何驗收。狀態：提案中（2026-10-30）。相依：WI-70。決策見 [ADR-022](../adr/ADR-022-resource-type-ownership.md)。

## 背景

型別規則分散在 accessors 與 engine 的每型別行為與可觀測中（[02](../02-current-state.md) 發現一）。

## 行為與驗收條件

**歸屬**
- 每個型別（`counter`、`file`、`jdbc-pool`、`openai-compatible`）在 accessors 內有一份完整的型別定義，涵蓋 ADR-022 第 1 點列出的全部責任。
- accessors 內只有一處封閉清單列出全部型別；engine 主程式取得型別的唯一途徑是這份清單。
- engine 主程式中沒有依型別名稱分支的設定解析、驗證、正規化、描述、併發上限推算或檢查邏輯；engine 的 resource 功能只負責容量與等待、儲存、API、授權、稽核、提供機密、把可觀測事件轉成 metric 與 trace。回報中列出搬移前後各責任所在的位置對照。

**機密與可觀測**
- 型別定義以抽象方式聲明需要哪些機密別名與憑證別名，由 engine 從金鑰庫提供；機密值不出現在型別定義的對外描述、問題描述與錯誤中。
- 型別發出的可觀測事件由 engine 轉換，metric 與 span 的名稱、標籤、單位與現況完全相同。

**行為不變**
- 資源的建立、修改、檢查、刪除、取得、存取端操作、失效與回收、`GET /api/v1/resource-types` 的回應，對同一輸入與現況完全相同；既有資源相關的驗收測試（WI-40 至 WI-58 範圍）全部通過。
- [WI-51](../../../stable/pipeline-engine/work-items/WI-51-secret-non-leak-verification.md) 的機密不外洩驗證全部通過。
- 符合[總覽全域約束](README.md#全域約束適用本總覽所有項目)的全部條件。

## 架構約束

- accessors 不依賴 engine、Ktor、OpenTelemetry、Flyway、engine 的資料存取層或金鑰庫實作；由 WI-65 的依賴檢查涵蓋。
- 型別集合封閉，不引入註冊、外掛或以反射探索型別（[ADR-019](../../../stable/pipeline-engine/adr/ADR-019-typed-shared-resources.md)）。
- core 的存取端契約與 Engine 與 run 邊界的型別不變。
- 不改 devkit 的行為（屬 [WI-67](WI-67-resource-type-ownership-devkit.md)）；devkit 在本項只需持續通過既有測試。
