# #35：ffmpeg 靜態檔改用 BtbN GitHub release

本項回答：建置 `browser-transcribe` 映像時，如何從新來源取得並驗證 ffmpeg 與 ffprobe。狀態：已核可（2026-10-19），已完成（2026-10-19）。Issue：#35。相依：[ADR-36](../adr/ADR-36-ffmpeg-source-btbn.md)（已核可）。儲存庫：runline。入口：[README](../README.md)。

## 目標

johnvansickle.com 無法連線時，`browser-transcribe` 映像仍能建置並通過既有檢查；ffmpeg 與 ffprobe 來自 BtbN 的 GitHub release 並以 sha256 固定。

## 驗收條件

1. `runline-transcribe-fetch` 從 BtbN 的一個月度 autobuild 發佈標籤下載 amd64 與 arm64 的壓縮檔，下載後先比對釘住的 sha256；不符則失敗，目的地不留任何檔案。
2. 版本與雜湊只定義在 `runline-transcribe-fetch` 一處；Dockerfile 與主機安裝都經由它取得。內部鏡像覆寫仍可用，雜湊仍適用。
3. 取得的 `ffmpeg` 與 `ffprobe` 安裝位置、可執行性與對外行為與原有一致，其他輸入（silero-vad 模型、deep-filter）不變。
4. 在兩個平台（amd64、arm64）建置 `transcribe` 階段成功；`runline-transcribe-check` 通過；既有 fetch 測試（含雜湊不符的拒絕案例）通過，並涵蓋新來源的下載位置與壓縮檔結構。
5. 發佈標籤、ffmpeg 版本線與授權變體的選擇與理由記錄在 `deploy/transcribe.md` 的固定輸入表，更新流程改寫為新來源；文件描述現況，不寫沿革。
6. 本項不修改 v0.0.14 標籤；驗收後由使用者決定新 tag，重新觸發 `publish-image`。
7. 以上驗證若有環境不允許的部分（例如沒有 arm64 環境），回報時明說未驗證。

## 架構約束

- 固定方式遵守 ADR-032：雜湊定義於一處，不使用 `latest` 或可移動的標籤。
- 只改建置輸入的來源；不改映像的對外行為、Engine 與 workflow 的觸發方式。
- 不在 Dockerfile 加入新的 apt 套件；下載工具仍只存在於丟棄的 `transcribe-fetch` 階段。
- 選擇的 ffmpeg 版本線若導致轉錄行為改變，停下來回報，不自行調整其他輸入。
- 實作方式（腳本與測試設計）由 tdd-coder 決定，須遵守專案的 TDD 與既有慣例。
