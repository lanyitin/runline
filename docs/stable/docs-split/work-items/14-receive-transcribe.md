# #14：runline-transcribe 接收 transcribe 文件並設定 agent

本項回答：`runline-transcribe` 要達成什麼狀態。狀態：已核可，已完成。Issue：#14。相依：[#13](../adr/13-docs-split.md)（已核可）。

## 目標

`runline-transcribe` 儲存庫自己持有 transcribe 的 stable 與 draft 文件，文件間連結有效，並具備與 runline 相同的 agent、`CLAUDE.md` 與設定。

## 驗收條件

**文件搬入**
- runline 的 `docs/stable/transcribe-pipeline`、`docs/stable/transcribe-scenes`、`docs/draft/transcribe-scenes` 的每個檔案都出現在 `runline-transcribe` 的 `docs/stable/`、`docs/draft/` 下同名目錄與同名路徑；檔案數與 runline 現況一致。
- 內容除了連結與位置敘述外與原檔一致；回報中列出所有被修改的位置敘述（例如「設計在 runline 儲存庫的 …」）。
- 連結依 #13 的連結規則處理：儲存庫內相對連結、跨儲存庫網址；以全部搬入文件為樣本，沒有指向不存在檔案的相對連結。
- `runline-transcribe` 既有的 README 與其他檔案中，指向 runline 內 transcribe 文件的路徑改為本儲存庫的新位置；讀取這些文件的測試或腳本（若有）改讀新位置並通過。
- `runline-transcribe/docs/` 既有的兩份報告維持原位不動。
- 本項不修改 runline 儲存庫。

**agent 與流程設定**
- `runline-transcribe` 具備 `.claude/agents/high-level-architect.md`、`.claude/agents/tdd-coder.md`、`.claude/settings.json`、`CLAUDE.md`，內容與 runline 一致，只有設定中以儲存庫名稱限定的項目改為 `runline-transcribe`。
- 不複製 `.claude/settings.local.json`。

**其他**
- 受影響的既有測試通過。
- 回報該儲存庫的 Forgejo 是否已有 `in-progress` 標籤，沒有時列為需要使用者處理。

## 架構約束

- 只做位置與連結的改動，不改寫文件的實質內容。
- 在 `runline-transcribe` 的獨立 git worktree 與分支上工作；以該儲存庫的 Forgejo PR 提交，合併由使用者進行。
- 遵循 [#13](../adr/13-docs-split.md)；遇到與其衝突或未涵蓋、會改變範圍的情況時，停下回報。
