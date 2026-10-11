# WI-1203 部署範例與 authentik 設定手冊

本文回答：維運者如何在 Docker 與 systemd 啟用 OIDC，並在 authentik 端完成設定。狀態：已核可（2026-10-10）。相依：WI-1200。設定見 [04-deployment.md](../04-deployment.md)。工作儲存庫：runline（deploy、文件）。

## 驗收條件

1. **範例設定**：Docker 的 `.env.example` 與 systemd 的 `runline.env.example` 含所有 OIDC 設定項，OIDC 預設關閉，只放占位符；工作階段重新驗證間隔與寬限時間、refresh token 加密金鑰；client secret 與加密金鑰以檔案形式提供的方式（Docker secret、systemd credential）有範例，與金鑰庫的做法一致；真實值不進版本庫。
2. **對外網址與 cookie**：範例與說明指出對外網址必須與使用者實際看到的一致，以及 TLS 終止於 Engine 之前時 `Secure` 屬性的行為。
3. **私有 CA**：說明 authentik 使用私有 CA 時 Engine 的 JVM 如何信任它，沿用 `deploy/` 現有的做法；以實際私有 CA 驗證 Engine 能完成登入。
4. **authentik 手冊**（放 `deploy/`，單一主題文件）：建立 provider 與 application 的設定、redirect URI、兩個群組、讓 ID token 帶出群組 claim 與 `preferred_username` 的 scope mapping、`offline_access` 與 refresh token 的有效期限、限制 application 可見者、client secret 的取得與輪替；並有「怎麼確認 claim 正確」的檢查步驟。內容以文字與設定項描述，不放程式碼。
5. **排錯對照**：登入失敗的錯誤代碼與 log 原因類別對應的處理方式。
6. **回報待確認事項**：回報 [01-requirements.md](../01-requirements.md) 待確認問題 1、2、6 的實測結果（群組 claim 名稱、是否私有 CA、實際 redirect URI），並記錄實際採用的 access token 與 refresh token 有效期限（refresh token 不短於 `RUNLINE_SESSION_ABSOLUTE_SECONDS`）。
7. **簽章金鑰與 refresh token**：手冊說明 authentik provider 須選擇簽章金鑰（非對稱 RS256），並說明未選擇時的症狀（登入失敗、`invalid_token`）；說明須允許 `offline_access` 並確認 provider 會回傳 refresh token，並說明未回傳時的症狀（`refresh_unavailable`）；排錯對照包含這兩種代碼。

## 架構約束

- 不在 `deploy/` 放任何真實機密。
- 符合 12-Factor：設定一律來自環境。
