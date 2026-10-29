# Console 深色模式

本文回答：Console 如何提供深色模式，範圍、切換方式與驗證標準為何。狀態：提案中（Issue #70、#71）。補充 [ADR-015](../../stable/pipeline-engine/adr/ADR-015-console-frontend.md)（Console 前端）與 [WI-33](../../stable/pipeline-engine/work-items/WI-33-console-shell-and-i18n.md)（其中「可選深色」未實作）；沒有與 stable 衝突。

## 1. 需求摘要

- 目標：使用者的作業系統或瀏覽器設為深色時，Console 以深色呈現。
- 範圍內：所有頁面與共用元件、深色 token、對比度與可讀性、驗證。
- 範圍外：手動切換開關與偏好記憶、多組佈景、Engine 與 API 的任何改動、新的視覺設計稿。
- 假設：
  - 切換只跟隨系統（使用者已確認），不提供頁面內開關。
  - 沿用現有設計系統的結構與 token 名稱，只新增深色的值。
- 待確認問題：無。

## 2. 現況觀察

- Console 只有一組淺色 token（`console/src/styles/tokens.css`），元件的顏色幾乎都經由 token 引用。
- 直接寫死顏色的只有 log 檢視的終端面板（`console/src/runs/LogViewer.svelte`），它在淺色主題下也固定為深色。
- 設計稿所在的 `.superdesign/design-system.md` 不在儲存庫中，沒有現成的深色色票。
- 淺色的狀態色已有「顏色不是唯一的標示」的規則（徽章有文字）。

## 3. 方案比較與推薦

| 面向 | 採用 | 未採用 |
|---|---|---|
| 切換方式 | 跟隨系統設定 | 手動開關加記憶偏好（需新增儲存與介面，使用者未要求） |
| 實作層級 | 在 token 層覆蓋深色值，元件不改 | 逐元件加深色樣式（重複且易漏） |

決策見 [ADR-70](adr/ADR-70-console-dark-mode.md)。

## 4. 部署架構

不適用：前端隨 Engine 發佈，沒有新的 process 或組態。

## 5. IPC 模型

不適用：不改變任何 API 契約。

## 6. 資料模型

不適用：沒有資料變更，也不儲存使用者偏好。

## 7. 非功能性考量與風險

| 風險 | 緩解 |
|---|---|
| 深色下文字對比不足 | 文字與背景對比至少 4.5:1，大字與元件邊界至少 3:1；以自動化檢查所有 token 配對 |
| 淺色外觀被改壞 | 淺色 token 與外觀不變，既有測試與 e2e 全部通過 |
| 漏掉寫死顏色的元件 | 驗證項目含全路由的深色畫面檢查，並確認元件不自訂顏色 |
| 圖片、圖示或第三方內容在深色下突兀 | Console 不載入第三方資源；圖示隨文字色，逐頁驗證 |

## 8. 決策記錄

- [ADR-70 Console 跟隨系統提供深色模式（提案中）](adr/ADR-70-console-dark-mode.md)

## 9. 交接給 tdd-coder 的工作項

見[工作項總覽](work-items/README.md)（提案中，核可並晉升到 stable 前不派工）。
