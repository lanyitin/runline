# WI-49 Console 資源頁改造：型別、檢查、刪除、機密檢視與宣告者

本文回答：管理員在 Console 如何看到資源的型別與狀態、檢查與刪除資源、檢視金鑰庫別名並重載，以及 pipeline 如何顯示其宣告的資源狀態。狀態：已核可（2026-10-06）。相依：WI-36、WI-40、WI-41、WI-43、WI-46（別名狀態與引用者需要第一個接受別名的型別才能顯示與驗證）。決策見 [ADR-019](../adr/ADR-019-typed-shared-resources.md) 第 11 點。各型別專屬的建立與修改欄位在 [WI-50](WI-50-console-typed-resource-forms.md)。

## 背景

現行資源頁（WI-36）只有容量、啟用、持有者與等待者、強制釋放。本項把資源頁改為型別感知，並加入檢查、刪除與機密檢視；在 WI-46 之後即可進行，可與 WI-47、WI-48 並行，只依賴已實作的 API。

## 行為與驗收條件

- **型別感知的表單**：建立表單先選型別，再依型別顯示欄位；本項提供選擇機制與 `counter`（名稱、容量）的表單，其他型別的欄位由 WI-50 加入，本項不顯示尚無表單的型別。名稱與型別在建立後唯讀；表單不收機密值。
- **資源卡片**：顯示型別標籤、非機密設定摘要、機密別名與狀態（未設定、已找到、找不到）、最近一次檢查的結果與時間，並保留容量、啟用、持有者、等待者與強制釋放。型別專屬的使用量由 WI-50 加入。
- **檢查**：每個資源有「檢查」動作，顯示結果類別；檢查只在管理員按下時執行，不被輪詢觸發，卡片只顯示最近一次結果。
- **刪除**：先以 `preview=true` 顯示會受影響的 pipeline 定義與 trigger 數及目前是否有持有者與等待者，確認後才刪除；`resource_in_use` 時說明原因並引導停用或強制釋放；刪除後列表更新。
- **機密檢視**：置於資源頁內的獨立區塊：列出金鑰庫別名與引用它的資源，提供「重載」動作並顯示結果（別名數、有變更的別名與引用者）；`secret_store_not_configured` 與 `secret_store_unreadable` 有清楚說明；說明新增或更換機密需要維運以金鑰庫工具操作，Console 不接受機密值。用語須與 webhook 密鑰（只顯示一次）明確區分。
- **宣告者**：卡片顯示宣告了此資源的 pipeline（含連結到 pipeline 詳情）。Pipeline 詳情頁的資源宣告顯示型別與資源狀態（存在、停用、型別是否相符）。
- 沿用既有規則：管理員專屬頁依角色顯示、不可信字串一律純文字渲染、輪詢 3 秒且只在分頁可見時、所有文字有 zh-TW 與 en、破壞性操作有確認步驟、變更者以 token 對應的名稱顯示。

**驗收方式**
- 沿用 [WI-36](WI-36-console-admin-pages.md)：真實的打包後 Engine、真實 PostgreSQL（Testcontainers）、真實編譯的測試 jar、真實 PKCS12 金鑰庫與真實瀏覽器引擎，以管理員與開發人員兩種 token，以本機手動執行的腳本進行（不接進 Gradle `check`），並在回報中附上執行結果；純邏輯的前端單元測試納入 `check`。
- 額外驗證：以含獨特標記的金鑰庫確認標記字串不出現在 DOM、瀏覽器儲存與任何網路回應；預覽、檢查、刪除、重載的結果與 API 一致。不使用 Stub 或 Mock；需要替代品時使用自製的簡易真實實作（Fake）。

- 憑證項目的類型、主旨、到期日與指紋在機密檢視中的顯示由 [WI-52](WI-52-tls-trust-and-mtls.md) 加入；本項只需讓機密檢視在清單含項目類型欄位時不出錯。

## 架構約束

- 只使用已實作並記載於 08-api 的 API，不新增或修改端點；若需要的資料欄位 API 尚未提供，回報由對應的後端項處理。
- 不新增 CI；前端單元測試納入 `check`，真實瀏覽器腳本為手動執行（[ADR-015](../adr/ADR-015-console-frontend.md)）。

## 實作結果（2026-10-07）

**結構。** 只用 08-api 已記載的端點（資源的列表、建立、修改、`check`、`DELETE` 與 `preview=true`、強制釋放，`GET /api/v1/secrets`、`POST /api/v1/secrets/reload`，上傳回應的 `metadata.resourceTypes` 與 `warnings`），沒有新增或修改端點，也沒有改 Engine 的程式碼。Console：`admin/ResourceCard.svelte`（一張資源卡片：型別標籤、非機密設定摘要、機密別名與狀態、最近一次檢查、使用量、持有者與等待者、宣告者與連結；檢查動作在卡片內）、`admin/ResourceDeleteDialog.svelte`（先預覽再刪除）、`admin/SecretsPanel.svelte`（金鑰庫區塊與重載）、`admin/resource-types.ts`（有表單的型別、帶機密的型別、各型別的設定摘要欄位；WI-50 在這裡加條目，不改頁面）、`pipelines/declarations.ts`（pipeline 頁的資源宣告：期望的型別與狀態）。`ResourcesPage.svelte` 只組合它們與輪詢。

**實作時的決定（超出條文之處，供審閱）。**

- 建立表單先以下拉選單選型別（預設「選擇型別」），選了才出現該型別的欄位；目前只列 `counter`。修改時名稱與型別以唯讀文字顯示，不送出 `name`、`type`。
- 卡片的機密列只在型別會引用機密（`openai-compatible`、`jdbc-pool`）或已有別名時顯示；`counter` 與 `file` 不顯示「未設定」。
- 設定摘要：`file` 為 `path`；`openai-compatible` 為 `baseUrl`、`organization`、`project`；`jdbc-pool` 為 `kind`、`host`、`port`、`database`、`username`。其餘設定（端點、逾時、上限）留給 WI-50 的型別專屬顯示。
- 檢查成功後重讀資源，卡片顯示的是 API 保存的 `lastCheck`，而不是檢查回應本身（見下方「發現」）。
- 刪除：預覽顯示 `inUse` 時不提供確認按鈕並說明停用或強制釋放；預覽之後才被 run 取得而得到 `resource_in_use` 時，在對話框內以同一段說明呈現。
- 金鑰庫區塊與資源列表同樣每 3 秒、只在分頁可見時重讀；`secret_store_not_configured` 視為一種狀態（資訊提示、不提供重載），不是錯誤。
- pipeline 頁的資源宣告狀態取自上傳回應的 `warnings`（開發人員沒有讀資源的權限）：同一名稱有多個警告時依「尚未定義 > 停用 > 型別不在集合內 > 型別不符」取一個；沒有警告即「可使用」。
- 新增示範 pipeline `demo-typed`（`dev/sample-pipelines`，宣告 `demo-printer` 並期望 `file`），讓契約測試與真實瀏覽器腳本能以真實 jar 驗證 `declaredType` 與 `resource_type_mismatch`；Fake 的示範 jar 同步加入。
- Fake Engine：`test-support/fake-secrets.ts`（金鑰庫：別名、項目類型、狀態與代表內容的指紋，不含任何值；測試改寫「檔案」，重載才讀入，讀不到時記憶體不變）；`fake-resources.ts` 加入型別、設定、別名與狀態、`lastCheck`、`declaredBy`、檢查（別名缺失或不可用時不問實體）與刪除（預覽、`resource_in_use`）。Fake 經 API 只建立 `counter` 與最簡單的 `openai-compatible`，其他型別與實體的檢查結果由測試直接放入。`contract/admin-contract.ts` 新增 10 個測試（counter 的欄位、宣告者與 trigger 數、型別宣告與 `resource_type_mismatch`、檢查與 `lastCheck`、刪除的預覽與 204、404 與 400、使用中的預覽與 409、機密的角色、列表與重載、別名缺失時的狀態與 `alias_missing`），同一組在 Fake 與真實 Engine 上通過。

**驗證。** `npm test`（納入 `./gradlew check`）：新增與修改的元件測試對 Fake Engine（真實 HTTP）執行，`ResourcesPage.test.ts` 44 個、`PipelineDetailPage.test.ts` 18 個、`admin-api.test.ts` 31 個、`resource-types.test.ts` 7 個、`declarations.test.ts` 3 個，以及上述契約測試。對真實打包的 Engine（`engine.jar`、PostgreSQL 17、真實 PKCS12 金鑰庫，含一個值為獨特標記的機密、另一個機密與一張受信任憑證）：`npm run test:contract` 95 個通過；`npm run e2e` 81 個通過（含新增的 `e2e/resources.e2e.ts` 4 個：卡片內容與只在按下時才檢查、檢查結果與 API 的 `lastCheck` 相同；管理員與開發人員看到的宣告者與宣告狀態；刪除的預覽與 API 相同、刪除後 404、真實 run 持有時不能刪除且 API 同樣回 409；金鑰庫區塊與 API 的別名一致、以 `keytool -importpass` 新增別名後重載的結果、非金鑰庫檔案的重載失敗與記憶體不變、zh-TW；所有收到的回應本文與標頭、DOM、localStorage、sessionStorage、cookie 都不含標記）。既有的 `admin.e2e.ts` 與 `security.e2e.ts` 的建立資源流程改為先選型別。

`./gradlew check`：Console 的型別檢查、測試與翻譯檢查（`consoleCheck`）與 `packagedTest` 通過；`:accessors:test` 3 個與 `:engine:test` 5 個失敗，在未含本項變更的 HEAD 上以同樣的環境執行，失敗完全相同（與本項無關）：以 root 執行使檔案權限不生效（`FileProbeTest` 兩個、`ResourceCheckerTest` 一個）、呼叫者名稱 `root` 與系統使用者名稱相同（`InfoRoutesTest`）、下述的時間精度（`ResourceCheckApiTest`、`ResourceCheckerTest` 各一個）、負載下的計時（`OpenAiBindingMultipartTest`、`TriggerStartupTest`）。

**發現（不阻擋，交由後端項判斷）。** `POST /api/v1/resources/{name}/check` 回應的 `checkedAt` 精確到奈秒（例如 `...50.918100732Z`），之後 `GET` 的 `lastCheck.checkedAt` 是資料庫保存的微秒（四捨五入為 `...50.918101Z`），兩者字串不同（在時鐘有奈秒精度的 Linux 上，Engine 自己的 `ResourceCheckApiTest` 與 `ResourceCheckerTest` 也因此失敗）。契約測試以「同一毫秒內」比較；Console 只顯示保存的值，不受影響。

**未驗證或未做。**

- Engine 沒有組態金鑰庫（`secret_store_not_configured`）的畫面只以元件測試對 Fake 驗證；真實瀏覽器腳本所用的 Engine 有金鑰庫，沒有另起一個沒有金鑰庫的 Engine。契約測試的這個分支同樣只在 Fake 上走到。
- 金鑰庫的 `private_key` 項目沒有放進真實金鑰庫；清單含 `trusted_certificate` 項目時正常顯示（WI-52 只要求不出錯），`private_key` 的標籤只在翻譯檔中。
- Fake 建立 run 時不檢查宣告的型別（真實 Engine 以 `resources_unavailable` 拒絕型別不符）；本項的畫面不依賴它。
