# WI-65 刪除 `jdbc-pool` 資源時關閉連線池

本文回答：刪除 `jdbc-pool` 資源後，它的資料庫連線如何被關閉，以及如何驗收。狀態：已核可（2026-10-10）。相依：WI-40、WI-48。決策見 [ADR-019](../adr/ADR-019-typed-shared-resources.md) 決策 8（刪除）與 `jdbc-pool` 的連線池世代。

## 背景

WI-51 發現：刪除 `jdbc-pool` 資源後，該資源的連線池沒有被關閉，連線一直開著，佔用資料庫的連線數。每次都能重現。WI-51 的測試「deleting a jdbc-pool resource closes the connections of its pool」與「after the same races no request or connection is left once the resources are deleted」因此失敗。

## 行為與驗收條件

- 刪除沒有持有者的 `jdbc-pool` 資源後，該資源所有世代的連線池都已關閉。驗證方式：以真實 PostgreSQL 查詢，屬於該資源帳號的連線數歸零；完成所需時間不超過 `jdbc-pool` 清理連線的逾時（WI-62 的 5 秒）。
- 修改設定或金鑰庫重載產生的舊世代，在資源被刪除時同樣關閉。
- 有持有者時刪除仍被拒絕（既有語意不變）；強制釋放後再刪除，連線同樣全部關閉。
- 關閉連線失敗時（例如資料庫無回應），刪除本身不卡住：在有界時間內完成，失敗寫入 log，連線在資料庫端逾時後消失。
- WI-51 原本失敗的上述 2 個測試不修改即通過。
- 其他型別的刪除行為不變。

## 架構約束

- 不改變刪除 API、預覽與 `resource_in_use` 的語意。
- 測試不使用 Stub 或 Mock；資料庫用真實容器。
