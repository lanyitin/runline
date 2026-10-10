# WI-1018 落實參數修訂：`condition` 必填與 `readerModel`

本文回答：把 WI-1002 與 WI-1007 的參數修訂落實到 `runline-digest-rss` 要達成什麼行為。狀態：已核可（2026-10-09）。工作儲存庫：`runline-digest-rss`。

背景：實作時 `condition` 有預設 `none`，請求的模型 id 來自 Engine 環境變數 `DIGESTRSS_READER_MODEL`。修訂後 `condition` 必填，模型 id 改為 pipeline 參數 `readerModel`，使漏填條件不會悄悄對所有未讀文章標記與下載，且模型設定屬 pipeline 層級而不需要改動 Engine 的環境。

## 驗收條件

1. `condition` 無預設且必填：未提供、空、格式不合時，run 以呼叫錯誤失敗並列出問題，不開始任何階段；`none` 仍是合法的明確值，行為不變（不呼叫模型、全部保留）。
2. 新參數 `readerModel`：`condition` 不是 `none` 時必填；非空、不含控制字元、最長 100 字元；`condition` 為 `none` 時不要求且不使用。不合法或缺漏同樣以呼叫錯誤失敗。
3. 讀者與摘要的請求模型 id 取自 `readerModel`；回應模型比對規則不變。環境變數 `DIGESTRSS_READER_MODEL` 不再被讀取，程式、測試、`build.gradle.kts`、README 與本機設定中都不再出現。
4. 參數宣告、README 的參數表、錯誤訊息與 `PipelineDeclarationTest` 同步更新；與 WI-1002 的參數表逐項一致。
5. 所有因此改變的既有測試以新預期更新並說明；不放寬斷言，不刪測試。`./gradlew ktfmtCheck test` 全綠，`ThinJarTest` 維持通過。
6. 在打包的真實 Engine 上傳後，以 trigger 參數 `condition` 與 `readerModel` 啟動一次 run（Fake 服務），並驗證缺 `condition` 時 run 失敗為呼叫錯誤。

## 架構約束

- 不改變讀者、下載、標記等其他行為。
- 不修改 runline 儲存庫與 docs/stable；需要時停下來回報。
