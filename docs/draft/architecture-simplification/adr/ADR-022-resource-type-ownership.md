# ADR-022 資源型別的知識集中於 accessors

狀態：提案中（2026-10-30）。補充 [ADR-019](../../../stable/pipeline-engine/adr/ADR-019-typed-shared-resources.md) 與 [ADR-021](../../../stable/pipeline-engine/adr/ADR-021-resource-type-catalog-endpoint.md)，不改變其語意。回答：一個資源型別的規則應由哪個模組擁有，engine 與 devkit 如何使用。

## 背景

型別的規則分散在 core（契約）、accessors（設定解析與實體）、engine（每型別一個行為與可觀測）、devkit（依型別分支組裝），見 [02](../02-current-state.md) 發現一。engine 與 devkit 各自把設定接到實體，一致性只靠紀律維持。

## 決策

1. **accessors 是資源型別的唯一擁有者**。每個型別在 accessors 內提供一份完整的型別定義，涵蓋：設定的驗證與問題描述、正規化、對外描述（[ADR-021](../../../stable/pipeline-engine/adr/ADR-021-resource-type-catalog-endpoint.md) 端點的內容來源）、併發上限的推算、檢查、實體與存取端的建立、失效與回收、可觀測事件。
2. **封閉清單只有一處**：accessors 內列出全部型別的定義；engine 與 devkit 透過這份清單取得型別，主程式中不出現依型別名稱分支的設定解析、驗證或檢查。
3. **engine 只做與型別無關的協調**：容量與等待、儲存、API、授權、稽核、金鑰庫、OpenTelemetry。機密由 engine 依型別定義聲明的別名需求提供；可觀測事件由 engine 轉成既有的 metric 與 trace。
4. **devkit 使用同一份型別定義**：本機組態的驗證結果與 engine 對同一份設定的驗證結果相同；機密與可觀測由 devkit 以本機方式提供。
5. **core 的存取端契約不變**：pipeline 看到的介面、錯誤類別與邊界型別不因本決策改變。

## 取捨

- 得到：改一個型別只動 accessors（改契約時加 core）；engine 與 devkit 的行為一致由結構保證，不靠紀律。
- 付出：accessors 需要表達「需要機密」與「可觀測事件」這兩個抽象，而不直接依賴金鑰庫與 OpenTelemetry；一次性的搬移成本。
- 不做：外掛或動態註冊；型別集合仍封閉（ADR-019）。

## 後果

- accessors 的責任由「存取端」擴大為「資源型別」，模組名稱是否調整見 [03](../03-options.md) 與待確認 Q2。
- engine 的 `resource` 功能縮小為協調；型別專屬的可觀測只保留轉換。
- accessors 不得依賴 engine、Ktor、OpenTelemetry、資料庫存取層或金鑰庫實作（依賴檢查見 [ADR-023](ADR-023-engine-feature-slices.md)）。
