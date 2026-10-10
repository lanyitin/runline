# ADR-026 homelab arr 的部署形態與映像檔來源

狀態：已核可（2026-10-09）。回答：runline 在 arr 上用什麼形態運行、映像檔從哪來。

## 背景

使用者要在 arr LXC 運行 runline，筆電以 `https://runline.app.lan` 使用。arr 已有 Docker、Traefik 與 step-ca 簽發的 TLS，但沒有 JDK／Node，只有 2 vCPU 且與約 15 個容器共用。服務由 Terraform 渲染 compose，慣例是 CI 建置映像檔、arr 只 pull。

## 選項

| 選項 | 優點 | 缺點 |
|---|---|---|
| **A. 筆電建置映像檔，推 Forgejo registry，arr 以 compose pull（採用）** | 與 arr 其他服務一致；arr 不需 JDK／Node；建置不吃 arr 的 2 vCPU；沿用 runline 現成的 Docker 部署 | 依賴 registry；筆電若是 arm64 需交叉建置 amd64；建置是手動步驟 |
| B. 在 arr 上直接建置 | 少一個 registry 依賴 | arr 沒有 JDK 25 與 Node；Gradle 建置吃緊共用的 2 vCPU；違反 arr 的不可變慣例 |
| C. systemd 直接裝在 LXC | 無容器層 | 要在 LXC 內裝 JDK 25 與 PostgreSQL；與 arr 的 compose 管理面脫節 |

## 決策

採 A，映像檔為瀏覽器版（digest-rss 需要；僅 amd64，在 arm64 筆電上交叉建置）。入口為 arr 既有 Traefik；engine 不發布到宿主。建置自動化（Forgejo runner）待驗證其能跑 Gradle 與 JDK 25 後再評估，不屬本決策。

## 後果

- pipeline-engine 的 Docker 部署只描述「本機 build」；本案新增「由 registry pull」的路徑，見 [WI-1100](../work-items/WI-1100-publishable-image.md)。
- 瀏覽器版只有 amd64，arr 是 amd64；筆電建置需模擬，較慢。
- 首版為全新 instance 且不備份，資料遺失風險已由使用者接受。
- 密鑰沿用 homelab 的 fnox → `TF_VAR_*` 機制。
- 私有 CA 的根憑證需提供給 Engine，做法見 [04](../04-deployment.md)「私有 CA 的信任」。
