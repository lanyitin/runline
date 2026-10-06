# IPC 模型

本文回答：誰呼叫誰、傳什麼語意的資料、失敗如何處理。

## 外部介面（同步 REST，除另註）

| 呼叫者 | 介面 | 語意 |
|---|---|---|
| 開發人員 | 上傳 jar | 回傳探索到的 pipeline 清單、metadata、safe／unsafe 判定與原因（含依賴路徑）。同一內容重複上傳視為同一版本（以內容雜湊辨識），冪等 |
| 管理員 | trigger 管理 | 建立／停用 cron 或 webhook 綁定 |
| 管理員 | 白名單管理 | 新增／修改／刪除條目；變更前可預覽影響；變更與重判為單一原子操作 |
| 管理員 | pipeline 的 unsafe 執行設定 | 逐 pipeline 開關 |
| 管理員 | 共享資源管理 | 定義資源與容量；查看持有者與等待者；強制釋放 |
| 外部系統 | webhook 入口 | 驗證密鑰後立即回 accepted，run 非同步執行；以呼叫端提供的 delivery 識別碼去重 |
| 任何已授權者 | run 查詢 | 狀態、結果、log |
| 任何已授權者 | run log 串流 | 以 WebSocket 或 SSE 推送；客戶端中斷不影響 run（背壓：緩衝有上限，超過則丟棄舊 log 並標記） |
| 任何已授權者 | 取消 run | 協作式取消（見 [07](07-nfr-risks.md)） |

認證：管理與上傳 API 用既有 Ktor 認證機制；webhook 用 per-trigger 密鑰。重試與逾時：webhook 由呼叫端重試；Engine 不自動重跑 run。API 版本以路徑前綴區分。

## Engine 與 Runner 的內部邊界

Engine 與 run 位於同一 JVM，但分屬不同 class loader。邊界契約如下：

- 只使用 JDK 內建型別傳遞資料，任何 Engine 或 Runner 的自有型別都不得穿過邊界。
- Engine 傳入：pipeline 識別、參數、已解析的 effective metadata、取消訊號。
- Runner 回報：狀態變化、log 行、結果、（開發模式下）IO 事件。
- 回報方向為事件推送，Engine 負責落庫與轉發。
- 同一契約也由開發入口實作，使本機與 Engine 的行為一致。

## 共享資源的取得

- 取得發生在 run 的初始化階段，由 Engine 在建立 run 後、啟動 pipeline 本體前處理，不經由 pipeline 程式碼呼叫。
- Engine 依 metadata 宣告的資源名稱整體取得；結果為成功、等待逾時、被取消、資源不存在或已停用。
- 釋放由 Engine 在 run 終止時執行，不依賴 run 自行釋放；管理員強制釋放走管理 API。
- 等待與持有的狀態變化以事件記錄。

## 錄製資料

IO 事件記錄在 context 層：每次檔案、連線、行程動作產生一筆事件，內容為動作類別、目標與讀寫性質。事件輸出為提案產生器的輸入（見 [ADR-004](adr/ADR-004-recording.md)）。錄製只在開發入口開啟：事件在 run 的 class loader 內累積，run 結束時以 `recording` 鍵隨結果 map 回傳，內容只有 JDK 內建型別（map、list、字串、數字）；Runner 在邊界另一側轉成 `RecordedIo`。Engine 的 run 不帶錄製選項，結果中沒有該鍵。
