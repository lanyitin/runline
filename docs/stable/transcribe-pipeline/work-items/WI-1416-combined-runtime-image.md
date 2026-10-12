# WI-1416 轉錄與瀏覽器共用的 Engine 映像

狀態：已核可（2026-10-12），未開始。儲存庫：R。相依：WI-1402。本文回答：同一個 Engine 要同時跑 digest-rss（需要 Chrome）與 transcribe（需要 ffmpeg 與 VAD 工具）時，部署如何提供。

## 背景

WI-1402 的轉錄覆蓋層與既有的瀏覽器覆蓋層各自指定自己的映像，同一個 Engine 只能二選一。兩條 pipeline 共用同一個 Lemonade，通常也跑在同一個 Engine 上，所以需要同時具備兩者的部署。

## 驗收條件

1. Docker：提供同時使用瀏覽器與轉錄兩個覆蓋層的方式，產生單一 Engine 映像，同時具備 Chrome、虛擬顯示、ffmpeg、ffprobe、VAD 工具位置與模型檔；單獨使用任一覆蓋層的行為不變，不使用覆蓋層時原部署不受影響。
2. systemd：兩個 drop-in 可同時安裝而不衝突（環境變數、PATH、目錄、`CacheDirectory`），文件說明同時安裝的步驟。
3. 兩種能力互不干擾：以真實容器驗證 Chrome 相關檢查與轉錄檢查（`deploy/bin/runline-transcribe-check`，VAD 工具尚未安裝時以 `--skip-vad`）在同一映像中都通過。
4. 建置輸入的版本與雜湊固定，不重複定義（沿用既有單一定義處）；無機密。
5. 文件（`deploy/transcribe.md`、`deploy/browser.md` 與 README 部署段）說明合用方式，並把「二選一」的敘述改為現況描述。

## 架構約束

不改 Engine 程式；單一 Engine 實例；以選配覆蓋層組合，不新增必用的相依。
