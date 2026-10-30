# ADR-025 analyzer 主程式共用 core 的 metadata 模型

狀態：提案中（2026-10-30）。修訂 [WI-16](../../../stable/pipeline-engine/work-items/WI-16-analyzer-module.md) 的「analyzer 主程式不依賴 core」約束與 [WI-19](../../../stable/pipeline-engine/work-items/WI-19-unified-metadata-reading.md) 的對照測試要求。回答：pipeline metadata 的模型應有幾份，analyzer 可以依賴什麼。

## 背景

core 與 analyzer 各有一份 metadata 模型，預設值以對照測試保持一致（[02](../02-current-state.md) 發現四）。WI-16 禁止 analyzer 依賴 core 的理由是「分析程式不隨 run 載入」與「分析是純函式」；這兩點都不受 analyzer 依賴 core 影響，因為 core 只依賴 JDK 與 Kotlin 標準函式庫，且依賴方向是 analyzer 指向 core，不是相反。

## 決策

1. analyzer 主程式可依賴 core，**只限** metadata 模型、宣告註解與預設值的定義；不得依賴 runner、accessors、engine。
2. metadata 模型只有 core 一份。analyzer 以位元組層級讀取（不載入、不執行 pipeline 類別）產出 core 的模型；位元組層級讀取仍只在 analyzer（WI-19 的單一來源原則不變）。
3. 預設值只在 core 定義一次，analyzer 與 run 期的讀取都引用它。對照測試改為驗證「analyzer 讀出的結果等於 run 期讀出的結果」，不再需要比對兩份模型。

## 取捨

- 得到：新增 metadata 欄位只改一份模型；devkit 與 engine 不再面對兩個同名型別。
- 付出：analyzer 的對外相依多了 core；core 的模型變更會連帶影響 analyzer 的編譯（這正是期望的一致性）。

## 後果

- 建置的依賴檢查（[ADR-023](ADR-023-engine-feature-slices.md)）將「analyzer 只能依賴 core」列為規則。
- 靜態分析仍以套件名稱識別 core 為受信任葉節點，行為不變。
