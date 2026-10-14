# ADR-055 thinking 的取樣政策、生成上限、分開的逾時與及早止損

本文回答：開啟 thinking 時要送什麼取樣參數與額度、每個模型請求的生成上限、逾時如何分設、同一原因連續失敗時何時停止。狀態：已核可（2026-10-14）。補充 stable 的 `modelCallTimeoutSeconds` 與截斷處理（WI-1426、WI-1432）；取代 [ADR-049](../../../stable/transcribe-pipeline/adr/ADR-049-batches-with-read-only-context.md) 中「重試用盡時對半拆開再試」的作法。決策 4 的適用範圍見 [ADR-057](ADR-057-asr-endpoints-and-alignment-word-timing.md)。

## 背景

run 8（[02](../02-current-state.md)）顯示：

- thinking 配 temperature 0.3 時，gemma4 推理進入迴圈，任何額度都會用盡；
- 生成上限「字元數加 1024」不足以容納推理；
- 截斷後對半拆批，上限隨之變小，必然再次失敗；
- 511 次呼叫全數失敗，run 仍持續 3 小時；
- 轉錄（應在 1 秒內完成）與對話階段共用 180 秒的逾時；
- 推理內容與 5xx 的狀態都沒有記錄，無從診斷。

WI-1451 在 arr 的量測另外顯示：

- 一個 30 秒的雜音片段送 Qwen3-ASR 後，模型持續生成，用戶端在 5 分鐘後逾時；
- 逾時後 Lemonade 仍回報忙碌超過 6 分鐘，表示用戶端斷線不會停止伺服器端的生成；
- 轉錄請求沒有生成上限。Lemonade 一次只服務一個請求，資源容量為 1，所以一次失控的生成會擋住後續所有呼叫，包括其他 pipeline 的呼叫。

## 決策

1. **thinking 預設關閉**：所有 gemma4 階段各有 thinking 開關，預設關閉。關閉的作法沿用現行（預填已結束的推理）。
2. **開啟時的取樣**：送 `thinkingTemperature`（預設 1.0）取代該階段的 temperature；另送推理額度 `thinkingTokenBudget`，與答案的生成上限相加作為該次的 `max_tokens`。資源鎖定 temperature 時，log 提示 thinking 的取樣參數無法生效。
3. **截斷不拆批**：單元（片段）重試用盡即降級，不再拆小重試。
4. **轉錄的生成上限**：每個走 chat 端點的轉錄請求都帶生成上限，依該次音訊的秒數計算：`asrTokensPerSecond` 乘以秒數，再加 `asrTokenMargin`。範圍包含長片段、現行的短段與補掃。
   - 這是讓伺服器端停止失控生成的唯一手段。逾時只結束用戶端的等待，不保證伺服器停止。
   - 走 `audio.transcriptions` 的模型沒有生成上限：OpenAI 的轉錄協定沒有這個欄位，Engine 資源也不放行多送的欄位。這個端點只受 `asrCallTimeoutSeconds` 保護，文件須明說這項限度。
   - Qwen3-ASR 固定走 chat 端點，所以預設配置的每個轉錄請求都有生成上限。
   - 達到上限的回答視為截斷，依重試、降級與止損規則處理，不採用其文字。
   - 預設值須涵蓋快速對白的正常輸出。依據是 WI-1452 實測的「每秒音訊的輸出 token 數」最大值，再留餘裕。
   - 所有 chat 模型請求都必須帶生成上限。對話階段已有上限；thinking 開啟時依決策 2 計算。
5. **分開的逾時**：轉錄的單次逾時 `asrCallTimeoutSeconds` 與對話階段的 `modelCallTimeoutSeconds` 分開設定，都只能縮短資源的逾時。
6. **及早止損**：一個階段內，連續 `earlyStopFailures` 次（預設 5）呼叫都因同一原因失敗（例如截斷、逾時、5xx），且期間沒有任何成功時：
   - 立即停止該 run；
   - run 以專屬的失敗分類結束，訊息指出階段、原因與次數；
   - 已完成的資料保留，不寫字幕檔。

   掃描模式把此失敗計入該影片的失敗次數（沿用 ADR-040）。
7. **可觀測**：EXCHANGE 記錄推理內容與推理 token 數、上游的 HTTP 狀態（含 5xx）；階段結果行記錄推理 token 總數。

## 取捨

- 得到：
  - 系統性的失敗在數分鐘內浮現，不再耗費數小時；
  - 開 thinking 的情況有可用的取樣參數；
  - 轉錄逾時可以短而不傷及對話階段；
  - 失控的 chat 轉錄在數秒內被伺服器截止，不再佔住 Lemonade。
- 失去：
  - 偶發的連續失敗也可能觸發止損（以門檻與「同一原因」降低機率）；
  - 推理內容增加資料量（依保留期清除）；
  - 生成上限設得太低時，語速極快的片段會被截斷而降級（以實測值加餘裕降低機率）；
  - 換成走 `audio.transcriptions` 的轉錄模型時，失控生成只能靠逾時結束用戶端的等待，伺服器端不一定停止。

## 後果

- 新增參數：`thinkingTemperature`、`thinkingTokenBudget`、`asrCallTimeoutSeconds`、`earlyStopFailures`、`asrTokensPerSecond`、`asrTokenMargin`。
- 新增失敗分類：止損（名稱由實作決定，須在 runbook 的排錯表列出）。
- 決策 1、2、3、5、6、7 由 WI-1453 實作。決策 4 由 WI-1461 實作，量測工具（WI-1451、WI-1452）也套用同樣的上限。
- 本 ADR 與長片段、場景的改動無相依，可先行實作。
