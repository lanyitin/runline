# WI-1419 部署檔與 pipeline 實作對齊（runline 部署）

狀態：已核可（2026-10-12），未開始。儲存庫：R。相依：WI-1417、WI-1402。本文回答：WI-1417 之後，`deploy/` 的檢查腳本、drop-in 與文件要如何與 pipeline 的實際行為一致。

## 驗收條件

1. `deploy/bin/runline-transcribe-check` 檢查的工作目錄，與 pipeline 實際使用的一致：`TRANSCRIBE_WORK_DIR` 有設時檢查它；未設定時檢查 JVM 的預設暫存目錄（Linux 為 `/tmp`，不看 `TMPDIR`）。腳本的測試涵蓋兩種情形。
2. `deploy/transcribe.md` 說明：`TRANSCRIBE_WORK_DIR` 設了但不存在或不可寫時，run 在 `cleanup` 階段失敗；systemd drop-in 設定的 `/var/cache/runline-transcribe` 由 `CacheDirectory=` 建立，即為實際使用的目錄。
3. VAD 工具的暫存目錄由 pipeline 以 `JDK_JAVA_OPTIONS` 指定：確認並以測試驗證 `deploy/bin/runline-vad` 在 Docker 與 systemd 的環境下不會清掉或覆蓋這個環境變數（包裝腳本保留並傳給 `java`）；若會，修正並測試。
4. `deploy/transcribe.md` 的資源範例與說明建議為 `llm` 資源啟用模型清單端點（`models.list`），並說明不啟用時模型清單檢查被略過、改由第一個對話請求的 404 發現設定錯誤。
5. 既有 `deploy/test/` 腳本與 `deploy/check-pins` 通過；`shellcheck` 對變更的腳本無警告；不改變不使用轉錄覆蓋層的部署行為。

## 架構約束

只改 `deploy/` 與其文件；不改 Engine 程式；工作樹中其他進行中的變更不得更動。
