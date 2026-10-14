# ADR-057 轉錄的生成上限只適用 chat 端點，對齊回應固定含字詞時間

本文回答：OpenAI 轉錄端點不接受生成上限、Engine 資源不放行 `timestamp_granularities` 時，轉錄的生成上限與對齊的字詞時間如何落實。狀態：已核可（2026-10-14）。補充 [ADR-055](ADR-055-thinking-policy-and-early-stop.md) 決策 4 與 [ADR-056](ADR-056-alignment-on-gateway-gpu.md) 決策 2，不取代任何決策。

## 背景

- **生成上限**：OpenAI 的音訊轉錄協定沒有生成上限欄位。Engine 的 `audio.transcriptions` 條目只放行 `model`、`language`、`prompt`、`response_format`、`temperature`，多送欄位會被拒絕。
- **字詞時間**：OpenAI 以 `timestamp_granularities[]` 要求字詞時間，Engine 資源不支援該欄位（[WI-53](../../pipeline-engine/work-items/WI-53-openai-compatible-multipart-and-binary.md)），ADR-056 決策 2 與 05 卻假設請求可以要求字詞時間。
- **現行的端點選擇**：[ADR-042](../../transcribe-pipeline/adr/ADR-042-asr-by-model-labels.md) 依模型 labels 選端點。Qwen3-ASR 的 labels 不含 `transcription`，因此走 chat 端點，那裡可以帶 `max_tokens`。

## 決策

1. **生成上限只適用 chat 端點**：ADR-055 決策 4 的上限套用在 chat 端點的轉錄請求。走 `audio.transcriptions` 的模型沒有生成上限，只受 `asrCallTimeoutSeconds` 保護；文件須明說這項限度。
2. **Qwen3-ASR 固定走 chat 端點**：預設的轉錄模型不走 transcriptions 端點，因此預設配置的每個轉錄請求都有生成上限。
3. **對齊回應固定含字詞時間**：gateway 收到對齊模型、`response_format` 為 `verbose_json` 的請求時，不論有沒有 `timestamp_granularities`，回應都含全文與每個字詞的文字、起點、終點（秒）。對齊模型沒有「只回全文」的模式。
4. **對齊請求只用 Engine 放行的欄位**：音訊檔、`model`、`language`、`prompt`（逐字稿）、`response_format`（`verbose_json`）。`temperature` 不使用。
5. **Engine 不修改**：不放寬資源的放行欄位，也不新增欄位。

## 取捨

- 得到：Engine 與資源契約不變；對齊的呼叫路徑不依賴被拒絕的欄位；預設配置的轉錄都有生成上限。
- 失去：
  - 換成走 transcriptions 端點的轉錄模型（例如 Whisper）時，失控生成只能靠逾時結束用戶端的等待，伺服器端不一定停止；
  - 對齊模型的行為偏離 OpenAI（`verbose_json` 隱含字詞時間），契約只寫在 gateway 的 README；
  - 未來若要在 transcriptions 端點加上限，須先修改 Engine 資源，並另開決策。

## 後果

- ADR-055 決策 4、[05](../05-ipc.md) 與 [WI-1462](../work-items/WI-1462-gateway-alignment-endpoint.md) 依本決策修訂。
- [WI-1461](../work-items/WI-1461-asr-generation-cap.md) 的驗收條件 2 已涵蓋「transcriptions 端點不接受生成上限時在 README 註明並以測試證明送出的內容」，不需修改。
- 量測工具與 pipeline 對 transcriptions 端點的上限行為，維持 WI-1461 的現況。
