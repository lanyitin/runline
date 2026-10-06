# WI-39 部署檔案：Dockerfile、Compose 與 systemd 單元

本文回答：如何把 Docker 與 systemd 的部署檔案放進版本庫，並與部署文件一致。狀態：已核可（2026-10-05）。相依：WI-31、WI-38（健康檢查工具選定後）。決策見 [ADR-018](../adr/ADR-018-liveness-readiness-probes.md)，設定項目與建議值見 [04-deployment-docker.md](../04-deployment-docker.md)、[04-deployment-systemd.md](../04-deployment-systemd.md)。

## 背景

部署檔案放在專案根目錄的 `deploy/`，與應用程式碼模組分開，依平台分子目錄。檔案是兩份部署文件的實例，文件與檔案須一致。

## 行為與驗收條件

**Docker**
- Dockerfile：以 `engineDistribution` 的產物（`engine.jar` 加 `run-runtime/`）建置映像，`RUNLINE_RUNTIME_DIR` 指向後者；JDK 25 執行環境、不含 Node 與建置工具；JVM 為主行程（或由最小 init 轉送訊號）；不含任何機密；健康檢查工具依 WI-38 的決定。
- Compose：`postgres`（持久磁碟區）、一次性遷移（與 Engine 同產物與環境變數）、`engine`（相依遷移成功完成與資料庫健康；共享目錄掛載持久磁碟區；埠 8080）；健康檢查對應就緒，間隔 10 秒、逾時 3 秒、3 次、啟動寬限 60 秒（啟動期間探測間隔 2 秒，Docker 版本支援時；啟動期間連線被拒，期間的失敗不計入重試）；重啟政策為除非手動停止否則重啟；`stop_grace_period` 為 `RUNLINE_SHUTDOWN_GRACE_SECONDS` 加 15 秒。機密與連線字串經環境變數或平台密鑰機制提供，範例使用佔位值。
- 以真實 Docker 驗證：映像建置成功；Compose 啟動後遷移完成、`ready` 轉為 200、容器顯示 healthy；停止資料庫時 `ready` 為 503 而 Engine 容器不被重啟，恢復後回 200；`docker stop` 時 `ready` 先轉 503、進行中請求完成、容器在停止等待內以結束代碼 0 結束而不是被強制終止；Engine 行程結束後容器被自動重啟；`run-runtime/` 內沒有 Engine 類別、Console 資源只在 `engine.jar`（沿用打包驗證）。

**systemd**
- 單元檔：遷移的一次性單元、Engine 服務（一般前景行程；失敗時重啟、間隔 5 秒、300 秒內最多啟動 5 次；`TimeoutStopSec` 為寬限時間加 15 秒；結束代碼依 WI-38 的結果設定）、啟動後輪詢就緒（總逾時 60 秒；連線被拒視為繼續等待，不是失敗）、選用的存活檢查計時器（15 秒、逾時 3 秒、連續 3 次失敗重啟）；環境檔範例（佔位值、權限說明）與狀態目錄／暫存目錄。不使用 `Type=notify` 與 `WatchdogSec`。
- 驗證：開發環境通常沒有 systemd，驗收以兩種方式：（a）可用時以 `systemd-analyze verify` 靜態檢查單元；（b）在可執行 systemd 的環境（例如帶 systemd 的容器）實測一次啟動、就緒等待、停止與失敗重啟，回報結果。若兩者都無法執行，回報並由使用者決定如何驗證，不得宣稱已驗證。

**一致性**
- `deploy/` 內的閾值與設定項目與 04-deployment-docker.md、04-deployment-systemd.md 一致；有差異時以文件為準並修正檔案（或回報需要修訂文件）。
- README 說明如何建置映像、啟動 Compose、安裝單元與必填的環境變數。

## 架構約束

- 只新增 `deploy/` 與 README 的說明；不修改應用程式碼（需要時回報，由 WI-38 處理）。
- 不納入任何真實機密。
- 測試使用真實 Docker 與真實 PostgreSQL，不使用 Stub 或 Mock；不新增 CI。
