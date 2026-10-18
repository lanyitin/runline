# #2：stable 合併後同步到 Wiki

本項回答：要讓 Wiki 成為 stable 的映像，須達成什麼行為。狀態：提案中，取代 `docs/stable/wiki-mirror/work-items/2-sync-stable-to-wiki.md`。Issue：#2。相依：[#6](../adr/6-mirror-mapping-links-deletion-credential.md) 核可，以及使用者提供寫入憑證。

## 目標

`docs/stable/` 在 `main` 上有變動時，Forgejo Wiki 自動更新為與 stable 一致，文件間連結可點擊，並有依主題列出的首頁目錄與側邊欄。

## 驗收條件

**先決確認**
- 在實作前確認 Wiki 功能已啟用、Forgejo 對頁名中路徑分隔的支援情形，以及 Actions 內建 token 是否能寫入 Wiki。結果與選用的頁名規則依 #6 回報；無法滿足 #6 的前提時停下回報，不自行改變規則。

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

**憑證**
- 憑證只從 Forgejo secrets 讀取，不出現在儲存庫與日誌；權限限於寫入 Wiki 所需。

## 架構約束

- 單向：只由 stable 寫入 Wiki，不從 Wiki 讀回。
- 以 Forgejo Actions 執行，沿用 `.forgejo/workflows/` 現有慣例（action 以 commit id 釘版、組態與 secrets 在檔頭說明）。
- 遵循 [#1](../../../stable/wiki-mirror/adr/1-wiki-is-readonly-mirror.md) 與 #6。
