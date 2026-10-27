# #43 run 類別載入器提供 OpenTelemetry

本文回答：要達成什麼行為、如何驗收。狀態：已作廢（2026-10-27），被 #46 取代，未實作，不得派工。Issue：#43。決策見 [ADR-42](../adr/ADR-42-run-provides-opentelemetry.md)，公告見 [runner-libraries](../runner-libraries.md)。相依：無。完成後需通知 runline-transcribe（其 WI-18 等待本項部署）。

## 行為

1. 每個 run 的 class loader 能載入 OpenTelemetry 的 API、SDK、OTLP 匯出與語意慣例類別，來源是 run 執行期目錄。
2. 版本與 Engine 自用的版本分開管理；初始值為現有版本目錄的值，公告於 runner-libraries。
3. 每個 run 的 OpenTelemetry 設定與全域狀態只屬於該 run。
4. pipeline jar 夾帶 OpenTelemetry 類別時，以 Runner 的為準，並在上傳分析結果與該 run 的 log 各給一次警告。
5. 不引用 OpenTelemetry 的 pipeline 行為不變。
6. 開發入口提供同一組函式庫（行為與 Engine 一致）。
7. Engine 啟動檢查 run 執行期目錄時，新函式庫缺失視為目錄不完整；目錄中含 Engine 自己的 OpenTelemetry 類別仍不允許（兩者是不同來源）。

## 驗收條件

- 引用 OpenTelemetry 類別的 pipeline 能在 Runner 初始化 SDK，並以 OTLP 匯出 trace 到測試用的真實接收端（簡易 Fake）。
- 兩個 run 同時執行，各自設定不同的服務名稱與端點，彼此的 trace 不混；一個 run 結束或關閉其 SDK，不影響另一個與 Engine 的 trace。
- 既有不引用 OpenTelemetry 的 pipeline 測試全數通過，行為不變。
- 公告的版本與 run 實際載入的版本一致，以自動化檢查驗證；公告與執行期目錄內容不符時檢查失敗。
- 夾帶不同版本 OpenTelemetry 的 jar：run 使用 Runner 的版本，警告出現在上傳結果與 run log。
- run 結束後 class loader 可回收，包含 pipeline 未關閉 SDK 的情況。
- 04-deployment 與 ADR-001 的相關描述同步更新；文件一致性不留矛盾。

## 架構約束

- 不得改變 [ADR-001](../../pipeline-engine/adr/ADR-001-isolated-classloader.md) 的隔離：run 的上層仍只有 JDK 平台 class loader。
- 邊界只傳 JDK 內建型別，不穿過 OpenTelemetry 型別。
- Engine 的 OpenTelemetry 不得被 run 看見，反之亦然。
- core 與 runner 主程式維持不依賴 OpenTelemetry；函式庫只存在於 run 執行期目錄。
- 測試的外部系統用真實或自製簡易真實實作，不用 Stub 或 Mock。
