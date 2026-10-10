# WI-1100 可推送的 Engine 映像檔與不在本機建置的 Compose 範本

狀態：已核可（2026-10-09）。回答：要達成什麼行為，使 Engine 映像檔能在筆電建置、經 registry 部署到 arr。

## 目標

維運者在筆電上以單一入口，從乾淨且已 commit 的工作樹產出 `linux/amd64` 的瀏覽器版 Engine 映像檔，以 commit 識別的 tag 推到 registry；部署端只引用該映像檔即可運行完整的 Engine 加 PostgreSQL，不需 Gradle、JDK、Node 或 runline 原始碼。

## 範圍

- 內：建置並推送的單一入口與其說明（放 `deploy/`）；一份「引用已發佈映像檔、不含建置步驟」的 Compose 範本；對應文件的現況描述。
- 外：Forgejo runner 自動化、arr 的 Terraform／Traefik 設定、GPU 渲染。

## 驗收條件

1. 在 arm64 與 amd64 的筆電上執行建置入口，產出的映像檔架構都是 `linux/amd64`。
2. 映像檔的 tag 由 commit 識別決定；工作樹有未提交變更時，建置入口拒絕推送並說明原因。
3. 不使用浮動 tag（`latest`）作為部署引用。
4. 入口接受 registry 位址等環境相關值，來自環境變數，不寫死在版本庫；registry 憑證不寫入檔案、映像檔或命令列。
5. 映像檔內容符合現行規則：不含密鑰與組態；`engine.jar` 與 `run-runtime/` 維持分離（ADR-001）；以非 root 帳號運行。
6. 引用已發佈映像檔的 Compose 範本涵蓋瀏覽器版（含 `init`、受信任根憑證目錄、虛擬顯示），並與現行 `compose.yaml` 與 `compose.browser.yaml` 行為一致（migrate 先於 engine、停止等待大於寬限時間、就緒探測、三個持久目錄）。
7. 範本中 engine 不發布連接埠到宿主，使其可由同網路的反向代理存取。
8. 以一個乾淨的 Docker 主機，只拿到映像檔與範本（沒有原始碼），能啟動並使就緒探測通過。
9. 瀏覽器探測（`runline-browser-probe`）在已發佈的映像檔中可執行並通過。
10. 「run 內看不到 Engine 的類別」在該映像檔上仍然成立（pipeline-engine 要求在打包後的產物上驗證）。

## 架構約束

- 沿用 [04-deployment.md](../../pipeline-engine/04-deployment.md) 與 [Docker 指南](../../pipeline-engine/04-deployment-docker.md)；不改 Engine 行為。
- 12-Factor：組態與密鑰只經環境變數或機密檔。
- 不新增只在此部署成立的程式分支；差異只在部署檔。

## 阻擋與前提

- Q5 已確認可用，registry 位址與帳號由環境提供。
- arr 為 amd64，已確認。
- 範本須讓使用者能設定：Engine 容器的記憶體與 CPU 上限、共享記憶體大小、根憑證的掛載、GPU 的裝置與群組（皆經變數，不寫死）；以及一個一次性的 root 初始化容器，為 shared、resources 兩個目錄準備擁有者後 engine 才啟動（[04](../04-deployment.md)「資料」）。postgres-data 不預建。
- Q4：交叉建置在 arm64 筆電上需 Rosetta／QEMU 模擬；建置入口須在缺少模擬時明確失敗並說明。
