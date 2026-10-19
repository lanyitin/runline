# 瀏覽器部署手冊

本文回答：要讓 Engine 為「驅動有畫面的 Chrome 的 pipeline」（digest-rss，[ADR-022](https://forgejo.dev.lan/runline/runline-digest-rss/src/branch/main/docs/stable/digest-rss/adr/ADR-022-unsafe-pipeline-with-local-browser.md)）運作，Docker 與 systemd 兩種部署各要提供什麼、怎麼設定、怎麼驗證，以及 SQLite 原生程式庫在部署環境的條件。需求來源是 [digest-rss 的 04](https://forgejo.dev.lan/runline/runline-digest-rss/src/branch/main/docs/stable/digest-rss/04-deployment.md) 與 [WI-1001](https://forgejo.dev.lan/runline/runline-digest-rss/src/branch/main/docs/stable/digest-rss/work-items/WI-1001-engine-browser-deployment.md)；Engine 平台共通的部分見 [Docker](../docs/stable/pipeline-engine/04-deployment-docker.md) 與 [systemd](../docs/stable/pipeline-engine/04-deployment-systemd.md) 指南。

本文標明每一項是「實測」（在本專案的開發機上實際執行過）還是「未實測」及原因。實測環境：macOS arm64、Colima（`vmType: vz`、Rosetta，2 CPU、4 GiB）上的 `linux/amd64` 容器、Google Chrome 155.0.8059.39、沒有 `/dev/dri`。**沒有在有 GPU 的主機或 systemd 主機上實測**。

## 檔案一覽

| 檔案 | 用途 |
|---|---|
| `docker/Dockerfile` 的 `browser` 階段 | 在 Engine 映像上加 Chrome、Xvfb、字型與下列腳本；只建 `linux/amd64` |
| `docker/compose.browser.yaml` | 疊加檔：用 `browser` 階段、啟用 init、掛載私有 CA 的根憑證 |
| `docker/compose.browser-gpu.yaml` | 疊加檔：把 `/dev/dri` 交給容器 |
| `publish-image` | 建置入口：在筆電交叉建置 `linux/amd64` 的瀏覽器版映像並以 commit 為 tag 推到 registry |
| `docker/compose.registry.yaml` | 獨立範本：引用已發佈的映像（不建置），含 PostgreSQL、`init`、migrate、engine |
| `docker/compose.registry-gpu.yaml` | `compose.registry.yaml` 的疊加檔：GPU 裝置與群組 |
| `check-pins` | 自動檢查：工作流程的 action、Dockerfile 與各 Compose 範本的映像任何一個沒有帶雜湊就失敗（見「更新固定的雜湊」） |
| `test/pins-test.sh` | `check-pins` 的檢查：對目前的檔案通過，對故意放回標籤的各種情形失敗 |
| `test/publish-test.sh` | 不需 Docker daemon 的檢查：建置入口的拒絕條件、範本展開後的內容 |
| `bin/runline-entrypoint` | 容器（與 systemd 選用）的啟動：虛擬顯示、JVM 信任、啟動時的渲染器報告，然後以 `exec` 啟動 Engine |
| `bin/runline-browser-probe` | 讀出 Chrome 取得的 WebGL 渲染器；可要求硬體渲染 |
| `bin/runline-build-truststore` | 把 JDK 的 CA 與目錄中的 PEM 合成一個 JVM 信任庫 |
| `systemd/runline-engine-browser.conf` | systemd 的 drop-in |
| `docker/compose.browser-transcribe.yaml`、`Dockerfile` 的 `browser-transcribe` 階段 | 與轉錄覆蓋層並用時的單一映像（Chrome 與 ffmpeg、VAD 工具位置、模型檔）；用法見 [transcribe.md](transcribe.md) 的「與瀏覽器並用」 |

## 發佈映像檔與引用它的 Compose 範本（WI-1100）

部署端（例如 homelab 的 arr）不建置：維運者在筆電上建置並推送，部署端只引用映像檔。

建置入口（要求 Docker daemon 在跑、Gradle 的 Node 與 JDK 就緒、已 `docker login`）：

```
RUNLINE_REGISTRY=registry.example/owner deploy/publish-image          # 建置並推送
RUNLINE_REGISTRY=registry.example/owner deploy/publish-image --no-push   # 只在本機建置
RUNLINE_REGISTRY=registry.example/owner deploy/publish-image --stage browser-transcribe   # 發佈另一個階段
```

- `--stage <階段>`（選填，WI-1104）：要發佈的 `Dockerfile` 階段，`browser`（預設，行為與沒有此參數時完全相同）或 `browser-transcribe`（Chrome 加 ffmpeg、ffprobe、VAD 工具與模型，見 [transcribe.md](transcribe.md) 的「與瀏覽器並用」）。映像名稱隨階段：`runline-engine-browser` 或 `runline-engine-browser-transcribe`；tag 規則、架構檢查與工作樹檢查兩者相同。其他值在建置之前就被拒絕。arr 用 `browser-transcribe`；單獨需要 Chrome 的環境仍可用 `browser`。

- `RUNLINE_REGISTRY`（環境變數，必填）：registry 位址與路徑，不含 tag、帳密。映像為 `$RUNLINE_REGISTRY/runline-engine-<階段>:<commit 前 12 碼>`（預設階段 `browser` 即 `runline-engine-browser`），最後一行輸出印出完整引用。沒有浮動 tag。
- `RUNLINE_EXTRA_TAG`（環境變數，選填）：同一映像再加的第二個 tag，例如版本 tag `v1.2.0`。加在 commit tag 之外而非取代，commit tag 先推；拒絕 `latest` 與不合法的 tag，且在建置之前檢查。入口每推一個引用就印一行。
- 憑證：入口不讀不存。先 `docker login <host>`（密碼由終端機提示或 `--password-stdin`），推送使用該登入。
- 工作樹有未提交或未追蹤的變更時拒絕推送並說明原因；`--no-push` 仍可建置，tag 為 `<commit>-dirty`。
- 一律 `linux/amd64`。arm64 筆電上 Docker 的虛擬機須以 Rosetta 模擬（Colima：`vmType: vz`、`rosetta: true`）；入口先實際跑一個 amd64 容器，不行就明確失敗並說明。建置後檢查映像的架構，不是 `linux/amd64` 即不推送。映像帶 `org.opencontainers.image.revision` 標籤（完整 commit）。

### 推送 `v*` tag 時由 Forgejo Action 建置（WI-1101、ADR-027）

`.forgejo/workflows/publish-image.yaml` 只在推送符合 `v*` 的 tag 時執行（分支與其他 tag 不觸發），在 runner 上準備 JDK 25、Node（`.node-version`）與 registry 登入後依序對 `browser` 與 `browser-transcribe` 兩個階段執行同一個 `deploy/publish-image`，並以 `RUNLINE_EXTRA_TAG` 帶入觸發它的 tag（兩個映像的 commit tag 與額外 tag 相同）。兩個映像都先以 `--no-push` 建置完才推送，所以任何一個建置失敗時 registry 什麼都沒有；若第二個推送失敗，工作失敗，日誌中第一個推送步驟已印出它推送的引用，重新執行同一個 tag 即補齊。規則只在入口一處，產物與筆電的可互換；它不接觸 arr、不改部署設定，上線仍由維運者手動把 `RUNLINE_IMAGE` 改成新的 tag。

在 Forgejo 的儲存庫設定（Actions）需要：

| 名稱 | 種類 | 說明 |
|---|---|---|
| `RUNLINE_REGISTRY` | 變數 | registry 位址與路徑，例如 `forgejo.dev.lan/runline` |
| `REGISTRY_USER` | 機密 | 持有權杖的帳號 |
| `REGISTRY_TOKEN` | 機密 | 只授與 `write:package` 的存取權杖 |

登入以標準輸入傳權杖，job 結束時（含失敗）登出。工作流程的 `runs-on: ubuntu-latest` 是 runner 上提供 Docker CLI 並連到 Docker daemon 的標籤（同一個 Forgejo 的 streamexa 發佈流程用同一標籤）；`docker` 標籤的環境沒有 Docker CLI（首次 run 以 exit code 127 失敗）。工作一開始（安裝 JDK、Node 之前）先執行 `docker version`，不可用就以明確訊息立即失敗。Docker CLI 由 runner 提供，工作流程不安裝；runner 須為 amd64。

部署端只需要 `docker/compose.registry.yaml`、`.env`（由 `docker/.env.example` 複製，置換佔位值）與下列變數，不需要原始碼：

| 變數 | 說明 |
|---|---|
| `RUNLINE_IMAGE`（必填） | 入口印出的映像引用（含 commit tag） |
| `RUNLINE_USER` | engine、migrate 執行的 uid:gid，也是 `init` 容器給 `shared`、`resources` 的擁有者；預設 `10001:10001`（映像的帳號）。見 [ADR-041](../docs/stable/homelab-arr/adr/ADR-041-engine-image-arbitrary-uid.md) |
| `RUNLINE_DATA_DIR`（必填） | 宿主目錄；其下的 `postgres-data`、`shared`、`resources` 是三個持久目錄 |
| `TRUSTED_CA_FILE`（必填） | 私有 CA 根憑證檔，唯讀掛載為容器內信任目錄中唯一的檔案 |
| `ENGINE_SHM_SIZE` | engine 的共享記憶體，預設 `1gb` |
| `ENGINE_MEM_LIMIT`、`ENGINE_CPUS` | engine 的記憶體與 CPU 上限，預設不限 |
| `GPU_DEVICE`、`RENDER_GROUP_ID` | 只用於 `compose.registry-gpu.yaml`：裝置（預設 `/dev/dri`）與其數字群組（必填） |

行為與 `compose.yaml` 加 `compose.browser.yaml` 相同：migrate 先於 engine、`stop_grace_period` 45s、就緒探測、`init: true`；差異是 engine 不發布連接埠（由同一 Compose 網路上的反向代理連 8080），並多一個一次性的 root 容器 `init`，在 engine 啟動前建立 `shared`、`resources` 並把這兩個目錄（不遞迴）的擁有者設為 `RUNLINE_USER`（預設 `10001:10001`）。`RUNLINE_USER`（選用）同時是 engine 與 migrate 執行的 uid:gid（例如 `1000:1000`），未設時用映像的預設帳號。`postgres-data` 不預建，由 PostgreSQL 首次啟動時建立。

驗證：`deploy/test/publish-test.sh`（不需 daemon）。其餘在 Colima（Rosetta）與本機 `registry:2` 上實測：從乾淨 clone 建置並推送，刪除本機映像後在只有範本、`.env` 與憑證檔的目錄 `docker compose up -d` 拉取並啟動，engine 就緒、`runline-browser-probe` 執行完成、在映像上跑一個 pipeline 確認 Engine 的類別對 run 不可見。**未實測**：amd64 原生的筆電、真實的 Forgejo registry（含認證）、GPU 疊加檔在有 `/dev/dri` 的主機。

### 更新固定的雜湊（WI-1102、ADR-032）

建置輸入都以內容雜湊引用，標籤只是旁邊給人讀的註解；上游重新發佈同一標籤不影響建置。代價是安全修補不會自動進來，由維運者決定何時升級。不使用自動更新工具。每個雜湊只定義一處：

| 輸入 | 定義處 | 雜湊的種類 |
|---|---|---|
| 工作流程的 action（checkout、setup-java、setup-node） | `.forgejo/workflows/publish-image.yaml` 的 `uses:` | 完整提交識別（40 碼），註解標明版本 |
| Engine 基底映像 `eclipse-temurin:25-jre` | `docker/Dockerfile` 的 `FROM`（`deploy/publish-image` 的架構檢查讀同一行，所以檢查與建置是同一個映像） | 多平台索引的 `sha256:` |
| PostgreSQL `postgres:17-alpine` | `docker/compose.yaml` 與 `docker/compose.registry.yaml` 的 `image:`。後者要單獨複製到部署端，所以兩個檔案各一行；`check-pins` 要求兩處相同 | 多平台索引的 `sha256:` |

Chrome 不在其中：它以 `CHROME_VERSION` 固定版本號，從 Google 的網址下載，不驗證內容雜湊（ADR-032 已接受的限度）。

升級一個輸入時：

1. 取得新雜湊，從上游本身取得，不從第三方轉貼：
   - action：`git ls-remote https://data.forgejo.org/actions/<name> 'refs/tags/<版本>' 'refs/tags/<版本>^{}'`。若有 `^{}` 那一行，用它（標籤指向的提交）；否則用第一行。
   - 映像：`docker buildx imagetools inspect <名稱>:<標籤>`，取 `Digest:`，且確認 `MediaType` 是 `application/vnd.oci.image.index.v1+json`（或 `...manifest.list.v2+json`），Manifests 內含 `linux/amd64`。要的是索引的雜湊，不是單一平台的。
2. 確認來源：註解中的標籤確實解析到該雜湊（上面的指令就是在標籤上查的）；映像的 registry 是 Docker Hub 的官方映像（`docker.io/library/...`）。對照上游的版本說明確認這是要升級的版本。
3. 更新引用：只改上表的定義處，並同步改旁邊註解的版本或標籤。PostgreSQL 兩個 Compose 檔一起改。
4. 重新驗證：`deploy/check-pins`（全部有雜湊、兩個 PostgreSQL 引用相同）、`deploy/test/pins-test.sh`、`deploy/test/publish-test.sh`；然後 `RUNLINE_REGISTRY=registry.invalid/x deploy/publish-image --no-push` 建置，檢查映像（非 root、無密鑰、`run-runtime/` 與 `engine.jar` 分離、`runline-browser-probe` 可執行），並以 `compose.registry.yaml` 啟動到 engine 就緒。action 的升級在推送新的 `v*` tag 後於 runner 上驗證。

## 環境變數

全部經環境變數提供（Docker：`.env` 與映像的預設；systemd：`runline.env`）。缺漏時不會讓 Engine 啟動失敗，除非標明。

| 變數 | 預設 | 說明 |
|---|---|---|
| `DISPLAY` | `:99`（映像） | Chrome 繪製的顯示。pipeline 與探測都讀它 |
| `CHROME_BIN` | `/usr/bin/google-chrome-stable`（映像） | pipeline 啟動的 Chrome |
| `CHROME_ARGS` | `--no-sandbox --disable-dev-shm-usage --ignore-gpu-blocklist`（映像） | 加在 Chrome 啟動旗標之後。`--ignore-gpu-blocklist` 缺少時 Chrome 在沒有 GPU 的機器上沒有 WebGL（WI-1000 實測）；`--no-sandbox` 與 `--disable-dev-shm-usage` 是容器內的需要（見下）。主機上的 systemd 部署只需要 `--ignore-gpu-blocklist` |
| `RUNLINE_VIRTUAL_DISPLAY` | `on` | `on` 由 `runline-entrypoint` 在 `DISPLAY` 上啟動 Xvfb；`off` 把顯示留給別處提供 |
| `RUNLINE_VIRTUAL_DISPLAY_SIZE` | `1920x1080x24` | 虛擬顯示的大小與色深 |
| `RUNLINE_BROWSER_PROBE` | `on` | 啟動時在 log 報告 Chrome 的渲染器（不影響 Engine 啟動與就緒） |
| `CHROME_REQUIRE_HARDWARE_RENDERING` | `false` | `true` 要求硬體渲染，分工見「GPU 與硬體渲染」 |
| `RUNLINE_TRUSTED_CA_DIR` | 未設定 | PEM 檔的目錄，其中的根憑證由整個 JVM 信任（見「根憑證信任」）。**設了卻讀不了、或其中有不是憑證的檔案時，容器啟動失敗**（快速失敗，不在沒有給定的信任下啟動） |
| `TRUSTED_CA_HOST_DIR` | `./trusted-ca` | 僅 Docker：宿主機上放這些 PEM 的目錄，唯讀掛到 `RUNLINE_TRUSTED_CA_DIR` |
| `RENDER_GROUP_ID` | 無 | 僅 `compose.browser-gpu.yaml`：宿主機上 render node 的數字群組 |
| `JDK_JAVA_OPTIONS` | 未設定（Docker）；drop-in 設 `-Djava.io.tmpdir=/var/cache/runline`（systemd） | JVM 自己讀的啟動選項；本文用它設 `-Djava.io.tmpdir`、`-Dorg.sqlite.tmpdir`。`runline-entrypoint` 在它之後附加信任庫選項 |

## Docker

```
./gradlew :engine:engineDistribution
cp deploy/docker/.env.example deploy/docker/.env        # 取代每個佔位值
docker compose -f deploy/docker/compose.yaml -f deploy/docker/compose.browser.yaml up --build -d
```

（或在 `.env` 設 `COMPOSE_FILE=compose.yaml:compose.browser.yaml`。）同一個 Engine 還要跑 transcribe pipeline 時，加上 `compose.transcribe.yaml` 與排在最後的 `compose.browser-transcribe.yaml`，得到同時有 Chrome 與 ffmpeg 的單一映像（[transcribe.md](transcribe.md) 的「與瀏覽器並用」）。疊加檔設定 `platform: linux/amd64`：Google 只提供 amd64 的 Chrome。在 arm64 的開發機上，Docker 的虛擬機必須以 Rosetta 模擬 amd64（Colima：`vmType: vz`、`rosetta: true`）；以 qemu 模擬時 Chrome 155 在啟動時中止（WI-1000 實測）。

| 項目 | 內容 |
|---|---|
| 映像 | `Dockerfile` 的 `browser` 階段，建在 `engine` 階段之上（`compose.yaml` 指定 `target: engine`，原本的映像不變）。以 `ARG CHROME_VERSION`（預設 `155.0.8059.39-1`，WI-1000 量測的版本）從 Google 的套件庫安裝 Chrome，另含 Xvfb、`fonts-noto-cjk`、`curl`、`procps`。非 amd64 建置時，建置步驟以說明訊息失敗 |
| 可重現性 | 映像**不在** WI-32 的位元組級可重現範圍內：那個保證是給 `engine.jar` 等 jar 的，映像只是打包它們。Chrome 以固定版本安裝，基底映像以雜湊固定（見「更新固定的雜湊」），但 apt 的相依套件仍會隨時間改變。發佈建置（`verifyReproducibleRelease`）不受影響 |
| 帳號 | 與 Engine 相同：預設 `runline`（uid 10001），可由 `user:` 改為其他 uid:gid，包括 `/etc/passwd` 沒有項目的 uid（ADR-041）。Chrome 在容器內以 `--no-sandbox` 執行（容器本身是隔離邊界；沙箱需要使用者命名空間，預設的容器沒有）；`HOME=/home/runline` 供 Chrome 放小檔案（憑證資料庫、設定），不需持久；該目錄是 root 擁有、1777（sticky），任何 uid 都寫得進去，各 uid 只能動自己建的檔案（位置與理由：[04-deployment-docker](../docs/stable/pipeline-engine/04-deployment-docker.md)「Engine 的帳號」） |
| 顯示 | `runline-entrypoint` 在 `DISPLAY` 上啟動 Xvfb（`-nolisten tcp`）。Xvfb 以子殼層啟動，由 init 接手，不是 JVM 的子行程。容器重啟（`docker restart`）時先清掉上次留下的鎖檔（實測：重啟後顯示正常出現）。顯示沒有在 10 秒內出現時只在 log 警告，Engine 照常啟動 |
| init | `compose.browser.yaml` 設 `init: true`：PID 1 是 Docker 的 init（tini），回收被取消而遺留的 Chrome 輔助行程。Java 為 PID 1 時它們成為殭屍（見「實測」）。`docker run` 要加 `--init` |
| `/dev/shm` | 預設 64 MiB，Chrome 會不足；映像的 `CHROME_ARGS` 帶 `--disable-dev-shm-usage` 讓它改用 `/tmp`。替代做法是容器設 `shm_size: 1gb` 並拿掉該旗標（未實測） |
| 暫存目錄 | JVM 的 `java.io.tmpdir` 是容器的 `/tmp`（容器自己的可寫層，可執行）。SQLite 的原生程式庫與 Chrome 的使用者資料目錄都在這裡。**不要**把 `/tmp` 換成 `noexec` 的 tmpfs；要換就看「SQLite 的暫存目錄」 |
| 機密 | 映像沒有機密與組態；信任的 PEM 是公開的憑證，唯讀掛載 |
| 就緒 | 不變。健康檢查仍是 `HealthCheckKt ready`（ADR-018）。Engine 不認識 Chrome：沒有 Chrome、沒有顯示、探測失敗，都不影響就緒（見測試） |

### 啟動時的 log

容器啟動時 log 有（節錄，時間省略）：

```
runline-entrypoint: virtual display :99 is up (1920x1080x24)
browser-probe: WARNING: software rendering: vendor='Google Inc. (Mesa)' renderer='ANGLE (Mesa, llvmpipe (LLVM 21.1.8 256 bits), OpenGL 4.5)' /dev/dri=absent
browser-probe: WARNING: the container has no /dev/dri; give it the device for hardware rendering (deploy/browser.md, GPU)
```

探測在背景執行，約 10 秒（Rosetta 下）後出現，不延遲 Engine。隨時可以手動執行：`docker compose exec engine runline-browser-probe`。

## systemd

**實測範圍**：在 Colima 上的特權容器內執行 systemd 259（Ubuntu 26.04、JDK 25、Rosetta 的 amd64）驗證過下列步驟（檢查項目見本節與各節的「systemd」說明）；**不是真實的主機**：沒有 journald、沒有 GPU，容器內的 Chrome 需要 `--no-sandbox`，主機的沙箱與 `PrivateDevices` 等加固沒有驗證。步驟與前置條件：

1. 主機前置條件：
   - 真實的 Google Chrome（Google 的套件，非 Chromium 衍生版），Engine 的服務帳號可執行。
   - Xvfb（套件 `xvfb`），除非 `DISPLAY` 指向主機上已有的實體顯示（此時設 `RUNLINE_VIRTUAL_DISPLAY=off`）。
   - `curl`、`procps`（`pkill`）、`bash`、`awk`（啟動時的渲染器報告與信任庫腳本用）；沒有它們時 Engine 照常啟動，只缺報告。
   - `keytool`（隨 JDK；信任庫腳本用）。
2. 安裝腳本與 drop-in：

   ```
   install -d /opt/runline/bin
   install -m 0755 deploy/bin/runline-entrypoint deploy/bin/runline-browser-probe deploy/bin/runline-build-truststore /opt/runline/bin/
   install -d /etc/systemd/system/runline-engine.service.d
   install -m 0644 deploy/systemd/runline-engine-browser.conf /etc/systemd/system/runline-engine.service.d/browser.conf
   systemctl daemon-reload
   ```

3. 在 `/etc/runline/runline.env` 設定 `runline.env.example` 的「browser」各項（選用）。`JDK_JAVA_OPTIONS` 由 drop-in 為 Engine 單元設定（`-Djava.io.tmpdir=/var/cache/runline`），不要在 `runline.env` 再設：環境檔的值會取代它，而且遷移單元也讀環境檔，那時目錄還不存在。
4. `systemctl restart runline-engine`。

與轉錄 drop-in 同時安裝時，兩者放在同一個 `runline-engine.service.d/` 目錄（`browser.conf`、`transcribe.conf`），沒有衝突；步驟見 [transcribe.md](transcribe.md) 的「與瀏覽器並用」。

顯示來源：drop-in 讓 `ExecStart` 經由 `runline-entrypoint`，它在服務的 control group 內啟動 Xvfb（停止服務即結束它），顯示號碼是 `DISPLAY`（預設在 `runline.env` 設 `:99`；與主機上其他 X 伺服器不同號碼）。Chrome 由 Engine 的 pipeline 啟動，在同一個 control group 內，所以 `systemctl stop` 與 `restart` 會一併結束所有遺留的 Chrome 行程；回收殭屍由 systemd 與 init 負責，不需另設。

`HOME`：Chrome 需要可寫的家目錄，沒有時在啟動時當掉（實測：以 `useradd --system --no-create-home` 做的帳號，Chrome 以 `Trace/breakpoint trap` 結束）。drop-in 設 `HOME=/var/cache/runline`。

與 Docker 的差別：主機上的 Chrome 使用沙箱，所以 `CHROME_ARGS` 不帶 `--no-sandbox`；若主機禁用非特權使用者命名空間（某些加固的發行版），Chrome 會在啟動時失敗，這時才需要 `--no-sandbox`，並意識到它放寬了隔離。

## 根憑證信任

信任是**整個 JVM** 的設定，不在 pipeline 裡，也不寫死在任何程式：FreshRSS、rqbit、streamexa 的 HTTPS 由 JVM 驗證（pipeline 自帶的 HTTP 用戶端與 Engine 自己的資源檢查都用 JVM 的預設信任）。

做法：`RUNLINE_TRUSTED_CA_DIR` 指向放 PEM 檔（`*.pem`、`*.crt`、`*.cer`，每個檔一個或多個憑證）的目錄。`runline-entrypoint` 在每次啟動時以 `runline-build-truststore` 把 JDK 自己的 CA 加上這些憑證合成一個 PKCS12 信任庫（放在只有服務帳號進得去的暫存目錄），並在 `JDK_JAVA_OPTIONS` 附加 `-Djavax.net.ssl.trustStore=…`。這樣公開的 CA 仍被信任，私有 CA 另外被信任；JDK 升級時自動跟進（每次啟動重建）。信任庫放在 `mktemp -d` 建的目錄（預設 `/tmp`，systemd 主機上每次啟動留下一個約 150 KB 的目錄，由主機的暫存清理處理）。信任庫的密碼是 JDK 自己 `cacerts` 的 `changeit`，它只保護公開憑證的完整性，不是機密。

- Docker：把根憑證放進宿主機的 `deploy/docker/trusted-ca/`（或 `TRUSTED_CA_HOST_DIR`），重啟容器。目錄是空的時什麼都不加。
- systemd：把 PEM 放進 `/etc/runline/trusted-ca/`，在 `runline.env` 設 `RUNLINE_TRUSTED_CA_DIR=/etc/runline/trusted-ca`，重啟。
- Chrome 不受此設定影響：Chrome 用自己的信任（NSS 與內建根憑證庫）。digest-rss 的 pipeline 是以 JVM 的 HTTP 用戶端呼叫上述服務，Chrome 只開被擷取的公開網站；如果日後要讓 Chrome 開私有 CA 的頁面，需要另外把根憑證放進該使用者的 NSS 資料庫（`~/.pki/nssdb`），本項未做。

驗證步驟（已在測試中以真實的 nginx 與真實的 CA 實測）：

1. 準備兩個 HTTPS 服務：一個的憑證由你信任的私有 CA 簽發，另一個由你沒有交給 Engine 的 CA 簽發。
2. 把第一個 CA 的根憑證放進信任目錄，重啟 Engine。
3. 用管理員的 token 為兩個服務各建一個 `openai-compatible` 資源（`settings` 只要 `baseUrl`），各做一次檢查：

   ```
   curl -s -X POST -H "Authorization: Bearer $ADMIN_TOKEN" -H 'Content-Type: application/json' \
     -d '{"name":"trusted-probe","capacity":1,"type":"openai-compatible","settings":{"baseUrl":"https://homelab.example"}}' \
     http://localhost:8080/api/v1/resources
   curl -s -X POST -H "Authorization: Bearer $ADMIN_TOKEN" http://localhost:8080/api/v1/resources/trusted-probe/check
   ```

4. 預期：受信任者 `"ok":true`、`"failure":null`；另一個 `"failure":"trust_failed"`。（用完刪除這些資源。）

## 就緒與 Chrome 不可用

Engine 不依賴 Chrome。啟動腳本把顯示、信任與渲染器報告都做成「只報告、不攔阻」，唯一會讓容器啟動失敗的是 `RUNLINE_TRUSTED_CA_DIR` 設了卻不可用（給定的信任不能默默失效）。`CHROME_BIN` 指向不存在的檔案並關閉虛擬顯示時，Engine 仍通過就緒探測，log 有 `could not read the renderer`（測試 `BrowserDeploymentDegradedTest` 實測）。Chrome 不可用的後果只落在使用它的 pipeline：它的 run 失敗，並註明原因。

## GPU 與硬體渲染

### 把 GPU 交給容器（Docker）

`compose.browser-gpu.yaml` 是疊加檔，範例組態如下（`docker compose config` 驗證可解析；**沒有在有 GPU 的主機上實測**）：

```
docker compose -f deploy/docker/compose.yaml -f deploy/docker/compose.browser.yaml \
    -f deploy/docker/compose.browser-gpu.yaml up -d
```

1. 裝置掛載：`devices: - /dev/dri:/dev/dri`。
2. 讓 Engine 的 uid（預設 10001，`user:` 可改）能開 render node，兩種方式擇一：
   - **加入裝置所屬的群組**（建議）：`stat -c %g /dev/dri/renderD128` 得到數字群組，設 `.env` 的 `RENDER_GROUP_ID`；疊加檔以 `group_add` 加入。要數字，因為容器的 `/etc/group` 不認得宿主機的群組名稱。
   - **以 root 執行容器**：把疊加檔的 `group_add` 換成 `user: root`。這放寬整個容器（磁碟區、Chrome）的帳號限制，所以不建議。
3. 帳號進不了裝置時，探測會說：`/dev/dri=present, not accessible to uid <Engine 的 uid> (give the container the device's group)`（以一個權限為 600 的假 render node 模擬實測了這個訊息；沒有真實裝置）。

### 主機端（systemd）

Engine 的服務帳號（`runline`）要能開 `/dev/dri/renderD128`：把帳號加進該裝置的群組（`stat -c %G /dev/dri/renderD128`，常見為 `render` 或 `video`）：`usermod -aG render runline`，或在 drop-in 加 `SupplementaryGroups=render`。單元沒有 `PrivateDevices=`，所以不必另外放行。主機的核心與驅動要支援該 GPU（Intel iGPU 用 `i915`／`xe` 與 Mesa）。**未實測。**

### 沒有 GPU 時

仍可啟動；Chrome 的渲染器是 llvmpipe（軟體渲染），啟動時的 log 以 `WARNING: software rendering` 明講，並指出 `/dev/dri` 是不存在、不可存取，還是存在但仍是軟體渲染（見下一節）。WI-1000 對此的判定是：條件通過，前提是三類目標站點在該環境各重複至少 5 次都放行（本專案在此環境實測 15/15）；沒有 WebGL 則不通過。

### 要求硬體渲染

`CHROME_REQUIRE_HARDWARE_RENDERING=true`（預設 `false`）。分工（WI-1001 第 8 條由實作決定）：

| 角色 | 做什麼 | 狀態 |
|---|---|---|
| 部署 | 把該變數原樣交給 Engine 的行程環境（Docker：`.env`；systemd：`runline.env`）；啟動時報告渲染器，要求硬體而渲染器為軟體時多一行警告；提供 `runline-browser-probe` 作為部署時的預檢（要求硬體而為軟體渲染時結束代碼 3，讀不到渲染器為 2） | 實測（Docker，軟體渲染情形） |
| pipeline | 已實作（[WI-1006](https://forgejo.dev.lan/runline/runline-digest-rss/src/branch/main/docs/stable/digest-rss/work-items/WI-1006-page-capture.md) 第 11 條，程式在 `runline-digest-rss`）。有文章要擷取的 run 在擷取前，以 pipeline 自己啟動的 Chrome 讀出 WebGL 渲染器（log `BROWSER renderer=…`，同時記入 `run.browser_renderer`）；`CHROME_REQUIRE_HARDWARE_RENDERING=true` 且渲染器為軟體或讀不到時，run 以明確原因失敗（`BROWSER REFUSED: …`、`HardwareRenderingRequiredException`），不擷取任何頁面、不標記；未啟用時只記錄、不影響執行 | 已在 `runline-digest-rss` 以軟體渲染的環境驗證（Linux 容器，渲染器為 llvmpipe）；本儲存庫不含此程式 |

預檢與 pipeline 用同一條判定規則：渲染器字串含 `swiftshader`、`llvmpipe`、`software`、`microsoft basic`（不分大小寫），或沒有渲染器，即為軟體渲染。

### 虛擬顯示的硬體 GL

WI-1000 的來源文件指出 Xvfb 本身可能沒有硬體 GL 路徑：即使掛了 GPU，渲染器仍可能是 llvmpipe。**本專案沒有可驗證的環境**（沒有 `/dev/dri`、沒有 iGPU），以下是選項與取捨，不是結論，選哪個由使用者在目標主機上量測後決定，並依 WI-1000 的規則處理：

| 做法 | 取捨 |
|---|---|
| 維持 Xvfb，接受軟體渲染 | 最簡單，與本專案的實測相同；WI-1000 判為條件通過。指紋是 llvmpipe，目標站點今天放行，不保證日後 |
| 容器內執行有 DRI 的 X 伺服器（例如 Xorg 的 `modesetting` 驅動加 glamor，用 `/dev/dri/card0` 與無螢幕組態） | 可能得到硬體渲染；需要 `card0`（不只 render node）的存取，通常要更多權限，組態複雜；未驗證 |
| 在主機（systemd）上執行有 DRI 的 X 伺服器或無螢幕的 Wayland 合成器，Engine 連到它 | 主機部署才實際可行；與容器隔離相衝；未驗證 |
| Chrome 的 headless 模式配 GPU | **不採用**：headless 本身就是 WI-1000 排除的模式（會得到看似成功的檢查頁） |

在目標主機上記錄實際渲染器：

```
docker compose logs engine | grep browser-probe
docker compose exec engine runline-browser-probe
```

其中 `renderer='…'` 就是結果。含 `Intel`、`iris` 之類且不含 `llvmpipe`／`SwiftShader` 才是硬體。仍是 llvmpipe 時，把這兩個輸出連同 `ls -l /dev/dri` 一起留下，並從上表選一個做法。

## SQLite 的原生程式庫

`jdbc-pool` 的 `sqlite` 種類使用 `org.xerial:sqlite-jdbc`，其原生程式庫在 `engine.jar` 內（`org/sqlite/native/Linux/x86_64/` 與 `Linux-Musl/x86_64/` 等），第一次使用時解開到 JVM 的暫存目錄並載入。

### 目標架構

Docker 映像為 `linux/amd64`、glibc（Ubuntu）。實測：部署出的 Engine 對 `sqlite` 資源執行同一個 pipeline 連續 10 次，每次寫一列並讀回 `PRAGMA journal_mode`，全部成功，最後一次回報 `wal|10`（WAL 模式、10 列；測試 `BrowserDeploymentTest`）。

glibc 與 musl 的取捨：

| | glibc（映像用） | musl（Alpine） |
|---|---|---|
| SQLite 驅動 | 載入 `Linux/x86_64` | 載入 `Linux-Musl/x86_64`（驅動自行判斷）。**實測**：`eclipse-temurin:25-jre-alpine`（amd64）以 `engine.jar` 的驅動開啟資料庫並設為 WAL 成功；只測驅動，沒有跑完整的 Engine |
| Chrome | 有官方套件 | **沒有**：Google 不提供 musl 的 Chrome，Alpine 上要用發行版的 Chromium，那不是真實的 Chrome |
| Netty 的原生傳輸 | 可用 | 未實測 |

所以瀏覽器映像用 glibc。沒有瀏覽器需求的 Engine 映像若想用 Alpine，SQLite 在驅動層可行，其餘（Netty、整個 Engine）未驗證。

### 受限方法的警告（採用 manifest 宣告）

JDK 24 起，第一次呼叫會載入原生程式庫的方法（`System.load`／`loadLibrary`）時，JVM 警告 `A restricted method in java.lang.System has been called … Restricted methods will be blocked in a future release unless native access is enabled`。實測：**這個警告在 Engine 一啟動就出現，來源是 Netty 的原生傳輸（`io.netty.util.internal.NativeLibraryUtil`），與 SQLite 無關**；SQLite 驅動（`org.sqlite.SQLiteJDBCLoader`）是第二個呼叫者。

決定：**採用**，在 `engine.jar` 的 manifest 宣告 `Enable-Native-Access: ALL-UNNAMED`（`engine/build.gradle.kts` 的 `shadowJar`）。理由：

- 需求屬於產物（它內含會載入原生程式庫的程式庫），不屬於主機；宣告在產物裡，`java -jar engine.jar` 在 Docker、systemd 與任何其他啟動方式都不必記得加旗標，也不會因為少設一個環境變數而在未來的 JDK 被封鎖。
- 範圍與命令列旗標 `--enable-native-access=ALL-UNNAMED` 相同：類別路徑上的程式碼沒有更窄的寫法。這表示 run 的 class loader 載入的 pipeline 類別（也在未命名模組）同樣不再收到警告；警告不是安全邊界，pipeline 的限制來自分析器與 unsafe 政策（ADR-006），不是這個警告。
- 不改變 Engine 的對外 API，位元組級可重現性不受影響（屬性是固定的一行；同一個樹連續兩次 `--rerun-tasks` 建置的 SHA-256 相同，正式驗證 `verifyReproducibleRelease` 需要乾淨且已提交的工作樹，本次沒有執行）。
- 只對 `java -jar` 啟動的 `engine.jar` 有效。以 `-cp` 啟動的入口（遷移、健康檢查）不載入原生程式庫，不需要。直接以類別路徑執行 Engine 或在 IDE 內執行時加 `--enable-native-access=ALL-UNNAMED`（Gradle 的 `test` 已加）。
- 驗證：容器的 log 在啟動並執行過 SQLite 之後不含 `restricted method`（測試實測；採用前同一個測試失敗，log 有 Netty 的警告）。

### 暫存目錄（`noexec`）

驅動第一次使用時把原生程式庫解到 `java.io.tmpdir`（預設 `/tmp`）並以 `System.load` 載入。該目錄必須可寫，且所在的掛載不是 `noexec`。

- Docker：容器的 `/tmp` 是可寫層，可執行，不必設定。把 `/tmp` 設成 tmpfs 時**不要**加 `noexec`。
- systemd：drop-in 把 `java.io.tmpdir` 指向 `/var/cache/runline`（`Environment=JDK_JAVA_OPTIONS=…`），並以 `CacheDirectory=runline` 讓 systemd 建立該目錄並交給服務帳號。確認：`findmnt -no OPTIONS -T /var/cache/runline` 不含 `noexec`。
- 暫存目錄不可執行而無法改時，另給驅動專用目錄：`-Dorg.sqlite.tmpdir=<可寫且可執行的目錄>`（`JDK_JAVA_OPTIONS`），例如 Docker 的 `/var/lib/runline/runs` 或 systemd 的 `/var/cache/runline`。

失敗時的樣子（以 `/tmp` 掛成 `noexec` 的 tmpfs 實測，Docker）：`sqlite` 資源的檢查失敗（實測：`"ok":false`，`failure` 為 `unexpected_response`；類別不指出原因，原因在 log），Engine 的 log 有：

```
ERROR org.sqlite.SQLiteJDBCLoader - Failed to load native library: sqlite-3.53.4.0-<uuid>-libsqlitejdbc.so. osinfo: Linux/x86_64
java.lang.UnsatisfiedLinkError: /tmp/sqlite-3.53.4.0-<uuid>-libsqlitejdbc.so: /tmp/sqlite-3.53.4.0-<uuid>-libsqlitejdbc.so: failed to map segment from shared object
```

判讀：`UnsatisfiedLinkError` 加上 `failed to map segment from shared object`，路徑在暫存目錄下 = 該目錄不能執行（`noexec`）。把 `-Dorg.sqlite.tmpdir` 指向可執行的目錄後重啟，檢查通過（測試實測，目錄用 `/var/lib/runline/runs`）。

## 遺留的 Chrome 行程與暫存檔

pipeline 在 run 取消、逾時與結束時結束自己啟動的 Chrome（WI-1000：取消後約 60 至 100 ms 全部消失，50 次無累積）。以下情形仍可能遺留：

| 情形 | 處理 |
|---|---|
| Engine 的 JVM 被 SIGKILL 或當掉（Docker） | 容器的 PID 1 是 init；主行程（JVM）結束時 init 隨之結束，容器停止，其中所有行程（Xvfb、Chrome）隨容器結束（實測：主行程結束後容器以 0 結束，容器內仍有 Xvfb 與 Chrome 在跑）。`restart: unless-stopped` 起新的容器，沒有遺留 |
| Engine 的 JVM 被 SIGKILL、OOM 殺掉（systemd） | 單元的 `Restart=on-failure` 先停止單元；`KillMode=control-group`（預設）把同一個 control group 的 Xvfb 與 Chrome 一併結束。沒有遺留。**未實測** |
| Engine 還活著，但某個 run 沒有清乾淨 Chrome | pipeline 在每個 run 開始時清理屬於它且失去擁有者的 Chrome 與使用者資料目錄（ADR-022）。手動：見下 |
| 暫存檔：`runline-chrome-*` 目錄（Chrome 的使用者資料目錄）與 `sqlite-*-libsqlitejdbc.so` | Docker 隨容器重建消失。systemd 的 `/var/cache/runline` 會留：Engine 停止時 `find /var/cache/runline -maxdepth 1 \( -name 'runline-chrome-*' -o -name 'sqlite-*' \) -mtime +1 -exec rm -rf {} +` |

手動檢查與處理：

```
docker compose exec engine pgrep -af 'google-chrome|chrome_crashpad'       # Docker
pgrep -u runline -af 'chrome'                                              # systemd 主機
```

Engine 沒有進行中的瀏覽器 run 卻還有 Chrome 行程時，是遺留：Docker 用 `docker compose restart engine`（最簡單，Engine 的進行中 run 會被標為中斷）；不想重啟 Engine 時 `docker compose exec engine pkill -f 'user-data-dir=/tmp/runline-chrome-'`；systemd 主機用 `pkill -u runline -f 'user-data-dir=.*runline-chrome-'`。進行中的 run 的 Chrome 也符合這條樣式，所以先確認沒有 run 在用。殭屍（`Z` 狀態）不是遺留：它在 init 回收之前存在，沒有 init 才會累積（見「實測」）。

## 在目標主機（amd64，Intel iGPU）上要做的驗證

本專案**沒有**在有 GPU 的主機上驗證（使用者決定，WI-1000 第 8 條）。在目標主機上依序做，把輸出留下：

1. `ls -l /dev/dri`；`stat -c '%g %G' /dev/dri/renderD128`（群組）。
2. `.env` 設 `RENDER_GROUP_ID`，以三個 `-f` 啟動（見上）。
3. `docker compose logs engine | grep -E 'runline-entrypoint|browser-probe'`：確認顯示、渲染器字串、`/dev/dri=` 的狀態。
4. `docker compose exec -e CHROME_REQUIRE_HARDWARE_RENDERING=true engine runline-browser-probe; echo $?`：硬體為 0，軟體為 3。
5. 指紋，用 WI-1000 的量測方法（需要 `runline-digest-rss` 的 `./gradlew linuxCheckLibs` 產出的目錄，本例在 `$CHECK`）：

   ```
   docker compose run --rm --no-deps -v "$CHECK":/check:ro engine \
     java -Djava.awt.headless=true -cp '/check/classes:/check/lib/*' digestrss.browser.FingerprintCheck
   ```

   讀 `webglRenderer`（判定規則見上）。
6. 取消與重複：同樣的指令，類別改為 `digestrss.browser.LinuxSuite digestrss.browser.CancellationTest digestrss.browser.RepeatedRunsTest digestrss.browser.ChromeCaptureTest`。
7. 若渲染器仍是 llvmpipe：留下步驟 3 與 `ls -l /dev/dri`，從「虛擬顯示的硬體 GL」選一個做法。

## 實測紀錄

環境見開頭。指令在部署出的映像（`runline-engine-browser`）內執行，帳號為 `runline`，有 init，顯示由 `runline-entrypoint` 啟動。

| 項目 | 結果 |
|---|---|
| 指紋（`digestrss.browser.FingerprintCheck`） | `webdriver=false`、`pluginCount=5`、`hasChromeObject=true`、`automationMarkers=[]`、UA 為 `Chrome/155.0.0.0`（無 Headless）；`webglVendor=Google Inc. (Mesa)`、`webglRenderer=ANGLE (Mesa, llvmpipe (LLVM 21.1.8 256 bits), OpenGL 4.5)`；唯一的問題是軟體渲染（WI-1000：條件通過）。與 WI-1000 的 Debian／Ubuntu noble 映像（LLVM 20.1.2）相比，只有 LLVM 版本不同 |
| 取消與重複（`LinuxSuite`：`CancellationTest`、`RepeatedRunsTest`、`ChromeCaptureTest`），有 init | 13 項全部通過，約 5 分鐘（Rosetta）；`CANCEL all 15 Chrome processes gone 60ms after the interrupt`；`REPEAT 50 runs; cancel -> all processes gone: n=17 max=100ms avg=66ms` |
| 同上的 `CancellationTest`，**沒有** init（Java 為 PID 1） | 4 項中 2 項失敗：`Chrome processes still alive 5s after the interrupt`（殭屍）與逾時的那項 |
| Engine 的 `sqlite` 資源，WAL，連續 10 個 run | 10 個成功，`wal|10` |
| 私有 CA | 受信任的服務檢查通過；另一個 CA 的服務 `trust_failed` |
| `noexec` 的 `/tmp` | 檢查失敗，log 如上；改 `-Dorg.sqlite.tmpdir` 後通過 |
| Chrome 與顯示都不可用 | Engine 就緒；log 有 `could not read the renderer` |
| 啟動 log 的軟體渲染警告 | 實測（見上） |
| systemd（容器內的 systemd 259）：drop-in 啟動 | `runline-wait-ready` 通過、`ready=200`；Xvfb 在服務的 control group 內，`systemctl stop` 後沒有 Xvfb 或 Chrome；log 無 `restricted method` |
| systemd：`sqlite` 資源連續 10 個 run | 10 個成功，`wal|10` |
| systemd：私有 CA（`openssl` 做的 CA 與 `openssl s_server`） | 沒設 `RUNLINE_TRUSTED_CA_DIR`：`trust_failed`；設了並重啟：`"ok":true` |
| systemd：`/var/cache/runline` 掛成 `noexec` 的 tmpfs | 檢查失敗，log 同 Docker 的訊息，路徑在 `/var/cache/runline`；`-Dorg.sqlite.tmpdir=/opt/runline/native` 後通過 |
| systemd：發現的問題 | `runline-entrypoint` 找不到同目錄的腳本（systemd 的 PATH 沒有 `/opt/runline/bin`），已改為以自己的位置尋找；沒有家目錄的系統帳號使 Chrome 當掉，drop-in 加了 `HOME` |
