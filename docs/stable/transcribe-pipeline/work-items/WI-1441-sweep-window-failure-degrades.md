# WI-1441 補掃窗口轉錄失敗時降級，不讓 run 失敗

本文回答：補掃窗口用盡重試仍無法轉錄時，run 應如何處理。狀態：已核可（2026-10-13）。依據 [ADR-044](../adr/ADR-044-asr-coverage-sweep.md)。相依：WI-1436。儲存庫：T（runline-transcribe）。

## 背景

[WI-1436](WI-1436-asr-coverage-sweep.md) 讓窗口走與 VAD 條目同一條轉錄路徑，窗口用盡重試（含 `TRUNCATED`）會使整個 run 以 `TRANSCRIPTION_FAILED` 失敗。補掃預設開啟，補回的是額外內容；雜音窗口讓模型失控被截斷，不應讓原本能成功的影片失敗。

## 行為與驗收條件

1. **降級**：補掃窗口用盡重試（含 `TRUNCATED`、逾時、上游錯誤）後，略過該窗口，run 繼續。VAD 條目的轉錄失敗行為不變，仍使 run 失敗。
2. **可查**：
   - 失敗的窗口在 `sweep_window` 記錄為失敗，且該窗口的模型呼叫在 `exchange` 記為失敗（含失敗類型）。
   - `sweep-filtered` view 與 `degradations` view 列出失敗的窗口（影片時間、失敗類型），不靜默消失。既有欄位名稱與值不變。
   - log 有一行 `SWEEP FAILED window N (起 s to 訖 s)` 與原因；轉錄階段結果與 RUN SUMMARY 新增 `sweepFailed` 計數（補掃關閉時 log 與 RUN SUMMARY 仍與以前相同，補掃開啟但無空隙時該計數為 0）。
3. **全部失敗的邊界**：沒有任何 VAD 條目、補掃也沒有產生條目，且至少一個窗口失敗時（其餘窗口為無語音），run 以 `TRANSCRIPTION_FAILED` 失敗並指出失敗的窗口數與第一個原因，不以 `NO_SPEECH` 結束；只要有任一條目（VAD 或補掃）存在，run 繼續。
4. **不降級的情況**：取消仍使 run 取消；設定性錯誤（找不到 ASR 模型、無可用端點等會讓所有轉錄都失敗的錯誤）仍使 run 以原本的失敗類型失敗，不被降級吞掉。
5. **驗證**：以 Fake OpenAI 伺服器涵蓋：單一窗口截斷（run 成功、條目數正確、計數與 view 正確）、多個窗口失敗、全部窗口失敗且有／無 VAD 條目、VAD 條目失敗仍使 run 失敗、取消、設定性錯誤、`sweepEnabled=false` 不受影響。
6. **紀錄／版本**：若需要資料庫結構調整，使用下一個可用的結構版本，舊 run 的資料視為無失敗窗口。
7. README（T 儲存庫）中「窗口失敗語意待確認」的字句改為此行為；runbook 與資料模型的對齊由架構師處理。

## 架構約束

- 不改變條目時間不變式（ADR-038 決策 3、4）、不存放音訊。
- 只改補掃窗口的失敗處理，不改 VAD 條目、潤飾、核對、翻譯的失敗行為。
- 薄 jar、嚴格 TDD、不使用 Stub 或 Mock。
