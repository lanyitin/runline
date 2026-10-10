# WI-1021 手冊移到 stable 後的路徑修正

本文回答：上線手冊從 draft 移到 stable 後，哪些引用要跟著改。狀態：已核可（2026-10-09）。工作儲存庫：`runline-digest-rss`（測試與 README）與 runline（手冊本身的相對連結）。

背景：手冊 `docs/draft/digest-rss/runbook.md` 核可後由使用者搬到 `docs/stable/digest-rss/runbook.md`。`RunbookReasonsTest`、`RunbookQueriesTest` 與支援程式從舊路徑讀取手冊，手冊內的相對連結也以舊路徑為基準。

## 驗收條件

1. 手冊在新路徑 `docs/stable/digest-rss/runbook.md`（前提：使用者已搬移；舊路徑不存在）。測試與支援程式改讀新路徑；測試找不到手冊時以明確的訊息失敗，不靜默通過。
2. 手冊內所有相對連結（到 `deploy/`、`console/e2e/`、`runline-digest-rss` 的 README、WI-1017 與其他 stable 文件）以新路徑為基準重新核對並更正，逐一確認目標存在。
3. 手冊的狀態敘述改為「已核可（日期）」，移除「草稿、待使用者審閱後晉升」；內文引用的「WI-1011」等維持。
4. `runline-digest-rss` 的 README 與其他文件中對手冊舊路徑的引用全部改為新路徑；全專案搜尋舊路徑不再出現。
5. `./gradlew ktfmtCheck test` 通過（含 `RunbookReasonsTest`、`RunbookQueriesTest`）。

## 架構約束

- 不改變手冊的實質內容；不修改程式行為；不修改 docs/stable 中手冊以外的檔案。
- 不自行 git commit。
