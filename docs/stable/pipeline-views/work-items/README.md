# pipeline view 工作項總覽

本文回答：要交給 tdd-coder 的工作有哪些、先後順序為何。狀態：已核可（2026-10-11）。tdd-coder 只需讀本頁加被派到的那一項，以及入口 [README](../README.md)。編號從 1300 起。

儲存庫分工：WI-1300 至 WI-1302、WI-1304、WI-1305 改動 runline 儲存庫；WI-1303 在外部專案 `runline-digest-rss`。派工時須在回報要求中指明工作的儲存庫。

| 項目 | 目標 | 相依 | 狀態 |
|---|---|---|---|
| [WI-1300](WI-1300-view-declaration.md) | pipeline 可在 metadata 宣告 view，上傳時驗證並隨版本保存 | 無 | 已完成（2026-10-11） |
| [WI-1301](WI-1301-view-api.md) | Engine 的唯讀 view API：列出與讀取，含唯讀保證、分頁、上限與可觀測性 | WI-1300 | 已完成（2026-10-11） |
| [WI-1304](WI-1304-view-named-placeholders.md) | view 查詢以具名佔位符引用參數，上傳時檢查 | WI-1301 | 已完成（2026-10-11） |
| [WI-1305](WI-1305-view-read-limits.md) | view 讀取逾時 30 秒、並行上限可由環境變數調整 | WI-1301 | 已完成（2026-10-11） |
| [WI-1302](WI-1302-console-views.md) | Console 的通用 view 呈現：參數、表格、分頁、鑽取、狀態語氣 | WI-1301 | 已完成（2026-10-12） |
| [WI-1303](WI-1303-digest-rss-views.md) | digest-rss 宣告「下載了哪些、沒下載的原因」等 view | WI-1300、WI-1304；實機驗收需 WI-1302 | 已完成（2026-10-12） |

順序：WI-1304、WI-1305 彼此獨立，皆在 WI-1301 之後；WI-1302 與它們無相依，可平行規劃但一次只派一項；WI-1303 須在 WI-1304 之後。建議順序：WI-1304 → WI-1305 → WI-1302 → WI-1303。pipeline-engine 與 digest-rss 的總覽需補一列對照本表。
