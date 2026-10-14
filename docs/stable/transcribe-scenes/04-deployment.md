# 4 部署

狀態：已核可（2026-10-14）。本文回答：為了對齊與場景偵測，映像與主機要多提供什麼。現行部署見 [stable 04](../../stable/transcribe-pipeline/04-deployment.md) 與 `deploy/transcribe.md`；本文只寫差異。

## 對齊工具

- **形態**：Engine 映像（`transcribe` 與 `browser-transcribe` 兩個階段）內的獨立行程，由 pipeline 啟動、等待結束，作法同降噪工具（[ADR-046](../../stable/transcribe-pipeline/adr/ADR-046-denoise-before-vad.md)）。
- **執行環境**：映像加入 Python 執行環境與對齊所需的推論程式庫，只用 CPU，版本固定。
- **模型檔**：Qwen3-ForcedAligner 的權重放在映像內，以雜湊固定（與其他固定雜湊的項目一起由 `check-pins` 檢查）。執行時不得連網下載。
- **帳號**：沿用任意 uid 的作法（[ADR-041](../../stable/homelab-arr/adr/ADR-041-engine-image-arbitrary-uid.md)）；工具的快取與暫存寫在工作目錄，不寫 HOME 以外的固定位置。
- **代價**：映像增加 Python 與推論程式庫的大小，以及模型檔（量級數百 MB 到約 2 GB，以 WI-1451 實測為準）。
- **主機（systemd）部署**：主機要提供相同版本的 Python 環境與模型檔，位置以環境變數指定。

## 場景偵測

使用既有的 ffmpeg，不新增工具。

## 畫面擷取

沿用現行能力（ffmpeg 每秒一張、縮到 `frameMaxEdgePixels`、只在記憶體），改以場景片段為單位擷取。

## 組態

| 環境變數或參數 | 用途 |
|---|---|
| 對齊工具的位置（環境變數） | 映像內預設；主機部署時指定 |
| 對齊模型檔的位置（環境變數） | 同上 |
| pipeline 參數 | 見 [05](05-ipc.md) 與各 ADR；新參數的完整清單在 runline-transcribe 的 README |

## 啟動時的檢查

- 對齊工具或模型檔不存在：run 在對齊階段以明確的失敗類別失敗（與 `VAD_TOOL_MISSING` 同類）；Engine 本身照常啟動。
- `runline-entrypoint` 的啟動報告列出對齊工具的版本與模型檔雜湊（只報告、不攔阻）。
