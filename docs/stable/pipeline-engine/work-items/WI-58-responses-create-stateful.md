# WI-58 `responses.create` 標為有狀態

本文回答：型別目錄中 `responses.create` 的「有狀態」標示要如何調整，以及如何驗收。狀態：已核可（2026-10-08）。相依：WI-55。決策見 [ADR-021](../adr/ADR-021-resource-type-catalog-endpoint.md)；「有狀態」的意義見 [ADR-019](../adr/ADR-019-typed-shared-resources.md) 決策 13。

## 背景

WI-55 把會建立、取消或刪除服務端保存之物的條目標為有狀態，但沒有標 `responses.create`。OpenAI 相容服務預設會保存建立的回應，使用者決定將它標為有狀態（會改變服務端保存的資料）。

## 行為與驗收條件

- `GET /api/v1/resource-types` 中 `responses.create` 的有狀態標示為真；其他條目的標示不變。
- `responses.create` 仍不預設啟用；「有狀態者不預設啟用」的既有測試仍然通過。
- Console 的端點選項在 `responses.create` 旁顯示「會變更服務端保存的資料」；Fake Engine 的目錄同步，契約測試在 Fake 與真實 Engine 上通過，且兩者回應相等。
- 08-api 與 [WI-55](WI-55-resource-type-catalog-endpoint.md)「實作結果」中「有狀態的範圍」的描述同步改寫。

## 架構約束

- 只改這一個條目的標示，不改變任何條目的啟用規則、路徑與請求驗證。
- 型別描述仍是唯一事實來源（ADR-021 決策 2）。

## 實作結果（2026-10-08）

**改動。** `accessors` 的 `OpenAiEndpoints` 中 `responses.create` 加上 `stateful = true`，這是唯一的 production 改動；Engine 的 `GET /api/v1/resource-types` 由同一份描述寫出，所以不需另改。Fake Engine 的唯一描述 `console/test-support/fake-resource-types.ts` 同步標示。Console 的端點選項本來就依目錄的 `stateful` 顯示標示，沒有改動。啟用規則、路徑、請求驗證與其他條目的標示都未改；`responses.create` 仍不預設啟用。08-api 的 `stateful` 說明改為列出全部有狀態條目；WI-55「實作結果」的「有狀態的範圍」改寫。

**驗證。**

- Red 的證據：`ResourceTypeCatalogApiTest`（真實 PostgreSQL）新增的 `responses.create` 條目斷言失敗於 `"stateful":false`；`OpenAiEndpointsTest` 的有狀態集合斷言失敗於缺少 `responses.create`；契約 `resource-types-contract.ts` 對 Fake 的新斷言失敗於 `stateful: false`。改動後三者通過。`ResourcesPage.test.ts` 新增的測試（以 Fake 的目錄、zh-TW：`responses.create` 旁顯示「會變更服務端保存的資料」、無「新資源預設啟用」、新資源未勾選）在 Fake 的目錄改動之後才寫，以暫時還原 Fake 的目錄確認它會失敗，之後還原。
- 「有狀態者不預設啟用」的既有斷言（`OpenAiEndpointsTest`、契約）仍然通過。
- `npm test`：65 個檔案、1093 個測試通過。
- 對真實打包的 Engine（`engine.jar`、PostgreSQL 17 容器、以 keytool 製作的 PKCS12 金鑰庫）：`npm run test:contract` 113 個通過（含 Fake 與真實回應相等的比對）；`e2e/resource-types.e2e.ts` 4 個通過（每個條目的有狀態標示與 API 回應比對）。
- `./gradlew check`：失敗的是既有的 8 個，另有負載下的計時測試 `OpenAiBindingStreamTest` 1 個與 `OpenAiResourceRunTest` 1 個，兩者單獨重跑通過，與本項無關。

**未驗證或未做。** 其他真實瀏覽器腳本未重跑（需要 Fake OpenAI 服務行程、TLS 憑證等環境，且與本項改動無關）。
