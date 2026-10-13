# 5 IPC 模型

狀態：已核可（2026-10-11）。本文回答：誰呼叫誰、傳什麼語意的資料、逾時與重試、錯誤如何傳遞。

## 通訊總覽

| 呼叫 | 方式 | 同步性 |
|---|---|---|
| 呼叫端 → Engine | webhook，HTTP POST，JSON 本文提供 `file` | 驗證後立即 202，run 非同步 |
| Engine cron → pipeline | cron trigger 建立 run，沒有呼叫端參數（`callerParameters` 一律為空） | 非同步；`file` 為空，pipeline 自己掃描收件根目錄（[ADR-040](adr/ADR-040-cron-scan-mode.md)） |
| pipeline → ffprobe／ffmpeg | 行程，參數逐一傳遞（不經 shell） | 等結束 |
| pipeline → 降噪工具 | 行程，同上；只在 `denoise=true` 時，位於 VAD 工具之前 | 等結束 |
| pipeline → VAD 工具 | 行程，同上 | 等結束 |
| pipeline → Lemonade | `openai-compatible` 資源：音訊轉錄（多部分，位元組）、對話（文字與圖片） | 同步，逐一，並行 1 |
| pipeline → SQLite | `jdbc-pool` 資源 | 同步 |

## webhook 契約

見 [ADR-036](adr/ADR-036-webhook-caller-parameters.md)。呼叫端需提供密鑰、delivery 識別碼與含 `file` 的 JSON。重送同一 delivery 不產生第二個 run。

## cron 觸發契約

cron trigger 不帶任何呼叫端參數：固定參數全部來自 trigger，`file` 不設，所以 run 進入掃描模式。錯過的觸發時間不補跑；Engine 不因「前一個 run 未結束」而跳過觸發，後來的 run 在 `llm` 資源前先進先出等候（[ADR-040](adr/ADR-040-cron-scan-mode.md)）。掃描規則與排程建議見 [runbook](runbook.md) 第 6 與 14 節。

## 降噪工具契約（行為層級）

降噪工具只決定「怎麼降噪」，不判定語音；它的輸出只給 VAD 工具使用，音訊切片與轉錄一律取自原始音訊（[ADR-046](adr/ADR-046-denoise-before-vad.md)）。

- 呼叫：`<命令> --atten-lim <dB> --output <WAV 路徑> <WAV 路徑>`。命令預設為 `runline-denoise`（可由 `TRANSCRIBE_DENOISE_COMMAND` 改）；衰減上限取自參數 `denoiseAttenLimitDb`。工具讀取環境變數 `RUNLINE_DENOISE_BINARY` 找到 deep-filter，那是工具自己的設定，pipeline 不讀。
- 輸入：48 kHz 單聲道 16 位元 WAV（由 pipeline 的音訊擷取保證）。
- 輸出：`--output` 指定的 WAV，48 kHz 單聲道。**不位移**：工具必須使用延遲補償，輸出與輸入的對齊延遲為 0。**長度不比輸入長，最多短 100 毫秒**（模型前瞻的尾端）；pipeline 轉為 16 kHz 時於尾端補零回原始長度，使 VAD 的視窗與時間對得上。衰減上限為 0 時不降噪，原樣輸出。同一輸入與衰減上限得到相同的位元組。
- 標準輸出無內容；標準錯誤第一行是工具自述（名稱、版本、執行檔的 sha256、衰減上限），pipeline 記入 run 的紀錄，失敗時接著說明原因。結束碼 0 成功；1 失敗；2 參數錯誤。
- 不連網、僅 CPU；版本與模型（內建於執行檔）的雜湊固定，執行檔缺少或雜湊不符時失敗（版本與雜湊見 [04](04-deployment.md)）。
- pipeline 的處理：命令找不到、逾時（30 分鐘）、非 0 結束、輸出不是 48 kHz 單聲道、輸出比輸入短超過 100 毫秒，都視為部署問題，run 以 `CONFIGURATION` 失敗並指出原因，不靜默略過。

## VAD 工具契約（行為層級）

- 呼叫：`<命令> --model <模型檔路徑> <WAV 路徑>`。命令預設為 `runline-vad`（可由 `TRANSCRIBE_VAD_COMMAND` 改），模型檔路徑取自環境變數 `RUNLINE_VAD_MODEL`，由 pipeline 以 `--model` 傳給工具。
- 輸入：16 kHz 單聲道 WAV 路徑、模型檔位置。
- 輸出：標準輸出每行一個十進位數字，為整個檔案依時間順序每個固定長度視窗的語音機率（0 至 1），不含其他內容；視窗長度與取樣率是模型版本固定的屬性（silero-vad v5 在 16 kHz 為 512 取樣、32 毫秒），記載於工具文件並在開始時寫到標準錯誤，pipeline 以相同常數換算時間。錯誤與診斷走標準錯誤；結束碼 0 成功，非 0 失敗。
- 同一輸入與模型版本的輸出相同（可重現）；工具一次呼叫處理整個檔案，不為每個區間重啟。
- 工具不含門檻、最小靜音等判定參數。VAD-A 與 VAD-B 是 pipeline 對同一份機率套用兩組判定參數（B 只套用在 A 的區間內）；超過 25 秒的切開與短時間能量計算也在 pipeline 側完成（JDK 即可）。
- 模型的內部狀態處理與版本相關，由工具負責並固定版本；pipeline 不依賴之。

## 對 Lemonade 的請求語意

| 階段 | 一次請求的內容 | 回答要求 |
|---|---|---|
| 轉錄 | 一個 VAD-B 段的音訊（位元組）、`asrModel`；`audio.transcriptions` 端點另帶語言提示；chat 型端點在音訊之前可帶一個 system 訊息（`asrSystemPrompt`，預設不帶） | 文字；空白結果記為無語音 |
| 補掃 | 一個窗口的音訊（取自原始音訊），端點、模型與 system 訊息同轉錄；請求不帶 `max_tokens`，與轉錄一樣由資源的 `defaults` 決定 | 同轉錄；`language None` 或沒有字母數字記為無語音 |
| 潤飾 | 一個 VAD-A 組的 SRT（其下所有 VAD-B 條目） | 同樣條目數、同樣時間、只改文字（見 ADR-038） |
| 核對 | 一個字幕條目的文字、其區間的畫面（最多 6 張）、前後條目作語境 | 修正後文字，加一句畫面觀察 |
| 摘要 | 各條目的觀察與修正後字幕，階層式彙整（先每個 VAD-A 組，再全片） | 全片摘要，及（供翻譯用的）來源語言標示 |
| 翻譯 | 一個 VAD-A 組的修正後字幕、全片摘要 | 同樣條目數、同樣時間的繁體中文文字 |

各對話階段的 system 訊息是參數，預設為現行 prompt，覆寫與限制見[入口文件](README.md)「參數」。潤飾、摘要、翻譯的請求另帶 pipeline 算出的 `max_tokens`；轉錄、補掃與核對的由資源的 `defaults` 決定。

## 冪等、重試、逾時

- 模型呼叫：單次失敗重試次數與退避由參數決定；資源層已有連線、首位元組、閒置逾時。重試用盡的條目依階段規則處理：轉錄失敗＝整個 run 失敗；補掃窗口失敗＝略過該窗口並計入降級；潤飾、核對、翻譯失敗＝該組退回上一階段文字並記錄，run 仍完成但標記有降級（降級計數寫入紀錄）。
- 回答不符契約（條目數或時間變動、格式錯誤）：視同失敗，重試後退回上一階段文字。
- 設定類錯誤（模型不存在或不被允許、未授權、端點未啟用）不降級：run 立即以 CONFIGURATION 失敗，不重試，也不逐組降級。第一個使用對話模型的階段開始前，若資源提供模型清單，先確認 `llmModel`（及已設的階段覆寫）在清單內；清單取不到（含端點未啟用、被拒、回答不是清單、空清單）時略過檢查並記錄，不當作錯誤。
- 取消：run 取消時結束自己啟動的行程並刪除工作目錄。
- 去重：delivery 識別碼；不同 delivery 對同一檔案是兩個獨立的 run。cron 觸發沒有 delivery；重複的選取由 `llm` 資源的容量 1 序列化各 run 加上「字幕已存在」的判定避免（[07](07-nfr-risks.md)）。

## 認證與信任

webhook 密鑰；pipeline 對 Lemonade 的信任由 Engine 資源（金鑰庫、受信任憑證）管理，pipeline 不持有機密。

## 版本演進

VAD 工具契約與資料表結構有版本；資料表演進方式沿用 digest-rss 的原則（只增不改，版本記在資料庫）。
