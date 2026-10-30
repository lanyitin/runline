# ADR-024 core 只保留 pipeline 撰寫契約

狀態：已核可（2026-10-30）。縮小 [ADR-002](../../../stable/pipeline-engine/adr/ADR-002-context-and-unsafe.md) 中「core 為受信任的葉節點」所涵蓋的範圍，不改變該規則本身。回答：哪些東西屬於 core，執行期實作放哪裡。

## 背景

core 是 pipeline 撰寫者編譯時依賴的契約，也是靜態分析中受信任、不向下展開的葉節點。現況 core 也公開了只有 runner 使用的執行期實作：限制 context、錄製 context、IO 錄製器、以反射讀取 metadata 的讀取器，以及存取端在 run 側的轉接（[02](../02-current-state.md) 發現三）。pipeline 參照這些類別不會被判 unsafe。

## 決策

1. **core 只含撰寫契約**：pipeline 介面與宣告、metadata 模型與宣告註解、context 與各 IO 能力的介面、存取端介面與其邊界資料型別、pipeline 會收到的例外與錯誤類別。
2. **執行期實作移到 runner 的 run 側**（隨 run class loader 載入的部分）：限制、錄製、存取端轉接、反射讀取 metadata。run-runtime 的組成（runner、core、Kotlin 標準函式庫）不變。
3. **判定規則不變**：core 仍是受信任的葉節點；runner 不在預設白名單，pipeline 參照 runner 的類別會被判 unsafe。

## 取捨

- 得到：core 的公開面等於撰寫所需；受信任面縮小；撰寫者看到的 API 更聚焦。
- 付出：直接使用被移走類別的 pipeline 需修改，且會被判 unsafe；不保留過渡期，devkit 指南註明。儲存庫內只有 runner 使用這些類別。

## 後果

- devkit 與 engine 透過 runner 使用限制與錄製能力，不直接接觸 core 的實作。
- 錄製仍只能由開發入口開啟，Engine 沒有開啟錄製的途徑（維持現行約束）。
