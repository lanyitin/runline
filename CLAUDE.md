# runline

## 文件與立項流程

- `docs/stable/` 是已核可的現行依據，`docs/draft/` 是待審提案。Forgejo Wiki 是 `docs/stable/` 的唯讀映像，由 CI 同步，不直接編輯。
- 開工前須先查重再立項：同步 `main`，搜尋 Kandev 既有 task（含已歸檔），並比對 `docs/` 既有工作項與 ADR；有相似項目就停下回報，無重複才建立 Kandev task 並開分支（透過 `add_branch_to_task_kandev`）。
- 文件在該分支撰寫、以 PR 審查，合併由使用者進行；合併後歸檔 Kandev task。
- 詳細規則見 `.claude/agents/high-level-architect.md` 的 5.5。
- 與現行流程衝突時，停下來與使用者討論。
