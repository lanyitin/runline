# 部署：systemd

本文回答：以 systemd 部署 Engine 時，該設定哪些項目、建議值為何、組態檔各放什麼。語意與閾值的來源是 [04](04-deployment.md) 與 [ADR-018](adr/ADR-018-liveness-readiness-probes.md)；可執行的單元檔放在專案根目錄的 `deploy/`（systemd 子目錄），是本文的實例。狀態：已核可（2026-10-05）。

## 主機需求與內容

| 項目 | 內容 |
|---|---|
| 產物 | `engine.jar` 與同層的 `run-runtime/`，`RUNLINE_RUNTIME_DIR` 指向後者 |
| 執行環境 | 主機安裝 JDK 25；不需要 Node |
| 服務帳號 | 專用的非特權帳號 |
| 組態與密鑰 | 環境檔提供環境變數，權限限制為服務帳號可讀；不寫在單元檔中（12-Factor） |
| 目錄 | 共享目錄用持久的狀態目錄（[ADR-009](adr/ADR-009-file-scopes.md)），`file` 資源的根目錄用另一個持久的狀態目錄（`StateDirectory=runline runline-resources`，位置 `/var/lib/runline-resources`，與 `/var/lib/runline` 並列而不在其中），run 私有目錄用暫存目錄 |

## 單元與相依

| 單元 | 說明 |
|---|---|
| 遷移（一次性） | 使用與 Engine 相同的產物與環境檔，先於 Engine 執行（`java -cp engine.jar dev.lawlan.runline.engine.db.MigrateKt`，成功為 0）；失敗則 Engine 不啟動 |
| Engine 服務 | 相依於遷移單元與資料庫；一般的前景行程型態 |
| 存活檢查計時器與其服務（選用） | 定期呼叫存活探測，連續失敗達閾值才重啟 Engine 服務 |

## 設定項目與建議值

| 項目 | 建議值 | 說明 |
|---|---|---|
| 服務型態 | 一般前景行程（不使用 `Type=notify`） | JVM 沒有內建 sd_notify 支援，且已有 HTTP 就緒探測 |
| 重啟 | 失敗時重啟；間隔 5 秒；300 秒內最多啟動 5 次 | 避免快速重啟迴圈 |
| 結束代碼 | Engine 優雅關閉後以 0 結束，不需設定 `SuccessExitStatus` | 已由 `packagedTest` 驗證；`systemctl stop` 後單元不會被標為 failed |
| 停止等待（`TimeoutStopSec`） | `RUNLINE_SHUTDOWN_GRACE_SECONDS` 加 15 秒（預設 45 秒） | 預設 90 秒夠用，仍建議明確設定並與寬限時間連動 |
| 就緒等待 | 啟動後步驟輪詢就緒探測（可用 `java -cp engine.jar dev.lawlan.runline.engine.HealthCheckKt ready`，以結束代碼判斷），總逾時 60 秒，逾時則單元失敗 | 讓相依單元等到就緒；Engine 啟動完成前尚未綁定連接埠，輪詢會得到連線被拒，須視為「繼續等待」而不是失敗；腳本由 `deploy/` 提供 |
| 存活檢查（選用） | 計時器 15 秒、單次逾時 3 秒、連續失敗 3 次後重啟 | 取代 `WatchdogSec`（不採用，JVM 需 sd_notify 支援）|

## 資源根目錄（`file` 型別共享資源）

`runline.env.example` 設定 `RUNLINE_RESOURCE_ROOT=/var/lib/runline-resources`，單元的 `StateDirectory=runline runline-resources` 在啟動時建立並交給服務帳號。三個目錄（共享、資源、run 私有）不得互為上下層；注意 `/var/lib/runline-resources` 與 `/var/lib/runline` 是並列的兩個目錄，不是父子。備份、容量與還原由營運者負責。這份單元檔的 `StateDirectory` 與目錄所有權**沒有**在 systemd 上實測過（開發環境沒有 systemd），Docker 版本以真實 Docker 驗證。

## 金鑰庫（機密與憑證）

Engine 側的組態項目（`RUNLINE_KEYSTORE_PATH`、`RUNLINE_KEYSTORE_PASSWORD_FILE`、`RUNLINE_KEYSTORE_PASSWORD`）與失敗類別見 [04](04-deployment.md)「組態與密鑰」；金鑰庫的內容與日常維運見[維運手冊](../../../deploy/README.md)。不設定金鑰庫是允許的，所以組態放在選用的 drop-in `deploy/systemd/runline-engine-keystore.conf`（安裝為 `/etc/systemd/system/runline-engine.service.d/keystore.conf`），不在單元檔內：

| 項目 | 內容 |
|---|---|
| 金鑰庫 | `/etc/runline/keystore/runline.p12`；目錄 `root:runline` `0750`，檔案 `root:runline` `0640`：服務帳號（群組）可讀，其他使用者不可讀（群組可讀不觸發 Engine 的警告）。不在共享、資源與 run 私有目錄之下 |
| 密碼 | systemd 憑證：`LoadCredential=keystore-password:/etc/runline/keystore-password`，來源檔 `root:root` `0600`（服務帳號讀不到它）；systemd 在服務的憑證目錄提供，`RUNLINE_KEYSTORE_PASSWORD_FILE=%d/keystore-password`（實測為 `/run/credentials/runline-engine.service/keystore-password`，只有該服務的行程讀得到）。密碼不在環境檔，也不在行程環境變數 |
| 替換金鑰庫 | `cp -p` 保留擁有者與權限（沒有 `-p` 時副本的擁有者與權限取決於 root 的 umask，服務帳號可能讀不到；此點未實測），改副本後 `mv -f`，再呼叫重載端點；見維運手冊 |
| 密碼輪替 | 更新 `/etc/runline/keystore-password` 後 `systemctl restart runline-engine`（憑證在服務啟動時載入） |

驗證（2026-10-07，Colima 上的特權容器內執行 systemd 259，JDK 25，另一個容器是 PostgreSQL 17，用 README 的安裝步驟加上述兩個檔案）：

- `systemd-analyze verify` 對 `runline-engine.service` 與 drop-in 只有一則警告：`Service uses a combination of Type=simple, ExecStartPost=, and credentials. This could lead to race conditions. Continuing.`；服務照常啟動，實測沒有問題，但警告存在。
- 啟動成功：`GET /api/v1/secrets` 以管理員 token 列出別名；`/etc/runline/keystore-password` 服務帳號讀不到（Permission denied），`runline` 帳號讀得到金鑰庫，`nobody` 讀不到；服務行程的環境變數有 `RUNLINE_KEYSTORE_PATH` 與 `RUNLINE_KEYSTORE_PASSWORD_FILE`，沒有密碼。
- 以 `cp -p`、`keytool`、`mv -f` 原子替換後 `POST /api/v1/secrets/reload` 回報新別名；刪除別名後再重載亦然。
- 密碼錯誤：主行程以結束代碼 1 結束，log 為 `KeystoreOpenException: The keystore cannot be opened: wrong_password`，log 中沒有該密碼。**因為 `ExecStartPost` 的就緒等待最多 60 秒，單元在主行程失敗後仍維持 `activating`，約 60 秒後才以 `exit-code` 失敗**；這是既有的單元設計，不是金鑰庫特有，已知而未修改。
- 金鑰庫檔案權限 `0644` 時，啟動記錄警告 `The keystore file is readable by other users; restrict it to the service account`。
- 沒有掛上 drop-in 的預設情形（不設金鑰庫）沒有在本次重新驗證；drop-in 之外沒有改動單元檔。

## 已接受的限度

- 沒有選用的存活檢查時，systemd 的重啟只來自行程結束，與單機 Docker 相同；行程卡死由監控告警處理。
