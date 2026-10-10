# ADR-024 以 SQLite 保存 pipeline 的所有寫入

狀態：已核可（2026-10-08）。補充 ADR-019 決策 7（資料庫設定檔）。回答：pipeline 的寫入放在哪裡、SQLite 如何被 pipeline 使用。

## 背景

pipeline 的所有寫入（run 產出、隔離清單、摘要）改存 SQLite，使日後可用 SQL 彙整：每次 run 抓到哪些未讀、每篇的圖文判定、哪些被派給 rqbit 或 streamexa。

每個 run 使用獨立 root class loader（ADR-001）。SQLite 的 JDBC 驅動帶原生程式庫，原生程式庫在同一個 JVM 內只能被一個 class loader 載入，pipeline 自帶驅動時第二次 run 起很可能載入失敗。

## 選項

| 選項 | 優點 | 缺點 |
|---|---|---|
| **A. Engine 新增 `sqlite` 資料庫種類，pipeline 經 `jdbc-pool` 存取端（採用）** | 驅動只在 Engine 載入一次；pipeline 維持單純（不參照驅動）；沿用取消、世代、檢查、可觀測；符合 ADR-019 擴充方式 | 需要 Engine 側新增工作項；檔案型資料庫不適用主機、埠、帳密欄位；觸及 pipeline-engine |
| B. pipeline 自帶 SQLite 驅動 | 不動 Engine | 原生程式庫跨 class loader 載入問題；需自行處理檔案位置、取消與並行；此處理在 unsafe 範圍內無保護 |
| C. 改用既有 PostgreSQL `jdbc-pool` | 現成、可並行、可供 Console 或外部工具查詢 | 不是使用者要求的 SQLite；需管理員準備資料庫 |

## 決策

採 A。資料庫檔位於管理員設定的資源根目錄之內，由 Engine 開啟；使用資源的 pipeline 只拿到窄 SQL 存取。C 作為備援：若 A 在實作中不可行，改以 PostgreSQL 達成同樣的資料模型，不改本 pipeline 的資料模型。

## 後果

- 新增 [WI-1003](../work-items/WI-1003-sqlite-database-kind.md)；與 WI-48 的契約一致，不改 core 的存取端契約。
- SQLite 為單寫入者：Engine 側設定檔採可同時讀取的日誌模式，並設定忙碌等待；digest-rss 的 run 因 LLM 資源容量 1 而本來就序列化。
- 彙整查詢由使用者在主機上直接開啟資料庫檔進行；Engine 不提供查詢端點。
- 資料會持續增長，需保留策略（見 [06](../06-data-model.md)）。
- 瀏覽器本身為了運作而寫的暫存（使用者資料目錄）不屬 pipeline 的寫入，不納入本決策。
