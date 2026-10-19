# #2：stable 合併後同步到 Wiki

本項回答：要讓 Wiki 成為 stable 的映像，須達成什麼行為。狀態：已核可，已完成。Issue：#2。相依：[#6](../adr/6-mirror-mapping-links-deletion-credential.md)（已核可）。

## 目標

`docs/stable/` 在 `main` 上有變動時，Forgejo Wiki 自動更新為與 stable 一致，文件間連結可點擊，並有依主題列出的首頁目錄與側邊欄。

## 驗收條件

**同步**
- 只有 `docs/stable/` 的變動會觸發；其他路徑的變動不觸發。
- 每次同步以當下的 stable 完整重新產生 Wiki 內容；stable 中被刪除或改名的文件，Wiki 同步移除。
- 頁面集合與 stable 的 Markdown 文件一一對應；頁名可逆還原為原路徑，不同主題的同名文件不碰撞。
- 重複執行同一份內容的同步，不產生新的 Wiki 提交。
- 同步失敗時，工作流標為失敗，Wiki 維持上一次成功的狀態。
- `docs/draft/` 的內容不出現在 Wiki。

**連結**
- 依 #6 的連結處理表改寫：stable 內文件連結（含錨點）、目錄連結、儲存庫內 stable 之外的連結（指向 `main` 上的檔案）、無法解析的連結（保留文字並在日誌列出）、外部網址與頁內錨點（不變）。
- 以目前 stable 為樣本，改寫後沒有指向不存在 Wiki 頁的連結；stable 本身的檔案不被修改。

**目錄**
- 首頁目錄與側邊欄依主題階層列出各入口文件，順序穩定，每次同步重新產生。

**憑證與安全**
- 使用 Actions 內建 token，並宣告最小權限；不建立專用 secret。
- 憑證不出現在儲存庫與日誌；不關閉 TLS 驗證。

## 架構約束

- 單向：只由 stable 寫入 Wiki，不從 Wiki 讀回。
- 以 Forgejo Actions 執行，沿用 `.forgejo/workflows/` 現有慣例（action 以 commit id 釘版、組態在檔頭說明）。
- 遵循 [#1](../adr/1-wiki-is-readonly-mirror.md) 與 #6。
- 內建 token 在實作環境不可寫入 Wiki 時，停下回報，不自行改用其他憑證。
