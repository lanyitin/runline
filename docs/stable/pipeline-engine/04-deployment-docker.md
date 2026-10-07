# 部署：Docker

本文回答：以 Docker（含 Compose）部署 Engine 時，該設定哪些項目、建議值為何、組態檔各放什麼。語意與閾值的來源是 [04](04-deployment.md) 與 [ADR-018](adr/ADR-018-liveness-readiness-probes.md)；可執行的檔案放在專案根目錄的 `deploy/`（Docker 子目錄），是本文的實例。狀態：已核可（2026-10-05）。

## 映像內容

| 項目 | 內容 |
|---|---|
| 產物 | `engine.jar` 與同層的 `run-runtime/`（`engineDistribution` 的輸出），`RUNLINE_RUNTIME_DIR` 指向後者 |
| 基底 | JDK 25 執行環境；不含 Node 與建置工具 |
| 主行程 | JVM 本身，不經不轉送訊號的殼層；或由容器內的最小 init 轉送訊號 |
| 機密 | 不寫入映像；資料庫連線、API token、webhook 密鑰基底等經環境變數或平台的密鑰機制提供 |
| 健康檢查工具 | 打包產物自帶的自我檢查入口：`java -cp engine.jar dev.lawlan.runline.engine.HealthCheckKt ready`（或 `live`），以結束代碼回報；映像不需另加 HTTP 用戶端（決定見 [ADR-018](adr/ADR-018-liveness-readiness-probes.md)，入口見 [04](04-deployment.md)「部署入口」）|

## 服務與相依

| 服務 | 說明 |
|---|---|
| `postgres` | PostgreSQL，資料磁碟區持久 |
| 遷移（一次性） | 使用與 Engine 相同的產物與環境變數，先於 Engine 執行（`java -cp engine.jar dev.lawlan.runline.engine.db.MigrateKt`）；Engine 容器相依於「遷移成功完成」 |
| `engine` | 相依遷移完成與資料庫健康；埠 8080；共享目錄掛載持久磁碟區（[ADR-009](adr/ADR-009-file-scopes.md)），資源根目錄（`/var/lib/runline/resources`，`RUNLINE_RESOURCE_ROOT` 由映像設定）掛載另一個持久磁碟區，run 私有目錄為暫存 |

## 設定項目與建議值

| 項目 | 建議值 | 說明 |
|---|---|---|
| 健康檢查對象 | 就緒（單機）；依 unhealthy 重啟的編排器改用存活 | 單機 Docker 不因 unhealthy 重啟，只用於顯示與相依等待 |
| 間隔／逾時／重試 | 10 秒／3 秒／3 次 | 服務端的就緒與資料庫檢查逾時須小於 3 秒 |
| 啟動寬限（`start_period`） | 60 秒；啟動期間探測間隔 2 秒（`start_interval`，若 Docker 版本支援） | 涵蓋 JVM 啟動、遷移確認、中斷標記與觸發處理；這段期間 Engine 尚未綁定連接埠，健康檢查得到連線被拒，期間的失敗不計入重試次數 |
| 重啟政策 | 除非手動停止否則重啟（`unless-stopped`） | 單機 Docker 只在行程結束時重啟 |
| 停止等待（`stop_grace_period`） | `RUNLINE_SHUTDOWN_GRACE_SECONDS` 加 15 秒（預設 45 秒） | Docker 預設 10 秒不足 |
| 結束代碼 | Engine 優雅關閉後以 0 結束 | 平台不需特例（已實作並由 `packagedTest` 驗證，見 [04](04-deployment.md)「部署入口」） |

## 資源根目錄（`file` 型別共享資源）

- 映像內有 `/var/lib/runline/resources`，擁有者是 Engine 的非特權帳號，`RUNLINE_RESOURCE_ROOT` 預設指向它；它是 `VOLUME`，Compose 把具名磁碟區 `resources` 掛在這裡。與共享目錄（`/var/lib/runline/shared`）、run 私有目錄（`/var/lib/runline/runs`）並列，三者互不包含（包含關係會使 Engine 啟動失敗）。
- 磁碟區的備份、容量與還原由部署環境負責；`docker compose down -v` 會刪除它。容器重建（`up --force-recreate`）不影響其中的檔案。
- 以真實 Docker 驗證：`./gradlew :engine:packagedTest --tests '*DockerResourceVolumeTest*'` 用 `deploy/docker/compose.yaml` 與 Dockerfile 建立整組服務，確認 Engine 以非特權帳號寫入掛載的根目錄，並在重建容器之後檔案仍在、檢查端點回報可用。

## 金鑰庫（機密與憑證）

Engine 側的組態項目（`RUNLINE_KEYSTORE_PATH`、`RUNLINE_KEYSTORE_PASSWORD_FILE`、`RUNLINE_KEYSTORE_PASSWORD`）與失敗類別見 [04](04-deployment.md)「組態與密鑰」；金鑰庫的內容與日常維運（建立、新增、更新、刪除、憑證、迭代次數、備份）見[維運手冊](../../../deploy/README.md)。不設定金鑰庫是允許的，所以掛載放在選用的疊加檔 `deploy/docker/compose.keystore.yaml`，不在 `compose.yaml` 內：

```
docker compose -f deploy/docker/compose.yaml -f deploy/docker/compose.keystore.yaml up -d
```

| 項目 | 內容 |
|---|---|
| 金鑰庫 | 宿主機目錄（預設 `deploy/docker/keystore/`，以 `.env` 的 `KEYSTORE_HOST_DIR` 改）**唯讀**掛到容器的 `/etc/runline/keystore`，`RUNLINE_KEYSTORE_PATH=/etc/runline/keystore/runline.p12` |
| 密碼 | Docker secret，來源是宿主機的密碼檔（預設 `deploy/docker/secrets/keystore-password`，以 `.env` 的 `KEYSTORE_PASSWORD_HOST_FILE` 改），在容器內是 `/run/secrets/runline_keystore_password`（唯讀），`RUNLINE_KEYSTORE_PASSWORD_FILE` 指向它；不經環境變數 |
| 為什麼掛目錄 | 更新是以更名覆蓋原子替換檔案；只掛單一檔案時，容器會繼續看到被取代的舊檔（實測）。更名在宿主機上做，Engine 的掛載保持唯讀 |
| 映像 | 不含金鑰庫與密碼；`docker history`、映像各層與容器的環境變數都找不到金鑰庫密碼（由測試驗證） |
| 宿主機權限 | 兩個檔案只有 Engine 的帳號（uid 10001）讀得到：目錄與檔案屬於 10001 或其群組，檔案權限 `0400` 或 `0440`；其他使用者可讀時 Engine 啟動記錄警告。這是 Linux 宿主機的檔案權限語意，本專案的驗證在 Colima（macOS）上做，該環境不呈現 Linux 的擁有者與權限，**沒有在 Linux 宿主機上實測** |
| 版本庫 | 兩個預設目錄列在 `deploy/.gitignore`；版本庫沒有真實金鑰庫與密碼，`.env.example` 只有佔位值 |

以真實 Docker 驗證：`./gradlew :engine:packagedTest --tests '*DockerKeystoreMountTest*'` 用 `compose.yaml`、`compose.keystore.yaml`、Dockerfile 與以真實 `keytool` 做的金鑰庫建立整組服務：(1) Engine 啟動、`GET /api/v1/secrets` 列出別名；(2) 在容器內寫入金鑰庫目錄失敗，兩個掛載（金鑰庫與密碼 secret）的 `RW` 都是 `false`；(3) 容器環境變數、映像歷史與映像各層（`docker save` 的位元組）都找不到密碼；(4) 在宿主機以副本加更名原子替換金鑰庫後，`POST /api/v1/secrets/reload` 回報新別名；(5) 密碼錯誤時 Engine 容器以非 0 結束，log 含 `wrong_password` 而不含任何密碼。

## 已接受的限度

- 單機 Docker 不因行程卡死（存活失敗）而重啟；以監控告警補位。
- Swarm 等依健康狀態重啟的編排器：健康檢查必須指向存活，否則資料庫短暫中斷會造成重啟風暴。
