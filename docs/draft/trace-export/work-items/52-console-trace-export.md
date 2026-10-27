# #52 Console 與型別目錄支援 trace-export

本文回答：管理員在 Console 如何管理新型別、如何驗收。狀態：提案中（2026-10-27）。Issue：#52。決策見 [ADR-46](../../../stable/trace-export/adr/ADR-46-trace-export-resource.md)。相依：#51。

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
- 版面在既有 Console 的響應式規則下可用（[console-responsive](../../../stable/console-responsive/README.md)）。

## 架構約束

- Console 只用同源 `/api/v1`，不改變 Bearer API 契約（[ADR-012](../../../stable/pipeline-engine/adr/ADR-012-api-authentication.md)、[ADR-015](../../../stable/pipeline-engine/adr/ADR-015-console-frontend.md)）。
- 不信任內容的顯示機制沿用既有規則。
