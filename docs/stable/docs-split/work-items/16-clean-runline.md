# #16：runline 移除已搬走的文件並修正引用

本項回答：搬走之後，runline 要達成什麼狀態。狀態：已核可，已完成。Issue：#16。相依：[#14](14-receive-transcribe.md)、[#15](15-receive-digest-rss.md) 都已驗收並由使用者合併。

## 目標

runline 不再持有已搬走的文件，所有對它們的引用都指向新位置，且 Wiki 映像仍然可以同步。

## 驗收條件

- `docs/stable/transcribe-pipeline`、`docs/stable/transcribe-scenes`、`docs/stable/digest-rss`、`docs/draft/transcribe-scenes` 從 runline 移除；移除前確認 #14、#15 的檔案已在 `runline-transcribe`、`runline-digest-rss` 的 `main` 上。
- 留在 runline 的 stable 文件中，指向這些主題的連結（約 40 個）改為指向對方儲存庫 `main` 的網址；其他連結不動。
- 非文件檔（部署腳本、compose、環境範例、程式註解，約 18 個）中引用搬走文件路徑之處，改為新位置或網址；行為不變。
- 以留在 runline 的 stable 為樣本，沒有指向不存在檔案的相對連結；Wiki 同步工作流的自我檢查（改寫後沒有指向不存在 Wiki 頁的連結）通過。
- 留在 runline 的文件中提到搬走主題的敘述（例如「專案位置」）反映新位置。
- 受影響的既有測試通過。

## 架構約束

- 只改引用與移除，不改寫留下文件的實質內容。
- 本項被授權修改 `docs/stable/`，僅限上述移除與連結、位置敘述的調整。
- 在 runline 的獨立 git worktree 與分支上工作；以 Forgejo PR 提交，合併由使用者進行。
- 遵循 [#13](../adr/13-docs-split.md)；遇到與其衝突或未涵蓋的情況時，停下回報。
