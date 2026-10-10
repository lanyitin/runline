# WI-1000 瀏覽器可行性報告

狀態：草稿（2026-10-08）。成果在外部專案 `runline-digest-rss` 的 `digestrss.browser` 套件。

## 結論

- 純 Kotlin／JVM 以 DevTools 協定驅動有畫面的真實 Google Chrome 可行：開頁、等待、元素截圖、可見文字、以頁面 session 下載、5xx 判失敗、取消後行程乾淨結束，開發機（macOS）與 Linux 容器（Xvfb）都通過。
- 指紋：開發機通過（硬體 GPU 渲染）。Linux 虛擬顯示**沒有硬體渲染路徑**，得到 llvmpipe（軟體渲染），依驗收 2 的字面不通過；但在這個軟體渲染下，三類目標站點仍放行（見下）。
- **不含硬體 GPU 路徑**：Mac 上的 Colima 沒有 `/dev/dri`，也沒有 Intel iGPU，所以硬體渲染只在 macOS 開發機量到。**需在目標主機（amd64，Intel iGPU 以 `/dev/dri` 直通）另行驗證**，步驟見最後一節。
- 需要架構決定的事：Linux 軟體渲染是否可接受（見風險）、容器必須有 init 行程（見取消一節）。

| 驗收 | 開發機（macOS arm64） | Linux 容器（amd64，Xvfb，無 GPU） |
|---|---|---|
| 1 啟動、截圖、文字、session 下載 | 通過 | 通過（`ChromeCaptureTest` 8 項） |
| 2 指紋 | 通過（Apple M2 Metal） | 不通過：llvmpipe；不加 `--ignore-gpu-blocklist` 則連 WebGL 都沒有 |
| 3 三類站點 | 通過 | 通過，15/15（Cloudflare 站點、播放器頁、torrent 列表各 5 次）；另一個公開的 Cloudflare 挑戰測試頁 0/3 |
| 4 5xx 為失敗 | 通過 | 通過 |
| 5 取消／逾時，50 次無累積 | 通過 | 通過（容器需 init） |
| 6 不用 Playwright／Selenium／Node | 通過 | 通過 |
| 7 報告 | 本文 | |

## 驗證環境

- 開發機：macOS arm64（M2）、Google Chrome 155.0.8059.40、實體顯示。
- Linux：Docker（Colima，`vmType: vz`，2 CPU、4 GiB）上的 `linux/amd64` 容器；基底 `eclipse-temurin:25-jre-noble`，Xvfb（1920x1080x24），Google Chrome stable 155.0.8059.39，`--no-sandbox --disable-dev-shm-usage`。
- 確認 amd64 容器走 Rosetta 而不是 qemu，依據：
  - `~/.colima/default/colima.yaml` 為 `vmType: vz`、`rosetta: true`。
  - VM 內 `/proc/sys/fs/binfmt_misc/rosetta` 為 `enabled`，直譯器 `/mnt/lima-rosetta/rosetta`；`qemu-x86_64` 為 `disabled`。
  - 容器內 `/proc/self/maps` 出現 `/mnt/lima-rosetta/rosetta` 的映射。
  - 對照：改用 qemu 時 Chrome 155 於啟動即中止（`Assertion failed: p_rcu_reader->depth != 0`，結束碼 134）。
- 容器沒有 `/dev/dri`、沒有 GPU 直通。

## 指紋（驗收 2）

檢查項：`navigator.webdriver`、UA 是否含 Headless、`window.chrome`、plugins 數量、window 上的自動化標記（`cdc_`、`__pw`、`playwright` 等）、WebGL 未遮罩的 vendor／renderer 是否為軟體渲染。指令：開發機 `./gradlew test --tests 'digestrss.browser.FingerprintTest'`；Linux `CHROME_ARGS="…" dev/linux-check/run.sh digestrss.browser.FingerprintCheck`。

| 環境與啟動旗標 | webdriver | 自動化標記 | WebGL vendor／renderer | 判定 |
|---|---|---|---|---|
| macOS，Chrome 155 | false | 無 | `Google Inc. (Apple)` ／ `ANGLE (Apple, ANGLE Metal Renderer: Apple M2, Unspecified Version)` | 通過 |
| Linux，Chrome 155，預設旗標（無 `--ignore-gpu-blocklist`） | false | 無 | 無 WebGL（`getContext` 為 null，兩個字串皆空） | 不通過 |
| Linux，Chrome 155，`--ignore-gpu-blocklist` | false | 無 | `Google Inc. (Mesa)` ／ `ANGLE (Mesa, llvmpipe (LLVM 20.1.2 256 bits), OpenGL 4.5)` | 不通過（軟體渲染） |
| Linux，上列再加 `--enable-unsafe-swiftshader` | false | 無 | 同上（llvmpipe） | 不通過 |

兩個 Linux 環境的其餘項目相同：plugins 5、`window.chrome` 存在、UA 為 `Mozilla/5.0 (X11; Linux x86_64) … Chrome/155.0.0.0 Safari/537.36`。`--ignore-gpu-blocklist` 決定 WebGL 是否存在；它不會讓渲染變成硬體。更早在 arm64 原生 Debian Chromium 154 上也量過（預設無 WebGL；加 `--enable-unsafe-swiftshader` 為 SwiftShader），與 Chrome 的結果同類。

## 目標站點實測（驗收 3）

指令：`./gradlew realSitesTest`（開發機）；Linux 為 `dev/linux-check/run.sh digestrss.browser.LinuxSuite tag:real-sites`，網址以 `REALSITE_*` 環境變數傳入（`run.sh` 依名稱轉給容器，值不寫入任何檔案）。通過條件：標題不是檢查頁、可見文字夠長；torrent 項要求下載到 bencode（含 `4:info`）。Linux 以 `CHROME_ARGS="--no-sandbox --disable-dev-shm-usage --ignore-gpu-blocklist"`，即渲染器為 llvmpipe。

| 類型 | 頁面 | 開發機 | Linux（llvmpipe），5 次 |
|---|---|---|---|
| 需 Cloudflare clearance | missav.ws 首頁（curl 取得 403「Just a moment...」） | 通過 | 5/5；最終 200；可見文字 3842 至 5290 字 |
| 一般播放器頁（Plyr） | missav.ws 影片頁 | 通過 | 5/5；200；可見文字 2543 字；`.plyr__poster`（頁面放在 opacity 0）強制可見後截成 924x519，中間一列 40 種顏色 |
| torrent 列表 | onejav.com 的一個 torrent 頁 | 通過 | 5/5；200；頁面 session 下載的 `.torrent` 為 200、`application/x-bittorrent`、25773 位元組 |
| Cloudflare 挑戰測試頁 | scrapingcourse.com/cloudflare-challenge | 通過（403 的挑戰頁自行解開，出現「You bypassed the Cloudflare challenge!」） | **0/3**：45 秒後仍是「Just a moment...」，可見文字 270 至 703 字 |

- 開發機的 missav 項目另有 5 次完整重跑與獨立重跑皆通過；torrent 項有過一次未能重現的失敗（訊息未留下，之後 8 次單獨與 3 次完整都通過）。
- 為何公開挑戰測試頁在 Linux 失敗、真實目標站點通過，沒有查明：可能是該測試頁專為偵測自動化而設的更嚴規則、軟體渲染、Linux 平台或 Rosetta 之一或其組合，未分離。目標站點今天放行軟體渲染，不保證日後。
- 為避免在報告中展示成人內容，只以尺寸、顏色種類數、文字長度判斷，未檢視截圖本身。
- plyr.io 首頁的 `video` 是純色（未載入影片），不適合作播放器頁，未採用。

## 取消與無累積（驗收 5）

做法：`ChromeBrowser.close()`（`use {}` 結尾）交給 `ChromeReaper`：主行程、其目前後代，以及命令列含該 run 專屬 profile 目錄的行程（找孤兒輔助行程與 crash handler）；先 SIGTERM，3 秒後 SIGKILL；清理不受呼叫端中斷影響，並把中斷旗標還給呼叫端。指令：`./gradlew test --tests '*CancellationTest' --tests '*RepeatedRunsTest'`；Linux 為 `dev/linux-check/run.sh digestrss.browser.LinuxSuite digestrss.browser.CancellationTest digestrss.browser.RepeatedRunsTest digestrss.browser.ChromeCaptureTest`。

| 量測 | macOS | Linux（容器有 init） |
|---|---|---|
| 頁面載入中收到執行緒中斷到全部 Chrome 行程消失 | 102 至 121 ms（10 個行程） | 40 ms（14 至 15 個行程，含 zygote） |
| 逾時 | 丟 `TimeoutException`，5 秒內全消失 | 同 |
| 啟動中被中斷、close 時旗標已設 | 不遺留；旗標保留 | 同 |
| 50 次（取消 17、逾時 17、5xx 失敗 16） | 取消到全消失最長 172 ms、平均 121 ms；零遺留行程與 profile 目錄；約 60 秒 | 最長 82 ms、平均 62 ms；零遺留行程與 profile 目錄；`ChromeCaptureTest`、`CancellationTest`、`RepeatedRunsTest` 共 13 項全綠；整批 342 秒（Rosetta，2 CPU） |

- **容器必須有 init**：Java 當 PID 1 且沒有 `--init` 時，被取消的 Chrome 輔助行程變成無人回收的殭屍，行程表中仍在；測試因此在 5 秒內看到存活行程而失敗（13 項中 3 項失敗，首項即取消測試）。以 `docker run --init`（或 tini）後全綠。這是部署需求：Engine 的容器必須以 init 為 PID 1（WI-1001）。我沒有改程式去判斷殭屍狀態。
- Linux 的調整：`--no-sandbox --disable-dev-shm-usage`（容器內以 root 執行、`/dev/shm` 小），來自環境變數 `CHROME_ARGS`，不是程式常數；測試程式在容器內以 `-Djava.awt.headless=true` 執行（映像的 JRE 缺 X 用戶端函式庫，截圖測試只用 ImageIO）。
- 記憶體：4 GiB 足夠。上述全部測試（含 50 次、每次一個 Chrome）沒有出現 OOM；VM 的核心訊息中 out-of-memory 為 0 筆；沒有量峰值用量。

## 測試與程式

- 外部系統：Chrome 是真實的 Google Chrome；被擷取頁面由本機真實 HTTP 伺服器提供（`TestSite`：opacity 0 的海報、需頁面自設 cookie 才能下載的連結、帶本文的 502、永不結束的回應、延遲填入內容的頁面）。沒有 Stub 或 Mock。
- `./gradlew ktfmtCheck test` 全綠（含原有骨架與 analyzer 判定測試）。新增 22 個測試：`ChromeOptionsTest` 3、`ChromeCaptureTest` 8、`FingerprintTest` 3、`CancellationTest` 4、`RepeatedRunsTest` 1，加上不在預設套件的 `RealSitesTest` 3（標籤 `real-sites`）。
- 程式：`ChromeBrowser`（啟動、結束）、`CdpConnection`（WebSocket 上的 JSON-RPC）、`BrowserPage`、`Fingerprint`、`ChromeReaper`。測試端另有 `FingerprintCheck` 與 `LinuxSuite`（無 Gradle 的主機以 JUnit launcher 執行測試）。
- 取消測試抓到並修正過兩個缺陷：啟動中被中斷會留下孤兒行程；中斷旗標已設時清理提前放棄。
- 5xx 判斷是先寫了實作，再以暫時改門檻確認測試會失敗，不是標準的先紅後綠。

## 剩餘風險與需要決定的事

1. **Linux 沒有硬體渲染（容器內）**：指紋為 llvmpipe。目標站點今天放行，但公開挑戰測試頁不放行；是否接受軟體渲染，或要求目標主機走 GPU，是架構決定。硬體路徑未量到。
2. **Xvfb 本身沒有硬體 GL 路徑**（來源文件記載）：即使目標主機有 iGPU，在 Xvfb 下可能仍是 llvmpipe；可能需要有 DRI 的顯示伺服器（Xorg modesetting + glamor）。這點我沒有驗證。
3. **文件衝突：「pipeline 不寫任何檔案」與 Chrome 需要 `--user-data-dir`**。我用每個 run 專屬的暫存目錄並在結束時刪除。需決定是否允許（建議：明訂為瀏覽器行程自己的暫存，容器內為 tmpfs 或可寫的 run 目錄）。
4. **容器需 init**（上述）。
5. Cloudflare 的首個回應若是 5xx（挑戰頁為 503）會被判失敗；目前兩個站點的挑戰頁都是 403，行為與來源一致，未排除 503。
6. Engine 或 JVM 被 SIGKILL 時 Chrome 會遺留；只處理了取消與逾時。ADR 的「啟動時清理」未實作，`ChromeReaper` 依 profile 目錄掃描可作基礎。
7. 新增第三方函式庫 kotlinx-serialization-json 1.11.0（管理員白名單條目）；薄 jar 不含它，Runner 如何提供未驗證。`junit-platform-launcher` 只在測試用。
8. Cloudflare 放行與指紋會隨站點與 Chrome 版本改變；`FingerprintTest` 與 `realSitesTest` 可在每次搬移或升級後重跑。
9. 截圖在寬色域螢幕有輕微色偏（E81C1C 取回為 D52C2C），截圖測試以色相判斷。
10. Rosetta 下的速度不代表原生 amd64；只有行為結果可參考，時間數字（如 342 秒）不可。

## 在目標主機（amd64，Intel iGPU）重跑的步驟

目的：量硬體渲染下的指紋，並重跑站點與取消測試。需要：Docker、本專案（含 `runline` 同層目錄，因為用 composite build）或已在別處產生的 `build/linux-check` 目錄。

1. 確認主機有 render node，並取得其群組：`ls -l /dev/dri`、`stat -c %g /dev/dri/renderD128`。
2. 產生類別路徑（有 JDK 的機器上，在專案根目錄）：`./gradlew linuxCheckLibs`，得到 `build/linux-check`（可整個複製到目標主機）。
3. 指紋（硬體，加 `--ignore-gpu-blocklist`；`--device` 掛載 render node，`--group-add` 用上一步的群組編號，或改以 root 執行容器）：

   ```
   DOCKER_RUN_ARGS="--device /dev/dri --group-add <群組編號>" \
   CHROME_ARGS="--no-sandbox --disable-dev-shm-usage --ignore-gpu-blocklist" \
   dev/linux-check/run.sh digestrss.browser.FingerprintCheck 2>&1 | tee fingerprint-hw.txt
   ```

   讀 `webglRenderer`：出現 Intel 的 ANGLE／Mesa 字串（如 `Intel`、`iris`）且不含 `llvmpipe`／`SwiftShader` 才算硬體。若仍是 llvmpipe，是 Xvfb 無 DRI 的限制（風險 2），請連同下一項的輸出一起回報。
4. 對照：同一指令拿掉 `--ignore-gpu-blocklist` 與拿掉 `--device`，各跑一次。
5. 站點實測（網址以環境變數傳入，不寫檔）：

   ```
   export REALSITE_CLOUDFLARE=… REALSITE_PLAYER=… REALSITE_PLAYER_ELEMENT=… REALSITE_LISTING=… REALSITE_LISTING_LINK=…
   for i in 1 2 3 4 5; do DOCKER_RUN_ARGS="--device /dev/dri --group-add <群組編號>" \
     CHROME_ARGS="--no-sandbox --disable-dev-shm-usage --ignore-gpu-blocklist" \
     dev/linux-check/run.sh digestrss.browser.LinuxSuite tag:real-sites; done 2>&1 | tee realsites-hw.txt
   ```

6. 取消與重複：`DOCKER_RUN_ARGS="--device /dev/dri --group-add <群組編號>" CHROME_ARGS="…同上…" dev/linux-check/run.sh digestrss.browser.LinuxSuite digestrss.browser.CancellationTest digestrss.browser.RepeatedRunsTest 2>&1 | tee cancel-hw.txt`。
7. 回傳：把三個 `*.txt` 輸出檔交回即可，其中的 `FINGERPRINT`、`REAL …`、`CANCEL …`、`REPEAT …` 行與最後的測試摘要就是全部要看的內容。`run.sh` 已使用 `--init`；在目標的正式部署也必須有 init。
