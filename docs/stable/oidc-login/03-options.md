# 方案比較與推薦

本文回答：OIDC 登入由誰完成、登入後如何維持，哪個方案最合適。

## 方案

| 方案 | 說明 | 優點 | 缺點 |
|---|---|---|---|
| A. Engine 當 OIDC client，伺服器端工作階段（推薦） | Engine 完成 code flow，建立使用者與工作階段，以 HttpOnly cookie 維持登入 | 與 Forgejo 的體驗一致；憑證不進 JavaScript，XSS 拿不到登入憑證；工作階段可由伺服器撤銷；登入頁只多一個按鈕；機密 client 的 secret 只在後端 | 新增登入端點、使用者與工作階段資料表、CSRF 防護；推翻 ADR-017 對 cookie 的否決；認證從單一 Bearer 變成雙軌 |
| B. SPA 做 PKCE，Engine 只驗 JWT | Console 取得 authentik 的 access token 當 Bearer，Engine 以 JWKS 驗證 | 不改 Bearer 契約；Engine 無工作階段 | token 仍在 JavaScript 內（XSS 可取）；沒有伺服器撤銷；角色變更要等 token 重發；Console 要實作 OIDC 與 refresh；與「像 Forgejo」不同 |
| C. 外部反向代理注入身分標頭 | authentik outpost 擋在 Engine 前面 | Engine 改動最小 | 信任邊界在代理，直連 Engine 即可偽造；與 token 並存困難；Engine 不知道登入的生命週期 |

## 推薦

**方案 A**。需求是 Forgejo 式登入，並由 authentik 群組決定角色；A 是唯一讓 Engine 自己掌握「誰登入、什麼角色、何時失效」的方案。它的代價（新資料表、CSRF）都是成熟且範圍有限的工作，而且 ADR-012 已預留 provider 可替換、ADR-017 已預留 Console 的認證邊界。

主要風險與緩解見 [07-nfr-risks.md](07-nfr-risks.md)。決策記錄見 [ADR-029](adr/ADR-029-oidc-login-with-server-session.md)。
