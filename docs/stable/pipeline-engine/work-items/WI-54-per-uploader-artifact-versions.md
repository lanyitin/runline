# WI-54 相同位元組的 jar 由不同上傳者各自成為版本

本文回答：兩位開發人員上傳位元組完全相同的 jar 時，如何讓每位都得到自己可見、可用的版本，且不洩漏他人是否上傳過。狀態：2026-10-07 納入，已核可（決策見 ADR-020），其「待使用者確認」所列採推薦值，待使用者確認。相依：WI-06、WI-07、WI-08、WI-10、WI-18、WI-35、WI-36、WI-40。決策見 [ADR-020](../adr/ADR-020-per-uploader-artifact-versions.md)；建議在 WI-49 之前完成（WI-49 的「宣告者」顯示會帶出 `uploader`；WI-49 已完成時順帶調整）。

## 背景

現行版本以內容雜湊唯一識別；相同位元組由第二位上傳時回 200 並指向第一位的版本，第二位看不到也不能用，且回應洩漏「有人上傳過」。ADR-020 改為版本以（內容雜湊，上傳者）識別。

## 行為與驗收條件

**上傳**
- 同一上傳者重複上傳相同位元組：200，回傳其自己的既有版本，不產生新紀錄、不改變任何既有資料。
- 不同上傳者上傳相同位元組：201，建立屬於該上傳者的新版本（含自己的 definition、判定、unsafe 設定預設不允許）；回應只含呼叫者自己的版本，欄位與全新上傳的回應無法區分（同樣的欄位與值型態，`uploadedBy` 為呼叫者）。
- 兩位上傳者並行上傳相同位元組，各得到自己的版本，不產生重複、不互相失敗；同一上傳者並行重複上傳只產生一個版本（其餘回 200）。
- 解壓上限、`not_a_jar` 等 413 與 422 拒絕對所有人一致，不因他人是否上傳過而不同；拒絕時不留下任何資料。
- 第二位上傳與全新上傳走相同的檢查與分析路徑，判定依目前白名單；不複製他人版本的判定。

**可見與不洩漏**
- 開發人員只看到自己的版本與 definition：`GET /artifacts/{contentHash}`、`GET /definitions`、建立 run 都只作用於自己的版本。他人的版本與不存在的回應位元組級相同（404 `not_found`、`definition_not_found`）。
- 開發人員的任何回應、錯誤、log 與 trace 輸出都不含「相同內容已有他人版本」的資訊；沒有只在他人已上傳時才出現的狀態碼、欄位或訊息。
- 開發人員給 `uploader`：等於自己名稱時接受，其他值回與不存在相同的 404；開發人員永遠不會收到 `ambiguous_version`。
- 以測試證明：甲上傳後，乙上傳同位元組與乙上傳不同位元組，兩者的回應結構與狀態碼相同；乙查詢、建立 run 時看不到甲的資料。

**管理員與消歧義**
- 管理員可見所有版本；`GET /definitions` 對同雜湊的多個版本各列一筆，每筆帶 `uploader`。
- 選填的 `uploader`：`GET`／`DELETE /artifacts/{contentHash}`、`PUT /definitions/{contentHash}/{pipeline}/unsafe-execution` 以查詢參數；建立 run、建立與修改 trigger 以本文欄位（與 `contentHash` 並列）。
- 可見版本恰好一個時 `uploader` 可省略，行為與既有完全相同（既有呼叫端與既有資料不受影響）。可見版本有多個而未給 `uploader`：409 `ambiguous_version`，本文含 `uploaders[]`，什麼都沒改變。給了 `uploader` 而沒有該版本：與不存在相同的 404。管理員解析不偏好自己的版本。
- 回應帶出 `uploader`：artifact、definition 列表（含 `uploadedBy` 維持）、run、trigger、白名單變更 `impact.changes[]`、資源 `declaredBy.definitions[]`。
- unsafe 設定逐版本獨立：核准甲的版本不改變乙的版本；新版本預設不允許；run 與 trigger 使用其綁定版本自己的設定。

**儲存、刪除與 trigger**
- 相同位元組在資料庫只存一份；各版本以內容雜湊參照。大小是內容的屬性。
- 刪除（管理員）以版本為單位：甲的版本被 trigger 或 run 引用回 409 `in_use`，不阻擋乙的版本被刪除；刪除一個版本不影響同雜湊的其他版本、其 definition、trigger 與 run。最後一個參照者被刪除時，位元組在同一交易內移除；並行上傳與刪除下不出現版本指向不存在的位元組。
- Trigger 綁定到特定版本內的 definition，不隨新版本或同雜湊的其他版本移動；既有 trigger 與 run 的參照在遷移後原樣有效。

**白名單與重判**
- 白名單變更與手動重判逐版本更新判定與 `allowListVersion`，同一交易、同一把鎖，失敗時全部維持原狀。同雜湊的各版本在同一次重判後判定一致。
- 影響摘要與 `changes[]` 以版本與其 definition 計；`changes[]` 每個受影響的版本各一筆並含 `uploader`；「收回允許 unsafe 執行」逐版本判斷與回報。預覽不寫入。

**遷移**
- 既有每筆 artifact 成為其雜湊的唯一版本：既有 definition、trigger、run、白名單判定與 unsafe 設定的內容與參照完全不變；位元組移到以內容雜湊為鍵的單一儲存，遷移後既有 jar 逐位元組可取回且雜湊相符。
- 遷移為單向前進、獨立步驟；Engine 啟動時 schema 非最新仍失敗（沿用既有規則）。以含既有資料（含被 trigger 與 run 引用者）的真實資料庫驗證遷移前後行為一致。

**Console**
- Pipelines 列表：同雜湊多版本時各列一筆並顯示上傳者（管理員）；開發人員的畫面不變。詳細頁依所選版本顯示（帶 `uploader`）。
- 上傳結果：201 顯示新版本；200 的訊息改為「你已上傳過相同內容」（只表示自己的版本），不暗示其他人；兩種語系皆更新。
- 管理員的建立 run、建立與修改 trigger、unsafe 設定、刪除、白名單變更影響清單：帶 `uploader`；收到 `ambiguous_version` 時以清楚的訊息要求選擇版本，不自動猜測。
- 所有文字有 zh-TW 與 en；上傳者名稱與 pipeline 字串以 WI-33 的純文字機制顯示。

**文件**
- 本項實作時同步更新 [06](../06-data-model.md)（Pipeline Artifact 與內容、一致性、生命週期）與 [08-api](../08-api.md)（上傳 200／201 語意、`uploader`、`ambiguous_version`、各回應的 `uploader`）；`ApiDocumentationTest` 通過。尚未實作的內容不預先列入。

**驗收方式**
- 測試使用真實 PostgreSQL（Testcontainers）與真實編譯的 jar，不使用 Stub 或 Mock；涵蓋上述各項，含並行上傳、遷移前後比對、白名單重判跨多版本、刪除與 `in_use` 的逐版本語意、開發人員與管理員的可見範圍與不洩漏。
- Console 以前端單元測試涵蓋訊息與 `ambiguous_version` 的處理；真實瀏覽器腳本（手動執行，不接進 Gradle `check`）走：甲乙上傳相同 jar → 乙建立 run → 管理員在列表看到兩版本並以 `uploader` 建立 trigger，並在回報附上執行結果。
- 全部既有測試通過、既有測試數量不減少；建置與 `ktfmtCheck` 通過。

## 架構約束

- 不破壞既有 `contentHash` 路徑與請求；`uploader` 只能是選填的新增。開發人員的 API 契約不變。
- 擁有權只以 token 名稱比對（[ADR-012](../adr/ADR-012-api-authentication.md)）；認證與角色語意不變。
- 判定與 unsafe 設定逐版本獨立，不繼承、不共享（[ADR-006](../adr/ADR-006-unsafe-policy.md)）；不得以他人版本的判定取代自己的分析。
- 任何對開發人員的回應不得透露他人版本的存在（包含狀態碼、欄位、錯誤代碼、訊息）。
- 被 trigger 或 run 引用的版本不得刪除（`in_use`），所有參照 definition 的表維持「拒絕刪除」的參照方式。
- 遷移單向前進，不在啟動時自動執行；機密與 token 不得出現在 log、trace 或錯誤訊息。
- 不新增 CI；完成程式碼變更時依專案規則先以 ktfmt 格式化；嚴格 TDD。
