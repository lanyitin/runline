# ADR-056 強制對齊改在 Lemonade 主機的 llm-gateway 上以 GPU 執行

本文回答：Qwen3-ForcedAligner 在哪裡執行、Engine 如何呼叫它、如何與 Lemonade 共用 GPU。狀態：已核可（2026-10-14）。取代 [03](../03-options.md) A 的推薦（A1 改為 A2 的一種形式），以及 [ADR-051](ADR-051-long-chunks-and-forced-alignment.md) 決策 3 原先「對齊工具在 Engine 映像內」的部分；[WI-1454](../work-items/WI-1454-aligner-in-image.md) 因此作廢。

## 背景

- **CPU 的量測結果**：WI-1451 在 arr（8 vCPU，float32）初步量測，每片段約 17 秒，其中約 8 秒是啟動與載入；推估一小時約 35 分鐘，超過 30 分鐘的關卡。即使只啟動一次，推估仍約 19 分鐘，峰值記憶體約 6 GB，映像增加約 1.9 GB（壓縮後）。
- **GPU 的分布**（2026-10-14 唯讀查詢）：
  - arr 只有 Intel Iris Xe 內顯，沒有可用的 GPU 運算環境；
  - Lemonade 主機有 NVIDIA RTX 5070 Laptop（規格 8 GB），Lemonade 以 CUDA 執行 Qwen3-ASR。
- **既有的 gateway**：Lemonade 主機上已有使用者維護的 llm-gateway（Python，user 層級 systemd 服務，埠 8098）。
  - 它在同一張 GPU 上輪流提供 d1 決策模型與 Lemonade：同一時間只有一邊佔用 GPU，依請求類型切換。
  - d1 以外的請求原樣轉發給 Lemonade。
- **Engine 的資源契約**：`openai-compatible` 資源只能呼叫端點目錄中的條目，不能指定主機、路徑或方法。transcribe 已經使用其中的音訊轉錄條目（`audio.transcriptions`）。
- 使用者要求對齊使用 Lemonade 主機的 GPU，並在 gateway 上提供。

## 決策

1. **位置**：Qwen3-ForcedAligner 由 llm-gateway 載入並執行，使用 GPU。Engine 映像不加入 Python 與對齊模型。
2. **對外契約**：gateway 把對齊模型當成一個模型名稱，以 OpenAI 相容的音訊轉錄端點提供。
   - 請求：音訊檔、模型名稱、語言，以及要對齊的文字；文字放在 `prompt` 欄位，對這個模型而言，`prompt` 是要對齊的逐字稿，不是提示。
   - 回應：比照 OpenAI 的 `verbose_json` 加字詞時間戳格式，含全文與每個字詞的文字、起點、終點（秒）。
   - 這個端點的其他模型名稱照舊轉發給 Lemonade。
   - 一次請求對齊一個片段。模型常駐在 gateway，所以載入成本只在切換 GPU 時發生，不隨片段數增加。
3. **Engine 的呼叫路徑**：
   - Engine 的 `openai-compatible` 資源，上游位址從 Lemonade（13305）改為 gateway（8098）；
   - transcribe 以既有的音訊轉錄條目呼叫對齊模型，不需要修改 Engine；
   - 所有經 Engine 的 Lemonade 呼叫都改經 gateway，因此也受到 gateway 的 GPU 切換保護；
   - 資源容量仍為 1，對齊、轉錄與對話呼叫依序排隊。
4. **GPU 共用**：對齊模型是 gateway 的第三方。與 d1 相同，切換規則如下：
   - 需要對齊時，先讓 Lemonade 卸載模型，再載入對齊模型；
   - 其他請求到來時，先釋放對齊模型。
   - 另一方有進行中的請求時不切換，沿用 gateway 現行的規則。
5. **pipeline 的順序**：所有片段轉錄完成後才開始對齊，對齊全部完成後才進入後續階段，使 GPU 在一個 run 中只切換兩次。
6. **失敗處理**：沿用 [ADR-055](ADR-055-thinking-policy-and-early-stop.md) 的重試、逾時與及早止損；重試用盡的片段改用估計時間（ADR-051），計入降級。
   - gateway 無法取得 GPU（例如驅動不可用、記憶體不足）時，回覆明確的服務不可用錯誤，不在 gateway 端改用 CPU。
7. **版本固定**：對齊模型的版本（revision）與檔案雜湊在 gateway 固定；執行時不連網下載。

## 取捨

- 得到：
  - 對齊在 GPU 上執行，速度遠快於 CPU；
  - Engine 映像不變大，也不增加 Python 供應鏈；
  - Engine 程式不需修改；
  - 經 Engine 的所有模型呼叫都受到 gateway 的切換保護。
- 失去：
  - gateway 成為所有 Engine 模型呼叫的必經點；gateway 停止時，所有 LLM 類 pipeline 都無法呼叫模型；
  - 每個 run 多兩次 GPU 切換：Lemonade 卸載與重新載入模型約數秒到數十秒；
  - 筆電獨顯可能休眠或驅動不可用，對齊因此失敗，需要在 Lemonade 主機處理；
  - gateway 沒有認證並綁在 `0.0.0.0`（沿用現況），對齊端點同樣暴露在區網；
  - 對齊的部署與更新不隨 runline 映像走，而是由使用者在 Lemonade 主機維運；
  - 以 `prompt` 傳逐字稿偏離 OpenAI 的原意，契約要寫在 gateway 的 README。

## 後果

- 新工作項 WI-1462（gateway 的對齊端點，儲存庫 llm-gateway）。
- WI-1451 改以 gateway 的 GPU 量測；CPU 的試驗映像與量測結果保留作為對照，不再作為關卡依據。
- WI-1454 作廢。
- WI-1455 以音訊轉錄條目呼叫對齊模型，並遵守決策 5 的順序。
- 部署：Engine 資源的上游位址改指 gateway，由使用者或 homelab-deployer 在 WI-1455 的版本上線前完成（[04](../04-deployment.md)）。WI-1451 的量測工具直接呼叫 gateway，不依賴這項切換。
- 07 的對齊耗時與相關風險，在 WI-1451 量測後由 WI-1460 對齊。
