# WI-1424 cron 掃描的文件、trigger 設定與實機驗證

本文回答：管理員如何設定 cron trigger 讓 transcribe 自動處理新影片，以及驗收方式。狀態：已核可（2026-10-12）。相依：WI-1423、WI-1421。儲存庫：runline（文件）；實機驗證需 runline-transcribe 的 jar。

## 背景

見 [ADR-040](../adr/ADR-040-cron-scan-mode.md)。

## 行為與驗收條件

1. 文件對齊且各記一處：`01-requirements.md`（觸發含 cron 掃描；`file` 選填）、`05-ipc.md`（cron trigger 不帶呼叫端參數）、`runbook.md`（cron trigger 的建立步驟、掃描規則、參數、「無待處理」、積壓與排程間隔的建議、排錯）、`07-nfr-risks.md`（排隊 run 累積、資源容量須為 1 的前提、以檔案存在判定「已產生字幕」的後果）、`06-data-model.md`（run 新增的檔案指紋）。
2. runbook 說明 cron trigger 的建立：綁定 transcribe 版本、固定所有必填參數（不含 `file`）、`callerParameters` 為空、排程表達式與時區；並說明排程間隔不宜小於典型的處理時間，以及錯過的時間不補跑。
3. runbook 說明如何重做一部影片（刪除其 `_zh-TW.srt` 或重送 webhook）、如何讓失敗達上限的影片重新處理（取代或修改該檔案）、哪些副檔名被掃描。
4. 實機驗證（打包的真實 Engine、真實 cron trigger、可寫收件目錄）：
   1. 放入兩部符合條件的影片與一部太新的、一部已有 `_zh-TW.srt` 的、一部副檔名不在清單的：連續兩次觸發後，前兩部各得兩個字幕檔，其餘不被處理；第三次觸發為「無待處理」；
   2. 一部必然失敗的影片（例如無音訊）在 `scanMaxAttempts` 次失敗後不再被選中，且不阻擋其他影片；
   3. 兩個 cron run 重疊時不選到同一部。
   環境不允許的部分（例如沒有真實 Lemonade）明說未驗證，可以用既有端到端驗證使用的替代方式，但須在回報中標明。
5. 部署文件說明：cron 掃描與 webhook 可並存；以「新檔出現」觸發 webhook 的外部系統與掃描是兩條獨立路徑，不會互相取代。

## 架構約束

- 只改文件與驗證步驟，不改 Engine 與 pipeline 程式碼。
- 同一資訊只寫一處，其他處以連結引用；文件用現在式、宣告式。
- 內容須與核可的 ADR-040 一致，不引入其他行為。
