# 資源型別目錄端點（入口文件）

狀態：提案中（2026-10-08）。本頁回答：Console 如何取得 Engine 內建、隨版本發佈的資源型別資訊（`openai-compatible` 端點目錄等），而不在前端保留複本。

## 目標與範圍

- Engine 新增一個唯讀端點，公開執行中 Engine 內建、隨發佈固定的資源型別資訊；Console 的資源表單改以它為唯一來源，移除前端的複本。
- 範圍內：`openai-compatible` 的端點目錄與可設定的請求參數清單、`jdbc-pool` 的資料庫設定檔種類與允許的連線屬性、封閉的型別集合。
- 範圍外：管理員自訂目錄條目、執行期修改目錄、個別資源的設定（仍由 `/api/v1/resources` 提供）、機密資訊（目錄不含任何機密）。

## 關鍵決策結論

| 主題 | 結論 | 詳見 |
|---|---|---|
| 目錄來源 | Engine 內建的型別描述是唯一事實來源；新增 `GET /api/v1/resource-types` 唯讀端點，管理員專屬；Console 不保留複本 | [ADR-021](adr/ADR-021-resource-type-catalog-endpoint.md)（提案中） |

## 九章摘要

1. **需求摘要**：Console 的 `openai-catalog.ts` 與資料庫種類清單是 Engine 目錄的人工複本，Engine 發佈新條目時會不同步。使用者已同意新增目錄端點。假設與待確認問題見 [ADR-021](adr/ADR-021-resource-type-catalog-endpoint.md)「待確認問題」。
2. **現況觀察**：目錄只存在 Engine 內部與 08-api 的文字；Console 端複本的成因與風險記載於 stable [WI-50](../stable/pipeline-engine/work-items/WI-50-console-typed-resource-forms.md)「實作結果」。
3. **方案比較與推薦**：見 ADR-021「方案比較」，推薦涵蓋所有型別的單一型別描述端點。
4. **部署架構**：不適用。端點在既有 Engine 行程內，不新增元件、組態或部署檔。
5. **IPC 模型**：Console 對同源 Engine 的同步 HTTP GET，Bearer 認證；契約見 ADR-021「契約」。
6. **資料模型**：不適用。目錄是 Engine 內建的靜態資料，不進資料庫，不需遷移。
7. **非功能性考量與風險**：見 ADR-021「後果」。
8. **決策記錄**：[ADR-021](adr/ADR-021-resource-type-catalog-endpoint.md)（提案中）。
9. **交接給 tdd-coder 的工作項**：[工作項總覽](work-items/README.md)（提案中）。

## 與 stable 的關係

- 補充 [ADR-019](../stable/pipeline-engine/adr/ADR-019-typed-shared-resources.md) 第 11 點（Console）與決策 13（端點目錄），不取代：目錄內容、啟用規則與 `invalid_endpoint` 拒絕語意不變。
- 核可後的晉升位置：ADR 至 `docs/stable/pipeline-engine/adr/ADR-021-resource-type-catalog-endpoint.md`；WI-55 至 `docs/stable/pipeline-engine/work-items/WI-55-resource-type-catalog-endpoint.md`，並在 stable 工作項總覽新增 WI-55 一列與順序說明；入口文件的決策表新增一列。
