# WI-1426 模型因 token 上限被截斷時，明確辨識並報告

本文回答：thinking 模型的 `max_tokens` 不足時，pipeline 如何不把「空回答」誤判為格式錯誤。狀態：已核可（2026-10-12）。相依：無。儲存庫：runline-transcribe（若需 runline 的 `openai-compatible` 存取端回傳 `finish_reason`，先確認再回報，不自行修改 runline）。

## 背景

arr 上的 Lemonade 是 thinking 模型；`max_tokens` 不足時回空字串並帶 `finish_reason=length`，不是錯誤。目前 pipeline 不讀 `finish_reason`（程式搜尋無結果），空回答會被當成「不是 SRT」或「條目為空」而重試、降級，原因分類不指向真正的原因。

## 行為與驗收條件

1. 每次模型呼叫的 `finish_reason` 被讀取並記入 `exchange`（沿用前向、冪等、記錄版本的遷移規則）；若存取端目前不回傳，先確認 runline 的存取端是否提供，不提供則停下來回報。
2. `finish_reason=length` 的回答被視為「被截斷」：不論內容是否為空，都不當成有效回答；分類為專用原因（與 `NOT_SRT`、`COUNT_CHANGED`、`TIME_CHANGED` 並列），重試規則同其他不符合契約的回答，用盡後該單位降級並在 log 與 `exchange.outcome` 留下此原因。
3. log 的 RUN SUMMARY 或降級統計中可看出被截斷的次數，管理員能據此判斷要調高 `max_tokens`（`llm` 資源的 `defaults`）或關閉 thinking；runbook 的排錯以此為依據（由後續文件工作項處理，本項列出需要的修改）。
4. 以真實 `FakeOpenAiServer` 的情境驗證：回空字串加 `length`、回部分文字加 `length`、回正常文字加 `stop`，三種結果各有明確、不同的處置；既有測試維持通過。
5. 薄 jar 約束不變；測試不使用 Stub 或 Mock；嚴格 TDD。

## 架構約束

- 不改變 `finish_reason` 為 `stop` 或缺少時的任何行為。
- 不新增參數。
