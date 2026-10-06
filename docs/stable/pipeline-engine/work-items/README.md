# 工作項總覽

本文回答：要交給 tdd-coder 的工作有哪些、先後順序為何。tdd-coder 只需讀本頁加被派到的那一項。狀態：已核可（2026-10-03；WI-40 至 WI-51 為 2026-10-06）。

| 項目 | 目標 | 相依 |
|---|---|---|
| [WI-00](WI-00-template-cleanup.md) | 清除範本無關內容，建立本案的模組邊界 | 無 |
| [WI-13](WI-13-module-renaming.md) | 模組命名調整為 core、runner、engine | WI-00 |
| [WI-12](WI-12-auth-simplify-and-deprecations.md) | 簡化認證骨架並消除 deprecated 用法 | WI-00 |
| [WI-01](WI-01-pipeline-sdk.md) | Pipeline 撰寫契約：宣告、metadata、context 的 IO 類別 | WI-13 |
| [WI-02](WI-02-runner-core.md) | Runner 核心：獨立 class loader、context 限制、取消與逾時 | WI-01 |
| [WI-03](WI-03-runner-dev-entry.md) | 開發入口：IDE 中斷點除錯 | WI-02、WI-05、WI-16 |
| [WI-04](WI-04-recording.md) | IO 錄製與 metadata 提案 | WI-03 |
| [WI-05](WI-05-safety-analyzer.md) | 靜態分析與 safe／unsafe 判定 | WI-01 |
| [WI-06](WI-06-upload-and-discovery.md) | 上傳 API、探索、儲存、版本 | WI-05、WI-16 |
| [WI-07](WI-07-triggers.md) | 管理員 trigger 綁定、cron、webhook | WI-06、WI-08 |
| [WI-08](WI-08-run-orchestration.md) | Run 編排、各 pipeline 的 unsafe 設定、歷史與 log | WI-02、WI-06、WI-19 |
| [WI-09](WI-09-shared-resources.md) | 跨 pipeline 共享資源（互斥／限流） | WI-02、WI-08 |
| [WI-10](WI-10-allowlist-admin.md) | 管理員白名單設定、預設白名單與立即重判 | WI-05、WI-06、WI-21、WI-24 |
| [WI-11](WI-11-workspace-directories.md) | Pipeline 共享目錄與 run 私有目錄的建立、清除與用量 | WI-01 |
| [WI-14](WI-14-kmfmt-formatting.md) | ktfmt 格式化可用於專案 | 無 |
| [WI-15](WI-15-package-renaming.md) | 套件名稱與專案 group 調整為 dev.lawlan.runline 前綴 | 無 |
| [WI-16](WI-16-analyzer-module.md) | 靜態分析獨立為 analyzer 模組 | WI-05、WI-15 |
| [WI-17](WI-17-external-test-project.md) | 外部測試專案 runline-test，供 devkit 的 IDE 實測 | WI-03 的實作 |
| [WI-18](WI-18-upload-hardening.md) | 上傳與服務端的安全補強 | WI-06、WI-08 |
| [WI-19](WI-19-unified-metadata-reading.md) | metadata 讀取統一到 analyzer | WI-06、WI-16 |
| [WI-20](WI-20-run-retention.md) | run、log 與觸發紀錄的保留期限清理 | WI-08、WI-07 |
| [WI-21](WI-21-io-sensitive-members.md) | 靜態分析的 IO 敏感成員判定 | WI-05、WI-16、WI-19 |
| [WI-24](WI-24-class-level-allow-list.md) | 類別層級的白名單條目 | WI-21 |
| [WI-22](WI-22-surface-cleanup.md) | 對外介面清理與目錄名稱規則共用 | WI-18 |
| [WI-23](WI-23-test-stability.md) | 測試穩定性 | 無 |
| [WI-25](WI-25-allow-list-format-and-default-refinement.md) | 白名單文字格式統一與預設白名單補強 | WI-10 |
| [WI-26](WI-26-test-timeouts.md) | 測試的逾時保護 | WI-23 |
| [WI-27](WI-27-repository-baseline.md) | 版本庫的初始整理與初始 commit | 無 |
| [WI-28](WI-28-build-info-and-system-endpoints.md) | 建置資訊注入，`GET /api/v1/info` 與 `GET /api/v1/system` | WI-18、WI-27 |
| [WI-29](WI-29-health-probes.md) | 存活與就緒探測端點 | WI-08、WI-18 |
| [WI-30](WI-30-node-toolchain.md) | 開發環境的 Node 工具鏈（devcontainer、mise） | 無 |
| [WI-31](WI-31-frontend-build-and-static-serving.md) | 前端建置接進 Gradle，Engine 提供靜態檔與 SPA fallback | WI-28、WI-30 |
| [WI-32](WI-32-reproducible-release-build.md) | 固定平台上位元組級可重現的發佈建置 | WI-28、WI-31 |
| [WI-33](WI-33-console-shell-and-i18n.md) | Console 骨架、設計系統、多語系基礎、不可信內容顯示機制 | WI-31 |
| [WI-34](WI-34-console-auth-boundary.md) | 認證邊界、token 登入、跨分頁工作階段、Engine 版本與 hash 標示 | WI-28、WI-33 |
| [WI-35](WI-35-console-developer-pages.md) | 開發人員功能頁：pipeline、上傳、run、log 輪詢 | WI-34 |
| [WI-36](WI-36-console-admin-pages.md) | 管理員功能頁：trigger、白名單、共享資源、unsafe 設定 | WI-35 |
| [WI-37](WI-37-console-security-verification.md) | Console 的 XSS、CSP 與 token 保存驗證 | WI-35、WI-36 |
| [WI-38](WI-38-deployment-entry-points.md) | 部署入口：從 jar 執行遷移、結束代碼、健康檢查工具評估 | WI-18、WI-29 |
| [WI-39](WI-39-deployment-files.md) | `deploy/` 的 Dockerfile、Compose 與 systemd 單元 | WI-31、WI-38 |
| [WI-40](WI-40-typed-resources-and-deletion.md) | 資源型別化基礎：型別欄位與遷移、metadata 型別宣告、`type_mismatch`、宣告者查詢、刪除與預覽 | WI-09、WI-19 |
| [WI-41](WI-41-keystore-secrets.md) | 金鑰庫機密機制：JDK 25 工具前置驗證、唯讀載入、別名與狀態、機密端點與重載、不外洩 | WI-40 |
| [WI-42](WI-42-keystore-deployment.md) | 金鑰庫的 Docker 與 systemd 掛載與維運手冊 | WI-39、WI-41 |
| [WI-43](WI-43-resource-accessor-boundary.md) | 資源存取端邊界：取用規則、備妥、回收與強制釋放失效、開發入口本機實作與錄製 | WI-40 |
| [WI-44](WI-44-resource-check-endpoint.md) | `check` 端點與最近一次檢查結果 | WI-40、WI-41 |
| [WI-45](WI-45-file-resource.md) | `file` 型別：資源根目錄、路徑限制、受控讀寫附加、互斥、根目錄部署 | WI-39、WI-43、WI-44 |
| [WI-46](WI-46-openai-compatible-resource.md) | `openai-compatible` 型別（一次完整回傳）：固定與可覆寫參數、金鑰注入、錯誤分類、並行上限 | WI-41、WI-43、WI-44 |
| [WI-47](WI-47-openai-compatible-streaming.md) | `openai-compatible` 串流：逐塊拉取、閒置逾時、取消與用量 | WI-46 |
| [WI-48](WI-48-jdbc-pool-resource.md) | `jdbc-pool` 型別（PostgreSQL）：連線池世代、窄 SQL 存取、資料庫設定檔機制 | WI-41、WI-43、WI-44 |
| [WI-49](WI-49-console-resources-page-rework.md) | Console 資源頁改造：型別、檢查、刪除、機密檢視、宣告者 | WI-36、WI-40、WI-41、WI-44 |
| [WI-50](WI-50-console-typed-resource-forms.md) | Console 各型別資源表單與使用量 | WI-45、WI-46、WI-48、WI-49 |
| [WI-51](WI-51-secret-non-leak-verification.md) | 機密不外洩與型別化資源的整體驗證、文件一致性核對 | WI-42、WI-47、WI-48、WI-50 |

建議順序：WI-00 → WI-13 → WI-01（WI-12 與 WI-01 無相依，可並行）→ (WI-02、WI-05、WI-11 並行) → WI-16 → WI-03 → WI-04（WI-03 的 IDE 實測需要 WI-17 提供外部測試專案，WI-17 在 WI-03 實作完成後進行）；WI-06 → WI-19 → WI-08 → (WI-07、WI-09 並行)；WI-10 與 WI-18 在 WI-06 之後即可進行，與其他項無先後要求。WI-02 的初始化階段需要 WI-11 提供目錄位置，WI-02 與 WI-11 需一併驗收。WI-15 與其他項無相依，在尚未完成的項目之前先做，使後續項目都使用新的套件名稱。

Console 與部署相關項目（WI-28 至 WI-39，決策見 [ADR-015](../adr/ADR-015-console-frontend.md) 至 [ADR-018](../adr/ADR-018-liveness-readiness-probes.md)）的建議順序：WI-28 → WI-29 → WI-30 → WI-31 → WI-32（WI-28 與 WI-30 無相依，可並行；WI-29 與前端項目無相依，可在任何時間點進行）；WI-31 之後 WI-33 → WI-34 → WI-35 → WI-36 → WI-37；WI-29 之後 WI-38 → WI-39（WI-39 同時需要 WI-31 完成；WI-38 內的健康檢查工具選項須先經架構決定）。WI-32 與 Console 畫面項目無先後要求。WI-28 與 WI-29 完成之前，`ApiDocumentationTest` 會因 08-api 已記載尚未實作的端點而失敗，屬預期，完成後轉為通過。

型別化共享資源項目（WI-40 至 WI-51，決策見 [ADR-019](../adr/ADR-019-typed-shared-resources.md)）的建議順序：WI-40 → WI-41 → WI-43 → WI-44 → WI-45 → WI-46 → WI-47 → WI-48 → WI-50 → WI-51。WI-41 的前置驗證（JDK 25 金鑰庫工具）須先完成並通過，結果不如預期時停下來由架構決定；WI-42（部署掛載與維運手冊）在 WI-41 之後即可進行，與型別項目無先後要求，須在 WI-51 之前完成；WI-49（Console 資源頁改造）只依賴已實作的 API，在 WI-44 之後即可與型別項目並行，須在 WI-50 之前完成。WI-43 與 WI-41、WI-44 在型別存在之前以僅存在於測試的 Fake 型別驗證通用機制。WI-46 實作前須向使用者確認目標服務的端點與並行上限。每個新增或變更 API 的項目在實作時同步更新 08-api，`ApiDocumentationTest` 在各項完成時保持通過；08 在對應項目完成前不列尚未實作的路由。

```mermaid
flowchart LR
  WI09[WI-09 共享資源] --> WI40[WI-40 型別化基礎與刪除]
  WI19[WI-19 metadata 統一讀取] --> WI40
  WI40 --> WI41[WI-41 金鑰庫機制]
  WI40 --> WI43[WI-43 存取端邊界]
  WI40 --> WI44[WI-44 check 端點]
  WI41 --> WI44
  WI39[WI-39 部署檔案] --> WI42[WI-42 金鑰庫部署與手冊]
  WI41 --> WI42
  WI43 --> WI45[WI-45 file]
  WI44 --> WI45
  WI39 --> WI45
  WI41 --> WI46[WI-46 openai-compatible]
  WI43 --> WI46
  WI44 --> WI46
  WI46 --> WI47[WI-47 串流]
  WI41 --> WI48[WI-48 jdbc-pool]
  WI43 --> WI48
  WI44 --> WI48
  WI36[WI-36 Console 管理員頁] --> WI49[WI-49 Console 資源頁改造]
  WI40 --> WI49
  WI41 --> WI49
  WI44 --> WI49
  WI45 --> WI50[WI-50 Console 各型別表單]
  WI46 --> WI50
  WI48 --> WI50
  WI49 --> WI50
  WI42 --> WI51[WI-51 整體驗證]
  WI47 --> WI51
  WI48 --> WI51
  WI50 --> WI51
```

## 全域架構約束（適用所有項目）

- 每次 run 使用獨立 root class loader，其上層僅為 JDK 平台 class loader（[ADR-001](../adr/ADR-001-isolated-classloader.md)）。
- Engine 與 run 邊界只傳 JDK 內建型別（[05](../05-ipc.md)）。
- 探索與分析不得執行 pipeline 邏輯（[ADR-003](../adr/ADR-003-jar-and-discovery.md)）。
- 開發入口與 Engine 共用同一份 Runner 執行路徑。
- 每個 run 使用一條專屬平台 thread，不以 coroutine 執行 pipeline 本體；JDK 基準為 25（[ADR-008](../adr/ADR-008-execution-model-and-jdk.md)）。
- 跨 run 共享的狀態由 Engine 持有，不放在 run 的 class loader 內（[ADR-007](../adr/ADR-007-shared-resources.md)）。
- 開發流程遵循 TDD；範本無關內容之外的既有行為不得被破壞。
- 專案沒有 CI：所有驗證都能在本機以 Gradle 或明確的本機指令完成；測試的外部系統使用真實容器或自製的簡易真實實作（Fake），不使用 Stub 或 Mock。
- Console 只用同源的 `/api/v1`，不改變 Bearer API 契約（[ADR-012](../adr/ADR-012-api-authentication.md)、[ADR-015](../adr/ADR-015-console-frontend.md)）。
- 型別化資源由 Engine 中介：機密值只存在於金鑰庫，不進入資料庫、API、metadata、log、trace、錯誤與 Engine 與 run 的邊界；型別集合封閉，不開放外掛；容量維持 run 級持有（[ADR-019](../adr/ADR-019-typed-shared-resources.md)）。
