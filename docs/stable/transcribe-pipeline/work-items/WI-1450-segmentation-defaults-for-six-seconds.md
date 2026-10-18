# WI-1450 依約 6 秒的原則決定分段參數的預設值

本文回答：分段與補掃的預設值要調成多少，以及如何以實際影片驗證。狀態：已作廢，由 [WI-1455](../../transcribe-scenes/work-items/WI-1455-long-chunks-alignment-entries.md) 取代，不派工（原已核可 2026-10-13）。相依：WI-1448、WI-1449。決策見 [ADR-050](../adr/ADR-050-subtitle-length-and-merge.md) 決策 1。儲存庫：T（runline-transcribe）；比較在 arr 上進行（homelab-deployer 協助）。

## 背景

run 7 的條目長度由 VAD-A 的最小靜音（500 ms）決定，中位數 1.05 秒。另一方面，分段上限（25 秒）與補掃窗口（8 秒）都超過 6 秒的原則。這些都是既有參數，可以先在 run 上覆寫來比較，不必改程式。

## 行為與驗收條件

1. **比較**：以 `FC2-PPV-47287.mp4`（run 7）為主，必要時加一部對白較多的影片，用 WI-1448 與 WI-1449 完成後的版本跑兩組：
   - 對照組：現行分段預設值。
   - 候選組：`vadAMinSilenceMs=1200`、`maxSegmentSeconds=6`、`refineThresholdSeconds=6`、`sweepWindowSeconds=6`。

   跑之前先備份影片旁邊既有的字幕檔，因為輸出會覆蓋它們。
2. **比較項目**（由資料庫計算，記入本文件的結果表）：
   - 條目長度的分布：中位數、P10、P90、短於 1 秒的比例、超過 6 秒的條目數。
   - 有語音的條目數與總字數。
   - 補掃窗口數與保留數。
   - 合併次數。
   - 各階段改動的條目數與降級數。
   - 總耗時。
   - 人工抽查至少 20 段：聽錯的字與句子被切斷是否減少。
3. **決定**：依比較結果決定預設值，由使用者核可。
   - 採用候選值或其變體時：
     - 更新參數宣告的預設值（每個只在一處定義）；
     - 更新 T README 與入口文件的參數表；
     - 更新既有測試中依賴舊預設值的期望（不得放寬斷言）。
   - 不採用時記錄原因，預設值不變。
4. **一致性檢查**：預設值須滿足既有的參數約束：`refineThresholdSeconds` 不大於 `maxSegmentSeconds`，`minPieceSeconds` 不超過 `maxSegmentSeconds` 的一半，`sweepWindowSeconds` 不短於 `minPieceSeconds`，且 `maxSegmentSeconds` 與 `sweepWindowSeconds` 不超過 `subtitleMaxSeconds`。

## 架構約束

- 只改參數的預設值，不改分段與補掃的演算法。
- 比較以真實 run 進行，不修改 arr 上的其他設定；備份與還原字幕檔由執行比較的人負責。
- 嚴格 TDD；測試不使用 Stub 或 Mock。
