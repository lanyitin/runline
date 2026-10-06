# WI-11 Pipeline 共享目錄與 run 私有目錄

目標：Engine 為每個 pipeline 與每個 run 備妥目錄，並管理其生命週期。

## 行為與驗收條件
- Run 的初始化階段備妥該 pipeline 的共享目錄與該 run 的私有目錄；不存在則建立。
- 同一 pipeline 的不同 run 看到同一個共享目錄；不同 pipeline 之間互相看不到對方的共享目錄。
- 一個 run 的私有目錄，其他 run 無法存取。
- Run 結束後，私有目錄依保留政策清除：成功的 run 立即清除；失敗、取消、中斷的 run 保留一段由 Engine 組態決定的時間。
- Engine 重啟後，共享目錄內容仍在；殘留的私有目錄依保留政策清理。
- 管理員可查詢每個 pipeline 共享目錄的用量，並可清空它。
- 磁碟用量上限由 Engine 組態決定；超出時，寫入被拒絕並產生清楚的錯誤。
- 目錄的根位置由環境變數提供。
- 目錄建立、清除、用量都有 log 與 metric。

## 架構約束
[ADR-009](../adr/ADR-009-file-scopes.md)。共享目錄所在位置必須是持久儲存；私有目錄可為暫存。Pipeline 被判為「同名新版本」時沿用同名 pipeline 的共享目錄（是否如此待確認，[01](../01-requirements.md) Q2）。
