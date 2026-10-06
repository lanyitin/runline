# WI-02 Runner 核心

目標：在獨立 class loader 中執行 pipeline，並以 context 套用 metadata 限制。

## 行為與驗收條件
- 給定 jar 與 pipeline 識別、參數，Runner 在專屬 class loader 內執行並回報狀態、log、結果。
- 兩個同時執行的 run 彼此看不到對方的類別；run 看不到 Engine 的類別。
- 同名類別的不同版本可在不同 run 並存。
- Run 結束後 class loader 可被回收（可驗證）。
- 標準輸出入的內容歸屬到所屬 run。
- 每個 run 在一條專屬平台 thread 上執行，thread 以 run 識別碼命名，並使用該 run 的 context class loader。
- Run 結束後檢查是否有殘留 thread，並回報（供 class loader 洩漏監控）。
- 初始化階段向 WI-11 取得兩種目錄的位置，並交給 context。
- 取消以中斷訊號傳達，可喚醒的阻塞呼叫會被喚醒；不可喚醒的維持協作式。
- 逾時為協作式；逾時後仍未結束的 run 被標記為「逾時未結束」。
- Pipeline 失敗（例外）時，回報失敗原因，不影響其他 run。
- 邊界只使用 JDK 內建型別。
- Run 生命週期包含「初始化」階段，位於 pipeline 本體執行之前；資源取得在此階段，由邊界另一側實作（Engine 或開發入口），細節見 WI-09。

## 架構約束
[ADR-001](../adr/ADR-001-isolated-classloader.md)、[05](../05-ipc.md)。JVM 結束呼叫的處置請回報後交由架構決定。
