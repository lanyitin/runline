# WI-59 Engine：版本刪除的影響預覽與確認後刪除

本文回答：Engine 要達成哪些行為，管理員才能預覽並確認刪除有 run 的版本。狀態：已核可（2026-10-13）。相依：無。契約見 [05-ipc](../05-ipc.md)，決策見 [ADR-047](../adr/ADR-047-delete-version-with-runs.md)。

## 行為與驗收條件

- 預覽端點依契約回傳 run 總數與依狀態分布、進行中筆數、時間範圍、log 筆數、仍引用的 trigger、內容是否會移除、是否可刪；只有管理員可用；版本解析（`uploader`、`ambiguous_version`、404）與既有版本端點一致；預覽不改變任何資料。
- 有終態 run、沒有 trigger、沒有進行中 run：帶與預覽相符的 `runs` 刪除成功（204），該版本的 run、log、definition 與版本都消失；觸發紀錄仍在，其 run 參照被清除；其他上傳者的同內容版本及其 run 不受影響；內容依既有規則在最後一個參照被刪時移除。
- 有終態 run 而未帶 `runs`：409 `runs_exist` 並帶 `total`，什麼都沒刪。帶的數量與當下不符：409 `impact_changed` 並帶目前 `total`，什麼都沒刪。沒有 run 時不帶 `runs` 照常刪除。
- 仍有 trigger：409 `in_use` 並列出 trigger；有進行中 run：409 `runs_active` 並帶筆數；判定順序依契約；以上皆什麼都沒刪。`in_use` 不再因 run 而回。
- 刪除與建立 run 互斥：並行測試下，不會出現指向已刪 definition 的 run；刪除成立後建立 run 的結果與版本不存在相同。
- 刪除在單一交易內完成；任何一步失敗則全部不變。資料庫外鍵維持「拒絕刪除」。
- 刪除成功與被拒絕時依契約記錄稽核資訊。
- 以大量 run（至少數萬筆，含 log）量測預覽與刪除的耗時並記錄於回報；若耗時不可接受，回報並停下來請架構決定，不自行改為分批。
- 文件同步：stable 的 `08-api`（新端點與 `DELETE` 的新行為，滿足 `ApiDocumentationTest`）、`06-data-model` 的版本刪除條款、ADR-020 決策 8 的指向，與本項一致，單一事實來源只寫一處。

## 架構約束

- 不新增資料表或欄位；不把外鍵改為連鎖刪除。
- 不連帶刪除 trigger，不自動取消 run。
- 不改變其他 API 的行為與錯誤碼。
- 測試使用真實 PostgreSQL（Testcontainers），不使用 Stub 或 Mock。
