# WI-1104 發佈 browser-transcribe 映像

本文回答：如何把與 digest-rss 共用的 `browser-transcribe` 映像發佈到 registry，供 arr 使用。狀態：已核可（2026-10-12）。相依：WI-1100、WI-1101、WI-1102、WI-1103（皆已完成）。儲存庫：runline。

## 背景

arr 以同一個 Engine 執行 digest-rss（需要 Chrome）與 transcribe（需要 ffmpeg 與 VAD 工具）。`browser-transcribe` 映像（Dockerfile 階段）已存在，但 `deploy/publish-image` 只建置 `--target browser`、映像名稱固定為 `runline-engine-browser`，Forgejo workflow 也只呼叫它，registry 上因此沒有 `browser-transcribe` 映像。

## 行為與驗收條件

1. `deploy/publish-image` 可選擇要發佈的階段：`browser`（預設，行為與現在完全相同）或 `browser-transcribe`；選擇後的映像名稱為 `runline-engine-browser` 或 `runline-engine-browser-transcribe`，標籤規則（commit 與額外標籤）、架構檢查（linux/amd64）、工作樹乾淨檢查維持不變。
2. Forgejo workflow 在推送 `v*` tag 時，兩個映像都建置並推送（相同 commit 與額外標籤）；任何一個失敗整個工作失敗，且不留下只推了一個的狀態不明的結果，或明確說明推了哪一個。
3. `browser-transcribe` 映像的建置輸入（ffmpeg、silero 模型）仍以雜湊固定（ADR-032）；`check-pins` 與既有的 `publish-test` 涵蓋新的階段與名稱，並維持通過。
4. 文件對齊且各記一處：`deploy/browser.md`、`deploy/transcribe.md`、homelab-arr 的 04-deployment（arr 使用的映像名稱與階段）與 `docs/stable/homelab-arr/` 入口；寫明 arr 用 `browser-transcribe`，單獨需要 Chrome 的環境仍可用 `browser`。
5. 以 `--no-push` 在本機實際建置 `browser-transcribe` 一次並驗證：映像為 linux/amd64、含 Chrome 與 ffmpeg／ffprobe／silero 模型、`runline-transcribe-check` 與 `runline-browser-probe` 可執行；環境不允許的部分明說未驗證。

## 架構約束

- `browser` 映像的發佈行為不得改變；既有的 `v*` tag 流程不得因此失敗。
- 映像不含密鑰與組態；不改映像內容，只改發佈路徑。
- 只改 `deploy/`、`.forgejo/workflows/` 與文件；Engine 與 pipeline 程式碼不在本項。
- 測試不使用 Stub 或 Mock。
