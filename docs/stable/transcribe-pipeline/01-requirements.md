# 1 需求摘要

狀態：已核可（2026-10-12）。本文回答：pipeline 要達成什麼、輸入輸出是什麼、哪些是假設、哪些還待確認。

## 目標與情境

影片放進收件目錄後，由兩種觸發之一處理：管理員或外部系統呼叫 webhook 並帶入檔名；或 cron trigger 排程觸發，由 pipeline 在收件目錄找一部尚未產生字幕的影片。pipeline 非同步處理，結果寫入資料庫，並在影片旁寫出字幕檔；管理員或下游直接取用字幕檔，或以查詢從資料庫取出。

## 功能需求

1. 觸發：
   - webhook：帶一個參數代表檔名（`file`）。檔名是收件根目錄下的相對路徑，永遠處理該檔案。
   - cron 掃描（[ADR-040](adr/ADR-040-cron-scan-mode.md)）：`file` 為空（或未提供）即掃描模式，每次觸發處理收件根目錄下一部尚未產生字幕的影片；沒有待處理的影片時 run 立即成功結束。掃描規則、參數與 trigger 的建立見 [runbook](runbook.md) 第 6 節。
2. 擷取音訊：輸入可為 mp4 或其他影片格式；輸出單聲道、16000 Hz 的 WAV。影片長度超過 `maxMediaSeconds` 者在開始處理前被拒絕。
3. 分段：silero-vad 切出 VAD-A（每段不超過 25 秒，過長者於最低能量點切開）；VAD-A 中超過 6 秒者以更短的最小靜音再切，得 VAD-B；未超過 6 秒者原樣成為 VAD-B 的一段。
4. 轉錄：VAD-B 每段送 Lemonade 的 qwen-asr。模型名稱與請求參數是 pipeline 參數。
5. 潤飾：以 VAD-A 為單位，把其下 VAD-B 各段的文字組成 SRT，送 gemma4 潤飾，修正因 VAD 切斷句子造成的轉錄錯誤。
6. 畫面核對與摘要：對每一個字幕條目，取其時間區間內每秒一張的畫面（最多 6 張），連同字幕送 gemma4，核對並修正字幕；同時產出影片內容摘要。
7. 翻譯：以修正後的字幕與摘要為依據，送 gemma4 翻譯。目標語言固定為繁體中文；來源語言由影片內容決定。
8. 輸出：來源語言字幕與繁體中文字幕皆為 SRT，並各寫成影片所在目錄的字幕檔「影片檔名_語系.srt」（[ADR-039](adr/ADR-039-subtitle-files-beside-video.md)）；另有影片摘要。
9. 保存：資料庫是完整紀錄（SRT、摘要與各階段文字）；字幕檔是其中兩份 SRT 的副本；不保存圖片與音訊，只保存時間區間。

## 參數（對外契約）

| 參數 | 來源 | 意義 |
|---|---|---|
| `file`（選填，預設空） | 呼叫端（webhook）；cron trigger 不設 | 收件根目錄下的相對路徑；空＝掃描模式 |
| `scanExtensions`、`scanMinAgeSeconds`、`scanMaxAttempts` | trigger 固定或手動 run | 掃描模式的副檔名清單、檔案最小年齡、同一檔案的失敗上限；預設值與意義見 [runbook](runbook.md) 第 6.1 節 |
| `maxMediaSeconds`（預設 21600，即 6 小時） | trigger 固定或手動 run | 可接受的影片長度上限（秒）；超過者在處理前被拒絕 |
| VAD 參數（A 與 B 各一組） | 同上 | 判定門檻、最小靜音、最小語音長度；B 的最小靜音短於 A |
| `maxSegmentSeconds`（預設 25）、`refineThresholdSeconds`（預設 6） | 同上 | VAD-A 上限與進一步細分的門檻 |
| `cutSearchWindowSeconds` | 同上 | 過長片段尋找最低能量點的搜尋範圍 |
| `asrModel` 與轉錄請求參數 | 同上 | qwen-asr 的模型名稱、語言提示（可空＝自動）、取樣參數 |
| `llmModel` 與各階段覆寫（潤飾、核對、翻譯） | 同上 | gemma4 的模型名稱與取樣參數，含 thinking 開關 |
| `maxFramesPerRequest`（預設 6）、`frameMaxEdgePixels` | 同上 | 每次核對請求的圖片上限與縮圖邊長 |

只有 `file` 由呼叫端提供（webhook）；其餘由 trigger 固定，或手動 run 時由人填寫；cron trigger 不設 `file`。各參數的最終名稱與預設值以 `runline-transcribe` 的 README 參數表為準（WI-1403 定稿）。

## 非功能需求

- 延遲：以小時計是可接受的（Lemonade 並行 1、逐段循序）。長度上限 6 小時的影片，處理時間可達數十小時（見 [07](07-nfr-risks.md)）。
- 資料量：一部影片數百至上千個 VAD-B 段；文字量為 MB 以下。
- 可用性：單機、單 Engine；run 被中斷後立即結束且不續跑（見假設 7）。
- 安全：呼叫端只能指定收件根目錄下的檔名；webhook 仍以 per-trigger 密鑰驗證。
- 可觀測：階段 log、每階段計數與耗時、模型呼叫次數與失敗數。

## 假設

1. 第 7 步「依據」之後的文字缺漏，假設為「依 VAD-A 分組」翻譯，以摘要作為語境。
2. 檔案在 webhook 被呼叫時已完整寫入；呼叫端負責。掃描模式以檔案的最小年齡（`scanMinAgeSeconds`）判斷，不由呼叫端保證。
3. Lemonade 上的 gemma4 支援圖片輸入，qwen-asr 可經音訊轉錄端點呼叫；由 [WI-1404](work-items/WI-1404-capability-feasibility.md) 驗證。
4. 同一個 Lemonade 服務的並行上限是 1（資源容量 1）。cron 掃描依賴它來序列化各個 run（[07](07-nfr-risks.md)）。
5. 潤飾、核對、翻譯的回答要能逐條對回 VAD-B 條目（見 [ADR-038](adr/ADR-038-segment-model-and-stage-invariants.md)）。
6. VAD 以獨立 JVM 小工具（ONNX Runtime）提供，pipeline 以子行程呼叫，主機不需 Python（使用者已選定，見 [03](03-options.md) 3.3 方案 C）。
7. 長 run 被中斷（Engine 重啟、取消、逾時）時，pipeline 立即結束，不續跑，也不自動重跑；要重做時重送 webhook；掃描模式下被中斷的影片因沒有字幕，下次觸發時會再被挑到。已寫入的階段資料依保留規則保存（使用者已決定）。
8. 與 digest-rss 共用同一個 Lemonade 資源時，單次 run 佔用資源數小時至數十小時；後來搶資源的 run 依 Engine 資源的先進先出規則處於等候狀態，直到取得資源（使用者已接受，見 ADR-007）。

## 待確認問題

| # | 問題 | 影響 |
|---|---|---|
| 1 | Console 是否需要在 trigger 表單中設定「呼叫端提供的參數」（WI-1401）？不做也可用 API 設定 | 是否保留 WI-1401 |
| 2 | 核對階段每個條目一次請求；摘要由各條目的觀察彙整（階層式）。是否接受，或每個 VAD-A 組一次請求以減少呼叫？ | 呼叫次數與準確度取捨，見 [05](05-ipc.md) |
