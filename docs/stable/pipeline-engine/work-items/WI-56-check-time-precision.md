# WI-56 檢查時間的精度一致

本文回答：資源檢查回傳的時間，為何要與之後讀回的 `lastCheck` 相同，以及如何驗收。狀態：已核可（2026-10-08）。相依：WI-43。

## 背景

`POST /api/v1/resources/{name}/check` 回應中的 `checkedAt` 精確到奈秒，資料庫保存的是四捨五入後的微秒；之後 `GET` 讀到的 `lastCheck.checkedAt` 與檢查回應的字串不同。在時鐘有奈秒精度的 Linux 上，Engine 自己的 `ResourceCheckApiTest`、`ResourceCheckerTest` 因此失敗；Console 的契約測試只能以「同一毫秒內」比對。

## 行為與驗收條件

- 同一次檢查中，檢查回應的 `checkedAt` 與之後 `GET /api/v1/resources/{name}`、`GET /api/v1/resources` 中 `lastCheck.checkedAt` 的字串完全相同。在時鐘有奈秒精度的平台上，以真實 PostgreSQL（Testcontainers）驗證。
- 時間的精度與格式在 08-api 中記載一次，其他提到 `checkedAt` 的段落以連結引用。
- Engine 中所有「回應當下回傳、之後從資料庫讀回」的時間欄位都盤點過，有相同問題者一併處理，並在回報中列出盤點結果。
- 原本因精度失敗的 `ResourceCheckApiTest` 與 `ResourceCheckerTest` 通過；不得以放寬斷言達成。
- Console 契約測試改回以字串相等比對，並在 Fake 與真實 Engine 上通過。

## 架構約束

- 不改變 `checkedAt` 的欄位名稱與意義，也不改變 API 的其他行為。
- 測試不使用 Stub 或 Mock，資料庫用真實容器。

## 實作結果（2026-10-08）

**改動。** `ResourceChecker` 取檢查時間時截到微秒（更細的部分捨去），回應與保存用同一個值；資料庫原本就保存微秒，所以讀回的字串與回應相同。這是唯一的 production 改動，`checkedAt` 的欄位名稱、意義與 API 的其他行為不變。08-api 的「通則」記載時間的格式與精度，資源欄位的 `lastCheck` 與檢查端點以連結引用。Console 契約測試改回字串相等，並加上列表讀回的比對。

**盤點：回應當下回傳、之後從資料庫讀回的時間欄位。** 只有檢查的 `checkedAt` 是以記憶體中的值回應。其他寫入資料庫的時間，回應都是寫入後從資料庫讀回的值：資源的建立與修改（`createdAt`、`updatedAt`）、trigger 的建立、修改與輪替密鑰（`createdAt`、`updatedAt`、`secretRotatedAt`）、上傳（`uploadedAt`）、unsafe 設定（`setAt`）、run 的建立、白名單條目與版本。webhook 的觸發時間、run 的狀態時間與 log 行的時間寫入時不在回應中。不保存的時間（共享資源持有者與等待者的時間、`GET /api/v1/system` 的 `startedAt`、憑證的 `notAfter` 與 `daysLeft`）沒有讀回的問題。盤點不需要改變 API 契約或資料模型。

**驗證。**

- Red 的證據：在時鐘有奈秒精度的 Linux 上，`ResourceCheckApiTest` 的保存比對失敗於 `...00.558266545Z` 對 `...00.558267Z`，`ResourceCheckerTest` 的保存比對失敗於同類差異；新增的 `ResourceCheckerTest`「時鐘比微秒更細」以固定時鐘 `...03.123456789Z` 失敗於未截斷，不依平台時鐘。契約測試對修正前的打包 Engine 失敗於 `...47.939968641Z` 對 `...47.939969Z`。
- 修正後三者通過（真實 PostgreSQL，Testcontainers）。
- `npm test`：65 個檔案、1093 個測試通過（含 Fake 上的契約）。
- 對真實打包的 Engine（`engine.jar`、PostgreSQL 17 容器、以 keytool 製作的 PKCS12 金鑰庫）：`npm run test:contract` 113 個通過。
- `./gradlew cleanTest check ktfmtCheck`：失敗的都是 WI-57 範圍（以 root 執行的 `FileProbeTest` 2 個、`ResourceCheckerTest` 權限 1 個、`InfoRoutesTest`；負載下的 `OpenAiBindingMultipartTest`、`TriggerStartupTest`），另有負載下的 `EngineAccessorBehaviorTest`、`ResourceRunIntegrationTest` 與 `ConsoleBuildTest`（其內部的 `CreateRunPage.test.ts` 一個），三者單獨重跑通過，與本項無關。

**未驗證或未做。** 真實瀏覽器腳本未重跑（Console 只顯示保存的值，與本項改動無關）。
