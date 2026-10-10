# WI-1005 FreshRSS 存取

本文回答：如何取得 FreshRSS 未讀、解析、標已讀並記錄。狀態：已核可（2026-10-08）。

目標：取得未讀、解析為候選清單、標已讀，並把結果記入資料庫。行為參考：來源的 `freshrss.mjs`、`parse-unread.sh`。工作儲存庫：`runline-digest-rss`。

## 驗收條件

1. 以 Streamable HTTP（JSON-RPC、SSE 框架回應、session id）與 FreshRSS MCP 通訊；位址與 token 來自組態與 ADR-023。
2. 取得最多 cap 篇未讀並解析出每篇的識別、url、標題、feed 項目內的 torrent／magnet（可為空）。空清單是正常結果。
3. 單次列出的上限與 FreshRSS 一致（來源為 100）：`cap` 超過上限時實際取得上限數量，並把實際值記入 RUN 與 log，不報錯。
4. 每篇寫入 POST（跨 run 穩定身分，更新最近看到時間）與本次 RUN_POST（順序、enclosure）；「這次抓到哪些未讀」可由資料庫完整回答。
5. 缺 token 時伺服器回「找不到工具」，需被識別並明確報告為憑證問題。
6. 取不到清單時：本次無事可做，不擷取、不標記，以成功結束並記錄原因。
7. 標已讀只接受由 WI-1010 提供的 id 清單，一次呼叫；每篇結果記入 MARK；失敗時保留待下次重試，不視為整個 run 失敗。
8. 以自製的 FreshRSS MCP Fake（真實實作）驗證，含私有 CA 的 TLS；token 不出現在 log 與資料庫。

## 架構約束

- 不使用資源型別；外部 HTTP 為 unsafe pipeline 的範圍（ADR-022）。
- 憑證依 ADR-023。
