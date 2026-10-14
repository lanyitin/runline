# WI-1452 可行性關卡：長片段轉錄與場景片段的成本

本文回答：30 秒片段的轉錄是否比現行短段好、請求是否在上限內；場景片段附圖的潤飾要多久、換人標記準不準。狀態：已核可（2026-10-14）。相依：無；轉錄的量測依賴 WI-1461。決策見 [ADR-051](../adr/ADR-051-long-chunks-and-forced-alignment.md)、[ADR-052](../adr/ADR-052-scenes-as-llm-units.md)、[ADR-053](../adr/ADR-053-polish-with-frames-and-speaker-turns.md)。儲存庫：T（量測工具與報告）。量測在 arr 與真實 Lemonade 上進行，由使用者或 homelab-deployer 執行。

## 行為與驗收條件

1. **長片段轉錄**：同一部影片（run 8）的同一段 10 分鐘，分別以 30 秒片段與現行短段送 Qwen3-ASR。記錄：
   - 請求大小與資源的 `maxRequestBytes`；
   - 單次耗時；
   - 人工抽查至少 30 句的錯字與斷句錯誤數；
   - 整段無語音或雜音部分的幻覺句數。
2. **場景片段潤飾的成本**：取 10 個約 12 秒的片段，各以 4、6、8 張畫面（長邊 512）附上條目送 gemma4（thinking 關），記錄：
   - 請求大小、單次耗時、prompt 與 completion token；
   - 回答是否守住條目、序號、時間與行數；
   - 以此推算一小時影片的潤飾、片段摘要、翻譯總時間，與 3 小時目標比較。
3. **換人標記**：上述片段中至少 3 個對話場景、1 個獨白場景，由使用者判定換人標記的正確率是否可接受。
4. **場景偵測**：對同一部影片以 2 至 3 個門檻跑場景偵測，記錄場景數、場景長度分布與耗時，由使用者抽看是否合理。
5. **建議預設值**：報告提出以下參數的建議預設值與依據，由使用者核可：
   - `chunkTargetSeconds`、`framesPerSegment`、`segmentTargetSeconds`；
   - `sceneThreshold`、`sceneMinSeconds`。
6. 報告放在 runline 的 `docs/draft/transcribe-scenes/`，列出重跑步驟。

## 架構約束

- 只驗證，不改 pipeline 的階段。
- 呼叫經 Engine 的 `openai-compatible` 資源，或使用與其相同的請求內容直接呼叫 Lemonade；不得改動 Lemonade 的設定。若需要換模型或調整 context，先回報。
- 不通過時停下來回報，由架構決定。
