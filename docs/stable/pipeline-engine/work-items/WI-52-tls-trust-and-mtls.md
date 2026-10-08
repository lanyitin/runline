# WI-52 TLS 信任與 mTLS 用戶端憑證

本文回答：`openai-compatible` 與 `jdbc-pool` 如何信任內網自簽或內部 CA 的伺服器憑證、如何以 mTLS 用戶端憑證連線，以及憑證如何被檢視、監看到期與輪替。狀態：已核可（2026-10-08；2026-10-07 依使用者補充需求納入）；已實作，見「實作結果」。相依：WI-41、WI-46、WI-48、WI-50。決策見 [ADR-019](../adr/ADR-019-typed-shared-resources.md) 第 12 點。

## 背景

金鑰庫（WI-41）已能載入受信任憑證項目與私鑰項目並列出別名與項目類型；兩個型別（WI-46、WI-48）目前只使用機密項目，且以 JVM 預設信任連線。本項讓兩個型別選用 `trustAliases` 與 `clientCertAlias`，並補上憑證的檢視、到期監看與 Console 欄位。排在 WI-50 之後，因為 Console 表單與機密檢視已存在，本項只擴充。

## 行為與驗收條件

**資源設定**
- `openai-compatible`（根位址為 `https` 時有效；`http` 帶這兩個欄位被拒絕）與 `jdbc-pool` 各新增選填的 `trustAliases`（多個）與 `clientCertAlias`（單一）。未設定時行為與現行完全相同。
- 別名類型檢查：`trustAliases` 只接受受信任憑證項目、`clientCertAlias` 只接受私鑰項目、`secretAlias` 只接受機密項目；不符時回 422 `invalid_resource`（`problem` 為別名類型不符），檢查回報同一類別。別名狀態為 `not_set`、`found`、`missing`、`wrong_type`，永遠不含任何項目內容。
- 設定了 `trustAliases`：只信任指定的憑證，不信任 JVM 預設信任的憑證（以真實的 TLS 服務端驗證：以公開信任鏈簽發的服務端在僅指定內部 CA 時連線失敗）。未設定：使用 JVM 預設信任。信任設定只作用於該資源的連線，不改變 JVM 全域預設，也不影響其他資源與 Engine 自身的對外連線。
- 主機名稱驗證與憑證驗證不可關閉：沒有欄位、連線屬性或旗標可關閉；以憑證主旨與連線主機不符的真實服務端驗證連線被拒絕且失敗類別為主機名稱不符。`jdbc-pool` 的 TLS 模式固定在資料庫設定檔，管理員不能選擇較弱模式；額外連線屬性允許清單不得含任何 TLS 相關項目（以此類屬性被拒絕驗證）。
- 伺服器要求用戶端憑證時，設定了 `clientCertAlias` 的資源連線成功，未設定者失敗且類別為用戶端憑證被拒絕或握手失敗（以要求 mTLS 的真實服務端驗證）。私鑰項目的保護密碼就是金鑰庫密碼；私鑰與憑證鏈只在 Engine 內使用。

**檢查、到期與可觀測**
- 檢查（WI-43 機制）對所參照的每個憑證回報別名、主旨、到期日、剩餘天數與 SHA-256 指紋（私鑰項目回報憑證鏈中每張憑證），並以真實握手驗證信任、主機名稱與用戶端憑證。失敗類別至少區分：別名缺失、別名類型不符、憑證已過期、信任失敗、主機名稱不符、用戶端憑證被拒絕、連線失敗、逾時。類別之間的辨識若依賴例外訊息文字，須實測並記載。
- 剩餘天數低於警告門檻（Engine 組態，預設 30 天，名稱依 `RUNLINE_` 慣例寫入 04）時，檢查結果帶到期警告，不使檢查失敗；已過期則失敗。
- `GET /api/v1/secrets` 的每個別名帶項目類型；憑證項目另帶主旨、到期日、剩餘天數與指紋；永遠不含私鑰、私鑰衍生資料、機密值與金鑰庫密碼。以含獨特標記的私鑰與機密驗證標記不出現在任何輸出面。
- Metric：各憑證別名的剩餘天數（標籤只用別名）；TLS 失敗依類別計數。Log 記錄憑證別名與類別，不記錄憑證內容之外的任何金鑰資料。

**輪替與重載**
- 沿用整體重載與新世代：重載後以指紋判斷有變更的憑證別名，其引用資源之後被取得的 run 使用新的信任與用戶端憑證，進行中的 run 不受影響，舊世代的連線池與用戶端在持有者全部結束後關閉；重載回應列出有變更的別名與引用者。以真實金鑰庫替換憑證並以兩個世代的真實 TLS 服務端驗證。

**實作路徑與待實測項目**（結果不如預期時停下來回報，不自行改變信任或驗證模型）
- HTTP 用戶端：由金鑰庫項目建立該資源專屬的安全上下文；實測主機名稱驗證預設開啟，且 Engine 不設定任何可關閉它的系統屬性。
- `jdbc-pool`：PostgreSQL 驅動只接受 socket 工廠類別名稱，不接受安全上下文物件。推薦：Engine 內建的工廠類別，依連線池身分取得該池的安全上下文，類別名稱由資料庫設定檔固定。實測：驅動版本與此做法的相容性、主機名稱驗證是否生效、連線池重建時的世代切換、不使私鑰落地。可行性不如預期或需要私鑰寫入暫存檔時，停下來回報。
- 私鑰項目保護密碼與金鑰庫密碼的關係、金鑰庫密碼含非 ASCII 的往返行為，以 JDK 25 實測並回報，供 WI-42 手冊使用。
- 開發入口不支援 TLS 信任與用戶端憑證，明確拒絕設定了憑證別名的資源（[ADR-019](../adr/ADR-019-typed-shared-resources.md) 已決定事項 16）。

**Console 與文件**
- Console 的型別表單新增信任別名（多選）與用戶端憑證別名（單選）欄位，選項來自金鑰庫現有別名依項目類型篩選；機密檢視顯示項目類型、主旨、到期日、剩餘天數、指紋與到期警告；絕不顯示私鑰。沿用 WI-49、WI-50 的驗證方式（本機手動腳本）。
- 08-api 同步更新欄位、狀態、問題類別與機密清單欄位；04 的組態說明新增警告門檻；`ApiDocumentationTest` 通過。

## 架構約束

- 私鑰、憑證與安全上下文不穿過 Engine 與 run 的邊界；pipeline 取不到，Engine 代理連線。
- 不新增關閉主機名稱或憑證驗證的任何途徑；不修改 JVM 全域預設的安全上下文或信任庫。
- 私鑰不落地（不寫入暫存檔或資料庫）。
- 測試使用真實 TLS 服務端（含自簽或內部 CA 簽發、要求 mTLS、過期憑證、主機名稱不符各情境）、真實 PostgreSQL（Testcontainers，啟用 TLS）與真實 PKCS12 金鑰庫，不使用 Stub 或 Mock；需要替代品時使用自製的簡易真實實作（Fake）；嚴格 TDD；不新增 CI；完成程式碼變更時依專案規則先以 ktfmt 格式化。

## 實作結果（2026-10-08）

程式：TLS 的共用部分在 `accessors/src/main/kotlin/tls/`（`ResourceTls`：資源專屬的安全上下文；`TlsContext`：失敗類別；`TlsAliases`：設定中的別名；`TlsFailure`）；`openai-compatible` 在 `OpenAiSettings`、`OpenAiBinding`、`OpenAiProbe`；`jdbc-pool` 在 `JdbcSettings`、`PostgresProfile`（`tlsProperties`、`refusesClientCertificate`）、`PoolTls`（連線池世代的安全上下文與 `PostgresTlsSocketFactory`）、`JdbcPools`、`JdbcProbe`；Engine 在 `engine/src/main/kotlin/secret/`（憑證項目、`invalid_key`、機密清單的憑證、到期 metric）與 `resource/`（`ResourceAliases`、`ResourceChecker` 的憑證報告與警告、`TlsTelemetry`、兩個型別的 behavior）；組態 `resources.certificateWarningDays`（`RUNLINE_CERTIFICATE_WARNING_DAYS`）；開發入口 `devkit/.../LocalResources.kt`；Console `admin/CertificateAliasesField.svelte`、`SecretsPanel.svelte`、`resource-forms.ts`、`admin-model.ts`。測試的憑證一律由 `keytool` 產生（`accessors/src/testFixtures/kotlin/tls/TestPki.kt`），真實 TLS 服務端為 TLS 版的 Fake（`FakeOpenAiServer(tls = ..., requireClientCertificate = ...)`，同一份 `OpenAiServerContract` 在 TLS 加 mTLS 下 18 個通過）與啟用 TLS 的真實 PostgreSQL（`accessors/src/testFixtures/kotlin/jdbc/TlsPostgres.kt`，Testcontainers，`clientcert=verify-ca` 的帳號）。

**待實測項目的結果**（ADR-019 決策 12 的「實測結果」為摘要）：

- **HTTP 用戶端**：JDK HTTP 用戶端預設驗證主機名稱；`-Djdk.internal.httpclient.disableHostnameVerification=true` 實測會關閉它。Engine 不設定任何這類屬性。資源的信任管理器（`VerifyingTrustManager`）在連線的 SSL 參數沒有主機名稱驗證演算法時拒絕連線（類別 `hostname_mismatch`），所以這個屬性或任何未要求驗證的連線都只會失敗；以「沒有設定驗證演算法的 socket」測試，拿掉這道防護時握手成功（證明防護必要），有防護時被拒絕。
- **`jdbc-pool` 的 socket 工廠**：驅動 42.7.13 以 `sslfactory` 類別名稱、驅動的 class loader 與 `(Properties)` 建構子，每條連線建立一次工廠；工廠以屬性 `runlineTlsContext`（每個連線池世代或每次檢查一個隨機識別）取得安全上下文，識別在世代或檢查結束時撤銷。`sslmode=verify-full`：驅動在握手後以自己的驗證器再驗主機名稱，工廠也讓 JDK 在握手中驗證；以 IP 連到只有 `dns:localhost` 的伺服器為 `08006`（原因 `CertificateException`）。不使用驅動的任何憑證或金鑰檔案屬性（`sslcert`、`sslkey`、`sslrootcert`、`sslpassword`），私鑰只在記憶體中的 `KeyStore`（每個上下文自己的隨機密碼）；憑證改變產生新的連線池世代（指紋為連線池鍵的一部分），以兩個世代並存與舊世代持有者結束後關閉驗證。可行，沒有需要停下來的情況。
- **私鑰保護密碼與金鑰庫密碼**：`keytool` 對 PKCS12 忽略不同的 `-keypass`（警告），私鑰只能以金鑰庫密碼取得；JDK API 可以寫出不同密碼的私鑰項目，Engine 載入時不使用它，狀態 `invalid_key`、記錄警告（別名與類別），檢查為 `alias_invalid`。
- **金鑰庫密碼含非 ASCII（`pässwörd-密碼`）**：密碼檔（`keytool -storepass:file`、`RUNLINE_KEYSTORE_PASSWORD_FILE`）以 UTF-8 讀取，在 POSIX locale 下也可靠往返，Java API 寫出的檔案 `keytool` 也能開；`keytool` 的互動輸入在 POSIX 與 C.UTF-8 下都判為密碼錯誤；`-storepass:env` 與 `RUNLINE_KEYSTORE_PASSWORD` 只在 UTF-8 locale 下正確，POSIX locale 下環境變數被以 ASCII 解碼（11 個字元成為 17 個），開啟失敗為 `wrong_password`。給 WI-42 手冊：建議可列印 ASCII；非 ASCII 只用密碼檔。
- **失敗類別的辨識方式**（不依賴例外訊息文字）：信任管理器先以 JDK 的 PKIX 只驗憑證鏈（失敗為 `trust_failed`，原因含 `CertPathValidatorException` 的 `EXPIRED` 或 `CertificateExpiredException` 時為 `certificate_expired`），通過後再以 JDK 對整條連線的檢查（含主機名稱）驗證，此時失敗為 `hostname_mismatch`（原因含路徑驗證例外時仍為 `trust_failed`，即演算法限制）；判定結果放在拋出的例外中，由原因鏈取回。`client_cert_rejected`：金鑰管理器記錄服務是否要求過用戶端憑證；HTTP 為「要求過」加上 `SSLException`（TLS 1.3 下服務送出 `certificate_required` 警示），PostgreSQL 在 TLS 1.3 下於握手後才以 `28000`（`connection requires a valid client certificate`）拒絕，所以為「要求過」加上 SQLState `28000`。其他 `SSLException` 為 `handshake_failed`。限度：(1) 「要求過」是安全上下文層級的記號，檢查每次用新的上下文所以準確，run 的連線則以該 run（openai）或該連線池世代（jdbc）的上下文為準，同一上下文先前的成功握手也會留下記號；(2) PostgreSQL 對 `pg_hba.conf` 完全沒有符合的規則也回 `28000`，伺服器設定了 `ssl_ca_file`（因而每次都要求憑證）時會被歸為 `client_cert_rejected`。
- **開發入口**：依 ADR-019 已決定事項 16 只做 Engine 側；開發入口遇到設定了 `trustAliases` 或 `clientCertAlias` 的資源明確拒絕並說明（WI-52 之前這兩個成員本來就是 `invalid_settings`），不改用 JVM 預設信任或驅動預設。

**實作時的決定（超出條文之處，供審閱）**：

- **欄位位置**：`trustAliases`、`clientCertAlias` 放在 `settings` 內（ADR 的「設定各增兩個選填欄位」），因此沒有資料庫遷移，修改會清除 `lastCheck`，寫入時為小寫且 `trustAliases` 不得重複、最多 16 個；別名格式不合為 `invalid_secret_alias`，形狀不合為 `invalid_settings`。
- **類型不符**：建立與修改時檢查三種別名（含既有的 `secretAlias`），不符為 422 `alias_wrong_type`。這改變了 WI-46、WI-48 的一個行為：`secretAlias` 指向憑證項目原本被接受並顯示 `missing`，現在被拒絕；重載後才變成類型不符的別名，狀態為 `wrong_type`，檢查為 `alias_wrong_type`，呼叫為 `SECRET_UNAVAILABLE`。
- **資源回應**新增 `trustStatus`（`[{alias, status}]`）與 `clientCertStatus`；機密清單的憑證項目新增 `certificates`（主旨、到期日、剩餘天數、`keytool -list` 格式的 SHA-256 指紋、`expiry`：`valid`／`expiring`／`expired`），機密項目沒有這個成員；`usedBy` 與重載的 `changed` 含以憑證引用的資源。
- **檢查**：回應新增 `certificates`（每個所用憑證，含私鑰項目的整條憑證鏈）與 `warnings`（`certificate_expiring`，每個別名一次），不保存於 `lastCheck`。所用憑證已過期時檢查失敗為 `certificate_expired` 且不連線；服務憑證過期由握手判定為同一類別。剩餘天數為向下取整的整天數；警告條件為「未過期且剩餘天數小於門檻」。檢查的 log 記錄所用憑證的別名（不記錄憑證內容）。
- **`openai-compatible` 的 `https` 一律使用資源自己的安全上下文**：沒有 `trustAliases` 時以 `TrustManagerFactory` 的預設初始化取得 JVM 預設信任（與 JVM 預設上下文相同的信任庫與系統屬性），這樣才能分類失敗並套用上面的主機名稱防護。與 WI-52 之前的差異只在邊緣：JVM 預設上下文由 `javax.net.ssl.keyStore` 提供的用戶端憑證不再被使用（要 mTLS 須用 `clientCertAlias`）。
- **`jdbc-pool` 沒有任何憑證別名時維持現行**（驅動預設 `sslmode=prefer`，不驗證，被拒絕時改用不加密連線，實測確認會發生）；設定了任一別名時固定 `verify-full`，只有 `clientCertAlias` 時信任 JVM 預設。依 ADR-019 已決定事項 15。
- **別名不可用時不降級**：任一憑證別名缺失、類型不符或私鑰不可用時，run 仍取得資源，但呼叫以 `SECRET_UNAVAILABLE` 失敗且不連線，不會改用 JVM 預設信任。
- **pipeline 看到的類別不變**：TLS 失敗對 pipeline 是 `CONNECTION_FAILED`（core 契約沒有新增類別）；類別記在 metric `runline.resources.tls.failures`（標籤 `resource`、`type`、`kind`）與 Engine 的 log（資源名稱與類別）。剩餘天數 metric 為 `runline.secrets.certificate.days_left`（標籤只有 `alias`，取該項目最早到期的憑證）。
- **Console**：型別表單的「憑證（TLS）」區塊：受信任憑證以勾選框多選、用戶端憑證以選單單選，選項依項目類型從金鑰庫篩選並顯示主旨與剩餘天數，資源現有但金鑰庫已沒有的別名仍列出並標示；`alias_wrong_type` 顯示在此區塊。機密區塊在表格下方列出每個憑證項目的憑證（主旨、有效期限、剩餘天數、指紋、即將到期的警告）。卡片以文字顯示新的檢查失敗類別。沒有修改 Console 的錯誤碼（只有 `problem` 值），`consoleApiDocCheck` 不需要新的翻譯。

**驗證**：

- Red 的證據：每個循環先執行新測試並看到因行為缺失而失敗（例：信任 → `PKIX path building failed`；主機名稱 → 握手成功而非失敗；`jdbc` 的 mTLS 帳號 → `28000` 而非成功；Engine 檢查測試以前一版 production code 執行 6 個全部失敗於 `connection_failed`／`rejected`；遙測測試 2 個失敗於找不到 metric；Console 元件測試 5 個、契約測試對舊 Fake 4 個失敗）。實作先於測試寫出的幾處（主機名稱防護、預設信任與 `trustAliases` 的取代、連線池世代鍵、輪替 run 測試），以「拿掉該段 production code 後測試失敗」驗證測試有效。
- 新增的測試：`accessors`：`ResourceTlsTest`（10）、`ResourceTlsDefaultTrustTest`（2，獨立 JVM，以命令列的 `javax.net.ssl.trustStore` 使測試 CA 成為「JVM 預設信任」：未設定時連得上、設定了內部 CA 時同一服務為 `trust_failed`）、`OpenAiBindingTlsTest`（3）、`JdbcTlsTest`（7，真實 PostgreSQL TLS）、`FakeOpenAiServerTlsContractTest`（18），`OpenAiSettingsTest`、`JdbcSettingsTest` 各新增；`engine`：`KeystoreCertificatesTest`（4）、`TlsResourceApiTest`（3）、`TlsResourceCheckTest`（6，TLS Fake 與 TLS PostgreSQL）、`TlsResourceRunTest`（1，真實 run、兩次重載：用戶端憑證輪替與信任替換，持有舊世代的 run 不受影響）、`TlsSecretApiTest`（3，含私鑰標記與機密標記不出現在任何回應）、`TlsTelemetryTest`（2），`EngineConfigTest` 新增；`devkit`：`DevSessionResourcesTest` 新增兩種情形；Console：`resource-forms.test.ts` 8 個、`ResourcesPage.test.ts` 6 個、`admin-contract.ts` 1 個新增與 3 個擴充（對 Fake 與真實 Engine）。
- 對真實打包的 Engine（`engine.jar`、PostgreSQL 17、以 `keytool` 製作的 PKCS12 金鑰庫：機密、受信任憑證、兩個私鑰項目，其中一個 10 天後到期）與 TLS 加 mTLS 的 Fake 行程：`npm run test:contract` 104 個通過；新增的手動腳本 `e2e/certificates.e2e.ts` 1 個通過（憑證清單與 API 一致、即將到期警告、表單選信任與用戶端憑證後真實握手檢查通過、沒有用戶端憑證時 `client_cert_rejected` 的文字與 API 的 `lastCheck` 相同、修改後通過、zh-TW；私鑰標記與金鑰庫密碼不在任何回應、DOM、storage 與 cookie）；既有的 `e2e/resources.e2e.ts` 4 個與 `e2e/typed-forms.e2e.ts` 4 個也在同一環境通過。

**未驗證或未做**：

- 開發入口的 TLS 支援：依 ADR-019 已決定事項 16 不支援。
- 公開 CA 簽發的真實網際網路服務：以「JVM 預設信任所含的測試 CA」代替（見上），沒有連到真實公開服務。
- 真實 lemonade 或其他 OpenAI 相容服務的 TLS（手動腳本 `RealOpenAiServerContractTest` 可對 `https` 位址執行，本次沒有可用的服務）。
- `handshake_failed` 只以「以純 HTTP 回答握手的服務端」驗證（`ResourceTlsTest`；這個測試在分類邏輯之後才寫），沒有建立協定版本或加密套件不相容的服務端情境。實測另見：服務端收下 ClientHello 卻不回應時，JDK HTTP 用戶端的連線逾時涵蓋握手，結果為 `CONNECT_TIMEOUT`（檢查為 `timeout`），不是 TLS 類別。
- 憑證過期的 metric 以 gauge 讀取驗證；沒有驗證匯出到 OTLP 後的實際名稱與單位（`d`）。
- 開發入口的 `jdbc-pool` 拒絕路徑與 `openai-compatible` 共用同一段程式，只以 `openai-compatible` 測試。
