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

## OIDC 登入（選用）

啟用方式與 authentik 端的設定見 [authentik 設定手冊](../../../deploy/authentik.md)；設定項目見 [04](04-deployment.md)。不啟用是預設：`.env` 沒有任何 OIDC 設定時 Engine 與以前完全相同。啟用時：

- 不機密的項目（issuer、client ID、`RUNLINE_PUBLIC_BASE_URL` 與選用項）寫在 `.env`（`.env.example` 的 OIDC 區段，全部註解掉、只放占位符）。`RUNLINE_PUBLIC_BASE_URL` 必須與使用者在瀏覽器看到的網址一致；為 https 時 cookie 帶 `Secure`，Engine 之前的 TLS 終止不影響這個判斷（只看這個設定）。
- client secret 與加密 refresh token 的金鑰以檔案提供：疊加 `deploy/docker/compose.oidc.yaml`，把 `docker/secrets/oidc-client-secret` 與 `docker/secrets/session-encryption-key`（路徑可用 `OIDC_CLIENT_SECRET_HOST_FILE`、`SESSION_ENCRYPTION_KEY_HOST_FILE` 改）掛成 Docker secret，並設 `RUNLINE_OIDC_CLIENT_SECRET_FILE`、`RUNLINE_SESSION_ENCRYPTION_KEY_FILE`，與金鑰庫密碼同一做法；機密不在映像、環境變數與 log。
- provider 使用私有 CA：再疊加 `compose.oidc-ca.yaml`，掛載以 `deploy/bin/runline-build-truststore` 做的 JVM 信任庫並設 `JDK_JAVA_OPTIONS`（瀏覽器映像改用 `RUNLINE_TRUSTED_CA_DIR`）。
- 驗證：`DockerOidcPrivateCaTest`（`packagedTest`，真實的 Compose 檔、PostgreSQL 與以自建 CA 提供 https 的 Keycloak）：未信任該 CA 時登入起點回 `provider_unavailable`；用手冊的做法做出信任庫後，登入成功並取得群組對應的角色。未用真實 authentik 驗證（WI-1204）。

## 瀏覽器映像（驅動 Chrome 的 pipeline）

為 digest-rss 這類在 Engine 主機上驅動有畫面的 Chrome 的 pipeline（[ADR-022](../digest-rss/adr/ADR-022-unsafe-pipeline-with-local-browser.md)），`Dockerfile` 另有 `browser` 階段，疊加檔 `deploy/docker/compose.browser.yaml` 使用它：真實的 Google Chrome（只有 `linux/amd64`）、Xvfb、init 為 PID 1、私有 CA 根憑證的 JVM 信任、啟動時的渲染器報告；`compose.browser-gpu.yaml` 把 `/dev/dri` 交給容器。`compose.yaml` 指定 `target: engine`，原本的映像與就緒探測都不變，Chrome 不可用不影響就緒。設定項目、驗證步驟與實測見 [瀏覽器部署手冊](../../../deploy/browser.md)；以真實 Docker 驗證：`./gradlew :engine:browserDeploymentTest`（需要 amd64 的 Docker，下載 Chrome，不屬於 `check`）。

### 由 registry 引用的發佈路徑

部署端不建置時（例如 [homelab arr](../homelab-arr/README.md)），改由 `deploy/publish-image` 在筆電建置 `linux/amd64` 的瀏覽器映像並以 commit 為 tag 推到 registry，部署端以 `deploy/docker/compose.registry.yaml` 引用它（獨立範本，無建置步驟，engine 不發布連接埠，多一個一次性的 root `init` 容器準備 `shared` 與 `resources` 的擁有者）。用法、變數與實測範圍見 [瀏覽器部署手冊](../../../deploy/browser.md)「發佈映像檔與引用它的 Compose 範本」。

## 已接受的限度

- 單機 Docker 不因行程卡死（存活失敗）而重啟；以監控告警補位。
- Swarm 等依健康狀態重啟的編排器：健康檢查必須指向存活，否則資料庫短暫中斷會造成重啟風暴。
