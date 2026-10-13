# WI-1432 arr 首次 run 的兩項發現：潤飾呼叫的端點未記錄、摘要失控生成

本文回答：arr 首次完整 run（2026-10-13）暴露的兩個缺陷要達成什麼行為。狀態：已核可（2026-10-13）。相依：WI-1429、WI-1431（均已完成）。儲存庫：T（runline-transcribe）；文件部分在 R，併入同一項回報。

## 觀察（事實）

- run 成功：27 條、degraded=0，產出 `<影片>_ja.srt` 與 `<影片>_zh-TW.srt`，轉錄走 `chat.completions`，`language None` 為 0 次。
- 摘要階段 551.6 秒，佔整個 run 約 66%：第 20 組第一次嘗試產生 32,562 個 completion token 才以 `finish_reason=length` 結束（答案已完成後，模型繼續輸出自我註解）；重試 2.2 秒、14 個 token 成功。
- 潤飾階段 27 筆 `exchange.endpoint` 為空，其餘階段皆有值。

## 行為與驗收條件

1. 每一次模型呼叫（所有階段，含潤飾）的 `exchange.endpoint` 都記錄實際端點，與 [06-data-model.md](../06-data-model.md) 的 EXCHANGE 定義一致；新 run 不得再有空值。
2. 摘要階段（及其他只需短答的對話階段）單次呼叫的生成量有上限，超過時沿用既有 `TRUNCATED` 處理（重試、降級、計數），不得讓單一呼叫佔用數百秒。上限值由管理員可調，預設值須使上述情形在數十秒內被截斷；該參數寫入入口文件的參數表與 runbook（單一寫處）。
3. 單次呼叫的牆鐘時間另有上限（可調，預設有值），逾時視為可重試的失敗，並在 log 與 `exchange` 留下原因。兩個上限互相獨立，任一先到即生效。
4. 既有行為不變：條目時間、ADR-038 不變式、降級規則、其他階段的輸出。

## 架構約束

- 上限屬於 pipeline 參數或階段內部常數，不要求修改 Engine 或共享資源定義。
- 不降低既有階段的輸出品質；上限的預設值須有 arr 實測或 `FakeOpenAiServer` 的情境依據。
- 對真實 Lemonade 的驗證由 arr 上的第二次 run 完成。

## 核可的預設值

- `summaryMaxTokens`：4096，只加在摘要請求。
- `modelCallTimeoutSeconds`：180，套用到所有模型呼叫；逾時原因為 `TOTAL_TIMEOUT`。
