# ADR-030 OIDC 登入的前提：非對稱簽章、必須取得 refresh token、登入暫存上限

狀態：已核可（2026-10-10）。修訂 [ADR-029](ADR-029-oidc-login-with-server-session.md) 的決策 1 與 9。回答：ID token 用哪種簽章才接受、拿不到 refresh token 時怎麼辦、如何避免未登入的登入起點被濫用。

## 背景

WI-1200 實作後發現三個缺口：authentik 的 provider 若沒有選簽章金鑰，ID token 以 client secret 做對稱簽章；authentik 不一定回傳 refresh token，而實作在沒有時仍建立工作階段；登入起點不需認證卻每次寫一筆暫存。

## 決策

1. **只接受非對稱簽章（RS256）的 ID token**。Engine 以探索文件宣告的公鑰驗證，不使用 client secret 驗簽。authentik 的 provider 須選擇簽章金鑰；否則登入失敗，並在 log 指出簽章演算法不被接受，讓維運者能排查。
2. **沒有 refresh token 就拒絕登入（失敗關閉）**。ADR-029 的「立刻踢人」依賴 refresh token 重新驗證；沒有它的工作階段無法被收回權限，與需求矛盾。拒絕時不建立使用者與工作階段，Console 顯示明確的設定錯誤（錯誤代碼 `refresh_unavailable`），log 指出提供者未回傳 refresh token。
3. **待處理的登入暫存有數量上限**。超過上限時，新的登入起點回 429 並帶 `Retry-After`，既有的暫存與進行中的登入不受影響；過期暫存照常清理。上限值由組態決定，預設值足以涵蓋數十人同時登入並留有餘裕。

## 取捨

| 項目 | 優點 | 缺點 |
|---|---|---|
| 只接受 RS256 | client secret 不兼作驗簽金鑰，洩漏面小 | authentik 須多設一個簽章金鑰 |
| 無 refresh token 即拒絕 | 保證每個工作階段都可被撤銷 | provider 設定錯誤時整個登入無法使用，直到修正 |
| 暫存上限 | 阻止未認證請求灌爆資料庫 | 惡意請求可暫時占滿名額，使正常登入被 429；限於 10 分鐘暫存期內 |

## 後果

- 新增 [WI-1207](../work-items/WI-1207-require-refresh-token.md)；對 WI-1201、WI-1202、WI-1203、WI-1204、WI-1205 新增了驗收條件。
- 未解決：占滿名額的阻斷風險只靠暫存期限緩解，需要時另做依來源的限流。
