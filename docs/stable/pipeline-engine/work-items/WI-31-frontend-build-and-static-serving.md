# WI-31 前端建置接進 Gradle，Engine 提供靜態檔與 fallback

本文回答：Console 的前端專案如何由 Gradle 建置並打包進 `engine.jar`，Engine 如何在 `/` 提供它而不遮蔽 API。狀態：已核可（2026-10-05）。相依：WI-28（發佈旗標）、WI-30。決策見 [ADR-015](../adr/ADR-015-console-frontend.md)，路由規則見 [08-api](../08-api.md)「Console 的靜態檔與入口頁」。

## 背景

Console 是 Svelte 靜態 SPA（Vite 建置），隨 Engine 發佈。本項建立前端專案的骨架（一個最小的入口頁，證明接線正確；實際畫面在 WI-33 之後）、建置接線、Engine 的靜態檔提供與 SPA fallback。

## 行為與驗收條件

**建置接線**
- 前端專案位於版本庫內與 Kotlin 模組分開的位置（具體位置與是否為 Gradle 模組由本項決定並回報）；相依以鎖定檔固定並以鎖定檔安裝；建置不在執行階段下載相依。
- 建置 Engine 時 Gradle 先建置前端，產物納入 `shadowJar`，因此 `engineDistribution` 與 `packagedTest` 自動含 Console。前端輸入（原始碼、鎖定檔、建置設定）變動才重建，未變動時不重跑。
- 前端的測試與型別檢查納入 `check`；不存在只能在 CI 執行的步驟。
- 提供「略過前端建置」開關（僅本機）：略過時 Engine 仍可啟動，`/` 回 404，API 不受影響；發佈旗標（WI-28）或 `packagedTest` 遇到此開關時直接失敗，不靜默產出沒有 Console 的產物。
- `run-runtime/` 不含任何 Console 資源（以 `packagedTest` 驗證）；Engine 啟動檢查與 run 的隔離不受影響。
- 開發模式：Vite 開發伺服器把 `/api`（含 WebSocket）代理到本機 Engine；代理設定不進入產物。
- 前端產物不含任何環境相關值，API 呼叫只用同源相對路徑。

**靜態檔與 fallback**
- `GET /` 回傳入口頁；對應到實際資源檔的 `GET` 回傳該檔；不在 `/api` 與 `/openapi` 之下、且不像資源檔的 `GET` 路徑回傳入口頁（200）。
- `/api/**` 與 `/openapi/**` 不被遮蔽：`/api` 下不存在的路徑回 404（不回傳入口頁）；無 token 的 Bearer 端點仍回 401；非 GET 請求不適用 fallback；既有端點行為不變。
- 雜湊命名的資源檔可長期快取，入口頁不快取。
- 回應帶限制為同源的內容安全政策（不載入第三方腳本與字型、不允許內嵌腳本）、禁止被嵌入框架與 `nosniff`；Engine 不啟用 CORS。具體值回報，供 WI-37 驗證。
- 優雅關閉期間靜態檔請求遵守既有的 503 規則。

**與 ApiDocumentationTest**
- 靜態與 fallback 路由是 `GET` 且免認證；測試不得被放寬為「放行所有未記錄路由」：`/api/**` 下任何路由仍必須逐一記錄，新增 `/api` 路由未記錄即失敗。Console 路由的處置（逐一記錄，或以單一明確的掛載點視為一組）由本項選擇、與 08-api「Console 的靜態檔與入口頁」一節一致，並回報所做的測試調整。

建置、全部既有測試（含 `packagedTest`）在 JDK 25 下通過，`ktfmtCheck` 通過；既有測試數量不減少。

## 架構約束

- 不改變 API 契約與既有認證語意；Console 靜態檔免認證僅限 [ADR-012](../adr/ADR-012-api-authentication.md) 所列。
- 測試使用真實 PostgreSQL（Testcontainers）與真實的打包後 Engine 行程，不使用 Stub 或 Mock。
- 不新增 git hook 或 CI；Kotlin 程式碼依專案規則先以 ktfmt 格式化。
