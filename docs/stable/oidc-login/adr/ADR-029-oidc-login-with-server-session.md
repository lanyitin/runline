# ADR-029 以 authentik OIDC 登入，Engine 當 OIDC client 並維持伺服器端工作階段

狀態：已核可（2026-10-10）。取代 [ADR-017](../../pipeline-engine/adr/ADR-017-console-websocket-and-token.md) 中對「Cookie 工作階段」的否決，以及「Token 登入是 Console 唯一方式」的前提；修訂 [ADR-012](../../pipeline-engine/adr/ADR-012-api-authentication.md) 的認證範圍與免認證端點清單。其餘（輪詢取代 WebSocket、不把憑證放進網址、XSS 緩解）不變。回答：人如何用 authentik 登入、角色如何決定、登入後如何維持，以及與既有 token 如何並存。

## 背景

ADR-012 只有 Bearer token；ADR-017 預告 OIDC 須另開 ADR。使用者要求 Forgejo 式登入，角色由 authentik 群組決定。方案比較見 [03-options.md](../03-options.md)。

## 決策

1. **Engine 是 OIDC client**：Authorization Code 流程，加 PKCE，機密 client（client secret 由組態提供）。登入由 Engine 的端點完成，Console 只以頁面導覽進入，不處理授權碼或 token。以 state 與 nonce 防重放與 CSRF；登入後的返回位置只接受本站相對路徑。
2. **角色由群組 claim 決定，登入時與撤銷重新驗證時判斷**：
   - 屬於 admin 群組（預設 `runline-admin`）→ `admin`。
   - 否則屬於 developer 群組（預設 `runline-developer`）→ `developer`。
   - 兩者都不屬於 → 拒絕登入，不建立工作階段，不建立使用者；Console 顯示明確的「無權使用」。
   - 群組名稱與 claim 名稱由組態提供。群組只從通過驗證的 ID token 讀取。
3. **身分名稱**：
   - 使用者以（issuer，subject）識別。首次登入時以 authentik 的 username 建立 runline 內的名稱，名稱之後固定不變，不隨 authentik 改名而變（擁有權綁在名稱上，見 ADR-020）。另保存可更新的顯示名稱。
   - 名稱在 runline 內唯一。若首次登入的 username 已是 `API_TOKENS` 的名稱，或已被另一位使用者佔用，拒絕登入並明確告知名稱衝突，不自動改名。
   - Engine 啟動時，若 `API_TOKENS` 的名稱與已存在的使用者名稱相同，拒絕啟動，訊息只指出名稱。
4. **工作階段**：
   - 登入成功後建立伺服器端工作階段，以不可預測的不透明識別碼放在 HttpOnly、`SameSite=Lax`、`Secure`（對外網址為 https 時）的 cookie；資料庫只保存識別碼的雜湊。
   - 工作階段記錄目前的角色；閒置與絕對存活時間由組態決定；登出刪除工作階段。角色的變更與撤銷依決策 9。
   - 登出只結束 runline 的工作階段，不登出 authentik。
5. **與 Bearer 並存**：
   - 請求帶 `Authorization` 標頭時只依 Bearer 判斷；否則看工作階段 cookie。兩者解析出同樣語意的「名稱與角色」，授權規則不分來源。
   - Cookie 認證的請求，凡會改變狀態的方法，須帶工作階段專屬的 CSRF token 並通過 `Origin` 同源檢查；Bearer 請求不需要。
   - WebSocket 的 log 串流維持只接受 Bearer（Console 使用輪詢）。
6. **免認證清單新增**：登入起點、callback、登入方式查詢（是否啟用 OIDC）。它們不接受也不回傳機密資料。其餘端點預設需認證。
7. **OIDC 為選配**：OIDC 組態全部未設定時功能關閉，Engine 行為與現在相同；只設定一部分時拒絕啟動。authentik 暫時不可用不影響 Engine 啟動與就緒探測，只影響登入（回 503）。
8. **機器對機器不走 OIDC**：CLI、CI 沿用 `API_TOKENS`。
9. **立刻踢人**（兩條途徑，都須在分鐘內生效）：
   - **跟隨 authentik 的變更**：登入時以 `offline_access` 取得 refresh token，加密後隨工作階段保存（加密金鑰由組態提供，不入資料庫）。Engine 對每個有效的工作階段，每隔固定間隔（預設 60 秒）以 refresh token 向 authentik 重新驗證：
     - 換得的新 ID token 重新計算角色；角色改變即更新工作階段，之後的請求用新角色。
     - 不再屬於任何允許群組、使用者被停用、或 authentik 拒絕 refresh（工作階段被撤銷）→ 立即刪除 runline 的工作階段，使用者下一個請求得到 401。
     - 重新驗證只在工作階段被使用時進行（閒置的工作階段不打擾 authentik），並在處理請求之前判斷「距上次驗證是否超過間隔」；超過就先驗證再放行，因此權限被收回後，最久延遲約一個間隔。
     - authentik 無法連線時，工作階段在寬限時間（預設 15 分鐘，自上次成功驗證起算）內繼續有效，之後失效並要求重新登入；寬限時間設 0 即嚴格失敗關閉。
   - **管理員在 runline 直接停用**：管理員可停用某位使用者：其所有工作階段立即刪除，且在管理員重新啟用前不能再登入（即使 authentik 仍允許）。停用狀態保存於使用者資料，並可列出與結束個別工作階段。停用不影響 `API_TOKENS` 的 token（token 的撤銷仍是修改組態並重啟）。
   - 不採用 OIDC Back-Channel Logout 作為主要機制：它依賴 authentik 在群組變更時發出通知，無法保證涵蓋「移出群組」；它可日後另加為加速手段。

## 取捨

| 項目 | 優點 | 缺點 |
|---|---|---|
| 伺服器端工作階段 | 憑證不進 JavaScript；可撤銷；角色可在伺服器端控管 | 新增資料表與 CSRF；多一條認證軌；有狀態（工作階段放資料庫，不依賴記憶體） |
| 定期以 refresh token 重新驗證 | 在 authentik 收回權限後約一個間隔內生效；角色變更自動套用 | Engine 要保存加密的 refresh token（多一把金鑰）；使用中的工作階段每間隔多一次對 authentik 的呼叫；authentik 中斷時須在「可用性」與「立刻」間取捨（寬限時間） |
| 管理員停用使用者 | 不依賴 authentik，立即生效；可覆蓋 authentik 仍允許的情況 | 多一個管理介面與使用者狀態 |
| 名稱固定、以 subject 識別 | authentik 改名不會讓使用者失去自己的 pipeline | 名稱與 authentik 目前的 username 可能不同；佔用過的名稱不會釋放 |
| 衝突時拒絕而非改名 | 不會默默繼承他人的擁有權 | 使用者需要請管理員處理衝突 |
| 群組只讀 ID token | 單一可信來源，已驗簽 | authentik 須讓 ID token 帶群組 claim |

被否決的做法：SPA 做 PKCE 並以 JWT 當 Bearer（token 在 JavaScript、無伺服器撤銷）；反向代理注入身分標頭（信任邊界在代理）。

## 後果

- ADR-012 的「角色語意固定、provider 可替換」成立；它的認證範圍從單一 Bearer 擴大為 Bearer 與工作階段，免認證清單新增三項。
- ADR-017 的 sessionStorage 與 BroadcastChannel 只屬於 token 登入方式；OIDC 登入的 Console 不持有憑證，登入狀態由 cookie 與 `GET` 工作階段查詢決定，跨分頁自然共用，登出由伺服器結束後各分頁在下次請求得到 401。
- 資料模型新增使用者、工作階段與登入進行中的暫存紀錄（[06-data-model.md](../06-data-model.md)）。
- 契約見 [05-ipc.md](../05-ipc.md)，設定見 [04-deployment.md](../04-deployment.md)。
- 實作見 [work-items/README.md](../work-items/README.md)。

## 待確認問題

見 [01-requirements.md](../01-requirements.md) 的待確認問題。
