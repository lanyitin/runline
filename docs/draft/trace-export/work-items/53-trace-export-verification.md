# #53 trace-export 整體驗證

本文回答：整條路徑是否符合 ADR-46、文件是否一致。狀態：提案中（2026-10-27）。Issue：#53。決策見 [ADR-46](../../../stable/trace-export/adr/ADR-46-trace-export-resource.md)。相依：#50、#51、#52。

## 行為

以真實 Engine、真實金鑰庫與簡易真實 OTLP 接收端，從管理員建立資源到 pipeline 送出 span，驗證端到端行為，並核對文件。

## 驗收條件

- 端到端：管理員建立並綁定資源後，pipeline 的 span 到達接收端；金鑰不出現在 pipeline、run 紀錄、log、API 回應與 Console。
- 未綁定與未啟用時，pipeline 正常執行且不匯出。
- 接收端不可用或掛住、佇列滿時，run 不被阻塞也不失敗，有計數與警告。
- 金鑰庫重載後依現有流程生效。
- 屬性內容（含長文字）未被 Engine 的 log、metric 與錯誤訊息回顯。
- 使用此契約的 pipeline 判 safe；run 不載入任何 OpenTelemetry 類別。
- 08-api、型別目錄、ADR-019 的型別清單、05-ipc 與 06 等相關文件與實作一致，沒有矛盾；一致性核對結果列於回報。
- 既有型別與既有 pipeline 的測試全數通過。

## 架構約束

- 同 [#51](51-engine-trace-export.md) 的架構約束；本項不新增功能，發現缺陷時回報由架構決定，不自行繞過。
