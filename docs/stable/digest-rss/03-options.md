# 架構方案比較與推薦

本文回答：瀏覽器擷取與外部服務存取有哪些做法、為何採用現在的選擇。決策本身見 ADR。

## 瀏覽器擷取（採 A）

| 方案 | 做法 | 優點 | 缺點／風險 |
|---|---|---|---|
| **A（採用）** | pipeline 在 Engine 主機啟動 Chrome，以 DevTools 協定控制 | 單一部署單元；沿用 Engine 的取消與 log | pipeline 為 unsafe；Engine 主機需有 Chrome 與顯示；取消或 Engine 崩潰時要處理遺留行程 |
| B | 獨立的 Kotlin 擷取服務，pipeline 以 HTTP 呼叫 | pipeline 可保持 safe；瀏覽器環境與 Engine 隔離 | 多一個要部署與維運的服務 |
| C | 純 HTTP 抓取，不用瀏覽器 | 最簡單 | 需要 Cloudflare clearance 的站點抓不到，行為不等價 |

## 控制瀏覽器的方式（A 之下）

| 做法 | 優點 | 缺點 |
|---|---|---|
| **直接以 DevTools 協定（採用）** | 純 Kotlin／JVM，無 Node 相依；與 Playwright 的防偵測補丁無關，行為由我們掌握 | 防偵測效果須實測（WI-1000 關卡）；等待與截圖時機要自行處理 |
| Playwright for Java | 現成 API | 其發行物內含 Node 驅動，與「不再有 JavaScript」的目標衝突；來源系統用的防偵測補丁版不適用 |
| Selenium | 成熟 | 需 chromedriver，另一個外部行程；防偵測較差 |

採用直接以 DevTools 協定，並以 WI-1000 作為可行性關卡：指紋或目標網站無法通過時停下來由架構決定。

## 外部服務存取

| 做法 | 評估 |
|---|---|
| **pipeline 自帶 HTTP 用戶端，使用 JDK 內建能力（採用）** | 不改 Engine 的封閉型別集合；pipeline 本來就是 unsafe，允許執行是管理員的信任決定（ADR-006）；不帶第三方函式庫，不需要白名單條目 |
| 為每個服務新增資源型別 | 機密與限制最乾淨，但改動封閉集合與多個工作項，且這些服務只有這條 pipeline 使用 |

憑證存放見 [ADR-023](adr/ADR-023-service-credentials.md)。

## 摘要

A + 直接以 DevTools 協定 + 以 JDK 內建能力自帶 HTTP 用戶端；LLM、資料庫與憑證檔維持 safe 路徑。資料保存的選項比較見 [ADR-024](adr/ADR-024-sqlite-run-store.md)。
