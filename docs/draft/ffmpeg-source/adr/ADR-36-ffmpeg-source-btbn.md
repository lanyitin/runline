# ADR-36 ffmpeg 靜態檔改用 BtbN GitHub release

狀態：提案中。Issue：#36。補充 [ADR-032](../../../stable/homelab-arr/adr/ADR-032-pin-build-inputs-by-hash.md)，不取代。回答：johnvansickle.com 無法連線時，ffmpeg 與 ffprobe 改從哪裡取得。

## 背景

`browser-transcribe` 映像的 `transcribe-fetch` 階段從 johnvansickle.com 下載 ffmpeg 7.0.2 靜態檔。該站無法連線，v0.0.14 的映像建置因此失敗。該站是個人經營，沒有可用性承諾，且是 CI 建置路徑上唯一的單點。

## 選項

| 選項 | 優點 | 缺點 |
|---|---|---|
| A. 等 johnvansickle.com 恢復 | 不動程式 | 時間不可控；日後再發生同樣的中斷 |
| B1. 自己託管於 Forgejo generic package | 完全自控 | 需有原檔可上傳；上傳與維護是額外動作 |
| **B2. 改用 BtbN/FFmpeg-Builds 的 GitHub release（採用）** | 有公開發佈歷史；月度發佈標籤長期保留；不需自行託管；沿用既有的雜湊固定流程 | 版本線與 7.0.2 不同，行為需重新驗證；上游每次發佈體積大 |

## 決策

採 B2。

1. 來源為 BtbN/FFmpeg-Builds 的 GitHub release，amd64 與 arm64 各取一個含 `ffmpeg` 與 `ffprobe` 的靜態建置壓縮檔。
2. 釘住一個月度 autobuild 發佈標籤下的檔案，不使用滾動的 `latest`。版本與 sha256 仍只定義在 `runline-transcribe-fetch` 一處（ADR-032 第 4 點）。
3. 既有的內部鏡像覆寫機制保留：改用鏡像時雜湊仍適用。
4. 換來源視為依賴升級：須通過 `runline-transcribe-check`、既有 fetch 測試與兩個平台的 `transcribe` 階段建置。
5. 使用的授權變體（GPL 或 LGPL）由 #35 依功能需求選擇並記錄；runline 映像只在內部部署。

## 取捨

- 換取來源穩定，代價是 ffmpeg 版本線改變，行為須重新驗證。
- 月度標籤若日後被上游清除，建置會失敗；因釘住雜湊，不會悄悄換成別的內容。屆時依 ADR-032 的更新流程另選標籤。

## 後果

- 建置不再依賴 johnvansickle.com；`deploy/transcribe.md` 的固定輸入表與更新流程改寫為新來源。
- ADR-032 的限度仍成立：首次下載的雜湊由本專案自行計算（若上游提供 sha256 清單，則改為互相對照）。
- 修復進 main 後，以新 tag 重新觸發映像發佈。
