# 工作項總覽

本文回答：pipeline-engine 有哪些工作項、各自的相依與目前狀態。tdd-coder 只需讀本頁加被派到的那一項。狀態：已核可（2026-10-30）。

尚未完成的項目（WI-57 重新驗收、WI-51）與其後的工作，依[架構簡化工作項總覽](../../architecture-simplification/work-items/README.md)的順序派工。

| 項目 | 目標 | 相依 | 狀態 |
|---|---|---|---|
| [WI-00](WI-00-template-cleanup.md) | 清除範本無關內容，建立本案的模組邊界 | 無 | 已完成 |
| [WI-13](WI-13-module-renaming.md) | 模組命名調整為 core、runner、engine | WI-00 | 已完成 |
| [WI-12](WI-12-auth-simplify-and-deprecations.md) | 簡化認證骨架並消除 deprecated 用法 | WI-00 | 已完成 |
| [WI-01](WI-01-pipeline-sdk.md) | Pipeline 撰寫契約：宣告、metadata、context 的 IO 類別 | WI-13 | 已完成 |
| [WI-02](WI-02-runner-core.md) | Runner 核心：獨立 class loader、context 限制、取消與逾時 | WI-01 | 已完成 |
| [WI-03](WI-03-runner-dev-entry.md) | 開發入口：IDE 中斷點除錯 | WI-02、WI-05、WI-16 | 已完成 |
| [WI-04](WI-04-recording.md) | IO 錄製與 metadata 提案 | WI-03 | 已完成 |
| [WI-05](WI-05-safety-analyzer.md) | 靜態分析與 safe／unsafe 判定 | WI-01 | 已完成 |
| [WI-06](WI-06-upload-and-discovery.md) | 上傳 API、探索、儲存、版本 | WI-05、WI-16 | 已完成 |
| [WI-07](WI-07-triggers.md) | 管理員 trigger 綁定、cron、webhook | WI-06、WI-08 | 已完成 |
| [WI-08](WI-08-run-orchestration.md) | Run 編排、各 pipeline 的 unsafe 設定、歷史與 log | WI-02、WI-06、WI-19 | 已完成 |
| [WI-09](WI-09-shared-resources.md) | 跨 pipeline 共享資源（互斥／限流） | WI-02、WI-08 | 已完成 |
| [WI-10](WI-10-allowlist-admin.md) | 管理員白名單設定、預設白名單與立即重判 | WI-05、WI-06、WI-21、WI-24 | 已完成 |
| [WI-11](WI-11-workspace-directories.md) | Pipeline 共享目錄與 run 私有目錄的建立、清除與用量 | WI-01 | 已完成 |
| [WI-14](WI-14-kmfmt-formatting.md) | ktfmt 格式化可用於專案 | 無 | 已完成 |
| [WI-15](WI-15-package-renaming.md) | 套件名稱與專案 group 調整為 dev.lawlan.runline 前綴 | 無 | 已完成 |
| [WI-16](WI-16-analyzer-module.md) | 靜態分析獨立為 analyzer 模組 | WI-05、WI-15 | 已完成 |
| [WI-17](WI-17-external-test-project.md) | 外部測試專案 runline-test，供 devkit 的 IDE 實測 | WI-03 | 已完成 |
| [WI-18](WI-18-upload-hardening.md) | 上傳與服務端的安全補強 | WI-06、WI-08 | 已完成 |
| [WI-19](WI-19-unified-metadata-reading.md) | metadata 讀取統一到 analyzer | WI-06、WI-16 | 已完成 |
| [WI-20](WI-20-run-retention.md) | run、log 與觸發紀錄的保留期限清理 | WI-08、WI-07 | 已完成 |
| [WI-21](WI-21-io-sensitive-members.md) | 靜態分析的 IO 敏感成員判定 | WI-05、WI-16、WI-19 | 已完成 |
| [WI-24](WI-24-class-level-allow-list.md) | 類別層級的白名單條目 | WI-21 | 已完成 |
| [WI-22](WI-22-surface-cleanup.md) | 對外介面清理與目錄名稱規則共用 | WI-18 | 已完成 |
| [WI-23](WI-23-test-stability.md) | 測試穩定性 | 無 | 已完成 |
| [WI-25](WI-25-allow-list-format-and-default-refinement.md) | 白名單文字格式統一與預設白名單補強 | WI-10 | 已完成 |
| [WI-26](WI-26-test-timeouts.md) | 測試的逾時保護 | WI-23 | 已完成 |
| [WI-27](WI-27-repository-baseline.md) | 版本庫的初始整理與初始 commit | 無 | 已完成 |
| [WI-28](WI-28-build-info-and-system-endpoints.md) | 建置資訊注入，`GET /api/v1/info` 與 `GET /api/v1/system` | WI-18、WI-27 | 已完成 |
| [WI-29](WI-29-health-probes.md) | 存活與就緒探測端點 | WI-08、WI-18 | 已完成 |
| [WI-30](WI-30-node-toolchain.md) | 開發環境的 Node 工具鏈（devcontainer、本機安裝） | 無 | 已完成 |
| [WI-31](WI-31-frontend-build-and-static-serving.md) | 前端建置接進 Gradle，Engine 提供靜態檔與 SPA fallback | WI-28、WI-30 | 已完成 |
| [WI-32](WI-32-reproducible-release-build.md) | 固定平台上位元組級可重現的發佈建置 | WI-28、WI-31 | 已完成 |
| [WI-33](WI-33-console-shell-and-i18n.md) | Console 骨架、設計系統、多語系基礎、不可信內容顯示機制 | WI-31 | 已完成 |
| [WI-34](WI-34-console-auth-boundary.md) | 認證邊界、token 登入、跨分頁工作階段、Engine 版本與 hash 標示 | WI-28、WI-33 | 已完成 |
| [WI-35](WI-35-console-developer-pages.md) | 開發人員功能頁：pipeline、上傳、run、log 輪詢 | WI-34 | 已完成 |
| [WI-36](WI-36-console-admin-pages.md) | 管理員功能頁：trigger、白名單、共享資源、unsafe 設定 | WI-35 | 已完成 |
| [WI-37](WI-37-console-security-verification.md) | Console 的 XSS、CSP 與 token 保存驗證 | WI-35、WI-36 | 已完成 |
| [WI-38](WI-38-deployment-entry-points.md) | 部署入口：從 jar 執行遷移、結束代碼、健康檢查工具評估 | WI-18、WI-29 | 已完成 |
| [WI-39](WI-39-deployment-files.md) | `deploy/` 的 Dockerfile、Compose 與 systemd 單元 | WI-31、WI-38 | 已完成 |
| [WI-40](WI-40-typed-resources-and-deletion.md) | 資源型別化基礎：型別欄位與遷移、metadata 型別宣告、`type_mismatch`、宣告者查詢、刪除與預覽 | WI-09、WI-19 | 已完成 |
| [WI-41](WI-41-keystore-secrets.md) | 金鑰庫機密機制：唯讀載入、格式與開啟失敗檢查、別名查找、機密端點與重載、不外洩 | WI-40、WI-43 | 已完成 |
| [WI-42](WI-42-keystore-deployment.md) | 金鑰庫的 Docker 與 systemd 掛載與維運手冊 | WI-39、WI-41 | 已完成 |
| [WI-43](WI-43-resource-accessor-boundary.md) | 資源存取端、檢查端點與 `file` 型別：存取端邊界與回收失效、`check` 端點與檢查結果、`file` 的資源根目錄與路徑限定與互斥與部署 | WI-39、WI-40 | 已完成 |
| [WI-46](WI-46-openai-compatible-resource.md) | `openai-compatible` 型別（一次完整回傳）：版本化端點目錄與管理員啟用（JSON 端點）、固定與可覆寫參數、金鑰注入、錯誤分類、整體並行（容量 × 每 run 上限）、連線與首位元組與選填總時間與等待額度逾時 | WI-41、WI-43 | 已完成 |
| [WI-47](WI-47-openai-compatible-streaming.md) | `openai-compatible` 串流：逐塊拉取、閒置逾時、取消與用量 | WI-46 | 已完成 |
| [WI-48](WI-48-jdbc-pool-resource.md) | `jdbc-pool` 型別（PostgreSQL）：連線池世代、窄 SQL 存取、資料庫設定檔機制 | WI-41、WI-43、WI-46 | 已完成 |
| [WI-49](WI-49-console-resources-page-rework.md) | Console 資源頁改造：型別、檢查、刪除、機密檢視、宣告者 | WI-36、WI-40、WI-41、WI-43、WI-46 | 已完成 |
| [WI-50](WI-50-console-typed-resource-forms.md) | Console 各型別資源表單與使用量 | WI-43、WI-46、WI-48、WI-49 | 已完成 |
| [WI-51](WI-51-secret-non-leak-verification.md) | 機密不外洩與型別化資源的整體驗證、文件一致性核對 | WI-42、WI-47、WI-48、WI-50、WI-52、WI-53、WI-55、WI-56、WI-57、WI-58、WI-59、WI-60、WI-61、WI-62、WI-63、WI-64 | 未開始 |
| [WI-52](WI-52-tls-trust-and-mtls.md) | TLS 信任與 mTLS：金鑰庫的受信任憑證與私鑰項目、`trustAliases` 與 `clientCertAlias`、到期監看、輪替、Console 顯示與欄位 | WI-41、WI-46、WI-48、WI-50 | 已完成 |
| [WI-53](WI-53-openai-compatible-multipart-and-binary.md) | `openai-compatible` 的多部分上傳與二進位回應：圖像、音訊、檔案端點；檔案來源與去處走 ADR-009 範圍；大小上限 | WI-46、WI-47 | 已完成 |
| [WI-54](WI-54-per-uploader-artifact-versions.md) | 相同位元組由不同上傳者各自成為版本（內容雜湊 + 上傳者）（[ADR-020](../adr/ADR-020-per-uploader-artifact-versions.md)） | WI-06、WI-07、WI-08、WI-10、WI-18、WI-35、WI-36、WI-40 | 已完成 |
| [WI-55](WI-55-resource-type-catalog-endpoint.md) | 資源型別目錄端點 `GET /api/v1/resource-types`，Console 以它為唯一來源（[ADR-021](../adr/ADR-021-resource-type-catalog-endpoint.md)） | WI-50、WI-52 | 已完成 |
| [WI-56](WI-56-check-time-precision.md) | 檢查回應的時間與保存後讀回的時間完全相同 | WI-43 | 已完成 |
| [WI-57](WI-57-test-environment-robustness.md) | `./gradlew check` 在開發容器（以 root 執行、負載下）穩定通過；真實瀏覽器腳本的前置條件寫入文件 | WI-26、WI-71 | 待重新驗收 |
| [WI-58](WI-58-responses-create-stateful.md) | `responses.create` 在型別目錄中標為有狀態 | WI-55 | 已完成 |
| [WI-59](WI-59-run-end-ordering.md) | Run 被觀察為已結束時，存取端已失效、資源已釋放 | WI-09、WI-43 | 已完成 |
| [WI-60](WI-60-upload-idle-progress.md) | 上傳的閒置判定以服務端實際收下的進度計時，誤差有固定上限 | WI-53 | 已完成 |
| [WI-61](WI-61-metrics-log-reporter.md) | 指標不定期寫入 log，Engine 停止時停止所有指標相關背景工作 | 無 | 已完成 |
| [WI-62](WI-62-release-wait-limit.md) | 釋放有上限、不卡住排程；`jdbc-pool` 清理連線有逾時 | WI-59 | 已完成 |
| [WI-63](WI-63-metrics-otlp-export.md) | 指標經 OTLP 匯出，OpenTelemetry 由 Engine 關閉，不累積關閉掛鉤 | WI-61 | 已完成 |
| [WI-64](WI-64-shutdown-budget-and-dev-errors.md) | 關閉寬限時間作為整個關閉流程的總預算；run 與釋放同時失敗時以 run 的失敗為主 | WI-62、WI-63 | 已完成 |

WI-44（檢查端點）與 WI-45（`file` 型別）的內容併入 WI-43，編號不再使用。

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
