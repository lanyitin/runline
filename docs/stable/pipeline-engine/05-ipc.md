# IPC 模型

本文回答：誰呼叫誰、傳什麼語意的資料、失敗如何處理。

## 外部介面（同步 REST，除另註）

| 呼叫者 | 介面 | 語意 |
|---|---|---|
| 開發人員 | 上傳 jar | 回傳探索到的 pipeline 清單、metadata、safe／unsafe 判定與原因（含依賴路徑）。同一內容重複上傳視為同一版本（以內容雜湊辨識），冪等 |
| 管理員 | trigger 管理 | 建立／停用 cron 或 webhook 綁定 |
| 管理員 | 白名單管理 | 新增／修改／刪除條目；變更前可預覽影響；變更與重判為單一原子操作 |
| 管理員 | pipeline 的 unsafe 執行設定 | 逐 pipeline 開關 |
| 管理員 | 共享資源管理 | 定義資源（型別、容量與型別專屬設定）；查看持有者與等待者；修改、刪除（無持有者與等待者時）、檢查實體、強制釋放（[ADR-019](adr/ADR-019-typed-shared-resources.md)） |
| 管理員 | 機密管理 | 唯讀列出金鑰庫別名與引用者、重載金鑰庫；不接受機密值 |
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

## 資源存取端

型別化資源（`file`、`jdbc-pool`、`openai-compatible`，見 [ADR-019](adr/ADR-019-typed-shared-resources.md)）由 Engine 中介：

- 實體（檔案路徑、連線池、HTTP 用戶端）與機密由 Engine 持有，操作在 Engine 側執行；run 只拿到受控的存取端。存取端的契約由 core 定義。
- 存取端跨越邊界時只使用 JDK 內建型別（字串、數字、位元組、集合）；機密、驅動物件與 Engine 內部型別不穿過邊界。
- 取得容量成功後，Engine 為「宣告了型別」的資源備妥存取端；pipeline 只能依名稱取用自己宣告且已持有者，沒有執行中取得資源的操作。只宣告名稱的資源只取得容量。
- 存取端綁定該 run 與取得當下的資源設定；run 終止或管理員強制釋放時失效，之後的操作失敗並註明原因，Engine 回收其佔用的實體。
- 回傳給 run 的實體錯誤只含錯誤類別，不含連線字串與驅動訊息原文；原文寫入 Engine log，以 `errorId` 對應。經 HTTP 的型別（`openai-compatible`）另附服務回的狀態碼（`ResourceAccessException.status`），仍不含服務回的訊息或本文。
- 進行中的長時間操作（HTTP 請求、查詢）可以被取消：存取端失效時 Engine 先要求綁定中止正在做的事（`ResourceBinding.abort`），再等待它結束；因此強制釋放、run 取消與終止立即中斷進行中的請求，不必等它自己結束。
- 開發入口以同一份契約提供本機實作，不連 Engine。

## 錄製資料

IO 事件記錄在 context 層：每次檔案、連線、行程動作產生一筆事件，內容為動作類別、目標與讀寫性質。事件輸出為提案產生器的輸入（見 [ADR-004](adr/ADR-004-recording.md)）。錄製只在開發入口開啟：事件在 run 的 class loader 內累積，run 結束時以 `recording` 鍵隨結果 map 回傳，內容只有 JDK 內建型別（map、list、字串、數字）；Runner 在邊界另一側轉成 `RecordedIo`。Engine 的 run 不帶錄製選項，結果中沒有該鍵。
