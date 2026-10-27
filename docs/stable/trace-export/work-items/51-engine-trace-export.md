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
