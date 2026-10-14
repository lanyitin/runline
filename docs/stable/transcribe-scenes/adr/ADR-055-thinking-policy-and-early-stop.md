# ADR-055 thinking 的取樣政策、分開的逾時與及早止損

本文回答：開啟 thinking 時要送什麼取樣參數與額度、逾時如何分設、同一原因連續失敗時何時停止。狀態：已核可（2026-10-14）。補充 stable 的 `modelCallTimeoutSeconds` 與截斷處理（WI-1426、WI-1432）；取代 [ADR-049](../../../stable/transcribe-pipeline/adr/ADR-049-batches-with-read-only-context.md) 中「重試用盡時對半拆開再試」的作法。

## 背景

run 8（[02](../02-current-state.md)）顯示：

- thinking 配 temperature 0.3 時，gemma4 推理進入迴圈，任何額度都會用盡；
- 生成上限「字元數加 1024」不足以容納推理；
- 截斷後對半拆批，上限隨之變小，必然再次失敗；
- 511 次呼叫全數失敗，run 仍持續 3 小時；
- 轉錄（應在 1 秒內完成）與對話階段共用 180 秒的逾時；
- 推理內容與 5xx 的狀態都沒有記錄，無從診斷。

## 決策

1. **thinking 預設關閉**：所有 gemma4 階段各有 thinking 開關，預設關閉。關閉的作法沿用現行（預填已結束的推理）。
2. **開啟時的取樣**：送 `thinkingTemperature`（預設 1.0）取代該階段的 temperature；另送推理額度 `thinkingTokenBudget`，與答案的生成上限相加作為該次的 `max_tokens`。資源鎖定 temperature 時，log 提示 thinking 的取樣參數無法生效。
3. **截斷不拆批**：單元（片段）重試用盡即降級，不再拆小重試。
4. **分開的逾時**：轉錄的單次逾時 `asrCallTimeoutSeconds` 與對話階段的 `modelCallTimeoutSeconds` 分開設定，都只能縮短資源的逾時。
5. **及早止損**：一個階段內，連續 `earlyStopFailures` 次（預設 5）呼叫都因同一原因失敗（例如截斷、逾時、5xx），且期間沒有任何成功時：
   - 立即停止該 run；
   - run 以專屬的失敗分類結束，訊息指出階段、原因與次數；
   - 已完成的資料保留，不寫字幕檔。

   掃描模式把此失敗計入該影片的失敗次數（沿用 ADR-040）。
6. **可觀測**：EXCHANGE 記錄推理內容與推理 token 數、上游的 HTTP 狀態（含 5xx）；階段結果行記錄推理 token 總數。

## 取捨

- 得到：
  - 系統性的失敗在數分鐘內浮現，不再耗費數小時；
  - 開 thinking 的情況有可用的取樣參數；
  - 轉錄逾時可以短而不傷及對話階段。
- 失去：
  - 偶發的連續失敗也可能觸發止損（以門檻與「同一原因」降低機率）；
  - 推理內容增加資料量（依保留期清除）。

## 後果

- 新增參數：`thinkingTemperature`、`thinkingTokenBudget`、`asrCallTimeoutSeconds`、`earlyStopFailures`。
- 新增失敗分類：止損（名稱由實作決定，須在 runbook 的排錯表列出）。
- 本 ADR 與長片段、場景的改動無相依，可先行實作（WI-1453）。
