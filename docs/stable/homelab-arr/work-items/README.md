# homelab arr 部署工作項總覽

狀態：已核可（2026-10-09）。本文回答：要交給 tdd-coder 的工作有哪些、順序為何。編號從 1100 起。tdd-coder 讀入口 [README](../README.md)、本頁與被派到的那一項。

| 項目 | 目標 | 相依 | 狀態 |
|---|---|---|---|
| [WI-1100](WI-1100-publishable-image.md) | 在筆電以單一入口交叉建置可推送到 registry 的 amd64 瀏覽器版 Engine 映像檔，並提供不在本機建置的 Compose 範本 | 無 | 未開始 |
| [WI-1101](WI-1101-forgejo-action.md) | 推送 `v*` tag 時由 Forgejo Action 建置並推送映像檔，不自動上線 | WI-1100；runner 條件確認 | 未開始（待 runner 條件確認後才派工） |
| [WI-1102](WI-1102-pin-build-inputs.md) | action 與容器映像以內容雜湊固定，附自動檢查與更新說明（[ADR-029](../adr/ADR-029-pin-build-inputs-by-hash.md)） | WI-1100、WI-1101 | 未開始 |

arr 側的 Terraform、Traefik 路由、DNS 與憑證不是 runline 儲存庫的工作項，由 homelab-deployer 在 homelab 儲存庫執行；它需要的輸入是 WI-1100 產出的映像檔名稱與 tag、服務的組態清單、卷的位置與探測方式。
