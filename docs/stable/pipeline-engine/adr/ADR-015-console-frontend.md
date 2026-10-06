# ADR-015 Console 前端為隨 Engine 發佈的 Svelte 靜態 SPA

狀態：已核可（2026-10-05）。補充 [ADR-012](ADR-012-api-authentication.md)（免認證端點清單新增靜態檔）。回答：Console 前端用什麼技術、如何建置與發佈、如何與 Engine 的路由、API 文件測試、12-Factor 與本機驗證共存，以及如何支援多語系。

## 背景

Engine 原本只有 HTTP API，沒有操作介面。使用者決定做一個給開發人員與管理員使用的 Console（設計稿：Superdesign 專案 "Runline Console"，風格見 `.superdesign/design-system.md`）。限制如下：

- 核可的 Bearer API 契約（[ADR-012](ADR-012-api-authentication.md)、[08-api](../08-api.md)）不得被破壞；Console 是 API 的一個呼叫端，不是新的契約來源。
- 部署拓撲是「一個 Engine process 加一個 PostgreSQL」，且 Engine 的產物形態受 [ADR-001](ADR-001-isolated-classloader.md) 約束（[04](../04-deployment.md)「打包形態」）。
- `ApiDocumentationTest` 要求每個實際註冊的路由都在 08-api.md 有對應標題與認證行，ADR-012 要求免認證端點須明列。
- 現況：Engine 範本遺留的根路徑端點已移除，`/` 回 404；Swagger UI 在 `/openapi`。

## 選項

| 選項 | 說明 | 結論 |
|---|---|---|
| A. Svelte 靜態 SPA，建置產物打包進 engine.jar，由 Engine 在 `/` 提供 | 單一產物、同源、不需 CORS | 採用 |
| B. 前端獨立部署（靜態主機或反向代理）| 前後端各自發佈 | 否決：多一個部署單元與一套環境組態，需 CORS，與「一個 Engine 加一個資料庫」的拓撲不符，v1 單團隊沒有分開擴縮的需求 |
| C. 把建置好的前端產物提交到版本庫 | 建置不需 Node | 否決：產物與原始碼易不同步、diff 不可讀、版本庫膨脹 |
| D. 伺服器端渲染（htmx 等）、Compose Wasm、Kobweb | 已由使用者比較並否決 | 不再評估 |

路由方式再比較：

| 項目 | History 路由（乾淨網址）+ 伺服器 fallback | Hash 路由（`#/…`）|
|---|---|---|
| 伺服器要求 | 非 API、非檔案的 GET 回傳入口頁 | 不需 fallback |
| 與 API 路由的衝突面 | 需明確界定 fallback 範圍，避免吞掉 API 的 404 | 無 |
| 網址品質 | 好 | 較差，但足夠 |

使用者已決定要 SPA 路由 fallback，本 ADR 採 history 路由；hash 路由是 fallback 範圍造成問題時的退路。

## 決策

**發佈形態**

- Console 是靜態 SPA（Svelte，Vite 建置），建置產物成為 engine.jar 的 resources，與 Engine 同一個產物、同一個版本、同一次發佈。部署拓撲不變。
- `engineDistribution` 的產物形態不變（`engine.jar` 加 `run-runtime/`）：Console 只存在於 `engine.jar`；`run-runtime/` 不得含 Console 資源。Engine 啟動檢查與 ADR-001 的隔離不受影響。
- Console 只用同源的相對路徑呼叫 `/api/v1`：不在建置時寫入任何 API 位址或環境相關值（12-Factor：同一個產物用於 dev／staging／prod）。Engine 不啟用 CORS。
- 資源路徑：入口頁在 `/`，雜湊命名的資源檔在固定前綴下。

**建置接線（行為層級）**

- 建置 Engine 時，Gradle 先執行前端建置，產物納入 `shadowJar`，因此 `engineDistribution`、`packagedTest` 與一般的發佈建置都自動含 Console。前端建置的輸入（原始碼、鎖定檔、建置設定）變動時才重建，沒有變動時不重跑，不拖慢後端迭代。
- 前端相依以鎖定檔固定版本，並以鎖定檔安裝（可重現）。Node 版本在單一處固定（比照 `mise.toml` 與 `gradle/libs.versions.toml` 的單一版本來源原則）。
- 提供「略過前端建置」的明確開關，僅供本機只改後端時使用；略過時 Engine 仍能啟動，`/` 回 404，API 不受影響。發佈用的建置（本機以明確的發佈旗標執行，見 [ADR-016](ADR-016-engine-build-info-endpoint.md)）與 `packagedTest` 遇到此開關時直接失敗，不靜默產出沒有 Console 的產物。
- 開發模式：前端用 Vite 開發伺服器並把 `/api` 代理到本機 Engine（含 WebSocket）；此代理只存在於開發環境，不進入產物。

**Engine 提供靜態檔與 fallback**

- 優先順序：`/api/**`、`/openapi/**` 先於靜態檔與 fallback；Console 不得遮蔽它們。
- `GET` 請求對應到實際存在的資源檔時回傳該檔；對應不到、且路徑不在 `/api` 與 `/openapi` 之下、且不像資源檔（副檔名）時，回傳入口頁（200）。
- `/api/**` 下不存在的路徑維持現行的 404（不得回傳入口頁）；非 GET 請求不適用 fallback。
- 快取：雜湊命名的資源檔可長期快取；入口頁不快取（確保新版發佈後立即生效）。
- 回應帶基本安全標頭，包含限制為同源的內容安全政策（不載入第三方腳本與字型，字型自行打包）、禁止被嵌入框架、`nosniff`。具體值由實作評估，須與 [ADR-017](ADR-017-console-websocket-and-token.md) 的 XSS 防護目標一致。
- 靜態檔與入口頁免認證（它們不含機密，資料一律經 Bearer API 取得），在 ADR-012 的免認證端點清單明列。

**與 ApiDocumentationTest 的關係**

- Console 的靜態檔路由是 `GET` 且免認證，會被「實際註冊的路由」列舉測試看見。處置原則：
  - 不得以「放行所有未記錄路由」的方式放寬測試；`/api/**` 下的任何路由仍必須逐一記錄。
  - Console 的靜態與 fallback 路由在 08-api.md 以獨立一節說明（與 `/openapi` 同屬「不屬於 API 契約」的端點，不保證穩定）；測試對這組路由的處置（逐一記錄，或以單一明確的掛載點視為一組）由 tdd-coder 選擇，但必須維持「新增 `/api` 路由未記錄即失敗」。
- 另須有測試保證：`/api/**` 與 `/openapi/**` 不被 fallback 遮蔽、無 token 的 Bearer 端點仍回 401。

**Node 建置鏈對 12-Factor 與本機驗證**

- Node 只在建置階段存在；執行環境（JDK 25 的 Engine）不含 Node，執行期沒有任何前端建置或 Node 相依。建置、發佈、執行三階段仍分離。
- 專案沒有 CI，也不預期近期導入；所有驗證都必須能在本機完成：前端建置、前端單元測試與型別檢查納入 Gradle `check`，單一指令即可驗證整個產物（含 `packagedTest`）。需要真實瀏覽器（Chrome）與執行中的打包後 Engine 的測試（端對端測試 `npm run e2e`、契約測試 `npm run test:contract`）不接進 `check`，是本機手動執行的腳本，使用方式寫在 README；涉及它們的工作項在回報中附上執行結果。不存在只在 CI 上才執行的步驟。
- 開發環境需要 Node：devcontainer 只有 JDK 25 與 PostgreSQL，需新增 Node（版本與鎖定檔一致）；本機非 devcontainer 的環境由單一版本來源（如 `mise.toml`）固定 Node 版本。
- 供應鏈：前端相依由鎖定檔固定；相依漏洞檢查以本機可執行的指令提供（納入發佈用的建置）；建置不得在執行階段下載相依。
- 日後導入 CI 時只需執行同一個 Gradle 入口，不需調整本決策。

**多語系（i18n）**

首版提供繁體中文（zh-TW）與英文（en），不預留其他語系的功能（翻譯檔結構本身可擴充）。

- 函式庫（已核可）：以 `intl-messageformat` 12.1.3 與 `@formatjs/icu-messageformat-parser` 3.5.21（鎖定版本）處理 ICU MessageFormat，其上由專案自己提供一層薄的、有型別的翻譯層（`t()`／`translate()`），畫面只呼叫這一層。須滿足：支援複數與參數插值、支援缺漏鍵的後備（英文）、翻譯檔隨 bundle 打包（不另行請求）、鎖定版本；鍵的型別與缺鍵檢查由薄層與本機建置提供。
  - 已否決：`svelte-i18n`（2024 年 10 月後無更新、以 store 為基礎、沒有型別化的鍵與建置期缺鍵檢查）；`typesafe-i18n`（不是 ICU）；Paraglide（自有訊息語法、工具鏈較重）。
  - 日後更換函式庫的影響限於薄層，畫面與翻譯鍵不變。
- 語系偵測與切換：
  - 順序：使用者先前的選擇 → 瀏覽器偏好語言 → 預設 zh-TW。瀏覽器偏好為 `zh` 系（含 zh-TW、zh-HK 等）對應 zh-TW，`en` 系對應 en，其他語言回退到預設。
  - 介面提供語言切換（登入前後皆可用）；切換立即生效，不需重新載入，也不需重新登入。
  - 語系屬使用者偏好，不是機密：保存在瀏覽器的 localStorage（與 token 的保存分開，token 不使用 localStorage，見 [ADR-017](ADR-017-console-websocket-and-token.md)），所有分頁共用；不保存於伺服器（Engine 沒有使用者偏好儲存，也不為此新增）。
  - `<html lang>` 隨語系更新。
- 翻譯檔結構：每個語系一份翻譯檔，以穩定的鍵（依畫面與功能分組、不以原文當鍵）為索引；zh-TW 與 en 的鍵集合必須一致，缺鍵在本機建置時失敗（不到執行期才發現）；英文為後備語系。專有名詞（run、pipeline、trigger、webhook、jar、hash、狀態與判定名稱）保留英文原詞，兩個語系的設計一致。
- API 錯誤的本地化：08-api 規定 `error` 代碼穩定、`message` 文字可能調整，因此：
  - Console 以 `error` 代碼（加上 HTTP 狀態與 `problems[]`、`problem` 等結構化欄位）對應到翻譯鍵，產生本地化訊息；`message` 不作為顯示依據。
  - 沒有對應翻譯的代碼，顯示通用的本地化錯誤訊息，並附上原始代碼（以及 500 的 `errorId`）供回報；`message` 只在「詳細資訊」中以原文顯示，標示為伺服器原文。
  - 本機驗證須涵蓋：08-api 記載的每個錯誤代碼在兩個語系都有對應翻譯（以 08-api 為單一事實來源檢查，與 ApiDocumentationTest 的精神一致）。
  - run 的狀態、判定（SAFE／UNSAFE）、`reasons[].kind` 等列舉值同樣以代碼對應翻譯，不顯示原始列舉字串（保留原詞者除外）。
  - pipeline 輸出的 log 與 pipeline 內容、失敗訊息的 `message`／`trace` 來自 pipeline，不翻譯。
- 日期與數字格式：Engine 一律回傳 ISO-8601 UTC 時間（08-api）；Console 依目前語系與瀏覽器時區以瀏覽器內建的國際化格式化功能顯示本地時間，並可在懸浮提示顯示 UTC 原值；相對時間（例如「3 分鐘前」）、持續時間、位元組大小與數字分隔符同樣依語系格式化；hash、runId、cron 表達式、類別名稱不格式化。

## 取捨

| 項目 | 優點 | 缺點 |
|---|---|---|
| 隨 Engine 發佈 | 單一產物；同源，無 CORS；版本與 API 永遠一致（UI 顯示的版本即 API 的版本）| 前端改版需重新發佈 Engine（重啟、進行中 run 中斷）；Engine 多承擔靜態檔流量（v1 單團隊可忽略）|
| History 路由 | 乾淨網址 | 需維護 fallback 範圍與其測試 |
| Node 進入建置鏈 | 前端生態與型別／建置工具成熟 | 建置多一個工具鏈；首次建置較慢；需鎖定與掃描相依 |
| 兩個語系首版同時提供 | 日後加語系不需改結構；錯誤與狀態以代碼對應，不受伺服器訊息文字調整影響 | 每個新增的畫面與 API 錯誤代碼都需兩份翻譯；需有鍵一致與錯誤代碼涵蓋的檢查 |
| 同源且不啟用 CORS | 攻擊面小 | 其他來源的前端無法直接呼叫（目前沒有需求）|

## 後果

- Engine 的 jar 變大（前端資源）；Engine 啟動、API 與 run 隔離不受影響。
- 前端改版與 Engine 改版同步，Console 不會遇到「版本不符的 API」；API 變更必須同時維護 Console。
- 管理員與開發人員的 Bearer token 會輸入到瀏覽器，傳輸機密性（TLS 終止於入口）從「建議」變成實質必要，見 [ADR-017](ADR-017-console-websocket-and-token.md) 與 [07](../07-nfr-risks.md)。
- ADR-012 的「免認證端點」清單擴充；其「新增端點預設需要認證」原則不變。
- 實作拆為 [WI-30](../work-items/WI-30-node-toolchain.md)（Node 工具鏈）、[WI-31](../work-items/WI-31-frontend-build-and-static-serving.md)（建置接線與靜態檔）與 [WI-33](../work-items/WI-33-console-shell-and-i18n.md)（骨架與多語系）。
