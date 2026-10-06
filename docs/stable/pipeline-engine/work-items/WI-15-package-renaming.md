# WI-15 套件名稱調整為 dev.lawlan.runline 前綴

本文回答：三個模組的套件名稱與專案 group 如何調整，使其統一在 `dev.lawlan.runline` 之下。狀態：已核可（2026-10-04）。無相依；排在 WI-05 補強與 WI-03 之前。

## 背景

現況的套件名稱：

| 模組 | 現行套件 | 備註 |
|---|---|---|
| core | `dev.lawlan.core` | 含範例與測試夾具子套件 |
| runner | `dev.lawlan.runner` | 含隔離入口與測試輔助子套件 |
| engine | `dev.lawlan` | 主程式直接位於根套件；靜態分析位於其 `safety` 子套件；測試輔助位於 `support` 子套件 |

Gradle 專案 group 目前為 `dev.lawlan`。

## 目標

| 模組 | 目標套件 |
|---|---|
| core | `dev.lawlan.runline.core` |
| runner | `dev.lawlan.runline.runner` |
| engine | `dev.lawlan.runline.engine`（含原 `safety`、`support` 子套件，變為其子套件） |

子套件的相對結構維持不變，只改前綴。測試程式的套件跟隨其對應的主程式。Gradle 專案 group 改為 `dev.lawlan.runline`。

## 行為與驗收條件

- 三個模組的所有主程式與測試程式的套件名稱符合上表；舊的 `dev.lawlan.core`、`dev.lawlan.runner` 與 engine 位於 `dev.lawlan` 根套件的程式不再存在。
- Gradle 專案 group 為 `dev.lawlan.runline`，各模組的建置產物座標隨之改變，專案內任何依賴該座標的設定同步更新。
- 不改變任何行為：建置、全部既有測試在 JDK 25 下通過，測試數量與改名前相同，`ktfmtCheck` 通過。
- 以名稱參照套件或類別的設定與資源同步更新，包括 Engine 的啟動設定中以名稱載入的模組進入點，以及日誌與其他組態中的套件名稱。
- 靜態分析判定「core 為受信任的葉節點」所依據的套件名稱，改用新的 core 套件；分析結果與改名前相同。既有的分析測試以新名稱通過。
- Runner 的 class loader 隔離與邊界型別檢查，以新的套件名稱運作；「run 內看不到 Engine 與 Host 專用類別」的測試以新名稱通過。
- 模組邊界測試（core 與 runner 不含 Ktor、OpenTelemetry、Engine）檢查的類別名稱同步更新，且確實能在邊界被破壞時失敗。
- 專案內的說明文件（例如各模組的 README）中提到的舊套件名稱同步更新。
- 變更只包含套件宣告、引用、目錄對應、group 與上述設定；不夾帶行為改變或格式以外的重構。

## 架構約束

- core 與 runner 仍不得依賴 Ktor、OpenTelemetry 或 Engine；runner 只依賴 core。
- Runner 與 core 的 class loader 載入順序、邊界只傳 JDK 內建型別的規則不受影響（[ADR-001](../adr/ADR-001-isolated-classloader.md)、[05](../05-ipc.md)）。
- 靜態分析的 core 判定（[ADR-002](../adr/ADR-002-context-and-unsafe.md)）依套件識別 core；改名後仍須成立。
- 目前沒有任何已儲存的 pipeline jar，因此不需要舊套件名稱的相容處理；範例與測試用的 pipeline 隨之更新。
- 不新增 git hook 或 CI；完成程式碼變更時依專案規則先以 ktfmt 格式化。
