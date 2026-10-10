# WI-66 共用背景執行緒不持有 run 的 class loader

本文回答：Engine 跨 run 共用的背景執行緒如何保證不持有任何 run 的 class loader，以及如何驗收。狀態：已核可（2026-10-10）。相依：WI-46。決策見 [ADR-001](../adr/ADR-001-isolated-classloader.md)「共用背景執行緒」一點。

## 背景

WI-51 發現：`openai-compatible` 的共用計時執行緒，是在第一個使用它的 run 的執行緒上建立的，因此繼承了那個 run 的 class loader，使它永遠無法回收。WI-51 的測試「after the same races every class loader of a run is reclaimed」因此失敗。

## 行為與驗收條件

- 第一個使用 `openai-compatible` 資源的 run 結束後，它的 class loader 可被回收。以真實打包的 Engine 與 Fake 服務驗證，涵蓋一般呼叫與串流。
- 盤點 Engine 中所有跨 run 共用的背景執行緒與執行緒池（計時、排程、連線池維護、遙測、HTTP 用戶端等），每一個都不持有任何 run 的 class loader：它們的建立不受當下 run 的執行緒影響。盤點結果列在回報與「實作結果」中。
- 對盤點出的每一類共用背景工作，各有一個「第一個觸發它的 run 結束後，class loader 可被回收」的測試；其中以 run 的執行緒首次觸發者，須先在修正前以失敗證明測試有效。
- WI-51 原本失敗的上述測試不修改即通過；WI-61 的背景執行緒測試仍然通過。

## 架構約束

- 不改變任何資源型別的行為、逾時與錯誤類別。
- 每個 run 使用獨立 root class loader 的模型不變（ADR-001）。
- 測試不使用 Stub 或 Mock。
