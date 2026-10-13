# arr 部署架構

本文回答：runline 在 arr 上的服務拓撲、路由與 TLS、資料位置、密鑰與升級流程各是什麼。

## 現況（homelab-deployer 實測）

- arr：非特權 Incus LXC，已開 nesting，Docker 29 與 compose 可用；2 vCPU、約 31 GB RAM；沒有 JDK 與 Node。已跑約 15 個容器。
- 宿主只對外開 80 與 443，由 arr 內的 Traefik v3.7（docker provider，以 compose 標籤路由）接手。
- DNS：`app.lan` 由 Technitium 解析，記錄由 Terraform 管；`runline.app.lan` 尚無記錄。
- TLS：內部 CA step-ca，Traefik 以 ACME DNS-01 為每個主機名申請獨立憑證（效期 720 小時，自動續）。筆電已信任該根 CA。
- 部署慣例：服務由 Terraform 渲染進 `/opt/arr/compose.yaml`，再由 systemd path 單元觸發 compose 更新。

## 拓撲

- 三個服務加入 arr 的 compose：postgres（17）、migrate（一次性，先於 engine）、engine。內容與 runline 的 Docker 部署相同，差別在映像檔來源（registry，不在 arr 建置）與入口（Traefik，不發布 8080）。
- 這是全新的 instance：postgres 容器、三個持久目錄與組態都在 arr 的 compose 內定義，不連線既有資料庫。
- engine 使用瀏覽器版（真實 Chrome、虛擬顯示、`init`、JVM 信任私有 CA），疊加方式見 [deploy/browser.md](../../../deploy/browser.md)。
- engine 只在 compose 網路內以 8080 提供服務，不發布到宿主，與 arr 上其他容器的埠號互不衝突。
- 單實例，不擴縮（[04-deployment.md](../pipeline-engine/04-deployment.md)「擴縮」）。

## 路由與 TLS

- 筆電 → 443 → Traefik 依主機名稱 `runline.app.lan` 導到 engine:8080。`/` 與 `/api/v1` 走同一個 upstream（Console 用同源相對路徑，Engine 不啟用 CORS），WebSocket 需通過（Traefik 原生支援）。
- TLS 終止在 Traefik，滿足「TLS 終止於 Engine 之前」的必要條件（[ADR-017](../pipeline-engine/adr/ADR-017-console-websocket-and-token.md)）。Traefik 到 engine 在 compose 內網以 HTTP。
- 啟用 `runline.app.lan` 需要三件事同時成立：DNS 記錄、憑證、Traefik 路由；前兩者由 homelab 既有的「web app 名單」機制產生。
- 探測端點 `/api/v1/health/*` 免認證，與 API 同域同埠，經 Traefik 對 LAN 可見；內容不含機密，已接受。

## 映像檔

- 在筆電對 `linux/amd64` 交叉建置瀏覽器版（Chrome 只有 amd64；arm64 筆電須模擬）並推到 Forgejo registry；Chrome 版本固定。tag 使用不可變的版本識別（commit），不使用浮動的 `latest`，使回滾與稽核可行。
- arr 使用 `browser-transcribe` 映像（`<registry>/runline-engine-browser-transcribe:<tag>`，Chrome 加 ffmpeg 與 VAD 工具，供同一個 Engine 跑 digest-rss 與 transcribe）；單獨需要 Chrome 的環境仍可用 `browser` 映像（`runline-engine-browser`）。兩個映像由 `deploy/publish-image --stage <階段>` 建置，推送 `v*` tag 時 Forgejo workflow 以相同的 commit 與額外 tag 一起推送（WI-1104，細節見 [deploy/browser.md](../../../deploy/browser.md)）。
- arr 的 compose 以明確 tag 引用；升級＝建置新 tag、推送、更新 tag、apply、確認容器真的重啟（apply 成功不等於容器已重啟，須檢查單元結果與執行中的映像檔 tag）。
- 映像檔不含密鑰與組態，維持 pipeline-engine 現行規則。

## 資料

- 持久目錄放在 `/data/appdata/runline/` 下的 postgres-data、shared、resources（bind mount，`/data` 為 hdd 上的 ZFS 卷）。
- arr 的 Docker 沒有 userns-remap，容器內 uid N 就是 arr 內的 uid N，不需換算。擁有者的準備方式：
  - postgres-data：不預建，由 postgres 容器首次啟動自行建立並擁有（同 arr 上 metatube-postgres 的做法）。
  - shared、resources：由 compose 內一次性的 root 初始化容器建立 `/data/appdata/runline` 與這兩個目錄，並把這兩個目錄（不遞迴）的擁有者設為 Engine 執行的 uid:gid（arr 用 1000:1000，與 `/data/media` 的擁有者一致，見 [ADR-041](adr/ADR-041-engine-image-arbitrary-uid.md)；映像本身預設 10001，由 compose 的 `user: "1000:1000"` 覆寫）；engine 在它成功完成後才啟動（同 arr 上 rqbit 的做法）。不在 cloud-init 做，因為它只在首次開機執行。
- 首版不備份（已接受）：postgres 的 jar、定義與 run 紀錄，以及 resources 的檔案，在磁碟毀損時遺失。resources 與 postgres 在 pipeline-engine 的規則下視為資料，所以這是明確承擔的風險，不是預設。
- shared 視為可重建的快取。

## 組態與密鑰

- 組態項目與必填檢查沿用 pipeline-engine（`.env.example` 所列）；缺漏時 Engine 啟動即失敗並列出鍵名。
- 密鑰（資料庫密碼、API token、金鑰庫密碼）以 fnox（age 加密）存成 `TF_VAR_*`，由 Terraform 渲染；不寫進版本庫、`.tf` 或 `.tfvars`。
- 金鑰庫密碼用檔案提供，不用環境變數（unsafe pipeline 可讀環境變數；digest-rss 是 unsafe pipeline，此點必要）。
- 已知限制：渲染後的 compose 含明文密鑰，檔案權限須限制為管理者。

## 私有 CA 的信任

- 根憑證在 arr 內 `/usr/local/share/ca-certificates/homelab-root.crt`（來源是 Terraform 變數，路徑由 arr 的既有變數決定）。arr 上其他容器以唯讀單檔掛載取得它。
- Engine 的 `RUNLINE_TRUSTED_CA_DIR` 讀的是目錄中的 `*.pem`、`*.crt`、`*.cer`；做法是把該檔以唯讀單檔掛載到容器內專用目錄（目錄內只有這一個檔），Engine 啟動時把它與 JDK 內建憑證合併。根憑證更新時 Terraform 不需修改掛載。
- 信任是整個 JVM 的設定（digest-rss 部署文件的既有規則）。

## GPU

- arr 內已有 `/dev/dri/card0` 與 `renderD128`（Incus 的 gpu 裝置，群組 gid 由 arr 既有的變數提供）；arr 層不需要變更。
- Engine 容器疊加 GPU 設定：交付 `/dev/dri`，並加入該群組。gid 來自同一個變數，不寫死。多個容器（Jellyfin、pi-web）共用 render node 不衝突，限制是 iGPU 與系統共用 RAM。
- `/dev/dri` 存在不等於硬體加速：2026-08 曾驗證 arr 內沒有可用的硬體加速。部署後以映像檔內的探測實測，不預設為真（Q12）。沒有硬體渲染時，擷取可能得到看似成功的 bot 檢查頁，digest-rss 須能辨識（其 WI-1000 條件通過規則已涵蓋軟體渲染）。

## 資源預算

- 瀏覽器版的 Chrome 加 JVM 需求高於一般版，arr 只有 2 vCPU 且與約 15 個容器共用，現有容器沒有任何 CPU／記憶體上限。
- 決定：Engine 與 postgres 容器不設 CPU 與記憶體上限，與 arr 現有容器一致。代價是 Chrome 可能吃滿 2 vCPU，拖慢 Jellyfin 與 pi-web；以並行 run 上限 1 降低此風險，觀察 `docker stats` 後再決定是否加上限。
- 共享記憶體一定要設為 1 GB：Chrome 的預設 64 MB 會使其莫名崩潰，這不是資源上限。
- Engine 的並行 run 上限（`RUNLINE_MAX_CONCURRENT_RUNS`）初始為 1。
- arr 上的 `pi-web` 也跑 Chrome；同時執行時 2 vCPU 會很擠。

## 上線風險

arr 的 compose 一變動會重啟整個堆疊。`pi-rss-digest` 已設定為不執行，所以重啟不會中斷它的 run；pi-web 若有進行中的工作仍會被中斷，升級前先確認。
