# WI-46 `openai-compatible` 型別資源（一次完整回傳）

本文回答：管理員如何把一個 OpenAI 相容服務定義為共享資源，pipeline 如何在版本化的端點目錄與受管理的請求參數下呼叫它。狀態：已核可（2026-10-06）；端點目錄、整體並行與逾時為 2026-10-07 修訂；逾時與大小上限的數值已由使用者確認採決定 14 的建議預設（2026-10-07）；已實作（2026-10-07，見「實作結果」；對真實服務的手動實測尚未執行）。相依：WI-41、WI-43（含檢查端點）。決策見 [ADR-019](../adr/ADR-019-typed-shared-resources.md) 第 4 點「`openai-compatible`」與決定 13、14。串流在 [WI-47](WI-47-openai-compatible-streaming.md)（必要，緊接本項）；多部分上傳與二進位回應的端點在 [WI-53](WI-53-openai-compatible-multipart-and-binary.md)。

## 背景

端點、金鑰與請求預設由管理員管理，pipeline 只能在管理員允許的範圍內覆寫請求參數，無法自選主機、路徑與標頭。本項是金鑰庫機密的第一個實際使用者：WI-41 只驗證金鑰庫機制本身，別名到資源的解析在本項首次驗證（見「機密別名的解析」）。

## 前置確認

目標服務的事實已確認（ADR-019 待確認問題）：需要 OpenAI 相容 API 的全部端點；最高並行請求為 1，以資源容量控制；單次生成時間不固定且可能很長。實作前仍須向使用者確認 ADR-019「待確認問題」中的逾時與大小上限數值（無回覆時採決定 14 的建議預設）。目標服務對各端點的實際支援與「連線中斷後是否停止生成」由本項的手動腳本實測。

## 行為與驗收條件

**資源設定**
- 固定、pipeline 不能覆寫：根位址（只允許 `http` 或 `https`，不含使用者資訊）、API 金鑰（機密別名，選填）、organization 與 project（選填，作為標頭送出）、額外標頭（非機密；名稱含 auth、key、token、secret、cookie 者被拒絕，不分大小寫）、啟用的端點（在端點目錄內以條目識別名稱選取，見下節）、各種逾時上限（見下節）、每 run 同時請求數上限（預設 1）、請求與回應大小上限。不合規（含啟用的條目不在目錄內）回 422 `invalid_resource`，`problem` 指出原因類別。
- 預設、可被覆寫：模型與取樣、長度相關的請求參數（temperature、top_p、max_tokens、stop、seed、回應格式等）。
- 覆寫限制（選填）：允許的模型清單（空為不限）、各參數可標為鎖定、數值參數的上限（例如 max_tokens）。

**端點目錄（決定 13）**
- Engine 內建版本化的端點目錄；每個條目固定識別名稱、方法、路徑樣板、請求本文種類、回應種類與可帶的路徑與查詢參數。目錄完整內容與預設啟用者見 ADR-019 第 4 點。本項交付目錄機制與「請求為無本文或 JSON、回應為 JSON」的條目（對話、補全、嵌入、模型、responses、內容審查、重排序、圖像生成、批次、檔案的列出與讀取與刪除中屬 JSON 者）；多部分上傳與二進位回應的條目由 [WI-53](WI-53-openai-compatible-multipart-and-binary.md) 加入，串流由 WI-47 加入。目錄裡尚未被交付的條目，資源設定啟用時被拒絕。
- 預設啟用：對話、補全、嵌入、模型；其餘（尤其有狀態的刪除與取消條目）預設不啟用，管理員逐條啟用。新版 Engine 新增的條目對既有資源預設不啟用。以真實設定驗證：未啟用的條目被呼叫時以錯誤類別拒絕且不送出請求。
- Pipeline 只能以條目識別名稱呼叫。路徑參數（識別碼）只接受限定字元集，含路徑分隔字元、相對路徑片段、控制字元者被拒絕；查詢參數只接受條目清單內者。以測試證明 pipeline 無法藉路徑參數或查詢參數改變主機、路徑或方法。
- 重排序的路徑（`/rerank` 與 `/reranking` 各為條目）以對目標服務的實測確認哪個有效；結果回報。

**整體並行與額度**
- 每 run 同時請求數（含進行中的串流，WI-47）預設 1。端點的整體並行請求不超過「容量 × 每 run 上限」；因此每 run 上限維持 1 時，容量即整體並行上限（目標服務最高並行 1 時，容量設 1）。資源的查詢回傳推導出的「整體並行上限」（08-api 隨實作更新，Console 顯示）。以 Fake 服務端驗證：容量 1 加每 run 上限 1 時，無論多少 run 與多少條 thread 同時呼叫，服務端觀察到的同時請求數從不超過 1；容量 2 時不超過 2。
- 降低容量不收回持有者，降低後到持有者結束前整體並行可暫時高於新上限；以測試鎖定此行為並寫入文件。
- 每 run 額度用盡時等待額度，受「等待額度逾時」與 run 取消約束，逾時以專屬錯誤類別回傳；等待時間不計入生成時間。

**逾時（決定 14）**
- 五種：連線逾時（預設 10 秒）、首位元組逾時（預設 15 分鐘）、閒置逾時（預設 5 分鐘，串流適用，WI-47 啟用）、選填的總時間上限（預設不設）、等待額度逾時（預設 60 秒）。管理員在資源設定；pipeline 呼叫時只能縮短，不能放寬。數值為建議預設，以使用者確認的值為準。
- 非串流呼叫時，服務端在生成結束前不送出資料，因此首位元組逾時即整體生成上限；以 Fake 服務端驗證：延遲低於首位元組逾時的回應（延遲可遠長於過去常見的整體逾時）成功，超過者得到首位元組逾時類別，且各逾時種類回傳不同的錯誤類別。未設總時間上限時，不因整體時間而中止呼叫。
- Run 取消、強制釋放或終止時，等待額度與進行中的請求立即中斷（Fake 服務端觀察到連線中斷），額度歸還；等待額度時間計入資源的等待時間 metric，不計入持有時間以外的逾時。
- 檢查（WI-43）使用獨立的短逾時（預設 10 秒，由 Engine 組態），不使用上述逾時；只做連線與一個輕量讀取（啟用了模型端點時為模型列表，否則只驗證連線與回應），不取得容量與額度、不產生內容。服務正在生成而逾時時，結果為逾時類別。

**請求規則**
- 合併規則：資源預設在先，pipeline 提供的同名項目覆蓋；被鎖定的參數、不在允許清單的模型、超過上限的數值被拒絕並回傳錯誤類別；訊息與工具定義等其餘本文由 pipeline 完全提供。逾時只能縮短，不能超過資源上限。
- 路徑與方法由端點目錄決定；pipeline 不能指定主機、路徑或標頭。`stream` 由本型別管理，本項 pipeline 要求串流時被拒絕（WI-47 加入）。
- Engine 注入金鑰（授權標頭）、organization 與 project、額外標頭，pipeline 提供的標頭不會覆蓋它們。
- 離開根位址（主機、埠、協定或根路徑之外）的重新導向不被跟隨，以錯誤類別回傳。
- 回傳給 pipeline 的回應標頭剝除授權相關者（含 `Set-Cookie`、`WWW-Authenticate`、`Proxy-Authenticate`）。
- Engine 不自動重試：連線失敗、逾時、被拒絕（401、403）、限流（429）、服務錯誤（5xx）、請求不合規（其他 4xx）、重新導向被阻擋、被取消，各以不同的錯誤類別回傳，並附 HTTP 狀態碼；是否重試由 pipeline 決定。
- 請求與回應大小有管理員設定的上限，超過者以錯誤類別拒絕或中止（JSON 本文的上限在本項，二進位與上傳在 WI-53）。

**機密別名的解析（金鑰庫機制 WI-41 的資源側驗證）**
- 資源的金鑰別名經 Engine 正規化，格式限制與資源名稱的字元規則一致；`counter` 與 `file` 帶別名仍被拒絕。
- 資源回應含別名與狀態：`not_set`（未設定別名）、`found`、`missing`（設定了別名但金鑰庫沒有該別名或未組態金鑰庫）；永遠不含機密值。以真實金鑰庫驗證三種狀態與重載後的狀態變化。
- `GET /api/v1/secrets` 的引用者與 `POST /api/v1/secrets/reload` 回應中「有變更的別名被哪些資源引用」，以引用該別名的 `openai-compatible` 資源驗證。
- 檢查（WI-43 的機制）在別名缺失或未組態金鑰庫時失敗，類別為別名缺失，與狀態 `missing` 一致。
- 金鑰值被服務端回射到回應本文或錯誤時，不出現在 Engine log、run log、trace、API 回應與回傳給 pipeline 的錯誤（遮蔽機制端對端驗證）。

**世代、失效與機密**
- 修改資源設定或重載金鑰庫後，已持有者繼續使用取得當下的設定與機密，之後取得的 run 使用新的；舊世代的用戶端在其持有者全部結束後關閉。以真實金鑰庫（更換別名內容）與 Fake 服務端驗證兩個世代的請求分別帶新舊金鑰。
- 強制釋放或 run 終止時，進行中的請求被取消（Fake 服務端可觀察到連線中斷）；這同時是 WI-43 通用存取端「進行中的長時間操作被取消」的驗證。
- 金鑰值不出現在任何 API 回應、log、trace、錯誤訊息；回傳給 pipeline 的錯誤只含類別與狀態碼。

**檢查、使用量與可觀測**
- 檢查（WI-43）驗證服務可連與金鑰別名存在，失敗類別至少區分連線失敗、逾時、被拒絕、別名缺失。
- 資源的查詢回傳型別專屬的使用量（目前進行中的請求數），供 Console 顯示；寫入 08-api。
- Metric：進行中請求數、依狀態類別的請求數與延遲、等待請求額度的時間、生成時間（首位元組到完成）、依逾時種類的逾時次數、服務回報的 token 用量（回應含 `usage` 時）；標籤只用資源名稱、型別、端點條目識別名稱（固定目錄）、狀態類別與逾時種類這類固定集合，不放模型、提示或網址等使用者輸入。Run 的 trace 每次請求一個 span，含等待額度時間與首位元組時間。Log 與 trace 不記錄請求與回應本文。
- 使用此型別不需在 metadata 的 `network` 宣告其主機，也不使 `network` 變成不限制；`network` 宣告的主機與某個 `openai-compatible` 或 `jdbc-pool` 資源的主機相同時，上傳對該 pipeline 產生警告（不影響判定）。使用存取端的 pipeline 仍為 safe。

**開發入口與驗證**
- 本機實作以本機組態提供同契約（可連真實服務），與 Engine 以同一組行為測試驗證合併規則、鎖定、上限、錯誤類別。
- 自動測試以自製的 OpenAI 相容 HTTP 服務端（Fake，真實 HTTP，只存在於測試）驗證上述全部行為，包含重新導向、標頭注入與剝除、各錯誤類別、並行上限。
- 對 lemonade 與另一個 OpenAI 相容服務的實測是本機手動執行的腳本（不接進 Gradle `check`），回報執行結果；服務不可用時回報未執行，不得宣稱已驗證。實測項目：各條目的實際可用性（含重排序路徑）、容量 1 的整體並行是否為 1、run 取消後服務端是否停止生成（若不停止，回報真實並行可短暫高於計數）、長 context 或 thinking 下實際的首位元組時間。
- 08-api 在本項實作時同步更新（`type=openai-compatible` 的設定欄位、啟用端點與逾時欄位、`invalid_resource` 的新增 `problem`、使用量與「整體並行上限」欄位）；尚未實作的內容不預先列入；`ApiDocumentationTest` 通過。

- TLS 信任與 mTLS 用戶端憑證（`trustAliases`、`clientCertAlias`）不在本項範圍，由 [WI-52](WI-52-tls-trust-and-mtls.md) 加入；本項連線 `https` 時使用 JVM 預設信任，且不得提供任何關閉主機名稱或憑證驗證的途徑。

## 架構約束

- 容量是 run 級持有（ADR-007）；不引入請求級限流。
- 存取端契約預留串流與多部分、二進位（WI-47、WI-53），加入時不改變既有呼叫的行為與簽章語意（以回歸測試驗證）；逾時機制從本項起就是首位元組、閒置、選填總時間三種，不以單一整體逾時為基礎。
- 端點為版本化目錄加管理員啟用；不提供通用 HTTP 轉送，pipeline 不能自選主機、路徑、方法或標頭；邊界只傳 JDK 內建型別。
- 測試使用 Fake 服務端與真實 PostgreSQL（Testcontainers），不使用 Stub 或 Mock；嚴格 TDD；不新增 CI；完成程式碼變更時依專案規則先以 ktfmt 格式化。

## 實作結果（2026-10-07）

程式：契約在 `core`（`Accessors.openAiCompatible`、`OpenAiAccessor`、`OpenAiRequest`、`OpenAiTimeouts`、`OpenAiResponse`，`ResourceFailure` 新增的類別，`ResourceAccessException.status`）；Engine 與開發入口共用的主機側程式在 `accessors/src/main/kotlin/openai/`（端點目錄 `OpenAiEndpoints`、設定 `OpenAiSettings`、請求規則 `OpenAiRequestPlan`、傳送與逾時與取消 `OpenAiBinding`、額度 `RequestQuota`、檢查 `OpenAiProbe`、觀察介面 `OpenAiObserver`）；Engine 側在 `engine/src/main/kotlin/resource/`（`OpenAiCompatibleBehavior`、`OpenAiTelemetry`、`OpenAiUsage`，以及 `ResourceCatalog`、`ResourceAdmin`、`ResourceWarnings` 的擴充）；開發入口在 `devkit/src/main/kotlin/LocalResources.kt`。測試：Fake 服務端與其契約在 `accessors/src/testFixtures/kotlin/`（`FakeOpenAiServer`、`OpenAiServerContract`、`BlackHole`、`OpenAiBehaviorSuite`）；各測試在 `accessors/src/test/kotlin/openai/`、`engine/src/test/kotlin/resource/OpenAi*`、`devkit/src/test/kotlin/`。

實作時的決定與細節（超出條文之處，供審閱）：

- **設定欄位與單位**（08-api 有完整表）：`baseUrl`、`organization`、`project`、`headers`、`endpoints`、`timeouts`（`connectMs`、`firstByteMs`、`idleMs`、`totalMs`、`quotaWaitMs`，毫秒，以便測試用短值）、`requestsPerRun`、`maxRequestBytes`、`maxResponseBytes`、`defaults`、`allowedModels`、`lockedParameters`、`maxValues`。寫入資料庫的是正規化形式（每個有效值都明寫，`endpoints` 依目錄順序），所以新版 Engine 新增的條目與改變的預設值不影響既有資源。
- **回應大小**：本項只有「記憶體內回應上限」（預設 8 MiB，超過為 `RESPONSE_TOO_LARGE`）；決定 14 的「回應總上限 256 MiB」用於寫入檔案的二進位回應，隨 WI-53 加入，本項沒有放一個沒有用處的欄位。
- **預設參數的範圍**：只有模型與取樣、長度相關的參數（清單見 08-api）可以設為預設、鎖定或上限；`model` 套用到有模型的條目，取樣參數只套用到對話、補全與 responses 的建立，不會被加到嵌入請求。訊息、工具等本文完全由 pipeline 提供。鎖定的參數 pipeline 一提供就被拒絕（即使值相同）；允許的模型清單不空時，沒有模型（含預設）的請求也被拒絕。
- **`stream`**：`"stream": true`、非 `false` 的值與 `stream_options` 被拒絕為 `STREAM_NOT_SUPPORTED`；`"stream": false` 原樣送出。
- **逾時的縮短**：pipeline 要求的值比資源的長時，取資源的值（不是拒絕）；資源沒設總時間上限時，pipeline 可以設一個。非正數與不認得的項目是 `INVALID_ARGUMENT`。
- **重新導向**：根位址之內（同協定、主機、埠，路徑在根路徑之下，先正規化 `..`）的重新導向被跟隨，最多 5 次，307 與 308 保留方法與本文，303 與對 POST 的 301、302 改為 GET；其餘與沒有 `Location` 的重新導向為 `REDIRECT_BLOCKED`，且不對其他位址送出任何請求（以第二個真實服務端驗證）。固定使用 HTTP/1.1。
- **路徑參數的字元集**：`A-Za-z0-9._:-`、1 至 256 字元、不是 `.` 與 `..`。含 `/` 的模型識別碼（例如 `Qwen/Qwen3-8B`）因此不能經 `models.retrieve` 取得（`models.list` 不受影響）；需要時以條目為單位放寬，放寬前須重新證明不能改變路徑。
- **金鑰別名**：存成小寫的正規化形式，格式同資源名稱；`PATCH` 可以換別名，沒有「清除別名」的修改（要去掉金鑰須刪除後重建）。別名在金鑰庫缺失或不可用時，run 仍能取得資源，呼叫以 `SECRET_UNAVAILABLE` 失敗且不送出請求；檢查則為 `alias_missing`、`alias_invalid`。資源回應新增 `secretStatus`（`not_set`、`found`、`missing`、`invalid_secret`）、`concurrencyLimit`（容量乘以 `requestsPerRun`）與 `usage`（`inFlightRequests`）。
- **世代**：每個 run 的存取端各有自己的設定與金鑰快照與自己的 HTTP 用戶端（每種連線逾時一個），存取端失效後立即關閉；因此「舊世代的用戶端在其持有者全部結束後關閉」成立，不共用跨 run 的用戶端池，也不保留跨 run 的連線。
- **取消**：存取端失效時先呼叫綁定的 `abort`（取消進行中的請求、喚醒等待額度者、之後的呼叫立即失敗），再等進行中的操作結束；run 的 thread 被中斷時，等待額度與請求同樣立即中止（`CANCELLED`，且 thread 維持中斷狀態）。`ResourceBinding` 新增 `close`，失效時呼叫一次。
- **檢查**：啟用了 `models.list` 時讀它（非 2xx 為 `unexpected_response`），否則對根位址發一個 GET（任何不是 401、403、5xx 的回應都算通過）；用 `RUNLINE_RESOURCE_CHECK_TIMEOUT_SECONDS` 當連線、首位元組與閒置的上限，不用資源自己的逾時；新增的失敗類別：`connection_failed`、`rejected`、`server_error`、`unexpected_response`、`redirect_blocked`、`alias_missing`、`alias_invalid`。
- **`network` 警告**：新增警告種類 `network_host_has_resource`（`network` 的主機，不分大小寫，與某個 `openai-compatible` 資源的根位址主機相同）；查詢時重新計算，不影響判定。`jdbc-pool` 的主機比對隨 WI-48。
- **可觀測**：metric 名稱 `runline.resources.openai.*`（`requests.in_flight`、`requests`、`request.duration`、`quota_wait.duration`、`generation.duration`、`timeouts`、`tokens`），標籤只有 `resource`、`type`、`endpoint`、`outcome`、`kind`；每個請求一個 span（`runline.resource.openai.call`），帶 `openai.endpoint`、`openai.outcome`、`openai.quota_wait_ms`、`openai.first_byte_ms`；log 與 trace 不含位址、模型、提示與回應。
- **既有行為的修正**：`PATCH` 只改容量或啟用狀態時，原本對 `file` 資源也會以 `invalid_settings` 被拒絕（修改的檢查把「沒有給設定」當成「設定不合規」）；已改為只檢查有給的欄位，並以測試鎖定。`openai-compatible` 不再是 `unsupported_type`。
- **已知限度**：服務端若把金鑰回射到成功回應的本文，pipeline 會拿到它（ADR-019 接受的限度；Engine 不處理本文）；pipeline 的 thread 在 run 結束後才第一次使用的類別，因 class loader 已關閉可能得到 `NoClassDefFoundError` 而不是 `ENDED`（run 內先用過一次的類別不受影響）。串流（`stream`）、多部分上傳與二進位回應不在本項；`responses` 與 `batches` 的串流相關與檔案內容條目隨 WI-47、WI-53。
- **測試與環境**：Fake 服務端是在真實 socket 上說 HTTP/1.1 的小型實作（每條連線一個請求），契約測試（`OpenAiServerContract`）同時跑在 Fake 上，並在設定了 `RUNLINE_OPENAI_CONTRACT_URL` 時跑在真實服務上（未設定時回報為略過，不是通過）。連線逾時的測試需要一個封包被丟棄的位址（TEST-NET-1，由 `BlackHole` 先探測；網路立即回應時該測試回報為略過）；「連線佇列滿」的做法在 macOS 會被重設而不是丟棄，已放棄。Console 沒有新增錯誤碼（只有 `problem` 值與欄位），`consoleApiDocCheck` 不需要新的翻譯；Console 畫面屬 WI-49、WI-50。

**尚未驗證（需要真實的 lemonade 與另一個 OpenAI 相容服務，本機手動；服務不可用時回報未執行，不得宣稱已驗證）**：以 `RUNLINE_OPENAI_VERIFY_URL=<根位址> RUNLINE_OPENAI_VERIFY_MODEL=<模型> ./gradlew :accessors:verifyOpenAiService` 執行（不屬於 `check`，報告在 `accessors/build/reports/openai-verification.txt`；位址設為 `fake` 時量測 Fake，用來試跑這支腳本本身）：(1) 各條目的實際可用性，含 `rerank` 與 `reranking` 哪個有效；(2) 容量 1 的整體並行是否為 1（腳本量測服務端是否一次只服務一個請求；Fake 驗證的是 Engine 側的上限）；(3) run 取消（連線中斷）後服務端是否停止生成；(4) 長 context 或 thinking 下實際的首位元組時間；另外 `RealOpenAiServerContractTest`（`RUNLINE_OPENAI_CONTRACT_URL`）驗證 Fake 與真實服務的協定一致。

