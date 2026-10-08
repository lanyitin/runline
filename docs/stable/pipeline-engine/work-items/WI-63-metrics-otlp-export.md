# WI-63 指標經 OTLP 匯出，OpenTelemetry 由 Engine 關閉

本文回答：指標如何與 trace、log 一樣匯出，OpenTelemetry 的生命週期如何由 Engine 管理，以及如何驗收。狀態：已核可（2026-10-08）。相依：WI-61。決策見 [07](../07-nfr-risks.md)「可觀測性」。

## 背景

Engine 啟動時強制關閉 OpenTelemetry 的指標匯出，因此 07 與 08-api 記載的 metric 實際上沒有送往任何地方；WI-61 移除定期寫 log 之後，指標完全無法觀察。另外，OpenTelemetry 每次啟動都會註冊一個 JVM 結束時才執行的關閉掛鉤，同一個 JVM 多次啟動時會累積（WI-61 實作時的發現）。

## 行為與驗收條件

- 部署環境以標準 OpenTelemetry 環境變數設定 OTLP 目的地時，指標與 trace、log 一起送出。驗證方式：
  - 以真實的 OpenTelemetry Collector 容器（Testcontainers）接收。
  - 至少確認 07 列出的一個 run 指標與一個資源指標，以記載的名稱與標籤送達。
- Engine 不再強制關閉任何一種訊號的匯出，也不新增 Engine 專屬的開關；設定方式與 trace、log 相同，04 的部署說明同步更新。
- 沒有收集器或收集器無法連線時，Engine 的行為不受影響，log 不會因匯出失敗而持續大量輸出。驗證方式：閒置數秒，log 行數有上限。
- OpenTelemetry 由 Engine 在停止時關閉，不註冊 JVM 結束時的關閉掛鉤。驗證方式：同一個 JVM 內連續啟動並停止 Engine 至少 20 次，JVM 結束時的關閉掛鉤數量不增加；WI-61 的背景執行緒測試仍然通過。
- Engine 停止時，已產生而尚未送出的訊號，在關閉時盡力送出，所需時間在既有的關閉寬限時間內。

## 架構約束

- 不改變 metric 與 span 的名稱、標籤與結構。
- 不恢復定期寫 log，也不新增 Engine 專屬的匯出開關。
- 測試不使用 Stub 或 Mock；收集端用真實容器。

## 實作結果（2026-10-08）

**指標不再被強制關閉。** `getOpenTelemetry`（engine，`OpenTelemetry.kt`）移除了 `System.setProperty("otel.metrics.exporter", "none")`；三種訊號都只由標準 OpenTelemetry 設定（環境變數或同名系統屬性）決定，沒有新增 Engine 的開關。metric 與 span 的名稱、標籤與結構未改變。

**OpenTelemetry 由 Engine 關閉，不註冊關閉掛鉤。** SDK 以 `disableShutdownHook()` 建立。Engine 停止時由 DI 的 `cleanup` 呼叫 `shutdownWithin(關閉寬限時間)`：SDK 的 `shutdown()` 本身是同步阻塞的（指標讀取器先等它的排程執行緒最多 5 秒，再做最後一次匯出；收集器不回應時再等匯出逾時），因此在專屬的 daemon 執行緒 `opentelemetry-shutdown` 上執行，Engine 最多等候關閉寬限時間後繼續停止，該執行緒在 SDK 自行放棄後結束。OpenTelemetry 宣告在第二位，DI 以相反順序關閉，所以它幾乎最後關閉，其餘元件停止時記下的訊號一併送出。Ktor DI 之後仍會對每個 `AutoCloseable` 依賴呼叫 `close()`，這第二次呼叫立即返回，SDK 會在標準錯誤寫一行 `INFO: Multiple shutdown calls`（每次停止一行；要消除需以非 SDK 型別提供 `OpenTelemetry`，會改變既有測試 `TelemetryServiceNameTest` 對 SDK 型別的轉型，未做）。

**發現：協定與埠。** Java SDK autoconfigure 的預設協定是 `grpc`（收集器的 4317 埠），而 `deploy/` 的範例把 `OTEL_EXPORTER_OTLP_ENDPOINT` 指向 4318（OTLP/HTTP）。照範例設定時 trace 也送不到（gRPC 打到 HTTP 埠，連線被關閉）。範例檔與 04、README 已補上 `OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf` 的說明；程式未改預設值（改預設等於 Engine 自己的設定規則）。

**沒有收集器時。** Engine 照常服務。SDK 經 `java.util.logging` 在標準錯誤記錄匯出失敗：指標讀取器每次失敗的匯出一行 `WARNING: Exporter failed`（不節流，預設每 60 秒一次，即約每分鐘一行），匯出器自身的錯誤經 SDK 的節流（每分鐘至多 5 行，之後每小時 1 行）。停止時尚未送出的訊號會被重試（連線被拒約 5 至 8 秒），最多等候關閉寬限時間。沒有收集器的環境仍建議三個匯出器都設為 `none`（04、README）。

**測試（真實元件，無 Stub 或 Mock）。** 測試支援新增 `OtelCollector`（Testcontainers，`mirror.gcr.io/otel/opentelemetry-collector:0.115.0`，OTLP/HTTP 接收，debug 匯出器以 detailed 輸出，自容器輸出解析收到的 metric 名稱與資料點屬性）與 `withSystemProperties`、`unusedPort`。測試任務另加 `otel.metrics.exporter=none`（理由同既有的 traces、logs）與 `--add-opens=java.base/java.lang=ALL-UNNAMED`（讀取 JVM 的關閉掛鉤清單）。

- `MetricsExportTest`「metrics reach the collector…」：以標準設定（`otlp`、`http/protobuf`、收集器端點）啟動 Engine，定義並檢查 `counter` 資源後停止。匯出間隔維持 SDK 預設（60 秒），因此收到的資料都是停止時送出的。收集器收到 `runline.runs.active`（07：進行中 run 數）與 `runline.resources.checks{resource=exported,type=counter}`（07：實體檢查結果，帶名稱與型別標籤）；停止耗時小於測試的寬限時間（5 秒）。Red：舊程式在 60 秒內一筆 metric 都沒有送達（span 有送達）。
- `MetricsExportTest`「with a collector that never answers…」：收集器端以 `StuckServer`（收下請求、不回應）代替，寬限 2 秒。Red：停止耗時 11,180 ms；只加 `join(寬限)` 時仍為 7,030 ms（SDK 的 `shutdown()` 同步阻塞），改為專屬執行緒後通過（3 秒內）。
- `MetricsExportTest`「with no collector listening…」：三種訊號經 OTLP 送往無人監聽的埠，指標每秒匯出一次（SDK 預設的重試），閒置 12 秒。之後就緒探測回 200，Engine 的 log 加上 SDK 的 log 合計不超過 10 行，且確實有匯出失敗的記錄。屬於守護性測試：舊程式下指標不匯出，因此沒有 Red 階段。
- `MetricsOutputTest`「…leaves no shutdown hook of the JVM behind」：同一個 JVM 內啟動並停止 Engine 21 次（第 1 次為暖身），之後 JVM 的關閉掛鉤集合與暖身後相同。Red：20 次啟停後掛鉤集合變大（autoconfigure 每次建立 SDK 都註冊一個掛鉤）。
- WI-61 的「…leaves no background threads behind」改為連指標也經 OTLP 匯出，仍然通過。這兩個 20 次啟停的測試設定 `otel.java.exporter.otlp.retry.disabled=true`（標準的 Java SDK 設定），被拒絕的匯出不重試，否則每次停止都要等滿寬限時間（每個測試約 110 秒，加上後約 3 至 9 秒）。

**已知的保留點。** 停止的最壞耗時：等候進行中的請求（至多寬限時間）、停止 run（至多寬限時間，WI-62 另有釋放等待上限），再加上關閉 OpenTelemetry（至多寬限時間）。收集器連不上的閒置 Engine，停止會多花最多一個寬限時間，仍在 04 建議的平台停止等待（寬限時間加 15 秒）之內；忙碌時的最壞情況則超過，這與 WI-62 記錄的問題相同。是否另設較短的上限需架構決定。`.devcontainer` 沒有加入收集器（Engine 沒有收集器也能運作）。

**驗證。** `./gradlew cleanTest :engine:cleanPackagedTest check --continue` 執行 1 次，全部通過（18 分 46 秒）：accessors 352（21 個跳過，同 WI-57）、analyzer 133、core 88、devkit 182、runner 58、engine 1128（1 個跳過，同 WI-57；含本項新增的 4 個測試）、packagedTest 63；Console 測試、型別檢查、API 文件檢查與 ktfmt 檢查通過。這次沒有出現 `OpenAiBindingStreamTest`「a stream that keeps producing…」的偶發失敗。
