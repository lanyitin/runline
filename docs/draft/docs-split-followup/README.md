# docs-split-followup：文件拆分與 Wiki 映像之後的收尾

本主題回答：docs-split 與 wiki-mirror 完成後，還有哪些遺留資源要清理、哪些文件敘述要更正。狀態：提案中。

## 範圍

- 範圍內：遺留的分支、探測資源、Actions 紀錄與本機 worktree；`runline-transcribe`、`runline-digest-rss` 文件中過時的位置敘述。
- 範圍外：agent 定義是否貼合兩個接收專案（另案）、兩個接收專案的 Wiki 映像、文件實質內容的改寫。

## 工作項

| 編號 | 目標 | 相依 | 詳細文件 |
|---|---|---|---|
| #22 | 清理遺留資源 | 使用者授權刪除遠端分支 | [#22](work-items/22-cleanup-leftovers.md) |
| #23 | 更正 `runline-transcribe` 文件中過時的位置敘述 | 無 | [#23](work-items/23-transcribe-location-wording.md) |
| #24 | 更正 `runline-digest-rss` 文件中過時的位置敘述 | 無 | [#24](work-items/24-digest-rss-location-wording.md) |

#23 與 #24 互不相依，可並行，各在自己儲存庫的獨立 worktree 與分支上進行。#22 與兩者無相依。

## 待確認問題

- 已合併的分支是否改為由 Forgejo 在合併後自動刪除（儲存庫設定）？可避免日後再累積；是設定變更，不在本主題的項目內。
- 不是本次流程建立的遠端分支（`simplify-llm`、`claude/cool-ride-qexzgi`）是否清理，由使用者決定；本主題不動它們。
