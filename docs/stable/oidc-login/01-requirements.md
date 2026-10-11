# 需求摘要與現況

本文回答：OIDC 登入要滿足什麼、現有系統的哪些限制會影響它、還有什麼沒確定。

## 需求

- 使用者在 Console 登入頁選「使用 authentik 登入」，完成後以其身分使用 Console。
- 管理員與開發人員由 authentik 群組決定：`runline-admin` 為管理員，`runline-developer` 為開發人員；同時在兩個群組時為管理員；都不在則不能使用。
- 在 authentik 變更群組、停用使用者或撤銷其工作階段後，runline 的工作階段在約一個重新驗證間隔（預設 60 秒）內失效或改變角色；管理員也能在 runline 直接停用使用者，立即生效。
- 現有 token 的使用者與 authentik 的使用者不能有共同的身分（名稱不可重複）。
- 現有 Bearer token 的用法（CLI、CI、webhook 之外的機器呼叫）不受影響。

非功能需求：單團隊、少量使用者；Engine 仍是單實例，但工作階段放資料庫，不依賴行程記憶體；authentik 暫時不可用時，Engine 與 token 使用者不受影響；token、client secret、session 識別碼不出現在 log、trace 與錯誤訊息。

## 現況觀察

- 認證只有 Bearer token，來自 `API_TOKENS`；角色只有 `developer`、`admin`；名稱即擁有權身分（[ADR-012](../pipeline-engine/adr/ADR-012-api-authentication.md)、[ADR-020](../pipeline-engine/adr/ADR-020-per-uploader-artifact-versions.md)）。
- 認證實作是可替換的 provider，角色與身分語意固定（ADR-012）。
- Console 有與憑證種類脫鉤的認證邊界，目前唯一實作是 token 加 sessionStorage 與 BroadcastChannel（[WI-34](../pipeline-engine/work-items/WI-34-console-auth-boundary.md)、[ADR-017](../pipeline-engine/adr/ADR-017-console-websocket-and-token.md)）。
- ADR-017 否決 cookie 工作階段，理由是它改變 ADR-012 的模型；同時預告 OIDC 須另開 ADR 修訂 ADR-012。ADR-029 即是該 ADR。
- 免認證端點為明列清單（ADR-012），新增須修訂。
- Console 的 CSP 為 `default-src 'self'` 且 `form-action 'self'`（[README](../../../README.md)）；登入以頁面導覽（非 fetch）進行，不受影響。
- Engine 目前沒有使用者表、工作階段表，也沒有對外發起 HTTPS 請求去驗證身分的元件。
- 資料庫為 PostgreSQL，遷移由一次性的 `migrate` 程序執行，Engine 只檢查版本。

## 假設

- authentik 是已存在的實例，可建立 OAuth2／OIDC provider 與 application、群組與 scope mapping。
- 瀏覽器、Engine、authentik 之間為 HTTPS；Engine 之前有 TLS 終止入口（ADR-012）。
- Console 與 API 同源（Engine 同時提供），因此 cookie 不需要跨站。
- authentik 的 username 在 authentik 內唯一。
- authentik 允許 `offline_access` 與 refresh token；access token 與 refresh token 的有效期限可調整；refresh 時回傳的 ID token 仍帶群組 claim（使用者已確認）。

## 待確認問題

1. authentik 的 token 是否已帶有群組 claim，claim 名稱為何（預設假設為 `groups`）。影響：角色對應的設定值；若沒有，需在 authentik 補 scope mapping。請 homelab-deployer 確認。
2. authentik 實例的憑證是否由私有 CA 簽發。影響：Engine 的 JVM 需信任該 CA（部署文件已預留，見 [04-deployment.md](04-deployment.md)）。
3. 「立刻」可接受的延遲：預設重新驗證間隔 60 秒，所以在 authentik 收回權限後最久約 1 分鐘生效。影響：對 authentik 的呼叫量；要更短需調低間隔。
4. authentik 無法連線時，已登入者是否保持有效：預設保持 15 分鐘後失效。影響：authentik 當機時的可用性與安全；要嚴格失敗關閉設 0。
5. 登出是否同時登出 authentik。預設只登出 runline（同 Forgejo）。
6. authentik 的 redirect URI 與 Engine 的對外網址（含是否在代理之後）。影響：callback 位址與 cookie 的 `Secure` 屬性。
