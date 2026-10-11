# authentik 設定手冊（OIDC 登入）

本文回答：維運者如何在 authentik 建立 runline 登入所需的 provider 與 application、如何讓 Engine 信任它、如何確認設定正確，以及登入失敗時怎麼查。語意與決策見 [oidc-login](../docs/stable/oidc-login/README.md) 與 [ADR-029](../docs/stable/oidc-login/adr/ADR-029-oidc-login-with-server-session.md)、[ADR-030](../docs/stable/oidc-login/adr/ADR-030-signing-and-refresh-token-requirements.md)；Engine 的設定項目見 [04-deployment.md](../docs/stable/oidc-login/04-deployment.md)。範例檔：`docker/.env.example`、`docker/compose.oidc.yaml`、`docker/compose.oidc-ca.yaml`、`systemd/runline.env.example`、`systemd/runline-engine-oidc.conf`。

## 先讀：哪些已驗證，哪些尚未

本手冊寫成時**沒有連到真實的 authentik**。標示「〔待確認〕」的內容是依 authentik 文件與慣例寫的，**沒有在真實 authentik 上驗證**，介面名稱與預設值可能因版本而異；它們由 [WI-1204](../docs/stable/oidc-login/work-items/WI-1204-end-to-end-verification.md) 在真實 authentik 上逐項確認，確認後移除標記。

已驗證的部分（用的是容器中真實運作的 Keycloak，不是 authentik）：

- Engine 側的行為：群組對應角色、`preferred_username` 當名稱、沒有 refresh token 時以 `refresh_unavailable` 拒絕、ID token 以 HS256 簽章時以 `invalid_token` 拒絕並在 log 指出演算法、各失敗代碼（WI-1200、WI-1207 的測試）。
- 私有 CA：測試 `DockerOidcPrivateCaTest` 以 `docker/compose.yaml` 加兩個疊加檔、真實的 PostgreSQL，以及用自建 CA 簽發 https 憑證的 Keycloak，驗證：未信任該 CA 時登入起點導回 `/?login_error=provider_unavailable`；用本手冊的做法做出信任庫後，使用者登入成功並取得群組對應的角色；client secret 與 session 金鑰以 Docker secret 提供，不在容器環境變數與 log。
- 範例檔與本手冊的完整性：`OidcDeploymentExamplesTest`（每個設定項都在兩個範例檔、OIDC 預設關閉、機密只放占位符、每個失敗代碼在本手冊有說明）。

**未驗證**：systemd 版（`runline-engine-oidc.conf` 只做過 `systemd-analyze verify`，和金鑰庫的 drop-in 一樣只有「Type=simple、ExecStartPost 與憑證」的既有警告，沒有在真實的 systemd 主機上啟動）；authentik 的一切。

## 流程總覽

1. 決定對外網址（下節）。
2. 在 authentik 建立兩個群組、把使用者加進去。
3. 讓 ID token 帶出群組 claim 與 `preferred_username`（scope mapping）。
4. 建立 OAuth2／OpenID provider（含簽章金鑰、redirect URI、scope、token 有效期限）。
5. 建立 application、限制可見者。
6. 取得 client secret 與 issuer，放進 Engine 的設定。
7. 若 authentik 使用私有 CA，讓 Engine 的 JVM 信任它。
8. 依「確認 claim 正確」檢查，再讓使用者登入。

## 對外網址與 cookie

`RUNLINE_PUBLIC_BASE_URL` 必須**與使用者在瀏覽器網址列看到的 Engine 網址一致**：協定、主機名稱、連接埠（非預設時），沒有路徑、沒有結尾斜線。三件事取決於它：

- **redirect URI**：Engine 要求 authentik 把使用者送回 `RUNLINE_PUBLIC_BASE_URL` 加 `/api/v1/auth/oidc/callback`。authentik 登記的 redirect URI 與此不完全相同時，authentik 在它自己的頁面顯示錯誤，使用者不會回到 Engine。
- **cookie 的 `Secure` 屬性**：工作階段 cookie（`runline_session`，`HttpOnly`、`SameSite=Lax`）只在 `RUNLINE_PUBLIC_BASE_URL` 以 `https://` 開頭時帶 `Secure`。Engine 只看這個設定，**不看** `X-Forwarded-Proto` 等標頭。
- **同源檢查**：以工作階段發出的改狀態請求（POST、PUT、PATCH、DELETE）的 `Origin` 必須等於 `RUNLINE_PUBLIC_BASE_URL`，否則 403 `origin_invalid`。

TLS 終止在 Engine 之前（反向代理）時：把 `RUNLINE_PUBLIC_BASE_URL` 設為使用者實際使用的 **https** 網址（代理對外的網址），Engine 與代理之間是否為 http 不影響；cookie 因此帶 `Secure`，瀏覽器只透過 https 送它。常見錯誤：

| 設定 | 症狀 |
|---|---|
| 對外是 https，`RUNLINE_PUBLIC_BASE_URL` 寫成 `http://…` | cookie 沒有 `Secure`（安全性降低）；callback 位址是 http，與 authentik 登記的 https 不同，authentik 拒絕 |
| 對外是 http（只在本機開發），`RUNLINE_PUBLIC_BASE_URL` 寫成 `https://…` | cookie 帶 `Secure`，瀏覽器不在 http 頁面保存它：登入看似成功，卻立刻回到登入頁（工作階段查詢得到 401） |
| 使用者用 `localhost`，設定寫主機名稱（或反過來） | `Origin` 不同源：登入後的改狀態請求得到 403 `origin_invalid`；redirect URI 的主機也不同 |

只有本機開發可以用 http（`Secure` 不帶）。

## 在 authentik 建立群組

在目錄（Directory）的群組（Groups）建立兩個群組，名稱與 Engine 的設定一致：

| 群組 | Engine 設定 | 角色 |
|---|---|---|
| `runline-admin` | `RUNLINE_OIDC_ADMIN_GROUP`（預設） | 管理員 |
| `runline-developer` | `RUNLINE_OIDC_DEVELOPER_GROUP`（預設） | 開發人員 |

規則（Engine 側，已驗證）：同時在兩個群組時為管理員；都不在則不能登入（`not_authorized`，不建立使用者）；名稱比對是完全相同，區分大小寫。要用別的名稱時，改 Engine 的兩個設定，不必改程式。把使用者加進其中一個群組。

## 讓 ID token 帶出群組與 `preferred_username`

Engine 登入時要求的 scope 固定為 `openid`、`profile`、`offline_access`（不含 `email`，也不含自訂的 `groups`）；它從 **ID token** 讀兩樣東西：

- `preferred_username`：成為使用者在 runline 的名稱（首次登入時決定，之後不變）。沒有它，登入以 `invalid_token` 失敗。
- 群組 claim：名稱由 `RUNLINE_OIDC_GROUPS_CLAIM` 決定，預設 `groups`；值是群組名稱的清單（只有一個群組時，字串也可以）。

因為 Engine 只要求上面三個 scope，**群組 claim 必須由會隨 `profile`（或 `openid`）scope 一起回傳的 scope mapping 提供**。〔待確認〕authentik 內建的 `profile` 對應（scope mapping，名稱為 authentik default OAuth Mapping 的 OpenID 'profile'）據文件已包含 `preferred_username`（值為 authentik 的 username）與 `groups`（使用者所屬群組的名稱清單）；若該 authentik 版本的內建對應確實如此，**不需要**自建 mapping，只要在 provider 選取它。

若內建對應沒有 `groups`，或群組 claim 名稱不是 `groups`：

1. 在自訂（Customization）的屬性對應（Property Mappings）建立一個 scope mapping。
2. **scope 名稱填 `profile`**（不要填 `groups`：Engine 不會要求名為 `groups` 的 scope，那個 mapping 就不會被套用）。〔待確認〕同一個 scope 名稱可由多個 mapping 同時提供，claim 合併。
3. 運算式回傳一個 claim，名稱為你要的 claim 名稱（預設 `groups`），內容是該使用者所屬群組的名稱清單（不是群組的 UUID，也不是路徑）。
4. 在 provider 的 scope 選項選取這個 mapping。
5. claim 名稱不是 `groups` 時，把 Engine 的 `RUNLINE_OIDC_GROUPS_CLAIM` 設成同名。

## 建立 provider

在應用程式（Applications）的 provider 建立 OAuth2／OpenID provider：

| 設定 | 值 | 原因 |
|---|---|---|
| 名稱 | 自訂，例如 `runline` | |
| 授權流程（Authorization flow） | 依你的政策（明確同意或隱含同意） | 與 Engine 無關 |
| Client type | **機密（Confidential）** | Engine 在後端用 client secret 換 token |
| Client ID | authentik 產生的值，填入 `RUNLINE_OIDC_CLIENT_ID` | |
| Client Secret | authentik 產生的值，見「client secret」 | |
| Redirect URI | `RUNLINE_PUBLIC_BASE_URL` 加 `/api/v1/auth/oidc/callback`，**精確比對（strict）**，不用正規表示式比對 | 見「對外網址」 |
| 簽章金鑰（Signing Key） | **必須選一把非對稱金鑰**（RSA，簽出 RS256；例如 authentik 自簽憑證或你自己的金鑰） | 見下 |
| Scopes | 選取 `openid`、`profile`（含群組 claim 的對應）與 `offline_access` | 見下 |
| Access token validity | 見「token 有效期限」 | |
| Refresh token validity | **不短於 `RUNLINE_SESSION_ABSOLUTE_SECONDS`**（預設 604800 秒，即 7 天） | 見「token 有效期限」 |
| Issuer mode | 每個 provider 一個 issuer（per provider）〔待確認〕 | issuer 網址要和 Engine 設定完全相同 |

〔待確認〕上表的欄位名稱與預設值以真實 authentik 為準。PKCE：Engine 一律送 PKCE（S256）；若該版本的 provider 有「要求 PKCE」的選項，開啟它。

### 簽章金鑰（RS256）

Engine 只接受以 **RS256** 簽章的 ID token（ADR-030）。〔待確認〕authentik 的 provider **未選簽章金鑰時，ID token 以 client secret 做 HS256 對稱簽章**，任何知道 client secret 的人都能偽造，所以 Engine 拒絕它：

- 症狀：使用者回到登入頁，畫面上是「登入失敗」，網址帶 `login_error=invalid_token`；Engine 的 log 有一行警告：`Sign-in refused: invalid_token: the ID token is signed with HS256, which is not accepted; only RS256 is, so the provider needs a signing key chosen for this client`（演算法名稱因情形而異；只含演算法名稱，不含 token）。
- 處理：在 provider 的簽章金鑰選一把 RSA 金鑰，存檔，再登入一次。

此行為在 Engine 側以 Keycloak 驗證過（client 設為 HS256 時得到同樣的代碼與 log）；authentik 側「未選金鑰就是 HS256」〔待確認〕。

### `offline_access` 與 refresh token

Engine 的每個工作階段都需要 refresh token，才能定期向 authentik 重新驗證（角色變更、停用、撤銷都靠它）。Engine 要求 `offline_access` scope；沒有拿到 refresh token 就拒絕登入（失敗關閉，ADR-030）：

- 症狀：`login_error=refresh_unavailable`；log：`Sign-in refused: refresh_unavailable (<使用者名稱>): the provider returned no refresh token; check that the client may use the offline_access scope and that the provider issues refresh tokens for it`。不建立使用者與工作階段。
- 處理：確認 provider 的 scope 選了 `offline_access`、application 對該使用者可用，並確認 provider 對這個 client 回傳 refresh token（見下「確認 claim 正確」的檢查步驟 1）。〔待確認〕authentik 在哪些條件下不回傳 refresh token。

此行為在 Engine 側以 Keycloak 驗證過（client 不發 refresh token 時得到同樣的代碼與 log）。

### token 有效期限

| 項目 | 建議 | 原因 |
|---|---|---|
| Access token | 數分鐘（authentik 的預設，〔待確認〕為 5 分鐘） | Engine 不使用 access token 呼叫 API，只用 refresh token 換新的 ID token；短不影響使用者 |
| Refresh token | **不短於 `RUNLINE_SESSION_ABSOLUTE_SECONDS`**（預設 604800 秒＝7 天；〔待確認〕authentik 預設 30 天，則已滿足） | refresh token 先過期，工作階段在絕對時間之前就會在下次重新驗證時被結束（refresh 被拒），使用者被要求重新登入 |

調整 `RUNLINE_SESSION_ABSOLUTE_SECONDS` 時，同步調整 refresh token 的有效期限。**實際採用的值**記在本節下方的「待確認事項」表（由 WI-1204 在真實 authentik 填入）。

## 建立 application、限制可見者

1. 在應用程式建立 application：名稱 `runline`，slug 例如 `runline`，provider 選上一步建立的。
2. issuer 網址：〔待確認〕authentik 的 issuer 形如 `https://<authentik 主機>/application/o/<slug>/`，**結尾有斜線**。把它**逐字**填入 `RUNLINE_OIDC_ISSUER`：Engine 取得探索文件後，要求文件裡的 `issuer` 與設定**字串完全相同**（結尾斜線、大小寫、連接埠都算），不同就視同 provider 不可用（`provider_unavailable`）。最可靠的做法是從探索文件複製（見「確認 claim 正確」步驟 2），不要憑記憶。
3. 限制可見者（第二道防線；Engine 仍自行檢查群組）：在 application 的政策／群組／使用者綁定（Policy / Group / User Bindings）綁定 `runline-admin` 與 `runline-developer` 兩個群組，其他人看不到也無法啟動這個 application。〔待確認〕不在綁定中的使用者在 authentik 端的表現（被拒絕的頁面或事件）。

## client secret

- **取得**：在 provider 的設定頁複製 client secret（〔待確認〕介面位置）。把它寫進檔案（一行，結尾換行無妨），**用檔案提供給 Engine**，不用環境變數：
  - Docker：`docker/secrets/oidc-client-secret`（路徑可用 `.env` 的 `OIDC_CLIENT_SECRET_HOST_FILE` 改），由 `docker/compose.oidc.yaml` 掛成 Docker secret，並設 `RUNLINE_OIDC_CLIENT_SECRET_FILE=/run/secrets/runline_oidc_client_secret`。`docker/secrets/` 已被 git 忽略。
  - systemd：`/etc/runline/oidc-client-secret`（`root:root` `0600`），由 `systemd/runline-engine-oidc.conf` 以 `LoadCredential` 交給服務，`RUNLINE_OIDC_CLIENT_SECRET_FILE=%d/oidc-client-secret`。
  - 同時設 `RUNLINE_OIDC_CLIENT_SECRET` 與 `_FILE` 是組態錯誤，Engine 拒絕啟動。與金鑰庫密碼的做法一致（檔案優先，Docker secret、systemd credential）。
- **session 加密金鑰**（不是 authentik 的設定，但同樣以檔案提供）：`openssl rand -base64 32` 的輸出，Docker 為 `docker/secrets/session-encryption-key`，systemd 為 `/etc/runline/session-encryption-key`。更換它會使現有工作階段失效，使用者需重新登入。
- **輪替 client secret**：
  1. 在低流量時段進行。authentik 一旦換了 secret，舊 secret 立刻失效（〔待確認〕），而 Engine 只在啟動時讀檔。
  2. 先把新值寫進 Engine 的檔案，在 authentik 更換 secret，立刻重啟 Engine（Docker：`docker compose up -d --force-recreate engine`；systemd：`systemctl restart runline-engine`）。工作階段在資料庫，重啟不會結束它們。
  3. 換 secret 到重啟完成之間：新登入失敗（`invalid_token`）；正好在這段時間做重新驗證的工作階段會因 refresh 被拒而結束（依重新驗證的語意，被拒就是撤銷），使用者重新登入即可。縮短這段時間就縮小影響。
  4. 用「確認 claim 正確」確認登入恢復。
  Engine 不支援新舊 secret 並存。

## 讓 Engine 信任私有 CA

authentik 使用私有 CA（或自簽）時，Engine 的 JVM 必須信任該 CA 的**根憑證**，否則探索文件、金鑰與換 token 的 HTTPS 呼叫都失敗。症狀：登入起點導回 `login_error=provider_unavailable`，log 只有 `The provider could not be used to start a sign-in: UNAVAILABLE`（不指出是憑證問題；用下面的檢查確認）。做法沿用 `deploy/` 現有的機制（[browser.md](browser.md)「根憑證信任」）：JVM 信任庫 = JDK 自己的 CA + 你的根憑證，以 `-Djavax.net.ssl.trustStore` 交給 JVM。公開的 CA 仍被信任。**信任是整個 JVM 的**，不寫死在程式。

**Docker**（一般映像）：

1. 把根憑證（PEM，`*.pem`、`*.crt`、`*.cer`，每檔一個或多個憑證）放進一個目錄，例如 `deploy/docker/trusted-ca/`。
2. 在映像內用 `deploy/bin/runline-build-truststore` 做信任庫（映像已有 JDK 的 `keytool`）。把下列三個目錄換成你的路徑，`--user` 用你自己的帳號，才寫得進輸出目錄：

   ```
   docker run --rm --user "$(id -u):$(id -g)" \
     -v "$PWD/deploy/bin:/deploy-bin:ro" -v "$PWD/deploy/docker/trusted-ca:/ca:ro" \
     -v "$PWD/deploy/docker/truststore:/out" \
     --entrypoint /deploy-bin/runline-build-truststore \
     runline-engine:local /ca /out/truststore.p12
   ```

   （映像名稱與標籤依你建置的為準；腳本的結束代碼 1 表示目錄不可讀或其中有檔案不是憑證，不產生檔案。）
3. 疊加 `docker/compose.oidc-ca.yaml`（`COMPOSE_FILE=compose.yaml:compose.oidc.yaml:compose.oidc-ca.yaml`）。它唯讀掛載 `TRUSTSTORE_HOST_DIR`（預設 `deploy/docker/truststore`）並設 `JDK_JAVA_OPTIONS`。信任庫的密碼 `changeit` 是 JDK 自己 `cacerts` 的密碼，只保護公開憑證的完整性，不是機密。
4. 映像更新（JDK 隨之更新）或 CA 更換時，重做信任庫。

瀏覽器映像（`compose.browser.yaml`）已有同等機制：把 PEM 放進 `TRUSTED_CA_HOST_DIR`，不需要上面的步驟。

**systemd**：用主機的 JDK 做信任庫（`runline-build-truststore` 找 `JAVA_HOME` 或 `java` 所在的 JDK）：`/opt/runline/bin/runline-build-truststore /etc/runline/trusted-ca /etc/runline/truststore.p12`，然後在 `runline.env` 設 `JDK_JAVA_OPTIONS`（範例已列）。已用瀏覽器 drop-in 時改用其 `RUNLINE_TRUSTED_CA_DIR`（`runline.env` 與 drop-in 都設 `JDK_JAVA_OPTIONS` 會互相取代，見 `runline.env.example`）。

**檢查**：在 Engine 所在的主機或容器網路，對 issuer 的探索文件做 HTTPS 請求，確認憑證鏈可驗證（`openssl s_client -connect <主機>:443 -CAfile <根憑證>` 看 `Verify return code: 0 (ok)`）；憑證的主題別名（SAN）必須含 issuer 的主機名稱。最終的檢查是登入一次。

驗證狀況：以自建 CA 簽發的 Keycloak 實測（見上「已驗證」）。**未用 authentik 的真實 CA 驗證**〔待確認，WI-1204〕。

## 確認 claim 正確

先確認 authentik 端，再確認 Engine 端：

1. **provider 的預覽**〔待確認〕：authentik 的 provider 頁面有預覽（Preview）分頁，可選使用者，顯示以該 provider 的設定為其簽發的 token 內容。檢查：有 `preferred_username`；有群組 claim（名稱和 `RUNLINE_OIDC_GROUPS_CLAIM` 相同）且值是群組**名稱**的清單，含 `runline-admin` 或 `runline-developer`；`iss` 和你填的 `RUNLINE_OIDC_ISSUER` 逐字相同。預覽若沒有該功能，就用下面第 3 步的結果判斷。
2. **探索文件**：用瀏覽器或 `curl`（私有 CA 時帶 `--cacert`）取得 `<issuer>/.well-known/openid-configuration`（authentik 的 application 頁面也列出這個網址〔待確認〕）。檢查：`issuer` 欄位與 `RUNLINE_OIDC_ISSUER` 逐字相同；`id_token_signing_alg_values_supported` 含 `RS256`；`scopes_supported` 含 `offline_access`；`jwks_uri` 的網址可取得並有金鑰。
3. **用 Engine 登入一次**（最終依據）：先用 `GET /api/v1/auth/methods` 確認登入已啟用（回應含 `"oidc":{"enabled":true}`），在 Console 登入頁選「使用 authentik 登入」。登入後以該 session 呼叫 `GET /api/v1/auth/session`：
   - `name` 應為該使用者在 authentik 的 username（證明 `preferred_username` 正確）；
   - `role` 應符合其群組（證明群組 claim 的名稱與內容正確）。
   - 失敗時看網址的 `login_error` 代碼與 Engine 的 log，對照下節。
4. **重新驗證**：在 authentik 把該使用者移出群組，等一個重新驗證間隔（預設 60 秒）再操作：角色應降級或工作階段結束。這證明 refresh token 與 refresh 後的 ID token 仍帶群組 claim。

## 排錯對照

登入失敗時，Engine 把瀏覽器導回 Console 登入頁並帶 `?login_error=<代碼>`（頁面轉成文字後把它從網址拿掉；要看原值就看瀏覽器歷史或代理的存取記錄）。Engine 的 log 對每個拒絕寫一行警告：`Sign-in refused: <代碼> (<使用者名稱，若已知>)[: <提示>]`；log 與回應都不含 token、secret、授權碼。同一個代碼在 log 裡可能有多種原因，下表列出原因與處理。

| 代碼 | log 的原因類別 | 常見原因 | 處理 |
|---|---|---|---|
| `provider_unavailable` | 登入起點：`The provider could not be used to start a sign-in: UNAVAILABLE`；callback：`Sign-in refused: provider_unavailable` | Engine 連不到 authentik（網路、防火牆、DNS）；**憑證不被信任（私有 CA）**；憑證的主機名稱不符；探索文件的 `issuer` 與 `RUNLINE_OIDC_ISSUER` 不是同一個字串（結尾斜線）；authentik 回 5xx；逾時（連線與讀取各 5 秒）；探索文件不是 JSON（代理的錯誤頁） | 從 Engine 所在處對 issuer 做 HTTPS 請求（見「檢查」）；逐字比對 issuer；私有 CA 做信任庫。授權碼只能用一次，callback 階段的失敗不重試，使用者重新登入。authentik 恢復後下次登入自動重新取得探索文件，不必重啟 Engine |
| `invalid_token` | `Sign-in refused: invalid_token`，**有提示且含演算法名稱**（如 HS256） | provider 沒選簽章金鑰（對稱簽章） | 選一把 RSA 簽章金鑰（見「簽章金鑰」） |
| `invalid_token` | `Sign-in refused: invalid_token`，**無提示** | client secret 不對或剛輪替過；`RUNLINE_OIDC_CLIENT_ID` 不對（audience 不符）；ID token 的 `iss` 不符；`nonce` 不符；Engine 與 authentik 的時鐘差超過 60 秒；ID token 沒有 `preferred_username`；換 token 被 authentik 以 4xx 拒絕（包含 redirect URI 與送出的不符） | 對照 authentik 的事件記錄〔待確認：事件頁面位置〕；檢查 client secret、client ID、時鐘（NTP）、`preferred_username` 是否在 ID token（見「確認 claim 正確」）。Engine 的 log 刻意不細分，避免洩漏 |
| `refresh_unavailable` | `Sign-in refused: refresh_unavailable (<名稱>): the provider returned no refresh token; …` | provider 沒有回傳 refresh token：`offline_access` 沒選或沒授權 | 見「`offline_access` 與 refresh token」。不建立使用者與工作階段 |
| `not_authorized` | `Sign-in refused: not_authorized (<名稱>)` | 使用者不在 `runline-admin` 也不在 `runline-developer`；群組 claim 不存在或名稱與 `RUNLINE_OIDC_GROUPS_CLAIM` 不同；claim 的值是 UUID 或路徑而不是名稱；群組名稱和 `RUNLINE_OIDC_ADMIN_GROUP`／`RUNLINE_OIDC_DEVELOPER_GROUP` 不同（大小寫） | 把使用者加進群組；用預覽或 token 內容確認 claim（見「確認 claim 正確」）；群組 claim 只在 scope mapping 的 scope 名稱是 `profile` 時才會被 Engine 取到 |
| `access_denied` | `Sign-in refused: access_denied` | 使用者在 authentik 取消、拒絕同意，或 authentik 因政策拒絕並帶 `error` 回來（application 沒綁定該使用者） | 確認 application 的綁定包含該使用者的群組；使用者重新登入 |
| `invalid_state` | `Sign-in refused: invalid_state` | callback 的 `state` 不認得：登入起點之後超過 10 分鐘才完成、回上一頁或重複送出同一個 callback（`state` 與授權碼各只能用一次）、書籤了 callback 網址 | 重新從 Console 登入頁開始。頻繁出現而不是偶發，檢查多個 Engine 是否各用不同的資料庫 |
| `user_disabled` | `Sign-in refused: user_disabled (<名稱>)` | 管理員在 runline 停用了這個使用者 | 管理員在 Console 的使用者頁（或 `API_TOKENS` 的 token 呼叫 API）啟用 |
| `name_conflict` | `Sign-in refused: name_conflict (<名稱>)` | 該 `preferred_username` 與 `API_TOKENS` 的名稱，或與另一個 authentik 使用者已用的名稱相同 | 在 authentik 改 username，或改 `API_TOKENS` 的名稱；不會建立使用者 |
| `too_many_logins` | `Sign-in not started: too many are under way` | 同時進行中的登入數達上限（`RUNLINE_OIDC_MAX_PENDING_LOGINS`，預設 200，每個登入保留 10 分鐘） | 稍候再試；持續出現就是被濫用或上限太低 |

另外兩個不經 `login_error` 的症狀：

- **登入後立刻回到登入頁，沒有錯誤代碼**：cookie 沒被瀏覽器保存，見「對外網址與 cookie」的第二列（`https` 設定、網址是 `http`）；或瀏覽器擋了 cookie。
- **登入後的操作得到 403 `origin_invalid`／`csrf_invalid`**：`RUNLINE_PUBLIC_BASE_URL` 與使用者實際用的網址不同源（見上）。
- **已登入的使用者被突然登出**：refresh token 被 authentik 拒絕（使用者被停用、工作階段被撤銷、refresh token 過期（有效期限短於 `RUNLINE_SESSION_ABSOLUTE_SECONDS`）、client secret 被更換）。

## 待確認事項（交 WI-1204）

下列需要真實的 authentik 才能回答，**目前沒有結果**。WI-1204 實測後填在「實測結果」欄。

| 項目 | 對應 | 本手冊目前假設的 | 實測結果 |
|---|---|---|---|
| 群組 claim 名稱，以及 authentik 內建的 `profile` 對應是否已帶出 | [01-requirements](../docs/stable/oidc-login/01-requirements.md) 待確認問題 1 | 名稱 `groups`、值為群組名稱清單；內建 `profile` 對應已包含 | 待填 |
| authentik 的憑證是否由私有 CA 簽發；Engine 的 JVM 以該 CA 完成登入 | 問題 2 | 未知；機制已用自建 CA 與 Keycloak 驗證，見「已驗證」 | 待填 |
| 實際使用的 redirect URI，以及 Engine 前是否有代理 | 問題 6 | `RUNLINE_PUBLIC_BASE_URL` 加 `/api/v1/auth/oidc/callback` | 待填 |
| Access token 有效期限（實際採用） | | 數分鐘（authentik 預設 5 分鐘） | 待填 |
| Refresh token 有效期限（實際採用；不短於 `RUNLINE_SESSION_ABSOLUTE_SECONDS`＝604800 秒） | | 30 天（authentik 預設） | 待填 |
| 未選簽章金鑰時 authentik 是否為 HS256 對稱簽章（症狀 `invalid_token` 與 log 提示） | | 是 | 待填 |
| 沒有 `offline_access` 或不發 refresh token 的條件（症狀 `refresh_unavailable`） | | 未選 `offline_access` 時不回傳 refresh token | 待填 |
| 自建 scope mapping 的 scope 名稱必須是 `profile`（Engine 只要求 `openid profile offline_access`） | | 其他名稱的 scope 不會被套用 | 待填 |
| issuer 網址形式與結尾斜線；探索文件的 `issuer` 與網址是否一致 | | `https://<主機>/application/o/<slug>/` | 待填 |
| application 綁定群組後，不在群組的使用者在 authentik 的表現 | | 看不到也無法啟動 | 待填 |
| 更換 client secret 後舊 secret 是否立即失效 | | 立即 | 待填 |
| refresh 回傳的 ID token 仍帶群組 claim（使用者先前已確認，需實測） | | 帶 | 待填 |
