# WI-53 `openai-compatible` 的多部分上傳與二進位回應

本文回答：圖像編輯、音訊轉錄與語音、檔案上傳與讀取內容等需要上傳檔案或回傳二進位的端點，如何在 Engine 中介下提供給 pipeline。狀態：2026-10-07 依使用者「需要所有端點」的需求納入，待使用者確認；已實作（2026-10-07，見「實作結果」；對真實服務的手動實測尚未執行）。相依：WI-46、WI-47。決策見 [ADR-019](../adr/ADR-019-typed-shared-resources.md) 第 4 點「端點目錄」「多部分上傳與二進位回應」與決定 13、14。

## 背景

WI-46 交付端點目錄機制與 JSON 端點；本項補上目錄中其餘條目：請求為多部分上傳（圖像編輯與變體、音訊轉錄與翻譯、檔案上傳）或回應為二進位（語音合成、檔案內容）。邊界只傳 JDK 內建型別；大檔不應整份進記憶體；檔案的來源與去處沿用 [ADR-009](../adr/ADR-009-file-scopes.md) 的範圍（pipeline 共享目錄、run 私有目錄）。

## 行為與驗收條件

- 目錄新增條目：圖像編輯與變體、音訊轉錄與翻譯、檔案上傳、語音合成（二進位輸出）、檔案內容（二進位輸出）；其餘群組仍預設不啟用。管理員啟用條目的規則與 WI-46 相同；目錄中尚未交付的條目啟用時被拒絕。
- 多部分請求：Engine 組裝請求本文；pipeline 只提供欄位（文字）與檔案部分。檔案部分的來源二選一：位元組（受請求大小上限約束）或共享目錄與私有目錄中的檔案（以範圍與相對路徑指定，Engine 側串流讀取，不整份載入記憶體）。指向範圍外、不存在或不可讀的路徑被拒絕並以錯誤類別回傳，規則與 ADR-009 一致。欄位名稱與各檔案部分的 Content-Type 由條目限定，pipeline 不能注入其他標頭或欄位。
- 二進位回應：小於資源記憶體上限者以位元組回傳；呼叫時可指定寫入共享目錄或私有目錄的檔案（同一範圍規則），回傳相對路徑與大小。超過資源設定的回應總上限時中止並以錯誤類別回傳，已寫入的部分檔案被移除。寫入的檔案計入既有的目錄用量上限。
- 請求大小上限、回應記憶體上限與回應總上限由管理員在資源設定，建議預設見 ADR-019 決定 14（待使用者確認）；pipeline 只能收緊。
- 逾時、額度、取消、重新導向與標頭規則沿用 WI-46、WI-47：大檔上傳與下載期間的取消會中止傳輸並歸還額度（Fake 服務端觀察到連線中斷）；傳輸的進度停滯由閒置逾時涵蓋，語音合成的串流輸出也走 WI-47 的逐塊拉取。
- 回應標頭剝除、回應與請求本文不寫入 log 與 trace、機密不外洩的規則不變；檔案名稱與位元組內容不進入 metric 標籤。
- 開發入口的本機實作提供同契約與同一組行為測試。
- 驗證：Fake 服務端（真實 HTTP，只存在於測試）接受多部分請求並可回傳二進位、慢速傳輸與中途斷線；涵蓋位元組與檔案兩種來源、範圍外路徑被拒、超限被拒、取消、寫入檔案去處與用量。對 lemonade 的手動腳本確認各條目實際可用性（圖像、音訊、檔案、批次）；服務不可用或端點不被支援時回報未執行或不支援，不得宣稱已驗證。
- 08-api 在本項實作時同步更新（新增的請求與回應欄位、`problem`）；尚未實作的內容不預先列入；`ApiDocumentationTest` 通過。

## 架構約束

- 邊界只傳 JDK 內建型別；pipeline 不能自選主機、路徑、方法或標頭；容量維持 run 級持有（ADR-007）。
- 檔案來源與去處只限 ADR-009 的兩種範圍。
- 測試使用 Fake 服務端與真實檔案系統，不使用 Stub 或 Mock；嚴格 TDD；不新增 CI；完成程式碼變更時依專案規則先以 ktfmt 格式化。

## 實作結果（2026-10-07）

程式：契約在 `core`（`OpenAiAccessor` 新增 `download`、`downloadTo`、`streamBytes`；`OpenAiRequest` 新增 `fields`、`files`、`sizes`；`OpenAiUpload`、`OpenAiFile`、`OpenAiSizes`、`OpenAiBinaryResponse`、`OpenAiStoredResponse`、`OpenAiByteStream`；`ResourceFailure.SCOPE_FULL`；範圍與模式的 run 側檢查在 `HostAccessors`）；Engine 與開發入口共用的主機側在 `accessors/`（`openai/UploadForm`、`openai/OpenAiEndpoints` 的欄位與檔案部分、`openai/OpenAiRequestPlan`、`openai/OpenAiBinding` 的 `openai.download` 與位元組串流、`ScopeDirectory`、`ScopeArguments`、`BoundResources.workspaceReady`）；Runner 在 run 的目錄備妥後以 `ResourceHost.workspaceReady` 告知目錄位置、用量上限與宣告的範圍可寫性（`RunExecution`、`RunEntry`）。測試：Fake 服務端新增多部分、檔案、批次、語音與音訊路由與慢速接收（`FakeOpenAiServer`、`FakePart`），契約測試（`OpenAiServerContract`）新增八項；`OpenAiBindingMultipartTest`、`OpenAiBindingDownloadTest`、`OpenAiBindingByteStreamTest`、`OpenAiScopeRootsTest`（accessors）、`OpenAiFilesAccessorTest`（core）、共用行為套件的三項（Engine 與開發入口各跑一次）、`OpenAiResourceRunTest`、`OpenAiKeyLeakTest`、`OpenAiResourceApiTest`（engine）。

實作時的決定（超出條文之處，供審閱）：

- **新設定** `maxDownloadBytes`（寫入檔案的回應總上限，預設 256 MiB，上限 16 GiB，`invalid_limit`）；`maxResponseBytes` 仍是記憶體內回應上限。pipeline 每次呼叫以 `sizes` 只能收緊三種上限。
- **每個條目的欄位與檔案部分**在目錄中固定（08-api 有表）；檔案部分的 Content-Type 一律 `application/octet-stream`；檔名只接受 `A-Za-z0-9._ -`（1 至 128 字元，不是 `.` 與 `..`），不合者 `INVALID_ARGUMENT`，不做編碼，省略時用檔案名稱；分隔字串每個請求隨機產生 128 位元，不檢查內容是否含它（pipeline 在分隔字串產生前就給完了內容，無法預測）。
- **目錄位置不來自呼叫**：呼叫只帶範圍名稱與相對路徑；目錄位置、用量上限與宣告的範圍可寫性由 Runner 告知（`ResourceHost.workspaceReady`），`BoundResources` 在每次呼叫覆蓋呼叫帶的 `root` 與 `maxBytes`，並依宣告拒絕未宣告的範圍（讀）與非可寫的範圍（寫）為 `PATH_REJECTED`，未知範圍名稱為 `INVALID_ARGUMENT`。run 側（`HostAccessors`）的同一檢查只是方便，不是唯一的強制。錄製的 run 視兩個範圍皆可寫。過去提交的版本中，若呼叫能自帶 `root`（只有繞過存取端直接呼叫連結的程式碼做得到），主機側會信任它；測試以 `/etc` 重現後已改為覆蓋。
- **下載寫入**：先寫同目錄的隨機名暫存檔（`.runline-<uuid>.part`），完成才原子取代目標；目標路徑在送出請求之前以與 `file` 資源相同的 `Confinement` 檢查；用量上限以「目錄現有用量，扣掉要被取代的檔案」計；暫存檔的用量也計入。
- **上傳的閒置逾時**：以服務端實際收下的進度計時（[WI-60](WI-60-upload-idle-progress.md) 改寫）。表單被用戶端取走的每次讀取重新計時，而 Engine 與開發入口啟動時把 JDK HTTP 用戶端的送出緩衝上限設為 256 KiB（`UploadSendBuffer`，JVM 全域），使「用戶端取走」緊跟「服務端收下」；服務端持續讀取時不逾時，停止讀取超過 `idleMs`（加上填滿 Engine 送出緩衝與服務端接收緩衝的時間）為 `IDLE_TIMEOUT`。首位元組逾時仍從送出請求起算，含上傳。原本的做法未限制送出緩衝，作業系統自動調大（開發容器達 4 MiB）時，持續有進度的慢速上傳會被誤判為閒置（WI-57 第 2 項）。
- **二進位回應中的金鑰**：三條路徑（記憶體、檔案、位元組串流）逐段掃描金鑰的 UTF-8 位元組，保留 `金鑰長度 - 1` 位元組的接續視窗；找到時整份拒絕為 `SECRET_IN_RESPONSE`（不改寫，因為改寫會損壞音訊與檔案），串流把每段結尾可能是金鑰開頭的位元組留到下一段才交出，檔案寫入以暫存檔、拒絕時一併刪除。其他帶憑證的值：`organization`、`project` 與額外標頭依設計不是機密（額外標頭名稱含 auth、key、token、secret、cookie 者在設定時被拒絕），回應標頭的金鑰與授權相關標頭沿用既有的遮蔽與剝除。\n- **語音串流**：`streamBytes` 走與事件串流相同的串流操作（`openai.stream.open` 帶 `binary`），回傳每次一塊位元組；不加 `stream` 欄位，不解析事件；金鑰以上述的掃描處理。
- **未做**：`audio.transcriptions` 的 `stream` 與 `timestamp_granularities[]` 欄位、`images.edits` 的多張 `image[]`；JSON 本文中的金鑰不遮蔽（WI-46 的已知限度）。Console 沒有新增錯誤碼（只有 `problem` 值不變、設定欄位與失敗類別），`consoleApiDocCheck` 不需要新的翻譯。

**尚未驗證（需要真實的 lemonade 與另一個 OpenAI 相容服務，本機手動；服務不可用時回報未執行，不得宣稱已驗證）**：`RUNLINE_OPENAI_VERIFY_URL=<根位址> RUNLINE_OPENAI_VERIFY_MODEL=<模型> ./gradlew :accessors:verifyOpenAiService` 新增一項：各上傳、二進位與批次條目（圖像編輯與變體、音訊轉錄與翻譯與語音、檔案建立與列出與讀取與內容與刪除、批次）的實際可用性，只刪除腳本自己建立的檔案（位址設為 `fake` 時量測 Fake，已用來試跑）。`RealOpenAiServerContractTest` 新增的契約項在服務沒有該端點時回報為略過。真實服務的行為（各端點是否支援、大檔與取消後是否停止）全部未驗證。
