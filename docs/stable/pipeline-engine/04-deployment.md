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

## 執行環境

- 各環境使用相同的 JDK 25 執行環境（[ADR-008](adr/ADR-008-execution-model-and-jdk.md)）。
- 開發人員的 IDE 與 pipeline 專案也使用 JDK 25，避免類別檔版本不一致。

## 組態與密鑰（12-Factor）

- 資料庫連線、API token、webhook 密鑰基底、白名單初始值、並行 run 上限、run 執行期目錄、目錄根位置與用量上限、保留期限、上傳大小與解壓後上限、關閉寬限時間、遙測服務名稱、埠號，皆經環境變數或部署平台的密鑰機制提供。必填項缺漏或格式錯誤時，Engine 啟動即失敗並一次列出所有問題（只列鍵名，不含值）。
- 業務狀態（jar、定義、run 紀錄與 log）在 PostgreSQL。
- 例外：pipeline 共享目錄的內容位於 Engine 本機的持久儲存（掛載的磁碟區），位置由環境變數提供（[ADR-009](adr/ADR-009-file-scopes.md)）。Run 私有目錄是暫存，不需持久。
- 擴為多實例前，共享目錄需改為各實例可共用的儲存。

## 資料庫遷移

遷移是獨立的一次性步驟，使用與 Engine 相同的程式碼與設定，於部署時先於 Engine 啟動執行。Engine 啟動時只檢查 schema 是否為最新，不自行遷移，未遷移則啟動失敗。

## 擴縮

v1 單實例。Cron 排程、run 並行上限、run 排程佇列與共享資源協調的語意都假設單實例，擴為多實例前需先解決排程重複觸發、佇列與資源狀態共享問題（見 [07](07-nfr-risks.md)）。

## 備份與復原

- PostgreSQL 定期備份，包含 jar 與 pipeline 定義。
- Pipeline 共享目錄所在的磁碟區是否備份，由營運者依資料重要性決定；預設視為可重建的快取。
- Engine 重啟時，狀態為進行中的 run 一律標記為「中斷」，不自動重跑。

## 優雅關閉

Engine 收到終止訊號後，不再接受新請求，進行中的 run 被要求停止並標記為「中斷」，進行中的請求在寬限時間內完成，資料庫連線被釋放。寬限時間由組態決定。

## 外部連線

pipeline 可發起的連線取決於其 metadata。網路邊界由部署環境另行控管，本案不在 Engine 內建立網路隔離。
