# WI-03 開發入口與 IDE 除錯

目標：開發人員在 IDE 中對自己的 pipeline 設中斷點並逐步除錯。這是不可妥協的功能。

狀態：已核可（2026-10-04）。相依：WI-02、WI-05、WI-16。

## 模組歸屬

開發入口同時使用 core（撰寫契約）、runner（執行路徑）與 analyzer（判定），現有模組都不能容納它而不破壞邊界：

| 候選位置 | 結論 |
|---|---|
| core | 否決。core 不能依賴 runner 或 analyzer |
| analyzer | 否決。主程式只能依賴 JDK 與 Kotlin 標準函式庫（[WI-16](WI-16-analyzer-module.md)） |
| engine | 否決。會把 Ktor 與資料庫帶入開發人員的專案 |
| runner | 否決。runner 只依賴 core；且 runner 的類別會載入每個 run 的 class loader，開發入口不應暴露給 pipeline |
| 新模組 devkit | 採用。關注點是「開發人員在本機執行與除錯 pipeline」 |

| 模組 | 套件 | 依賴 |
|---|---|---|
| devkit | `dev.lawlan.runline.devkit` | core、runner、analyzer；不依賴 Engine、Ktor、OpenTelemetry 或資料庫 |

## 行為與驗收條件
- 專案有 devkit 模組；模組邊界測試驗證它不含 Engine、Ktor、OpenTelemetry 與資料庫相依，且在邊界被破壞時會失敗。
- 開發人員從自己的 pipeline 專案，用一個標準執行／除錯設定即可啟動 Runner 執行 pipeline。
- 中斷點、單步、檢視變數在 pipeline 程式碼中有效；由使用者在 IntelliJ IDEA 實測並記錄步驟，操作指南中標明實測狀態。
- pipeline 在單一專屬 thread 上線性執行，單步時不會在 thread 間跳動；自動化測試驗證 thread 為專屬平台 thread、以 run 識別碼命名，且其 context class loader 為該 run 的獨立 class loader。
- 走的是與 Engine 相同的執行路徑（含獨立 class loader）；若兩者無法同時成立，停下來回報。
- 執行時套用與 Engine 相同的 metadata 限制，並顯示 unsafe 判定：使用 analyzer 模組的同一份分析，顯示判定結果、白名單版本、每項原因與依賴路徑，以及繁體中文的限度說明。判定為 JVM 結束呼叫時，須說明被允許的 unsafe pipeline 可以終止整個程序。
- 不需要啟動 Engine 或資料庫，也不依賴 Engine 模組。
- 本機提供同語意的兩種目錄：共享目錄在多次執行間保留，私有目錄每次執行都是全新的；根位置可設定，預設位於開發專案內。
- 提供開發人員操作指南，涵蓋在 IDE 中執行與除錯的步驟。

## 架構約束
不得為了方便除錯而繞過 [ADR-001](../adr/ADR-001-isolated-classloader.md) 的隔離。devkit 只依賴 core、runner 與 analyzer。判定顯示使用 analyzer 的同一份分析，不複製分析邏輯。目錄準備與保留沿用 [WI-11](WI-11-workspace-directories.md) 的行為。
