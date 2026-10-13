# homelab arr 部署工作項總覽

狀態：已核可（2026-10-09）。本文回答：要交給 tdd-coder 的工作有哪些、順序為何。編號從 1100 起。tdd-coder 讀入口 [README](../README.md)、本頁與被派到的那一項。

| 項目 | 目標 | 相依 | 狀態 |
|---|---|---|---|
| [WI-1100](WI-1100-publishable-image.md) | 在筆電以單一入口交叉建置可推送到 registry 的 amd64 瀏覽器版 Engine 映像檔，並提供不在本機建置的 Compose 範本 | 無 | 已完成（已部署到 arr 運作；乾淨 runner 暴露的架構檢查缺陷已修正） |
| [WI-1101](WI-1101-forgejo-action.md) | 推送 `v*` tag 時由 Forgejo Action 建置並推送映像檔，不自動上線 | WI-1100；runner 條件確認 | 已完成（真實 runner 以 `v*` tag 建置並推送成功；runner 標籤須提供 Docker CLI） |
| [WI-1102](WI-1102-pin-build-inputs.md) | action 與容器映像以內容雜湊固定，附自動檢查與更新說明（[ADR-032](../adr/ADR-032-pin-build-inputs-by-hash.md)） | WI-1100、WI-1101 | 已完成（固定後的 Action 在真實 runner 建置並已部署；PostgreSQL 雜湊在兩個範本各一處，由自動檢查保證一致） |

| [WI-1103](WI-1103-image-arbitrary-uid.md) | Engine 映像可以任意 uid:gid 執行，使 arr 能以影片目錄的擁有者 1000:1000 執行並在影片旁寫字幕檔（[ADR-041](../adr/ADR-041-engine-image-arbitrary-uid.md)） | 無 | 已完成（2026-10-12；四個映像以 1000:1000 與 2345:2345 驗證，browser 系列在 Rosetta 下；arr 主機實跑與 Linux 原生 amd64 未驗證） |

| [WI-1104](WI-1104-publish-browser-transcribe-image.md) | 發佈 `browser-transcribe` 映像（`deploy/publish-image` 可選階段，Forgejo workflow 兩個映像都推送），供 arr 的 transcribe 使用 | WI-1103 | 已完成（2026-10-12；本機以 `--no-push` 建置 `browser-transcribe` 並驗證內容，Rosetta 下；推送到真實 registry、Forgejo runner 上的 workflow 與原生 amd64 未驗證；VAD jar 由宿主目錄掛載，不在映像內） |

arr 側的 Terraform、Traefik 路由、DNS 與憑證不是 runline 儲存庫的工作項，由 homelab-deployer 在 homelab 儲存庫執行；它需要的輸入是 WI-1100 產出的映像檔名稱與 tag、服務的組態清單、卷的位置與探測方式。
