# ADR-031 Console 與 Engine 的登入契約對齊

狀態：已核可（2026-10-11）。修訂 [ADR-029](ADR-029-oidc-login-with-server-session.md) 與 [ADR-030](ADR-030-signing-and-refresh-token-requirements.md) 中登入起點回 503 與 429 的部分。回答：WI-1202 驗收時發現的 Engine 與 Console 契約落差如何處理。

## 背景

登入按鈕是整頁導向，登入起點的失敗若回 JSON，瀏覽器只會顯示原始內容。登入方式查詢只回是否啟用 OIDC，沒有按鈕名稱。OIDC 啟用時匿名載入會查詢一次工作階段並得到 401。需要 Keycloak 的 OIDC e2e 使預設的 e2e 指令依賴額外環境。

## 決策

| 主題 | 結論 |
|---|---|
| 按鈕名稱 | 不新增顯示名稱。範圍只驗證 authentik，登入方式查詢只回是否啟用 OIDC，按鈕固定顯示「使用 authentik 登入」 |
| 登入起點失敗 | 429 `too_many_logins` 與 503 `provider_unavailable` 改為導回 Console 登入頁並帶 `login_error`，與 callback 的失敗一致 |
| 匿名載入的 401 | 接受，不新增免認證端點 |
| Console e2e | 需要 Keycloak 的 OIDC e2e 從預設指令分離成獨立指令；獨立指令缺少環境時失敗，不略過 |

## 取捨

- 不加按鈕名稱：少一個設定項，換來單一 provider 假設；日後支援其他提供者時再加。
- 導回而非回 JSON：使用者看到 Console 的說明；代價是登入起點的兩種失敗不再有 HTTP 狀態碼供程式判斷，此端點只服務瀏覽器導覽。
- 接受 401 雜訊：瀏覽器 console 多一行錯誤，對使用者無影響。
- e2e 分離：日常開發不需 Keycloak；預設指令不涵蓋 OIDC 流程，要靠發版前與 WI-1204 執行獨立指令補上。

## 後果

- 實作見 [WI-1208](../work-items/WI-1208-login-start-failure-redirect.md)。
