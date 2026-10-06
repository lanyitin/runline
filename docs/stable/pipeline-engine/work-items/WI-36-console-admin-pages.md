# WI-36 Console 的管理員功能頁

本文回答：管理員在 Console 內如何管理 trigger、白名單、共享資源、unsafe 執行設定與版本刪除。狀態：已核可（2026-10-05）。相依：WI-35。API 見 [08-api](../08-api.md)。

## 行為與驗收條件

- 管理員專屬頁只對管理員顯示並可進入（沿用 WI-34 的角色規則）；開發人員直接輸入網址被擋；API 回 403 時有清楚的呈現。
- **Trigger**：列表、建立（cron 與 webhook；cron 表達式、時區、參數、啟用狀態）、修改、刪除、換發密鑰、觸發紀錄（`outcome`、`reason`、`detail`、`runId`）。webhook 密鑰只在建立與換發的回應出現一次：Console 只在那一刻顯示並提供複製，之後不保存、不寫入任何持久狀態、離開畫面即消失。`invalid_trigger` 的 `problem` 對應到欄位錯誤。
- **白名單**：顯示目前版本、條目（`kind`、`name`、`exactOnly`）與版本歷史；新增、修改、刪除條目與手動重判都先以 `preview=true` 顯示影響（變成 unsafe／safe 的定義、`unreadable`、`redundantEntries`、`unsafeExecutionRevoked`），確認後才套用；`entry_exists`、`entry_covered`、`invalid_entry` 有清楚說明；說明加入條目是信任決定、顯示 `limitations`。
- **共享資源**：列表（容量、啟用、持有者、等待者與時間）、建立、修改容量與啟用狀態（說明降低容量不收回、停用會使等待者失敗）、強制釋放持有者（確認對話）。
- **Unsafe 執行**：在 pipeline 詳細頁，管理員可對某版本的某 pipeline 設定是否允許 unsafe 執行，說明其影響與每版本各自設定、不繼承。
- **版本刪除**：管理員可刪除版本；`in_use` 說明仍被 trigger 或 run 引用。
- 具破壞性或影響大的操作都有確認步驟；操作的變更者以 token 對應的名稱顯示（從不顯示 token）。
- 所有 pipeline 或使用者提供的字串以純文字顯示；所有文字有 zh-TW 與 en。

**驗收方式**
- 使用真實的打包後 Engine、真實 PostgreSQL（Testcontainers）、真實編譯的測試 jar 與真實瀏覽器引擎，以管理員與開發人員兩種 token 驗證；預覽與套用的結果與 API 一致（含白名單變更後 pipeline 判定的實際變化）。webhook 密鑰只出現一次以真實瀏覽器行為驗證。不使用 Stub 或 Mock；需要替代品時使用自製的簡易真實實作（Fake）。

## 架構約束

- 只使用既有 API，不新增或修改端點。
- 不新增 CI；前端測試納入 `check`。
