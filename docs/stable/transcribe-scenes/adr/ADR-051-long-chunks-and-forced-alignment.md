# ADR-051 長片段轉錄加強制對齊，由程式組成字幕條目

本文回答：轉錄模型每次聽多長的音訊、字幕的時間從哪裡來。狀態：已核可（2026-10-14）。取代 [ADR-038](../../../stable/transcribe-pipeline/adr/ADR-038-segment-model-and-stage-invariants.md) 決策 1 至 3、[ADR-044](../../../stable/transcribe-pipeline/adr/ADR-044-asr-coverage-sweep.md)、[ADR-050](../../../stable/transcribe-pipeline/adr/ADR-050-subtitle-length-and-merge.md) 決策 2 至 4；保留 ADR-038 的「條目在組成後數量與時間固定」與 ADR-050 決策 1 的約 6 秒原則。

## 背景

- Qwen3-ASR 只回文字、不回時間，現行設計因此把音訊切成約 6 秒以內的段，讓段的時間就是字幕的時間。代價是 ASR 每次只聽約 1 秒（run 7 中位數 1.05 秒），缺少語境；VAD 漏掉的空隙要靠補掃，補掃在 run 8 逾時耗掉約 3.5 小時。
- Qwen3-ForcedAligner 能把「音訊＋已知文字」對齊到字詞層級的時間，於是 ASR 不必再受字幕長度的限制。

## 決策

1. **長片段**：以降噪後的 VAD 機率，把整條時間軸切成連續、不重疊的片段：
   - 每段接近 `chunkTargetSeconds`（預設 30 秒），不超過 `chunkMaxSeconds`；
   - 切點選在目標長度附近、語音機率最低（最安靜）的位置；
   - 片段之間沒有空隙，補掃取消；
   - 整段語音機率都低於門檻的片段標為無語音，不送轉錄。
2. **轉錄**：有語音的片段取**原始音訊**（降噪只供 VAD，沿用 ADR-046）送轉錄模型，得到語言與文字。
3. **對齊**：以 Qwen3-ForcedAligner 對「原始音訊片段＋轉錄文字＋語言」取得每個字詞的起訖時間。對齊失敗時，該片段的文字依字數比例分配到片段時間內，標為估計時間，計入降級。
4. **組成條目**：由程式依下列原則把字詞組成字幕條目：
   - 優先在句末標點與停頓（超過 `subtitlePauseSeconds`）處斷開；
   - 每條不超過 `subtitleMaxSeconds`（預設 6 秒）與每行字數上限，至多兩行；
   - 短於 `subtitleMinSeconds` 的條目，在不與下一條重疊的前提下延長顯示時間。
5. **不變式**：條目組成後，數量與時間固定；之後的階段只能改文字與換人資料。
6. **移除**：VAD-A／VAD-B 分段、補掃、輸出時合併短條目，以及它們的參數。

## 取捨

- 得到：ASR 有約 30 秒的語境；完整覆蓋時間軸，不必補掃；字幕長度由規則直接保證，不必在輸出時補救。
- 失去：
  - 多一個模型與 Python 執行環境（[04](../04-deployment.md)）；
  - 對齊失敗時時間只是估計；
  - 單一片段失控生成的影響範圍從約 1 秒變成約 30 秒。

## 後果

- 新增參數：`chunkTargetSeconds`、`chunkMaxSeconds`、`subtitlePauseSeconds`、`subtitleMinSeconds`、每行字數上限。保留 `subtitleMaxSeconds`。移除 VAD-A／B、補掃與合併的參數。
- 資料：CHUNK、ALIGNED_TOKEN、ENTRY（[06](../06-data-model.md)）。
- 先以可行性關卡驗證對齊與長片段轉錄（WI-1451、WI-1452），通過後才實作。
