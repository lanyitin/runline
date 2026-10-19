# #23：更正 runline-transcribe 文件中過時的位置敘述

本項回答：搬移之後，`runline-transcribe` 的文件中哪些敘述要更正。狀態：已核可，已完成。Issue：#23。相依：無。

## 目標

`runline-transcribe` 文件中描述「文件或專案位於何處」的敘述，與搬移後的現況一致。

## 驗收條件

- 凡敘述與現況不符者更正，例如：專案被描述為「目前為空目錄」、設計文件或 runbook 被描述為位於 runline 儲存庫。
- 敘述仍然正確者不動，包括：描述 runline 儲存庫仍持有的內容（Engine、`deploy/`、`pipeline-engine`、`pipeline-views` 文件等）；指向這些內容的引用若尚不是連結，改為指向 runline `main` 的網址。
- 描述當時分工或當時狀態的歷史敘述（例如工作項中的「R／T 儲存庫分工」）保留，不改寫。
- 起點（執行時須以搜尋找出完整清單）：
  - `docs/stable/transcribe-pipeline/README.md` 的「專案位置」（第 9 行）。
  - `README.md` 中指向 runline `docs/stable/pipeline-views/` 的敘述（第 1051 行）。
  - 其他以「runline 儲存庫」「與 runline 同層」描述文件位置者。
- 回報列出每個更正的位置與前後敘述，以及判定為「保留」的敘述清單。
- 受影響的既有測試通過（若更正涉及被測試讀取的文件）。

## 架構約束

- 只改位置敘述，不改寫實質內容；不改驗收條件與決策本身。
- 在 `runline-transcribe` 的獨立 git worktree 與分支上工作；以該儲存庫的 Forgejo PR 提交，合併由使用者進行。
- 不得修改 runline 儲存庫。
