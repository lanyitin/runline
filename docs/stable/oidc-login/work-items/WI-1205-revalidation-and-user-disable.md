# WI-1205 立刻踢人：定期重新驗證與管理員停用使用者

本文回答：權限在 authentik 被收回，或管理員要踢人時，Engine 如何讓工作階段在分鐘內失效或改變角色。狀態：已核可（2026-10-10）。相依：WI-1201。決策見 [ADR-029](../adr/ADR-029-oidc-login-with-server-session.md) 決策 9，契約見 [05-ipc.md](../05-ipc.md)。工作儲存庫：runline（engine）。

## 驗收條件

1. **重新驗證**：工作階段被使用且距上次成功驗證超過 `RUNLINE_SESSION_REVALIDATE_SECONDS` 時，先以 refresh token 向 authentik 重新驗證再處理該請求。間隔內的請求不呼叫 authentik。閒置的工作階段不被主動驗證。同一工作階段並行請求只做一次驗證，其餘等待同一結果。
2. **結果處理**：
   - 仍屬 admin／developer 群組 → 更新工作階段的角色；從 admin 降為 developer 後，下一個請求就不能使用管理 API；從 developer 升為 admin 同理生效。
   - 不屬於任何允許群組、使用者在 authentik 被停用、或 authentik 拒絕 refresh → 刪除工作階段，該請求回 401。
   - Refresh token 被輪替時保存新的；失敗不重試已使用的 token。
3. **authentik 無法連線**：自上次成功驗證起在 `RUNLINE_SESSION_REVALIDATE_GRACE_SECONDS` 內工作階段仍有效，超過後失效回 401；寬限設 0 時任何驗證失敗即失效。恢復連線後正常驗證。Engine 啟動與就緒探測不受影響。
4. **生效時間**：在 authentik 收回權限（移出群組、停用使用者、撤銷其工作階段）後，使用者在不超過一個間隔加一次請求的時間內失去權限；以測試量測並記錄。
5. **停用使用者（管理員）**：管理員可停用與啟用 OIDC 使用者。停用在同一交易內刪除該使用者所有工作階段，並使其在啟用前不能登入（`user_disabled`），即使 authentik 仍允許；停用與並行的登入或重新驗證競爭時，不得留下有效工作階段。停用或啟用記錄操作者與時間；對 `API_TOKENS` 的名稱回錯誤。
6. **工作階段管理（管理員）**：可列出某使用者的工作階段（建立與最近使用時間，不含識別碼或任何憑證）、結束單一或全部。管理員可停用或結束自己的工作階段，但 Engine 不得因此沒有任何可用的管理員途徑——`API_TOKENS` 的管理員 token 不受影響，文件說明這是還原途徑。
7. **權限**：以上管理端點只有 admin（Bearer 或工作階段）；developer 得 403，且對 developer 不洩漏使用者是否存在；工作階段下的改狀態請求要求 CSRF（WI-1201）。
8. **可觀測性與機密**：重新驗證結果（續期、角色改變、撤銷、寬限內放行、寬限過期）、停用、啟用、結束工作階段各有 log 與計數器；refresh token、ID token 與識別碼不出現在 log、trace、metric、錯誤與回應。
9. **文件**：`08-api.md`、`05-ipc.md`、`06-data-model.md`、`04-deployment.md` 更新；`ApiDocumentationTest` 通過。
10. **沒有 refresh token 的工作階段**：重新驗證時若工作階段沒有 refresh token（理論上不應存在，例如資料被破壞），視為驗證失敗，刪除工作階段。

## 架構約束

- 驗證結果寫入資料庫，不依賴行程記憶體（可水平擴充）。
- 角色與授權的語意不變，不為重新驗證另寫授權邏輯。
- 以真實 PostgreSQL 與真實運作的 OIDC 提供者（容器）驗證，涵蓋：移出群組、停用使用者、撤銷工作階段、降權與升權、提供者中斷與恢復、寬限過期、並行請求、停用與登入競爭。
