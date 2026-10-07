# 部署架構

本文回答：系統如何部署、有哪些環境與組態原則。

## 拓撲

- 一個 Engine process（單一 JVM）加一個 PostgreSQL。所有 run 都在 Engine JVM 內執行。
- 環境：dev（devcontainer）、staging、prod。三者拓撲相同，僅組態不同。
- 開發人員的本機 Runner 是 IDE 內的獨立 JVM，不屬於部署拓撲，也不連線 Engine。

## 打包形態

- Engine 的部署產物由兩部分組成：Engine 本體，以及獨立的 run 執行期目錄（內含 Runner、core 與 Kotlin 標準函式庫）。兩者不得合併成單一產物，否則 run 的 class loader 會看到 Engine 的類別，違反 [ADR-001](adr/ADR-001-isolated-classloader.md)。
- run 執行期目錄的位置由環境變數提供。Engine 啟動時檢查該目錄：缺少任一必要部分，或其中含有 Engine 的類別，即拒絕啟動。
- 「run 內看不到 Engine 的類別」須在實際打包後的產物上驗證，不只在開發環境。
- Console（Svelte 靜態 SPA）的建置產物是 `engine.jar` 的資源，隨 Engine 同一個產物發佈；`run-runtime/` 不含它（[ADR-015](adr/ADR-015-console-frontend.md)）。
- 建置資訊（版本、完整 commit hash、commit 時間、工作樹是否有未提交變更）在建置時寫入 `engine.jar`（[ADR-016](adr/ADR-016-engine-build-info-endpoint.md)）。
- 發佈建置須在乾淨、有 commit 的工作樹上、於固定的建置平台進行，產物位元組級可重現（同一 commit 的 SHA-256 相同；跨平台一致為盡力而為）；驗證只在發佈建置執行。`buildTime` 是 commit 的時間，不是建置當下的時鐘。

## 前端建置

- Node 只在建置階段需要；執行環境不含 Node。前端建置接在 Gradle 內，本機以單一入口驗證，沒有 CI 專屬步驟。
- Console 只用同源的相對路徑呼叫 `/api/v1`，建置時不寫入環境相關值，Engine 不啟用 CORS。
- Console 使 Bearer token 進入瀏覽器，因此 TLS 終止於 Engine 之前的入口是必要條件，不只是建議（[ADR-017](adr/ADR-017-console-websocket-and-token.md)）。

## 執行環境

- 各環境使用相同的 JDK 25 執行環境（[ADR-008](adr/ADR-008-execution-model-and-jdk.md)）。
- 開發人員的 IDE 與 pipeline 專案也使用 JDK 25，避免類別檔版本不一致。

## 組態與密鑰（12-Factor）

- 資料庫連線、API token、webhook 密鑰基底、白名單初始值、並行 run 上限、run 執行期目錄、目錄根位置與用量上限、保留期限、上傳大小與解壓後上限、關閉寬限時間、遙測服務名稱、埠號，皆經環境變數或部署平台的密鑰機制提供。必填項缺漏或格式錯誤時，Engine 啟動即失敗並一次列出所有問題（只列鍵名，不含值）。
- 型別化資源的機密（資料庫密碼、API 金鑰）保存在 PKCS12 金鑰庫檔案，Engine 以唯讀方式開啟；金鑰庫路徑、密碼來源（機密檔優先，環境變數備選）與 `file` 資源的根目錄都經環境變數提供，金鑰庫與其密碼不寫入版本庫、資料庫與映像檔（[ADR-019](adr/ADR-019-typed-shared-resources.md)）。金鑰庫內有三種項目：機密項目（字串機密，限可列印 ASCII，因為金鑰庫工具匯入密碼項目時非 ASCII 會被破壞且無法還原）、受信任憑證項目與私鑰項目（TLS 信任與 mTLS，ASCII 限制不適用，見 ADR-019 決策 12 與 [WI-52](work-items/WI-52-tls-trust-and-mtls.md)）。維運用金鑰庫工具時以密碼檔參數提供金鑰庫密碼（避免密碼出現在命令列與行程清單）；更新機密為先刪後建，於檔案副本上操作後原子替換並觸發重載；損毀若無法在載入時偵測，會在連線時呈現為認證失敗。憑證到期警告門檻是 Engine 組態（預設 30 天，名稱由 WI-52 定）。金鑰庫的組態（[WI-41](work-items/WI-41-keystore-secrets.md)）：`RUNLINE_KEYSTORE_PATH`（金鑰庫檔案路徑）；密碼的來源是 `RUNLINE_KEYSTORE_PASSWORD_FILE`（密碼檔路徑，讀取第一行，**建議**）或 `RUNLINE_KEYSTORE_PASSWORD`（環境變數），兩者擇一，同時設定是組態錯誤；三者都不設定表示沒有金鑰庫，Engine 照常啟動，引用別名的資源其檢查與使用失敗。只設路徑不設密碼、只設密碼不設路徑、密碼檔讀不到或是空的，都使啟動失敗並列出鍵名（不含值）。金鑰庫位於 pipeline 共享目錄、run 私有目錄或資源根目錄之下時啟動失敗，列出鍵名（`secrets.keystorePath` 與 `workspace.sharedRoot`、`workspace.runRoot`、`resources.root`，不含路徑）。Engine 以唯讀方式開啟金鑰庫，不修改檔案或其修改時間，在唯讀掛載與唯讀檔案權限下運作正常；檔案對其他使用者可讀（others 的讀取權限；群組可讀不算）時啟動記錄警告（不含密碼）。已組態但無法開啟時啟動失敗，原因只含類別：`file_missing`（檔案缺失）、`wrong_password`（密碼錯誤）、`corrupt`（檔案毀損或截斷）、`wrong_format`（格式不符，只接受 PKCS12，JKS、JCEKS 與其他檔案一律拒絕）、`unreadable`（其餘無法開啟）。管理員以 `GET /api/v1/secrets` 列出別名、以 `POST /api/v1/secrets/reload` 在不重啟下重新讀取（[08](08-api.md)）。金鑰庫的掛載方式由 [WI-42](work-items/WI-42-keystore-deployment.md) 寫入兩份平台指南；`file` 資源的根目錄見下一條。
- `file` 型別共享資源的檔案都在資源根目錄之下（`RUNLINE_RESOURCE_ROOT`，必填）。它是與 pipeline 共享目錄、run 私有目錄並列的第三個目錄：三者不得相同或互為上下層，否則 Engine 啟動失敗並列出鍵名（`resources.root` 與 `workspace.sharedRoot` 或 `workspace.runRoot`，不含路徑）。根目錄需是持久儲存（Docker 的磁碟區、systemd 的狀態目錄），其備份、容量與還原由部署環境負責，Engine 只要求它存在且可讀寫；根目錄不存在或不可讀寫時 Engine 仍可啟動，只有需要它的資源的檢查與 run 的初始化失敗（註明資源不可用）。選用：`RUNLINE_RESOURCE_CHECK_TIMEOUT_SECONDS`（資源檢查的整體時間上限，預設 10 秒）、`RUNLINE_RESOURCE_MAX_READ_BYTES`（單次讀取檔案的大小上限，預設 10 MiB，超過回報「檔案過大」）。
- 業務狀態（jar、定義、run 紀錄與 log）在 PostgreSQL。
- 例外：pipeline 共享目錄的內容位於 Engine 本機的持久儲存（掛載的磁碟區），位置由環境變數提供（[ADR-009](adr/ADR-009-file-scopes.md)）。Run 私有目錄是暫存，不需持久。
- 擴為多實例前，共享目錄需改為各實例可共用的儲存。

## 資料庫遷移

遷移是獨立的一次性步驟，使用與 Engine 相同的程式碼與設定，於部署時先於 Engine 啟動執行。Engine 啟動時只檢查 schema 是否為最新，不自行遷移，未遷移則啟動失敗。

## 部署入口

打包後的 `engine.jar` 同時是三個入口，部署環境不需要 Gradle；三者都只讀環境變數，不接受命令列參數傳遞機密。

| 入口 | 呼叫 | 結束代碼 |
|---|---|---|
| Engine | `java -jar engine.jar` | 收到終止訊號並完成優雅關閉後為 0；啟動失敗（組態錯誤、未遷移、run 執行期目錄不符）為非 0，原因輸出到標準輸出 |
| 遷移（一次性） | `java -cp engine.jar dev.lawlan.runline.engine.db.MigrateKt` | 成功（含已是最新的無操作）為 0；失敗為 1，輸出 `Migration failed: <原因>`，不含密碼。與 Gradle 任務 `./gradlew :engine:migrate` 是同一段程式碼，只讀 `POSTGRES_URL`、`POSTGRES_USER`、`POSTGRES_PASSWORD` |
| 健康檢查 | `java -cp engine.jar dev.lawlan.runline.engine.HealthCheckKt live` 或 `ready` | 探測回 200 為 0；無回應、逾時（2 秒，小於平台的 3 秒）或非 200 為 1；參數不是 `live`／`ready` 為 2。目標是本機的 `http://localhost:$PORT`（`PORT` 預設 8080） |

優雅關閉後的結束代碼為 0（JVM 對 SIGTERM 的預設是 143），平台不需把 143 列為成功代碼。只有「曾經對外服務、且已完成停止」的行程才以 0 結束；啟動失敗、停止未完成的結束代碼不被改寫，仍為非 0。

## 擴縮

v1 單實例。Cron 排程、run 並行上限、run 排程佇列與共享資源協調的語意都假設單實例，擴為多實例前需先解決排程重複觸發、佇列與資源狀態共享問題（見 [07](07-nfr-risks.md)）。

## 備份與復原

- PostgreSQL 定期備份，包含 jar 與 pipeline 定義。
- Pipeline 共享目錄所在的磁碟區是否備份，由營運者依資料重要性決定；預設視為可重建的快取。
- 資源根目錄（`file` 資源的檔案）所在的磁碟區視為資料：備份與還原由營運者負責，Engine 不備份它，刪除資源也不刪除其檔案。
- Engine 重啟時，狀態為進行中的 run 一律標記為「中斷」，不自動重跑。

## 優雅關閉

Engine 收到終止訊號後，就緒探測立即轉為失敗（先於寬限時間），不再接受新請求，進行中的 run 被要求停止並標記為「中斷」，進行中的請求在寬限時間內完成，資料庫連線被釋放。寬限時間由組態決定（`RUNLINE_SHUTDOWN_GRACE_SECONDS`，預設 30 秒）。

平台的停止等待（Docker 的 `stop_grace_period`、systemd 的 `TimeoutStopSec`）必須大於寬限時間加收尾時間，建議為寬限時間加 15 秒（預設 45 秒）；調高寬限時間時同步調高。Docker 的預設停止等待（10 秒）小於寬限時間，不調整會使優雅關閉被強制終止。

## 探測與重啟

Engine 提供免認證的存活探測 `GET /api/v1/health/live` 與就緒探測 `GET /api/v1/health/ready`（[ADR-018](adr/ADR-018-liveness-readiness-probes.md)，回應見 [08](08-api.md)）。

- 存活：行程能回應即為通過，不檢查資料庫；只有存活失敗可作為「重啟」的依據。
- 就緒：啟動完成、資料庫可連線、run 執行期目錄完整、未在關閉中；只用於導流與相依順序，不觸發重啟。
- 啟動期間：Engine 在啟動工作（遷移確認、中斷標記、待處理觸發、排程器）全部完成後才綁定連接埠，所以啟動期間探測端點沒有回應（連線被拒），不是回 503；平台以啟動寬限容忍，並以第一次得到就緒回應視為啟動完成。
- 單實例（v1）：就緒為否即服務暫停，屬預期行為。
- 單機 Docker 只在行程結束時重啟容器，不因 unhealthy 重啟；行程卡死由監控告警處理（已接受）。

| 項目 | Docker | systemd |
|---|---|---|
| 探測對象 | 單機：就緒；依 unhealthy 重啟的編排器：存活 | 外部存活檢查（選用）；啟動等待以輪詢就緒（容忍連線被拒）|
| 探測間隔／單次逾時 | 10 秒／3 秒 | 15 秒（計時器）／3 秒 |
| 連續失敗次數 | 3 次 | 3 次後重啟主單元 |
| 啟動寬限 | 60 秒（啟動期間探測間隔 2 秒；期間連線被拒不計失敗） | 就緒輪詢總逾時 60 秒（連線被拒不算失敗，逾時才失敗） |
| 重啟政策 | 除非手動停止否則重啟 | 失敗時重啟，間隔 5 秒；300 秒內最多啟動 5 次 |
| 停止等待 | 寬限時間加 15 秒 | 寬限時間加 15 秒 |

兩個平台的設定項目與組態檔用途見 [04-deployment-docker.md](04-deployment-docker.md) 與 [04-deployment-systemd.md](04-deployment-systemd.md)；可執行的 Dockerfile、Compose 檔與 systemd 單元檔放在專案根目錄的 `deploy/`，與應用程式碼模組分開。

## 外部連線

pipeline 可發起的連線取決於其 metadata。網路邊界由部署環境另行控管，本案不在 Engine 內建立網路隔離。
