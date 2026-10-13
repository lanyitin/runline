# WI-1103 Engine 映像可以任意 uid:gid 執行

本文回答：如何讓 Engine 映像以 1000:1000（或其他 uid）執行，並驗收。狀態：已核可（2026-10-12）。相依：無。儲存庫：runline（映像、compose 疊加檔、部署腳本、文件）。

## 背景

見 [ADR-041](../adr/ADR-041-engine-image-arbitrary-uid.md)。

## 行為與驗收條件

1. 以 `user: "1000:1000"`（以及另一個不在 `/etc/passwd` 的 uid:gid，例如 2345:2345）啟動 `engine`、`browser`、`transcribe`、`browser-transcribe` 各映像（browser 與 browser-transcribe 只有 linux/amd64，環境不允許的組合明說未驗證並標明替代方式）：Engine 啟動、就緒探測通過、能完成一個最小 run（不需 Lemonade 的既有端到端驗證方式）。
2. 對該 uid，下列位置可寫且不依賴 passwd：`HOME`、`java.io.tmpdir`、run 根目錄（`RUNLINE_RUN_ROOT`）、Chrome 的設定檔與憑證資料庫位置、Xvfb 的目錄；`runline-browser-probe` 與 `runline-transcribe-check` 在該 uid 下通過。
3. 唯讀部分（`/app`、`/opt/runline`）維持 root 擁有，該 uid 不能修改；以測試證明。
4. 不指定 `user:` 時，行為與映像的現況完全相同（uid 10001）；既有的映像與部署測試（`deploy/test/` 下全部）維持通過。
5. `compose.registry.yaml` 的 init 容器改為依 `.env` 的 uid:gid（變數名稱由實作定，預設 `10001:10001`）設定 shared、resources 的擁有者；`compose.keystore.yaml`、`compose.oidc.yaml` 的敘述與範例改為「Engine 執行的 uid」。以兩種設定各跑一次，檢查目錄的實際擁有者。
6. 收件目錄：把一個擁有者為 1000:1000、模式 0755 的目錄以可寫綁定掛載到 `/var/lib/runline/intake`，在 `user: "1000:1000"` 下 `runline-transcribe-check` 通過；以 uid 10001 執行同一個目錄時檢查失敗並指出缺什麼（證明擁有者確實決定可寫）。
7. 文件對齊且各記一處：`04-deployment-docker.md`、`deploy/browser.md`、`deploy/transcribe.md`、`deploy/README.md` 中「uid 10001」的敘述改為「預設 uid 10001，可由 `user:` 改為其他 uid」，並說明收件目錄的擁有者規則；homelab-arr 文件加入 arr 使用 1000:1000 的決定連結 ADR-041。
8. 回報須明確列出：映像內 `RUNLINE_RUN_ROOT` 的路徑與它能否作為掛載點（homelab-deployer 要把 `/data/appdata/runline/runs` 掛到這個路徑），以及 HOME 與暫存位置在容器內的實際路徑。

## 架構約束

- 預設部署不得受影響；同一個映像服務所有 uid，不為特定 uid 另建映像。
- 不放寬映像內唯讀部分的權限；world-writable 位置只限容器內的暫存與家目錄。
- 只改映像、compose、部署腳本與文件；Engine 與 pipeline 程式碼不在本項。arr 的 Terraform／compose 變更由 homelab-deployer 執行，不在本項。
- 測試不使用 Stub 或 Mock；用真實容器。
