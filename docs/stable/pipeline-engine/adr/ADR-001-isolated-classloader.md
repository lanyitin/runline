# ADR-001 同一 JVM、每次 run 獨立 root class loader

狀態：已核可（2026-10-03）。回答：pipeline 在哪裡、以何種隔離執行。

## 背景
使用者要求 Engine 在同一個 JVM 內執行 pipeline，且每次 run 使用不同的 root class loader，以支援後續功能。

## 決策
- 每次 run 建立專屬 class loader，其上層只有 JDK 平台 class loader，不是 Engine 的應用程式 class loader。
- Runner、context 與 pipeline jar（含其相依）都由該 loader 載入，每次 run 各有一份。
- Engine 與 run 之間只以 JDK 內建型別溝通（見 [05](../05-ipc.md)）。
- Run 結束後釋放 loader，使其可被回收。
- 開發入口與 Engine 共用同一份 Runner 執行路徑。

## 取捨
- 得到：pipeline 看不到 Engine 內部；同名類別與不同版本相依可並存；可回收。
- 失去：無記憶體／CPU 隔離，無法強制終止，全域狀態共享。

## 後果
- 取消與逾時為協作式。
- 需監控 loader 洩漏。
- 日後改採子行程時，邊界契約可沿用。
- 跨 run 共享的狀態（例如共享資源的鎖）必須由 Engine 持有，並經邊界提供給 run（[ADR-007](ADR-007-shared-resources.md)）。
