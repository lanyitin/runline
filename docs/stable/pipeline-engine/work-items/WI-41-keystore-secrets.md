# WI-41 金鑰庫機密機制

本文回答：Engine 如何從 PKCS12 金鑰庫唯讀載入機密、以別名被資源引用、重載與保證機密不外洩。狀態：已核可（2026-10-06），已實作（2026-10-07，見「實作結果」）。相依：WI-40、WI-43（資源根目錄的組態）。決策見 [ADR-019](../adr/ADR-019-typed-shared-resources.md) 第 6 點。

## 背景

機密（資料庫密碼、API 金鑰）與憑證（決策 12，用途由 WI-52 驗證）只存在於一個 PKCS12 金鑰庫檔案，由維運以 JDK 的金鑰庫工具管理；Engine 唯讀載入，資源只存別名。`file` 不使用機密；機密只被 `openai-compatible`（API 金鑰）與 `jdbc-pool`（密碼）引用。本項到完成時還沒有任何型別接受機密別名，因此本項只驗證「金鑰庫機制本身」：以真實金鑰庫檔與端點驗證載入、查找、列出、重載、不外洩。別名到資源的解析（別名格式、狀態、引用者、檢查的別名缺失類別、重載後的世代切換）由第一個接受別名的型別 [WI-46](WI-46-openai-compatible-resource.md) 驗證，[WI-48](WI-48-jdbc-pool-resource.md) 再以 `jdbc-pool` 驗證同一機制。部署掛載與維運手冊在 [WI-42](WI-42-keystore-deployment.md)。

## 前置驗證

狀態：已完成（2026-10-07，JDK 25.0.4.1；結果與決定見 [ADR-019](../adr/ADR-019-typed-shared-resources.md) 第 6 點的「機密字元集」與「實測結果」）。不需再執行，只在 JDK 升級時重新驗證。

重現步驟摘要：
1. 以 JDK 金鑰庫工具的匯入密碼功能建立 PKCS12 機密項目，再以 Java 金鑰庫 API 讀回，比對 ASCII（含空白、符號、700 字元）與非 ASCII（單一 é）。
2. 對既有別名再次匯入，確認失敗；先刪除再匯入，確認成功；以複製、修改副本、更名覆蓋的方式替換檔案後重新讀取。
3. 以大小寫混合的別名匯入，列出別名確認為小寫。
4. 分別以錯誤密碼、截斷檔案、垃圾檔案開啟，記錄例外類型與訊息；解析檔案確認完整性（MAC）參數。
5. 以列出、詳細與 RFC 輸出確認不顯示機密值。

結論要點：機密限可列印 ASCII（非 ASCII 無法還原）；更新為先刪後建；別名以 `Locale.ROOT` 小寫正規化；開檔失敗可區分密碼錯誤與毀損。未驗證的 `keystore.type.compat` 行為列為本項驗收（見「金鑰庫格式與開啟失敗」）。

## 行為與驗收條件

**組態與啟動**
- Engine 組態提供金鑰庫檔案路徑與金鑰庫密碼的來源：環境變數，或密碼檔路徑（命名依既有 `RUNLINE_` 慣例，寫入 [04](../04-deployment.md)：`RUNLINE_KEYSTORE_PATH`、`RUNLINE_KEYSTORE_PASSWORD_FILE`、`RUNLINE_KEYSTORE_PASSWORD`）。同時設定兩種來源視為組態錯誤。密碼值不出現在任何 log、錯誤訊息或命令列。
- 組態指定了金鑰庫但無法開啟：Engine 啟動失敗，原因只含類別（見下方「金鑰庫格式與開啟失敗」）。未指定金鑰庫：Engine 可啟動，引用別名的資源其檢查與使用失敗。
- 金鑰庫檔案對其他使用者可讀時記錄警告（不含密碼）；金鑰庫位於 pipeline 共享目錄、run 私有目錄或資源根目錄（WI-43）之下時拒絕啟動，原因列出鍵名。
- Engine 只以唯讀方式開啟金鑰庫：在唯讀掛載、唯讀檔案權限下運作正常，且啟動、重載前後檔案內容與修改時間不變。

**金鑰庫格式與開啟失敗**
- 格式檢查必須明確：以真實的 PKCS12 檔、JKS 檔、JCEKS 檔、截斷檔與垃圾檔驗證，只有 PKCS12 被接受，其餘以「格式不符」或「檔案毀損」拒絕。不得依賴 JDK 的 `keystore.type.compat` 預設行為（預設為開時要求 PKCS12 的讀取可能也能開 JKS 檔）：須先實測該設定在 JDK 25 下對各種檔案的實際行為，記錄結果，並以測試鎖定（含該設定關閉與預設兩種情況下行為一致）。
- 開啟失敗類別與原因對應：檔案缺失；密碼錯誤（對應完整性檢查失敗的 I/O 例外）；檔案毀損或截斷（對應檔案結尾例外）；格式不符（含 JKS 等其他格式）；其餘為無法開啟。以真實檔案驗證每一類。密碼錯誤的辨識依賴例外訊息文字，無法確定時歸入「無法開啟」而非誤判，並在程式碼外的文件註明須隨 JDK 升級重新驗證。
- 項目類型與內容檢查：載入時辨識三種項目（機密項目、受信任憑證項目、私鑰項目，後兩者的資源使用由 [WI-52](WI-52-tls-trust-and-mtls.md) 驗證）；其他類型的項目被忽略並記錄警告（只含別名與類型）。機密項目的值含非可列印 ASCII 字元時該別名被拒絕使用並記錄警告（別名與類別，不含值）；此別名在機密清單與資源狀態的狀態為 `invalid_secret`（2026-10-07 已決定，見 ADR-019 決定 11；08-api 的別名狀態列舉含它）。
- 不可偵測的損毀（非 ASCII 被破壞後恰落在可列印範圍）無法在載入時辨識，會在連線時以認證失敗呈現；此限度記載於 07 與維運手冊（WI-42），不要求本項偵測。

**別名查找**
- Engine 對別名一律以 `Locale.ROOT` 的小寫正規化後查找（與前置驗證的大小寫行為一致）：以真實金鑰庫驗證大小寫不同的別名（含在非英語地區設定下，例如土耳其語）查到同一項目、不存在的別名查不到、未組態金鑰庫時一律查不到；`GET /api/v1/secrets` 列出的別名為正規化後的形式。
- 資源層面的別名行為（格式限制與資源名稱的字元規則一致、狀態 `not_set`／`found`／`missing`、`counter` 與 `file` 帶別名被拒絕〔沿用 WI-40、WI-43〕）在 WI-46 與 WI-48 驗證；永遠不含機密值的規則適用於所有回應。

**機密端點（管理員；08-api 同步更新）**
- `GET /api/v1/secrets`：列出別名、項目類型與各別名被哪些資源引用（引用者欄位自本項起存在；尚無型別接受別名，因此本項驗證為空，引用關係由 WI-46 與 WI-48 驗證），不含值與項目內容；409 `secret_store_not_configured`。
- `POST /api/v1/secrets/reload`：整體重新讀取。200 回傳別名數與「內容有變更的別名及被哪些資源引用」（本項以替換金鑰庫檔案驗證有變更的別名被正確辨識，引用者為空；引用者由 WI-46 與 WI-48 驗證）；409 `secret_store_not_configured`；422 `secret_store_unreadable`，失敗時記憶體內容維持原狀（以損毀檔案驗證）。判斷內容是否有變更的過程不得把機密值寫入任何輸出。
- 開發人員一律 403，無 token 為 401；重載記錄管理員名稱與結果，並有重載次數與結果的 metric。
- 重載後，別名內容有變的資源，其之後被取得的 run 使用新機密（新世代），進行中的 run 不受影響：本項只負責讓重載後的查找得到新內容、失敗時維持舊內容；資源的世代切換由 WI-46 與 WI-48 以真實型別驗證。不做檔案變動的自動重載。

**不外洩**
- 以含獨特標記字串的真實金鑰庫（由 JDK 工具產生）驗證：標記字串不出現在任何 API 回應（含錯誤與 500）、資料庫內容、Engine log、run log、trace、metric 標籤、錯誤訊息；金鑰庫密碼同樣不出現。
- 對已知機密值做字串比對遮蔽（最後一道防線）：以真實的 log 輸出路徑驗證，載入的標記字串被寫入 Engine log 或 run log 時被遮蔽；此為盡力而為，不取代「不傳遞」。端對端（服務端回射金鑰進入錯誤與 log）由 WI-46 與 WI-51 驗證。
- 機密與金鑰庫的 Engine 內部型別不穿過 Engine 與 run 的邊界。

**文件**
- 08-api 新增兩個端點與 `secret_store_not_configured`、`secret_store_unreadable`；04 的組態說明新增金鑰庫相關項目；`ApiDocumentationTest` 通過。

## 架構約束

- 機密值不經任何 HTTP 請求本文；管理 API 與 Console 只接受別名。
- 機密項目的值限可列印 ASCII（[ADR-019](../adr/ADR-019-typed-shared-resources.md) 第 6 點）；此限制不適用於憑證與私鑰項目。非 ASCII 機密的混合方案是日後擴充，不在本項範圍。
- 機密提供者保持可替換（沿用 [ADR-012](../adr/ADR-012-api-authentication.md) 的原則），日後可加入其他來源。
- 同 JVM 內 unsafe pipeline 可讀取行程環境與記憶體，此限度接受並記載（[07](../07-nfr-risks.md)），本項不嘗試補強。
- 測試使用真實 PKCS12 檔案（以 JDK 工具產生）與真實 PostgreSQL（Testcontainers），不使用 Stub 或 Mock；需要替代品時使用自製的簡易真實實作（Fake）；嚴格 TDD；不新增 CI；完成程式碼變更時依專案規則先以 ktfmt 格式化。

## 實作結果（2026-10-07）

組態與程式：`engine/src/main/kotlin/secret/`（`SecretStore` 介面與 `KeystoreSecretStore`、`NoSecretStore`、`KeystoreLoader`、`SecretCatalog`、`SecretMasking`），路由 `SecretRoutes.kt`，組態 `secrets.*`（`application.yaml`）。測試在 `engine/src/test/kotlin/secret/` 與 `config/SecretSettingsTest.kt`，金鑰庫一律由 JDK 25 的 `keytool` 產生（`support/Keystores.kt`）。

實作時的決定與細節（超出條文之處，供審閱）：

- 重載回應的 `changed` 包含新增、移除與內容有變的別名（被移除的別名其引用資源同樣受影響）；內容是否有變以 SHA-256 指紋判斷（機密為值，憑證項目為憑證，私鑰項目為憑證鏈，不碰私鑰本身），指紋不輸出。
- 「對其他使用者可讀」的警告只看 others 的讀取權限；群組可讀不警告（根擁有、服務群組可讀是常見的正確設定）。
- 機密值為空，或含非可列印 ASCII（0x20 至 0x7E 以外的位元組，含 `keytool -genseckey` 產生的二進位金鑰），為 `invalid_secret`。
- 遮蔽（`SecretMasking`）是行程內全域的登記表：金鑰庫開啟時登記其可用機密與金鑰庫密碼，關閉時撤銷；掛在 logback 的 `%msg` 與例外轉換器（`logback.xml`）、run log 的每一行寫入（`RunRecorder`），以及 run 結束時記錄的失敗訊息與堆疊（`RunProgress`）。
- 開啟失敗類別（`file_missing`、`wrong_password`、`corrupt`、`wrong_format`、`unreadable`）的辨識：先檢查檔頭（0x30 開頭才是 PKCS12，空檔為毀損，其餘為格式不符），再載入；檔案結尾例外為毀損，原因為 `UnrecoverableKeyException` 的 I/O 例外為密碼錯誤（依型別，不依訊息文字），其餘為無法開啟。`keystore.type.compat` 的實測與決定記於 ADR-019 第 6 點的「實測結果」。
- 載入時遇到三種之外的項目類型：忽略並記錄警告（別名）。JDK 25 的 PKCS12 無法寫出這種項目，此分支沒有真實檔案可驗證。
- 機密與金鑰庫的內部型別（`SecretValue` 等）只在 Engine 內；run 的 class loader 只有 Runner、core 與 Kotlin 函式庫（既有的封裝後行程測試驗證 run 看不到 Engine 的類別）。
- 重載失敗回應的 `problem` 為失敗類別；Console 的文字只補了兩個新錯誤碼（`consoleApiDocCheck` 要求），畫面屬 WI-49。
