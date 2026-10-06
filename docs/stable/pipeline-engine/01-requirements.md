# 需求摘要

本文回答：要解決什麼問題、誰使用、成功的樣子、哪些前提已確認、哪些仍待確認。

## 角色

- **Pipeline 開發人員**：撰寫、本機除錯、錄製、上傳 pipeline。
- **管理員**：設定 trigger 綁定、import 白名單、共享資源、各 pipeline 的 unsafe 執行設定。
- **外部系統**：呼叫 webhook。

## 功能需求

1. Pipeline 以 Kotlin 撰寫，打包為 jar。
2. Engine 提供上傳 API；上傳時探索 jar 內的 pipeline 並判定 safe／unsafe。
3. Trigger 只有 cron 與 webhook，由管理員在 Engine 端綁定到特定 pipeline。
4. Runner 在執行時注入 context。Context 的檔案存取只限兩種目錄：同一 pipeline 的各 run 共享的目錄，以及只有當下那個 run 能存取的目錄（[ADR-009](adr/ADR-009-file-scopes.md)）；網路連線與外部行程依 pipeline metadata 限制。
5. Unsafe 判定規則，符合任一條即標記 unsafe 並記錄原因：
   - metadata 對網路連線或外部行程未提供限制（含明確宣告為不限制）。
   - 從 pipeline 定義所在類別出發，逐層展開類別參照形成的樹（含 jar 內夾帶的第三方函式庫）中，出現未列入白名單（套件層級）的節點。
6. 管理員可在 Engine 設定白名單（新增、修改、刪除條目，變更前可預覽影響）；變更時立即重判既有定義（[WI-10](work-items/WI-10-allowlist-admin.md)）。
7. 每個 pipeline 版本各自設定是否允許以 unsafe 執行，預設不允許，新版本不繼承。
8. 開發人員可在 IDE 以中斷點除錯 pipeline，且執行路徑與 Engine 一致。
9. 開發用 Runner 可錄製整個 run 的 IO 動作，並據此產出 metadata 提案文件，由開發人員自行採用。
10. 多個 pipeline 可互斥或限流地使用共享資源（例如 lemonade server）：資源定義在 Engine，pipeline 宣告所需資源，並在 run 的初始化階段整體取得（[ADR-007](adr/ADR-007-shared-resources.md)）。資源可對應實際的檔案、資料庫連線池與 OpenAI 相容服務，由 Engine 中介存取並以金鑰庫保存機密（[ADR-019](adr/ADR-019-typed-shared-resources.md)）。

## 非功能需求

- Pipeline 可以繞過 context，直接使用 JDK 的 IO 類別（例如 `java.io.File`）。此時沒有任何阻擋，也無法錄製；這類 pipeline 由靜態分析標為 unsafe。
- 經由 context 時：檔案存取只限上述兩種目錄；最寬鬆設定下網路連線與外部行程不阻擋。
- 每次 run 使用獨立 root class loader，run 結束後可被回收。
- 沿用既有技術：Kotlin、Ktor 3.x、PostgreSQL、OpenTelemetry。
- JDK 基準為最新 LTS（JDK 25）；每個 run 使用一條專屬 thread（[ADR-008](adr/ADR-008-execution-model-and-jdk.md)）。
- 符合 12-Factor：組態經環境變數，業務狀態在 PostgreSQL。

## 已確認的前提

- v1 單一團隊內部使用，不做多租戶。
- IO 類別：檔案、網路連線（含 HTTP 與資料庫）、外部行程執行。
- Webhook 每個 trigger 一組密鑰。
- v1 執行語意：參數、逾時、取消（協作式）、run 歷史與 log；不含 stage／parallel／secrets 管理。
- Engine 為單一實例。
- 共享資源的等待逾時由 Engine 組態設定。
- 逾時未結束的資源持有者維持占用，直到實際結束或管理員強制釋放。

- 類別參照樹遇到已列入白名單的節點即停止向下展開，視為受信任。
- 白名單套件的子套件一併允許；條目可標記為「僅此套件」以排除子套件。
- 接受 pipeline 結束 JVM 會中斷整個 Engine 的風險：由部署平台自動重啟，Engine 啟動時把進行中的 run 標記為中斷（[07](07-nfr-risks.md)）。
- Run 須同時取得並行額度與所有宣告的資源才開始執行，等待中不占額度。

## 待確認問題

| # | 問題 | 影響 |
|---|---|---|
| Q1 | 經由 context 無法存取兩種目錄以外的主機檔案。是否有需求讓 safe 的 pipeline 讀取主機上其他位置（例如掛載進來的程式碼庫、唯讀設定檔）？若有，建議新增第三種範圍：由管理員設定的唯讀目錄；否則這類 pipeline 只能直接使用 JDK 的 IO 而成為 unsafe | context 的檔案範圍與 metadata |
| Q2 | 共享目錄以 pipeline 名稱為單位，跨版本沿用？推薦：沿用，快取才不會因升版而遺失；風險是新版讀到舊版留下的資料 | 目錄的對應方式 |
| Q3 | 同一 pipeline 的多個 run 同時寫共享目錄會互相干擾。是否需要「同一 pipeline 同時只允許一個 run」的設定？推薦：先不做，需要者用共享資源（[ADR-007](adr/ADR-007-shared-resources.md)） | 並行語意 |
| Q4 | 磁碟用量上限是否由 Engine 組態設定單一預設值，適用每個共享目錄與每個 run 私有目錄？推薦：是 | 營運風險 |
