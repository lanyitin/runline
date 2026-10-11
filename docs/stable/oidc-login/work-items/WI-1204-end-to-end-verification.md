# WI-1204 OIDC 登入的整體驗證與文件一致性

本文回答：整套 OIDC 登入在真實環境下是否達到需求，文件與實作是否一致。狀態：已核可（2026-10-10）。相依：WI-1201、WI-1202、WI-1203、WI-1205、WI-1206。工作儲存庫：runline。

## 驗收條件

1. **真實 authentik**：在使用者的 authentik 上驗證（手動，記錄結果）：`runline-admin` 的人是管理員、`runline-developer` 的人是開發人員且看不到管理員頁、兩群組皆無的人被拒並看到說明、同時在兩群組為管理員。
2. **立刻踢人**（在真實 authentik 上，記錄實際耗時）：把人從 admin 移到 developer，不重新登入，約一個間隔內角色改變；把人移出兩個群組、停用其帳號、在 authentik 撤銷其工作階段，各自約一個間隔內該使用者得到 401；管理員在 Console 停用使用者立即生效且無法再登入；停止 authentik 後寬限時間內仍有效、過期後失效。結果供確認待確認問題 3、4。
3. **身分隔離**：與 `API_TOKENS` 同名的 authentik 使用者被拒；兩種來源的使用者彼此看不到對方的 pipeline；authentik 改名後仍是同一位使用者。
4. **並存**：CLI／CI 以 Bearer 上傳與建立 run 不受影響；OIDC 關閉時 Engine 與 Console 行為與現在相同。
5. **authentik 中斷**：停止 authentik 後，已登入的使用者與 token 呼叫繼續運作，登入給出明確錯誤，就緒探測不受影響。
6. **安全回歸**：[WI-37](../../pipeline-engine/work-items/WI-37-console-security-verification.md) 的 `dev/verify-console-security.sh` 通過；跨站改狀態請求被擋；機密不外洩驗證涵蓋 client secret、授權碼、ID token、refresh token、工作階段識別碼不出現在 log、trace、metric、錯誤與回應。
7. **文件一致性**：`docs/stable/pipeline-engine/` 的相關文件與實作一致，`ApiDocumentationTest` 通過；README 的 release checklist 加入 OIDC 登入的手動驗證步驟。
8. **回報**：列出未通過或需要架構決定之處。
9. **簽章與 refresh token**（先於條件 1 執行）：在使用者的 authentik 上確認 ID token 為 RS256、登入時確實取得 refresh token；並實測將 provider 的簽章金鑰移除後登入被拒、`offline_access` 被拒絕時登入被拒，記錄 Console 顯示的訊息與 log。
10. **WI-1203 待確認項**：在使用者的 authentik 上實測並填入 `deploy/authentik.md` 末尾「待確認事項」表：群組 claim 名稱、是否使用私有 CA、實際 redirect URI、access token 與 refresh token 的實際有效期限（refresh token 不短於 `RUNLINE_SESSION_ABSOLUTE_SECONDS`），並據此回覆 [01-requirements.md](../01-requirements.md) 待確認問題 1、2、6；手冊中標〔待確認〕的 authentik 介面名稱與行為逐項核對並更正。
11. **scope 與群組 claim**：確認 Engine 要求的 `profile` scope 在 authentik 是否帶出 `groups` 與 `preferred_username`；若不帶，回報為需要架構決定（是否讓 scope 可設定）。
12. **私有 CA**：若使用者的 authentik 使用私有 CA，按 `deploy/` 手冊的做法讓 Engine 信任它，並以該 authentik 完成一次登入。

## 架構約束

- 不修改已驗收項目的行為；發現問題回報，由架構師判斷。
- 不使用 Stub 或 Mock。
