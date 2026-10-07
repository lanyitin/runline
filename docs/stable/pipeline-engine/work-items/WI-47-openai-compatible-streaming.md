# WI-47 `openai-compatible` 的串流回應

本文回答：pipeline 如何逐塊消費串流回應，以及串流與額度、逾時、取消、失效的關係。狀態：已核可（2026-10-06）；串流列為必要與逾時語意為 2026-10-07 修訂，待使用者確認；已實作（2026-10-07，見「實作結果」；對真實服務的手動實測尚未執行）。相依：WI-46。決策見 [ADR-019](../adr/ADR-019-typed-shared-resources.md) 第 4 點「串流回應」與決定 8、14。

## 背景

Pipeline 是循序阻塞的平台 thread（[ADR-008](../adr/ADR-008-execution-model-and-jdk.md)），以逐塊拉取消費串流最自然，且不需要讓 Engine 內部的非同步型別穿過邊界。目標服務的單次生成時間不固定且可能很長（取決於 context 大小與是否 thinking）：非串流呼叫在生成結束前收不到任何位元組，只能靠很長的首位元組逾時；串流使「閒置逾時」取代對整體時間的猜測，並讓進度可見。因此串流是必要項，緊接 WI-46 交付，兩者之間不插入其他型別項目；WI-47 驗收前，此型別不視為可用於長時間生成的正式環境。

## 行為與驗收條件

- 存取端提供串流呼叫（適用目錄中支援串流的條目，如對話、補全、responses 與語音合成的串流輸出）：pipeline 逐塊拉取，每一塊只含 JDK 內建型別；串流有明確的結束訊號；pipeline 可隨時關閉串流。
- 串流佔用每 run 的請求額度直到串流結束、被 pipeline 關閉或 run 終止；額度在這些時機都確實歸還（以 Fake 服務端觀察並行數）。容量 1 加每 run 上限 1 時，串流進行中服務端觀察到的同時請求數為 1；同一 run 在串流未結束時再發請求會等待額度，受額度等待逾時約束並得到專屬錯誤類別，不會永久卡住（驗證，並在文件說明作者須先結束或關閉串流）。
- 逾時語意（沿用 WI-46 的五種逾時）：首位元組逾時涵蓋「送出請求到第一塊」（包含長 context 的 prefill）；閒置逾時涵蓋「兩塊之間」，上限由資源設定，pipeline 只能縮短；總時間上限為選填，預設不設。Fake 服務端不再送出資料時，pipeline 在閒置逾時後得到閒置逾時類別的錯誤；持續緩慢送出的串流不因整體時間而逾時，長時間（遠超過任何常見整體逾時）的串流可完成；設定了總時間上限時才因整體時間中止，並得到總時間逾時類別。
- Run 取消、強制釋放或終止時串流被關閉（Fake 服務端觀察到連線中斷），額度歸還，拉取中的 pipeline 以對應的錯誤類別返回。
- 串流中途斷線以錯誤類別回報，已收到的塊保留給 pipeline；Engine 不自動重試。
- 服務於串流結尾回報用量時，計入 token 用量 metric；沒有回報則不計入，不報錯。
- 可觀測性：串流的首塊時間（首位元組）、塊間最長間隔與總生成時間、等待額度時間記入 metric 與 run 的 trace span；不記錄串流內容。
- 資源設定的覆寫規則、鎖定、上限與標頭注入、回應標頭剝除、重新導向限制同樣適用於串流請求。
- 回歸：WI-46 的所有行為與測試不變，非串流呼叫的行為與簽章語意不受影響。
- 開發入口的本機實作提供同契約與同一組行為測試。
- 對 lemonade 的手動腳本（本機，不接進 Gradle `check`；服務不可用時回報未執行）：長 context 或 thinking 下的串流實際首塊時間與塊間間隔，作為調整預設逾時的依據。
- 本項不新增 HTTP 路由；若資源查詢的使用量欄位需要區分串流中的請求，同步更新 08-api。

## 架構約束

- 串流不改變容量語意（run 級持有，ADR-007）；回傳的每一塊與錯誤只含 JDK 內建型別。
- Log 與 trace 不記錄串流內容。
- Fake 服務端須能分塊送出、延遲、中途斷線與永不結束；測試使用真實 HTTP 與 Fake，不使用 Stub 或 Mock；嚴格 TDD；不新增 CI；完成程式碼變更時依專案規則先以 ktfmt 格式化。

## 實作結果（2026-10-07）

程式：契約在 `core`（`OpenAiAccessor.stream(request)` 回傳 `OpenAiStream`：`status`、`headers`、`next()`、`close()`）；主機側在 `accessors/src/main/kotlin/openai/`（`OpenAiBinding` 新增 `openai.stream.open`、`openai.stream.next`、`openai.stream.close` 三個操作，`ServerSentEvents` 解析事件，`OpenAiRequestPlan` 加上 `streaming`，`OpenAiObserver` 新增 `streamStarted`/`streamFinished` 與 `OpenAiOutcome.maxChunkGapMillis`）；`BoundResources` 的失效順序修正；Engine 側 `OpenAiTelemetry` 與 `EngineResourceObserver`。測試：Fake 服務端新增 `beginChunked`、`chunk`、`event`、`endChunked` 與對 `stream: true` 的預設串流路由（`chunkDelayMillis`），契約測試新增兩項（SSE 形狀、`include_usage`）；`OpenAiBindingStreamTest`、`ServerSentEventsTest`、`OpenAiAccessorTest`（core）、共用行為套件 `OpenAiBehaviorSuite`（Engine 與開發入口各跑一次）、`OpenAiResourceRunTest`、`OpenAiResourceObservabilityTest`、`OpenAiKeyLeakTest`、`BoundResourcesTest`。

實作時的決定（超出條文之處，供審閱）：

- **事件與結束訊號**：一塊是一個伺服器推送事件的 `data` 文字（多行 `data` 以換行相接；註解、`event:`、`id:`、`retry:` 被忽略；`event:` 的名稱不傳給 pipeline，`responses` 的事件類型在其 JSON 的 `type` 欄位）。服務送出 `[DONE]` 或乾淨地關閉連線時 `next()` 回傳 `null`；結尾沒有完整的事件被丟棄；只認 `\n` 與 `\r\n` 換行。
- **`stream` 與 `stream_options`**：`stream()` 的本文帶 `stream`（無論值）為 `INVALID_ARGUMENT`；`stream_options` 原樣送出，這是 pipeline 取得用量的方式（Engine 不替它加，因為不是每個服務都接受）。對不能串流的條目呼叫 `stream()` 為 `STREAM_NOT_SUPPORTED`；`call` 的行為不變。串流請求的 `Accept` 為 `text/event-stream`。
- **逾時**：首位元組逾時從送出請求起算，涵蓋到回應本文的第一個位元組（服務若先送標頭、prefill 後才送第一個事件，也包含在內）；之後每一次讀取受閒置逾時約束；總時間上限涵蓋整個串流。逾時發生時串流立即結束（連線關閉、額度歸還），即使 pipeline 沒有在拉取；之後的拉取得到同一個類別。
- **額度與結束**：串流在讀完、被關閉、失敗、逾時、失效或 run 終止時結束，額度恰好歸還一次（`Call.release` 冪等）；結束後 `next()` 回傳 `null`（或重複同一個失敗類別），重複 `close()` 無作用，`close()` 在 run 已終止時也不失敗。已結束的串流編號在主機側保留到 run 結束（僅一個小物件）。
- **事件大小**：單一事件大於 `maxResponseBytes` 為 `RESPONSE_TOO_LARGE`；串流總量沒有上限（它是串流）。
- **金鑰**：事件文字中出現金鑰處以 `***` 取代（比 `call` 的本文處理更嚴，`call` 維持 WI-46 的限度；串流的事件是增量資料，遮蔽的成本低）。
- **可觀測**：新增 metric `runline.resources.openai.stream.first_chunk.duration` 與 `runline.resources.openai.stream.max_gap.duration`；生成時間與 token 沿用；進行中請求數在串流結束才減少。每個串流一個 span `runline.resource.openai.stream`（屬於 run 的 trace，在開啟的操作內建立，結束時才結束），拉取不產生 span。塊間最長間隔是在 `next()` 內等待事件的時間（不含 pipeline 自己處理的時間，也包含第一個事件）。
- **失效順序（審查項目）**：`BoundResources.invalidate` 原本先 `abort()` 再取得寫鎖並設定原因，兩者之間到達的呼叫會通過檢查、進到已中止的綁定而得到 `CANCELLED`（不是原因的類別，造成 WI-46 的一個測試偶發失敗）；現在先以 CAS 認領原因再 `abort()`。另外：串流開啟完成時若同時被中止則不交出串流；串流的結束與原因以單一原子狀態設定，避免拉取把「正在被取消」誤認為正常結束；計時器停止的串流立即結束並歸還額度。
- **未做**：`audio.speech` 的串流（二進位事件）隨 WI-53，因該條目本版不能啟用；使用量欄位沒有區分串流中的請求（`inFlightRequests` 含串流）；Console 無新增錯誤碼（只有 `problem` 值與欄位），`consoleApiDocCheck` 不需要新的翻譯。

**尚未驗證（需要真實的 lemonade 與另一個 OpenAI 相容服務，本機手動；服務不可用時回報未執行，不得宣稱已驗證）**：以 `RUNLINE_OPENAI_VERIFY_URL=<根位址> RUNLINE_OPENAI_VERIFY_MODEL=<模型> ./gradlew :accessors:verifyOpenAiService` 執行，新增兩項（位址設為 `fake` 時量測 Fake，已用來試跑這兩項）：(1) 長 context 或 thinking 下串流的首個事件時間與事件間最長間隔（據此調整 `firstByteMs` 與 `idleMs` 的預設；服務若不串流輸出 thinking 內容，間隔會很長）；(2) 關閉串流後服務端是否停止生成。`RealOpenAiServerContractTest`（`RUNLINE_OPENAI_CONTRACT_URL`）也涵蓋串流的契約，未對真實服務跑過。
