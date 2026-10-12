# 4 部署架構

狀態：已核可（2026-10-11）。本文回答：pipeline 在哪裡跑、主機需要什麼、需要哪些資源與設定。

## 拓撲

pipeline 與 ffmpeg、VAD 工具、Engine 在同一台主機、同一個 process 樹內；Lemonade 為既有的本機模型服務，經 Engine 的 `openai-compatible` 資源存取。單機、單 Engine 實例。

## 主機需求（選配覆蓋層，Docker 與 systemd 皆提供，作法同瀏覽器覆蓋層）

| 項目 | 需求 |
|---|---|
| ffmpeg 與 ffprobe | 可在 PATH 找到 |
| VAD 工具 | 一個 jar（含 ONNX Runtime 的 CPU 原生程式庫）加固定版本與雜湊的 silero-vad ONNX 模型檔，以主機已有的 JRE 執行；不需 Python。命令列契約見 [05](05-ipc.md) |
| 收件根目錄 | 以唯讀掛載給 Engine；Engine 使用者可讀 |
| 工作目錄 | 系統暫存區內，每個 run 專屬，run 結束時刪除；容量須容納一部影片的 WAV（約 115 MB／小時）與暫時的畫面檔 |
| SQLite 檔 | 放在 `file` 資源的根目錄之下，作法同 digest-rss |

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

## 環境

dev 以 devkit 與本機 Lemonade 執行；prod 為 Engine 主機。沒有 staging。
