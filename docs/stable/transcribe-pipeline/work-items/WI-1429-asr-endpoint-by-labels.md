# WI-1429 轉錄依模型 labels 選擇 audio.transcriptions 或 chat.completions

本文回答：如何讓 `asrModel` 為 chat 型模型（如 Qwen3-ASR）時 pipeline 仍能轉錄（[ADR-042](../adr/ADR-042-asr-by-model-labels.md)）。狀態：已核可（2026-10-13）。相依：WI-1426（已完成）、WI-1428（先完成，使 4xx 說明可見）。儲存庫：runline-transcribe；若 runline 的 `openai-compatible` 存取端不能把含音訊輸入的 chat 請求與 `models.list` 的完整回應交給 pipeline，先回報，不自行修改 runline。

## 行為與驗收條件

0. **已量測的事實（2026-10-13，arr 上的 Lemonade 11.9.0，`Qwen3-ASR-1.7B-GGUF-Q8_0`，以此為實作依據；結果記入 `docs/feasibility-report.md`，如實標示範圍）**：
   - `/v1/models` 與 `/api/v1/models` 的每筆都含 `labels`（字串陣列）：Qwen3-ASR 為 `chat,custom,vision`，Gemma 為 `chat,custom,vision,tool-calling`，Whisper 系列為 `transcription,realtime-transcription`。
   - chat 的音訊輸入須用 OpenAI 標準寫法：`input_audio`，`data` 為純 base64，另帶 `format`（`wav`）。DashScope 的 data URL 寫法被拒（400 `Failed to load image or audio file`）。
   - 回答格式固定為 `language <語言名或 None><asr_text><文字>`。`language None` 加空文字表示沒有語音，不是錯誤。文字指示與 DashScope 的 `asr_options` 被接受但沒有效果。
   - 3 秒與 0.8 秒的真實語音都轉錄成功（日文），耗時 0.05 至 0.2 秒；`finish_reason` 為 `stop`。
   - 未驗證：長時間連續請求、非日文語言、`labels` 缺漏的服務、其他 chat 型模型接受音訊與否。
1. 轉錄階段開始前取得 `asrModel` 的 `labels`，依 ADR-042 決策 2 至 4 選擇端點；每個 run 只查一次。
2. `labels` 含 `transcription`：行為與現在完全相同（既有測試維持通過）。
3. `labels` 不含 `transcription`：每個 VAD-B 段落以 `chat.completions` 帶該段 WAV 送出，請求只有一則 user 訊息，內容為 OpenAI 標準的 `input_audio`（純 base64 加 `format`）。回答依事實 0 的格式剖析：取 `<asr_text>` 之後的內容為條目文字；前綴的語言名記為偵測到的語言；`language None` 加空文字視為該段沒有語音，依 [ADR-038](../adr/ADR-038-segment-model-and-stage-invariants.md) 對「空文字條目」的現行規則處理，不重試、不算失敗；回答不符此格式則視為不符合契約，依現行重試與降級規則處理。`asrLanguage` 在這條路徑無法生效（服務忽略語言提示），行為與文件須明說，不得默默忽略。偵測到的語言名要轉成語言標籤，供字幕檔名使用（[ADR-039](../adr/ADR-039-subtitle-files-beside-video.md) 的正規化；對應不到者為 `und`）。
4. 找不到模型或沒有 `labels`：run 於轉錄階段以 `CONFIGURATION` 失敗，訊息含模型名稱與看到的 labels。
5. 選擇的端點與依據的 labels 寫入 log 與 run 紀錄（run 層級，不重複存於每個條目）；`exchange` 記錄實際呼叫的端點。
6. 兩條路徑共用：逐段循序（同時請求數最多 1）、重試、降級、`TRUNCATED`、4xx 說明、`MODEL_IDENTITY` 檢查（chat 路徑的回答帶 `model`，可比對）、取消。
7. 以 `FakeOpenAiServer` 驗證：(i) labels 含 transcription 走轉錄端點，(ii) 不含走 chat 端點並清理回答，(iii) 找不到模型，(iv) 兩條路徑都遇到 4xx、5xx、截斷時的行為一致；FakeOpenAiServer 須能模擬帶 `labels` 的模型清單與音訊輸入的 chat 請求。
8. 薄 jar 約束不變；不新增參數；測試不使用 Stub 或 Mock；嚴格 TDD。
9. 文件（runline 儲存庫，另一個文件工作項處理，本項列出需要的修改）：runbook 的 `llm` 資源設定與 `asrModel` 說明、入口文件中的「qwen-asr」稱呼、`06-data-model.md` 的 run 層級記錄欄位。

## 架構約束

- 轉錄型模型的既有路徑不得改變行為。
- 不假設 Lemonade 以外的服務有 `labels`（見 ADR-042 決策 4）。
- 音訊只在記憶體，不寫檔、不進 log、不進 `exchange` 的 prompt（只記大小與雜湊）。
