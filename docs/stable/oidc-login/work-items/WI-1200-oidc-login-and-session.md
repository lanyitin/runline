# WI-1200 Engine 的 OIDC 登入與工作階段

本文回答：Engine 側的 OIDC 登入要達成什麼行為。狀態：已核可（2026-10-10）。決策見 [ADR-029](../adr/ADR-029-oidc-login-with-server-session.md)。工作儲存庫：runline（engine）。

## 驗收條件

1. **設定**：依 [04-deployment.md](../04-deployment.md) 的設定項。全部未設定時功能關閉且行為與現在相同；缺必要項、client secret 兩種來源同時設、兩個群組名稱相同、閒置時間大於絕對時間，Engine 列出所有問題並拒絕啟動；啟動時不連線 authentik；authentik 不可用不影響啟動與就緒探測。
2. **登入流程**：依 [05-ipc.md](../05-ipc.md)。Authorization Code 加 PKCE 的機密 client；驗證 ID token 的簽章、issuer、audience、到期與 nonce；state 單次使用且短效；返回路徑只接受本站相對路徑，絕對網址、協定相對網址與其他形式一律忽略。同一 state 或授權碼重送不建立第二個工作階段。
3. **角色**：群組 claim 含 admin 群組 → `admin`；否則含 developer 群組 → `developer`；兩者皆無（含 claim 缺失）→ 拒絕登入（`not_authorized`），不建立使用者與工作階段。同時在兩個群組為 `admin`。群組只讀自已驗證的 ID token。
4. **使用者與名稱**：首次登入以（issuer，subject）建立使用者，名稱取 authentik 的 username 並固定不變，顯示名稱每次登入更新。username 與 `API_TOKENS` 的名稱相同，或與另一位使用者的名稱相同 → 拒絕登入（`name_conflict`），不建立任何資料。authentik 中改名後再登入，名稱不變且仍是同一位使用者，其既有 pipeline 與綁定仍屬於他。並行的首次登入不產生重複使用者。Engine 啟動時，`API_TOKENS` 的名稱與既有使用者名稱重複 → 拒絕啟動，訊息只指出名稱。
5. **工作階段**：cookie 為 HttpOnly、`SameSite=Lax`，對外網址為 https 時 `Secure`；識別碼不可預測；資料庫不保存可還原的識別碼；記錄登入當下的角色與 CSRF token；閒置與絕對期限依設定判斷，過期即無效（不依賴清理）；登出刪除工作階段並清除 cookie，且冪等。登入時以 `offline_access` 取得 refresh token，加密後隨工作階段保存（金鑰來自 `RUNLINE_SESSION_ENCRYPTION_KEY`，不入資料庫、不入 log）；缺金鑰或格式不合，Engine 拒絕啟動。角色變更與撤銷由 [WI-1205](WI-1205-revalidation-and-user-disable.md) 負責。
6. **工作階段查詢**：有效工作階段回傳名稱、角色、顯示名稱與 CSRF token；無效回 401。
7. **錯誤**：callback 的失敗導回 Console 登入頁並帶穩定的錯誤代碼：使用者取消、state 無效或逾時、ID token 驗證失敗、`not_authorized`、`name_conflict`、authentik 不可用（登入起點的失敗見 [WI-1208](WI-1208-login-start-failure-redirect.md)）。OIDC 未啟用時，相關端點回 404。
8. **資料與遷移**：依 [06-data-model.md](../06-data-model.md) 新增使用者、工作階段與登入暫存；過期的工作階段與登入暫存由既有保留清理機制移除，遵守其批次與不重疊的原則；遷移以既有一次性 `migrate` 執行。
9. **可觀測性與機密**：登入成功、拒絕（含原因類別）、登出、名稱衝突各有 log、計數器與 trace；log 只含名稱、角色與原因類別；client secret、授權碼、ID token、refresh token、加密金鑰、工作階段識別碼不出現在任何 log、trace、metric、錯誤訊息與回應。
10. **文件**：`docs/stable/pipeline-engine/` 的 `05-ipc.md`、`06-data-model.md`、`08-api.md`（含免認證端點與 `ApiDocumentationTest` 保持通過）、`04-deployment.md`、README 的設定表同步更新；ADR-012、ADR-017 的狀態欄標明被 ADR-029 修訂與取代。

## 架構約束

- 工作階段狀態只在資料庫，不依賴行程記憶體。
- 授權碼只換一次，不重試。
- 角色與名稱的語意不變；擁有權資料仍以名稱保存。
- 以真實 PostgreSQL 與真實運作的 OIDC 提供者（容器）驗證，涵蓋：admin、developer、兩群組皆無、無群組 claim、名稱衝突、改名後重登、授權碼重送、狀態逾時。
