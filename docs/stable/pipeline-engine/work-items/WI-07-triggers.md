# WI-07 Trigger 綁定、cron 與 webhook

目標：管理員綁定 trigger，Engine 在時間到或收到 webhook 時建立 run。

狀態：已核可（2026-10-04）。相依：WI-06、WI-08。

## 行為與驗收條件

### 管理
- 管理員可建立、停用、啟用、查詢、修改與刪除 cron 與 webhook trigger，並綁定到特定版本的 pipeline 定義。管理 API 僅管理員可用（[ADR-012](../adr/ADR-012-api-authentication.md)）；未認證與權限不足的回應語意與既有 API 一致。
- 建立或修改 trigger 時帶入一組固定參數，依該定義的 metadata 驗證：缺少必填、提供未宣告的參數時拒絕並指出是哪個參數；未提供的選填參數套用預設值。
- 更換綁定的定義版本時重新驗證參數；不通過則拒絕變更。
- 綁定不跟隨最新版；綁定的定義被刪除前須先解除綁定（被 trigger 引用的 artifact 不可刪除）。
- 查詢回應不含 webhook 密鑰的任何形式，只顯示是否已設定與最近一次輪替時間。

### Cron
- Cron 以標準五欄表達式描述；每個 trigger 可指定 IANA 時區，預設 UTC。表達式或時區無效時建立被拒絕並指出原因。
- Cron 在排程時間建立 run，同一時刻不重複建立。
- Engine 重啟後已啟用的 cron trigger 自動恢復，錯過的時間不補跑。
- 停用的 trigger 不觸發。

### Webhook
- 建立 webhook trigger 時，Engine 隨機產生密鑰並只在該次回應中回傳一次；資料庫只保存其雜湊。
- 管理員可輪替密鑰：產生新密鑰並只回傳一次，舊密鑰立即失效。
- Webhook 入口以專用標頭接受密鑰，比對抵禦時間差；驗證失敗不建立 run、不洩漏密鑰、不透露 trigger 是否存在，且回應語意與其他驗證失敗一致。
- 呼叫端必須提供 delivery 識別碼；缺少時拒絕。
- 驗證成功即回 accepted，run 非同步執行；accepted 回應不含任何內部細節。
- 同一 trigger 內重複的 delivery 識別碼只建立一個 run，重複請求得到與第一次相同語意的 accepted 回應。
- Webhook 請求的內容不影響 run 的參數；本項不解析請求本文。
- 停用的 trigger 對 webhook 呼叫的回應與驗證失敗相同，且不建立 run。

### 觸發時的建立
- Cron 與 webhook 觸發都經由 WI-08 的單一建立流程，記錄觸發來源為該 trigger；不得各自實作一套。
- 建立被拒絕（unsafe 設定不允許、參數無效、資源未定義或已停用、定義不存在）時不建立 run；記錄 log 與 metric（含拒絕原因與 trigger），trigger 保持啟用，下一次觸發仍會嘗試。Webhook 在驗證成功後仍回 accepted，拒絕原因只記錄於 log、metric 與管理員可查詢的觸發紀錄。
- 管理員可查詢每個 trigger 最近的觸發紀錄：時間、結果（已建立 run 或被拒絕及原因）、對應的 run 識別碼。

### 觀測與機密
- Per-trigger 密鑰與 API token 不出現在 log、trace 或錯誤訊息中。
- 觸發、拒絕、去重、驗證失敗都有 log 與 metric；run 的 trace 含 trigger 來源。

## 架構約束
[ADR-005](../adr/ADR-005-trigger-binding.md)、[ADR-012](../adr/ADR-012-api-authentication.md)；v1 單實例。Unsafe 判斷交由 WI-08 的建立流程。

- Trigger 的資料表必須以「拒絕刪除」的方式參照 pipeline 定義，使被 trigger 引用的 artifact 無法刪除（[06](../06-data-model.md)）；需有測試以真實資料庫驗證刪除被拒絕。
- Delivery 識別碼去重由資料庫的唯一性保證（同一 trigger 內唯一），並在並行重送下仍只建立一個 run，需有測試驗證。
- 密鑰以高熵隨機值產生，資料庫只存雜湊；不得以可逆方式保存。
- Cron 排程器使用可控制的時鐘，使時間相關行為可在測試中確定地驗證，不以睡眠等待。
- Cron 表達式解析使用成熟且仍在維護的函式庫，不自行實作解析；函式庫的選擇、版本與授權由實作評估後在回報中說明，並須與專案現行的 JDK 與 Kotlin 版本相容。
- 資料庫遷移沿用既有的獨立一次性機制，Engine 啟動只檢查版本。
- 除上述 cron 解析函式庫外不新增外部相依；不新增 git hook 或 CI；完成程式碼變更時依專案規則先以 ktfmt 格式化。
