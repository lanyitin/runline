# ADR-050 字幕條目以約 6 秒為原則，太短且相鄰者合併顯示

本文回答：一條字幕應該多長，太短的字幕如何處理。狀態：已核可（2026-10-13）。補充 [ADR-038](ADR-038-segment-model-and-stage-invariants.md) 決策 1 至 3（分段與條目）與 [ADR-039](ADR-039-subtitle-files-beside-video.md)（字幕檔的內容）；不改變「條目時間在轉錄後固定」的不變式。

## 背景

使用者的原則：一條字幕約 6 秒最合適，太長難讀，太短來不及讀；很短、且與前後間隔不到一秒的字幕，應合併在一起顯示。

run 7（見 [ADR-049](ADR-049-batches-with-read-only-context.md) 背景）的條目中位數只有 1.05 秒，短於 1 秒者約一半；長度由 VAD-A 的最小靜音（500 ms）決定，一個停頓就切開。另一方面，分段上限 `maxSegmentSeconds` 是 25 秒，補掃窗口 `sweepWindowSeconds` 是 8 秒，都超過 6 秒。

短條目同時造成兩個問題：ASR 每次只聽約 1 秒，缺少語境；顯示時間太短，來不及讀。前者只能在分段時處理，後者可以在輸出時處理。

## 選項

| 選項 | 優點 | 缺點 | 結論 |
|---|---|---|---|
| A. 只改分段參數（VAD-A 跨過較短的停頓） | ASR 聽到較長的音訊 | 自訂參數的 run、補掃條目與孤立的短段仍可能零碎 | 部分採用 |
| B. 只在輸出 SRT 時合併 | 不影響前面任何階段 | ASR 仍只聽 1 秒 | 部分採用 |
| C. 兩者都做 | ASR 與顯示都改善；B 是 A 的安全網 | 兩處規則要一致地說明 | 採用 |

## 決策

1. **長度原則**：一條字幕以不超過 6 秒為原則。分段上限與補掃窗口的預設值不超過 6 秒。實際預設值待 [WI-1450](../work-items/WI-1450-segmentation-defaults-for-six-seconds.md) 在 arr 比較後決定。候選值為 `vadAMinSilenceMs` 1200、`maxSegmentSeconds` 6、`refineThresholdSeconds` 6、`sweepWindowSeconds` 6。
2. **輸出時合併**：產生 SRT 時，依時間順序檢查相鄰的兩個**有語音**條目 A、B。以下三項都成立時合併成一條字幕：
   - 間隔（B 起點減 A 終點）小於 `subtitleMergeGapSeconds`（預設 1.0 秒）；
   - A 或 B 的長度小於 `subtitleShortSeconds`（預設 1.5 秒）；
   - 合併後的長度（B 終點減 A 起點）不超過 `subtitleMaxSeconds`（預設 6 秒）。
3. **合併的形式**：合併的字幕時間為 A 起點到 B 終點，文字為 A 的文字換行接 B 的文字（兩行），不改寫任何文字。一條字幕最多由兩個條目合成（字幕最多兩行），已合併的字幕不再與下一條合併。由前往後貪婪配對。
4. **兩份一致**：合併只依時間決定，與文字無關，所以來源語言與繁體中文 SRT 的合併方式完全相同，條目數、序號與時間一致（沿用 ADR-038 的「兩份 SRT 由同一個函式做出」）。
5. **紀錄**：`result.source_srt`、`result.chinese_srt` 與字幕檔都是合併後的 SRT，三者仍逐位元組相同（ADR-039）。資料庫的條目（`segment_b`、`entry_text`）不合併；潤飾、核對與翻譯處理的仍是未合併的條目。

## 取捨

- 得到：ASR 聽到較長的音訊；畫面上不再出現閃過即逝的字幕；字幕長度有一致的原則。
- 失去：SRT 的條目與資料庫的條目不再一一對應（要對照時用時間）；分段預設值改變後，舊 run 與新 run 的條目長度不可直接比較。

## 後果

- 參數新增 `subtitleMergeGapSeconds`、`subtitleShortSeconds`、`subtitleMaxSeconds`。
- 合併見 [WI-1449](../work-items/WI-1449-merge-short-subtitles.md)；分段預設值見 [WI-1450](../work-items/WI-1450-segmentation-defaults-for-six-seconds.md)。
