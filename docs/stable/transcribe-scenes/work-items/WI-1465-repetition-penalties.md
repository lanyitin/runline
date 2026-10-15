# WI-1465 重複懲罰：轉錄截斷後重試、摘要的可選懲罰

本文回答：如何以重複懲罰參數處理轉錄與摘要的生成迴圈。狀態：架構師依授權決定（2026-10-15），待使用者回顧。相依：WI-1463、WI-1464。決策見 [ADR-061](../adr/ADR-061-repetition-penalties-on-loops.md)。儲存庫：T。

## 行為與驗收條件

1. **轉錄的懲罰重試**：片段被生成上限截斷時，同一片段再試一次，這次請求帶 `repeat_penalty` 與 `presence_penalty`（值取自 `asrRetryRepeatPenalty`、`asrRetryPresencePenalty`）。
   - 首次嘗試不帶這兩個欄位；其他重試原因（逾時、5xx、格式不符）的行為不變。
   - 重試仍被截斷：片段降級，不再嘗試，不計入及早止損（ADR-059）。
   - 兩個參數皆空：不重試（等同 WI-1463）；只設其一：只送該欄位。
   - 重試成功：採用其文字，`transcribeFailed` 不增加；結果行與 RUN SUMMARY 另列「經懲罰重試救回」的片段數。
2. **摘要的可選懲罰**：`summaryRepeatPenalty`、`summaryFrequencyPenalty`、`summaryPresencePenalty` 有值時，三層摘要的每次呼叫都帶對應欄位；空值不送。潤飾與翻譯不送任何懲罰欄位。
3. **參數**：五個新參數的宣告、預設值（`asrRetryRepeatPenalty` 1.1、`asrRetryPresencePenalty` 0.3，摘要三者空值）、值域檢查（錯誤在 intake 以設定類失敗指出參數）與 README 說明。`summaryMaxTokens` 預設維持 4096。
4. **上游拒絕**：上游以 4xx 拒絕懲罰欄位時，該次嘗試記為失敗並保留原因，不計入及早止損；README 的排錯章節說明。
5. **紀錄**：EXCHANGE 能看出該次請求是否帶了懲罰欄位與值（不含機密）；log 的降級與救回片段列出片段編號。
6. 其餘行為（生成上限、逾時、對齊、場景、潤飾、翻譯、view）不變。

## 架構約束

- 不改 Engine；懲罰欄位以 pipeline 逐次呼叫送出，不寫入資源預設。
- 不新增環境變數或掛載。
- 嚴格 TDD；測試不使用 Stub 或 Mock，以假服務重現「首次截斷、帶懲罰後成功」「帶懲罰仍截斷」「上游拒絕欄位」三種情境，並驗證首次請求沒有懲罰欄位、潤飾與翻譯請求沒有懲罰欄位。
