# docs-split 工作項總覽

本文列出 docs-split 的交付項目與相依順序。狀態：已核可。

| 編號 | 目標 | 相依 | 狀態 | 詳細文件 |
|---|---|---|---|---|
| #14 | `runline-transcribe` 接收 transcribe 文件並設定 agent | [#13](../adr/13-docs-split.md)（已核可） | 已完成 | [#14](14-receive-transcribe.md) |
| #15 | `runline-digest-rss` 接收 digest-rss 文件並設定 agent | #13（已核可） | 已完成 | [#15](15-receive-digest-rss.md) |
| #16 | runline 移除已搬走的文件並修正引用 | #14、#15 都已驗收並由使用者合併 | 已完成 | [#16](16-clean-runline.md) |

#14 與 #15 互不相依，可並行；兩者各在自己儲存庫的獨立 worktree 與分支上進行。
