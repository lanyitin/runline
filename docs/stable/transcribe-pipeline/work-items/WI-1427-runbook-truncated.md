# WI-1427 runbook 與資料模型補上 `TRUNCATED` 與 `exchange.finish_reason`

本文回答：手冊與資料模型要補哪些內容，管理員才能診斷「模型因 token 上限被截斷」。狀態：已核可（2026-10-12）。相依：WI-1426（已完成）。儲存庫：runline（只改文件）。

## 背景

WI-1426 讓 pipeline 讀取並記錄 `finish_reason`，把 `length` 視為被截斷的專用原因 `TRUNCATED`，各階段結果行新增 `truncated=N`，資料庫結構升為版本 4（`exchange.finish_reason`）。runbook 與資料模型尚未涵蓋。

## 行為與驗收條件

1. `runbook.md` 第 10 節：`TRUNCATED` 列在 `NOT_SRT`、`COUNT_CHANGED`、`TIME_CHANGED` 旁，說明其意義與對應的 log 行（重試與降級各一種）。
2. 第 11 節：階段結果行結尾的 `truncated=N` 的讀法（計入所有被截斷的嘗試，含之後重試成功者）。
3. 第 15 節：排錯項目「大量 `TRUNCATED`」：原因是 `llm` 資源 `defaults` 的 `max_tokens` 太小（thinking 模型把額度用在推理而回空字串）；處置是調高 `max_tokens` 或 `llmThinking=off`；查詢哪些呼叫被截斷（依 `exchange.finish_reason`）。第 5.1 節 `settings.defaults` 的「太小會截斷答案」連到這個診斷。
4. 第 16 節：已驗證／未驗證清單如實加入（以 FakeOpenAiServer 驗證三種情境；真實 Lemonade 的 `length` 情境未驗證）。
5. `06-data-model.md`：`exchange.finish_reason` 欄位與版本 4 的遷移；`exchange.outcome` 仍是封閉的四個值，截斷原因記在 `finish_reason` 與 log，不是 outcome。
6. 同一資訊只寫一處，其他處以連結引用；文件用現在式、宣告式。

## 架構約束

- 只改文件，不改程式碼；以 `runline-transcribe` 的 README 與程式為事實來源（唯讀）。
- 不修改 runbook 中其他測試所依賴的查詢內容。
