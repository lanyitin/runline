# WI-1020 runline 側文件的更正

本文回答：端到端驗證發現 runline 側有哪些文件與實作不一致，以及要更正什麼。狀態：已核可（2026-10-09）。工作儲存庫：runline（只改文件）。

## 驗收條件

1. `docs/stable/pipeline-engine/work-items/WI-48-jdbc-pool-resource.md` 末節「擴充：`sqlite` 種類」補一句：WI-1016 之後，WAL 日誌模式在連線建立時以重試方式設定（遇 `SQLITE_BUSY` 才重試，間隔 1 至 20 毫秒，遇中斷或最多 30 秒後放棄），SQLite 設定檔的啟動語句只剩啟用外鍵；並連結 [WI-1016](../../digest-rss/work-items/WI-1016-sqlite-first-open-race.md)。
2. `deploy/browser.md` 中「pipeline 端的硬體渲染檢查未實作」的說明改為與現況一致：WI-1006 已實作，`CHROME_REQUIRE_HARDWARE_RENDERING=true` 時 run 在擷取前以 pipeline 自己啟動的 Chrome 讀出渲染器，軟體渲染或讀不到時明確失敗、不擷取、不標記；分工說明與 WI-1001 第 8 條一致。
3. 逐項核對 `deploy/browser.md` 與 `docs/stable/digest-rss/` 其餘仍過時的敘述（以實作為準），列出並更正屬 runline 的部分；屬 `docs/stable/digest-rss/` 的不一致只列出回報，不修改。
4. 不改變任何程式與測試；`ApiDocumentationTest` 維持通過。

## 架構約束

- 只改文件。不修改 `docs/stable/digest-rss/` 與 `runline-digest-rss`。
