# 部署：Docker

本文回答：以 Docker（含 Compose）部署 Engine 時，該設定哪些項目、建議值為何、組態檔各放什麼。語意與閾值的來源是 [04](04-deployment.md) 與 [ADR-018](adr/ADR-018-liveness-readiness-probes.md)；可執行的檔案放在專案根目錄的 `deploy/`（Docker 子目錄），是本文的實例。狀態：已核可（2026-10-05）。

## 映像內容

| 項目 | 內容 |
|---|---|
| 產物 | `engine.jar` 與同層的 `run-runtime/`（`engineDistribution` 的輸出），`RUNLINE_RUNTIME_DIR` 指向後者 |
| 基底 | JDK 25 執行環境；不含 Node 與建置工具 |
| 主行程 | JVM 本身，不經不轉送訊號的殼層；或由容器內的最小 init 轉送訊號 |
| 機密 | 不寫入映像；資料庫連線、API token、webhook 密鑰基底等經環境變數或平台的密鑰機制提供 |
| 健康檢查工具 | 容器內需能發出 HTTP 請求；提供方式由 [WI-38](work-items/WI-38-deployment-entry-points.md) 評估 |

## 服務與相依

| 服務 | 說明 |
|---|---|
| `postgres` | PostgreSQL，資料磁碟區持久 |
| 遷移（一次性） | 使用與 Engine 相同的產物與環境變數，先於 Engine 執行；Engine 容器相依於「遷移成功完成」 |
| `engine` | 相依遷移完成與資料庫健康；埠 8080；共享目錄掛載持久磁碟區（[ADR-009](adr/ADR-009-file-scopes.md)），run 私有目錄為暫存 |

## 設定項目與建議值

| 項目 | 建議值 | 說明 |
|---|---|---|
| 健康檢查對象 | 就緒（單機）；依 unhealthy 重啟的編排器改用存活 | 單機 Docker 不因 unhealthy 重啟，只用於顯示與相依等待 |
| 間隔／逾時／重試 | 10 秒／3 秒／3 次 | 服務端的就緒與資料庫檢查逾時須小於 3 秒 |
| 啟動寬限（`start_period`） | 60 秒；啟動期間探測間隔 2 秒（`start_interval`，若 Docker 版本支援） | 涵蓋 JVM 啟動、遷移確認、中斷標記與觸發處理；這段期間 Engine 尚未綁定連接埠，健康檢查得到連線被拒，期間的失敗不計入重試次數 |
| 重啟政策 | 除非手動停止否則重啟（`unless-stopped`） | 單機 Docker 只在行程結束時重啟 |
| 停止等待（`stop_grace_period`） | `RUNLINE_SHUTDOWN_GRACE_SECONDS` 加 15 秒（預設 45 秒） | Docker 預設 10 秒不足 |
| 結束代碼 | Engine 優雅關閉後以 0 結束 | 平台不需特例（見 WI-38） |

## 已接受的限度

- 單機 Docker 不因行程卡死（存活失敗）而重啟；以監控告警補位。
- Swarm 等依健康狀態重啟的編排器：健康檢查必須指向存活，否則資料庫短暫中斷會造成重啟風暴。
