# ADR-016 Engine 版本與 commit hash 的公開端點與建置注入

狀態：已核可（2026-10-05）。補充 [ADR-012](ADR-012-api-authentication.md)（免認證端點清單新增一項）。回答：Console 每頁都要顯示的 Engine 版本與 commit hash 由哪個端點提供、哪些欄位公開、建置時如何取得 hash、工作樹有未提交變更時如何表示，以及發佈產物如何位元組級可重現。

## 背景

設計規範要求每個畫面（含登入頁）持續顯示 Engine 的版本與 commit hash，並可展開看完整 hash、建置時間、JDK、運行時間、白名單版本；介面也要顯示呼叫者的名稱與角色。現況：

- ADR-012 規定新增端點預設需 Bearer 認證，免認證須在 ADR 明列。
- 登入頁在使用者提供 token 之前就要顯示版本與 hash，因此至少有一部分資訊必須免認證。
- 現有 API 沒有回答「這個 token 是誰、什麼角色」的端點；Console 的角色徽章與管理頁導覽需要它。
- 專案版本由根建置檔單一處宣告（`1.0.0-SNAPSHOT`）。

## 選項

端點切分：

| 選項 | 說明 | 結論 |
|---|---|---|
| A. 單一端點，整個需 Bearer | 最符合 ADR-012 預設 | 否決：登入頁無法顯示版本與 hash，違反硬需求 |
| B. 單一端點，全部公開 | 簡單 | 否決：把 JDK、運行時間、白名單版本也公開，資訊超出必要；呼叫者身分也無法放入 |
| C. 單一端點，認證可有可無，依有無 token 回不同欄位 | 路徑只有一個 | 否決：同一路徑兩種認證語意，ApiDocumentationTest 與 ADR-012 的「明列免認證」都無法乾淨表達，且容易在重構時意外暴露 |
| D. 兩個端點：公開的最小資訊，加需 Bearer 的詳細資訊 | 認證語意各自單純 | 採用 |

建置時取得 hash 的方式：

| 選項 | 結論 |
|---|---|
| 建置時由 git 取得並寫入產物內的資源（產物自己知道自己是哪個 commit）| 採用：產物與 hash 不可分離，符合建置／發佈／執行分離 |
| 部署時以環境變數提供 | 否決：hash 與實際執行的 jar 可能不一致，等於信任部署者填對 |
| 執行時執行 git | 否決：執行環境沒有版本庫 |

## 決策

**端點與認證**

- `GET /api/v1/info`：免認證（ADR-012 清單新增此項），公開完整 commit hash 已由使用者確認。回傳最小集合：
  - `version`：Engine 版本（與專案版本同源）。
  - `commitHash`：完整 40 字元 hash，或 `unknown`（見下）。Console 自行取前 7 碼顯示短 hash。
  - `dirty`：建置當下工作樹是否有未提交變更（布林）。
- `GET /api/v1/system`：Bearer（developer；管理員包含）。回傳：
  - `buildTime`（此版本 commit 的時間，ISO-8601 UTC；不是建置當下的時鐘）、`jdk`（執行環境的 JDK 版本）、`startedAt` 與 `uptimeSeconds`、`allowListVersion`（目前生效的白名單版本，文字；與上傳判定中的同名欄位同義）。
  - `caller`：`name`（token 對應的名稱）與 `role`（`developer` 或 `admin`），供 Console 辨識身分與角色。
  - 另含 `version`、`commitHash`、`dirty`，使 Console 一次取得全部、不需合併兩個回應。
- 登入流程以 `GET /api/v1/system` 驗證 token：401 即 token 無效；成功即取得身分與角色。不新增專用的登入端點，也不在伺服器端保存登入狀態（維持 ADR-012 的無狀態 Bearer）。
- 「API base URL」不由伺服器提供：Console 與 Engine 同源，由瀏覽器自己知道。

**公開與否的判斷**

| 欄位 | 公開 | 理由 |
|---|---|---|
| version、commitHash、dirty | 是 | 登入頁硬需求；內部工具，資訊價值低。代價：可協助對方比對已知漏洞版本（接受，見取捨）|
| buildTime | 否（需登入）| 非登入頁需求；對辨識版本無額外幫助 |
| jdk、uptime／startedAt | 否 | 運行環境細節，可用於探測與判斷重啟時間 |
| allowListVersion | 否 | 屬分析設定資訊；已在登入後的定義資料中出現 |
| caller（名稱、角色）| 否（本質上需 token）| 只能對有效 token 回答 |

- 公開端點不查詢資料庫、不依賴外部系統、回應固定且極輕量，且不得因資料庫故障而失敗；回應標示不快取。
- 兩個端點都不得回傳 token、環境變數、主機名稱、路徑、依賴清單或任何組態值。

**建置注入**

- 建置時取得 git 的完整 HEAD hash、工作樹是否有變更、HEAD 的 commit 時間，寫入 engine.jar 內的建置資訊資源；版本取自專案版本的單一來源。Engine 啟動時讀取，`startedAt` 在啟動時決定。
- `buildTime` 的值是 HEAD 的 commit 時間（ISO-8601 UTC），不是建置當下的時鐘時間：同一 commit 在任何時間、任何機器建置，值都相同。欄位名稱維持 `buildTime`，語意為「此 commit 的時間」，08-api 明寫。
- 「有變更」的定義：已追蹤檔案有未提交的修改，或有未被忽略的未追蹤檔案；被版本控制忽略的建置產物不算。
- 工作樹有變更時，`commitHash` 仍是 HEAD 的完整 hash，另以 `dirty` 為 true 表示；Console 顯示為「短 hash 加 dirty 標記」（例如 `a3f9c1e` 後接 dirty 標記）。不把 `-dirty` 字樣混進 hash 欄位，使欄位維持純 hash，機器可比對。
- 取不到 git 資訊（沒有版本庫、尚無 commit、淺層複製異常）時，`commitHash` 為 `unknown`、`dirty` 為 true、`buildTime` 為固定的常數時間（無法取得 commit 時間時不使用時鐘）。一般開發建置容許此情況；發佈用的建置（以明確的發佈旗標在本機執行）遇到 `unknown` 或 dirty 時失敗，確保部署上線的產物都能追溯到 commit。
- 建置資訊的產生不得讓增量建置與建置快取失效到每次都重打包：HEAD 或工作樹狀態改變時才產生新內容，內容相同時下游視為未變。

**位元組級可重現**

需求：同一個 commit、乾淨工作樹，在固定的建置平台上做發佈建置，產出的 `engine.jar`（以及 `engineDistribution` 的各 jar）逐位元組相同，並以 SHA-256 相同作為驗收。

- 建置資訊：只含 commit 衍生的值（hash、commit 時間、版本、dirty 旗標）；不含建置當下的時鐘、主機名稱、使用者名稱、絕對路徑或環境變數。
- 封裝：所有 jar 的封裝以可重現的方式進行：項目順序固定、項目時間戳記固定（取自 commit 時間或固定常數）、檔案權限固定、不含建置者資訊；shadow 合併的資源（服務宣告、manifest）順序與內容同樣固定。這是對建置的行為要求，具體的 Gradle 設定由 tdd-coder 決定。
- 前端 bundle：前端建置的輸出須是確定性的：相依以鎖定檔精確安裝、Node 與建置工具版本固定、輸出檔名的雜湊只由內容決定、不寫入建置時間或絕對路徑、輸出順序固定。前端產物進入 jar 時不經過會改變內容的步驟（時間戳記以固定值覆寫）。
- 固定平台：可重現的發佈 jar 只在固定的建置平台（例如固定的容器映像加固定的 Node 與 JDK 版本）上建置；跨平台（不同作業系統或 CPU 架構）的位元組一致為盡力而為，不保證、不作為驗收。
- 驗證：只在發佈建置執行，不納入日常 `check`。本機提供單一 Gradle 入口，在固定平台上連續兩次從乾淨狀態（清除建置輸出與前端快取）發佈建置並比對 SHA-256。沒有 CI，所以此驗證不得依賴 CI。
- 髒工作樹：不保證可重現。`dirty` 為 true 的建置，其 `buildTime` 仍取 HEAD 的 commit 時間（避免引入時鐘），但產物內容含未提交的變更，不能由 commit 重現，因此標示 `dirty`，且不得作為發佈產物（發佈建置失敗）。開發用的 dirty 建置仍可執行與測試。
- 版本字串：發佈建置的專案版本不得含建置時間或序號（例如不使用以時間產生的快照後綴）。

## 取捨

| 項目 | 優點 | 缺點 |
|---|---|---|
| 兩個端點 | 每個端點的認證語意單純，易測、易在 ADR 明列 | 多一個端點與一份文件 |
| 公開 version／hash | 登入頁可顯示，也方便維運確認「部署的是哪版」| 對外洩漏版本資訊；緩解：對外暴露由入口層控管，且 v1 為內部工具 |
| `dirty` 獨立欄位 | 機器可讀、不污染 hash | Console 需自行組合顯示 |
| 建置期寫入產物 | hash 與 jar 一致，無需執行期 git | 本機未提交的建置也會帶 hash，須靠 `dirty` 辨識 |
| `buildTime` 取 commit 時間 | 位元組級可重現；同一 commit 值固定 | 不反映實際建置的時間點（兩者可能相差很久）；欄位語意需在文件明寫 |
| 位元組級可重現 | 任何人可從 commit 重建並以雜湊驗證產物，供應鏈可稽核 | 需約束封裝與前端工具鏈；工具鏈跨平台差異需驗證；驗證需兩次乾淨建置，較慢 |

## 後果

- Console 的 Engine 晶片：登入前用公開端點顯示版本與 hash；登入後改用 `GET /api/v1/system` 顯示完整資訊與運行時間。連線狀態點由「最近一次呼叫是否成功」判斷，不另設心跳端點。
- 端點不是健康檢查：存活與就緒探測由 [ADR-018](ADR-018-liveness-readiness-probes.md) 決定，`/info` 不用於探測。
- 公開端點不得成為攻擊放大點：速率限制仍由部署層負責（與 webhook 相同的處置，見 [07](../07-nfr-risks.md)）。
- 登入失敗與 token 無效沿用既有的 401 規則（`WWW-Authenticate`、無 JSON 本文）。
- 測試：公開端點無 token 可存取；`/system` 無 token 回 401；回應不含組態值；以 `packagedTest` 驗證打包後的 Engine 回傳的 hash 與建置時的 HEAD 相同。
- 實作見 [WI-28](../work-items/WI-28-build-info-and-system-endpoints.md)（建置資訊與端點）與 [WI-32](../work-items/WI-32-reproducible-release-build.md)（可重現發佈建置）。
