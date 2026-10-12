# WI-1403 pipeline 專案骨架

狀態：已核可（2026-10-11），未開始。儲存庫：T（建立 `runline-transcribe`，與 runline 同層，git 儲存庫，composite build 取得 devkit，沿用 `runline-digest-rss` 的專案慣例）。相依：無。決策見 [ADR-037](../adr/ADR-037-unsafe-pipeline-and-intake-root.md)。

## 驗收條件

1. 專案可建置出 pipeline jar，並能在 devkit 與 Engine 上傳；上傳判定為 unsafe。
2. 宣告的參數涵蓋 [01](../01-requirements.md) 的參數表（名稱與預設值於此項定稿並寫入專案文件）；缺少必填參數時由平台拒絕。
3. 宣告兩個資源角色（LLM：`openai-compatible`；資料庫：`jdbc-pool`），並註記用途，使 Console 可綁定。
4. 讀取 `TRANSCRIBE_INTAKE_ROOT`；缺少或不可用時 run 在初始階段失敗，訊息指出缺哪一項。
5. 檔名檢查：接受收件根目錄下的一般檔案（含子目錄）；拒絕空值、絕對路徑、含 `..` 的路徑、解析符號連結後落在根目錄外、目錄、不存在、不可讀；每種拒絕有明確原因分類，訊息不含根目錄的絕對路徑；以真實檔案系統與真實符號連結測試。
6. 階段 log：每個階段有明確的開始、結束與計數；取消時各階段在合理時間內停止並清理自己啟動的行程與工作目錄。
7. 每個 run 開始時清理屬於本 pipeline、已失去擁有者的工作目錄。
8. 結果語意：成功、含降級的成功、失敗各有明確定義並寫入 log；降級計數的意義見 [05](../05-ipc.md)。

## 架構約束

只用 JDK、core、Kotlin 標準函式庫；不引入第三方函式庫；機密不進 log。
