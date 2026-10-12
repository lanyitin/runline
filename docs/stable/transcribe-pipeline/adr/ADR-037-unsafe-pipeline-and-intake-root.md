# ADR-037 transcribe 以 unsafe pipeline 呼叫主機行程，並由部署設定指定收件根目錄

狀態：已核可（2026-10-11）。回答：影片檔如何被讀取、外部程式放在哪裡、信任邊界在哪。

## 背景

影片位於 context 的檔案範圍之外；ffmpeg 與 VAD 工具需要絕對路徑；`file` 資源只對應單一檔案。

## 決策

1. pipeline 為 unsafe，由管理員逐版本允許（沿用 [ADR-006](../../pipeline-engine/adr/ADR-006-unsafe-policy.md)、digest-rss 的 ADR-022 模式）。pipeline 為薄 jar，只用 JDK、core 與 Kotlin 標準函式庫。
2. 收件根目錄來自環境變數 `TRANSCRIBE_INTAKE_ROOT`，不是 pipeline 參數，故手動 run 與 webhook 都無法改變它。
3. 參數 `file` 必須是相對路徑。解析後（含符號連結）必須在收件根目錄之內、是一般檔案、可讀；否則 run 在初始階段失敗，原因分類為檔名不合法、不存在或不可讀，訊息不含根目錄的絕對路徑。
4. ffmpeg、ffprobe 與 VAD 工具（獨立 JVM 程式，ONNX Runtime 在它自己的行程內，不進 pipeline jar；理由見 [03](../03-options.md) 3.3）以行程啟動，參數逐一傳遞，不經 shell；run 取消、逾時、結束時終止自己啟動的行程並刪除工作目錄；每個 run 開始時清理屬於本 pipeline 且失去擁有者的工作目錄。
5. LLM 與資料庫走 Engine 資源，不持有機密。

## 取捨

- 得到：不新增資源型別；範圍由管理員的部署決定。
- 失去：路徑範圍的強制由 pipeline 自己負責（不受 Engine 的檔案範圍保護），因此路徑檢查是驗收重點；主機需有 ffmpeg 與 VAD 工具。

## 後果

部署需求見 [04](../04-deployment.md)；工作項 [WI-1402](../work-items/WI-1402-host-runtime-deployment.md)、[WI-1403](../work-items/WI-1403-pipeline-skeleton.md)。
