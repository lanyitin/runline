# WI-1101 推送 `v*` tag 時由 Forgejo Action 建置並推送映像檔

狀態：已核可（2026-10-09）。回答：要達成什麼行為，使發版 tag 能自動產出可部署的映像檔。依據 [ADR-027](../adr/ADR-027-forgejo-action-image-publishing.md)。

## 目標

維運者推送 `v*` tag 後，Forgejo 的 runner 自動建置 `linux/amd64` 的瀏覽器版 Engine 映像檔並推送到同一個 Forgejo registry；產物與筆電上執行 `deploy/publish-image` 的產物可互換。不自動上線。

## 範圍

- 內：Forgejo 工作流程定義、必要的說明文件。
- 外：任何對 arr 或 homelab 基礎設施的變更與自動部署；runner 本身的安裝與註冊；移除筆電手動推送路徑。

## 驗收條件

1. 只有推送符合 `v*` 的 tag 會觸發；推送到分支、推送其他名稱的 tag、提交本身都不觸發。
2. 建置與推送的規則與 `deploy/publish-image` 一致且只有一份：架構為 `linux/amd64`、映像檔 tag 由 commit 識別決定、不產生 `latest`、架構不符時不推送。工作流程不複製一份獨立的規則。
3. 映像檔在 commit 識別的 tag 之外，同時帶有觸發它的版本 tag。
4. 推送失敗或建置失敗時，工作流程結果為失敗並指出原因；不留下不完整的 tag。
5. 推送憑證來自 Forgejo 的機密機制，不出現在版本庫、工作流程日誌或映像檔；授與的權限僅限推送 packages。
6. 工作流程不接觸 arr、不修改任何部署設定、不觸發上線。
7. 以一個測試用的 `v*` tag 實際跑過一次：runner 建置完成、映像檔出現在 registry、在乾淨的 Docker 主機上以 `deploy/docker/compose.registry.yaml` 引用並啟動就緒。
8. CI 產出的映像檔通過與筆電產物相同的驗證：非 root 運行、不含密鑰、`run-runtime/` 與 `engine.jar` 分離、瀏覽器探測可執行。

## 架構約束

- 12-Factor：組態與密鑰只經環境變數或 Forgejo 機密。
- 行為以 [WI-1100](WI-1100-publishable-image.md) 的入口為準，不改 Engine 行為。
- 單一事實來源：建置規則只定義一處。

## 阻擋與前提

- 前提：runner 為 amd64、能執行容器映像檔建置（含 BuildKit 的額外建置情境）、具備建置所需工具與對外網路，並能以憑證推送到 registry。待 homelab-deployer 確認；任一不成立則本項不派工。
- 前提：已調大 `traefik-dev` 的讀取逾時（若 runner 經由它推送）。
