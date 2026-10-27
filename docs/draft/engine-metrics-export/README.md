# engine-metrics-export：Engine 指標匯出與佇列滿警告

本主題回答：Engine 的指標如何能被部署者匯出，以及 trace-export 佇列滿的警告在哪裡看得到。狀態：提案中（2026-10-27）。Issue：#64。來源：[trace-export](../../stable/trace-export/README.md) 整體驗證（#53）發現的兩個可觀測性缺口；補充 [ADR-46](../../stable/trace-export/adr/ADR-46-trace-export-resource.md) 決策 4 的「計數成為可觀測指標」。

## 結論

- Engine 不再把指標匯出寫死為關閉：部署者以 OpenTelemetry 的標準環境變數決定指標匯出；沒有設定時維持不匯出，既有部署行為不變。
- trace-export 的佇列滿警告進 Engine 的 log，維運不必進入 run log 才看得到。
- 這兩項是 #53 驗證出的缺口，不改變 ADR-46 的決策。

## 文件

| 文件 | 內容 | 狀態 |
|---|---|---|
| [#64](work-items/64-engine-metrics-export.md) | 工作項：行為與驗收條件 | 提案中 |

## 待確認問題

1. 指標匯出預設維持不匯出是否符合預期。影響：預設匯出會使沒有收集器的既有部署在關閉時等待匯出逾時（根目錄 README 已要求這類部署明確關閉）；建議維持預設不匯出。
