# WI-1201 API 雙認證與 CSRF 防護

本文回答：API 如何同時接受 Bearer 與工作階段，並防止 cookie 帶來的跨站請求。狀態：已核可（2026-10-10）。相依：WI-1200。決策見 [ADR-029](../adr/ADR-029-oidc-login-with-server-session.md)。工作儲存庫：runline（engine）。

## 驗收條件

1. **解析順序**：請求帶 `Authorization` 標頭時只依 Bearer，無效即 401，不退而使用 cookie；沒有該標頭時依工作階段 cookie；兩者皆無或無效回 401（Bearer 的 `WWW-Authenticate` 行為維持）。
2. **語意一致**：兩種來源解析出同樣的名稱與角色；所有端點的角色要求、可見範圍、擁有權、稽核欄位、[ADR-020](../../pipeline-engine/adr/ADR-020-per-uploader-artifact-versions.md) 的不洩漏規則不分來源。以同一名稱的 OIDC 使用者驗證：看不到他人的版本，回應與不存在相同。
3. **CSRF**：工作階段認證的 POST、PUT、PATCH、DELETE 須帶工作階段專屬的 CSRF token（標頭）且 `Origin` 與對外網址同源，缺少或不符回 403 且不產生任何副作用；GET 不改變狀態；Bearer 請求不受此限。跨站情境（惡意頁面對 Engine 發送改狀態請求）被擋。
4. **範圍限制**：Webhook 入口維持原認證；WebSocket log 串流維持只接受 Bearer，帶 cookie 而無 Bearer 時回 401；免認證端點清單只新增登入方式查詢、登入起點、callback。
5. **呼叫者資訊**：`GET /api/v1/system` 的呼叫者標示認證來源（token 或 oidc）；不回傳任何憑證。
6. **路由列舉測試**：列舉所有已註冊路由，驗證除明列的免認證端點外，無認證一律被拒；每個需認證的端點在 Bearer 與工作階段下的角色判斷一致；改狀態端點在工作階段下都要求 CSRF。新增路由漏掛則測試失敗。
7. **工作階段使用時間**：更新「最近使用時間」降頻，一般請求不因此每次寫入；閒置期限仍正確判斷。
8. **文件**：`08-api.md` 的通則（認證、錯誤）、各端點的認證欄、ADR-012 免認證清單同步更新；`ApiDocumentationTest` 通過。
9. **登入暫存上限**：待處理的登入暫存有數量上限（組態項，預設值足以涵蓋數十人同時登入）。達上限時，新的登入起點回 429 並帶 `Retry-After`，不寫入新暫存；既有暫存與進行中的登入的 callback 不受影響；過期暫存清理後名額恢復。以真實 PostgreSQL 驗證，涵蓋：達上限被拒、上限內的並行登入皆成功、既有登入在達上限時仍可完成、過期後恢復。`08-api.md` 與 `04-deployment.md` 更新。

## 架構約束

- 不改變既有 Bearer 契約與錯誤碼。
- 授權規則只有一套，不為工作階段另寫授權邏輯。
- 以真實 PostgreSQL 與真實 HTTP 請求驗證，不使用 Stub 或 Mock。
