# ADR-042 轉錄依模型的 labels 選擇端點

本文回答：`asrModel` 指向的模型不是轉錄型時，pipeline 如何仍能轉錄。狀態：已核可（2026-10-13）。補充 [ADR-038](ADR-038-segment-model-and-stage-invariants.md) 與入口文件中「轉錄走 `audio.transcriptions`」的假設，不取代任何其他已核可的決策。

## 背景

arr 的 Lemonade 上，`Qwen3-ASR-1.7B-GGUF-Q8_0` 是以 llamacpp 載入的 chat 型模型（labels 為 `chat,custom,vision`，沒有 `transcription`）。Lemonade 的轉錄端點只接受轉錄型 recipe，對該模型回 400「不支援」。現行 pipeline 一律走 `audio.transcriptions`，因此該模型不可用。使用者要用這個模型，並希望 pipeline 依模型能力自動選擇端點。

## 決策

1. 每個 run 在轉錄階段開始前，用 `llm` 資源的 `models.list` 取得 `asrModel` 對應項目的 `labels`。
2. `labels` 含 `transcription`：走 `audio.transcriptions`（現行行為，不變）。
3. `labels` 不含 `transcription`：改走 `chat.completions`，以音訊輸入（段落的 WAV，OpenAI 標準的 `input_audio`）請求，取回文字。請求不含文字指示（實測無效果）。
4. 模型清單可取得，但找不到 `asrModel`，或項目沒有可用的 `labels`：以 `CONFIGURATION` 失敗，訊息指出模型名稱與看到的 labels。模型清單本身取不到（端點未啟用、5xx、403 或 404、回答不是清單、清單為空）：沒有依據可選，沿用 `audio.transcriptions`，並在 log 留下一行說明；若 `asrModel` 實為 chat 型，第一個請求以 4xx 失敗，說明可見（[ADR-043](../../pipeline-engine/adr/ADR-043-upstream-error-detail.md)）。
5. 選擇結果每個 run 只決定一次，記入 run 的紀錄與 log（使用的端點、依據的 labels）。
6. 兩條路徑的外部契約相同：逐段循序、一次一個請求、`llm` 資源容量 1、條目時間不變、嚴格的 [ADR-038](ADR-038-segment-model-and-stage-invariants.md) 不變式、重試與降級規則、`TRUNCATED` 處理（WI-1426）、4xx 說明（WI-1428）。

## 取捨

| 選項 | 優點 | 缺點 |
|---|---|---|
| A. 依 labels 自動選擇（採用） | `asrModel` 一個參數即可；換模型不改設定；Whisper 與 Qwen3-ASR 都能用 | 多一次 `models.list` 呼叫；依賴 Lemonade 的 `labels` 欄位（非 OpenAI 標準） |
| B. 新增參數 `asrEndpoint` 由管理員指定 | 不依賴 `labels`，行為明確 | 管理員要懂端點差異；設錯才失敗；多一個參數 |
| C. 只支援轉錄型模型 | 不需改動 | Qwen3-ASR 不可用，與使用者目標衝突 |

## 後果

- `labels` 是 Lemonade 的擴充欄位；清單可取得但沒有 `labels` 的 OpenAI 相容服務，決策 4 使其以 `CONFIGURATION` 失敗，要支援這類服務須另開決策（例如 B 的參數）。清單取不到時維持舊行為，使未啟用 `models.list` 的既有部署不受影響。
- chat 路徑的音訊輸入採 OpenAI 標準的 `input_audio`（純 base64 加 `format`）。阿里雲 DashScope 的 data URL 寫法與 `asr_options` 不採用：前者被 Lemonade 的 llama-server 拒絕，後者被接受但無效果。
- 回答格式為 `language <語言名或 None><asr_text><文字>`，pipeline 負責剖析；`language None` 加空文字表示該段沒有語音。語言提示（`asrLanguage`）在這條路徑無法生效，log 說明。偵測到的語言名轉為語言標籤（對應不到者為 `und`）；字幕檔名的語言優先取摘要階段的語言（可帶 `zh-TW`），沒有時才用偵測到的語言。
- `labels` 只能回答「能否走轉錄端點」，不能回答「chat 能否吃音訊」：Qwen3-ASR 與 Gemma 的 labels 都是 `chat,custom,vision`。因此 `asrModel` 若誤指向不吃音訊的 chat 模型，不會在選擇端點時被發現，而是在第一次請求以 4xx 或不符契約的回答失敗，由 WI-1428 的上游錯誤說明讓原因可見。
- 已量測的範圍與未驗證項見 [WI-1429](../work-items/WI-1429-asr-endpoint-by-labels.md) 的事實 0。
- chat 路徑佔用 Lemonade 的 llm 槽（上限 1）。pipeline 在轉錄與潤飾之間切換 Qwen3-ASR 與 Gemma，會有模型換入換出的耗時；與 pi 並用時更明顯，屬 Lemonade 並發問題（待決）。
- 轉錄回答沒有逐字時間戳；條目時間來自 VAD-B 段落，不受影響。
- 文件中「qwen-asr」的稱呼改為「轉錄模型」，說明兩種端點（[WI-1431](../work-items/WI-1431-docs-asr-endpoint.md)）。
