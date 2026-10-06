# ADR-008 執行模型與 JDK 基準

狀態：已核可（2026-10-03）。回答：run 以什麼執行單位運行、使用哪個 JDK。

## 背景
Pipeline 是循序、阻塞式的程式，並須支援 IDE 中斷點除錯與 class loader 回收。專案現行 JDK 為 21。

## 決策

### 執行模型
- 每個 run 使用一條專屬的平台 thread，不使用 coroutine 執行 pipeline 本體，也不使用虛擬 thread。
- Thread 由有上限的執行器提供，上限即並行 run 數。
- Thread 以 run 識別碼命名，並在該 thread 上設定該 run 的 context class loader。
- Run 結束時檢查是否有殘留的 thread，作為偵測 class loader 洩漏的依據。
- 開發入口同樣在單一專屬 thread 上執行 pipeline。
- Engine 的周邊工作（排程、webhook、log 串流、資料庫存取）可使用 coroutine，與 Ktor 一致；coroutine 不穿過 Engine 與 run 的邊界。

### JDK 基準
- 採用今日（2026-10-03）最新的 LTS：JDK 25。
- Engine、core、Runner、開發入口與開發人員的 pipeline 都以 JDK 25 為基準。
- 開發環境（devcontainer）與建置的 toolchain 與之對齊。

## 取捨
- 得到：最直線的除錯體驗、取消可喚醒部分阻塞呼叫、class loader 與 thread 的對應單純、卡住的 run 可從 thread 辨識。
- 失去：每個 run 占一條平台 thread；v1 並行數小，影響可忽略。
- 虛擬 thread 在 JDK 25 可用，但本案不採用；若並行數日後顯著增加，另開 ADR 取代本文件。

## 後果
- 取消與逾時仍為協作式；專屬 thread 不改變無法強制終止的限度（[07](../07-nfr-risks.md)）。
- 靜態分析必須能讀取 JDK 25 的類別檔格式。
- 升級 JDK 25 須確認 Kotlin、Ktor、Gradle 與相依函式庫的相容性。
- 未來 LTS 發佈時，另開 ADR 決定是否升級。
