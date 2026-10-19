# #24：更正 runline-digest-rss 文件中過時的位置敘述

本項回答：搬移之後，`runline-digest-rss` 的文件中哪些敘述要更正。狀態：已核可，未開始。Issue：#24。相依：無。

## 目標

`runline-digest-rss` 文件中描述「文件或專案位於何處」的敘述，與搬移後的現況一致。

## 驗收條件

- 凡敘述與現況不符者更正，例如：專案被描述為「runline 儲存庫旁邊的外部專案」、上線手冊被描述為位於 runline 的 docs。
- 敘述仍然正確者不動，包括：Engine 與 composite build 仍在 runline；指向 runline 內容的引用若尚不是連結，改為指向 runline `main` 的網址。
- 描述當時狀態的歷史敘述保留，不改寫。
- 起點（執行時須以搜尋找出完整清單）：
  - `README.md` 第 17 行。
  - `docs/stable/digest-rss/work-items/README.md` 第 7 行。
  - `docs/stable/digest-rss/01-requirements.md` 第 37 行。
  - `docs/stable/digest-rss/work-items/` 下的 WI-1002（第 5 行）、WI-1011（第 5 行）、WI-1019（第 3 行，runbook 位置）、WI-1020（第 14 行）、WI-1021（第 3 行）、WI-1022（第 5 行）。
- 回報列出每個更正的位置與前後敘述，以及判定為「保留」的敘述清單。
- 讀取上線手冊的測試與其他受影響的既有測試通過。

## 架構約束

- 只改位置敘述，不改寫實質內容；不改驗收條件與決策本身。
- 在 `runline-digest-rss` 的獨立 git worktree 與分支上工作；以該儲存庫的 Forgejo PR 提交，合併由使用者進行。
- 不得修改 runline 儲存庫。
