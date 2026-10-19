# #22：清理 docs-split 與 wiki-mirror 過程的遺留資源

本項回答：哪些遺留資源要清掉、清到什麼狀態。狀態：已核可，未開始。Issue：#22。相依：使用者授權刪除遠端分支。

## 目標

過程中產生、且已不需要的分支、探測資源與本機 worktree 都被移除，只留下仍有用的內容。

## 盤點（撰寫時的現況）

| 位置 | 項目 | 處理 |
|---|---|---|
| runline 遠端分支 | 已合併：`adr-task-1`、`adr-task-4`、`adr-task-6`、`adr-task-6-promote`、`adr-task-9`、`adr-task-9-promote`、`adr-task-13`、`adr-task-13-promote`、`adr-task-16-clean-runline`、`adr-task-2-sync-wiki`、`adr-task-20` | 刪除 |
| runline 遠端分支 | 未合併的探測分支：`adr-task-2-probe-actions`（歷史含關閉 TLS 驗證的版本）、`adr-task-2-ci-check` | 刪除，不得合併 |
| runline 遠端分支 | `simplify-llm`、`claude/cool-ride-qexzgi` | 不是本流程建立，不動 |
| `runline-transcribe`、`runline-digest-rss` 遠端分支 | 已合併：`adr-task-14-receive-transcribe`、`adr-task-15-receive-digest-rss` | 刪除 |
| Wiki 倉庫遠端分支 | 探測遺留：`master`、`adr-task-2-probe-ci`、`ci-check-read`、`ci-check-write` | 刪除；預設分支 `main` 保留 |
| Wiki 倉庫 `main` | 已由同步工作流完整覆蓋，不含探測頁 | 維持現狀 |
| Actions 執行紀錄 | 探測與暫時檢查的執行（runline 的執行 #14 至 #18 一帶） | 儘可能移除；`fj` 沒有刪除指令，無法移除時列出由使用者處理 |
| 本機（runline） | `.claude/worktrees/` 下三個 worktree（其中一個鎖定）與對應分支，以及已合併的本機 `adr-task-*` 分支 | 移除 |
| 本機（兄弟目錄） | `runline-transcribe-adr-task-14`、`runline-digest-rss-adr-task-15` 兩個 worktree 與本機分支 | 移除 |
| 本機（兄弟目錄） | `runline-transcribe`、`runline-digest-rss` 的 `main` 落後遠端 | 更新到遠端 `main` |

清單是撰寫時的快照；執行時重新盤點，以當時的實際狀態為準。

## 驗收條件

- 上表標為「刪除」「移除」的項目都不存在；標為「不動」「保留」的項目都還在。
- `main` 分支、所有尚未合併的非探測分支、Wiki 預設分支與其內容，沒有被動到。
- 清理前先確認每個「已合併」分支的提交確實已在 `main`；不確定的列出，不刪。
- 清理後 Wiki 仍然可瀏覽，同步工作流下一次執行不受影響。
- 回報列出已清理與無法清理的項目。

## 架構約束

- 刪除遠端分支目前被使用者的權限設定拒絕；本項只能在使用者明確授權、且權限設定允許的環境執行，不得繞過。
- 不得刪除或改寫任何已合併內容的歷史。
- 不得停用 Wiki 或改動儲存庫設定。
