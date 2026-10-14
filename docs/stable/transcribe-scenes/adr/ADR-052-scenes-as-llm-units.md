# ADR-052 以場景片段作為 LLM 的處理單位

本文回答：潤飾、摘要、翻譯一次處理哪一段內容。狀態：已核可（2026-10-14）。取代 [ADR-049](../../../stable/transcribe-pipeline/adr/ADR-049-batches-with-read-only-context.md)（固定條數的批次）與 [ADR-038](../../../stable/transcribe-pipeline/adr/ADR-038-segment-model-and-stage-invariants.md) 決策 4、5。

## 背景

要判斷換人、描述演員的行為，模型需要看到「同一個場景」的文字與畫面；固定條數的批次會跨場景，也和畫面沒有對應。單次請求又不能太大，否則超出模型能力或耗時過長。

## 決策

1. **場景偵測**：以 ffmpeg 的場景分數偵測畫面切換：
   - 分數超過 `sceneThreshold` 處為場景邊界；
   - 短於 `sceneMinSeconds` 的場景併入前一個；
   - 沒有畫面或偵測失敗時，整部影片為一個場景（失敗時計入降級）。
2. **場景片段**：長於 `segmentTargetSeconds`（預設 12 秒）的場景，切成接近該長度的片段：
   - 切點選在條目之間的空隙，條目不跨片段；
   - 沒有合適空隙時，選在 VAD 機率最低處附近的條目邊界。
3. **條目歸屬**：每個條目依起點歸屬到唯一一個場景片段。
4. **語境**：每個片段的請求附上前一個片段（處理後的文字）作為唯讀語境，不跨場景時也可附上，讓連續對話不中斷。
5. **畫面**：每個片段每秒一張畫面，超過 `framesPerSegment` 時等距取樣到上限，縮到 `frameMaxEdgePixels`；只在記憶體，不保存。
6. **順序**：所有片段完成潤飾後才開始摘要，摘要完成後才開始翻譯；同一階段內依時間順序處理（沿用「階段依模型分批」的原則，減少模型切換）。

## 取捨

- 得到：單位與畫面、場景一致，模型能看到誰在畫面上；請求大小可由片段長度與圖片數控制。
- 失去：
  - 場景偵測的誤切會影響單位邊界；
  - 片段數取決於影片長度而非條目數，沒有對白的長片段也要處理（摘要）。

## 後果

- 新增參數：`sceneThreshold`、`sceneMinSeconds`、`segmentTargetSeconds`、`framesPerSegment`。
- 移除參數：批次大小與語境範圍（`contextSeconds`、`contextMaxEntries` 等 ADR-049 的參數）。
- 資料：SCENE、SCENE_SEGMENT（[06](../06-data-model.md)）。
- 門檻與圖片數的預設值由 WI-1452 的量測決定。
