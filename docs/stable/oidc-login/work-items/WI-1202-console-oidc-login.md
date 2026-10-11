# WI-1202 Console 的 OIDC 登入

本文回答：Console 如何提供「使用 authentik 登入」，並維持與憑證種類脫鉤的認證邊界。狀態：已核可（2026-10-10）。相依：WI-1201。決策見 [ADR-029](../adr/ADR-029-oidc-login-with-server-session.md)。工作儲存庫：runline（console）。

## 驗收條件

1. **登入頁**：向登入方式查詢得知 OIDC 啟用時，顯示「使用 authentik 登入」按鈕（名稱固定，查詢只回是否啟用）；token 輸入仍可用；OIDC 未啟用時外觀與現在相同。外殼（版本與 hash、語系切換）不變。文字全部經多語系機制（zh-TW、en）。
2. **登入流程**：按鈕以頁面導覽進入登入起點，並帶目前想去的頁面（本站相對路徑）；登入完成回到該頁；Console 不接觸授權碼、ID token 或任何憑證。
3. **登入狀態**：以工作階段查詢取得名稱與角色，導覽依角色顯示（管理員專屬頁僅管理員可見，直接輸入網址也被擋）。OIDC 登入下，畫面不持有任何憑證，也不寫入 sessionStorage 或 localStorage；API 呼叫附帶 cookie 與改狀態請求的 CSRF token，經認證邊界的單一出口。
4. **登出**：呼叫登出，成功後回登入頁。其他分頁在下次請求得到 401 時回到登入頁並保留原目標頁。
5. **401 統一處理**：任何 API 回 401，OIDC 與 token 兩種方式都回到登入頁並保留原目標頁，登入後返回。
6. **錯誤顯示**：callback 失敗帶回的代碼（取消、狀態逾時、驗證失敗、`not_authorized`、`name_conflict`、authentik 不可用）以對應的多語系文字顯示；`not_authorized` 說明需要的群組與向管理員申請，`name_conflict` 說明需聯絡管理員；`./gradlew :engine:consoleApiDocCheck` 對新錯誤代碼通過。登入起點導回的 `too_many_logins`（登入過多）與 `provider_unavailable`，以及 callback 的 `refresh_unavailable`，同樣以對應的多語系文字顯示：`too_many_logins` 說明稍後再試；`refresh_unavailable` 說明是登入服務設定問題、請聯絡管理員，不顯示技術細節。
7. **認證邊界**：畫面、路由、資料存取仍不直接接觸憑證，由靜態檢查或測試強制；token 方式的行為（sessionStorage、BroadcastChannel）不變。
8. **安全**：CSP 與 [WI-37](../../pipeline-engine/work-items/WI-37-console-security-verification.md) 的檢查維持通過，不放寬 CSP；不使用 `{@html}` 等禁用機制。
9. **驗證方式**：單元測試納入 `check`；真實 Engine、真實 PostgreSQL、真實 OIDC 提供者與真實瀏覽器引擎的腳本手動執行（`npm run e2e`），涵蓋：管理員登入、開發人員登入（看不到管理員頁）、兩群組皆無被拒、登出後各分頁回登入頁、token 登入仍可用，並在回報中附上結果。
10. **文件**：Console 相關的 README 段落、`console/e2e/README.md` 更新。

## 架構約束

- 只改 Console；API 契約依 [05-ipc.md](../05-ipc.md)。
- 不新增 CI；不使用 Stub 或 Mock。
