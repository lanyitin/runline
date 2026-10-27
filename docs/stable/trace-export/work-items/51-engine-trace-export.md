# #51 Engine 的 trace-export 型別、匯出器與緩衝

本文回答：Engine 側要達成什麼行為、如何驗收。狀態：已核可（2026-10-27），待派工。Issue：#51。決策見 [ADR-46](../adr/ADR-46-trace-export-resource.md)。相依：#50。

## 行為

1. 資源型別集合新增 `trace-export`：設定含 OTLP 匯出端點、服務名稱、一個金鑰庫別名（整串認證值）。管理員以既有資源管理、金鑰庫別名與綁定流程建立與維護。
2. 每個資源有獨立的匯出器與有界佇列；與 Engine 自身遙測的設定互不影響。
3. 存取端接收 span 後立即返回；Engine 依 run 指派追蹤識別碼，同一 run 的 span 屬於同一條 trace，父子關係依 pipeline 給的本地識別碼對應。
4. 佇列滿時丟棄新提交的 span 並計數；匯出失敗、逾時或別名缺失時丟棄該批、計數、記錄限速的警告；不阻塞也不使 run 失敗。
5. 屬性數量與值長度有上限，超過則截斷並計數。上限、佇列大小、沖出等待時間的預設值依 OpenTelemetry 批次處理器的量級起訂，可由部署組態調整。
6. run 結束時有界等待佇列沖出，逾時即放棄。
7. 金鑰庫重載後，新的匯出請求使用新金鑰；不需重新部署 pipeline 或重啟 run。
8. 資源未啟用或 pipeline 未綁定時，不匯出。
9. 容量為接近無限的固定值，取得容量永遠立即成功；容量不可由管理員編輯。
10. 丟棄、匯出成功與失敗、截斷的計數成為可觀測指標。
11. 08-api 與型別目錄資料（[ADR-021](../../pipeline-engine/adr/ADR-021-resource-type-catalog-endpoint.md)）同步更新。

## 驗收條件

- 綁定資源的 pipeline 送出的 span 到達測試用的真實 OTLP 接收端（簡易真實實作），服務名稱、父子關係與同一 run 同一條 trace 正確。
- 金鑰值不出現在 pipeline、run 紀錄、log、API 回應、metric、錯誤訊息與 Engine 的 trace；屬性內容不出現在 Engine 的 log、metric 與錯誤訊息。
- 接收端不回應（連線掛住）、回錯誤或不可達時，run 的延遲與成敗不受影響；有丟棄計數與警告。
- 大量提交使佇列滿時，run 不被阻塞；丟棄被計數。
- 更新金鑰庫並重載後，下一批匯出使用新金鑰，過程不需重新部署。
- 別名缺失或金鑰庫未設定時，資源的檢查回報對應類別，run 不失敗。
- 未綁定與未啟用的行為與沒有 tracing 時相同。
- 整串認證值為可列印 ASCII 的限制以真實金鑰庫驗證。
- 兩個資源指向不同端點與金鑰時，彼此與 Engine 自身遙測互不干擾。

## 架構約束

- OpenTelemetry 的 SDK 與匯出器只在 Engine 的 class loader；run 不載入 OpenTelemetry 類別。
- 不共用 Engine 自身遙測的全域設定。
- 機密只在金鑰庫，不進入資料庫、API、metadata、log、trace、錯誤與邊界（[ADR-019](../../pipeline-engine/adr/ADR-019-typed-shared-resources.md)）；型別集合維持封閉。
- 端點的 TLS 信任沿用 ADR-019 決策 12 的機制。
- 測試的外部系統用真實或簡易真實實作，不用 Stub 或 Mock。

## 實作記錄

- 型別 `trace-export` 在 Engine 的 `ResourceType`；資料庫的型別檢查約束由 `V15__trace_export_resource_type.sql` 加入第五個成員。設定為 `endpoint`（OTLP/HTTP protobuf traces 訊號的完整位址，Engine 不附加路徑）、`serviceName`，另可有 `trustAliases`、`clientCertAlias`（`https` 時，沿用 ADR-019 決策 12）；`secretAlias` 必填，指向整串認證值，原樣作為 `Authorization` 標頭。規則與預設見 [08](../../pipeline-engine/08-api.md) 的「`trace-export` 型別」。
- 匯出器在 `engine` 模組的 `traceexport` 套件（`accessors` 與 `runner` 沒有 OpenTelemetry 相依）：每個資源（同一份設定與憑證）一個 `TraceExporter`，由持有它的 run 共用、最後一個放開時結束；一條執行緒、一個有界佇列、OpenTelemetry 的 `OtlpHttpSpanExporter`（不重試、meter 為 noop、不讀環境與全域設定）。span 由 Engine 組成 OpenTelemetry 的 `SpanData`，追蹤識別碼每個 run（每個存取端綁定）隨機產生，span 識別碼由追蹤識別碼與 pipeline 的本地識別碼雜湊導出，所以父子關係不需要順序。
- 金鑰在每個批次送出時才從金鑰庫讀取，重載後下一個批次用新金鑰；別名缺失、不可用或金鑰庫未設定時該批以 `key_unavailable` 丟棄。端點的 TLS 用 `ResourceTls` 的安全上下文，再包一層 `HostCheckingContext`（accessors）讓 OkHttp 的連線也要求 JDK 檢查主機名稱：資源的信任管理器在連線沒有要求驗證主機時一律拒絕，OkHttp 自己在握手後才驗，所以不包就連不上；包了之後主機名稱驗證仍然不可關閉。
- 選用的角色：宣告為 `trace-export` 的角色沒有綁定到存在的資源、或資源停用時，`ResolvedRole.isLeftOut` 使它不成為問題（不拒絕 run、不警告、不取得容量、沒有存取端）。這是對 ADR-025「宣告的角色必須解析到可用資源」的例外，只適用此型別，依據是 ADR-46 決策 5 與驗收條件「未綁定與未啟用的行為與沒有 tracing 時相同」。
- 容量固定為 `1000000`（`TRACE_EXPORT_CAPACITY`）。建立時 `capacity` 照常必填（缺少為 400，維持既有契約），但須等於這個值，修改時帶不同的值被拒絕（`capacity`）；`GET /api/v1/resource-types` 的 `trace-export` 項有 `fixedCapacity`，供 Console（#52）使用。
- 預設數值：佇列 2048、批次 512、批次間隔 5 秒、匯出請求上限 10 秒、run 結束的沖出等待 5 秒、屬性數 128、文字長度 8192 個字元；七個環境變數 `RUNLINE_TRACE_EXPORT_*` 可調。run 結束的沖出等待在釋放資源的執行緒上進行（有上限）；接收端不回應時，一個 run 的結束最多多等這個時間，run 的狀態在釋放之前已經寫入，所以成敗與持續時間不受影響。
- 屬性超過數量上限時，保留依鍵排序的前面幾個；值的型別不合的屬性在 host 側再次捨棄並計入 `kind=invalid`。
- 型別目錄、08-api、05-ipc、04-deployment、README 的設定表、06、07 已同步；Engine 自身的 trace 不為 `trace.submit` 產生 span。
- 整串認證值為可列印 ASCII 的限制已用真實金鑰庫（`keytool` 寫入）驗證：0x20 至 0x7e 的每個字元原樣送達接收端；含非 ASCII 的機密是 `invalid_secret`，檢查回報 `alias_invalid`，匯出以 `key_unavailable` 丟棄。
