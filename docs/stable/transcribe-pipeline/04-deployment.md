# 4 部署架構

狀態：已核可（2026-10-12）。本文回答：pipeline 在哪裡跑、主機需要什麼、需要哪些資源與設定。

## 拓撲

pipeline 與 ffmpeg、VAD 工具、Engine 在同一台主機、同一個 process 樹內；Lemonade 為既有的本機模型服務，經 Engine 的 `openai-compatible` 資源存取。單機、單 Engine 實例。

## 主機需求（選配覆蓋層，Docker 與 systemd 皆提供，作法同瀏覽器覆蓋層）

| 項目 | 需求 |
|---|---|
| ffmpeg 與 ffprobe | 可在 PATH 找到 |
| VAD 工具 | 一個 jar（含 ONNX Runtime 的 CPU 原生程式庫）加固定版本與雜湊的 silero-vad ONNX 模型檔，以主機已有的 JRE 執行；不需 Python。命令列契約見 [05](05-ipc.md) |
| 收件根目錄 | 可寫掛載給 Engine；Engine 使用者可讀、可寫（pipeline 在影片旁寫出字幕檔，見下節與 [ADR-039](adr/ADR-039-subtitle-files-beside-video.md)）。只有收件根目錄可寫，其他掛載（VAD 工具目錄、模型檔）的唯讀性不變 |
| 工作目錄 | 系統暫存區內，每個 run 專屬，run 結束時刪除；容量須容納一部影片的 WAV（約 115 MB／小時）與暫時的畫面檔 |
| SQLite 檔 | 放在 `file` 資源的根目錄之下，作法同 digest-rss |

## 收件根目錄的寫入

- **範圍**：pipeline 只在「請求的影片檔所在目錄」建立檔案，且該目錄解析後（含符號連結）須在收件根目錄之內；檔名只有「影片檔名_語系.srt」兩個，以及寫入過程中的暫存檔 `.transcribe-*.tmp`（結束時移除）。已存在的同名一般檔案被覆蓋。
- **權限與擁有者**：Engine 使用者對影片所在目錄須有寫入與進入權限（Docker 容器內預設 uid 10001，可由 `user:` 改為其他 uid（如影片樹的擁有者 1000:1000），收件目錄的擁有者須等於該 uid 或對其可寫；systemd 是 `runline` 帳號）。字幕檔的擁有者是 Engine 使用者，權限為 `rw-r--r--`，播放器使用的其他帳號以 other 讀取。
- **檢查**：部署檢查腳本確認收件根目錄可寫並指出缺什麼；影片所在的子目錄是否可寫，由 run 的 `intake` 階段檢查，不可寫時 run 在 `intake` 以專用分類失敗，不等到轉錄結束（分類與排錯見 [runbook](runbook.md)）。以 root 執行時目錄權限的檢查可能不成立，Engine 與檢查腳本都不應以 root 執行。
- **呼叫端的責任**：以「新檔出現」觸發 webhook 的系統（目錄監看、同步工具等）**必須忽略 `.srt` 與以點開頭的檔案**。pipeline 在收件根目錄內建立的字幕檔，以及寫出期間短暫出現的 `.transcribe-*.tmp`，不得再觸發 run；否則每個影片都多出對這些檔案的無效 run。

## 設定（12-Factor：環境變數）

| 設定 | 意義 |
|---|---|
| `TRANSCRIBE_INTAKE_ROOT`（必要） | 收件根目錄的絕對路徑；缺少時 run 在初始階段失敗並指出缺哪一項 |
| `TRANSCRIBE_WORK_DIR`（選填） | 工作目錄的上層；未設定用系統暫存區 |
| `TRANSCRIBE_VAD_COMMAND`（選填） | VAD 工具的命令；未設定用 PATH 上的預設名稱 `runline-vad` |
| `RUNLINE_VAD_MODEL`（必要） | silero-vad 模型檔的絕對路徑；部署層設定，pipeline 與主機檢查腳本共用 |

`RUNLINE_RUN_TIMEOUT_SECONDS` 須不設定或遠大於預期的 run 時間；`RUNLINE_SHUTDOWN_GRACE_SECONDS` 到期時 run 會被標為中斷。

## Engine 側資源與綁定

| 角色 | 型別 | 說明 |
|---|---|---|
| LLM | `openai-compatible` | 啟用對話與音訊轉錄端點；請求大小上限須容納 6 張縮圖與一段 25 秒音訊 |
| 資料庫 | `jdbc-pool`（sqlite） | 所有紀錄 |

webhook trigger：綁定到已允許 unsafe 的特定 pipeline 版本；「呼叫端提供的參數」只含 `file`；其餘參數固定在 trigger。

cron trigger：綁定同一個版本，固定除 `file` 以外的必填參數，`callerParameters` 為空；每次觸發時 pipeline 自己掃描收件根目錄，挑一部尚未產生字幕的影片處理（[ADR-040](adr/ADR-040-cron-scan-mode.md)；建立步驟與掃描規則見 [runbook](runbook.md) 第 6 節）。掃描的正確性仰賴 `llm` 資源的容量為 1。

## cron 掃描與 webhook 並存

兩種 trigger 可同時存在，也可只用其中一種；它們是兩條獨立的路徑，互不取代：

- cron trigger 觸發的 run 由 pipeline 在收件根目錄內找影片，只處理尚未產生字幕者，不會重做已有字幕的影片。
- webhook 指定的檔案永遠處理，字幕已存在時覆蓋（[ADR-039](adr/ADR-039-subtitle-files-beside-video.md)）。
- 以「新檔出現」觸發 webhook 的外部系統與掃描互不知道對方：同一部影片可能被其中一條路徑先處理，另一條路徑隨後處理（webhook 重做，或掃描因已有 `_zh-TW.srt` 而略過）。兩條路徑共用同一個 `llm` 資源，一次只有一個 run 在處理。

## 環境

dev 以 devkit 與本機 Lemonade 執行；prod 為 Engine 主機。沒有 staging。
