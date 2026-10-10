# 現況觀察

本文回答：來源系統現在怎麼運作、runline 現在提供什麼，兩者的落差在哪。

## 來源系統（pi-env）

- 位置：`pi-env/agent/skills/video-page-summary`，約 5400 行 JavaScript 與 bash，入口 `rss-digest.sh`，沒有模型在中間協調。
- 兩種模型呼叫：讀者（每篇每條件一次，thinking 開、附圖）與摘要寫手（thinking 關）；其餘全是程式計算。
- 外部相依：FreshRSS、rqbit、streamexa（皆為 MCP over Streamable HTTP，私有 CA）、Lemonade（OpenAI 相容）、有畫面的真實 Chrome（防偵測，不可 headless）。
- 狀態：run 目錄（單次）、隔離清單與排程設定（跨次）、FreshRSS 已讀狀態（去重）。

## runline 能提供的

| 需要 | runline 現況 | 落差 |
|---|---|---|
| 定時觸發 | cron trigger（ADR-005） | 無 |
| 模型呼叫 | `openai-compatible` 資源，支援串流、圖片、容量 1 | 需確認回應中可取得所用模型名稱（驗收項） |
| 檔案 | 共享目錄與 run 私有目錄（ADR-009）、`file` 資源 | 本 pipeline 不寫檔，只用 `file` 資源讀取憑證檔 |
| 彙整用資料庫 | `jdbc-pool` 起初只有 PostgreSQL（現已由 WI-1003 新增 SQLite） | 需新增 `sqlite` 資料庫種類（ADR-024）；pipeline 自帶驅動會遇到原生程式庫跨 class loader 載入問題 |
| HTTP | context 的 `network` 只提供原始 socket | pipeline 須自帶 HTTP 用戶端，因而 unsafe |
| 行程 | context 的 `processes.run` 等行程結束才回傳 | 無法承載長駐的 Chrome；pipeline 直接啟動行程，因而 unsafe |
| 機密 | 只有金鑰庫經資源型別提供（ADR-019） | 外部 MCP 服務的 token 無處存放（見 ADR-023） |

## 結論

- 這條 pipeline 必然是 unsafe（外部行程不受限、直接使用網路與行程）；v1 接受，由管理員對該版本明確允許。
- 可以走 safe 路徑的部分（LLM、資料庫、憑證檔）仍走 context 與資源存取端，以保留限制、錄製與機密保護的好處。
