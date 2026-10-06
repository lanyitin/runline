# WI-06 上傳 API、探索與儲存

目標：開發人員上傳 jar，Engine 探索 pipeline、判定安全並保存版本。

狀態：已核可（2026-10-04）。相依：WI-05、WI-16。

## 行為與驗收條件
- 需認證才能上傳：呼叫端提供開發人員或管理員的 token（[ADR-012](../adr/ADR-012-api-authentication.md)）；未認證回應未認證，不上傳；記錄上傳者為 token 對應的名稱。
- 上傳成功回傳探索到的 pipeline、metadata、safe／unsafe、原因與依賴路徑，以及判定所用的白名單版本。
- 新版本的 unsafe 執行設定一律為不允許，不繼承前一版。
- 同一內容重複上傳回傳同一版本，不產生重複紀錄（冪等）。
- Artifact 與其 definition 於同一交易寫入；失敗時不留下部分資料。
- 無效 jar（包含不是有效壓縮檔）、找不到任何 pipeline、metadata 無法解析時，回傳可理解的錯誤。
- jar 內含 `dev.lawlan.runline.core` 套件的類別時拒絕上傳，錯誤說明須指出原因（run 使用 Runner 提供的 core，不接受夾帶的版本）與作者的處置（以不夾帶 core 的方式打包）；不留下任何資料。
- 探索不執行 pipeline 邏輯。
- 被引用的 artifact 不可刪除。
- 管理員可查詢 definition 清單與判定；開發人員預設只可查詢自己上傳的結果。
- 判定使用 analyzer 模組的同一份分析，不得另寫判定邏輯。
- 判定所需的白名單，在 [WI-10](WI-10-allowlist-admin.md) 完成前由組態提供，版本標示為組態來源；WI-10 完成後改由管理 API 的內容為準。白名單來源以可替換的方式提供，使 WI-10 不需改動上傳流程。
- Token 不出現在 log、trace 或錯誤訊息中；上傳有 log、trace 與 metric（數量、結果、判定分布）。

## 架構約束
[ADR-003](../adr/ADR-003-jar-and-discovery.md)、[ADR-012](../adr/ADR-012-api-authentication.md)、[06](../06-data-model.md)。Artifact 先存於 PostgreSQL。Engine 與 run 的邊界只傳 JDK 內建型別（[05](../05-ipc.md)）。分析來自 analyzer 模組，Engine 不複製其邏輯。

## 待確認問題
- 組態提供的過渡白名單預設內容。影響：預設為空時，所有 Kotlin pipeline 都會被判 unsafe，需管理員逐一允許；預設內容受 [WI-10](WI-10-allowlist-admin.md) 的第一項待確認問題限制，定案前不列入提供 IO 能力的套件。
