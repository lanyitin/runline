# 部署架構

本文回答：digest-rss 需要 Engine 部署環境額外提供什麼、管理員要設定什麼。操作步驟與驗證見 [deploy/browser.md](../../../deploy/browser.md)（Docker 與 systemd 的設定、環境變數、目標主機上要做的驗證）。

## Engine 主機需求

- 已安裝真實的 Google Chrome 155（linux/amd64；非 Chromium 衍生版，非 headless 模式），且 Engine 行程可啟動它；啟動旗標經環境變數提供，必含 `--ignore-gpu-blocklist`。
- 有畫面可用：實體顯示，或在 Linux 容器內以虛擬顯示（Xvfb）提供，並以環境變數 `DISPLAY` 告知顯示位置（12-Factor：組態經環境變數）。
- 容器有 init 行程回收殭屍行程（Docker 範例組態已啟用）。
- JVM 信任 homelab 根憑證（由 `RUNLINE_TRUSTED_CA_DIR` 提供 PEM），使 FreshRSS、rqbit、streamexa 的 HTTPS 可驗證。信任是整個 JVM 的設定，不是 pipeline 的設定。
- Engine 到 Lemonade 與三個 MCP 服務、到被擷取網站的網路可達。
- JVM 暫存目錄可寫且可執行（SQLite 原生程式庫解壓於此）；`noexec` 時以 `-Dorg.sqlite.tmpdir` 指定專用目錄。
- 可選的 GPU：把 `/dev/dri` 交給 Engine 容器，並讓行程有權限使用 render node；沒有 GPU 時為軟體渲染（WI-1000 的條件通過規則適用）。`CHROME_REQUIRE_HARDWARE_RENDERING=true` 可要求硬體渲染。
- `deploy/` 的 Docker 與 systemd 兩種平台都涵蓋上述項目（WI-1001）。

## 管理員設定（上線檢查清單）

| 項目 | 內容 |
|---|---|
| 白名單 | pipeline 不帶第三方函式庫，不需要新增條目；analyzer 的 unsafe 原因清單見上線手冊（ADR-002、ADR-014） |
| unsafe 執行 | 對該版本明確允許；新版本不繼承（ADR-006） |
| 資源綁定 | 在 pipeline 設定介面把三個資源角色綁到實際資源（ADR-025）；未綁定時用與角色同名的資源 |
| `openai-compatible` 資源 | 指向 Lemonade，啟用 `chat.completions` 與 `models.list` 端點，容量 1；取樣參數由資源的預設值提供；機密在金鑰庫 |
| `file` 資源 | 放置三個服務的憑證檔（ADR-023） |
| `jdbc-pool` 資源（`sqlite`） | 指向資源根目錄下的資料庫檔；資料庫檔連同 `-wal` 檔納入備份或使用 SQLite 的備份指令；使用者以 SQLite 工具唯讀查詢（ADR-024） |
| cron trigger | 綁定該 pipeline，參數含 cap、條件 id（必填）、模型 id `readerModel`、標已讀開關（`on` 才開啟），完整參數表見 WI-1002 |

## 環境

dev 在開發機以 devkit 除錯（本機 Chrome）；prod 在 Engine 主機（Linux、虛擬顯示）。沒有 staging 要求。
