# WI-1208 登入起點失敗導回 Console，並分離需要 Keycloak 的 e2e

本文回答：登入起點遇到登入過多或 authentik 不可用時，使用者看到什麼；Console 的 OIDC e2e 如何不拖累一般開發。狀態：已核可（2026-10-11）。相依：WI-1201、WI-1202。決策見 [ADR-031](../adr/ADR-031-console-contract-alignment.md)。工作儲存庫：runline（engine、console）。

## 驗收條件

1. **登入過多**：待處理登入達到上限時，登入起點導回 Console 登入頁並帶 `login_error=too_many_logins`，不再回 429 的 JSON。不建立新的登入暫存。
2. **authentik 不可用**：登入起點無法取得探索文件或簽章金鑰時，導回登入頁並帶 `login_error=provider_unavailable`，不再回 503 的 JSON。
3. **不變的行為**：OIDC 未啟用時登入起點仍回 404；`return` 的驗證不變；callback 的失敗導向不變；就緒探測不受影響。
4. **Console 顯示**：登入頁以多語系文字顯示這兩種代碼（Console 已備妥文字），代碼顯示後從網址移除，未知代碼不回顯。以真實 Engine 與真實 OIDC 提供者驗證至少 `too_many_logins` 與 `provider_unavailable` 各一次。
5. **登入方式查詢**：維持只回是否啟用 OIDC，不加顯示名稱；`08-api.md`、`05-ipc.md` 與 `ApiDocumentationTest` 一致。
6. **e2e 分離**：預設的 Console e2e 指令不執行需要 Keycloak 的 OIDC e2e，因此不需要 Keycloak 與開啟 OIDC 的 Engine；OIDC e2e 有獨立的指令，缺少所需環境時失敗而不是略過。`console/e2e/README.md` 與根 `README.md` 說明兩個指令與設定步驟，並說明 OIDC e2e 在發版前與 WI-1204 執行。
7. **機密**：導向網址、log、錯誤與回應不含 client secret、授權碼、state、nonce、工作階段識別碼。
8. **文件**：`docs/stable/pipeline-engine/` 的相關文件與實作一致。

## 架構約束

- 不改變已驗收的登入、工作階段、CSRF 與重新驗證行為；只改登入起點的兩種失敗呈現。
- 不新增免認證端點。
- 測試使用真實 PostgreSQL 與真實運作的 OIDC 提供者容器，不使用 Stub 或 Mock。
- e2e 分離只改指令的組成，不得以環境變數判斷後略過測試。
