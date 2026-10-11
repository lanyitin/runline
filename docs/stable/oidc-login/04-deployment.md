# 部署與設定

本文回答：啟用 OIDC 登入時，Engine 要設定什麼、authentik 端要準備什麼、網路與密鑰如何處理。

## Engine 設定（環境變數，12-Factor）

| 設定 | 必要 | 意義 |
|---|---|---|
| `RUNLINE_OIDC_ISSUER` | 啟用時必要 | authentik 該 provider 的 issuer 網址；Engine 由它的探索文件取得各端點與簽章金鑰 |
| `RUNLINE_OIDC_CLIENT_ID` | 啟用時必要 | authentik 的 client ID |
| `RUNLINE_OIDC_CLIENT_SECRET` 或 `RUNLINE_OIDC_CLIENT_SECRET_FILE` | 啟用時必要，二選一 | client secret，檔案形式優先；兩者都設則拒絕啟動 |
| `RUNLINE_PUBLIC_BASE_URL` | 啟用時必要 | 使用者看到的 Engine 網址；callback 位址由它推導，也決定 cookie 是否為 `Secure`（https） |
| `RUNLINE_OIDC_GROUPS_CLAIM` | 選填，`groups` | 群組 claim 名稱 |
| `RUNLINE_OIDC_ADMIN_GROUP` | 選填，`runline-admin` | 管理員群組 |
| `RUNLINE_OIDC_DEVELOPER_GROUP` | 選填，`runline-developer` | 開發人員群組 |
| `RUNLINE_SESSION_IDLE_SECONDS` | 選填，28800 | 閒置多久失效 |
| `RUNLINE_SESSION_ABSOLUTE_SECONDS` | 選填，604800 | 工作階段最長存活 |
| `RUNLINE_SESSION_REVALIDATE_SECONDS` | 選填，60，至少 10 | 使用中的工作階段多久向 authentik 重新驗證一次；也是收回權限後最久的生效延遲 |
| `RUNLINE_SESSION_REVALIDATE_GRACE_SECONDS` | 選填，900，0 為嚴格失敗關閉 | authentik 無法連線時，自上次成功驗證起工作階段仍有效多久 |
| `RUNLINE_SESSION_ENCRYPTION_KEY` 或 `RUNLINE_SESSION_ENCRYPTION_KEY_FILE` | 啟用時必要，二選一 | 加密 refresh token 的金鑰（檔案形式優先）；長度與格式不合、兩者都設則拒絕啟動；更換金鑰使現有工作階段失效（使用者需重新登入） |

規則：OIDC 設定全部未設時功能關閉；必要項缺少、兩個群組名稱相同、閒置時間大於絕對時間，Engine 列出問題並拒絕啟動。Client secret 不出現在 log、trace、錯誤訊息與 API 回應。

啟動時不連線 authentik；探索文件與簽章金鑰於第一次登入時取得並快取，失效後重新取得。authentik 不可用時，登入回 503，其餘功能與就緒探測不受影響。

## authentik 端需求

- 一個 OAuth2／OIDC provider 與其 application：機密 client、Authorization Code、需要 PKCE。
- Redirect URI 精確等於 `RUNLINE_PUBLIC_BASE_URL` 加 callback 路徑（見 [05-ipc.md](05-ipc.md)）。
- 兩個群組 `runline-admin`、`runline-developer`，使用者加入其中之一。
- ID token 須帶群組 claim（scope mapping），且帶 `preferred_username`。
- 允許 `offline_access` scope 與 refresh token；refresh token 的有效期限不短於 `RUNLINE_SESSION_ABSOLUTE_SECONDS`；refresh 時回傳的 ID token 仍須帶群組 claim。
- 建議限制 application 只對這兩個群組可見（第二道防線；Engine 仍自行檢查）。

## 網路與信任

- Engine 需要能以 HTTPS 連到 authentik 的 issuer 位址（容器網路與防火牆）。
- authentik 若用私有 CA，Engine 的 JVM 須信任該 CA 的根憑證；沿用 `deploy/` 已有的做法（JVM 信任庫加 `-Djavax.net.ssl.trustStore`，見 [deploy/browser.md](../../../deploy/browser.md)）；一般映像用預先做好的信任庫與疊加檔 `compose.oidc-ca.yaml`，做法見 [authentik 設定手冊](../../../deploy/authentik.md)。
- 對外入口須終止 TLS，並讓 Engine 取得正確的對外網址；cookie 不得在非 https 的部署送出 `Secure`（本機開發的 http 例外）。

## 範例與手冊（WI-1203）

- Docker：`deploy/docker/.env.example` 的 OIDC 區段、疊加檔 `compose.oidc.yaml`（機密以 Docker secret 提供）、`compose.oidc-ca.yaml`（私有 CA）。
- systemd：`deploy/systemd/runline.env.example` 的 OIDC 區段、drop-in `runline-engine-oidc.conf`（機密以 systemd 憑證提供）。
- authentik 端的設定、確認 claim 的步驟、排錯對照與待確認事項：[deploy/authentik.md](../../../deploy/authentik.md)。
- 群組 claim 要隨 Engine 實際要求的 scope（`openid profile offline_access`）回傳，所以自建的 scope mapping 其 scope 名稱須為 `profile`；見手冊。

## 環境

dev 環境不內建 authentik，OIDC 預設關閉，Console 只顯示 token 登入。staging、prod 以實際 authentik 啟用。範例檔（`deploy/` 的 `.env.example`、`runline.env.example`）只放占位符。資料庫遷移於部署時由既有的一次性 `migrate` 執行。
