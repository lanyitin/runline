# WI-1306 digest-rss 上線手冊補上 Console 的 view

本文回答：上線手冊要補哪些內容，才能讓管理員知道 Console 的資料區與新的結構版本。狀態：已核可（2026-10-12）。相依：WI-1303（已完成）。儲存庫：runline（只改文件）。

## 背景

WI-1303 已在 digest-rss 宣告四個 view：`downloads`、`not-downloaded`、`runs`、`run-articles`，並新增資料庫結構版本 8（一個索引，於下一次 run 開始時套用）。手冊 `https://forgejo.dev.lan/runline/runline-digest-rss/src/branch/main/docs/stable/digest-rss/runbook.md` 尚未提到它們。

## 行為與驗收條件

1. 第 8 節（查看結果）說明：Console 的 pipeline 詳情頁「資料」區顯示這四個 view，並各用一句話說明它回答什麼問題；既有的 SQL 查詢保留（它們仍由測試執行，也涵蓋 view 未涵蓋的問題）。
2. 第 8 節說明「未下載與原因」的原因類別名稱與各自的意思，特別是：`no_link` 表示保留的文章沒有 torrent／magnet，改用文章網址派發仍失敗；`not_dispatched` 表示保留但沒有任何派發紀錄；`download_unknown` 表示已派發但結果未知。類別名稱以 digest-rss 儲存庫的宣告為準，手冊以連結或單一對照表記載，不在多處重複。
3. 說明 view 需要資料庫資源角色 `database` 已綁定；未綁定時資料區顯示的狀態與處置。
4. 第 9、10 節（升級與備份）提到結構版本 8 與索引在下一次 run 開始時套用；在套用前 view 仍可用但較慢；較新的資料庫仍會被較舊的 pipeline 拒絕寫入。
5. 附錄的端到端涵蓋範圍加入 view 的驗證。
6. 說明時間欄需帶 UTC 偏移才會依使用者時區顯示，以及管理員要調整並行讀取上限時的環境變數（連結部署文件，不重複名稱與預設值）。
7. 手冊維持現有章節結構與風格；同一資訊只寫一處，其他處以連結引用。

## 架構約束

- 只改文件，不改程式碼；不改驗收條件以外的既有說明。
- 不修改 `RunbookQueriesTest` 與端到端測試所依賴的查詢內容。
