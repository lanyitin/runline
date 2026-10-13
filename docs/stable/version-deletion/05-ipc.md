# 版本刪除的 API 契約

本文回答：影響預覽與版本刪除的端點行為、確認方式與錯誤碼。狀態：已核可（2026-10-13）。修訂 [08-api](../pipeline-engine/08-api.md) 的 `DELETE /api/v1/artifacts/{contentHash}`，並新增一個預覽端點。

## 預覽：`GET /api/v1/artifacts/{contentHash}/deletion-impact`

認證：Bearer（admin）；選填查詢參數 `uploader` 同既有版本端點的解析規則（`ambiguous_version`、404 `not_found`）。唯讀，不改變任何東西。

回應描述刪除這個版本的影響：

| 欄位 | 語意 |
|---|---|
| `runs.total` | 該版本所有 definition 的 run 總數 |
| `runs.byState` | 依 run 狀態的筆數；只列出有 run 的狀態，缺漏的狀態視為 0 |
| `runs.active` | 非終態（排隊、等待資源、初始化、執行中、逾時未結束）的筆數 |
| `runs.earliestAt`、`runs.latestAt` | run 建立時間的範圍；沒有 run 時省略 |
| `logEntries` | 這些 run 的 log 總筆數 |
| `triggers[]` | 仍指向該版本的 trigger：名稱與類型 |
| `contentRemoved` | 刪除後 jar 內容是否一併移除（沒有其他版本參照時為真） |
| `deletable` | 現在是否可刪（沒有 trigger 且沒有進行中的 run） |

## 刪除：`DELETE /api/v1/artifacts/{contentHash}`

選填查詢參數 `uploader`（不變）與 `runs`（整數：呼叫者在預覽中看到的 `runs.total`，表示確認連同這些 run 一併刪除）。

成功：204。版本、其 definition、所有終態 run 與其 log 在同一交易內移除；觸發紀錄保留並清除對這些 run 的參照。

判定順序與錯誤（先到先回，什麼都沒有刪除）：

| 狀態 | 條件 |
|---|---|
| 409 `ambiguous_version`、404 `not_found` | 不變 |
| 409 `in_use` | 仍有 trigger 指向該版本；本文多 `triggers[]`。run 不再是 `in_use` 的原因 |
| 409 `runs_active` | 有進行中的 run；本文含 `active` 筆數 |
| 409 `runs_exist` | 有終態 run 而未帶 `runs`；本文含 `total`。沒有 run 時不帶 `runs` 也能刪 |
| 409 `impact_changed` | 帶的 `runs` 與當下的 run 總數不符；本文含目前的 `total` |
| 400 `bad_request` | `runs` 不是整數或為負數；什麼都沒有刪除 |

帶了 `runs` 而當下 run 總數為 0 且相符，視為相符（可刪）。

## 並行與冪等

- 同一版本的建立 run 與刪除互斥；刪除成立後建立 run 的結果與版本不存在相同。
- 重複刪除已不存在的版本得到 404；不提供其他冪等語意。
- 預覽沒有狀態，不需要冪等處理。

## 稽核與可觀測性

刪除成功時記錄管理員名稱、版本（內容雜湊與上傳者）、刪除的 run 數與 log 筆數；被拒絕的刪除記錄原因類別。
