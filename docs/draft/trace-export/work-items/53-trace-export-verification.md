# #53 trace-export 整體驗證

本文回答：整條路徑是否符合 ADR-46、文件是否一致。狀態：提案中（2026-10-27）；取代 [docs/stable/trace-export/work-items/53-trace-export-verification.md](../../../stable/trace-export/work-items/53-trace-export-verification.md)（新增真實 Engine 契約測試與真實瀏覽器腳本）。Issue：#53。決策見 [ADR-46](../../../stable/trace-export/adr/ADR-46-trace-export-resource.md)。相依：#50、#51、#52。

## 行為

以真實 Engine、真實金鑰庫與簡易真實 OTLP 接收端，從管理員建立資源到 pipeline 送出 span，驗證端到端行為，並核對文件。同時執行 #52 留下的真實 Engine 契約測試與真實瀏覽器腳本。

## 驗收條件

- 端到端：管理員建立並綁定資源後，pipeline 的 span 到達接收端；金鑰不出現在 pipeline、run 紀錄、log、API 回應與 Console。
- 未綁定與未啟用時，pipeline 正常執行且不匯出。
- 接收端不可用或掛住、佇列滿時，run 不被阻塞也不失敗，有計數與警告。
- 端點回轉址時不被跟隨，丟棄有計數。
- 金鑰庫重載後依現有流程生效。
- 屬性內容（含長文字與大型清單）未被 Engine 的 log、metric 與錯誤訊息回顯，Engine 側保留量有界。
- 使用此契約的 pipeline 判 safe；run 不載入任何 OpenTelemetry 類別。
- 真實 Engine 契約測試（Console 專案的 `test:contract`，含 #52 新增的 `trace-export` 測試）通過，結果與 Fake 一致。
- 真實瀏覽器腳本 `console/e2e/trace-export.e2e.ts` 通過：建立、修改、檢查、停用、刪除、容量不可編輯、機密標記不出現在頁面、手機寬度版面可用。
- 08-api、型別目錄、ADR-019 的型別清單、05-ipc 與 06 等相關文件與實作一致，沒有矛盾；一致性核對結果列於回報。
- 既有型別與既有 pipeline 的測試全數通過。

## 前置條件

真實 Engine 契約測試與真實瀏覽器腳本需要已打包的 Engine、PostgreSQL 與 Chrome；依專案文件（WI-57）的前置條件準備，缺少時回報而不跳過。

## 架構約束

- 同 [#51](../../../stable/trace-export/work-items/51-engine-trace-export.md) 的架構約束；本項不新增功能，發現缺陷時回報由架構決定，不自行繞過。
