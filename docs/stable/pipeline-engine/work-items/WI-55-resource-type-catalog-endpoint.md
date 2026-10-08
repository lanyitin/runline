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

## 實作結果（2026-10-08）

**結構。** 描述留在原本做驗證的地方，改成可被描述的資料：`accessors` 的 `OpenAiEndpoints`（每個條目加上 `group` 與 `stateful`；`enableable` 是可啟用條目的唯一清單，設定的 `endpoints` 驗證與回應都用它）、`OpenAiSettings.REQUEST_PARAMETERS`（`RequestParameter` 的名稱與 `ParameterKind`；預設值的型別檢查、鎖定與上限的允許清單都由它導出，取代原本的 `NUMERIC`、`DEFAULTABLE` 與 `fits`）、`PropertyRule`（由 lambda 改為 `Text`、`OneOf`、`Pattern` 三種資料，`accepts` 由資料導出）與 `JdbcProfiles.all`。Engine 的 `ResourceBehavior.description()` 由各型別的 behavior 從上述同一份資料寫出（`OpenAiCompatibleBehavior`、`JdbcPoolBehavior`，後者用 DI 中與驗證相同的 `JdbcProfiles` 實例），`ResourceTypeCatalog` 依 `ResourceType.entries` 的順序組成回應並只建一次，`ResourceRoutes.kt` 以 `authorized(Role.ADMIN)` 掛上 `GET /api/v1/resource-types`。Console：`api/admin-model.ts` 的 `parseResourceTypes`、`admin-api.ts` 的 `resourceTypes()`；`resource-forms.ts` 的 `formOf(type, catalog)`（`jdbc-pool` 與 `openai-compatible` 的表單由目錄建立，沒有目錄時沒有表單）；`ResourcesPage.svelte` 在開頁時讀一次目錄；`ResourceFormDialog.svelte`、`OpenAiFields.svelte`、`JdbcPoolFields.svelte`、`PairsField.svelte` 取用它；刪除 `admin/openai-catalog.ts`、`DATABASE_KINDS` 與翻譯中的屬性名稱與資料庫種類標籤。Fake Engine：`test-support/fake-resource-types.ts` 是 Fake 的唯一描述，`fake-resource-settings.ts` 由它導出接受的條目、參數、資料庫種類與屬性規則；`contract/resource-types-contract.ts`。

**實作時的決定（超出條文之處，供審閱）。**

- **回應結構**：`{"types": [{"type": ..., <型別的成員>}]}`；`openai-compatible` 為 `endpoints[]`（`id`、`group`、`method`、`path`、`request`：`none`／`json`／`multipart`、`response`：`json`／`binary`、`streams`、`defaultEnabled`、`stateful`）與 `requestParameters[]`（`name`、`kind`：`number`／`text`／`textOrList`／`object`、`ceiling`）；`jdbc-pool` 為 `databases[]`（`kind`、`properties[]`：`name`、`rule` 與其成員 `maxLength`／`values`／`pattern`）；`counter`、`file` 只有 `type`。細節見 08-api。
- **群組識別**：沿用 ADR-019 目錄表的群組，以英文識別名稱表示（`chat`、`completions`、`embeddings`、`models`、`responses`、`moderations`、`rerank`（含 `rerank` 與 `reranking`）、`images`、`audio`、`files`、`batches`）。Console 原樣顯示群組名稱，不另設翻譯，以免成為群組清單的複本。
- **「有狀態」的範圍**：標為 `stateful` 的是會建立、取消或刪除服務端保存之物的條目：`responses.delete`、`responses.cancel`、`files.create`、`files.delete`、`batches.create`、`batches.cancel`。`responses.create` 未標示（它主要是生成；OpenAI 預設會保存回應，這點可再決定）。這些條目原本就都不預設啟用，以測試保證「有狀態者不預設啟用」。
- **`streams`**：事件串流的條目（`chat.completions`、`completions`、`responses.create`）與以位元組塊拉取的 `audio.speech` 都為 `true`；回應種類 `binary` 可分辨後者。
- **只列可啟用的條目**：目錄中標為未交付（`delivered = false`）的條目不列出，因為它們不能放進 `endpoints`；目前版本全部已交付，所以列出全部 28 個。
- **不公開的內容**：依 ADR-021 待確認問題的決策，不含任何設定預設值（例如資料庫設定檔的預設埠、逾時、大小上限），也不含健康查詢、清理語句等設定檔內部細節。
- **值規則的寫法**：`pattern` 是整個值須符合的正規表示式，寫法限於 Java 與 JavaScript 意義相同的子集；`text` 一律不接受控制字元（與原本的 `ApplicationName` 規則相同）。
- **Console 的讀取時機與失敗**：資源頁在列表與目錄都有結果（成功或失敗）後才顯示，使對話框不會在目錄未到時開啟；目錄不隨 3 秒輪詢讀取。讀取失敗時頁面以文字說明並附 API 錯誤（含 `errorId`），型別選單中 `jdbc-pool` 與 `openai-compatible` 標為「無法使用」並停用；修改既有的這兩型資源時，對話框只顯示說明，儲存停用（容量與啟用狀態也一併不能改，依「停用建立與修改」的字面）。沒有重試按鈕：重新開啟頁面即重讀。
- **表單的呈現**：端點依群組分組，每組有「全部啟用」「全部停用」（儲存的仍是條目），條目標示「新資源預設啟用」與「會變更服務端保存的資料」；上限欄位只在 `ceiling` 為真時出現；預設值依 `kind` 解讀（`textOrList` 以 `[` 開頭時讀為 JSON 清單，`object` 讀為 JSON）；資料庫種類以 Engine 的識別名稱顯示（移除 Console 的「PostgreSQL」標籤）；額外連線屬性下方列出所選種類允許的屬性與規則。
- **修改的既有測試**：`JdbcSettingsTest` 中第二個設定檔的規則改用 `PropertyRule.OneOf`（`PropertyRule` 不再是 `fun interface`）；`resource-forms.test.ts` 的 `formOf` 多一個目錄參數（用 Fake 的目錄）；`ResourcesPage.test.ts` 一個斷言由選項文字「PostgreSQL」改為 `postgresql`；`e2e/typed-forms.e2e.ts` 的資料庫種類改為與 API 回應比對。行為的其他斷言未改。

**驗證。**

- Red 的證據：每個循環先執行新測試並看到因行為缺失而失敗。`accessors`：`OpenAiEndpointsTest` 3 個（群組、有狀態、可啟用清單；先以佔位成員使失敗落在斷言上）、`OpenAiSettingsTest` 的參數描述 1 個、`JdbcSettingsTest` 3 個（規則描述、規則接受的值、第二個設定檔）。Engine：`ResourceTypeCatalogApiTest` 的授權與型別集合（404）、端點條目、請求參數（缺成員）、資料庫種類（斷言不符）依序 Red；`ApiDocumentationTest` 在 08-api 補上前 2 個失敗。Console：契約對 Fake 8 個（404）、`admin-api.test.ts` 2 個、`resource-forms.test.ts` 3 個、`ResourcesPage.test.ts` 新增 8 個。與驗證一致的測試（`ResourceTypeCatalogApiTest` 後 5 個）在共用同一份描述之後才寫，寫成即通過；以「讓回應少一個條目或屬性、或洩漏金鑰庫別名」的暫時突變確認它們會失敗，之後還原。
- 新增的測試：`accessors` 7 個；Engine `ResourceTypeCatalogApiTest` 9 個（真實 PostgreSQL、以 keytool 製作含獨特標記的 PKCS12 金鑰庫：管理員 200、開發人員 403、無 token 401；條目、參數、資料庫種類的內容；回應不含標記、別名、資源名稱與設定、資源根目錄、金鑰庫路徑與密碼、token；每個列出的條目在建立與修改時被接受、未列出的名稱（含目錄中每個名稱與非條目名稱）為 `invalid_endpoint`；預設啟用條目等於省略 `endpoints` 時寫出者；資料庫種類與 `unsupported_database`；每個屬性以符合規則的值被接受、不符的值為 `invalid_settings`、未列出的名稱為 `property_not_allowed`）；Console `admin-api.test.ts` 2 個、`resource-forms.test.ts` 3 個、`ResourcesPage.test.ts` 8 個、契約 8 個（對 Fake 與真實 Engine）與真實 Engine 上 Fake 與真實回應相等的比對 1 個、`e2e/resource-types.e2e.ts` 4 個。
- `npm test`（納入 `./gradlew check`）：65 個檔案、1092 個測試通過；`svelte-check` 0 錯誤；`check:api-doc` 通過。前端原始碼（`console/src`，不含測試）搜尋 `chat.completions`、`postgresql`、`ApplicationName`、`temperature` 等條目、參數、資料庫種類與屬性名稱：沒有任何結果。
- 對真實打包的 Engine（`engine.jar`、PostgreSQL 17 容器、以 keytool 製作的 PKCS12 金鑰庫：含標記的 `llm-key`、`db-pass`、`other-pass` 與受信任憑證 `corporate-ca`）與 Fake OpenAI 服務行程：`npm run test:contract` 113 個通過（含新增 9 個）；`npm run e2e` 的 `resources.e2e.ts` 4 個、`typed-forms.e2e.ts` 4 個、`resource-types.e2e.ts` 4 個通過。另以加上 keytool 製作的憑證（`internal-ca` 簽發的 `app-client` 與 10 天後到期的 `soon-client`）與 TLS 加 mTLS 的 Fake 服務行程，執行全部 9 個真實瀏覽器腳本，90 個通過（`certificates.e2e.ts` 1 個含在內）。
- `./gradlew check`：失敗的只有既有的 8 個（`accessors` 的 `FileProbeTest` 2 個（以 root 執行）、`OpenAiBindingMultipartTest` 1 個（負載計時）；`engine` 的 `InfoRoutesTest`、`ResourceCheckApiTest`、`ResourceCheckerTest` 2 個、`TriggerStartupTest`），同一組測試在本項開始前的 commit 上同樣失敗；其他任務（含 `consoleTest`、`consoleTypecheck`、`consoleApiDocCheck`、`packagedTest`、`devkit`）通過。

**未驗證或未做。**

- 目錄讀取失敗時的頁面行為只以元件測試對 Fake Engine 驗證（Fake 可讓這個路由失敗）；真實 Engine 無法只讓這個路由失敗，真實瀏覽器沒有走這條路徑。
