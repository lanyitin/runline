# WI-16 靜態分析獨立為 analyzer 模組

本文回答：靜態分析如何獨立成模組，使 Engine 與開發入口都能使用同一份分析而不互相依賴。狀態：已核可（2026-10-04）。相依：WI-05、WI-15。

## 背景

靜態分析（WI-05）位於 engine 模組。開發入口（WI-03）需要顯示與 Engine 相同的 safe／unsafe 判定，但開發入口不得依賴 Engine：core 與 runner 必須能獨立於 Engine 使用，開發人員的專案不應帶入 Ktor 或資料庫相依。

選擇理由與被否決的做法：

| 做法 | 結論 |
|---|---|
| 獨立 analyzer 模組 | 採用。一個模組對應一個關注點，分析只依賴 JDK，Engine 與開發入口共用 |
| 搬進 runner | 否決。分析是上傳時的職責，runner 的類別會載入每個 run 的 class loader，分析程式不應隨之進入 |
| 開發入口不顯示判定 | 否決。開發人員失去與 Engine 一致的判定 |

## 目標

| 模組 | 套件 | 責任 | 依賴 |
|---|---|---|---|
| analyzer | `dev.lawlan.runline.analyzer` | 靜態分析與 safe／unsafe 判定 | 主程式僅依賴 JDK 與 Kotlin 標準函式庫；測試期依賴 core，僅用於編譯測試輸入的 pipeline |

依賴方向：engine 依賴 analyzer；開發入口依賴 analyzer；analyzer 的主程式不依賴 core、runner、engine、Ktor、OpenTelemetry 或資料庫。

## 行為與驗收條件

- 專案有 analyzer 模組，靜態分析的主程式與測試程式位於其中，套件為 `dev.lawlan.runline.analyzer`；engine 不含靜態分析程式，並依賴 analyzer。
- 分析行為與搬移前完全相同：WI-05 的所有驗收條件仍成立，既有的分析測試全部移入並通過，測試數量不減。
- 三個既有模組加上 analyzer 的測試總數與搬移前相同；engine 減少的測試數量等於 analyzer 增加的數量。
- analyzer 的主程式能在不含 Ktor、Engine、runner 與資料庫的環境中建置；有模組邊界測試，驗證 analyzer 不含這些相依，且在邊界被破壞時會失敗。
- 靜態分析以套件名稱識別 core 的規則維持不變，仍使用 `dev.lawlan.runline.core`。
- 限度說明文字為繁體中文，內容涵蓋：不涵蓋反射與動態載入、此限度同樣適用於 JVM 結束呼叫的判定、白名單內的類別不被檢查。測試斷言這三項要點皆出現。
- 建置、全部測試在 JDK 25 下通過，`ktfmtCheck` 通過，含 analyzer 模組。
- 專案內的說明文件有 analyzer 模組的簡述，模組清單與相依說明一致。

## 架構約束

- 不改變任何判定行為；只搬移與調整套件、模組設定、限度說明語言。
- analyzer 是 jar 與白名單的純函式。主程式不依賴任何其他專案模組，不使用 core 的型別；識別 core 只靠套件名稱。
- 測試期可依賴 core，僅用於編譯測試輸入的 pipeline；此依賴不得出現在 analyzer 對外的相依中，也不得進入主程式。
- 分析過程不執行 pipeline 邏輯，也不觸發其靜態初始化（[ADR-003](../adr/ADR-003-jar-and-discovery.md)）。
- Runner 與 core 的邊界、class loader 規則不受影響（[ADR-001](../adr/ADR-001-isolated-classloader.md)）。
- 不新增 git hook 或 CI；完成程式碼變更時依專案規則先以 ktfmt 格式化。

## 待確認問題

- 判定原因與報告中的其他訊息文字（例如各項違規的說明）是否也統一為繁體中文。影響：呈現給開發人員與管理員的語言一致性；目前只有限度說明為繁體中文，其他訊息維持英文。
