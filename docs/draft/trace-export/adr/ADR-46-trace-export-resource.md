# ADR-46 trace 匯出型別化資源與 core span 契約

狀態：提案中（2026-10-27）。Issue：#46。補充 [ADR-019](../../../stable/pipeline-engine/adr/ADR-019-typed-shared-resources.md)（新增一個內建型別），不取代；取代已作廢的 [ADR-42](../../../stable/run-otel/adr/ADR-42-run-provides-opentelemetry.md)。來源：runline-transcribe 的 ADR-31（該儲存庫 Issue #31，已核可）。回答：pipeline 如何在不持有機密、不引用 OpenTelemetry 的前提下送出 trace，以及 span 契約、緩衝與資源的形狀。

## 背景

- pipeline 需要把 trace 送到 Langfuse（OTLP 端點，Basic 認證）。認證金鑰作為 run 參數會出現在 run 紀錄與介面，違反「pipeline 不持有機密」。
- ADR-019 的模型是：機密只在金鑰庫，Engine 中介使用，不把原值交給 pipeline；資源型別是封閉集合；邊界上只傳 JDK 內建型別。現有型別（`counter`、`file`、`jdbc-pool`、`openai-compatible`）都不能代為送出 trace。
- Engine 已使用 OpenTelemetry（Engine 自身的遙測），但其端點與認證由部署決定，與「每個資源各有端點與金鑰」不同。

## 方案比較

### 一、span 如何跨過邊界

| 選項 | 內容 | 優點 | 缺點 |
|---|---|---|---|
| A. 完成後提交（採用） | pipeline 在 span 結束時提交一筆完整紀錄：名稱、起訖時間、屬性、父子關係、狀態 | 邊界只有一個單向、無狀態的呼叫；run 被取消或失敗時不留下未結束的 span；Engine 不需追蹤 pipeline 的開啟中 span | 子 span 通常先於父 span 提交，Engine 須用 pipeline 給的本地識別碼對應父子 |
| B. 開啟與結束兩段式 | 開啟時回傳控制代碼，結束時再呼叫 | 貼近 OpenTelemetry 使用習慣 | 邊界有狀態；取消或例外時開啟中的 span 洩漏；需要逾時清理 |
| C. 批次整包提交 | run 結束時一次提交全部 span | 邊界呼叫最少 | run 中途失敗或被終止時全部遺失；長 run 在結束前看不到 trace |

推薦 A。

### 二、匯出端點與金鑰的持有

| 選項 | 內容 | 優點 | 缺點 |
|---|---|---|---|
| A. 每個資源一個獨立的匯出器（採用） | 資源設定決定端點、服務名稱與金鑰別名；Engine 為每個資源維護自己的匯出器，與 Engine 自身遙測分開 | 可依資源區分 Langfuse 專案；金鑰輪替只影響該資源；Engine 自身遙測不受影響 | 多一組匯出器與緩衝要管理 |
| B. 沿用 Engine 自身遙測的匯出設定 | 全部 pipeline 的 span 走部署設定的同一個端點 | 不增加型別 | 無法依資源區分；Langfuse 金鑰要放環境變數，與金鑰庫模型不一致；pipeline 的內容混進 Engine 自身遙測 |

推薦 A。

## 決策

1. **新增內建型別 `trace-export`**（ADR-019 的封閉集合新增一員）。設定欄位的語意：OTLP 匯出端點、服務名稱、一個金鑰庫別名。別名指向的機密是整串認證值，Engine 組成匯出請求的認證標頭，值不入版本庫、資料庫、映像檔、API 回應、run 紀錄與 log。管理員建立與維護，沿用既有資源管理、金鑰庫別名與綁定流程（ADR-019、ADR-025）。欄位的確切名稱與格式由工作項定義。
2. **core 契約**：pipeline 宣告一個資源角色後取得受控存取端，以完成後提交（方案一 A）送出 span。span 紀錄只含 JDK 內建型別，語意如下：
   - 名稱、起訖時間、狀態（成功、失敗與選填訊息）；
   - 屬性：字串鍵，值限字串、布林、整數、浮點數與其清單；
   - 父子關係：pipeline 自行指定的本地識別碼與父識別碼；
   - 追蹤識別碼由 Engine 依 run 指派，同一個 run 的 span 屬於同一條 trace，pipeline 不需產生也不能指定。
3. **Engine 側的匯出器**：每個資源一個，使用 OTLP 匯出。OpenTelemetry 的 SDK 與匯出器只存在於 Engine 的 class loader；run 不載入任何 OpenTelemetry 類別（不使用 ADR-42 的作法）。
4. **有界緩衝與失敗處理**：
   - 存取端提交即返回，不等待匯出，也不拋出例外給 pipeline。
   - 每個資源一個有界佇列；佇列滿時丟棄新提交的 span，並累加丟棄計數。
   - 匯出失敗、逾時或金鑰缺失時，該批丟棄並計數，記錄警告（限速，避免洗版），不重試無限次。
   - 單筆 span 的屬性數量與值長度有上限，超過則截斷並計數，保護 Engine 記憶體。
   - run 結束時有界等待佇列沖出，逾時即放棄。
   - 上述數值的預設值與可調性由工作項定義，初始建議對照 OpenTelemetry 批次處理器的預設量級。
5. **未綁定或未啟用即不匯出**：pipeline 沒綁定資源，或資源停用時，存取端為空操作，pipeline 行為與沒有 tracing 時相同。pipeline 以「是否有存取端」判斷，不因此失敗。
6. **金鑰輪替**：沿用金鑰庫重載機制。重載後新的匯出請求使用新金鑰，不需要重新部署 pipeline 或重啟 run；重載期間已在佇列中的 span 使用哪一版金鑰，以不丟失為優先。
7. **內容不過濾**：契約不過濾屬性內容（包含提示詞與回答文字）。內容是否送出，由管理員是否建立並綁定此資源決定。Engine 自己的 log、metric 與錯誤訊息不得回顯屬性內容。
8. **安全性**：端點與金鑰只由管理員設定，pipeline 無法指定目的地。端點的 TLS 信任沿用 ADR-019 決策 12 的機制。pipeline 經 core 契約使用，不影響 safe／unsafe 判定。
9. **開發入口**：devkit 提供本機實作，不對外匯出，與其他型別一致，使 IDE 中能執行含 tracing 的 pipeline。
10. **容量語意**：此型別不提供互斥或限流。為相容 ADR-019 的資源模型（含容量欄位與 run 級持有），容量設為接近無限的固定值，使取得容量永遠立即成功、不會讓 run 等待；資源表單不讓管理員編輯此欄位。固定值的確切數字與持有者顯示方式由工作項定義。
11. **一個 run 一條 trace**：同一個 run 的所有 span 屬於同一條 trace，契約不提供追蹤分組。日後若需要同一個 run 內多條獨立 trace，另開 ADR 擴充契約。
12. **數值預設**：屬性數量與值長度上限、佇列大小與沖出等待時間，由工作項依 OpenTelemetry 批次處理器的預設量級起訂，並可由部署組態調整。

## 取捨

- 得到：機密不在 pipeline 與 run 紀錄；pipeline 保持薄 jar，不依賴 OpenTelemetry；可依資源區分追蹤後端；tracing 不影響 run 的成敗與延遲。
- 失去：Engine 要新增型別、存取端契約、匯出器與管理介面；pipeline 失去直接使用 OpenTelemetry 完整 API 的彈性；佇列滿時 span 會遺失（有計數）。

## 後果

- 新增 core 契約、Engine 型別、型別目錄（[ADR-021](../../../stable/pipeline-engine/adr/ADR-021-resource-type-catalog-endpoint.md)）項目、Console 表單與 08-api 的資源型別說明。
- 丟棄、匯出成功與失敗的計數成為 Engine 的可觀測指標；確切名稱由工作項定義。
- runline-transcribe 之後改用此契約，並移除自己的 OpenTelemetry 依賴與三個 langfuse 參數；該儲存庫的工作不在本儲存庫範圍。
- 已作廢的 #42、#43 實作不併入。實作過程找到的 class loader 回收問題（背景執行緒、傳送端）因 run 不再載入 OpenTelemetry 而不適用。
