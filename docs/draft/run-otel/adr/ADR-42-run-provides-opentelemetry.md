# ADR-42 run 類別載入器提供 OpenTelemetry

狀態：提案中（2026-10-27）。Issue：#42。修訂 [ADR-001](../../../stable/pipeline-engine/adr/ADR-001-isolated-classloader.md) 的 class loader 內容（不取代其隔離決策）。回答：pipeline 需要 OpenTelemetry 時，由誰提供、版本與隔離如何處理。

## 背景

runline-transcribe 的 pipeline 需要用 OpenTelemetry 以 OTLP 匯出 trace 到 Langfuse。run 的 class loader 目前只含 Runner 入口、core 與 Kotlin 標準函式庫，上層是 JDK 平台 class loader，所以 pipeline 無法使用 OpenTelemetry，除非把它打包進 jar。runline-transcribe 已決定改為由 Runner 提供（該儲存庫 ADR-23）。

## 選項

| 選項 | 優點 | 缺點 |
|---|---|---|
| A. 維持現狀，pipeline 自行打包 | Runner 不動 | 每個 pipeline jar 變大；各自版本不一；與分析器對夾帶函式庫的展開範圍衝突；已被需求方否決 |
| **B. run 執行期目錄加入 OpenTelemetry，每個 run 各載一份（採用）** | 保持隔離；全域狀態自然屬於各 run；版本單點決定 | 每個 run 多載入一份類別；執行期目錄變大 |
| C. 由 Engine 的 OpenTelemetry 透過邊界共用 | 只有一份 | 需把 OpenTelemetry 型別穿過邊界，違反 JDK 內建型別規則；run 之間共享全域狀態，違反需求 |

## 決策

採 B。

1. run 執行期目錄新增 OpenTelemetry 的 API、SDK、OTLP 匯出與語意慣例函式庫；每個 run 的 class loader 從該目錄載入自己的一份。
2. 版本由 Runner 決定，公告於 [runner-libraries](../runner-libraries.md)。run 內提供的版本與 Engine 自用的版本分開管理，初始值取專案版本目錄現有版本。理由：Engine 的升級節奏與 pipeline 相容性承諾不同，綁在一起會使 Engine 升級變成對所有 pipeline 的破壞性變更。
3. 載入順序：Runner 提供的 OpenTelemetry 優先於 pipeline jar 夾帶的同名類別，與 core 相同（[ADR-002](../../../stable/pipeline-engine/adr/ADR-002-context-and-unsafe.md) 的前提）。
4. 版本不一致時警告，不拒絕。偵測範圍限於 jar 內夾帶了 OpenTelemetry 類別的情況：上傳分析與 run 開始時各告知一次，說明以 Runner 版本為準。pipeline 編譯時所用但未夾帶的版本，無法由 jar 判定，靠公告與 OpenTelemetry API 的向後相容承諾。
5. 邊界不變：Engine 與 run 之間仍只傳 JDK 內建型別。
6. 不使用 OpenTelemetry 的 pipeline 不載入這些類別，行為不變。

## 取捨

- 得到：隔離完整；pipeline jar 精簡；版本有單一公告。
- 失去：夾帶不同主要版本的 pipeline 會被強制使用 Runner 的版本，可能不相容；run 執行期目錄變大。

## 後果

- 04-deployment 對 run 執行期目錄內容的描述、Engine 啟動時對必要部分的檢查，需納入新函式庫。
- SDK 若在背景啟動執行緒，pipeline 未關閉時須不使 class loader 無法回收（ADR-001 的洩漏監控適用）。
- 工作項核可並完成後，stable 的 ADR-001 與 04-deployment 需同步改寫。
