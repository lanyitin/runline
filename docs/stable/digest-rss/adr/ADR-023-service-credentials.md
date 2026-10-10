# ADR-023 外部 MCP 服務的憑證存放

狀態：已核可（2026-10-08）。補充 ADR-019。回答：FreshRSS、rqbit、streamexa 的位址與 token 放在哪裡。

## 背景

ADR-019 規定機密只存在金鑰庫，並只經資源型別中介。三個 MCP 服務不是資源型別，pipeline 又自行呼叫它們，因此需要一個不把 token 放進資料庫、trigger 參數、metadata 或 log 的存放處。

## 選項

| 選項 | 優點 | 缺點 |
|---|---|---|
| **A. 管理員維護的 `file` 資源內的憑證檔（採用）** | 不新增型別；token 不進資料庫與 API；`file` 資源有路徑限定與容量 | 憑證以檔案而非金鑰庫保存，偏離 ADR-019「機密只在金鑰庫」的精神；unsafe pipeline 取得 token 後可自行外送 |
| B. Engine 行程的環境變數 | 符合 12-Factor；最簡單 | 任何 unsafe pipeline 都能讀所有環境變數 |
| C. 新增 HTTP 服務資源型別 | 機密在金鑰庫、最乾淨 | 改動封閉型別集合與多個工作項 |

## 決策

採 A。憑證檔是單一 JSON，以服務名稱（`freshrss`、`rqbit`、`streamexa`）為鍵，每項含 `url` 與 `token`；位址不寫死在程式、不放 trigger 參數。憑證檔由管理員在部署時放置，不由 pipeline 或 API 寫入；pipeline 只讀取，不寫入 log 與輸出。

## 後果

- 與 ADR-019「機密只在金鑰庫」的偏離由使用者接受，範圍僅限這三個服務的 token。
- 因 pipeline 為 unsafe，此做法不提供對惡意 pipeline 的保護，只防止 token 進入資料庫與 API。
