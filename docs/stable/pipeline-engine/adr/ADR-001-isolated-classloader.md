# ADR-001 同一 JVM、每次 run 獨立 root class loader

狀態：已核可（2026-10-03）；共用背景執行緒的規則為 2026-10-10 補充。回答：pipeline 在哪裡、以何種隔離執行。

## 背景
使用者要求 Engine 在同一個 JVM 內執行 pipeline，且每次 run 使用不同的 root class loader，以支援後續功能。

## 決策
- 每次 run 建立專屬 class loader，其上層只有 JDK 平台 class loader，不是 Engine 的應用程式 class loader。
- Runner、context 與 pipeline jar（含其相依）都由該 loader 載入，每次 run 各有一份。
- Engine 與 run 之間只以 JDK 內建型別溝通（見 [05](../05-ipc.md)）。
- Run 結束後釋放 loader，使其可被回收。
- Engine 跨 run 共用的背景執行緒（計時、排程、連線池維護、遙測等）不得持有任何 run 的 class loader：它們的建立不得受當下 run 的執行緒影響，所繼承的 class loader 與執行緒群組一律是 Engine 自己的。新增任何跨 run 共用的背景工作時都適用，並以「run 結束後其 class loader 可被回收」的測試驗證。理由：在 run 的執行緒上首次建立的共用執行緒會繼承該 run 的 class loader，使它永遠無法回收；寫成通則避免日後新增背景工作時重犯。
- 開發入口與 Engine 共用同一份 Runner 執行路徑。

## 取捨
- 得到：pipeline 看不到 Engine 內部；同名類別與不同版本相依可並存；可回收。
- 失去：無記憶體／CPU 隔離，無法強制終止，全域狀態共享。

## 後果
- 取消與逾時為協作式。
- 需監控 loader 洩漏。
- 日後改採子行程時，邊界契約可沿用。
- 跨 run 共享的狀態（例如共享資源的鎖）必須由 Engine 持有，並經邊界提供給 run（[ADR-007](ADR-007-shared-resources.md)）。
