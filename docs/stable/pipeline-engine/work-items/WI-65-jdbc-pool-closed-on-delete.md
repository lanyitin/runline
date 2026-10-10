# WI-65 刪除 `jdbc-pool` 資源時關閉連線池

本文回答：刪除 `jdbc-pool` 資源後，它的資料庫連線如何被關閉，以及如何驗收。狀態：已核可（2026-10-10）；已實作（2026-10-10，見「實作結果」）。相依：WI-40、WI-48。決策見 [ADR-019](../adr/ADR-019-typed-shared-resources.md) 決策 8（刪除）與 `jdbc-pool` 的連線池世代。

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

## 實作結果（2026-10-10）

**刪除時關閉連線池。** `ResourceBehavior` 新增 `removed(name)`（預設不做任何事），由 `ResourceRemoval` 在協調器的鎖內、資料庫中的定義刪除成功之後，對該資源的型別呼叫；只有 `jdbc-pool`（`JdbcPoolBehavior`）覆寫它，呼叫 `JdbcPools.remove`。`JdbcPools.remove` 把該資源目前的世代自 `current` 移除並退役：沒有持有者時立即關閉（連線池的每一條連線與該世代的 TLS 環境），仍有持有者時於最後一個持有者結束時關閉（與既有的世代退役相同）。修改設定或金鑰庫重載產生的舊世代原本就在最後一個持有者結束時關閉；刪除只在沒有持有者與等待者時成立，因此刪除成功時舊世代已關閉，目前的世代由刪除關閉。之後以同名重建的資源一律使用新的連線池（不再可能沿用舊世代）。在協調器的鎖內進行，使「刪除後立即以同名重建並取得資源」不會拿到即將被關閉的世代。刪除 API、預覽、`resource_in_use` 的語意與錯誤碼未改變；`counter`、`file`、`openai-compatible` 沒有覆寫 `removed`，刪除行為不變。

**有界。** 關閉連線不等待資料庫：PostgreSQL 驅動的 `close` 只送出結束訊息並關閉 socket，不讀取回應，I/O 錯誤由驅動自行忽略。實測以可凍結轉送（WI-62 的 `FreezableForward`）凍結資料庫方向後刪除，`DELETE` 在 5 秒內回 204，資源已不存在；解除凍結後該連線在資料庫端消失。沒有新增組態與逾時。

**測試（全部以真實元件：Testcontainers 的 PostgreSQL 17、`keytool` 做的 PKCS12 金鑰庫、真實編譯的 pipeline jar、真實排程器與 Runner；沒有 Stub 或 Mock；暫存目錄沿用 WI-57 的 `TestDirectories`）：**

- `JdbcPoolsTest`（accessors）新增「removing a resource closes the connections its pool keeps for the next run」。Red：`remove` 為空實作時 `the pool of a removed resource is open ==> expected: <0> but was: <1>`。
- 新增 `JdbcResourceDeletionTest`（engine，經 API）4 個，以資料庫端該資源帳號的連線數（`pg_stat_activity`）判定：
  - 「deleting a jdbc-pool resource nobody holds closes the connections its pool kept」：204 之後 5 秒內歸零。Red（接上 `ResourceRemoval` 之前）：`1 session(s) still open after 10027 ms`。
  - 「a held jdbc-pool resource is still not deleted, and once forced free its delete closes every connection」：持有中回 409 `resource_in_use` 且連線未被動到；強制釋放後刪除回 204，5 秒內歸零。
  - 「the generations made by a change of settings and by a keystore reload are all closed once the resource is deleted」：三個 run 分別持有原設定、修改設定後、金鑰庫重載（更換密碼）後的三個世代，刪除被拒絕；三個 run 結束後刪除，5 秒內歸零。
  - 「deleting while the database does not answer does not wait for it, and the connection goes once it does」：見上方「有界」。
  - 後 3 個測試寫在產品程式之後，Red 以暫時把 `JdbcPoolBehavior.removed` 改回不做任何事驗證：4 個測試全部失敗（皆為 `1 session(s) still open after 10 s`），還原後全部通過。
- 既有測試的調整：`ResourceAccessorRunTest`「deleting a file resource leaves the file where it is」直接建構 `ResourceRemoval`，加傳 `h.behaviors`（建構子新增參數），斷言未改。

**WI-51 的測試（未修改）。** `PackagedResourceRaceTest`「deleting a jdbc-pool resource closes the connections of its pool」與「after the same races no request or connection is left once the resources are deleted」通過；同一類別的「after the same races every class loader of a run is reclaimed」仍失敗（created 27、reclaimed 26），屬 WI-66。

**未做到或保留的點。**

- 驗收條件「關閉連線失敗時……失敗寫入 log」：以真實元件無法使關閉失敗或卡住（驅動不等待資料庫、吞掉 I/O 錯誤），因此沒有可寫入 log 的失敗，也沒有對應的測試與程式；`JdbcConnectionPool.close` 對個別連線 `close` 的例外仍以 `runCatching` 忽略（既有行為，未改）。若要求即使驅動拋出也記錄，需架構決定是否在沒有真實重現方式的情況下加入。
- 資料庫在刪除時無回應、之後也一直不恢復時，連線在資料庫端要等 TCP 層的逾時（keepalive 等，非 Engine 組態）才消失；本項只驗證了恢復後立即消失。

**驗證。** `./gradlew cleanTest :engine:cleanPackagedTest :engine:cleanConsoleTest :engine:cleanConsoleTypecheck :engine:cleanConsoleApiDocCheck check --continue` 1 次：唯一的失敗是上述 WI-66 的 1 個 packagedTest。accessors 356（21 個跳過，同 WI-57）、analyzer 133、core 89、devkit 184、runner 58、engine 1138（1 個跳過，同 WI-57）、packagedTest 104 中 103 通過；Console 測試、型別檢查、API 文件檢查與 `ktfmtCheck` 通過。
