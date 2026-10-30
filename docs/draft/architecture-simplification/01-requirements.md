# 需求摘要

本文回答：這次架構調整要達成什麼、怎麼算成功、有哪些假設與待確認問題。

## 目標

- 簡化複雜度：修改一個關注點時，需要同時理解與修改的模組、套件數量變少。
- 提升內聚：每個 Gradle 模組與 engine 內的每個功能套件，只擁有一組相關的責任，且責任能用一句話說清楚。

## 角色

- 維護者（目前與 tdd-coder 協作）：主要受益者，修改時讀得少、改得少。
- Pipeline 開發人員：撰寫契約（core）變得更小、更聚焦，不再看到執行期實作類別。
- 管理員與維運：無感；行為、API、組態鍵、部署方式全部不變。

## 範圍

| 範圍內 | 範圍外 |
|---|---|
| Gradle 模組之間的責任分配與依賴方向 | 新功能與新資源型別 |
| engine 內部套件的組織與依賴方向 | HTTP API、錯誤代碼、回應格式的變更 |
| core 與 analyzer 的重複模型 | 資料庫結構、遷移 |
| 依賴方向的自動檢查 | 部署拓撲與產物形態 |
| | Console 前端結構 |

## 成功指標

| 指標 | 現況 | 目標 |
|---|---|---|
| engine 功能套件之間的循環依賴 | 5 組（見 [02](02-current-state.md)） | 0，且建置會在出現時失敗 |
| 修改一個資源型別的設定規則所需觸及的 Gradle 模組 | accessors、engine、devkit（加 core 若改契約） | 只有 accessors（加 core 若改契約） |
| engine 與 devkit 主程式中出現型別專屬設定解析、驗證、檢查的位置 | engine 3 個型別行為檔加 devkit 本機資源組裝 | 0；只剩一處封閉的型別清單 |
| pipeline metadata 模型的定義份數 | 2（core、analyzer）加對照測試 | 1 |
| core 公開但非撰寫契約的類別 | 有（限制 context、錄製 context、錄製器、反射讀取器等） | 0 |

## 非功能需求

- 對外行為零變更：API、錯誤代碼、組態鍵、log 與 metric 名稱、判定結果、run 結果皆不變；既有測試全部通過且數量不減少。
- 發佈 jar 位元組級可重現的保證維持（[ADR-016](../../stable/pipeline-engine/adr/ADR-016-engine-build-info-endpoint.md)）。
- 每個工作項可獨立交付、獨立回退，完成後主幹隨時可發佈。

## 假設

1. 系統維持單實例、單一可部署單位（`engine.jar` 加 `run-runtime/`）。
2. 資源型別集合維持封閉（[ADR-019](../../stable/pipeline-engine/adr/ADR-019-typed-shared-resources.md)），本次不引入外掛機制。
3. 沒有外部 pipeline 直接使用 core 的 `RestrictedContext`、`RecordingContext`、`IoRecorder`、`PipelineMetadataReader`；儲存庫內只有 runner 主程式使用它們。
4. 調整期間不並行開發新功能，避免重整與功能變更互相衝突。

## 待確認問題

| 問題 | 影響 |
|---|---|
| Q1：外部測試專案 `runline-test` 或其他已發佈的 pipeline 是否直接使用 core 的執行期類別（假設 3）？ | 若有，[WI-68](work-items/WI-68-core-contract-only.md) 會造成原始碼不相容，需先公告或保留過渡期 |
| Q2：本次是否同時把 `accessors` 模組改名（例如 `resources`）以反映它擁有「資源型別」而非只有「存取端」？ | 改名提高可讀性，但牽動建置、套件名稱與 run-runtime 外的 jar 名稱；推薦本次不改名（見 [03](03-options.md)） |
| Q3：依賴方向的自動檢查失敗時，是否讓 `check` 失敗（阻擋）而非只警告？ | 推薦阻擋；不阻擋則規則會逐漸失守 |
| Q4：調整期間是否凍結新功能（假設 4）？ | 不凍結則各工作項需更頻繁地與主幹合併，衝突成本上升 |
