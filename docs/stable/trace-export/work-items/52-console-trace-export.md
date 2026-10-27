# #52 Console 與型別目錄支援 trace-export

本文回答：管理員在 Console 如何管理新型別、如何驗收。狀態：已核可（2026-10-27），待派工。Issue：#52。決策見 [ADR-46](../adr/ADR-46-trace-export-resource.md)。相依：#51。

## 行為

1. 型別目錄端點列出 `trace-export` 及其設定欄位（端點、服務名稱、金鑰別名）；Console 依目錄呈現，不另存前端複本。
2. 資源頁能建立、修改、啟用／停用、檢查與刪除此型別的資源，沿用既有資源頁行為。
3. 容量欄位對此型別唯讀；持有者顯示不造成誤導。
4. 金鑰別名只顯示別名，不顯示值；別名缺失時的檢查結果有明確顯示。
5. 可顯示丟棄與匯出失敗計數的概況（來自 #51 的指標），欄位內容不含屬性資料。
6. 多語系字串補齊。

## 驗收條件

- 新型別出現在建立表單，欄位與目錄一致；目錄更新後 Console 不需修改前端。
- 建立、修改、停用、刪除、檢查在真實 Engine 上可操作。
- 容量不可編輯；介面上沒有任何位置顯示機密值。
- 既有型別的資源頁行為不變。
- 版面在既有 Console 的響應式規則下可用（[console-responsive](../../console-responsive/README.md)）。

## 架構約束

- Console 只用同源 `/api/v1`，不改變 Bearer API 契約（[ADR-012](../../pipeline-engine/adr/ADR-012-api-authentication.md)、[ADR-015](../../pipeline-engine/adr/ADR-015-console-frontend.md)）。
- 不信任內容的顯示機制沿用既有規則。

## 實作記錄

- 型別目錄：`parseResourceTypes` 讀取 `trace-export` 的 `fixedCapacity`（`ResourceTypeCatalog.traceExport`；不是 1 以上的整數即為 API 失敗）。表單依目錄呈現：目錄有此型別才提供表單（與 `jdbc-pool`、`openai-compatible` 相同，目錄讀不到時該型別顯示為不可用）；容量由目錄的 `fixedCapacity` 決定，Console 內沒有容量常數。
- 表單（`TraceExportFields.svelte`）：`endpoint`、`serviceName`，機密別名（沿用 `SecretAliasField`，只列金鑰庫的機密別名，不收任何值），端點為 `https` 時才出現憑證別名（`trustAliases`、`clientCertAlias`，沿用 `CertificateAliasesField`；`http` 時不送出，與 Engine 規則一致）。容量欄位唯讀並說明由 Engine 固定；建立時送出目錄的值，修改時不送容量。Engine 的 `invalid_base_url`、`invalid_settings`、`invalid_secret_alias`（別名必填）顯示在對應欄位。
- 卡片：摘要顯示 `endpoint` 與 `serviceName`（以 `PlainText` 顯示，沿用不信任內容的規則）；別名只顯示別名與金鑰庫狀態。固定容量的型別不顯示持有者計量與持有者、等待者區塊，改說明沒有容量限制、run 不會因它等待（避免容量 1000000 的計量造成誤導）。檢查沿用既有的檢查與失敗類別文字（別名缺失為「機密別名不在金鑰庫」，沒有組態金鑰庫也是這一類）。
- 多語系：en 與 zh-TW 補齊型別名稱、設定欄位、說明、容量固定說明、機密別名標籤、憑證說明、兩個 `problem` 的型別專屬文字與卡片說明。
- Fake Engine 與契約：Fake 的目錄、設定規則（端點原樣保留，只將 scheme 轉小寫；服務名稱不修剪；別名必填；固定容量）與真實 Engine 對齊；`contract/resource-types-contract.ts`、`contract/admin-contract.ts` 新增 `trace-export` 的目錄、固定容量與設定規則測試，同一組測試對 Fake 與真實 Engine 執行。真實瀏覽器腳本 `e2e/trace-export.e2e.ts`（建立、修改、檢查、停用、刪除、容量不可編輯、機密標記不出現、手機寬度）。
- 未做：行為 5（丟棄與匯出失敗計數的概況）。#51 的計數只是 OpenTelemetry 指標（`runline.trace_export.*`），Engine 的 API（資源欄位、`usage`）沒有提供這些數字，Console 同源只能讀 `/api/v1`，無法在不改 Engine 的前提下顯示。需要先決定 Engine 是否在資源的 `usage` 加入這些計數（屬 08-api 與 Engine 的變更），再由 Console 顯示；該項不在驗收條件之內。
- 沒有 Console 對選用角色的特別處理：角色是否算問題由 Engine 的綁定 API 回答（`problem` 為 `null`），Console 照顯示。
