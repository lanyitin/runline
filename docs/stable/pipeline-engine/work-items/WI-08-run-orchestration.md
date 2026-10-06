# WI-08 Run 編排、unsafe 設定與歷史

目標：Engine 建立並管理 run 的完整生命週期，並落實各 pipeline 的 unsafe 執行設定。

狀態：已核可（2026-10-04）。相依：WI-02、WI-06、WI-19。

## 行為與驗收條件

### Run 的建立
- 建立 run 只有單一流程，手動建立與 trigger（[WI-07](WI-07-triggers.md)）建立都經由它；流程包含 unsafe 檢查、參數驗證、並行額度與排隊。
- 手動建立 run 的 API：呼叫者提供 pipeline 定義與參數，回應 run 識別碼。
  - 開發人員只可對自己上傳的 pipeline 建立 run，不限版本，包含自己上傳的舊版本；管理員可對任何 pipeline 建立（[ADR-012](../adr/ADR-012-api-authentication.md)）；未認證與權限不足的回應語意與既有 API 一致。對開發人員不可見的 pipeline，回應與「不存在」相同。
  - 參數依 pipeline 的 metadata 驗證：缺少必填、提供未宣告的參數時拒絕，並指出是哪個參數；未提供的選填參數套用預設值。
  - 定義不存在、參數無效、unsafe 不允許，各有清楚且可區分的拒絕原因，且不進入執行、不留下 run。
- 建立 run 時，若 pipeline 為 unsafe 且設定不允許，則拒絕並記錄原因，不進入執行；safe pipeline 不受影響。
- Run 識別碼在同一 pipeline 內唯一（[WI-11](WI-11-workspace-directories.md) 的要求）；run 記錄觸發來源（手動時為呼叫者的名稱，trigger 時為該 trigger）。

### unsafe 設定
- 管理員可逐 pipeline 設定是否允許以 unsafe 執行，預設不允許；設定者與時間被記錄。此管理 API 僅管理員可用，設定者記錄為 token 對應的名稱。
- 實際以 unsafe 執行的 run 記錄當時的設定與設定者。

### 生命週期與排程
- 並行 run 數量受組態上限約束，超出則排隊。
- Run 狀態包含「等待資源」；等待資源的 run 不占用並行額度。額度與所宣告的資源同時可用時，run 才進入初始化並開始執行（細節見 WI-09）。
- Run 狀態只能單向前進。
- 可取消 run（協作式）；逾時未結束的 run 持續占用並行額度，直到實際結束。取消的授權與查詢相同：開發人員只可取消自己上傳之 pipeline 的 run，管理員可取消全部。
- Engine 重啟時，進行中的 run 標記為「中斷」，不自動重跑；其私有目錄依 WI-11 的保留政策處理，「中斷」與 Runner 回報的結果如何對應，由實作評估後回報。
- 每個被接受的 run 最終都有終態。啟動路徑上任何非預期的例外都轉為失敗終態，並釋放目錄、並行額度與共享資源；不存在永遠不完成的 run 結果。

### 查詢、log 與觀測
- Run 的狀態與 log 可查詢；log 可即時串流。查詢與串流遵守與上傳結果相同的可見範圍：開發人員預設只可查詢自己上傳之 pipeline 的 run，管理員可查詢全部。
- 每個 run 有 trace；log、trace、metric 涵蓋 [07](../07-nfr-risks.md) 所列項目。
- WI-11 的目錄準備、結束與清除所產生的遙測，接入 Engine 的觀測與依賴注入，使其在 Engine 內實際生效。

### 隔離
- 在實際的 Engine 部署與打包形態下，run 內看不到 Engine 的類別；此條件在打包後的產物上可被驗證，不只在開發環境成立。

## 架構約束
[ADR-001](../adr/ADR-001-isolated-classloader.md)、[ADR-006](../adr/ADR-006-unsafe-policy.md)、[ADR-012](../adr/ADR-012-api-authentication.md)。使用 WI-02 的 Runner，不得另建執行路徑。沒有全域 unsafe 開關。

- Runner、core 與 Kotlin 標準函式庫必須與 Engine 的其餘部分分開打包，或明確指定 run 的類別路徑。不得把它們與 Engine 合併成單一產物後讓 run 的 class loader 共用，否則 run 會看到 Engine 的類別，違反 ADR-001。
- 若 WI-02 的 Runner 在非預期例外時可能使結果懸空，本項負責補強，使上述終態保證成立。
- Run 的資料表必須以「拒絕刪除」的方式參照 pipeline 定義，使被 run 引用的 artifact 無法刪除（[06](../06-data-model.md)）；需有測試以真實資料庫驗證刪除被拒絕。
- 各 pipeline 的 unsafe 設定沿用 pipeline 定義上已有的欄位，不另建重複的儲存。
- Run 所需的 metadata 取自 analyzer 的公開結果（[WI-19](WI-19-unified-metadata-reading.md)），不另寫讀取邏輯。
- 手動建立的 API 與 WI-07 的 trigger 共用同一個建立流程，不得各自實作一套。
