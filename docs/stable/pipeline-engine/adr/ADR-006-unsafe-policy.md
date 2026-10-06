# ADR-006 Unsafe 執行由各 pipeline 的設定決定

狀態：已核可（2026-10-03）。回答：Engine 如何決定 unsafe pipeline 能否執行。

## 背景
Unsafe pipeline 無阻擋行為，是否執行應由使用者決定，且粒度為各個 pipeline。

## 決策
- 每個 pipeline 有「允許以 unsafe 執行」設定，由管理員維護，預設為不允許。
- Safe pipeline 不受此設定影響。
- 設定不允許時，unsafe pipeline 的 run 在建立時被拒絕並記錄原因。
- 實際執行 unsafe pipeline 的 run 會記錄當時的設定與設定者。
- 沒有全域開關。

## 取捨
- 得到：逐 pipeline 的明確授權、可稽核。
- 失去：pipeline 多時管理成本增加。

## 後果
- 設定綁定在特定版本的 pipeline 定義上，新版本不繼承，需管理員重新設定。
