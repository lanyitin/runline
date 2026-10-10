# WI-1015 開發腳本 `dev/dev.sh` 提供資源根目錄

本文回答：為什麼 `dev/verify-console-security.sh` 在乾淨環境啟動 Engine 失敗，以及修正要達成什麼。狀態：已核可（2026-10-08）。

背景：Engine 要求設定資源根目錄（`RUNLINE_RESOURCE_ROOT`），但 `dev/dev.sh` 沒有提供，因此 `dev/verify-console-security.sh` 照現行寫法無法啟動 Engine；使用者必須事先自行匯出該環境變數才能通過。這在 WI-1003 的真實瀏覽器驗證中被發現，與 sqlite 無關。工作儲存庫：runline。

## 驗收條件

1. 在沒有任何額外環境變數的乾淨 shell 中，`dev/dev.sh` 能啟動 Engine，資源根目錄指向專案內被版本控制忽略的本機目錄（或每次建立的暫存目錄），不寫入使用者的家目錄以外的位置，也不寫死個人路徑。
2. 使用者已匯出 `RUNLINE_RESOURCE_ROOT` 時，以使用者的值為準。
3. `dev/verify-console-security.sh` 在乾淨 shell 中通過（安全腳本 15 項與鎖檔檢查），不需事先設定環境變數。
4. 文件（開發環境與 e2e README）說明資源根目錄的預設與覆寫方式，與腳本一致。

## 架構約束

- 只修改開發腳本與文件，不改 Engine 的組態契約（`resources.root` 仍為必填）。
- 驗證皆可本機完成。
