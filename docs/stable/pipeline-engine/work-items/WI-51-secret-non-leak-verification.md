# WI-51 機密不外洩與型別化資源的整體驗證

本文回答：型別化資源全部落地後，機密是否確實不外洩、文件與實作是否一致、各項風險是否有對應的驗證。狀態：已核可（2026-10-06）。相依：WI-42、WI-47、WI-48、WI-50、WI-52、WI-53、WI-55、WI-56、WI-57、WI-58、WI-59、WI-60、WI-61、WI-62、WI-63、WI-64。決策見 [ADR-019](../adr/ADR-019-typed-shared-resources.md) 與 [07](../07-nfr-risks.md)。

## 背景

各項已各自驗證機密不外洩；本項在真實打包的 Engine 上做一次跨型別、跨輸出面的整體驗證，並核對文件與實作的一致性。本項不新增功能。

## 行為與驗收條件

**機密不外洩（整體）**
- 使用真實打包後的 Engine、真實 PostgreSQL、真實 PKCS12 金鑰庫（含獨特標記的資料庫密碼與 API 金鑰）、真實編譯的測試 jar，涵蓋 `jdbc-pool` 與 `openai-compatible`（Fake 服務端）；刻意製造連線失敗、驗證失敗、服務端回射金鑰、未預期例外與 500。
- 逐一檢查所有輸出面，標記字串與金鑰庫密碼都不出現：全部 API 回應與錯誤本文、資料庫內容（含傾印）、Engine log、run log、trace、metric 標籤與值、effective metadata 與事件、重載回應；Console 的 DOM、瀏覽器儲存與網路回應（手動腳本）。
- 服務端回射金鑰於 HTTP 回應本文時，Engine 不處理本文（接受的限度）：驗證回應標頭被剝除授權相關者、Engine 自己的 log 中被遮蔽，並將此限度記載。
- Unsafe pipeline 的限度驗證並記載：它可直接使用 JDK 連到同一個實體而繞過容量與中介，也可讀取行程環境（含環境變數提供的金鑰庫密碼）；資源的資料庫密碼與 API 金鑰不在行程環境與 pipeline 可取得的任何契約回傳中。金鑰庫密碼以機密檔提供時，其值不在行程環境中。記憶體層級的讀取為協作式模型的既有限度，不在驗證範圍。

- 憑證：以含獨特標記的私鑰與機密的真實金鑰庫，確認私鑰與其衍生資料、金鑰庫密碼不出現在同一組輸出面（含機密清單、檢查結果、重載回應、Console）；只顯示別名、主旨、到期日與 SHA-256 指紋。驗證主機名稱與憑證驗證在各路徑都無法被關閉，且 pipeline 取不到私鑰與安全上下文。

**行為的整體驗證**
- 三種有實體的型別各有一個 pipeline 在真實 Engine 中完成端到端：以同一份行為驗收測試同時對 Engine 與開發入口執行，兩者行為一致。
- 競態與回收：並行的刪除、強制釋放、金鑰庫重載、修改設定與取得資源交錯時，沒有實體同時被兩個 run 使用、沒有殘留連線或請求、沒有 class loader 因存取端而無法回收。
- 刪除、強制釋放、檢查、重載、修改都記錄管理員名稱。

**文件一致性**
- `ApiDocumentationTest` 通過，08-api 與路由、錯誤碼、欄位逐項核對一致。
- 07 的憑證與 TLS 風險（到期、信任範圍、不可關閉的驗證）各有對應驗證，WI-52 的待實測項目已有結論並寫回文件。
- 逐項核對 [05](../05-ipc.md)、[06](../06-data-model.md)、[07](../07-nfr-risks.md)、[04](../04-deployment.md) 與平台指南、ADR-019 的描述與實際行為；不一致處回報，由架構修訂文件（本項不改行為以遷就文件）。
- 07 列出的每個 ADR-019 風險都有對應的驗證或明確的接受註記。

**回歸**
- 全部既有測試（含 `packagedTest`）在 JDK 25 下通過，`ktfmtCheck` 通過，測試數量不減少；Console 的前端單元測試通過，瀏覽器手動腳本全數執行並附結果。

## 架構約束

- 本項只新增測試、驗證腳本與驗證結果的回報；發現缺陷時回報並指回對應的工作項，不在本項內擴充功能。
- 測試使用真實容器、真實瀏覽器與 Fake，不使用 Stub 或 Mock；不新增 CI；Console 的瀏覽器測試為本機手動執行。

## 實作結果（2026-10-09）

本項只新增測試、一支真實瀏覽器腳本與兩處 README 的事實修正，沒有修改任何產品程式；發現的缺陷與文件不一致列在下方，指回對應的工作項，由架構決定處置。

**新增的測試與腳本**（全部以真實元件：打包後的 `engine.jar` 行程、Testcontainers 的 PostgreSQL 17、`keytool` 做的 PKCS12 金鑰庫、真實編譯的 pipeline jar、自製 Fake OpenAI 相容服務〔真實 socket，含 TLS 與 mTLS〕、真實 OpenTelemetry Collector 容器、真實 Chrome；沒有 Stub 或 Mock；暫存目錄沿用 WI-57 的 `TestDirectories`）：

- `engine/src/test/kotlin/support/PackagedEngine.kt`：以部署方式啟動 Engine（遷移行程、環境變數、`PORT`），經 API 操作並保留每一個回應供搜尋；`PostgresTestContainer.dump` 以伺服器自己的 `pg_dump` 取得資料庫傾印。
- `packaged/PackagedSecretNonLeakTest`（4 個）：整體不外洩（見驗收第 1、2、3 條）與 unsafe pipeline 的限度（第 4 條）。
- `packaged/PackagedCertificateNonLeakTest`（1 個）：憑證（第 5 條）。
- `core` 的 `ContractCarriesNoKeyMaterialTest`（1 個）：core 的公開型別、方法、建構子與欄位不帶 `java.security`、`javax.security`、`javax.net`、`java.sql`、`javax.sql`、`java.net.http` 的型別。
- `packaged/PackagedAccessorBehaviorTest`、`PackagedOpenAiBehaviorTest`、`PackagedJdbcBehaviorTest`（7、17、7 個）與 `support/PackagedEngineRig.kt`：開發入口與 Engine 共用的行為驗收套件（WI-43、WI-46、WI-48），這次對打包後的 Engine 經 API 執行。
- `packaged/PackagedResourceRaceTest`（4 個）：競態、回收與稽核（第 7、8 條）。
- `console/e2e/secrets.e2e.ts`（2 個，手動）：Console 的 DOM、瀏覽器儲存、cookie 與網路回應；前置條件已加入 `console/e2e/README.md`。

**驗收條件逐條**

| # | 條件 | 結果 | 依據 |
|---|---|---|---|
| 1 | 打包 Engine、真實 PostgreSQL、含標記的真實金鑰庫（資料庫密碼、API 金鑰、另一組錯誤的密碼與金鑰、金鑰庫密碼皆為只出現一次的標記）、真實 jar，涵蓋 `jdbc-pool` 與 `openai-compatible`；連線失敗、驗證失敗（錯誤密碼與錯誤金鑰）、服務端回射金鑰（錯誤、標頭、本文、串流、5xx）、pipeline 的未預期例外、Engine 的 500 | 通過 | `PackagedSecretNonLeakTest` 第 1 個 |
| 2 | 所有輸出面不含任何標記：API 回應與錯誤本文（含重載的成功與失敗、檢查、run、log、定義與 effective metadata、型別目錄、system、trigger、白名單）、資料庫全部表與 `pg_dump`、Engine 的輸出、run log、Collector 收到的 trace 與 metric（名稱、標籤與值） | 通過 | 同上；以「服務確實收到金鑰」「run 確實用密碼連上資料庫」「Collector 確實收到資源的 metric 與 span」「遮蔽確實作用（`Bearer ***`）」確認不是空驗證。Console 的部分見第 6 條 |
| 3 | 回射於 HTTP 回應本文時 Engine 不處理本文；回應標頭剝除授權相關者；Engine 自己的 log 遮蔽 | 通過，限度已記載 | pipeline 拿到的成功回應本文含金鑰（接受的限度，測試鎖定）；標頭中的回射被遮蔽、`X-Api-Key`／`Set-Cookie` 被剝除；串流事件與 run 失敗訊息中為 `***`。限度記載於 ADR-019 決策 6「限度」與 07「機密外洩」，本項未改文件 |
| 4 | Unsafe pipeline 的限度：可直接以 JDK 連到同一實體而繞過容量與中介；可讀行程環境（含環境變數提供的金鑰庫密碼）；資源的密碼與金鑰不在行程環境與任何契約回傳中；以機密檔提供時密碼不在行程環境 | 通過 | `PackagedSecretNonLeakTest` 第 2 至 4 個：環境變數的密碼讀得到；兩種資源的所有零參數契約方法（以反射逐一呼叫）、`System.getenv()`、`System.getProperties()` 都不含密碼與金鑰；機密檔時環境只有檔案位置；另一個未宣告資源的 unsafe pipeline 在 `llm`（容量 1）被持有時以 JDK HTTP 用戶端直接連到服務，被服務以 401 拒絕（沒有金鑰）。**另見**下方「需要架構修訂」第 3 點：機密檔本身 unsafe pipeline 讀得到 |
| 5 | 憑證：含私鑰與機密的真實金鑰庫，私鑰與其衍生資料、金鑰庫密碼不在同一組輸出面（含機密清單、檢查結果、重載回應、Console）；只顯示別名、主旨、到期日與 SHA-256 指紋；主機名稱與憑證驗證在各路徑都無法關閉；pipeline 取不到私鑰與安全上下文 | 通過 | `PackagedCertificateNonLeakTest`：私鑰以 base64（整段前 40 字與每一行 64 字）與十六進位搜尋；機密清單每張憑證只有 `subject`、`notAfter`、`daysLeft`、`fingerprint`、`expiry`，指紋與測試自己算的 SHA-256 相同。主機名稱：`openai-compatible` 的指定信任與 JVM 預設信任、`jdbc-pool` 的 `verify-full`，以 IP 連到只給 `localhost` 的憑證一律 `hostname_mismatch`；以 `JAVA_TOOL_OPTIONS` 開啟 JDK 的 `jdk.internal.httpclient.disableHostnameVerification=true` 時，HTTP 的四種資源全部失敗（連正確的主機也失敗，不會不驗證就通過，與 WI-52 的設計一致），PostgreSQL 不受影響；`verifyHostname`、`insecure` 等欄位為 `invalid_settings`，`sslmode`、`sslhostnameverifier` 為 `property_not_allowed`。pipeline 以反射呼叫所有零參數契約方法也拿不到私鑰或 `SSLContext`；`ContractCarriesNoKeyMaterialTest` 保證契約沒有可以拿到它們的型別。Console 見第 6 條 |
| 6 | Console 的 DOM、瀏覽器儲存與網路回應（手動腳本） | 通過 | `secrets.e2e.ts` 2 個通過：真實 run 用過兩種資源後，管理員開啟每一頁、從頁面檢查兩個資源並重載金鑰庫，開發人員開啟每一頁與兩個 run；金鑰、資料庫密碼、金鑰庫密碼與兩把私鑰都不在任何回應、DOM、storage 與 cookie。Red：把一個頁面上看得到的字串（`demo-db`）當成「資料庫密碼」時兩個測試都失敗 |
| 7 | 三種有實體的型別各有一個 pipeline 在真實 Engine 中端到端，同一份行為驗收測試對 Engine 與開發入口執行，行為一致 | 通過 | 打包後的 Engine 31 個全部通過（`file` 與通用規則 7、`openai-compatible` 17、`jdbc-pool` 7），與 `devkit` 的 `DevAccessorBehaviorTest` 等是同一份套件 |
| 8 | 競態與回收：並行的刪除、強制釋放、金鑰庫重載、修改設定與取得資源交錯時，沒有實體同時被兩個 run 使用、沒有殘留連線或請求、沒有 class loader 因存取端而無法回收 | **部分未通過** | `PackagedResourceRaceTest`：25 秒內交錯上述動作（含刪除後立即建立 run、與刪除並行的重新定義），服務端完整服務的請求與資料庫端成功的語句，依時間逐對比較，不同 run 之間沒有重疊（通過）。**殘留連線**：未通過，見缺陷 1。**class loader**：未通過，見缺陷 2 |
| 9 | 刪除、強制釋放、檢查、重載、修改都記錄管理員名稱 | 通過 | 同一競態測試檢查 Engine 的輸出中五種動作都有 `by root` 的紀錄（建立另有 `created ... by root`） |
| 10 | `ApiDocumentationTest` 通過，08-api 與路由、錯誤碼、欄位逐項核對 | 通過 | `ApiDocumentationTest` 在全量 `check` 中通過；資源、機密、檢查、重載的欄位與 `ResourceApiModels`、`SecretApiModels` 逐項相符，打包測試另鎖定機密清單與憑證的欄位集合 |
| 11 | 07 的憑證與 TLS 風險各有對應驗證；WI-52 的待實測項目已有結論並寫回文件 | 通過 | 到期：`TlsResourceCheckTest`、`TlsSecretApiTest`、`TlsTelemetryTest`（WI-52）；信任範圍與不可關閉的驗證：本項的憑證測試；WI-52 的結論已在 ADR-019 決策 12「實測結果」 |
| 12 | 逐項核對 05、06、07、04 與平台指南、ADR-019 的描述與實際行為 | 完成，不一致處見下 | |
| 13 | 07 列出的每個 ADR-019 風險都有對應的驗證或明確的接受註記 | 完成 | 見下方「07 的風險對照」 |
| 14 | 全部既有測試（含 `packagedTest`）在 JDK 25 下通過、`ktfmtCheck` 通過、測試數量不減少；Console 前端單元測試通過；瀏覽器手動腳本全數執行並附結果 | **未全部通過** | 見下方「回歸」：失敗的只有本項新增、揭露缺陷 1 與 2 的 3 個測試 |

**發現的缺陷（本項不修正）**

1. **刪除 `jdbc-pool` 資源不會關閉其連線池的連線**（ADR-019 決策 8「刪除會一併清除該資源的設定與其連線池與用戶端世代」；指回 [WI-48](WI-48-jdbc-pool-resource.md)，刪除本身為 [WI-40](WI-40-typed-resources-and-deletion.md)）。重現：`PackagedResourceRaceTest`「deleting a jdbc-pool resource closes the connections of its pool」（確定性，不需要競態）：一個 run 用過資源後連線池保留一條閒置連線，`DELETE` 回 204 之後 60 秒該連線仍在（`pg_stat_activity`）。原因：`ResourceRemoval` 只刪除資料庫中的定義，`JdbcPools` 沒有隨刪除關閉該資源的世代（世代只在被新世代取代且沒有持有者時關閉）。後果：刪除後連線一直佔用資料庫端的連線數（`CONNECTION LIMIT`）直到 Engine 停止；同名重建的資源若設定相同，可能沿用舊世代（未驗證）。競態測試「no request or connection is left once the resources are deleted」因同一原因失敗。`openai-compatible` 沒有跨 run 的用戶端，請求在刪除後為 0（通過）。
2. **第一個呼叫 `openai-compatible` 的 run，其 class loader 永遠不會被回收**（ADR-001 的回收、07「class loader 洩漏」；指回 [WI-46](WI-46-openai-compatible-resource.md)）。重現：`PackagedResourceRaceTest`「after the same races every class loader of a run is reclaimed」：以 `jcmd GC.run` 反覆回收後，`runline.runner.classloaders.created` 與 `reclaimed` 恆差 1（例：26 與 25）。原因：`OpenAiBinding` 的 `TIMERS`（伴生物件，整個 Engine 生命期的 `openai-timers` 執行緒）在第一次排程逾時時才建立執行緒，建立它的是 run 的執行緒，新執行緒因此繼承 run 的 context class loader 並一直持有。診斷實驗（只在本機，未提交）：讓該執行緒不帶 context class loader 後，同一測試通過。Engine 的 log 在那個 run 結束時有 `left threads running: [openai-timers, ...]`。只洩漏一個（之後的 run 都沿用同一條執行緒），不隨 run 數成長。
3. 觀察（非缺陷，供 WI-48 參考）：每個用過 `jdbc-pool` 的 run 結束時 Engine 記錄警告 `left threads running: [jdbc-statement-db, jdbc-statement-timer-db]`：這兩個執行緒池由 run 的執行緒在第一次需要時建立，存取端關閉時以 `shutdownNow` 非同步結束，run 結束的檢查看到它們還在。它們隨後結束，該 run 的 class loader 也被回收（競態測試中只有缺陷 2 的一個未回收）。

**文件一致性**

已修正（純事實）：

- 根目錄 `README.md` 的組態表缺 `RUNLINE_CERTIFICATE_WARNING_DAYS`（WI-52 新增），已補一列（選填，預設 30，至少 1）。
- `docs/stable/pipeline-engine/README.md` 文件索引寫「決策記錄（ADR-001 至 020）」，ADR-021 已存在，改為「至 021」。
- `console/e2e/README.md` 加入新腳本 `secrets` 的前置條件。

需要架構修訂（本項未改）：

1. ADR-019 決策 4「閒置逾時」寫「上傳（多部分表單與二進位本文）」，但端點目錄沒有以原始二進位為本文的上傳條目（`GET /api/v1/resource-types` 的 `request` 只有 `none`、`json`、`multipart`；WI-60「已知的保留點」也記載了這點）。
2. 上傳的閒置判定可能比 `idleMs` 提早：ADR-019 決策 4 與 08-api 只寫了上限（「不超過 `idleMs` 加上填滿緩衝的時間」），沒有寫下限；計時從用戶端最後一次取走資料起算，可能早於服務端最後一次讀取（WI-60 實測服務端停止讀取後 246 至 249 ms 判定，`idleMs` 300），兩次讀取間隔接近 `idleMs` 的服務可能被判為閒置。
3. ADR-019 決策 6「限度」與 07「金鑰庫與密碼的保管」只寫了「密碼來源為環境變數時 unsafe pipeline 可讀取」。實測：以機密檔提供時，環境中有檔案的位置（`RUNLINE_KEYSTORE_PASSWORD_FILE`），Engine 行程讀得到該檔，同一行程內的 unsafe pipeline 也讀得到（Docker secret 的 `/run/secrets/...`、systemd 憑證目錄皆同）。機密檔只是不出現在環境與診斷輸出，並不防同行程的 unsafe pipeline；金鑰庫檔案本身亦同。建議在限度中寫明，或另議緩解。
4. 07「可觀測性」、04「組態與密鑰」的遙測匯出與根目錄 README 都說 log 經 OpenTelemetry 匯出（`OTEL_LOGS_EXPORTER`）。實測 Engine 的 log 只寫標準輸出（`logback.xml` 只有 `ConsoleAppender`，沒有 OpenTelemetry 的 log 橋接）；`OTEL_LOGS_EXPORTER=otlp` 時 Collector 收到 metric 與 span，沒有收到任何 log。需決定是文件修正（log 只走 stdout，符合 12-Factor）或缺陷（WI-63）。README 的同一句因此沒有修改。
5. 05「外部介面」的 run log 串流寫「以 WebSocket 或 SSE 推送……（背壓：緩衝有上限，超過則丟棄舊 log 並標記）」；實作只有 WebSocket，從已儲存的 log 讀取（`RunLogFollower`），沒有緩衝與丟棄，慢的客戶端只是落後（08-api 的該端點描述與實作相符）。
6. 04「組態與密鑰」寫「憑證到期警告門檻是 Engine 組態（預設 30 天，名稱由 WI-52 定）」，名稱已定為 `RUNLINE_CERTIFICATE_WARNING_DAYS`（同段稍後已寫出）；屬事實更新，04 不在本項可改範圍，列出供架構一併修訂。
7. 刪除 `jdbc-pool` 不關閉連線（缺陷 1）與 ADR-019 決策 8、06「共享資源的定義變更（含刪除）」的描述不符；修正產品後兩者一致，文件不需改。

核對後與實作一致的部分：05「資源存取端」（邊界只有 JDK 型別、`errorId`、`sqlState`、串流與檔案為單一呼叫、`abort` 後再等待）、06 Shared Resource 的欄位與「持有者不入庫」、04 的金鑰庫組態鍵名與失敗類別、資源根目錄與選填項目的預設值（與 `application.yaml`、`EngineConfig` 相同）、平台指南的金鑰庫掛載（由既有的 `DockerKeystoreMountTest` 驗證；systemd 為 WI-42 的手動結果，本項未重做）、ADR-019 決策 6、7、8、10、12 的行為（見上表）。

**07 的風險對照**（ADR-019 相關各列）

| 07 的風險 | 驗證或接受 |
|---|---|
| 共享資源鎖是約定式（`counter`） | 接受；有實體的型別被 unsafe pipeline 以 JDK 繞過但拿不到金鑰：`PackagedSecretNonLeakTest` |
| 共享資源占用過久、持有者卡住 | 接受（ADR-007）；強制釋放：`ResourceRunIntegrationTest`、本項競態測試 |
| 共享資源狀態遺失 | `PackagedEngineTest`（WI-64：重啟後沒有持有者、run 為中斷） |
| 資源位址指向內部服務 | 接受（管理員信任）；不跟隨離開根位址的重新導向：`OpenAiBindingTest`、行為套件（打包 Engine 亦通過） |
| 資源檔案路徑穿越 | `AccessorBehaviorSuite`（打包 Engine 亦通過）、`ResourceAdminTest` 與 `ResourceCheckerTest` 的 `path_outside_root` |
| 資源檢查卡在無法中斷的檔案系統物件 | `ResourceCheckerTest`、`ResourceCheckApiTest`（具名管線） |
| 機密外洩 | 本項 `PackagedSecretNonLeakTest`、`secrets.e2e.ts`；HTTP 本文回射為接受的限度 |
| 金鑰庫與密碼的保管 | 本項 unsafe 測試（環境變數的限度；機密檔的限度見「需要架構修訂」第 3 點）、`KeystoreSecretStoreTest` 的權限警告、`DockerKeystoreMountTest` |
| 機密字元集與不可偵測的損毀 | `KeystoreLoaderTest`、`SecretApiTest`（`invalid_secret`）；不可偵測者為接受的限度 |
| 憑證到期與信任範圍 | WI-52 的測試（到期）與本項憑證測試（信任、不可關閉、不外洩）；私鑰密碼同金鑰庫密碼為接受的限度 |
| 持有者占用實體 | 強制釋放使存取端失效並回收：本項競態測試（無重疊）、`ResourceAccessorRunTest`；刪除後的回收見缺陷 1 |
| 資料庫帳號權限過大 | 接受，營運要求（04） |
| 連線屬性注入 | `JdbcSettingsTest`、`JdbcResourceApiTest`、本項憑證測試（TLS 屬性被拒） |
| 模型服務長時間生成占用容量 | 接受（ADR-019 決定 6）；持有、等待、生成時間 metric：`OpenAiResourceObservabilityTest` |
| 逾時不當 | `OpenAiBindingTest`、`OpenAiBehaviorSuite` 的逾時案例（打包 Engine 亦通過） |
| 取消後服務端仍在生成 | **未驗證**：需要真實目標服務（WI-46 的手動腳本 `:accessors:verifyOpenAiService`），本環境沒有；Engine 側的取消與額度歸還由行為套件驗證 |
| 端點目錄含有狀態或破壞性條目 | `ResourceTypeCatalogApiTest`、`OpenAiSettingsTest`（預設不啟用、`stateful`） |
| 大檔上傳與二進位回應 | WI-53、WI-60 的測試（`OpenAiBindingMultipartTest` 等），行為套件的上傳與下載案例（打包 Engine 亦通過） |
| 開發入口與 Engine 的資源行為差異 | 同一份行為套件：開發入口、行程內 Engine、打包 Engine 三者皆通過 |
| class loader 洩漏（ADR-001 的列，與存取端相關） | 本項競態測試：缺陷 2 |

**Red 的證據**（本項的測試驗證既有行為，多數第一次執行即通過；以下確認它們會因正確的原因失敗）：

- 整體不外洩：暫時讓 `SecretMasking.mask` 原樣傳回（只在本機，已還原），測試以 `the service said {"echo":"Bearer sk-marker-…"}` 失敗。
- `ContractCarriesNoKeyMaterialTest`：暫時把 `java.util.function.` 加入禁止清單，測試列出 `ResourceLink.getCall` 等而失敗。
- 競態測試的重疊判斷：第一版把 `llm` 與 `spare`（同一服務的兩個資源）放在一起比較，測試正確地報出兩者之間的重疊。
- 缺陷 1、2 的兩個測試在目前的產品上失敗，原因如上；缺陷 2 的診斷實驗顯示修正後會通過。
- 瀏覽器腳本：見第 6 條。

**回歸**

- `./gradlew cleanTest :engine:cleanPackagedTest :engine:cleanConsoleTest :engine:cleanConsoleTypecheck :engine:cleanConsoleApiDocCheck check ktfmtCheck --continue`（JDK 25，以 root 執行，30 分鐘）：`ktfmtCheck` 通過；accessors 355（21 個跳過，同 WI-57）、analyzer 133、core 89、devkit 184、runner 58、engine 1134（1 個跳過，同 WI-57）全部通過；Console 單元測試 1093、API 文件檢查 9 個、型別檢查通過；`packagedTest` 104 個中 101 個通過，**3 個失敗**，都是本項新增、揭露缺陷的測試：`PackagedResourceRaceTest` 的「deleting a jdbc-pool resource closes the connections of its pool」與「no request or connection is left once the resources are deleted」（缺陷 1）、「every class loader of a run is reclaimed」（缺陷 2）。整體 `check` 因此失敗，待 WI-48、WI-46 修正後轉綠。測試數量沒有減少（WI-60 驗收時為 accessors 352、core 88、devkit 182、engine 1124、packagedTest 63，之後的 WI 與本項只有增加）。
- 真實瀏覽器腳本（`console/e2e/README.md` 的步驟 1 至 8，新的 PostgreSQL 17、`keytool` 做的金鑰庫、HTTP 與 TLS 加 mTLS 兩個 Fake 行程、打包後的 Engine、Chromium 1194）：既有 9 支共 90 個全部通過；新的 `secrets.e2e.ts` 在同一次執行中因 `expect.poll` 不能用在 `beforeAll` 而整支失敗（2 個被略過，腳本本身的錯誤），修正後在依同樣步驟新建的環境單獨執行，2 個通過。
