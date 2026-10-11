# 工作項總覽：OIDC 登入

本文回答：OIDC 登入要交給 tdd-coder 的工作有哪些、先後順序為何。tdd-coder 只需讀本頁加被派到的那一項。狀態：已核可（2026-10-10）。

| 項目 | 狀態 | 目標 | 相依 |
|---|---|---|---|
| [WI-1200](WI-1200-oidc-login-and-session.md) | 已完成（2026-10-10） | Engine 完成 OIDC 登入：設定、登入流程、群組對應角色、使用者與名稱規則、工作階段、登出 | 無 |
| [WI-1207](WI-1207-require-refresh-token.md) | 已完成（2026-10-10） | 沒有 refresh token 就拒絕登入；ID token 只接受非對稱簽章 | WI-1200 |
| [WI-1201](WI-1201-dual-authentication-and-csrf.md) | 已完成（2026-10-10） | API 同時接受 Bearer 與工作階段，改狀態請求的 CSRF 與同源檢查，呼叫者資訊標示來源 | WI-1200 |
| [WI-1202](WI-1202-console-oidc-login.md) | 已完成（2026-10-11） | Console 登入頁加「使用 authentik 登入」，登入狀態與登出、錯誤顯示、跨分頁行為 | WI-1201 |
| [WI-1208](WI-1208-login-start-failure-redirect.md) | 已完成（2026-10-11） | 登入起點的登入過多與 authentik 不可用導回 Console 登入頁；需要 Keycloak 的 e2e 分離成獨立指令 | WI-1201、WI-1202 |
| [WI-1205](WI-1205-revalidation-and-user-disable.md) | 已完成（2026-10-10） | 立刻踢人：定期向 authentik 重新驗證（角色變更、撤銷）、管理員停用使用者與結束工作階段 | WI-1201、WI-1207 |
| [WI-1206](WI-1206-console-user-management.md) | 已完成（2026-10-11） | Console 的使用者與工作階段管理頁 | WI-1202、WI-1205 |
| [WI-1203](WI-1203-deployment-and-authentik-runbook.md) | 已完成（2026-10-11；條件 6 及條件 3、7 的 authentik 部分移交 WI-1204 條件 10 至 12） | 部署範例的 OIDC 設定、私有 CA 信任、authentik 端設定手冊 | WI-1200 |
| [WI-1204](WI-1204-end-to-end-verification.md) | 未開始 | 以真實 OIDC 提供者的整體驗證，與文件一致性核對 | WI-1201、WI-1202、WI-1203、WI-1205、WI-1206 |

共同約束：

- 遵守 [ADR-029](../adr/ADR-029-oidc-login-with-server-session.md) 與本目錄上層的 [05-ipc.md](../05-ipc.md)、[06-data-model.md](../06-data-model.md)、[04-deployment.md](../04-deployment.md)。
- ADR-029 取代 ADR-017 對 cookie 工作階段的否決，修訂 ADR-012；`docs/stable/pipeline-engine/` 的對應文件由各工作項更新。
- OIDC 未設定時，Engine 與 Console 的行為與現在完全相同。
- 測試中的外部系統一律使用真實實作：資料庫用 Testcontainers 的 PostgreSQL；OIDC 提供者用容器中真實運作的標準 OIDC 提供者，不使用 Stub 或 Mock。
- token、client secret、refresh token、加密金鑰、工作階段識別碼不得出現在 log、trace、錯誤訊息、API 回應與網址。
