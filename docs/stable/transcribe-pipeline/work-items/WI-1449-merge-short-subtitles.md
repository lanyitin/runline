# WI-1449 輸出 SRT 時合併太短且相鄰的條目

本文回答：產生字幕時，哪些條目要合併顯示、如何合併，以及如何驗收。狀態：已完成（2026-10-14）。相依：無。決策見 [ADR-050](../adr/ADR-050-subtitle-length-and-merge.md)。儲存庫：T（runline-transcribe）。

## 背景

run 7 的條目中位數 1.05 秒，約一半短於 1 秒；這種字幕在畫面上閃過即逝，來不及讀。使用者要求：很短、且與前後間隔不到一秒的字幕合併在一起顯示，一條字幕以約 6 秒為原則。

## 行為與驗收條件

1. **參數**：新增三個參數，皆為大於零的數字，不合法時在 intake 以 `CONFIGURATION` 失敗並指出參數名稱。
   - `subtitleMergeGapSeconds`（預設 1.0）
   - `subtitleShortSeconds`（預設 1.5）
   - `subtitleMaxSeconds`（預設 6）
2. **合併規則**：只看有語音、會出現在 SRT 的條目，依時間順序由前往後檢查相鄰的 A、B。以下三項都成立時合併為一條：
   - B 起點減 A 終點小於 `subtitleMergeGapSeconds`；
   - A 或 B 的長度小於 `subtitleShortSeconds`；
   - B 終點減 A 起點不超過 `subtitleMaxSeconds`。

   合併後的字幕不再與下一條合併，最多由兩個條目合成。不合併時，A 原樣輸出，改以 B 為 A 繼續檢查。
3. **合併的形式**：時間為 A 起點到 B 終點；文字為 A 的文字、換行、B 的文字，不改寫任何文字；序號從 1 連續重編。
4. **兩份一致**：合併只依時間決定。來源語言與繁體中文兩份 SRT 的條目數、序號與時間完全相同。翻譯降級、某條保留原文時，合併方式也不變。
5. **適用範圍**：`result.source_srt`、`result.chinese_srt` 與字幕檔都是合併後的 SRT，三者逐位元組相同。核對階段寫入的來源語言 SRT 同樣合併。送給潤飾與翻譯的 SRT **不合併**。`segment_b` 與 `entry_text` 不變。
6. **紀錄**：RESULT 行或 RUN SUMMARY 記下合併的次數（例如 `merged=<n>`）。T README 的「字幕檔輸出」與「紀錄資料庫」說明 SRT 是合併後的結果。
7. **驗證**：
   - 邊界值：間隔等於門檻時不合併；長度等於 `subtitleShortSeconds` 時不算短；合併後長度等於 `subtitleMaxSeconds` 時可以合併。
   - 連續三條短條目只合併前兩條；兩條都不短時不合併。
   - 中間隔著無語音條目時，以有語音條目的時間判斷。
   - 兩份 SRT 一致；某條翻譯降級時兩份仍一致。
   - `result` 與字幕檔逐位元組相同；資料庫條目不變。
   - 參數不合法時在 intake 失敗。

## 架構約束

- 合併只在產生 SRT 的單一函式內（現有的 `SubtitleSrt`）；不在各階段各自實作。
- 不改變條目時間與數量的不變式；不新增資料表或欄位。
- 薄 jar、嚴格 TDD、不使用 Stub 或 Mock。
