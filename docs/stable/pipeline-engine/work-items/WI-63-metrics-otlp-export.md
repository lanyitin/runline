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
