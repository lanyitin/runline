# WI-55 資源型別目錄端點，Console 改以它為唯一來源

本文回答：Engine 要公開哪些內建的型別資訊、Console 如何改用它，以及如何驗收。狀態：已核可（2026-10-08）。相依：WI-50、WI-52。決策見 [ADR-021](../adr/ADR-021-resource-type-catalog-endpoint.md)。

## 背景

Console 的資源表單目前內含端點目錄、請求參數清單與資料庫種類的複本，Engine 目錄變動時需人工同步（[WI-50](WI-50-console-typed-resource-forms.md)「實作結果」）。ADR-021 決定由 Engine 以唯讀端點公開，Console 不保留複本。

## 行為與驗收條件

**Engine 端點**
- `GET /api/v1/resource-types` 由管理員取得 200。回應列出：
  - 封閉的型別集合。
  - `openai-compatible` 端點目錄的每個條目：識別名稱、群組、方法、路徑樣板、請求與回應種類、是否可串流、是否預設啟用、是否有狀態。
  - `openai-compatible` 可設定預設、鎖定或上限的請求參數：名稱、值的種類、是否可設上限。
  - `jdbc-pool` 每個資料庫設定檔的種類，以及其允許的連線屬性與值規則。
- 開發人員取得 403，沒有 token 取得 401。
- 回應不含任何資源的設定、使用量、機密別名或機密值，也不含 Engine 組態值。以含獨特標記的真實 PKCS12 金鑰庫驗證：標記不出現在回應中。
- **與驗證一致**：
  - 回應列出的每個端點條目，都能被建立資源時的 `endpoints` 接受；不在回應中的名稱一律得到 `invalid_endpoint`。
  - 資料庫種類與 `unsupported_database` 的判定一致。
  - 允許屬性與 `property_not_allowed` 的判定一致。
  - 標示為「預設啟用」的條目，等於省略 `endpoints` 時正規化寫出的條目。
  - 以上皆以真實 Engine 與真實 PostgreSQL（Testcontainers）驗證。
- 08-api 記載此路由的用途、授權、回應結構與錯誤；`ApiDocumentationTest` 通過。

**Console**
- 資源表單的端點選項（含群組、預設啟用與有狀態標示）、請求參數欄位與資料庫種類選單，全部取自此端點。前端不再保留目錄、參數清單或資料庫種類的複本，以搜尋原始碼確認。
- 端點讀取失敗時，顯示可理解的錯誤，並停用 `openai-compatible` 與 `jdbc-pool` 的建立與修改；`counter` 與 `file` 不受影響。
- 端點只在載入資源頁時讀取，不隨 3 秒輪詢重複讀取。
- 既有 WI-49、WI-50 的行為與真實瀏覽器腳本維持通過。
- 所有新增文字有 zh-TW 與 en。

**Fake 與契約**
- Fake Engine 提供同一端點，契約測試在 Fake 與真實打包的 Engine 上都通過，且兩者回應的條目集合相同。

**驗收方式**
- 沿用 WI-49、WI-50：
  - 前端單元與元件測試、契約測試對 Fake 的部分，納入 `./gradlew check`。
  - 對真實打包的 Engine、真實 PostgreSQL、真實 PKCS12 金鑰庫的契約測試與真實瀏覽器腳本，為本機手動執行，結果附在回報中。
- 不使用 Stub 或 Mock。

## 架構約束

- Engine 內部的型別描述是唯一事實來源：端點回應與建立、修改資源的驗證共用同一份描述，不得各自維護清單（ADR-021 決策 2）。
- 端點唯讀、無副作用，不改變既有資源 API 的請求與回應，也不改變 `invalid_endpoint`、`unsupported_database`、`property_not_allowed` 的語意。
- 回應不含任何機密或 Engine 組態值；型別集合維持封閉，不開放外掛（ADR-019）。
- Console 只用同源的 `/api/v1`，不退回任何內建清單（ADR-015、ADR-021 決策 5）。
- 實作時同步改寫 [WI-50](WI-50-console-typed-resource-forms.md)「實作結果」中關於 Console 複本的描述，以及 08-api。
