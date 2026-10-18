# #2：stable 合併後同步到 Wiki

本項回答：要讓 Wiki 成為 stable 的映像，須達成什麼行為。狀態：提案中。Issue：#2。相依：決策 #1 核可，以及 [README 的待確認問題](../README.md)。

## 目標

`docs/stable/` 在 `main` 上有變動時，Forgejo Wiki 自動更新為與 stable 一致，並有依主題列出的首頁目錄。

## 驗收條件

- 只有 `docs/stable/` 的變動會觸發；其他路徑的變動不觸發。
- 同步後 Wiki 的頁面集合與 stable 的文件集合一一對應；stable 中被刪除或改名的文件，Wiki 同步移除。
- 保留主題階層，不同主題的同名文件不互相覆蓋。
- 首頁目錄列出各主題的入口文件，順序穩定，且每次同步重新產生。
- 重複執行同一份內容的同步，不產生新的 Wiki 提交。
- 同步失敗時，工作流標為失敗，Wiki 維持上一次成功的狀態。
- `docs/draft/` 的內容不出現在 Wiki。
- 憑證只從 Forgejo secrets 讀取，不出現在儲存庫與日誌。

## 架構約束

- 單向：只由 stable 寫入 Wiki，不從 Wiki 讀回。
- 以 Forgejo Actions 執行，沿用 `.forgejo/workflows/` 現有慣例。
- 遵循 [#1](../adr/1-wiki-is-readonly-mirror.md)。
