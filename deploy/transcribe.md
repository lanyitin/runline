# 轉錄執行環境部署手冊

本文回答：要讓 Engine 為 transcribe pipeline（[ADR-037](../docs/stable/transcribe-pipeline/adr/ADR-037-unsafe-pipeline-and-intake-root.md)）運作，Docker 與 systemd 兩種部署各要提供什麼、怎麼設定、怎麼驗證。需求來源是 [04 部署架構](../docs/stable/transcribe-pipeline/04-deployment.md) 與 [WI-1402](../docs/stable/transcribe-pipeline/work-items/WI-1402-host-runtime-deployment.md)。作法同[瀏覽器覆蓋層](browser.md)：選配，不使用時原部署完全不變。這裡只安裝並驗證執行環境；pipeline 由專案 `runline-transcribe` 交付，VAD 工具 jar 由其 WI-1407 交付，**目前尚不存在**。

## 檔案一覽

| 檔案 | 用途 |
|---|---|
| `docker/Dockerfile` 的 `transcribe-fetch`、`transcribe` 階段 | 在 Engine 映像上加 ffmpeg、ffprobe、silero-vad 模型檔與下列腳本；`engine` 階段與既有映像不變 |
| `docker/compose.transcribe.yaml` | 疊加檔：用 `transcribe` 階段、唯讀掛載收件根目錄、設定 `TRANSCRIBE_INTAKE_ROOT`、掛載 VAD 工具目錄 |
| `docker/Dockerfile` 的 `browser-transcribe` 階段、`docker/compose.browser-transcribe.yaml` | 與瀏覽器並用時的單一映像與其疊加檔（見「與瀏覽器並用」） |
| `test/combined-image-test.sh` | 並用的檢查：Compose 展開結果、兩個 systemd drop-in 不衝突；不需 Docker daemon |
| `systemd/runline-engine-transcribe.conf` | systemd 的 drop-in |
| `bin/runline-transcribe-fetch` | ffmpeg、ffprobe、模型檔的**唯一**版本與雜湊定義處；下載、驗證雜湊後才寫出。Dockerfile 與 systemd 主機都用它 |
| `bin/runline-vad` | VAD 工具的命令名稱（預設的 `TRANSCRIBE_VAD_COMMAND`）：以 JRE 執行 `RUNLINE_VAD_JAR`，轉送所有參數與環境（含 pipeline 設的 `JDK_JAVA_OPTIONS`）；jar 不存在時說明並以 127 結束 |
| `bin/runline-transcribe-check` | 檢查腳本（見「檢查」） |
| `test/transcribe-check-test.sh`、`test/transcribe-fetch-test.sh`、`test/vad-wrapper-test.sh` | 三支腳本的測試（後者以真實的小 jar 驗證 `JDK_JAVA_OPTIONS` 到達 JVM）；不需 Docker daemon |

## 需求與環境變數

不需要 Python；JRE 已在 Engine 的映像與主機上。全部經環境變數提供（Docker：`.env`、映像與疊加檔；systemd：`runline.env`）。

| 變數 | 預設 | 說明 |
|---|---|---|
| `TRANSCRIBE_INTAKE_ROOT`（必要） | Docker：疊加檔設為 `/var/lib/runline/intake`；systemd：無 | 收件根目錄的絕對路徑。缺少時 pipeline 的 run 在初始階段失敗並指出缺哪一項（04） |
| `TRANSCRIBE_WORK_DIR` | Docker：未設定（JVM 的預設暫存目錄，容器的 `/tmp`）；systemd：drop-in 設 `/var/cache/runline-transcribe` | 工作目錄的上層（04）。須是絕對路徑、存在且可寫，否則 run 在 `cleanup` 階段失敗（見「工作目錄與容量」）；容量見下 |
| `TRANSCRIBE_VAD_COMMAND` | 未設定，用 PATH 上的 `runline-vad` | VAD 工具的命令（04） |
| `RUNLINE_VAD_JAR` | `/opt/runline/vad/runline-vad.jar` | 本部署層的設定，只被 `bin/runline-vad` 讀：VAD 工具 jar 的位置 |
| `RUNLINE_VAD_MODEL` | `/opt/runline/models/silero_vad.onnx` | 本部署層的設定：模型檔位置。檢查腳本讀它並以 `--model` 傳給工具（見「待 WI-1407 對齊」） |
| `RUNLINE_VAD_MODEL_SHA256` | 未設定；改讀 `<模型檔>.sha256` | 檢查時與模型檔比對的雜湊 |
| `RUNLINE_TRANSCRIBE_MIN_FREE_MB` | `1024` | 只用於檢查：工作目錄的可用空間下限 |
| `TRANSCRIBE_INTAKE_HOST_DIR`（僅 Docker，必填） | 無 | 宿主上放影片的目錄，唯讀掛載為 `TRANSCRIBE_INTAKE_ROOT` |
| `TRANSCRIBE_VAD_HOST_DIR`（僅 Docker） | `./transcribe-vad`（相對於疊加檔） | 宿主上放 `runline-vad.jar` 的目錄，唯讀掛載到 `/opt/runline/vad` |

沒有機密：映像與疊加檔不含密碼、金鑰或 URL 憑證；模型檔與 ffmpeg 都是公開的下載。

### 位置一覽

| 項目 | Docker | systemd（drop-in 的預設） |
|---|---|---|
| ffmpeg、ffprobe | `/usr/local/bin/` | `/usr/local/bin/` |
| 模型檔與其 `.sha256` | `/opt/runline/models/`（映像內，root 擁有、Engine 帳號唯讀） | `/opt/runline/models/` |
| VAD 工具 jar | `/opt/runline/vad/runline-vad.jar`（宿主目錄唯讀掛載） | `/opt/runline/vad/runline-vad.jar` |
| 命令 `runline-vad` | `/usr/local/bin/` | `/opt/runline/bin/` |
| 收件根目錄 | `/var/lib/runline/intake`（唯讀掛載） | 管理員指定 |
| 工作目錄 | 容器的 `/tmp`（可寫層） | `/var/cache/runline-transcribe` |

## Docker

```
./gradlew :engine:engineDistribution
cp deploy/docker/.env.example deploy/docker/.env        # 取代每個佔位值
TRANSCRIBE_INTAKE_HOST_DIR=/srv/intake \
docker compose -f deploy/docker/compose.yaml -f deploy/docker/compose.transcribe.yaml up --build -d
```

（或在 `.env` 設 `COMPOSE_FILE=compose.yaml:compose.transcribe.yaml` 與兩個 `TRANSCRIBE_*_HOST_DIR`。）沒有 `TRANSCRIBE_INTAKE_HOST_DIR` 時 Compose 在展開時拒絕並說明；不加這個疊加檔時，`compose.yaml` 與 `engine` 階段與以前完全相同（實測：映像內沒有 ffmpeg 與 `runline-vad`）。

| 項目 | 內容 |
|---|---|
| 映像 | `Dockerfile` 的 `transcribe` 階段，建在 `engine` 階段之上。單獨使用時是 `transcribe` 映像（沒有 Chrome）；與瀏覽器疊加檔並用時是 `browser-transcribe` 映像（見「與瀏覽器並用」） |
| 平台 | `linux/amd64` 與 `linux/arm64` 皆可（靜態 ffmpeg 兩者都有），不像瀏覽器疊加檔限 amd64 |
| 帳號 | 與 Engine 相同的 `runline`（uid 10001）。ffmpeg、ffprobe、VAD 工具都由 Engine 啟動，以該帳號執行；映像沒有以 root 執行任何這些工具 |
| 收件根目錄 | 宿主目錄以 `read_only: true` 綁定掛載。Engine 帳號能不能**讀**由宿主的檔案權限決定：影片檔要對 other 可讀（`chmod o+r`、目錄 `o+rx`），或群組是容器內帳號的群組。檢查會告訴你讀不到 |
| VAD 工具目錄 | 宿主目錄（可先是空的）唯讀掛載到 `/opt/runline/vad`；把 `runline-vad.jar` 放進去，重新啟動容器不需重建映像 |
| 模型檔 | 建置時由 `runline-transcribe-fetch` 驗證雜湊後放進映像（見「版本固定」）；root 擁有，Engine 帳號不能改 |
| 工作目錄 | 容器的 `/tmp`（可寫層），pipeline 為每個 run 建立專屬目錄並在結束時刪除。要把它放在別處，設 `TRANSCRIBE_WORK_DIR` 並提供該路徑的 volume（`tmpfs` 會佔記憶體，不建議放長影片） |
| 有狀態資料 | 無：映像內沒有狀態；SQLite 檔放在 `file` 資源的根目錄（04） |

## systemd

前置條件：`curl`、`tar`、`xz`（只在安裝時，用於 `runline-transcribe-fetch`）；JRE 與 Engine 已安裝（[systemd 指南](../docs/stable/pipeline-engine/04-deployment-systemd.md)）。安裝步驟寫在 `systemd/runline-engine-transcribe.conf` 檔頭，摘要：

1. 執行 `bin/runline-transcribe-fetch <暫存目錄>`：下載並驗證雜湊，把 `ffmpeg`、`ffprobe` 裝到 `/usr/local/bin`，把模型檔與其 `.sha256` 裝到 `/opt/runline/models`；把 `runline-vad` 與檢查腳本裝到 `/opt/runline/bin`。
2. 安裝 drop-in 為 `/etc/systemd/system/runline-engine.service.d/transcribe.conf`，`systemctl daemon-reload`。
3. 把 `runline-vad.jar` 放到 `/opt/runline/vad/`（`root` 擁有、Engine 帳號可讀、不可寫）。
4. 在 `/etc/runline/runline.env` 設 `TRANSCRIBE_INTAKE_ROOT`。收件根目錄須「Engine 帳號可讀、不可寫」，例如目錄 `root:runline 0750`、檔案 `root:runline 0640`；若要讓服務的掛載命名空間也強制唯讀，在 drop-in 加 `ReadOnlyPaths=<收件根目錄>`（檔頭有註解範例）。
5. `systemctl restart runline-engine`，然後執行檢查。

drop-in 只設預設值（`PATH`、`RUNLINE_VAD_*`、`TRANSCRIBE_WORK_DIR` 與 `CacheDirectory=runline-transcribe`），`runline.env` 的同名變數會取代它們；與瀏覽器 drop-in 可同時安裝（見「與瀏覽器並用」）。

## 與瀏覽器並用

同一個 Engine 要同時跑需要 Chrome 的 pipeline（digest-rss，[瀏覽器手冊](browser.md)）與 transcribe 時，兩個覆蓋層可以一起用；不使用覆蓋層、或單獨使用其中一個，行為都與只用那一個時相同。並用得到**單一** Engine 映像，同時具備 Chrome、Xvfb 虛擬顯示、ffmpeg、ffprobe、`runline-vad` 與模型檔，兩邊的檢查都在同一個容器中通過。

### Docker

```
./gradlew :engine:engineDistribution
cp deploy/docker/.env.example deploy/docker/.env        # 取代每個佔位值
TRANSCRIBE_INTAKE_HOST_DIR=/srv/intake \
docker compose -f deploy/docker/compose.yaml -f deploy/docker/compose.browser.yaml \
    -f deploy/docker/compose.transcribe.yaml -f deploy/docker/compose.browser-transcribe.yaml up --build -d
```

（或在 `.env` 設 `COMPOSE_FILE=compose.yaml:compose.browser.yaml:compose.transcribe.yaml:compose.browser-transcribe.yaml`。）`compose.browser-transcribe.yaml` **必須排在最後**：Compose 以最後一個檔案決定映像與建置階段；它只指定 `runline-engine-browser-transcribe` 映像與 `browser-transcribe` 階段，其餘（`linux/amd64`、`init`、私有 CA 目錄、收件根目錄、VAD 工具目錄）來自另外兩個疊加檔，所以三個都要列出。需要 GPU 時再加 `compose.browser-gpu.yaml`（不影響映像）。

| 項目 | 內容 |
|---|---|
| 映像 | `Dockerfile` 的 `browser-transcribe` 階段：建在 `browser` 階段之上，再從 `transcribe` 階段複製 `/opt/runline`（模型檔、VAD 目錄）與 ffmpeg、ffprobe、`runline-vad`、`runline-transcribe-check`，並設同樣的 `RUNLINE_VAD_MODEL`、`RUNLINE_VAD_JAR`。版本與雜湊仍只定義在 `bin/runline-transcribe-fetch`（階段是複製，不是重寫），Chrome 仍只由 `CHROME_VERSION` 定義 |
| 平台 | 只有 `linux/amd64`（Chrome 的限制；arm64 開發機需 Rosetta，見[瀏覽器手冊](browser.md)）。要在 arm64 平台用轉錄而不要 Chrome 時，單獨用本疊加檔 |
| 帳號與入口 | 與瀏覽器映像相同：uid 10001，入口是 `runline-entrypoint`（啟動 Xvfb），`init: true` |
| 檢查 | `docker compose <上列四個 -f> exec engine runline-transcribe-check`，以及 `exec engine runline-browser-probe` |

### systemd

兩個 drop-in 放在同一個目錄 `/etc/systemd/system/runline-engine.service.d/`，檔名不同（`browser.conf`、`transcribe.conf`），systemd 合併它們。兩者沒有共同的設定項：

| 項目 | 瀏覽器 drop-in | 轉錄 drop-in |
|---|---|---|
| `ExecStart` | 改為經 `runline-entrypoint` | 不設 |
| `PATH` | 不設（入口以自己的位置找同目錄腳本） | `/opt/runline/bin:/usr/local/bin:/usr/bin:/bin`，含瀏覽器所需的 `java`、`Xvfb`、`curl`、`pkill` |
| `CacheDirectory=` | `runline`（`/var/cache/runline`，JVM 暫存與 `HOME`） | `runline-transcribe`（`/var/cache/runline-transcribe`，工作目錄）；`CacheDirectory=` 是累加的清單，兩個目錄都建立 |
| 環境變數 | `JDK_JAVA_OPTIONS`、`HOME` | `RUNLINE_VAD_MODEL`、`RUNLINE_VAD_JAR`、`TRANSCRIBE_WORK_DIR` |

`test/combined-image-test.sh` 檢查這些性質（沒有共同的設定項、兩個不同的 `CacheDirectory`、JVM 暫存目錄與工作目錄不同、`PATH` 含 `/usr/bin`）。同時安裝的步驟：

1. 照[瀏覽器手冊](browser.md)的 systemd 步驟 1 至 3：主機前置條件、`install` 三支瀏覽器腳本到 `/opt/runline/bin`、`browser.conf`、`runline.env` 的 browser 各項。
2. 照上面「systemd」一節的步驟 1 至 4：`runline-transcribe-fetch`、`runline-vad` 與檢查腳本到 `/opt/runline/bin`（與瀏覽器腳本同一個目錄，檔名不重複）、`transcribe.conf`、VAD jar、`TRANSCRIBE_INTAKE_ROOT`。
3. 一次 `systemctl daemon-reload` 與 `systemctl restart runline-engine`。
4. 兩邊的檢查：`/opt/runline/bin/runline-transcribe-check` 與 `/opt/runline/bin/runline-browser-probe`（以 Engine 的帳號與 `runline.env` 的環境，如上）。

## 工作目錄與容量

pipeline 為每個 run 在工作目錄下建立專屬目錄，放單聲道 16 kHz WAV 與暫時的畫面檔，run 結束、取消或逾時時刪除（ADR-037 第 4 點）。

- **哪個目錄**：`TRANSCRIBE_WORK_DIR` 有設時是它；未設定或空白時是 JVM 的預設暫存目錄，Linux 上是 `/tmp`，**不看 `TMPDIR`**。`runline-transcribe-check` 以同樣的規則決定要檢查的目錄。
- **設了但不可用**：`TRANSCRIBE_WORK_DIR` 不是絕對路徑、不存在或不可寫時，run 在第一個階段 `cleanup` 以 `CONFIGURATION` 失敗，訊息 `TRANSCRIBE_WORK_DIR is not an existing, writable directory`（只指出變數，不含路徑）。pipeline 不會默默改用 `/tmp`。
- **systemd**：drop-in 設 `TRANSCRIBE_WORK_DIR=/var/cache/runline-transcribe`，該目錄由 `CacheDirectory=runline-transcribe` 在服務啟動時建立（擁有者是 Engine 帳號），即 pipeline 實際使用的目錄；`runline.env` 若設了別的 `TRANSCRIBE_WORK_DIR`，則用那一個，並須自行建立。
- **VAD 暫存**：pipeline 以 `JDK_JAVA_OPTIONS=-Djava.io.tmpdir=<run 工作目錄>/vad-tmp` 啟動 VAD 工具，`runline-vad` 保留並傳給 `java`（Docker 映像、Compose 疊加檔與 systemd drop-in 都不設定或清除這個變數），所以工具的暫存檔在 run 的工作目錄之下，隨它刪除而不遺留。

- WAV：16 kHz × 16 位元 × 單聲道 = 約 **115 MB／小時**影片。影片長度上限的預設是 6 小時（WI-1415），即單一 run 的 WAV 最多約 0.7 GB。
- 暫時畫面檔：用完即刪，大小取決於畫面解析度與擷取方式（WI-1406、WI-1411），**本項未量測**；先以 1 GB 的餘裕估計，端到端驗證（WI-1413）後依實測調整。
- 容量估算：`可用空間 ≥ 同時進行的轉錄 run 數 × (0.115 GB × 影片小時數 + 畫面餘裕)`。同時的 run 數受 `RUNLINE_MAX_CONCURRENT_RUNS` 限制；轉錄 pipeline 對 Lemonade 並行 1，但 run 之間仍可交錯，保守地以 `RUNLINE_MAX_CONCURRENT_RUNS` 計。
- 檢查腳本在可用空間低於 `RUNLINE_TRANSCRIBE_MIN_FREE_MB`（預設 1024）時失敗；依上式調整該值。

## `llm` 資源與模型清單檢查

transcribe pipeline 使用的 `llm` 資源（`openai-compatible`）須啟用 `audio.transcriptions` 與 `chat.completions`，並**建議同時啟用 `models.list`**：

```
{
  "baseUrl": "http://<模型服務>/api/v1",
  "endpoints": ["models.list", "chat.completions", "audio.transcriptions"],
  "maxRequestBytes": 33554432
}
```

潤飾階段開始前，pipeline 用 `models.list` 確認 `llmModel` 與已設的 `polishModel`、`verifyModel`、`translateModel` 都在服務的清單內；不在時 run 立即以 `CONFIGURATION` 失敗（失敗階段 `polish`，訊息只指出參數名稱），不會先花時間在對話請求上。資源沒有啟用 `models.list`（或服務拒絕、逾時、回答不是模型清單、清單是空的）時，這項檢查被略過，log 一行 `POLISH NOTE the list of models is not available; the models are not checked`，run 照常進行；打錯的模型名稱改由第一個對話請求的 404 發現，同樣以 `CONFIGURATION` 失敗，只是發生得較晚。

## 檢查

`runline-transcribe-check [--skip-vad]` 以 Engine 的帳號執行，全部項目都跑完，逐項印出 `ok`、`skip` 或 `FAIL  <原因>`，全部通過才以 0 結束：

| 項目 | 通過條件；失敗時的訊息 |
|---|---|
| ffmpeg、ffprobe | 在 PATH 上且能執行，印出版本；否則 `ffmpeg not found on the PATH` |
| 帳號 | 不是 root |
| 收件根目錄 | `TRANSCRIBE_INTAKE_ROOT` 已設定、存在、可讀，且**真的寫不進去**（嘗試建立一個探測檔；若建成功就刪除並判失敗，`... is writable`） |
| 工作目錄 | `TRANSCRIBE_WORK_DIR`（未設定或空白時為 `/tmp`，不看 `TMPDIR`）存在、可寫，印出可用空間，不低於下限 |
| VAD 工具與模型 | 命令存在，否則 `VAD tool is not installed: ...`；模型檔存在，否則 `VAD model is not installed: ...`；有雜湊時相符（`... sha256 ... differs from the pinned ...`）；然後產生 2 秒的 16 kHz 單聲道樣本 WAV，呼叫 `<命令> --model <模型檔> <wav>`，要求結束碼 0 且標準輸出每行都是 0 到 1 的機率，印出最小、最大值與筆數；失敗時顯示工具的標準錯誤 |

`--skip-vad` 略過最後一項並印出 `skip`，用於工具尚未交付時驗證其餘環境。不加時，工具尚未安裝會失敗並明說「工具或模型尚未安裝」，這是預期的狀態，不是環境壞了。

```
# Docker
docker compose -f deploy/docker/compose.yaml -f deploy/docker/compose.transcribe.yaml exec engine runline-transcribe-check
# systemd
sudo -u runline env $(grep -v '^#' /etc/runline/runline.env | xargs) /opt/runline/bin/runline-transcribe-check
```

樣本是合成的正弦音，只驗證「工具能載入模型、對 WAV 輸出落在 0 到 1 的機率」。語音處高、停頓處低的判斷屬 WI-1407 自己的驗收；要在部署環境用真實語音再看一次，可手動執行 `runline-vad --model "$RUNLINE_VAD_MODEL" <語音.wav>` 並檢視輸出。

### 待 WI-1407 對齊

[05](../docs/stable/transcribe-pipeline/05-ipc.md) 只規定行為（輸入 WAV 與模型檔位置、標準輸出為每視窗機率、結束碼），沒有規定命令列旗標、模型位置如何傳給工具、以及輸出的逐行格式。檢查腳本暫時採 `--model <模型檔> <wav>` 與每行一個機率；WI-1407 定案後若不同，只需改 `bin/runline-transcribe-check` 的 `check_vad_output`（與其測試），其餘不受影響。同樣，「模型位置來自組態」的環境變數名稱（本文暫用 `RUNLINE_VAD_MODEL`，屬部署層）須與 pipeline 的組態一致。

## 版本固定（ADR-032）

| 輸入 | 版本與雜湊 | 定義處 |
|---|---|---|
| ffmpeg、ffprobe | johnvansickle.com 靜態建置 7.0.2；amd64 `abda8d77…cf67`、arm64 `f4149bb2…01b1`（tar.xz 的 sha256） | `bin/runline-transcribe-fetch` |
| silero-vad 模型 | snakers4/silero-vad `v5.1.2` 的 `silero_vad.onnx`；`2623a295…788f` | 同上 |
| 基底映像 | 沿用 `engine` 階段的雜湊 | `docker/Dockerfile` 的第一個 `FROM` |

下載後先比對雜湊，不符就不寫出任何檔案，`docker build` 也隨之失敗（`test/transcribe-fetch-test.sh` 以真實的 HTTP 伺服器提供非固定內容，驗證拒絕且目的地不留檔案）。雜湊只定義在 `runline-transcribe-fetch` 一處；Dockerfile 與主機安裝都執行它。

**限度**：上游不發佈這些檔案的 sha256（ffmpeg 靜態建置的頁面只提供 md5；本專案沒有找到上游發佈的 sha256），所以這些雜湊是首次下載時**由本專案自己計算**的，防的是之後上游內容被換掉或下載被竄改，不是首次下載時的來源真偽。apt 套件不在其中，映像不含額外的 apt 套件（下載工具只在丟棄的 `transcribe-fetch` 階段）。

### 更新固定的輸入

1. 從上游取得新版本（ffmpeg：`https://johnvansickle.com/ffmpeg/release-source/` 對應的 `releases/ffmpeg-<版本>-<amd64|arm64>-static.tar.xz`；模型：silero-vad 的版本標籤），下載後以 `shasum -a 256` 自行計算。
2. 只改 `bin/runline-transcribe-fetch` 開頭的版本與雜湊。模型升級須同時確認 VAD 工具（WI-1407）支援該模型版本（07 風險表：模型版本的狀態介面不同），視為新決策。
3. 重新驗證：`deploy/test/transcribe-fetch-test.sh`、重建 `transcribe` 階段（兩個平台都建一次可確認兩個雜湊）、`runline-transcribe-check`。

## 驗證紀錄

實測環境：macOS arm64、Colima（Docker 29.9）的 `linux/arm64` 容器，並以 `--platform linux/amd64`（Rosetta）建置、執行 ffmpeg。

- **實測**：兩個平台的 `transcribe` 階段建置成功（雜湊驗證通過）；容器以 uid 10001 執行；`/opt/runline/models` 對該帳號唯讀；檢查腳本在容器內對下列狀態給出預期結果：工具未安裝（失敗並明說）、`--skip-vad`（通過）、收件根目錄為可寫掛載（失敗，且沒有留下探測檔）與唯讀掛載（通過）、以一個**臨時製作、不隨本專案提供的**最小 jar 驗證 `runline-vad` 經 JRE 載入並輸出機率的路徑（通過）、模型雜湊不符（失敗）；以 `compose.yaml` + `compose.transcribe.yaml` 啟動 PostgreSQL、migrate、engine，engine 健康、檢查通過、對收件目錄寫入得到 `Read-only file system`；不加疊加檔時映像內沒有 ffmpeg 與 `runline-vad`；`check-pins`、`pins-test.sh`、`publish-test.sh` 通過。
- **未實測**：真實的 VAD 工具與 silero-vad 模型的推論（工具尚不存在，WI-1407）；systemd 主機上的完整安裝（drop-in 只用 `systemd-analyze verify` 檢查語法，沒有在 systemd 下啟動 Engine）；`ReadOnlyPaths=`；amd64 原生機器；暫時畫面檔的大小；與瀏覽器並用時的 systemd 主機安裝（只以 `test/combined-image-test.sh` 檢查兩個 drop-in 的設定不衝突，未在 systemd 下啟動）。

### 與瀏覽器並用的驗證（WI-1416）

實測環境同上（Colima、`linux/amd64` 以 Rosetta 執行）。**實測**：以 `compose.yaml` + `compose.browser.yaml` + `compose.transcribe.yaml` + `compose.browser-transcribe.yaml` 建置 `browser-transcribe` 階段並啟動 PostgreSQL、migrate、engine（含 `init`）；engine 健康；同一個容器中 `runline-transcribe-check` 通過（含真實 VAD 工具 jar 載入模型並輸出機率，jar 由唯讀掛載提供，不隨本專案提供）與 `--skip-vad` 通過，收件根目錄寫入得到 `Read-only file system`；`runline-browser-probe` 在轉錄檢查前後各執行完成一次（Chrome 155.0.8059.39、Xvfb 在 `:99`、軟體渲染的警告如[瀏覽器手冊](browser.md)所述）；`test/combined-image-test.sh` 通過（Compose 展開：不用疊加檔、單獨用任一個、並用四種情形）；`check-pins`、`pins-test.sh` 通過。**未實測**：並用時 systemd 主機的安裝與啟動；GPU 疊加檔與並用映像；pipeline 在並用映像中的真實 run（pipeline 不在本儲存庫）。
