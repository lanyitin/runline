# WI-1453 thinking 取樣政策、分開的逾時與及早止損

本文回答：開啟 thinking 時送什麼、轉錄與對話的逾時如何分開、何時及早停止、要多記錄什麼。狀態：已核可（2026-10-14）。相依：無。決策見 [ADR-055](../adr/ADR-055-thinking-policy-and-early-stop.md)。儲存庫：T。

## 行為與驗收條件

1. **thinking 關閉**（各階段預設）：請求與現行相同（預填已結束的推理）。
2. **thinking 開啟**：
   - 請求的 temperature 改為 `thinkingTemperature`（預設 1.0）；
   - `max_tokens` 為答案的生成上限加 `thinkingTokenBudget`；
   - 資源鎖定 temperature 時，log 一行提示 thinking 的取樣參數無法生效。
3. **截斷不拆批**：重試用盡時該單位直接降級，不再對半拆開；拆批相關的 log 與計數移除。
4. **分開的逾時**：轉錄（含補掃，若仍存在）使用 `asrCallTimeoutSeconds`，對話階段使用 `modelCallTimeoutSeconds`；兩者都只能縮短資源的逾時。
5. **及早止損**：同一階段連續 `earlyStopFailures`（預設 5）次呼叫都因同一原因失敗，且期間沒有成功時：
   - run 立即以專屬的失敗分類結束，訊息指出階段、原因與次數；
   - 已完成的資料保留，不寫字幕檔。

   重現 run 8 的情境（每次都截斷）時，潤飾在 5 次呼叫內停止。
6. **紀錄**：
   - EXCHANGE 記錄推理內容與推理 token 數（模型有回報時），以及上游 HTTP 狀態（含 5xx）；
   - 對話階段的結果行與 RUN SUMMARY 列出推理 token 總數。
7. 新參數的驗證與錯誤訊息比照現有參數；README 的參數表與 runbook 的排錯表更新。

## 架構約束

- 本項不改分段、場景與各階段的單位，只改呼叫政策與紀錄；與 WI-1455 起的改動相容。
- 結構只增不改。
- 嚴格 TDD；測試不使用 Stub 或 Mock（模型服務使用既有的假服務）。
