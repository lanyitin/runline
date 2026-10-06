# WI-19 metadata 讀取統一到 analyzer

本文回答：如何讓 metadata 的位元組層級讀取只有單一來源，供 Engine 與開發入口共用。狀態：已核可（2026-10-04）。相依：WI-06、WI-16。

## 背景

不執行程式碼即讀出 pipeline 的 metadata，目前有三處各自實作相同的規則：

| 位置 | 讀取方式 | 用途 |
|---|---|---|
| core | 以反射讀取已載入的類別 | 執行期（Runner 載入後） |
| analyzer | 直接讀類別檔，取得網路與行程限制等判定所需資訊 | 靜態分析 |
| engine | 直接讀類別檔，取得完整 metadata | 上傳探索與儲存（[WI-06](WI-06-upload-and-discovery.md)） |

預設值（例如網路或行程限制未宣告即視為不限制）在多處重複，日後容易不一致。analyzer 的公開結果目前不含完整 metadata，且 analyzer 不得依賴 core（[WI-16](WI-16-analyzer-module.md)），因此位元組層級的讀取由 analyzer 擁有最合適。

## 行為與驗收條件

- 位元組層級的 metadata 讀取只存在於 analyzer 模組；analyzer 的公開結果包含每個 pipeline 的完整 metadata（名稱、參數、檔案範圍與模式、網路與行程限制、宣告的資源），與判定同一次分析產生。
- Engine 的上傳探索與儲存改用 analyzer 提供的 metadata，engine 不再保有自己的位元組讀取實作；上傳流程對外的行為、回應與儲存內容與改動前相同。
- 開發入口（devkit）如需顯示 metadata，使用同一份來源，不另寫讀取邏輯。
- 預設值的語意只在 analyzer 與 core 之間一致，並有對照測試：對同一個真實編譯的 pipeline，analyzer 讀出的 metadata 與 core 以反射讀出的 metadata 完全相同；涵蓋每一種宣告方式與每一個預設值，包含未宣告網路與行程限制、必填與含預設值的參數、各種檔案範圍與模式。對照測試位於可同時看到兩者的測試範圍，不使 analyzer 的主程式依賴 core。
- 分析與判定的行為不變：既有的分析測試全部通過，測試數量不減少；Engine 既有測試全部通過。
- 建置、全部測試在 JDK 25 下通過，`ktfmtCheck` 通過。

## 架構約束

- analyzer 的主程式維持只依賴 JDK 與 Kotlin 標準函式庫，不依賴 core；測試期依賴 core 僅用於編譯與對照測試輸入（[WI-16](WI-16-analyzer-module.md)）。
- 不執行 pipeline 邏輯，也不觸發其靜態初始化（[ADR-003](../adr/ADR-003-jar-and-discovery.md)）。
- 不改變 core 的契約與執行期行為；core 的反射讀取維持，作為執行期的讀取方式與對照基準。
- 儲存格式與 API 回應不變，不需要資料遷移。
- 不新增外部相依；不新增 git hook 或 CI；完成程式碼變更時依專案規則先以 ktfmt 格式化。
