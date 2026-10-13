# WI-1431 runbook、入口文件與資料模型對齊 ASR 端點選擇、錯誤說明與結構版本 5、6

本文回答：WI-1426、WI-1428、WI-1429 完成後，文件要補哪些內容。狀態：已核可（2026-10-13）。相依：WI-1429（已完成）。儲存庫：runline（只改文件；以 `runline-transcribe` 的 README 與程式為事實來源，唯讀）。

## 行為與驗收條件

1. `runbook.md`
   - `llm` 資源 `settings.endpoints`：`models.list` 必須啟用（轉錄靠它取得 labels）；`audio.transcriptions` 供轉錄型模型；`chat.completions` 供 chat 型模型與所有對話階段。`allowedModels` 須包含 `asrModel` 與 `llmModel`。
   - `asrModel` 的說明：「轉錄模型」；labels 含 `transcription`（如 Whisper）走 `audio.transcriptions`，其他（如 Qwen3-ASR）走 `chat.completions`；`asrLanguage` 在 chat 路徑無效，log 有提示。
   - 第 15 節 `CONFIGURATION` 列：補上 `asrModel` 不在清單、沒有 labels、chat 路徑請求過大的訊息；「轉錄回 400 `unsupported_operation`」的處置改為：chat 型模型現在自動走 chat 路徑，出現時代表 `asrModel` 誤指向不支援該端點的模型。
   - log 行：`TRANSCRIBE ENDPOINT …`、`TRANSCRIBE NOTE` 兩種（清單取不到；`asrLanguage` 無效）；轉錄階段的 `truncated=` 計數。
   - 排錯：`asrModel` 誤指向不吃音訊的 chat 模型（第一個請求以 4xx 失敗，說明在訊息與 `exchange.error`）；以 `exchange.endpoint`、`run.asr_endpoint`、`run.asr_labels` 查實際走哪條路。
   - 第 16、17 節已驗證／未驗證如實更新：以 `FakeOpenAiServer` 驗證；真實 Lemonade 的 chat 路徑僅有 WI-1429 的量測，pipeline 本身未對真實 Lemonade 重現（待 arr 上的第一次 run）。
2. 入口 `README.md`、`01-requirements.md`、`work-items/WI-1409-transcription.md`、`work-items/WI-1404-capability-feasibility.md` 與總覽：「qwen-asr」稱呼改為「轉錄模型」，說明兩種端點，連到 [ADR-042](../adr/ADR-042-asr-by-model-labels.md)。
3. `06-data-model.md`：
   - RUN 新增 `asr_endpoint`、`asr_labels`（版本 6）。
   - EXCHANGE 新增 `endpoint`（版本 6）與 `error`（版本 5：`<error.type>: <error.message>`，4xx 才有）；「轉錄呼叫沒有 `finish_reason`」改為「轉錄型模型的轉錄端點沒有；chat 路徑有」。
   - 版本歷史補上版本 5（WI-1428）與版本 6（WI-1429）。
4. `runbook.md` 第 4 節的分析器判定清單與實際一致：`NOT_ALLOW_LISTED` 的 JDK 類別補上 `java.nio.file.CopyOption`、`FileSystem`、`FileSystems`、`StandardCopyOption`、`attribute.FileTime`、`attribute.PosixFilePermission`、`attribute.PosixFilePermissions`（字幕寫出與收件檢查）以及 `java.security.MessageDigest`（音訊雜湊，WI-1429）；數量與原因以 arr 上傳最新 jar 的分析結果為準（31 個原因）。
5. 模型清單取不到時的行為（沿用 `audio.transcriptions` 並提示）只寫一處，並與 ADR-042 的修訂一致。
6. 同一資訊只寫一處，其他處以連結引用；文件用現在式、宣告式。

## 架構約束

- 只改文件，不改程式碼。
- 不修改 runbook 中其他測試所依賴的查詢內容。
