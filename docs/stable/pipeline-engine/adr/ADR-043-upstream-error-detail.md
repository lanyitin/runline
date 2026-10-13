# ADR-043 4xx 的上游錯誤說明有限度地交給 pipeline

本文回答：`openai-compatible` 資源收到模型服務的 4xx 時，管理員如何在 run 的失敗訊息看到原因，同時不破壞「錯誤不帶系統訊息」的安全原則。狀態：已核可（2026-10-13）。放寬 [ADR-019](ADR-019-typed-shared-resources.md) 第 181 行與 [05-ipc](../05-ipc.md) 第 50 行「經 HTTP 的型別只附狀態碼，不含服務回的訊息或本文」；兩處的描述隨 WI-1430 改為指向本文，以本文為準。

## 背景

arr 上的轉錄階段收到 Lemonade 的 HTTP 400，失敗訊息只有 `REQUEST_REJECTED (HTTP 400)`。服務的回應本文已明確說明原因（模型不支援該端點），但存取端在非 2xx 時丟棄本文，pipeline 與管理員因此必須對服務直接重現才能得知。

## 決策

1. 只在 `openai-compatible` 型別、回應狀態為 4xx 時，存取端讀取回應本文的有上限前段（上限由存取端決定，不超過數 KB），嘗試以 OpenAI 相容的錯誤格式解析。
2. 只取兩個欄位：`error.type` 與 `error.message`。解析失敗或沒有這兩個欄位時，不取本文的其他部分，說明為空。
3. 取出的兩個字串各有長度上限（`error.message` 300 字元、`error.type` 64 字元），超過者截斷並標明；只保留可列印文字，控制字元與換行去除。
4. 取出後套用既有的機密遮蔽（對已知機密值做字串比對）；遮蔽是盡力而為，不是主要防線。主要防線是「只取兩個欄位、有上限」。
5. 兩個字串作為 `ResourceAccessException` 與其跨越 `ResourceLink` 的表示的新欄位，與既有的 `status` 並列；只用 JDK 型別。5xx、逾時、連線失敗、2xx 不帶這兩個欄位，行為不變。
6. 完整本文不傳給 pipeline、不寫入 run log；Engine log 的既有行為不變。
7. pipeline 可據 `error.type` 判斷「重試不會改變結果」的類別，判定規則由各 pipeline 自己決定，Engine 不做。

## 取捨

| 選項 | 優點 | 缺點 |
|---|---|---|
| A. 兩個欄位加上限（採用） | 診斷成本大幅降低；攻擊面小，輸出有界 | 放寬一條已核可的安全規則；服務可能在 `error.message` 回顯輸入片段 |
| B. 維持現狀，由 runbook 說明如何直接重現 | 零風險 | 每次問題都要人工重現 |
| C. A 加資源層級開關，預設關閉 | 預設最安全 | 管理員要記得打開；多一個設定項 |

## 後果

- 風險：模型服務的錯誤說明可能含使用者提示的片段。緩解：長度上限、可列印文字、機密遮蔽；說明只進入該 run 的失敗訊息與該次呼叫的 `exchange` 紀錄，不進 Engine 的一般 log。
- 影響面：`core` 的 `ResourceAccessException`、`accessors` 的 `openai-compatible`、Engine 與 run 之間的 IPC 契約（新增兩個欄位，舊的 run 與新的 Engine 彼此相容：欄位缺漏視為空）、`devkit` 的對應表示。
- 其他資源型別（`jdbc-pool` 等）不變。
- 文件：ADR-019 與 05-ipc 的相關描述改為指向本文。
