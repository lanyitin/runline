# transcribe-scenes 交接文件

本文回答：新 session 接手時，目前做到哪、哪些已部署、下一步是什麼、使用者的偏好與限制。寫於 2026-10-15。這是工作交接，不是提案；新 session 讀完後可由使用者移除。事實的權威來源是 `docs/stable/transcribe-scenes/`（入口 README、工作項總覽 `work-items/README.md`、各 ADR 與工作項）與 git 紀錄；本文只補它們沒有的現況與操作。

## 1. 角色與規則（新 session 先讀）

- 你是 high-level-architect：不寫應用程式碼，不規劃程式碼層級的設計；寫入範圍只有 `docs/draft/`、`.claude/`、`CLAUDE.md` 與 `docs/stable/`（僅限晉升、工作項總覽維護、狀態欄）。**寫檔一律用 Write 與 Edit，不用 Bash 重導向。**
- 本 session 的修改在 commit `0c97083`（授權 push 與無強制 tag、晉升後清除 draft 原檔）與後續的設定變更（可派工給 homelab-deployer）；新 session 啟動後才生效。
- 派工只派 `docs/stable/` 中已核可的工作項；派 tdd-coder 的內容只含文件路徑、架構約束、回報要求，不含程式碼。
- 部署與實機測試：重啟後優先派給 `homelab-deployer`（若已載入）；否則用 SendMessage 轉給名為 `homelab-deploayer` 的現有 session。它可能要使用者在它那邊確認。
- 使用者的偏好：
  - **測試只跑與異動相關的，不跑全套**（全套太久；tdd-coder 定義的 §1.1 已寫）。根專案用 `:test --tests`，單用 `test` 會到 vad 模組而失敗。
  - 部署與實機測試是否進行，以使用者明確指示為準；這個 session 中他多次說「完成後自動部署並重測」，每次是針對該項。
  - 續跑時**只送要變更的參數**（Engine 把沒送的補成目前 jar 的預設值，整份重送會用舊提示詞蓋掉新預設）；必填的 `asrModel`、`llmModel` 必須送。
  - T 的 `mise.toml` 不提交；T 與 runline 都**不 push**，除非使用者要求。
  - 日文品質、時間精度、換人標記由使用者在成品字幕核對時判斷。
  - 使用者重視速度與電力，不喜歡長時間等待與無效的重試。

## 2. 儲存庫與路徑

- runline（本儲存庫）：`/Users/lanyitin/Projects/Personal/runline`，文件在 `docs/stable/transcribe-scenes/`。
- T（runline-transcribe）：`/Users/lanyitin/Projects/Personal/runline-transcribe`。
- 已建的 thin jar 副本：`/tmp/runline-deploy/runline-transcribe-<提交前7碼>.jar`（暫存目錄，重開機可能消失；可從 T 的提交重建）。
- 暫存與腳本：本 session 的 scratchpad（路徑可能已失效）。
- 影片：homelab 收件目錄的 `FC2-PPV-48000.mp4`（3830 秒，約 1.5 GB）。

## 3. 工作項現況（以 `work-items/README.md` 為準）

已完成並提交（T）：WI-1455 到 WI-1473（包含 WI-1463 截斷降級、WI-1464 時間不進模型、WI-1465 重複懲罰、WI-1466 低資訊量轉錄 `asrLowInformationRatio`、WI-1467 續跑、WI-1468 回答契約以片段處理、WI-1469 測試加速〔部分完成〕、WI-1470 降級的 run 可續跑、WI-1471 任何中文與語境檢查、WI-1472 續跑重新檢查沿用的譯文）。T 的最新提交：**`9eb335a`（WI-1473，已驗收，未部署；jar `/tmp/runline-deploy/runline-transcribe-9eb335a.jar`，sha256 `36a4d099…ece2d`）**。工作樹乾淨，只剩未追蹤的 `mise.toml`。

WI-1473 的驗收備註（已查證 git 與 jar；其餘依 tdd-coder 回報）：
- 相關範圍 443 個測試通過；最後一次修正三個過時的 `success` 期望後，只重跑了 `TranslateRunTest` 與 `PolishRunTest`，整組沒有重跑；沒跑 `ThinJarTest` 與 feasibility。
- 移除的失敗原因：`NOT_SRT`、`COUNT_CHANGED`、`NUMBER_CHANGED`、`CONTEXT_RETURNED`、`TOO_MANY_LINES`、`LINES_CHANGED`、`SPEAKER_CHANGED`、`EMPTY_TEXT`（整片段作廢用）；保留或新增：條目層級 `MISSING`、`EMPTY`、`UNTRANSLATED`，呼叫層級 `NO_ENTRIES`（沒有任何條目有文字）、`TRUNCATED` 與服務層失敗。
- **WI-1475 要接手的邊界（tdd-coder 刻意沒做）**：被截斷的回答目前仍會重試（WI-1473 只在重試用盡後採用完整的條目）；`NO_ENTRIES` 仍會附原因重試並每片段計一次止損；`modelRetries` 預設仍是 2。
- 一個超出工作項字面的決定：只要片段內有降級的條目，該片段的最後一次呼叫就記為 `fallback`（續跑靠 `fallback` 找出要重做的片段，否則降級條目會被誤沿用）。WI-1474、WI-1475 開工前要確認這個訊號是否保留。
- 換人標記的補回有一個小擴充：譯文行數與來源相同時，標記逐行對位（來源只有第二行有標記時才不會遺失）。
- 翻譯預設提示詞又改了；之前 run 記錄的 `run_parameter` 內是舊提示詞。
- `Srt.parseNumbered` 只剩假服務測試使用，保留。

待派工（已核可，在 stable）：
- WI-1474 摘要與角色表寬鬆解析。
- WI-1475 重試只留給服務錯誤與空手而回、及早止損以片段計數、角色表不為描述編譯名。
- WI-1476 被取消的 run 記錄結束狀態並可當續跑來源，取消盡快中止進行中的呼叫。
- 建議順序：WI-1474 → WI-1475；WI-1476 獨立（可隨時做）。前三項都改答案解析與重試，序列執行；並行須各自用獨立的 worktree 與分支，合併由使用者或明確指示。
- 做完 WI-1474、1475 後一次部署（若使用者想先驗證 WI-1473，可先部署 `9eb335a`；注意此時截斷仍重試、角色表仍有描述性的 named），再用 run 19 從 translate 續跑重測（見第 5 節）。

## 4. homelab 現況

- 部署中的 jar（WI-1473 尚未部署）：**`2ef98a78…`（T 的 `709032a`）**，transcribe-cron 的 contentHash；trigger **停用**（rss-digest-cron 也停用）。
- 資料庫 `transcribe.db`：schema v15；最新備份 `transcribe.db.pre-709032a`（在 `/data/appdata/runline/resources/`），另有 `pre-c9ba617`、`pre-aed32ca`、`pre-4d6df56`、`pre-q35-4b`、`pre-mistral7b` 等。回滾：PATCH 回舊 jar 的 contentHash；舊 jar 讀 v15 會失敗，所以 v15 之前的 jar 要連備份一起還原。
- 字幕：`/data/media/movies/FC2-PPV-48000.ja.srt` 與 `.zh-TW.srt` 現為 run 24 寫的版本；備份在 `/data/appdata/runline/srt-backup/`：`*.run19`（ja 與 zh-TW）、`FC2-PPV-48000.zh-TW.srt.run24`（sha256 32a34007…2144）。
- Lemonade：載入 Gemma4-26B-A4B-Uncensored-HauhauCS-Balanced-Q2_K_P（單一 llm slot，換模型會卸載其他；偶爾自行卸載，下次呼叫才重載）。已下載的其他模型：Qwen3.5-9B Q8_0（太慢，約 13 tok/s）、Qwen3.5-4B Q8_0（約 20 tok/s，格式問題多）、Mistral-7B-Instruct-v0.3 Q4_K_M（格式問題同樣多）、SenseVoiceSmall-GGUF-F16（Lemonade 載入失敗：架構 sensevoice-small 需要 FunASR 自己的 llama-funasr-sensevoice 執行檔，llama.cpp 與 whisper.cpp 都不支援，僅離線比較才可能）。
- 分析器：目前版本 UNSAFE，31 條 reasons（1 條 PROCESSES、1 條 IO_SENSITIVE_MEMBER ProcessBuilder.start()、29 個 JDK 類別），沒有 NETWORK；任何差異部署時要停下來回報。

## 5. 實機 run 與重測計畫

- DB run 編號與 Engine run id 不同；續跑參數 `resumeFromRun` 用的是 **DB run 編號**。
- run 18（Engine 7bbe6aff）：翻譯 EARLY_STOP 失敗，轉錄、潤飾、摘要資料齊全（535 條、27 場景、320 片段）。
- run 19：以 run 18 續跑（起點 summary）成功，success-degraded；zh-TW 有 181／535 條沒翻譯（含降級 74 條與成功片段中原樣回傳的 104 條；其中約 62 條是 ASR 把呻吟段標成中文的垃圾）。
- run 24：run 19 的翻譯續跑（WI-1470）；沒翻譯降到 142 條（26.5%）。
- run 25：709032a 的翻譯續跑，被 5 次連續截斷及早止損（尚未有 WI-1475）。
- run 26（Qwen3.5-4B）、run 27（Mistral-7B）：模型試驗，被取消；兩者的 DB 列沒有結束狀態（WI-1476 的缺陷）。
- **下次重測**：WI-1473、1474、1475 完成並部署後，對 run 19 從 translate 續跑，只送 `file`、`resumeFromRun=19`、`resumeFromStage=translate`、`asrModel=Qwen3-ASR-1.7B-GGUF-Q8_0`、`llmModel=Gemma4-26B-A4B-Uncensored-HauhauCS-Balanced-Q2_K_P`、`translateTemperature=0.6`；回報沒翻譯條目數（含假名、等於潤飾文字、聯集）、降級原因、呼叫次數與耗時，並與 run 24（72／139／142）對照。要驗證 WI-1466 的效果（ASR 垃圾）需要從 `transcription` 當起點續跑，會重做約 85 個片段的轉錄。
- 多場景驗證仍缺：2688 至 3163 秒是呻吟聲，沒有可轉錄的對話；需要使用者指定有對話又有畫面切換的區段，或換一部片。

## 6. 已確認的設計結論（簡述，細節在 ADR）

- ADR-059：轉錄截斷是內容問題，降級不計入止損；ADR-060：時間不進模型答案；ADR-061：重複懲罰；ADR-062：低資訊量轉錄視同沒有文字；ADR-063、066：以新 run 續跑，降級完成的 run 也可當來源；ADR-064：回答契約違反以片段處理（部分被 ADR-069 取代）；ADR-065：測試加速；ADR-067：翻譯接受任何中文；ADR-068：續跑重新檢查沿用的單位；**ADR-069：模型的回答能收就收（結構由 pipeline 掌握、寬鬆解析、取不到以條目降級、重試縮小、止損以片段計數）**。
- 實機證據：三個模型家族（Gemma Q2_K_P、Qwen3.5-4B、Mistral-7B）的翻譯失敗型態一致（自加 `- `、`->`、抄回原文、只回部分條目、夾註解），附原因重試的成功率只有 4% 至 7%；所以方向是降低檢查，不是換模型。

## 7. 尚未處理的事

1. **修訂稿**：stable 中被新 ADR 修改的敘述尚未更新（需在 draft 開修訂稿、使用者核可後才改）：ADR-055 決策 6；ADR-064 決策 1、3、4、5；ADR-067 決策 3；WI-1457、1458、1459 的對應檢查；ADR-063 決策 6 與 WI-1467 條件 2（ADR-066 修改）；`transcribe-pipeline/01-requirements.md` 假設 7、`07-nfr-risks.md` 第 40、48 行、`transcribe-scenes/01-requirements.md` 範圍外的「中斷後續跑」（ADR-063 取代）。
2. **draft 清除**：`docs/draft/transcribe-scenes/` 下已晉升的 ADR-062 到 ADR-069、WI-1466 到 WI-1476、舊的 `07-nfr-risks.md` 可以移除（新 session 的設定生效後我有清除權限；未生效前請使用者自行處理）。保留 `WI-1451-forced-alignment-report.md`、`WI-1452-long-chunks-and-segments-report.md`（stable 的 07 有連結），其中 WI-1452 報告的「600 秒 27 個場景」與量測檔不符，尚未查。
3. **WI-1469 的缺口**：全套時間目標（≤ 8 分鐘）與單一階段 ≤ 2 分鐘未驗證；`MeasurementRunTest` 每個測試約 16 秒的固定成本沒處理；`tdd-coder.md` 還沒有加入 `:measurementTest` 與 `check` 兩者都跑的說明。
4. **已知的小缺陷**：RUN SUMMARY 降級括號不列轉錄（WI-1466 已處理）；exchange 不記錄請求溫度；`degradations` view 的 failure 欄位對低資訊量為空；translate 的 STAGE 描述仍寫「Traditional Chinese」；RESUME RECHECKED 行在階段結束才寫（失敗的 run 看不到）。這些尚無工作項，可併入 WI-1473、1475 或另開。
5. **模型決定**：翻譯模型是否換成更高位元的 Gemma，由使用者決定；其他模型試驗都不理想。VRAM 12,231 MiB（nvidia-smi 讀數）與 homelab 認為的 8 GB 不一致，未核實。
6. **提交與 push**：runline 的 stable 文件已提交到 `245ab64`，之後工作項總覽的 WI-1473 狀態更新尚未提交；`docs/draft/` 未追蹤（含本文）；T 的工作樹乾淨；沒有 push 任何儲存庫。

## 8. 新 session 的第一步建議

1. 讀 `docs/stable/transcribe-scenes/README.md` 與 `work-items/README.md`，再讀本文。
2. 向使用者確認下一步：派 WI-1474，或先部署 `9eb335a` 並對 run 19 從 translate 續跑驗證 WI-1473。
3. 依第 3 節的順序派工，部署與重測等使用者指示。
4. 在適當時候提交 runline 的文件並詢問是否清除 draft 原檔。
