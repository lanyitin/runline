# ADR-033 Pipeline view：宣告式、唯讀、由 Engine 執行並由 Console 通用呈現

狀態：已核可（2026-10-11）。補充 [ADR-003](../../pipeline-engine/adr/ADR-003-jar-and-discovery.md)（metadata 新增 view 宣告）、[ADR-015](../../pipeline-engine/adr/ADR-015-console-frontend.md)（Console 新增通用頁面）、[ADR-025](https://forgejo.dev.lan/runline/runline-digest-rss/src/branch/main/docs/stable/digest-rss/adr/ADR-025-resource-roles-and-binding.md)（view 掛在資源角色上）。回答：pipeline 如何在 Console 顯示自己的資料，以及這條讀取路徑的安全邊界。

## 背景

- pipeline 是 run 內的一條 thread，run 結束即消失（ADR-008），不能自己提供網頁。
- digest-rss 的資料在 SQLite，經 `jdbc-pool` 資源存取（ADR-024）；目前只能用資料庫工具查（`https://forgejo.dev.lan/runline/runline-digest-rss/src/branch/main/docs/stable/digest-rss/06-data-model.md`「存取」）。
- Console 是同源 SPA，只呼叫 Bearer／工作階段 API，CSP 禁止第三方腳本（ADR-015、ADR-017）。
- 資料庫資源的 `readOnly` 屬性不可靠，唯一保護是帳號權限（`08-api.md`）；SQLite 沒有帳號。

## 選項

| 選項 | 優點 | 缺點 | 結論 |
|---|---|---|---|
| A. 宣告式 view，Engine 執行，Console 通用呈現 | pipeline 無前端程式碼；沿用綁定、認證、隔離；其他 pipeline 可重用 | Engine 新增契約；版面彈性有限 | 採用 |
| B. pipeline 附前端 bundle | 版面自由 | bundle 與 Console 同源，可讀 sessionStorage 的 token，破壞 ADR-017 的 XSS 防護 | 否決 |
| C. 另做唯讀檢視服務 | 不動 Engine | 多一個部署單元與認證；須與 SQLite 檔同機；偏離拓撲 | 否決 |
| D. 不做 UI，runbook 附查詢 | 零成本 | 達不到目標 | 否決 |

## 決策

**宣告。** pipeline 的 metadata 可列出零到多個 view。每個 view 有：識別、標題、說明、掛載的資料庫資源角色、唯讀查詢、參數定義、欄位定義、預設排序。

- 參數有名稱、型別（文字、整數、固定選項）、是否必填；值只作為查詢參數傳遞，不拼接進查詢文字。
- 欄位有名稱、顯示標籤、型別（文字、整數、時間、狀態）。「狀態」欄可為每個值指定語氣（正面、中性、負面），讓 Console 以顏色與文字區分。沒有「連結」型別（問題 2）。
- 欄位可宣告「鑽取」：點選時開啟同一 pipeline 的另一個 view，並把該列某欄的值帶入其參數。
- 宣告在上傳時驗證（格式、角色存在且為資料庫型別、鑽取目標存在、欄位與參數名稱不重複）；不合格的上傳為上傳錯誤，行為同現行 metadata 錯誤。宣告不影響 safe 或 unsafe 判定。

**執行與唯讀。**

- Engine 依 run 相同的解析規則（上傳者加 pipeline 名稱的綁定，ADR-025）取得角色的資源；解析失敗回報與 run 相同的 `problem`（如 `unbound`）。
- 一個 view 只能執行單一讀取語句；Engine 必須保證執行過程不能修改資料，即使查詢文字企圖寫入也不得成功。保證不得依賴資料庫帳號權限或 pipeline 的自律，並由真實 PostgreSQL 與真實 SQLite 驗證。
- SQLite 的唯讀方式不得使「沒有 run 進行時無法開啟」（`https://forgejo.dev.lan/runline/runline-digest-rss/src/branch/main/docs/stable/digest-rss/runbook.md` 第 8 節記載的限制）。
- view 查詢走獨立的讀取路徑：不占用 run 的資源容量（ADR-007），有自己的並行上限；套用資源既有的逾時、列數與回應大小上限，並加上分頁。
- 執行失敗（逾時、結構不符）回報明確的錯誤碼，不是 500；查詢文字與參數值不出現在回應與 log。

**權限與隔離。**

- 開發人員（Bearer 或工作階段）只能存取自己上傳的版本的 view；管理員可存取任何上傳者的，以 `uploader` 指定。別人的版本與不存在的版本回應位元組級相同（沿用 ADR-025）。
- view 不放寬綁定規則：開發人員能 view 的資料，限於其 pipeline 已綁定的資源。

**呈現。** Console 在 pipeline 詳情頁新增「資料」區：列出 view、顯示參數輸入、表格、分頁與鑽取。儲存格內容一律當純文字（沿用 WI-33 的不可信內容機制）。

## 取捨

- 得到：不新增部署單元；不引入第三方腳本；任何 pipeline 都能用；digest-rss 不需前端工作。
- 失去：版面只有表格；view 宣告的查詢是資料庫方言文字（SQLite 與 PostgreSQL 各自的語法），由 pipeline 作者負責；標籤不支援多語系（第一版）。
- 風險：見 [07-nfr-risks.md](../07-nfr-risks.md)。

## 後果

- 08-api 新增 view 的兩個端點與免於寫入的保證（[05-ipc.md](../05-ipc.md)）。
- ADR-003 的 metadata 格式新增 view 欄位；舊 jar 不含 view，行為不變。
- Console 的 pipeline 詳情頁新增一區，不新增頂層導覽。
