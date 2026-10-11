# WI-1207 沒有 refresh token 就拒絕登入

本文回答：Engine 在提供者沒有回傳 refresh token 時如何拒絕登入，並明確指出設定問題。狀態：已核可（2026-10-10）。相依：WI-1200。決策見 [ADR-030](../adr/ADR-030-signing-and-refresh-token-requirements.md)。工作儲存庫：runline（engine）。

## 驗收條件

1. 授權碼換 token 的回應沒有 refresh token 時，登入失敗：不建立使用者、不建立工作階段，導回 Console 登入頁並帶錯誤代碼 `refresh_unavailable`；已存在的使用者資料不變。
2. 每個成功建立的工作階段都帶加密的 refresh token；不存在「無 refresh token 的工作階段」。
3. log 指出提供者未回傳 refresh token 與應檢查的設定方向（`offline_access` 與提供者設定），只含原因類別，不含 token 或授權碼；計數器按原因類別計入拒絕。
4. ID token 的簽章演算法不是非對稱簽章（例如以 client secret 簽章的 HS256）時，驗證失敗（`invalid_token`），log 指出演算法不被接受；以真實提供者的對稱簽章設定驗證。
5. 以真實 PostgreSQL 與容器中真實運作的 OIDC 提供者驗證：提供者設定為不發 refresh token（或不授與 `offline_access`）時被拒；設定為對稱簽章時被拒；正常設定仍可登入。
6. 文件：`docs/stable/pipeline-engine/05-ipc.md`、`08-api.md`、`04-deployment.md` 的錯誤代碼、簽章要求與 refresh token 要求更新；`ApiDocumentationTest` 通過。

## 架構約束

- 失敗關閉：任何無法保證可撤銷的登入都不成立。
- 不放寬 WI-1200 已驗收的其他行為。
- 不使用 Stub 或 Mock。
