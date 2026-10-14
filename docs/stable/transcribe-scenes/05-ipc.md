# 5 IPC 模型

狀態：已核可（2026-10-14）。本文回答：pipeline 與模型服務、ffmpeg 之間傳什麼、怎麼處理失敗。所有模型呼叫都經 Engine 的 `openai-compatible` 資源（容量 1），上游為 llm-gateway（[ADR-056](adr/ADR-056-alignment-on-gateway-gpu.md)），見 [stable transcribe-pipeline 05](../../stable/transcribe-pipeline/05-ipc.md)；本文只寫差異。

## 呼叫一覽

| 對象 | 方式 | 單位 | 傳遞的資料 | 失敗處理 |
|---|---|---|---|---|
| Lemonade：轉錄 | `chat.completions`（同步），音訊輸入；Qwen3-ASR 固定走此端點（沿用 [ADR-042](../../stable/transcribe-pipeline/adr/ADR-042-asr-by-model-labels.md) 的端點選擇，[ADR-057](adr/ADR-057-asr-endpoints-and-alignment-word-timing.md)） | 長片段 | 原始音訊的片段（約 30 秒）→ 語言與文字；請求帶生成上限 | 逾時、5xx、截斷：重試；用盡則該片段沒有文字，計入降級；連續失敗時止損（[ADR-055](adr/ADR-055-thinking-policy-and-early-stop.md)） |
| gateway：對齊 | `audio.transcriptions`（同步，multipart），模型為對齊模型 | 長片段；全部轉錄完成後依序送出 | 原始音訊的片段、語言、轉錄文字（放在 `prompt`）、`response_format` 為 `verbose_json` → 全文與每個字詞的文字、起點、終點（秒） | 逾時、5xx、服務不可用、格式不符：重試；用盡則該片段改用估計時間（[ADR-051](adr/ADR-051-long-chunks-and-forced-alignment.md)），計入降級；連續失敗時止損 |
| ffmpeg：場景偵測 | 本機子行程 | 整部影片一次 | 影片 → 場景切點與分數 | 失敗則整部影片視為一個場景，記錄並計入降級 |
| ffmpeg：畫面 | 本機子行程（pipe） | 場景片段 | 時間點 → 圖片（只在記憶體） | 取不到畫面時，該片段不附圖照常送出 |
| Lemonade：潤飾 | `chat.completions`，文字＋多張圖 | 場景片段 | 規則、片段的條目、畫面、前一片段的唯讀語境 → 條目（含行與換人標記） | 契約違反或截斷：重試；用盡則該片段保留轉錄文字、不標換人，計入降級 |
| Lemonade：片段摘要 | 同上 | 場景片段 | 規則、潤飾後的條目、畫面 → 片段摘要 | 用盡則該片段缺摘要，計入降級 |
| Lemonade：場景與全片摘要 | `chat.completions`，純文字 | 場景、全片 | 片段摘要 → 場景摘要；場景摘要 → 全片摘要與角色表 | 同上 |
| Lemonade：翻譯 | `chat.completions`，純文字 | 場景片段 | 規則、角色表、場景摘要、已翻好的前文、條目（含行與換人標記）→ 繁體中文條目 | 契約違反：重試；用盡則該片段保留來源語言文字，計入降級 |

Lemonade 的呼叫都經 gateway 原樣轉發。

## 契約要點

- **條目不變式**：組成條目之後，任何回答都必須保留條目的數量、序號與時間；潤飾與翻譯另須保留每條的行數上限（2 行）與換人標記的位置規則（[ADR-053](adr/ADR-053-polish-with-frames-and-speaker-turns.md)）。
- **換人在回答中的表示**：回答的每一行若是新說話者的開頭，以行首的對話破折號表示；pipeline 解析後存成資料、從文字中拿掉，輸出時才依參數畫回。
- **唯讀語境**：語境只供理解，回答中出現語境內容視同契約違反。
- **冪等**：每次呼叫只讀取資料、回傳結果，重送不會產生副作用；寫入由 pipeline 在收到合格回答後以交易完成。對齊同一片段重送得到同樣的結果。
- **轉錄的生成上限**：
  - chat 端點的每個轉錄請求都帶生成上限（[ADR-055](adr/ADR-055-thinking-policy-and-early-stop.md) 決策 4）；
  - OpenAI 的轉錄協定沒有這個欄位，Engine 資源也只放行 `model`、`language`、`prompt`、`response_format`、`temperature`，所以走 `audio.transcriptions` 的模型沒有生成上限，只受 `asrCallTimeoutSeconds` 保護。
- **對齊的請求**：只使用 Engine 資源放行的欄位：音訊檔、`model`、`language`、`prompt`（逐字稿）、`response_format`（`verbose_json`）。不送 `timestamp_granularities`。
- **對齊的回應**：
  - 比照 OpenAI 音訊轉錄的 `verbose_json` 與字詞時間戳格式；對齊模型沒有「只回全文」的模式，`verbose_json` 的回應固定含字詞時間；
  - 字詞依時間排序，時間落在該片段的長度內；
  - 字詞串接後與送出的文字一致，空白與標點的處理寫在 gateway 的 README；
  - 不符合時視同格式不符。
- **GPU 切換**：對齊請求可能使 gateway 先卸載 Lemonade 的模型；轉錄完成前不送對齊，對齊全部完成前不送對話，使一個 run 只切換兩次。
- **請求大小**：
  - 30 秒的單聲道 16 kHz WAV 約 1 MB，base64 後約 1.3 MB；對齊以 multipart 上傳，約 1 MB；
  - 潤飾的請求含最多 `framesPerSegment` 張、長邊 `frameMaxEdgePixels` 的圖；
  - 都必須在資源的 `maxRequestBytes` 之內。過大時，轉錄縮短該片段後重切，潤飾與摘要縮小圖片邊長（沿用現行的縮圖機制）。WI-1452 量測實際大小。
- **逾時**：轉錄與對齊使用 `asrCallTimeoutSeconds`，對話階段使用 `modelCallTimeoutSeconds`（[ADR-055](adr/ADR-055-thinking-policy-and-early-stop.md)），都只能縮短資源的逾時。對齊的第一個請求包含 GPU 切換與模型載入的時間，逾時須容納。
- **版本演進**：回答格式的標記（例如摘要的 `CHARACTERS:`、`SUMMARY:`）由程式解析，system prompt 參數可覆寫，但不得刪去標記；規則同現行的 system prompt 參數。

## 呼叫次數（一小時影片，估算）

| 項目 | 次數 |
|---|---|
| 轉錄 | 約 120（有語音的片段，扣除整段無語音者） |
| 對齊 | 與轉錄成功同數（gateway，GPU） |
| 潤飾、片段摘要、翻譯 | 各約 300（3600 秒 ÷ 12 秒；沒有條目的片段不送潤飾與翻譯） |
| 場景摘要 | 場景數 |
| 全片摘要 | 1 |
| GPU 切換 | 2（轉錄後切到對齊，對齊後切回 Lemonade） |

耗時估算與 3 小時目標的關係見 [07](07-nfr-risks.md)。
