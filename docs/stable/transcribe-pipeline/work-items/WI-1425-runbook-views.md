# WI-1425 transcribe 的 runbook 與資料模型補上 Console 的 view

本文回答：手冊與資料模型要補哪些內容，才能讓管理員知道 Console 的資料區與寫出字幕檔的紀錄。狀態：已核可（2026-10-12）。相依：WI-1422（已完成）。儲存庫：runline（只改文件）。

## 背景

WI-1422 在 transcribe 宣告五個 view（`runs`、`run-stages`、`degradations`、`entries`、`result`），並新增結構版本 3 與表 `subtitle_file`（run、語系、相對路徑、是否寫出）。runbook 與資料模型尚未涵蓋。

## 行為與驗收條件

1. `runbook.md` 第 9 節：加入表 `subtitle_file` 的欄位與用途；說明 Console 的「資料」區顯示五個 view，各用一句話說明它回答什麼問題，並對應取代哪些手動查詢；說明 `run.result` 為 NULL 的 run 在 view 中顯示為 `running`；既有的 SQL 查詢保留。
2. `runbook.md` 第 16 節：已驗證與未驗證清單加入五個 view 在打包 Engine 上的讀取驗證；未驗證項目如實標示（Console 畫面、V2 資料庫經 Engine 升級）；結構版本的說明由 2 改為 3。
3. `06-data-model.md`：加入實體 SUBTITLE_FILE（以 run 與語系為鍵）與版本 3 的遷移；檔案是 `result` 的副本的說明維持。
4. 說明 `NO_PENDING` 的 run 在 `runs` view 中的呈現（檔名為空、可由 run 欄鑽取），以及 `stopped` 的語氣為中性。
5. 資料保留規則涵蓋 `subtitle_file`（隨 run 一併清除）；同一資訊只寫一處，其他處以連結引用。

## 架構約束

- 只改文件，不改程式碼；以 `runline-transcribe` 的 README 與 `src/main/kotlin/transcribe/views/` 為事實來源（唯讀）。
- 文件用現在式、宣告式；內容與核可的 WI-1422 一致，不引入其他行為。
- 不修改 `runbook.md` 中其他測試所依賴的查詢內容。
