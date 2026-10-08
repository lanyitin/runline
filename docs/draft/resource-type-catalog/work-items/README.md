# 工作項總覽：資源型別目錄端點

狀態：提案中（2026-10-08）。本頁回答：本主題要交給 tdd-coder 的工作與先後順序。核可後併入 stable 的[工作項總覽](../../../stable/pipeline-engine/work-items/README.md)，全域架構約束以該頁為準。

| 項目 | 目標 | 相依 |
|---|---|---|
| [WI-55](WI-55-resource-type-catalog-endpoint.md) | 新增 `GET /api/v1/resource-types`，以 Engine 內建的型別描述為唯一來源；Console 改用它，移除前端的端點目錄與資料庫種類複本 | WI-50、WI-52 |

順序：在 WI-52 之後、WI-51 之前。WI-52 會改動 `jdbc-pool` 與 `openai-compatible` 的設定與 Console 表單；WI-51 負責整體驗證與文件一致性核對，應涵蓋本項。
