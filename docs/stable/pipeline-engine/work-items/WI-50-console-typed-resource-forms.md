# WI-50 Console 各型別資源的表單與使用量

本文回答：管理員如何在 Console 建立與修改 `file`、`jdbc-pool`、`openai-compatible` 資源，並看到型別專屬的使用量。狀態：已核可（2026-10-06）；`openai-compatible` 表單欄位為 2026-10-07 修訂，待使用者確認。相依：WI-43、WI-46、WI-48、WI-49、WI-53。決策見 [ADR-019](../adr/ADR-019-typed-shared-resources.md) 第 11 點。

## 背景

WI-49 提供型別感知的資源頁與 `counter` 表單。本項在後端型別都已落地後，加入其餘三個型別的表單欄位與使用量顯示，使欄位與 API 的驗證一致。

## 行為與驗收條件

- **`file`**：資源根目錄下的相對路徑、容量；路徑不合規時顯示對應的欄位錯誤。
- **`jdbc-pool`**：資料庫種類（首版只有 PostgreSQL）、主機、埠、資料庫名、使用者名稱、機密別名、額外連線屬性、每 run 連線額度、逾時、容量；顯示「連線池大小 = 容量 × 每 run 連線額度」的說明。
- **`openai-compatible`**：根位址、機密別名（選填）、organization 與 project、額外標頭、預設模型與允許的模型清單、請求參數的預設值與是否鎖定與上限、端點集合、逾時、每 run 同時請求數、容量；說明鎖定與上限的意義。
- 機密別名以金鑰庫現有別名供選擇；金鑰庫未組態或沒有別名時說明原因，不接受自行輸入機密值。
- 修改時型別與名稱唯讀；`invalid_resource` 的 `problem` 對應到欄位錯誤；降低容量與停用的影響說明沿用 WI-36。
- 卡片顯示型別專屬的使用量（`jdbc-pool` 的使用中連線數、`openai-compatible` 的進行中請求數），隨既有的 3 秒輪詢更新；實體檢查仍不被輪詢觸發。
- 沿用不可信內容純文字渲染、zh-TW 與 en、確認步驟與變更者顯示。

**驗收方式**
- 沿用 WI-49：真實的打包後 Engine、真實 PostgreSQL、真實 PKCS12 金鑰庫與真實瀏覽器引擎，管理員與開發人員兩種 token，本機手動腳本；`openai-compatible` 使用自製的 OpenAI 相容 Fake 服務端、`jdbc-pool` 使用真實 PostgreSQL；前端單元測試納入 `check`。涵蓋建立、修改、檢查成功與失敗、刪除預覽與確認，並驗證機密值不出現在 DOM、瀏覽器儲存與網路回應。不使用 Stub 或 Mock。

- 信任別名與用戶端憑證別名欄位由 [WI-52](WI-52-tls-trust-and-mtls.md) 加入，本項不實作。

## 架構約束

- 只使用既有 API，不新增或修改端點；不新增 CI。

## 實作結果（2026-10-07）

**結構。** 只用 08-api 已記載的端點與欄位（資源的建立與修改的 `settings`、`secretAlias`，資源的 `concurrencyLimit`、`usage`，`invalid_resource` 的 `problem`，`GET /api/v1/secrets`），沒有新增或修改端點，也沒有改 Engine 的程式碼。Console：`admin/resource-forms.ts`（每個型別一個表單：新資源的欄位、由既有設定得到的欄位、欄位組成的設定、`problem` 對應的欄位；`formOf(type)` 選表單，對話框只問它）、`admin/openai-catalog.ts`（端點目錄與可設定的請求參數，依 08-api 的順序；已由 WI-55 移除，見下）、各型別的欄位元件 `FileFields`、`JdbcPoolFields`、`OpenAiFields`（共用 `TextField`、`PairsField`）、`SecretAliasField`（機密別名的選單）；`resource-types.ts` 加入型別的使用量（`usageLines`）；`ResourceFormDialog.svelte` 組合它們，`ResourceCard.svelte` 顯示使用量。

**實作時的決定（超出條文之處，供審閱）。**

- 欄位保留輸入的文字；表單只在送出前擋下 Engine 無法被詢問的情形（必填欄位空白、數字欄位不是數字、JSON 欄位不是 JSON、沒有啟用任何端點），其餘交給 Engine，`problem` 顯示在對應欄位（型別有專屬說明時用它，例如 `invalid_limit` 說出各型別的範圍）。留空的選填欄位不送出，使用 Engine 的預設（欄位以灰字顯示預設值）。
- 條文沒有列出的設定（`jdbc-pool` 的 `maxRows`、`maxResponseBytes`；`openai-compatible` 的 `maxRequestBytes`、`maxResponseBytes`、`maxDownloadBytes`）沒有欄位：建立時用 Engine 的預設，修改時保留既有的值。
- 修改只送有變更的部分：設定與保存的不同（不論成員順序）時整份送出（因此清除 `lastCheck`），別名換了才送 `secretAlias`；沒有任何變更時說明「沒有要變更的內容」，不呼叫 API。
- 機密別名是選單，只列金鑰庫中類型為機密的項目（憑證與私鑰不列），不可用的機密標示原因；新資源可選「不設機密」，已有別名的資源不提供（API 沒有清除別名的修改）；目前的別名不在金鑰庫時仍列出並標示。金鑰庫未組態或沒有機密時說明原因；說明新增或更換機密由維運以 keytool 操作後在本頁重載。
- `jdbc-pool` 的「連線池大小 = 容量 × 每 run 連線數」與 `openai-compatible` 的「同時請求上限 = 容量 × 每 run 請求數」依輸入即時計算；請求參數以表格呈現（預設、鎖定、上限；上限只對數值參數），表格上方說明鎖定與上限的意義。`stop` 可為文字或 JSON 清單，`response_format` 為 JSON 物件。
- （WI-55 改寫）本項實作時，端點目錄、請求參數清單與資料庫種類在 Console 內各有一份複本（`openai-catalog.ts`、`resource-forms.ts` 的 `DATABASE_KINDS`、翻譯中的屬性名稱），因為當時 API 沒有提供目錄。[WI-55](WI-55-resource-type-catalog-endpoint.md)（[ADR-021](../adr/ADR-021-resource-type-catalog-endpoint.md)）新增 `GET /api/v1/resource-types` 並移除這些複本：表單的端點選項、請求參數欄位、資料庫種類選單與允許的屬性說明全部取自執行中 Engine 的回應，讀取失敗時不退回任何內建清單，`jdbc-pool` 與 `openai-compatible` 的建立與修改停用。
- 卡片的使用量顯示為「使用中的連線：n / 上限」與「進行中的請求：n / 上限」（上限為 `concurrencyLimit`），隨資源列表的 3 秒輪詢更新，不觸發檢查。卡片未增加端點、逾時與上限的型別專屬顯示（條文未要求）。
- Fake Engine：`test-support/fake-resource-settings.ts` 依型別實作 Engine 的設定規則（接受什麼、拒絕的 `problem`、寫回的正規化形式），`fake-resources.ts` 經 API 建立與修改所有型別並給出 `concurrencyLimit`、`usage`（使用量由測試設定）；`fake-engine.ts` 另記錄每個請求的方法與本文（`received`），供元件測試確認送出的內容。`contract/admin-contract.ts` 新增 8 個測試（各型別寫回的設定與上限、各型別拒絕的 `problem`、修改設定與別名清除 `lastCheck`、不合規的修改不改變任何東西），同一組在 Fake 與真實 Engine 上通過。
- 真實瀏覽器腳本需要 OpenAI 相容服務：沿用 Engine 測試的 `FakeOpenAiServer`（已有 `OpenAiServerContract` 契約測試），新增 `FakeOpenAiServerMain` 與手動任務 `./gradlew :accessors:fakeOpenAiServer`（不納入 `check`）。新增示範 pipeline `demo-usage.jar`（`demo-pool-usage` 持有 `demo-db` 的連線、`demo-llm-usage` 對 `demo-llm` 送出一次 chat completion），讓腳本以真實 run 驗證使用量。

**驗證。** `npm test`（納入 `./gradlew check`）：`resource-forms.test.ts` 23 個、`resource-types.test.ts` 新增 4 個、`admin-api.test.ts` 新增 2 個、`ResourcesPage.test.ts` 新增 21 個（含改寫的「不收機密值」）、修改 2 個，以及上述契約測試對 Fake。Red 的證據：以未含本項的 production code（HEAD 的對話框、卡片、API 與 Fake）執行新測試，元件與 API 測試 28 個、契約測試對舊 Fake 8 個因行為缺失而失敗；`resource-forms.test.ts` 對只有 `counter` 表單的骨架 22 個失敗。對真實打包的 Engine（`engine.jar`、PostgreSQL 17、真實 PKCS12 金鑰庫，含標記值的 `llm-key`、資料庫密碼 `db-pass`、`other-pass` 與一張受信任憑證）：`npm run test:contract` 103 個通過；`npm run e2e` 85 個通過，含新增的 `e2e/typed-forms.e2e.ts` 4 個（`file`：路徑跳出資源根目錄的欄位錯誤、建立、檢查成功、修改路徑後 `lastCheck` 清除、預覽後刪除；`jdbc-pool`：真實 PostgreSQL、`property_not_allowed` 的欄位錯誤、連線池大小說明、以 `db-pass` 檢查成功與以 `other-pass` 檢查失敗（`rejected`）且與 API 的 `lastCheck` 相同、真實 run 持有連線時卡片顯示 1 / 4 且未觸發檢查；`openai-compatible`：Fake 服務要求的金鑰即標記、`invalid_header` 的欄位錯誤、鎖定與上限說明、檢查成功與失敗、真實 run 的請求進行中時卡片顯示 1 / 1；zh-TW 與開發人員看不到資源頁且 API 回 403；標記與資料庫密碼不出現在任何回應、DOM、localStorage、sessionStorage 與 cookie）。

**未驗證或未做。**

- Engine 沒有組態金鑰庫時的別名欄位說明只以元件測試對 Fake 驗證（真實瀏覽器腳本所用的 Engine 有金鑰庫）。
- `path_unusable`、`unsupported_database`、`invalid_timeout` 等其他 `problem` 的欄位對應以單元測試與契約測試驗證，真實瀏覽器只走了 `path_outside_root`、`property_not_allowed`、`invalid_header`。
- 本文件狀態列所述 `openai-compatible` 表單欄位（2026-10-07 修訂）尚待使用者確認；實作依修訂後的欄位。
